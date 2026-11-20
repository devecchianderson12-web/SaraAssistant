using LucyRemota.Core.Link;
using LucyRemota.Modules;

namespace LucyRemota.UI;

/// <summary>Ajustes del Receptor, con la estética de Lucy.</summary>
sealed class SettingsDialog : LucyForm
{
    const int FieldWidth = 400;
    readonly AppSettings s = AppSettings.Current.Copy();
    readonly MainForm main;
    readonly TextBox pcName = Field();
    readonly CheckBox autostart = Check("Iniciar con Windows");
    readonly CheckBox minimized = Check("Abrir minimizada en la bandeja");
    readonly ComboBox music = Combo();
    readonly CheckBox relay = Check("Conectarme por Internet cuando el teléfono está en otra red (datos móviles)");
    readonly TextBox relayUrl = Field();
    readonly CheckBox saveHistory = Check("Guardar el historial de órdenes");
    readonly CheckBox notify = Check("Avisarme en la bandeja cada vez que llega una orden");
    readonly CheckBox updates = Check("Buscar versiones nuevas sola (y avisarme)");
    readonly Label updateStatus = Theme.Label("", 9.5f, Theme.Muted);
    readonly PillButton updateNow = new("Actualizar") { Primary = true, Visible = false };
    Updater.Release? found;

    public SettingsDialog(MainForm main, AppsModule apps, IPcStore store)
    {
        this.main = main;
        Text = "Ajustes de Lucy Remota";
        StartPosition = FormStartPosition.CenterParent;
        Size = new Size(520, 680);
        MinimumSize = new Size(480, 480);
        MinimizeBox = MaximizeBox = false;

        pcName.Text = s.PcName;
        pcName.PlaceholderText = Environment.MachineName;
        autostart.Checked = Autostart.Enabled;
        minimized.Checked = s.StartMinimized;
        relay.Checked = s.UseRelay;
        relayUrl.Text = s.RelayUrl;
        relayUrl.Enabled = relay.Checked;
        relay.CheckedChanged += (_, _) => relayUrl.Enabled = relay.Checked;
        saveHistory.Checked = s.SaveHistory;
        notify.Checked = s.NotifyOrders;
        updates.Checked = s.CheckUpdates;

        music.Items.Add("Automática (YouTube Music instalada, Spotify…)");
        music.SelectedIndex = 0;
        _ = FillMusicApps(apps);

        var form = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true,
            Padding = new Padding(22, 14, 22, 10), BackColor = Color.Transparent,
        };
        void Add(params Control[] cs) { foreach (var c in cs) form.Controls.Add(c); }

        Add(Section("Esta computadora"),
            Caption("Nombre con el que la ve el teléfono"), pcName,
            autostart, minimized);
        Add(Section("Música"),
            Caption("App para «poné música» y «poné …»"), music,
            Note("Con YouTube Music instalada desde Chrome o Edge, Lucy usa esa ventana (si está cerrada la abre), busca ahí y pone el primer resultado. No abre pestañas."));
        Add(Section("Conexión"),
            relay, Caption("Dirección del relé"), relayUrl,
            Note("En la misma red Wi-Fi se conectan directo. En redes distintas pasan por el relé de Cloudflare: las órdenes van cifradas de punta a punta y el relé no puede leerlas."));
        Add(Section("Órdenes"), saveHistory, notify);
        var version = Theme.Label($"Versión instalada: V{Updater.CurrentVersion}", 10f);
        var check = new PillButton("Buscar actualizaciones");
        check.Click += async (_, _) => await CheckNow();
        updateNow.Click += async (_, _) => { if (found != null && await main.InstallUpdateAsync(found)) Close(); };
        Add(Section("Actualizaciones"), version, updates, Row(check, updateNow), updateStatus);
        var log = new PillButton("Ver registro");
        log.Click += (_, _) => M.ShellOpen(Paths.File("registro.txt"));
        var folder = new PillButton("Abrir la carpeta de datos");
        folder.Click += (_, _) => M.ShellOpen(Paths.Data);
        Add(Section("Diagnóstico"), Row(log, folder),
            Note($"Datos en {Paths.Data}. Para llevar Lucy Remota a otra computadora, copiá el .exe solo: la identidad de esta PC no sirve en otra (hay que volver a vincular)."));

