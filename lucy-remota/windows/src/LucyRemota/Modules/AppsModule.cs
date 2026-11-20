using System.Diagnostics;
using System.Text.Json.Nodes;
using LucyRemota.Core.Commands;
using LucyRemota.Native;

namespace LucyRemota.Modules;

/// <summary>Un programa del menú Inicio: nombre visible e identificador para abrirlo.</summary>
sealed record InstalledApp(string Name, string AppId);

/// <summary>
/// Aplicaciones de Windows: abrir, cerrar y traer al frente. Sólo se abren programas que están en
/// el menú Inicio (la lista la arma Windows); nunca se ejecuta un nombre como comando.
/// </summary>
sealed class AppsModule : ISkillModule
{
    List<InstalledApp>? cache;
    DateTime cachedAt;
    readonly SemaphoreSlim cacheLock = new(1, 1);

    public string Name => "apps";

    public IReadOnlyList<IntentSpec> Intents { get; } =
    [
        new("OPEN_APPLICATION", "apps", "Abre un programa instalado.", Risk.Change, M.P(("name", new(ParamType.String, Required: true, MaxLength: 80)))),
        new("CLOSE_APPLICATION", "apps", "Cierra un programa abierto (como si tocaras la X).", Risk.Change, M.P(("name", new(ParamType.String, Required: true, MaxLength: 80)))),
        new("FOCUS_APPLICATION", "apps", "Trae al frente un programa abierto.", Risk.Change, M.P(("name", new(ParamType.String, Required: true, MaxLength: 80)))),
        new("LIST_APPLICATIONS", "apps", "Lista los programas instalados.", Risk.Info),
    ];

    /// <summary>Lee la lista de apps en segundo plano al arrancar (así la primera orden es rápida).</summary>
    public void Preload() => _ = Apps();

    public async Task<Outcome> ExecuteAsync(string intent, Params p, CancellationToken ct)
    {
        if (intent == "LIST_APPLICATIONS")
        {
            var list = await Apps();
            return Outcome.Ok($"Hay {list.Count} programas.", new JsonObject { ["apps"] = new JsonArray(list.Select(a => (JsonNode)a.Name).ToArray()) });
        }
        var name = p.Str("name")!;
        switch (intent)
        {
            case "OPEN_APPLICATION":
            {
                var app = NameMatcher.Best(name, await Apps(), a => a.Name);
                if (app == null) return new Outcome(Status.Error, $"No encontré {name} instalado en la computadora.", null, "not_installed");
                // Una app web (YouTube Music de Chrome, por ejemplo) ya abierta: se trae su ventana en lugar de abrir otra.
                if (WebMusic.FindWindow(app.AppId) is var open and not 0)
                {
                    Win32.BringToFront(open);
                    return Outcome.Ok($"{app.Name} ya estaba abierto: te lo muestro.", new JsonObject { ["app"] = app.Name });
                }
                Launch(app);
                return Outcome.Ok($"Abriendo {app.Name} en tu computadora.", new JsonObject { ["app"] = app.Name });
            }
            case "CLOSE_APPLICATION":
            {
                var procs = await WindowsOf(name);
                if (procs.Count == 0) return new Outcome(Status.Error, $"{name} no está abierto.", null, "not_running");
                var label = procs[0].label;
                foreach (var (proc, _) in procs) { try { proc.CloseMainWindow(); } catch { } }
                return Outcome.Ok($"Listo, cerré {label}.", new JsonObject { ["app"] = label, ["windows"] = procs.Count });
            }
            default:
            {
                var procs = await WindowsOf(name);
                if (procs.Count == 0) return new Outcome(Status.Error, $"{name} no está abierto.", null, "not_running");
                Win32.BringToFront(procs[0].proc.MainWindowHandle);
                return Outcome.Ok($"Listo, te muestro {procs[0].label}.");
            }
        }
    }

    public InstalledApp? Find(string name) => cache == null ? null : NameMatcher.Best(name, cache, a => a.Name, 0.8);

