using LucyRemota.Core.Commands;
using LucyRemota.Core.Link;

namespace LucyRemota.UI;

/// <summary>
/// Ventana de Lucy Remota (Receptor): estado de la conexión, vincular/desvincular teléfonos y las
/// órdenes recibidas. Al cerrarla queda en la bandeja; «Salir» la cierra del todo.
/// </summary>
sealed class MainForm : Form
{
    readonly PcLink link;
    readonly IPcStore store;
    readonly History history;
    readonly Label status = Theme.Label("", new Font("Segoe UI Semibold", 11f));
    readonly ListView phones = Theme.List(("Teléfono", 220), ("Estado", 200), ("Última conexión", 170));
    readonly ListView orders = Theme.List(("Hora", 80), ("Orden", 190), ("Estado", 110), ("Detalle", 380));
    readonly NotifyIcon tray;
    readonly bool startHidden;
    bool exiting;
    PairDialog? pairDialog;

    static readonly Dictionary<string, string> Friendly = new()
    {
        ["SET_VOLUME"] = "Volumen", ["MUTE_AUDIO"] = "Silencio", ["GET_VOLUME"] = "Consultar volumen",
        ["MEDIA_PLAY"] = "Poner música", ["MEDIA_PAUSE"] = "Pausar", ["MEDIA_RESUME"] = "Seguir música", ["MEDIA_NEXT"] = "Siguiente canción",
        ["MEDIA_PREVIOUS"] = "Canción anterior", ["MEDIA_NOW_PLAYING"] = "Qué suena", ["OPEN_APPLICATION"] = "Abrir programa",
        ["CLOSE_APPLICATION"] = "Cerrar programa", ["FOCUS_APPLICATION"] = "Mostrar programa", ["LIST_APPLICATIONS"] = "Lista de programas",
        ["OPEN_SYSTEM_PAGE"] = "Abrir", ["LOCK_SESSION"] = "Bloquear", ["SCREEN_OFF"] = "Apagar pantalla", ["SET_BRIGHTNESS"] = "Brillo",
        ["MINIMIZE_WINDOWS"] = "Minimizar todo", ["RESTORE_WINDOWS"] = "Mostrar ventanas", ["SLEEP"] = "Suspender", ["SHUTDOWN"] = "Apagar",
        ["RESTART"] = "Reiniciar", ["CANCEL_SHUTDOWN"] = "Cancelar apagado", ["GET_TIME"] = "Hora", ["GET_SYSTEM_INFO"] = "Estado de la PC",
        ["TAKE_SCREENSHOT"] = "Captura", ["WEB_SEARCH"] = "Buscar en Google",
    };

    static readonly Dictionary<string, string> StatusText = new()
    {
        [Status.Success] = "Hecho", [Status.Error] = "Error", [Status.Unsupported] = "No disponible", [Status.Invalid] = "Inválida",
        [Status.Expired] = "Llegó tarde", [Status.NeedsConfirmation] = "Pidió confirmar", [Status.Unauthorized] = "No autorizado", [Status.Busy] = "Ocupada",
    };

    public MainForm(PcLink link, IPcStore store, History history, bool startHidden)
    {
        this.link = link;
        this.store = store;
        this.history = history;
        this.startHidden = startHidden;
        Text = "Lucy Remota · Receptor";
        Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? SystemIcons.Application;
        BackColor = Theme.Back;
        ForeColor = Theme.Text;
        Font = Theme.Base;
        MinimumSize = new Size(760, 560);
        Size = new Size(900, 680);
        StartPosition = FormStartPosition.CenterScreen;

        var header = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, FlowDirection = FlowDirection.TopDown, Padding = new Padding(18, 14, 18, 6), WrapContents = false };
        header.Controls.Add(Theme.Label("Lucy Remota", Theme.Title));
        header.Controls.Add(Theme.Label("Receptor para Windows · recibe las órdenes de tu teléfono y las hace en esta computadora", color: Theme.Muted));
        header.Controls.Add(status);