        var save = new PillButton("Guardar") { Primary = true };
        save.Click += (_, _) => Save();
        var cancel = new PillButton("Cancelar");
        cancel.Click += (_, _) => Close();
        var outer = new Panel { Dock = DockStyle.Fill, BackColor = Color.Transparent };
        outer.Controls.Add(form);
        outer.Controls.Add(PhonesDialog.Buttons(cancel, save));
        Controls.Add(outer);
        AcceptButton = null;
    }

    async Task FillMusicApps(AppsModule apps)
    {
        try
        {
            var list = await apps.MusicAppsAsync();
            if (IsDisposed) return;
            foreach (var a in list) music.Items.Add(a.Name);
            if (s.MusicApp.Length > 0)
            {
                var i = music.Items.IndexOf(s.MusicApp);
                if (i < 0) i = music.Items.Add(s.MusicApp);
                music.SelectedIndex = i;
            }
        }
        catch (Exception e) { Log.Write("Lista de apps de música: " + e.Message); }
    }

    async Task CheckNow()
    {
        updateStatus.Text = "Buscando…";
        updateNow.Visible = false;
        var latest = await Updater.LatestAsync();
        if (IsDisposed) return;
        if (latest == null) { updateStatus.Text = "No pude buscar actualizaciones. Revisá la conexión a Internet."; return; }
        if (latest.Version <= Updater.CurrentVersion) { updateStatus.Text = $"Está al día (la última es la V{latest.Version})."; return; }
        found = latest;
        updateStatus.Text = $"Hay una versión nueva: V{latest.Version}.";
        updateNow.Text = $"Actualizar a V{latest.Version}";
        updateNow.Size = updateNow.GetPreferredSize(Size.Empty);
        updateNow.Visible = true;
    }

    void Save()
    {
        var url = relayUrl.Text.Trim();
        if (relay.Checked && (!Uri.TryCreate(url, UriKind.Absolute, out var u) || u.Scheme is not ("https" or "http")))
        {
            MessageBox.Show(this, "La dirección del relé tiene que empezar con https://", "Lucy Remota");
            return;
        }
        s.PcName = pcName.Text.Trim();
        s.StartMinimized = minimized.Checked;
        s.MusicApp = music.SelectedIndex <= 0 ? "" : music.SelectedItem?.ToString() ?? "";
        s.UseRelay = relay.Checked;
        s.RelayUrl = url.Length > 0 ? url : RelayClient.DefaultUrl;
        s.SaveHistory = saveHistory.Checked;
        s.NotifyOrders = notify.Checked;
        s.CheckUpdates = updates.Checked;
        try { if (Autostart.Enabled != autostart.Checked) Autostart.Enabled = autostart.Checked; }
        catch (Exception e) { Log.Write("Inicio automático: " + e.Message); }
        AppSettings.Save(s);
        Close();
    }

    // ------------------------------------------------------------------ Controles con la estética de Lucy

    static Label Section(string text)
    {
        var l = Theme.Label(text, 11.5f, Theme.Accent, FontStyle.Bold);
        l.Margin = new Padding(0, 14, 0, 4);
        return l;
    }

    static Label Caption(string text)
    {
        var l = Theme.Label(text, 9.5f, Theme.Muted);
        l.Margin = new Padding(0, 6, 0, 2);
        return l;
    }

    static Label Note(string text)
    {
        var l = Theme.Label(text, 9f, Theme.Muted);
        l.MaximumSize = new Size(FieldWidth + 20, 0);
        l.Margin = new Padding(0, 4, 0, 4);
        return l;
    }

    static CheckBox Check(string text) => new()
    {
        Text = text, AutoSize = true, MaximumSize = new Size(FieldWidth + 20, 0), ForeColor = Theme.Text, BackColor = Color.Transparent,
        Font = Theme.Ui(10f), Margin = new Padding(0, 6, 0, 2), FlatStyle = FlatStyle.Standard,
    };

    static TextBox Field() => new()
    {
        Width = FieldWidth, BackColor = Theme.SurfaceSolid, ForeColor = Theme.Text, BorderStyle = BorderStyle.FixedSingle, Font = Theme.Ui(10f),
        Margin = new Padding(0, 0, 0, 4),
    };

    static ComboBox Combo() => new()
    {
        Width = FieldWidth, DropDownStyle = ComboBoxStyle.DropDownList, FlatStyle = FlatStyle.Flat, BackColor = Theme.SurfaceSolid,
        ForeColor = Theme.Text, Font = Theme.Ui(10f), Margin = new Padding(0, 0, 0, 4),
    };

    static Control Row(params Control[] items)
    {
        var row = new FlowLayoutPanel { AutoSize = true, BackColor = Color.Transparent, Margin = new Padding(0, 6, 0, 2), WrapContents = false };
        foreach (var c in items)
        {
            if (c is PillButton b) b.Size = b.GetPreferredSize(Size.Empty);
            c.Margin = new Padding(0, 0, 8, 0);
            row.Controls.Add(c);
        }
        return row;
    }
}
