using System;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Interop;
using Forms = System.Windows.Forms;

namespace Lucy;

/// <summary>
/// Lucy para Windows: asistente de voz multimedia. Se llama diciendo «Lucy» (o con Ctrl+Alt+L),
/// y entiende órdenes de música y volumen. Vive en la bandeja del sistema; un solo .exe portable.
/// </summary>
public static class Program
{
    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Contains("--selftest")) return SelfTest();
        using var single = new Mutex(true, "Lucy.SingleInstance", out var first);
        if (!first) return 0;
        var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        var lucy = new LucyApp(app);
        app.Startup += async (_, _) => await lucy.StartAsync();
        return app.Run();
    }

    /// <summary>Prueba sin interfaz para la integración continua: reglas, modelo, audio y voces.</summary>
    private static int SelfTest()
    {
        var log = new System.Text.StringBuilder();
        var failures = Interpreter.SelfTest();
        log.AppendLine($"Reglas: {(failures.Count == 0 ? "OK" : string.Join("; ", failures))}");
        var modelOk = ModelStore.EnsureAsync(s => log.AppendLine(s)).GetAwaiter().GetResult();
        log.AppendLine($"Modelo: {(modelOk ? "OK" : "FALTA")}");
        if (modelOk)
        {
            try
            {
                using var model = new Vosk.Model(ModelStore.Folder);
                using var rec = new Vosk.VoskRecognizer(model, 16000, "[\"lucy\", \"[unk]\"]");
                rec.AcceptWaveform(new byte[3200], 3200);
                log.AppendLine("Vosk: OK " + rec.FinalResult().Replace("\n", " "));
            }
            catch (Exception e)
            {
                failures.Add("Vosk: " + e.Message);
            }
        }
        else failures.Add("modelo");
        try { log.AppendLine("Voz: " + new Speaker().VoiceName); } catch (Exception e) { log.AppendLine("Voz: " + e.Message); }
        try { log.AppendLine("Volumen: " + new Media().VolumePercent + " %"); } catch (Exception e) { log.AppendLine("Volumen: sin dispositivo (" + e.GetType().Name + ")"); }
        File.WriteAllText("selftest.txt", log + (failures.Count == 0 ? "RESULTADO: OK" : "RESULTADO: FALLÓ " + string.Join("; ", failures)));
        return failures.Count == 0 ? 0 : 1;
    }
}

public sealed class LucyApp
{
    private readonly Application app;
    private readonly Settings settings = Settings.Load();
    private readonly Media media = new();
    private SphereWindow? sphere;
    private Listener? listener;
    private Speaker? speaker;
    private Forms.NotifyIcon? tray;
    private int busy;

    public LucyApp(Application app) => this.app = app;

    private void Ui(Action a) => app.Dispatcher.BeginInvoke(a);

    public async Task StartAsync()
    {
        sphere = new SphereWindow(settings);
        CreateTray();
        RegisterHotkey();
        speaker = new Speaker();
        sphere.SetState(VoiceState.Thinking, "Preparando la voz…");
        if (!await ModelStore.EnsureAsync(s => Ui(() => sphere.SetState(VoiceState.Thinking, s))))
        {
            Notify("Lucy no pudo preparar el modelo de voz. Revisá la conexión y abrila de nuevo.");
            sphere.SetState(VoiceState.Idle);
            return;
        }
        try
        {
            listener = await Task.Run(() => new Listener());
        }
        catch (Exception e)
        {
            Notify("No pude abrir el micrófono: " + e.Message);
            sphere.SetState(VoiceState.Idle);
            return;
        }
        listener.Sensitivity = () => settings.Sensitivity;
        listener.WakeEnabled = () => settings.WakeWordEnabled;
        listener.Level += l => Ui(() => sphere.SetLevel(l));
        listener.WakeDetected += () => Ui(() => _ = OnWake());
        listener.Partial += p => Ui(() => sphere.SetState(VoiceState.Listening, p));
        listener.CommandHeard += text => Ui(() => _ = OnCommand(text));
        listener.Start();
        sphere.SetState(VoiceState.Idle);
        Notify(settings.WakeWordEnabled ? "Lucy está lista: decí «Lucy» o apretá Ctrl+Alt+L." : "Lucy está lista: apretá Ctrl+Alt+L para hablar.");
    }

