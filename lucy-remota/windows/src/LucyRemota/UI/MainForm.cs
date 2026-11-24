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
    readonly Modules.AppsModule apps;
    readonly LocalLucy local;
    readonly Label localStatus = Theme.Label("", 14f, style: FontStyle.Bold);
    readonly Label localDetail = Theme.Label("", 10.5f, Theme.Muted);
    readonly PillButton localToggle = new("Activar") { Primary = true };
    readonly ComboBox micBox = new()
    {
        Width = 300, DropDownStyle = ComboBoxStyle.DropDownList, FlatStyle = FlatStyle.Flat, BackColor = Theme.SurfaceSolid,
        ForeColor = Theme.Text, Font = Theme.Ui(10f),
    };
    bool micsLoaded;
    readonly PillButton updateButton = new("Actualizar") { Primary = true, Visible = false };
    Updater.Release? pendingUpdate;
    bool installing;
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

    public MainForm(PcLink link, IPcStore store, History history, Modules.AppsModule apps, LocalLucy local, bool startHidden)
    {
        this.apps = apps;
        this.local = local;
        this.link = link;
        this.store = store;
        this.history = history;
        this.startHidden = startHidden;
        Text = $"Lucy Remota · V{Updater.CurrentName}";
        Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? SystemIcons.Application;
        MinimumSize = new Size(420, 560);
        Size = new Size(540, 680);
        StartPosition = FormStartPosition.CenterScreen;

        var top = new FlowLayoutPanel { Dock = DockStyle.Top, AutoSize = true, FlowDirection = FlowDirection.TopDown, Padding = new Padding(20, 16, 20, 0), BackColor = Color.Transparent, WrapContents = false };
        top.Controls.Add(Theme.Label("Lucy Remota", 15f, style: FontStyle.Bold));
        var subtitle = Theme.Label("Receptor · tu teléfono manda, esta computadora hace", 9.5f, Theme.Muted);
        top.Controls.Add(subtitle);
        // Dos apartados: con el teléfono (remoto) o Lucy completa en esta PC (sin teléfono).
        var tabs = new FlowLayoutPanel { AutoSize = true, BackColor = Color.Transparent, Margin = new Padding(0, 10, 0, 0), WrapContents = false };
        var phoneTab = new PillButton("Con el teléfono") { Primary = true };
        var localTab = new PillButton("Lucy en esta PC");
        foreach (var b in new[] { phoneTab, localTab }) { b.Size = b.GetPreferredSize(Size.Empty); b.Margin = new Padding(0, 0, 8, 0); tabs.Controls.Add(b); }
        top.Controls.Add(tabs);

        var bottom = new TableLayoutPanel { Dock = DockStyle.Bottom, AutoSize = true, ColumnCount = 1, Padding = new Padding(16, 0, 16, 16), BackColor = Color.Transparent };
        bottom.Controls.Add(Centered(status));
        bottom.Controls.Add(Centered(detail));
        var buttons = new FlowLayoutPanel { AutoSize = true, Anchor = AnchorStyles.None, BackColor = Color.Transparent, Margin = new Padding(0, 12, 0, 0), WrapContents = true };
        var pair = new PillButton("Vincular") { Primary = true };
        pair.Click += (_, _) => OpenPairing();
        var phones = new PillButton("Teléfonos");
        phones.Click += (_, _) => new PhonesDialog(link, store).ShowDialog(this);
        var orders = new PillButton("Historial");
        orders.Click += (_, _) => new HistoryDialog(history).ShowDialog(this);
        var settings = new PillButton("Ajustes");
        settings.Click += (_, _) => OpenSettings();
        var more = new PillButton("Más");
        more.Click += (_, _) => MoreMenu().Show(more, new Point(0, more.Height));
        updateButton.Click += async (_, _) => { if (pendingUpdate != null) await InstallUpdateAsync(pendingUpdate); };
        foreach (var b in new PillButton[] { pair, phones, orders, settings, more, updateButton }) { b.Size = b.GetPreferredSize(Size.Empty); buttons.Controls.Add(b); }
        bottom.Controls.Add(buttons);

        // ---- «Lucy en esta PC»: la Lucy de Windows completa, sin teléfono.
        var localBottom = new TableLayoutPanel { Dock = DockStyle.Bottom, AutoSize = true, ColumnCount = 1, Padding = new Padding(16, 0, 16, 16), BackColor = Color.Transparent, Visible = false };
        localBottom.Controls.Add(Centered(localStatus));
        localBottom.Controls.Add(Centered(localDetail));
        var localButtons = new FlowLayoutPanel { AutoSize = true, Anchor = AnchorStyles.None, BackColor = Color.Transparent, Margin = new Padding(0, 12, 0, 0), WrapContents = true };
        var openLocal = new PillButton("Abrir Lucy");
        openLocal.Click += async (_, _) => { if (local.IsRunning) local.Show(); else await local.StartAsync(showWindow: true); };
        var localSettings = new PillButton("Ajustes de Lucy");
        localSettings.Click += async (_, _) => { if (!local.IsRunning) await local.StartAsync(); local.OpenSettings(); };
        var talk = new PillButton("Hablar");
        talk.Click += (_, _) => local.Talk();
        localToggle.Click += async (_, _) => await ToggleLocalAsync();
        foreach (var b in new[] { localToggle, openLocal, localSettings, talk }) { b.Size = b.GetPreferredSize(Size.Empty); localButtons.Controls.Add(b); }
        localBottom.Controls.Add(localButtons);
        var micRow = new FlowLayoutPanel { AutoSize = true, Anchor = AnchorStyles.None, BackColor = Color.Transparent, Margin = new Padding(0, 10, 0, 0), WrapContents = false };
        var micLabel = Theme.Label("Micrófono", 10f, Theme.Muted);
        micLabel.Margin = new Padding(0, 6, 8, 0);
        var useMic = new PillButton("Usar");
        useMic.Size = useMic.GetPreferredSize(Size.Empty);
        useMic.Click += async (_, _) =>
        {
            if (micBox.SelectedItem is not string choice) return;
            await local.SetMicrophoneAsync(choice == DefaultMic ? "" : choice);
            RefreshLocal();
        };
        micRow.Controls.Add(micLabel);
        micRow.Controls.Add(micBox);
        micRow.Controls.Add(useMic);
        localBottom.Controls.Add(micRow);

        void ShowTab(bool phone)
        {
            phoneTab.Primary = phone;
            localTab.Primary = !phone;
            phoneTab.Invalidate();
            localTab.Invalidate();
            bottom.Visible = phone;
            localBottom.Visible = !phone;
            subtitle.Text = phone ? "Receptor · tu teléfono manda, esta computadora hace" : "Lucy completa en esta PC · decí «Lucy», sin teléfono";
            if (!phone) { RefreshLocal(); _ = LoadMicrophonesAsync(); }
        }
        phoneTab.Click += (_, _) => ShowTab(true);
        localTab.Click += (_, _) => ShowTab(false);
        local.Changed += () => Ui(() => { RefreshLocal(); if (local.Current == LocalLucy.State.Running) _ = LoadMicrophonesAsync(); });

        Controls.Add(sphere);
        Controls.Add(bottom);
        Controls.Add(localBottom);
        Controls.Add(top);

        tray = new NotifyIcon { Icon = Icon, Text = "Lucy Remota", Visible = true };
        var menu = Menus.Dark();
        menu.Items.Add("Abrir", null, (_, _) => ShowFromTray());
        menu.Items.Add("Vincular teléfono", null, (_, _) => { ShowFromTray(); OpenPairing(); });
        menu.Items.Add("Ajustes", null, (_, _) => { ShowFromTray(); OpenSettings(); });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Lucy en esta PC: abrir", null, async (_, _) => { if (local.IsRunning) local.Show(); else await local.StartAsync(showWindow: true); });
        menu.Items.Add("Lucy en esta PC: hablar", null, (_, _) => local.Talk());
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
        history.Added += r => Ui(() =>
        {
            sphere.Pulse();
            RefreshStatus();
            if (AppSettings.Current.NotifyOrders)
                tray.ShowBalloonTip(2500, $"Lucy Remota · {Friendly.GetValueOrDefault(r.Intent, r.Intent)}", r.Message, r.Status == Status.Success ? ToolTipIcon.Info : ToolTipIcon.Warning);
        });
        RefreshStatus();
        var timer = new System.Windows.Forms.Timer { Interval = 10_000 };
        timer.Tick += (_, _) => RefreshStatus();
        timer.Start();

        // Versiones nuevas: al rato de abrir y después cada 6 horas (si está activado en Ajustes).
        var updates = new System.Windows.Forms.Timer { Interval = 15_000 };
        updates.Tick += async (_, _) =>
        {
            updates.Interval = (int)TimeSpan.FromHours(6).TotalMilliseconds;
            if (AppSettings.Current.CheckUpdates) await local.UpdateIfNeededAsync();
            if (AppSettings.Current.CheckUpdates) await CheckUpdatesAsync();
        };
        updates.Start();
    }

    const string DefaultMic = "El predeterminado de Windows";

    async Task ToggleLocalAsync()
    {
        var on = !AppSettings.Current.LocalLucy;
        var s = AppSettings.Current.Copy();
        s.LocalLucy = on;
        AppSettings.Save(s);
        if (on) await local.StartAsync(); else await local.StopAsync();
        RefreshLocal();
    }

    void RefreshLocal()
    {
        var on = AppSettings.Current.LocalLucy;
        localToggle.Text = on ? "Desactivar" : "Activar";
        localToggle.Size = localToggle.GetPreferredSize(Size.Empty);
        (localStatus.Text, localStatus.ForeColor) = local.Current switch
        {
            LocalLucy.State.Running => ("Lucy está escuchando en esta PC", Theme.Ok),
            LocalLucy.State.Downloading or LocalLucy.State.Starting => ("Preparando Lucy…", Theme.Text),
            LocalLucy.State.Failed => ("No se pudo abrir Lucy", Theme.Bad),
            _ => (on ? "Activada" : "Lucy en esta PC está apagada", on ? Theme.Text : Theme.Warn),
        };
        localDetail.Text = local.Detail.Length > 0 && local.Current != LocalLucy.State.Off
            ? local.Detail
            : on ? "Decí «Lucy» cerca de la PC." : "Activala para usar a Lucy sin el teléfono: escucha «Lucy», habla, responde y hace todo lo de siempre en esta PC.";
    }

    async Task LoadMicrophonesAsync()
    {
        if (micsLoaded || LocalLucy.Installed() == null) return;
        micsLoaded = true;
        var mics = await local.MicrophonesAsync();
        if (IsDisposed) return;
        micBox.Items.Clear();
        micBox.Items.Add(DefaultMic);
        foreach (var m in mics) micBox.Items.Add(m);
        var current = LocalLucy.CurrentMicrophone();
        micBox.SelectedItem = current.Length > 0 && micBox.Items.Contains(current) ? current : DefaultMic;
    }

    void OpenSettings()
    {
        using var dialog = new SettingsDialog(this, apps, store);
        dialog.ShowDialog(this);
        RefreshStatus();
    }

    async Task CheckUpdatesAsync()
    {
        var release = await Updater.AvailableAsync();
        if (release == null || IsDisposed || pendingUpdate?.Version == release.Version) return;
        pendingUpdate = release;
        updateButton.Text = $"Actualizar a V{release.Label}";
        updateButton.Size = updateButton.GetPreferredSize(Size.Empty);
        updateButton.Visible = true;
        tray.ShowBalloonTip(5000, "Lucy Remota", $"Hay una versión nueva (V{release.Label}). Abrí Lucy Remota y tocá «Actualizar».", ToolTipIcon.Info);
    }

    /// <summary>Baja la versión nueva, la abre y cierra ésta. false si no se pudo (avisa por qué).</summary>
    internal async Task<bool> InstallUpdateAsync(Updater.Release release)
    {
        if (installing) return false;
        if (MessageBox.Show(this, $"¿Actualizo Lucy Remota a la V{release.Label}? Se cierra un momento y se vuelve a abrir sola; los teléfonos vinculados se mantienen.",
                "Lucy Remota", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return false;
        installing = true;
        ShowFromTray();
        status.Text = "Bajando la actualización…";
        status.ForeColor = Theme.Text;
        var progress = new Progress<int>(p => status.Text = $"Bajando la actualización… {p} %");
        var error = await Updater.InstallAsync(release, progress);
        installing = false;
        if (error == null)
        {
            ExitApp();
            return true;
        }
        RefreshStatus();
        MessageBox.Show(this, error, "Lucy Remota", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        return false;
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
        m.Items.Add("Buscar actualizaciones", null, async (_, _) =>
        {
            var latest = await Updater.LatestAsync();
            if (latest == null) MessageBox.Show(this, "No pude buscar actualizaciones. Revisá la conexión a Internet.", "Lucy Remota");
            else if (latest.Version <= Updater.CurrentVersion) MessageBox.Show(this, $"Lucy Remota está al día (V{Updater.CurrentName}).", "Lucy Remota");
            else await InstallUpdateAsync(latest);
        });
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
        try { local.StopAsync().Wait(5000); } catch { }
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
        if (installing) return;
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
            status.Text = "Conectada con " + string.Join(", ", connected.Select(x => x.ViaRelay ? x.Name + " (por Internet)" : x.Name));
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
