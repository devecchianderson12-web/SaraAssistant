using LucyRemota.Core.Commands;
using LucyRemota.Core.Link;
using LucyRemota.Modules;
using LucyRemota.UI;

namespace LucyRemota;

static class Program
{
    [STAThread]
    static int Main(string[] args)
    {
        // «--autoprueba <carpeta>»: prueba completa sin teléfono (la usa la compilación en la nube).
        var selftest = Array.IndexOf(args, "--autoprueba");
        if (selftest >= 0)
        {
            Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
            Application.EnableVisualStyles();
            return SelfTest.Run(args.Length > selftest + 1 ? args[selftest + 1] : "autoprueba");
        }
        // «--reemplazar <viejo.exe>»: la abrió la versión anterior al actualizarse; se espera a que se cierre.
        var replace = Array.IndexOf(args, "--reemplazar");
        var oldExe = replace >= 0 && args.Length > replace + 1 ? args[replace + 1] : null;
        // Una sola Lucy Remota por usuario: si ya está abierta, se muestra esa.
        var single = new Mutex(true, @"Local\LucyRemota.Receptor", out var first);
        for (var i = 0; !first && oldExe != null && i < 40; i++)
        {
            single.Dispose();
            Thread.Sleep(500);
            single = new Mutex(true, @"Local\LucyRemota.Receptor", out first);
        }
        using var _single = single;
        using var showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, @"Local\LucyRemota.Receptor.Mostrar");
        if (!first)
        {
            showSignal.Set();
            return 0;
        }
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.ThreadException += (_, e) => Log.Write("Error: " + e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log.Write("Error grave: " + e.ExceptionObject);
        TaskScheduler.UnobservedTaskException += (_, e) => { Log.Write("Error en segundo plano: " + e.Exception.GetBaseException().Message); e.SetObserved(); };

        Log.Write($"Lucy Remota · Receptor {Application.ProductVersion} — datos en {Paths.Data}");
        var store = new PcStore();
        var history = new History();
        var apps = new AppsModule();
        var registry = new CommandRegistry([new AudioModule(), new MediaModule(apps), apps, new SystemModule(), new InfoModule()]);
        registry.Logged += history.Add;
        if (oldExe != null) Updater.FinishReplace(oldExe);
        var link = new PcLink(store, registry, Log.Write);
        void ApplyRelay()
        {
            var s = AppSettings.Current;
            link.RelayUrl = s.UseRelay && Uri.TryCreate(s.RelayUrl, UriKind.Absolute, out var u) && u.Scheme is "https" or "http" ? s.RelayUrl : null;
            Log.Write(link.RelayUrl != null ? "Relé para redes distintas: " + link.RelayUrl : "Relé desactivado (sólo red local)");
        }
        ApplyRelay();
        AppSettings.Changed += ApplyRelay;
        apps.Preload();
        link.Start();

        var minimized = args.Contains("--minimizada") || AppSettings.Current.StartMinimized;
        var form = new MainForm(link, store, history, apps, startHidden: minimized);
        // Otra copia pidió mostrarse.
        ThreadPool.RegisterWaitForSingleObject(showSignal, (_, _) => form.BeginInvoke(form.ShowFromTray), null, -1, false);
        Application.Run(form);
        link.DisposeAsync().AsTask().Wait(2000);
        return 0;
    }
}
