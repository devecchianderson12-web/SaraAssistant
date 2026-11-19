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
        // Una sola Lucy Remota por usuario: si ya está abierta, se muestra esa.
        using var single = new Mutex(true, @"Local\LucyRemota.Receptor", out var first);
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
        var link = new PcLink(store, registry, Log.Write);
        apps.Preload();
        link.Start();

        var minimized = args.Contains("--minimizada");
        var form = new MainForm(link, store, history, startHidden: minimized);
        // Otra copia pidió mostrarse.
        ThreadPool.RegisterWaitForSingleObject(showSignal, (_, _) => form.BeginInvoke(form.ShowFromTray), null, -1, false);
        Application.Run(form);
        link.DisposeAsync().AsTask().Wait(2000);
        return 0;
    }
}
