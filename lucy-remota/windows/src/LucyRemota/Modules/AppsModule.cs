using System.Diagnostics;
using System.Text.Json.Nodes;
using LucyRemota.Core.Commands;
using LucyRemota.Native;

namespace LucyRemota.Modules;

/// <summary>
/// Un programa del menú Inicio (o del escritorio): nombre visible e identificador para abrirlo.
/// [Shortcut]: su acceso directo. [WebId]: si es una app web instalada desde Chrome/Edge (como
/// YouTube Music), su id de Chrome (32 letras) o «app:» + la dirección.
/// </summary>
sealed record InstalledApp(string Name, string AppId, string? Shortcut = null, string? WebId = null)
{
    public bool IsWebApp => WebId != null;
}

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
                if (app.IsWebApp && WebMusic.FindWindow(app) is var open and not 0)
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
    ];

    /// <summary>Reproductores de Windows: sólo si los elegís en Ajustes (solos no ponen nada sin canciones guardadas).</summary>
    static readonly string[] LocalPlayers = ["media player", "reproductor multimedia", "groove"];

    /// <summary>
    /// Sin app de música instalada: YouTube Music en una ventana de app del navegador (Chrome o Edge,
    /// con tu sesión), sin pestañas. Se maneja igual que la instalada.
    /// </summary>
    public static readonly InstalledApp BrowserMusic = new("YouTube Music", "web:youtube-music", WebId: "app:https://music.youtube.com/");

    /// <summary>
    /// La app de música a usar: la pedida («spotify»/«youtube»), la elegida en Ajustes o la mejor
    /// instalada (YouTube Music de Chrome/Edge, Spotify, …).
    /// </summary>
    public InstalledApp? MusicApp(string? wanted)
    {
        var list = cache ?? Apps().GetAwaiter().GetResult();
        InstalledApp? Match(string[] names) => list.FirstOrDefault(a => names.Any(n => NameMatcher.Normalize(a.Name).Contains(n)));
        // «YouTube Music» que no se reconoce como app web (otro tipo de acceso directo): igual se usa
        // YouTube Music en una ventana de app, nunca YouTube común ni una carpeta.
        InstalledApp? Fix(InstalledApp? a) => a != null && !a.IsWebApp && NameMatcher.Normalize(a.Name).Contains("youtube") ? BrowserMusic : a;
        if (wanted == "spotify") return Match(["spotify"]);
        if (wanted == "youtube") return Fix(Match(["youtube music"])) ?? BrowserMusic;
        if (AppSettings.Current.MusicApp is { Length: > 0 } chosen && list.FirstOrDefault(a => a.Name == chosen) is { } picked) return Fix(picked);
        return Fix(MusicPriority.Select(Match).FirstOrDefault(a => a != null)) ?? BrowserMusic;
    }

    /// <summary>Las apps de música instaladas (para elegir en Ajustes).</summary>
    public async Task<List<InstalledApp>> MusicAppsAsync()
    {
        var list = await Apps();
        return MusicPriority.Append(LocalPlayers).SelectMany(names => list.Where(a => names.Any(n => NameMatcher.Normalize(a.Name).Contains(n))))
            .DistinctBy(a => a.Name).ToList();
    }

    /// <summary>Abre el programa como desde su ícono (el acceso directo, si lo hay: así la app web abre con su perfil de Chrome).</summary>
    public void Launch(InstalledApp app)
    {
        if (app.Shortcut != null && File.Exists(app.Shortcut)) M.ShellOpen(app.Shortcut);
        else if (app.WebId is { } w && w.StartsWith("app:https://", StringComparison.Ordinal) && !File.Exists(app.AppId))
        {
            // Ventana de app del navegador (sin barra ni pestañas), con el perfil de siempre.
            var browser = BrowserPath("chrome.exe") ?? BrowserPath("msedge.exe");
            if (browser != null) { using var _ = Process.Start(new ProcessStartInfo(browser) { UseShellExecute = false, ArgumentList = { "--app=" + w[4..] } }); }
            else M.ShellOpen(w[4..]);
        }
        else if (Path.IsPathRooted(app.AppId) && File.Exists(app.AppId)) M.ShellOpen(app.AppId);
        else if (ResolveKnownFolder(app.AppId) is { } path) M.ShellOpen(path);
        // Un id de Windows de verdad (no una ruta): si no, el Explorador abriría Documentos.
        else if (!app.AppId.Contains('\\') && !app.AppId.Contains(':')) M.ShellOpen("explorer.exe", "shell:AppsFolder\\" + app.AppId);
        else throw new InvalidOperationException($"no sé cómo abrir {app.Name}");
    }

    /// <summary>«{6D809377-…}\Google\Chrome\chrome.exe» (así lista Windows algunos programas) → la ruta real.</summary>
    static string? ResolveKnownFolder(string id)
    {
        var m = System.Text.RegularExpressions.Regex.Match(id, @"^\{([0-9A-Fa-f-]{36})\}\\(.+)$");
        if (!m.Success) return null;
        try
        {
            var guid = Guid.Parse(m.Groups[1].Value);
            if (Native.KnownFolders.Path(guid) is not { } root) return null;
            var full = Path.Combine(root, m.Groups[2].Value);
            return File.Exists(full) ? full : null;
        }
        catch (Exception) { return null; }
    }

    static string? BrowserPath(string exe)
    {
        foreach (var root in new[] { Microsoft.Win32.Registry.CurrentUser, Microsoft.Win32.Registry.LocalMachine })
        {
            using var k = root.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\" + exe);
            if (k?.GetValue(null) is string path && File.Exists(path)) return path;
        }
        var guess = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Microsoft", "Edge", "Application", exe);
        return File.Exists(guess) ? guess : null;
    }

    /// <summary>Vuelve a leer la lista de programas (por ejemplo, después de instalar uno).</summary>
    public void Refresh() { cache = null; }

    static readonly string[] Browsers = ["chrome.exe", "chrome_proxy.exe", "msedge.exe", "msedge_proxy.exe", "brave.exe", "brave_proxy.exe"];

    /// <summary>
    /// Accesos directos de apps web (los que crea Chrome/Edge al «instalar» una página, como YouTube
    /// Music): en el menú Inicio y en el escritorio. Se leen sus argumentos (--app-id=…).
    /// </summary>
    static List<InstalledApp> WebAppShortcuts()
    {
        var found = new List<InstalledApp>();
        var dirs = new[]
        {
            Environment.SpecialFolder.StartMenu, Environment.SpecialFolder.CommonStartMenu,
            Environment.SpecialFolder.DesktopDirectory, Environment.SpecialFolder.CommonDesktopDirectory,
        }.Select(Environment.GetFolderPath).Where(d => d.Length > 0 && Directory.Exists(d)).Distinct();
        dynamic? wsh = null;
        try { wsh = Activator.CreateInstance(Type.GetTypeFromProgID("WScript.Shell")!); } catch { return found; }
        foreach (var dir in dirs)
        {
            IEnumerable<string> links;
            try { links = Directory.EnumerateFiles(dir, "*.lnk", new EnumerationOptions { RecurseSubdirectories = true, MaxRecursionDepth = 3, IgnoreInaccessible = true }).ToList(); }
            catch (Exception) { continue; }
            foreach (var lnk in links)
            {
                try
                {
                    dynamic sc = wsh!.CreateShortcut(lnk);
                    string target = sc.TargetPath ?? "";
                    string args = sc.Arguments ?? "";
                    if (!Browsers.Contains(Path.GetFileName(target), StringComparer.OrdinalIgnoreCase)) continue;
                    var id = System.Text.RegularExpressions.Regex.Match(args, @"--app-id=([a-p]{32})");
                    var url = System.Text.RegularExpressions.Regex.Match(args, @"--app=(\S+)");
                    var webId = id.Success ? id.Groups[1].Value : url.Success ? "app:" + url.Groups[1].Value.Trim('"') : null;
                    if (webId != null) found.Add(new InstalledApp(Path.GetFileNameWithoutExtension(lnk), lnk, lnk, webId));
                }
                catch (Exception) { }
            }
        }
        return found;
    }

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
                    list.Add(new InstalledApp(n, id, WebId: WebMusic.WebAppId(id)));
                }
                // Apps web (YouTube Music de Chrome…): se completan con su acceso directo, y se suman las
                // que sólo están en el escritorio.
                foreach (var web in WebAppShortcuts())
                {
                    var i = list.FindIndex(a => a.Name.Equals(web.Name, StringComparison.OrdinalIgnoreCase));
                    if (i >= 0) list[i] = list[i] with { Shortcut = list[i].Shortcut ?? web.Shortcut, WebId = list[i].WebId ?? web.WebId };
                    else list.Add(web);
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