        var actions = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(14, 4, 14, 4) };
        var pair = Theme.Button("Vincular teléfono", primary: true);
        pair.Click += (_, _) => OpenPairing();
        var unpair = Theme.Button("Desvincular");
        unpair.Click += async (_, _) => await UnpairSelected();
        var autostart = new CheckBox { Text = "Iniciar con Windows", Checked = Autostart.Enabled, AutoSize = true, ForeColor = Theme.Text, Margin = new Padding(14, 8, 0, 0) };
        autostart.CheckedChanged += (_, _) => { try { Autostart.Enabled = autostart.Checked; } catch (Exception e) { Log.Write("Inicio automático: " + e.Message); } };
        actions.Controls.AddRange([pair, unpair, autostart]);

        var phonesBox = Section("Teléfonos vinculados", phones, 150);
        var ordersButtons = new FlowLayoutPanel { Dock = DockStyle.Bottom, AutoSize = true, FlowDirection = FlowDirection.RightToLeft, Padding = new Padding(0, 4, 0, 0) };
        var openLog = Theme.Button("Ver registro");
        openLog.Click += (_, _) => Modules.M.ShellOpen(Paths.File("registro.txt"));
        var clear = Theme.Button("Borrar historial");
        clear.Click += (_, _) => { history.Clear(); orders.Items.Clear(); };
        ordersButtons.Controls.AddRange([openLog, clear]);
        var ordersBox = Section("Órdenes recibidas", orders, 0);
        ordersBox.Controls.Add(ordersButtons);
        ordersBox.Dock = DockStyle.Fill;

        Controls.Add(ordersBox);
        Controls.Add(phonesBox);
        Controls.Add(actions);
        Controls.Add(header);

        tray = new NotifyIcon { Icon = Icon, Text = "Lucy Remota · Receptor", Visible = true };
        var menu = new ContextMenuStrip();
        menu.Items.Add("Abrir", null, (_, _) => ShowFromTray());
        menu.Items.Add("Vincular teléfono", null, (_, _) => { ShowFromTray(); OpenPairing(); });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Salir", null, (_, _) => ExitApp());
        tray.ContextMenuStrip = menu;
        tray.DoubleClick += (_, _) => ShowFromTray();

        link.ConnectionsChanged += () => Ui(RefreshPhones);
        link.DevicesChanged += () => Ui(RefreshPhones);
        link.Paired += d => Ui(() =>
        {
            pairDialog?.Close();
            tray.ShowBalloonTip(3000, "Lucy Remota", $"Vinculada con {d.Name}.", ToolTipIcon.Info);
        });
        history.Added += r => Ui(() => AddOrder(r, top: true));
        foreach (var r in history.Items.Reverse().Take(200)) AddOrder(r, top: false);
        RefreshPhones();
        // Refresca «hace X» y la latencia cada tanto.
        var timer = new System.Windows.Forms.Timer { Interval = 5000 };
        timer.Tick += (_, _) => RefreshPhones();
        timer.Start();
    }

    static Panel Section(string title, Control content, int height)
    {
        var p = new Panel { Dock = DockStyle.Top, Height = height, Padding = new Padding(18, 6, 18, 10) };
        var label = Theme.Label(title, new Font("Segoe UI Semibold", 10.5f), Theme.Muted);
        label.Dock = DockStyle.Top;
        p.Controls.Add(content);
        p.Controls.Add(label);
        return p;
    }

    void Ui(Action a)
    {
        if (IsDisposed) return;
        if (InvokeRequired) BeginInvoke(a); else a();
    }

    protected override void SetVisibleCore(bool value)
    {
        // Con «--minimizada» arranca en la bandeja sin parpadear la ventana.
        if (startHidden && !IsHandleCreated) { CreateHandle(); value = false; }
        base.SetVisibleCore(value);
    }

    public void ShowFromTray()
    {
        Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // La X la manda a la bandeja: Lucy sigue recibiendo órdenes.
        if (!exiting && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            tray.ShowBalloonTip(2500, "Lucy Remota sigue activa", "Recibe órdenes desde la bandeja. Para cerrarla: clic derecho › Salir.", ToolTipIcon.Info);
            return;
        }
        tray.Visible = false;
        base.OnFormClosing(e);
    }

    void ExitApp()
    {
        exiting = true;
        tray.Visible = false;
        Close();
        Application.Exit();
    }

    void OpenPairing()
    {
        if (pairDialog is { IsDisposed: false }) { pairDialog.Activate(); return; }
        var window = link.StartPairing();
        pairDialog = new PairDialog(window, store);
        pairDialog.FormClosed += (_, _) => { if (link.Pairing == window) link.CancelPairing(); };
        pairDialog.Show(this);
    }

    async Task UnpairSelected()
    {
        if (phones.SelectedItems.Count == 0)
        {
            MessageBox.Show(this, "Elegí un teléfono de la lista.", "Lucy Remota");
            return;
        }
        var id = (string)phones.SelectedItems[0].Tag!;
        var name = phones.SelectedItems[0].Text;
        if (MessageBox.Show(this, $"¿Desvinculo {name}? Para volver a usarlo vas a tener que vincularlo de nuevo.", "Lucy Remota", MessageBoxButtons.YesNo) != DialogResult.Yes) return;
        await link.UnpairAsync(id);
        RefreshPhones();
    }

    void RefreshPhones()
    {
        var connected = link.Connected.ToDictionary(c => c.Id);
        var devices = store.Devices;
        phones.BeginUpdate();
        phones.Items.Clear();
        foreach (var d in devices.OrderBy(d => d.Name))
        {
            var on = connected.TryGetValue(d.Id, out var c);
            var item = new ListViewItem(d.Name) { Tag = d.Id, ForeColor = on ? Theme.Ok : Theme.Muted };
            item.SubItems.Add(on ? $"Conectado ({c!.Address}" + (c.LatencyMs > 0 ? $", {c.LatencyMs} ms)" : ")") : "Desconectado");
            item.SubItems.Add(d.LastSeen?.ToLocalTime().ToString("dd/MM HH:mm") ?? "—");
            phones.Items.Add(item);
        }
        phones.EndUpdate();
        if (devices.Count == 0)
        {
            status.Text = "○ Ningún teléfono vinculado: tocá «Vincular teléfono».";
            status.ForeColor = Theme.Warn;
        }
        else if (connected.Count > 0)
        {
            status.Text = "● Conectada con " + string.Join(", ", connected.Values.Select(c => c.Name));
            status.ForeColor = Theme.Ok;
        }
        else
        {
            status.Text = "○ Esperando al teléfono (abrí Lucy Remota en el teléfono, en la misma red Wi-Fi).";
            status.ForeColor = Theme.Muted;
        }
        tray.Text = connected.Count > 0 ? "Lucy Remota · conectada" : "Lucy Remota · esperando al teléfono";
    }

    void AddOrder(CommandRecord r, bool top)
    {
        var item = new ListViewItem(r.At.ToLocalTime().ToString("HH:mm:ss"));
        item.SubItems.Add(Friendly.GetValueOrDefault(r.Intent, r.Intent));
        item.SubItems.Add(StatusText.GetValueOrDefault(r.Status, r.Status));
        item.SubItems.Add(r.Message);
        item.ForeColor = r.Status == Status.Success ? Theme.Text : r.Status == Status.NeedsConfirmation ? Theme.Warn : Theme.Bad;
        item.ToolTipText = $"{r.Intent} {r.ParamsJson}";
        if (top) orders.Items.Insert(0, item); else orders.Items.Add(item);
        while (orders.Items.Count > 300) orders.Items.RemoveAt(orders.Items.Count - 1);
    }
}