    // ------------------------------------------------------------------ Ciclo de una orden

    private async Task OnWake()
    {
        if (Interlocked.Exchange(ref busy, 1) == 1 || listener == null || speaker == null) return;
        media.Duck(true);
        sphere!.SetState(VoiceState.Speaking, "Decime");
        listener.Paused = true;
        await speaker.SpeakAsync(new[] { "Decime.", "¿Sí?", "Dime." }[Random.Shared.Next(3)]);
        listener.Paused = false;
        sphere.SetState(VoiceState.Listening, "Te escucho…");
        listener.ListenNow();
    }

    private async Task OnCommand(string text)
    {
        if (listener == null || speaker == null) return;
        try
        {
            if (text.Length == 0)
            {
                sphere!.SetState(VoiceState.Idle);
                return;
            }
            sphere!.SetState(VoiceState.Thinking, "«" + text + "»");
            var command = Interpreter.Parse(text);
            var answer = await Execute(command);
            if (answer.Length > 0)
            {
                // Bajar la música recién después de ejecutar (si no, «poné X» suena bajito al empezar).
                listener.Paused = true;
                sphere.SetState(VoiceState.Speaking, answer);
                await speaker.SpeakAsync(answer);
                listener.Paused = false;
            }
        }
        catch (Exception e)
        {
            await speaker.SpeakAsync("Algo salió mal.");
            Notify("Error: " + e.Message);
        }
        finally
        {
            media.Duck(false);
            sphere!.SetState(VoiceState.Idle);
            Interlocked.Exchange(ref busy, 0);
        }
    }

    private async Task<string> Execute(Command command)
    {
        // Antes de tocar el volumen o la música, devolver el volumen de las apps.
        media.Duck(false);
        return command switch
        {
            Command.Play p => await media.Play(p.Query, p.Service, settings.MusicService),
            Command.Pause => await media.Pause(),
            Command.Resume => await media.Resume(),
            Command.Stop => await media.Stop(),
            Command.Next => await media.Next(),
            Command.Previous => await media.Previous(),
            Command.VolumeSet v => media.SetVolume(v.Percent),
            Command.VolumeUp u => media.ChangeVolume(u.Points ?? 10),
            Command.VolumeDown d => media.ChangeVolume(-(d.Points ?? 10)),
            Command.Mute => media.SetMute(true),
            Command.Unmute => media.SetMute(false),
            Command.WhatIsPlaying => await media.WhatIsPlaying(),
            Command.OpenService o => media.OpenService(o.Service),
            Command.Cancel => "Listo.",
            _ => "No te entendí. Pedime música o volumen.",
        };
    }

    // ------------------------------------------------------------------ Bandeja del sistema

    private void CreateTray()
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("Hablar ahora (Ctrl+Alt+L)", null, (_, _) => _ = OnWake());
        var wake = new Forms.ToolStripMenuItem("Escuchar «Lucy»") { Checked = settings.WakeWordEnabled, CheckOnClick = true };
        wake.CheckedChanged += (_, _) => { settings.WakeWordEnabled = wake.Checked; settings.Save(); };
        menu.Items.Add(wake);

        var service = new Forms.ToolStripMenuItem("Música en");
        foreach (var (id, label) in new[] { ("youtube", "YouTube"), ("youtube music", "YouTube Music"), ("spotify", "Spotify") })
        {
            var item = new Forms.ToolStripMenuItem(label) { Checked = settings.MusicService == id };
            item.Click += (_, _) =>
            {
                settings.MusicService = id;
                settings.Save();
                foreach (Forms.ToolStripMenuItem other in service.DropDownItems) other.Checked = other == item;
            };
            service.DropDownItems.Add(item);
        }
        menu.Items.Add(service);