    /// <summary>Apps de música, en orden de preferencia: la de YouTube Music instalada (Chrome/Edge), Spotify, las demás y el reproductor de Windows.</summary>
    static readonly string[][] MusicPriority =
    [
        ["youtube music"], ["spotify"], ["deezer"], ["amazon music"], ["apple music"], ["tidal"], ["soundcloud"],
        ["media player", "reproductor multimedia", "groove"],
    ];

    /// <summary>
    /// La app de música a usar: la pedida («spotify»/«youtube»), la elegida en Ajustes o la mejor
    /// instalada (YouTube Music de Chrome/Edge, Spotify, …).
    /// </summary>
    public InstalledApp? MusicApp(string? wanted)
    {
        var list = cache ?? Apps().GetAwaiter().GetResult();
        InstalledApp? Match(string[] names) => list.FirstOrDefault(a => names.Any(n => NameMatcher.Normalize(a.Name).Contains(n)));
        if (wanted == "spotify") return Match(["spotify"]);
        if (wanted == "youtube") return Match(["youtube music"]);
        if (AppSettings.Current.MusicApp is { Length: > 0 } chosen && list.FirstOrDefault(a => a.Name == chosen) is { } picked) return picked;
        return MusicPriority.Select(Match).FirstOrDefault(a => a != null);
    }

    /// <summary>Las apps de música instaladas (para elegir en Ajustes).</summary>
    public async Task<List<InstalledApp>> MusicAppsAsync()
    {
        var list = await Apps();
        return MusicPriority.SelectMany(names => list.Where(a => names.Any(n => NameMatcher.Normalize(a.Name).Contains(n))))
            .DistinctBy(a => a.Name).ToList();
    }

    public void Launch(InstalledApp app) => M.ShellOpen("explorer.exe", "shell:AppsFolder\\" + app.AppId);

    async Task<List<InstalledApp>> Apps()
    {
        await cacheLock.WaitAsync();
        try
        {
            if (cache != null && DateTime.UtcNow - cachedAt < TimeSpan.FromMinutes(10)) return cache;
            cache = await Sta.Run(() =>
            {
                var list = new List<InstalledApp>();
                var shellType = Type.GetTypeFromProgID("Shell.Application")!;
                dynamic shell = Activator.CreateInstance(shellType)!;
                dynamic folder = shell.NameSpace("shell:AppsFolder");
                foreach (var item in folder.Items())
                {
                    string n = item.Name;
                    string id = item.Path;
                    if (string.IsNullOrWhiteSpace(n) || string.IsNullOrWhiteSpace(id)) continue;
                    // Desinstaladores, ayudas y documentación no son «programas» que alguien pida abrir.
                    var low = n.ToLowerInvariant();
                    if (low.Contains("uninstall") || low.Contains("desinstalar") || low.EndsWith(" help") || low.Contains("readme")) continue;
                    list.Add(new InstalledApp(n, id));
                }
                return list;
            });
            cachedAt = DateTime.UtcNow;
            return cache;
        }
        finally { cacheLock.Release(); }
    }

    /// <summary>Ventanas abiertas del programa pedido (por nombre de proceso o título de la ventana).</summary>
    async Task<List<(Process proc, string label)>> WindowsOf(string name)
    {
        var installed = NameMatcher.Best(name, await Apps(), a => a.Name);
        var exe = installed?.AppId.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) == true ? Path.GetFileNameWithoutExtension(installed.AppId) : null;
        var me = Environment.ProcessId;
        var result = new List<(Process, string)>();
        foreach (var p in Process.GetProcesses())
        {
            try
            {
                if (p.Id == me || p.MainWindowHandle == 0 || p.ProcessName.Equals("explorer", StringComparison.OrdinalIgnoreCase)) continue;
                var byExe = exe != null && p.ProcessName.Equals(exe, StringComparison.OrdinalIgnoreCase);
                var byName = NameMatcher.Score(name, p.ProcessName) >= 0.8 || (installed != null && NameMatcher.Score(installed.Name, p.MainWindowTitle) >= 0.8);
                var byTitle = p.MainWindowTitle.Length > 0 && NameMatcher.Normalize(p.MainWindowTitle).Contains(NameMatcher.Normalize(name));
                if (byExe || byName || byTitle) result.Add((p, installed?.Name ?? p.ProcessName));
            }
            catch { }
        }
        return result;
    }
}
