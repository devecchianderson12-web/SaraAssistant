using LucyRemota.Core.Commands;
using LucyRemota.Core.Link;

namespace LucyRemota.UI;

/// <summary>
/// Ventana de Lucy Remota (Receptor), con la estética de Lucy: la esfera en el centro (late cuando
/// llega una orden), el estado de la conexión y la última orden, y botones redondeados abajo.
/// Al cerrarla queda en la bandeja; «Salir» la cierra del todo.
/// </summary>
sealed class MainForm : LucyForm
{
    readonly PcLink link;
    readonly IPcStore store;
    readonly History history;
    readonly SphereView sphere = new() { Dock = DockStyle.Fill };
    readonly Label status = Theme.Label("", 14f, style: FontStyle.Bold);
    readonly Label detail = Theme.Label("", 10.5f, Theme.Muted);
    readonly NotifyIcon tray;
    readonly bool startHidden;
    bool exiting;
    bool toldTray;
    PairDialog? pairDialog;

    internal static readonly Dictionary<string, string> Friendly = new()
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

    internal static readonly Dictionary<string, (string text, Color color)> StatusText = new()
    {
        [Status.Success] = ("Hecho", Theme.Ok), [Status.Error] = ("Error", Theme.Bad), [Status.Unsupported] = ("No disponible", Theme.Bad),
        [Status.Invalid] = ("Inválida", Theme.Bad), [Status.Expired] = ("Llegó tarde", Theme.Warn), [Status.NeedsConfirmation] = ("Pidió confirmar", Theme.Warn),
        [Status.Unauthorized] = ("No autorizado", Theme.Bad), [Status.Busy] = ("Ocupada", Theme.Warn),
    };

    public MainForm(PcLink link, IPcStore store, History history, bool startHidden)
    {
        this.link = link;
        this.store = store;
        this.history = history;
        this.startHidden = startHidden;
        var version = Application.ProductVersion.Split('+')[0];
        Text = $"Lucy Remota · V{version.Split('.').Last()}";
        Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? SystemIcons.Application;
        MinimumSize = new Size(420, 560);
        Size = new Size(540, 680);
        StartPosition = FormStartPosition.CenterScreen;

        var top = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, FlowDirection = FlowDirection.TopDown, Padding = new Padding(20, 16, 20, 0), BackColor = Color.Transparent, WrapContents = false };
        top.Controls.Add(Theme.Label("Lucy Remota", 15f, style: FontStyle.Bold));
        top.Controls.Add(Theme.Label("Receptor · tu teléfono manda, esta computadora hace", 9.5f, Theme.Muted));

        var bottom = new TableLayoutPanel { Dock = DockStyle.Bottom, AutoSize = true, ColumnCount = 1, Padding = new Padding(16, 0, 16, 16), BackColor = Color.Transparent };
        bottom.Controls.Add(Centered(status));
        bottom.Controls.Add(Centered(detail));
        var buttons = new FlowLayoutPanel { AutoSize = true, Anchor = AnchorStyles.None, BackColor = Color.Transparent, Margin = new Padding(0, 12, 0, 0), WrapContents = true };
        var pair = new PillButton("Vincular teléfono") { Primary = true };
        pair.Click += (_, _) => OpenPairing();
        var phones = new PillButton("Teléfonos");
        phones.Click += (_, _) => new PhonesDialog(link, store).ShowDialog(this);
        var orders = new PillButton("Historial");
        orders.Click += (_, _) => new HistoryDialog(history).ShowDialog(this);
        var more = new PillButton("Más");
        more.Click += (_, _) => MoreMenu().Show(more, new Point(0, more.Height));
        foreach (var b in new Control[] { pair, phones, orders, more }) { b.Size = b.GetPreferredSize(Size.Empty); buttons.Controls.Add(b); }
        bottom.Controls.Add(buttons);

        Controls.Add(sphere);
        Controls.Add(bottom);
        Controls.Add(top);

        tray = new NotifyIcon { Icon = Icon, Text = "Lucy Remota", Visible = true };
        var menu = Menus.Dark();
        menu.Items.Add("Abrir", null, (_, _) => ShowFromTray());
        menu.Items.Add("Vincular teléfono", null, (_, _) => { ShowFromTray(); OpenPairing(); });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Salir", null, (_, _) => ExitApp());
        tray.ContextMenuStrip = menu;
        tray.DoubleClick += (_, _) => ShowFromTray();