        var sensitivity = new Forms.ToolStripMenuItem("Sensibilidad");
        foreach (var (value, label) in new[] { (0.3, "Baja"), (0.5, "Media"), (0.8, "Alta"), (0.9, "Máxima") })
        {
            var item = new Forms.ToolStripMenuItem(label) { Checked = Math.Abs(settings.Sensitivity - value) < 0.01 };
            item.Click += (_, _) =>
            {
                settings.Sensitivity = value;
                settings.Save();
                foreach (Forms.ToolStripMenuItem other in sensitivity.DropDownItems) other.Checked = other == item;
            };
            sensitivity.DropDownItems.Add(item);
        }
        menu.Items.Add(sensitivity);

        var always = new Forms.ToolStripMenuItem("Esfera siempre visible") { Checked = settings.SphereAlwaysVisible, CheckOnClick = true };
        always.CheckedChanged += (_, _) =>
        {
            settings.SphereAlwaysVisible = always.Checked;
            settings.Save();
            sphere?.SetState(VoiceState.Idle);
        };
        menu.Items.Add(always);
        menu.Items.Add("Mover la esfera", null, (_, _) => sphere?.ShowForMoving());
        menu.Items.Add("Esfera a la esquina", null, (_, _) => { sphere?.ResetPosition(); sphere?.ShowForMoving(); });

        var startup = new Forms.ToolStripMenuItem("Iniciar con Windows") { Checked = Settings.StartsWithWindows, CheckOnClick = true };
        startup.CheckedChanged += (_, _) => Settings.StartsWithWindows = startup.Checked;
        menu.Items.Add(startup);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Salir", null, (_, _) => Exit());

        tray = new Forms.NotifyIcon
        {
            Icon = MakeIcon(),
            Text = "Lucy · asistente de voz",
            ContextMenuStrip = menu,
            Visible = true,
        };
        tray.DoubleClick += (_, _) => _ = OnWake();
    }

    private void Notify(string text) => tray?.ShowBalloonTip(4000, "Lucy", text, Forms.ToolTipIcon.None);

    private void Exit()
    {
        listener?.Dispose();
        speaker?.Dispose();
        if (tray != null) tray.Visible = false;
        UnregisterHotKey(IntPtr.Zero, HotkeyId);
        app.Shutdown();
    }

    /// <summary>Ícono generado (una esfera violeta-celeste): no hace falta archivo .ico.</summary>
    private static Icon MakeIcon()
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var path = new System.Drawing.Drawing2D.GraphicsPath();
            path.AddEllipse(2, 2, 28, 28);
            using var brush = new System.Drawing.Drawing2D.PathGradientBrush(path)
            {
                CenterColor = System.Drawing.Color.FromArgb(255, 120, 230, 255),
                SurroundColors = new[] { System.Drawing.Color.FromArgb(255, 140, 90, 255) },
            };
            g.FillEllipse(brush, 2, 2, 28, 28);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }

    // ------------------------------------------------------------------ Atajo global Ctrl+Alt+L (sin admin)

    private const int HotkeyId = 0x4C55;

    [DllImport("user32.dll")] private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint modifiers, uint vk);
    [DllImport("user32.dll")] private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    private void RegisterHotkey()
    {
        const uint MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_NOREPEAT = 0x4000;
        RegisterHotKey(IntPtr.Zero, HotkeyId, MOD_ALT | MOD_CONTROL | MOD_NOREPEAT, 0x4C);
        ComponentDispatcher.ThreadPreprocessMessage += (ref MSG msg, ref bool handled) =>
        {
            if (msg.message == 0x0312 && msg.wParam.ToInt32() == HotkeyId)
            {
                handled = true;
                _ = OnWake();
            }
        };
    }
}