        link.ConnectionsChanged += () => Ui(RefreshStatus);
        link.DevicesChanged += () => Ui(RefreshStatus);
        link.Paired += d => Ui(() =>
        {
            pairDialog?.Close();
            sphere.Pulse();
            tray.ShowBalloonTip(3000, "Lucy Remota", $"Vinculada con {d.Name}.", ToolTipIcon.Info);
            RefreshStatus();
        });
        history.Added += r => Ui(() => { sphere.Pulse(); RefreshStatus(); });
        RefreshStatus();
        var timer = new System.Windows.Forms.Timer { Interval = 10_000 };
        timer.Tick += (_, _) => RefreshStatus();
        timer.Start();
    }

    static Control Centered(Control c)
    {
        c.Anchor = AnchorStyles.None;
        c.Margin = new Padding(0, 2, 0, 2);
        c.MaximumSize = new Size(420, 0);
        if (c is Label l) l.TextAlign = ContentAlignment.MiddleCenter;
        return c;
    }

    ContextMenuStrip MoreMenu()
    {
        var m = Menus.Dark();
        var auto = new ToolStripMenuItem("Iniciar con Windows") { Checked = Autostart.Enabled, CheckOnClick = true };
        auto.CheckedChanged += (_, _) => { try { Autostart.Enabled = auto.Checked; } catch (Exception e) { Log.Write("Inicio automático: " + e.Message); } };
        m.Items.Add(auto);
        m.Items.Add("Ver registro", null, (_, _) => Modules.M.ShellOpen(Paths.File("registro.txt")));
        m.Items.Add("Abrir la carpeta de datos", null, (_, _) => Modules.M.ShellOpen(Paths.Data));
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add("Salir de Lucy Remota", null, (_, _) => ExitApp());
        return m;
    }

    void Ui(Action a)
    {
        if (IsDisposed) return;
        if (InvokeRequired) BeginInvoke(a); else a();
    }

    protected override void SetVisibleCore(bool value)
    {
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
        if (!exiting && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            if (!toldTray)
            {
                toldTray = true;
                tray.ShowBalloonTip(2500, "Lucy Remota sigue activa", "Recibe órdenes desde la bandeja. Para cerrarla: clic derecho › Salir.", ToolTipIcon.Info);
            }
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

    void RefreshStatus()
    {
        var connected = link.Connected;
        var devices = store.Devices;
        if (devices.Count == 0)
        {
            status.Text = "Ningún teléfono vinculado";
            status.ForeColor = Theme.Warn;
            sphere.Energy = 0.25f;
        }
        else if (connected.Count > 0)
        {
            var c = connected[0];
            status.Text = "Conectada con " + string.Join(", ", connected.Select(x => x.Name));
            status.ForeColor = Theme.Ok;
            sphere.Energy = 0.65f;
        }
        else
        {
            status.Text = "Esperando al teléfono…";
            status.ForeColor = Theme.Text;
            sphere.Energy = 0.3f;
        }
        var last = history.Items.LastOrDefault();
        detail.Text = devices.Count == 0
            ? "Tocá «Vincular teléfono» y escaneá el código con Lucy Remota en el teléfono."
            : last == null
                ? (connected.Count > 0 ? "Decile «Lucy» al teléfono para darle una orden." : "Abrí Lucy Remota en el teléfono, en la misma red Wi-Fi.")
                : $"Última orden: {Friendly.GetValueOrDefault(last.Intent, last.Intent)} · {StatusText.GetValueOrDefault(last.Status, (text: last.Status, color: Theme.Muted)).text} · {Ago(last.At)}\n{last.Message}";
        tray.Text = connected.Count > 0 ? "Lucy Remota · conectada" : "Lucy Remota · esperando al teléfono";
    }

    internal static string Ago(DateTimeOffset at)
    {
        var s = DateTimeOffset.UtcNow - at;
        return s.TotalSeconds < 60 ? "recién" : s.TotalMinutes < 60 ? $"hace {(int)s.TotalMinutes} min" : at.ToLocalTime().ToString("dd/MM HH:mm");
    }
}

/// <summary>Teléfonos vinculados: estado y desvincular.</summary>
sealed class PhonesDialog : LucyForm
{
    public PhonesDialog(PcLink link, IPcStore store)
    {
        Text = "Teléfonos vinculados";
        StartPosition = FormStartPosition.CenterParent;
        Size = new Size(460, 420);
        MinimizeBox = MaximizeBox = false;
        var list = new DarkList();
        void Load()
        {
            var connected = link.Connected.ToDictionary(c => c.Id);
            list.Items.Clear();
            foreach (var d in store.Devices.OrderBy(d => d.Name)) list.Items.Add(d);
            list.Describe = o =>
            {
                var d = (PairedDevice)o;
                var on = connected.TryGetValue(d.Id, out var c);
                return (d.Name, on ? $"Conectado · {c!.Address}" + (c.LatencyMs > 0 ? $" · {c.LatencyMs} ms" : "") : $"Desconectado · visto {(d.LastSeen is { } s ? MainForm.Ago(s) : "nunca")}",
                    on ? "● En línea" : "○", on ? Theme.Ok : Theme.Muted);
            };
            if (list.Items.Count == 0) list.Items.Add("Todavía no hay teléfonos vinculados.");
        }
        Load();
        var card = new RoundPanel { Dock = DockStyle.Fill, Margin = new Padding(16) };
        card.Controls.Add(list);
        var unpair = new PillButton("Desvincular");
        unpair.Click += async (_, _) =>
        {
            if (list.SelectedItem is not PairedDevice d) { MessageBox.Show(this, "Elegí un teléfono de la lista.", "Lucy Remota"); return; }
            if (MessageBox.Show(this, $"¿Desvinculo {d.Name}? Para volver a usarlo vas a tener que vincularlo de nuevo.", "Lucy Remota", MessageBoxButtons.YesNo) != DialogResult.Yes) return;
            await link.UnpairAsync(d.Id);
            Load();
        };
        var close = new PillButton("Cerrar");
        close.Click += (_, _) => Close();
        Controls.Add(Wrap(card, Buttons(unpair, close)));
    }

    internal static Control Buttons(params PillButton[] buttons)
    {
        var row = new FlowLayoutPanel { Dock = DockStyle.Bottom, AutoSize = true, FlowDirection = FlowDirection.RightToLeft, BackColor = Color.Transparent, Padding = new Padding(10, 0, 10, 12) };
        foreach (var b in Enumerable.Reverse(buttons)) { b.Size = b.GetPreferredSize(Size.Empty); row.Controls.Add(b); }
        return row;
    }

    internal static Control Wrap(Control content, Control bottom)
    {
        var host = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent, Padding = new Padding(16, 16, 16, 8) };
        host.Controls.Add(content);
        var outer = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent };
        outer.Controls.Add(host);
        outer.Controls.Add(bottom);
        return outer;
    }
}

/// <summary>Órdenes recibidas.</summary>
sealed class HistoryDialog : LucyForm
{
    public HistoryDialog(History history)
    {
        Text = "Órdenes recibidas";
        StartPosition = FormStartPosition.CenterParent;
        Size = new Size(560, 560);
        MinimizeBox = MaximizeBox = false;
        var list = new DarkList
        {
            Describe = o => o is CommandRecord r
                ? ($"{MainForm.Friendly.GetValueOrDefault(r.Intent, r.Intent)}   {r.At.ToLocalTime():dd/MM HH:mm:ss}", r.Message,
                    MainForm.StatusText.GetValueOrDefault(r.Status, (text: r.Status, color: Theme.Muted)).text, MainForm.StatusText.GetValueOrDefault(r.Status, (text: r.Status, color: Theme.Muted)).color)
                : (o.ToString() ?? "", "", "", Theme.Muted),
        };
        foreach (var r in history.Items.Reverse().Take(300)) list.Items.Add(r);
        if (list.Items.Count == 0) list.Items.Add("Todavía no llegó ninguna orden.");
        var card = new RoundPanel { Dock = DockStyle.Fill };
        card.Controls.Add(list);
        var clear = new PillButton("Borrar historial");
        clear.Click += (_, _) => { history.Clear(); list.Items.Clear(); list.Items.Add("Todavía no llegó ninguna orden."); };
        var close = new PillButton("Cerrar");
        close.Click += (_, _) => Close();
        Controls.Add(PhonesDialog.Wrap(card, PhonesDialog.Buttons(clear, close)));
    }
}
