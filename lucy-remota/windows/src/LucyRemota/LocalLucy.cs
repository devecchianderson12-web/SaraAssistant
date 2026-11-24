using System.Diagnostics;
using System.IO.Compression;
using System.Net.Sockets;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace LucyRemota;

/// <summary>
/// «Lucy en esta PC»: la Lucy de Windows completa (escucha «Lucy», voz, reconocimiento sin Internet,
/// IA, habilidades, esfera, todos sus ajustes) funcionando sin teléfono. Es un componente aparte
/// («LucyRemota-Local-vX.Y.Z.zip» del release) que se baja una vez al activarla; Lucy Remota la abre,
/// la cierra, la actualiza y le cambia el micrófono. Sus datos siguen en %APPDATA%\Lucy.
/// </summary>
sealed partial class LocalLucy
{
    public enum State { Off, Downloading, Starting, Running, Failed }

    const string Repo = "devecchianderson12-web/SaraAssistant";
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(15) };
    readonly object gate = new();
    Process? process;
    bool stopping;

    public State Current { get; private set; } = State.Off;
    public string Detail { get; private set; } = "";
    public event Action? Changed;

    [GeneratedRegex(@"^LucyRemota-Local-v(\d+)\.(\d+)\.(\d+)\.zip$", RegexOptions.IgnoreCase)]
    private static partial Regex AssetName();

    static string Root => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LucyRemota", "local");
    static string LucyHome => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Lucy");

    /// <summary>Versión instalada (la carpeta más nueva con «.ok»), o null.</summary>
    public static (int code, string dir)? Installed()
    {
        if (!Directory.Exists(Root)) return null;
        return Directory.GetDirectories(Root)
            .Where(d => File.Exists(Path.Combine(d, ".ok")) && File.Exists(Path.Combine(d, "Lucy.exe")))
            .Select(d => (code: Code(Path.GetFileName(d)), dir: d))
            .Where(x => x.code > 0)
            .OrderByDescending(x => x.code)
            .Select(x => ((int, string)?)x)
            .FirstOrDefault();
    }

    static int Code(string version)
    {
        var p = version.Split('.').Select(x => int.TryParse(x, out var n) ? n : -1).ToArray();
        return p.Length == 3 && p.All(n => n >= 0) ? Updater.Code(p[0], p[1], p[2]) : 0;
    }

    void Set(State s, string detail = "")
    {
        Current = s;
        Detail = detail;
        Changed?.Invoke();
    }

    /// <summary>La versión publicada más nueva (nombre, url, código), o null.</summary>
    static async Task<(string name, string url, int code)?> LatestAsync(CancellationToken ct)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, $"https://api.github.com/repos/{Repo}/releases?per_page=30");
            req.Headers.TryAddWithoutValidation("User-Agent", "LucyRemota");
            using var res = await Http.SendAsync(req, ct);
            res.EnsureSuccessStatusCode();
            (string, string, int)? best = null;
            foreach (var r in JsonNode.Parse(await res.Content.ReadAsStringAsync(ct))!.AsArray())
            foreach (var a in r?["assets"]?.AsArray() ?? [])
            {
                var name = a?["name"]?.GetValue<string>() ?? "";
                var m = AssetName().Match(name);
                if (!m.Success) continue;
                var code = Updater.Code(int.Parse(m.Groups[1].Value), int.Parse(m.Groups[2].Value), int.Parse(m.Groups[3].Value));
                var url = a!["browser_download_url"]?.GetValue<string>();
                if (url != null && url.StartsWith("https://github.com/", StringComparison.Ordinal) && (best == null || code > best.Value.Item3)) best = (name, url, code);
            }
            return best;
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException or InvalidOperationException)
        {
            Log.Write("Lucy en esta PC: no pude buscar la versión: " + e.Message);
            return null;
        }
    }

    /// <summary>Baja e instala la versión publicada si no está o hay una más nueva. Devuelve la carpeta lista.</summary>
    async Task<string?> EnsureInstalledAsync(bool update, CancellationToken ct)
    {
        var installed = Installed();
        if (installed != null && !update) return installed.Value.dir;
        var latest = await LatestAsync(ct);
        if (latest == null) return installed?.dir;
        if (installed != null && installed.Value.code >= latest.Value.code) return installed.Value.dir;
        var version = latest.Value.name.Replace("LucyRemota-Local-v", "", StringComparison.OrdinalIgnoreCase).Replace(".zip", "", StringComparison.OrdinalIgnoreCase);
        Set(State.Downloading, "Bajando Lucy para esta PC… (una sola vez)");
        Directory.CreateDirectory(Root);
        var zip = Path.Combine(Root, latest.Value.name + ".part");
        var dir = Path.Combine(Root, version);
        try
        {
            using (var res = await Http.GetAsync(latest.Value.url, HttpCompletionOption.ResponseHeadersRead, ct))
            {
                res.EnsureSuccessStatusCode();
                var total = res.Content.Headers.ContentLength ?? 0;
                await using var input = await res.Content.ReadAsStreamAsync(ct);
                await using var output = File.Create(zip);
                var buf = new byte[1 << 16];
                long done = 0;
                int n;
                var lastPct = -1;
                while ((n = await input.ReadAsync(buf, ct)) > 0)
                {
                    await output.WriteAsync(buf.AsMemory(0, n), ct);
                    done += n;
                    var pct = total > 0 ? (int)(done * 100 / total) : -1;
                    if (pct != lastPct && pct % 5 == 0) { lastPct = pct; Set(State.Downloading, $"Bajando Lucy para esta PC… {pct} %"); }
                }
            }
            Set(State.Downloading, "Preparando Lucy para esta PC…");
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
            await Task.Run(() => ZipFile.ExtractToDirectory(zip, dir), ct);
            File.WriteAllText(Path.Combine(dir, ".ok"), version);
            File.Delete(zip);
            // Las versiones viejas ya no hacen falta.
            foreach (var old in Directory.GetDirectories(Root).Where(d => !string.Equals(d, dir, StringComparison.OrdinalIgnoreCase)))
                try { Directory.Delete(old, true); } catch { }
            Log.Write($"Lucy en esta PC: instalada la V{version}");
            return dir;
        }
        catch (Exception e) when (e is HttpRequestException or IOException or UnauthorizedAccessException or InvalidDataException or TaskCanceledException)
        {
            try { File.Delete(zip); } catch { }
            Log.Write("Lucy en esta PC: no se pudo instalar: " + e.Message);
            return installed?.dir;
        }
    }

    /// <summary>Activa Lucy en esta PC: la baja si hace falta y la abre (escuchando «Lucy»).</summary>
    public async Task StartAsync(bool showWindow = false, bool update = false, CancellationToken ct = default)
    {
        lock (gate)
        {
            if (process is { HasExited: false }) { if (showWindow) Send("show"); return; }
            stopping = false;
        }
        Set(State.Starting, "Abriendo Lucy…");
        var dir = await EnsureInstalledAsync(update, ct);
        if (dir == null) { Set(State.Failed, "No pude bajar Lucy para esta PC. Revisá la conexión a Internet y probá de nuevo."); return; }
        try
        {
            var psi = new ProcessStartInfo(Path.Combine(dir, "Lucy.exe")) { UseShellExecute = false, WorkingDirectory = dir };
            psi.ArgumentList.Add("--embebida");
            if (showWindow) psi.ArgumentList.Add("--mostrar");
            var p = Process.Start(psi)!;
            p.EnableRaisingEvents = true;
            p.Exited += (_, _) => OnExited(p);
            lock (gate) process = p;
            Set(State.Running, "Escuchando «Lucy» en esta PC.");
            Log.Write("Lucy en esta PC: abierta");
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or IOException)
        {
            Set(State.Failed, "No pude abrir Lucy: " + e.Message);
        }
    }

    void OnExited(Process p)
    {
        lock (gate) { if (process != p) return; process = null; }
        if (stopping) { Set(State.Off); return; }
        // Se cerró sola (o se colgó): si sigue activada, se vuelve a abrir.
        Log.Write($"Lucy en esta PC se cerró (código {SafeExit(p)}); la vuelvo a abrir");
        Set(State.Starting, "Lucy se cerró; volviendo a abrirla…");
        _ = Task.Delay(3000).ContinueWith(_ => { if (!stopping && AppSettings.Current.LocalLucy) _ = StartAsync(); });
    }

    static string SafeExit(Process p) { try { return p.ExitCode.ToString(); } catch { return "?"; } }

    /// <summary>Desactiva Lucy en esta PC (deja de escuchar y se cierra).</summary>
    public async Task StopAsync()
    {
        Process? p;
        lock (gate) { stopping = true; p = process; }
        if (p == null) { Set(State.Off); return; }
        Send("quit");
        try { await p.WaitForExitAsync(new CancellationTokenSource(TimeSpan.FromSeconds(4)).Token); }
        catch (OperationCanceledException) { try { p.Kill(entireProcessTree: true); } catch { } }
        lock (gate) process = null;
        Set(State.Off);
    }

    public bool IsRunning { get { lock (gate) return process is { HasExited: false }; } }

    /// <summary>Muestra la ventana de Lucy (con su historial, la esfera y «Hablar»).</summary>
    public void Show() => Send("show");
    public void OpenSettings() => Send("settings");
    public void Talk() => Send("talk");

    /// <summary>Le manda una orden a la Lucy abierta por su canal local (sólo en esta PC).</summary>
    static void Send(string command)
    {
        try
        {
            var port = int.Parse(File.ReadAllText(Path.Combine(LucyHome, "lucy.port")).Trim());
            using var client = new TcpClient();
            client.Connect(System.Net.IPAddress.Loopback, port);
            using var s = client.GetStream();
            var bytes = System.Text.Encoding.UTF8.GetBytes(command + "\n");
            s.Write(bytes);
        }
        catch (Exception e) when (e is IOException or SocketException or FormatException or UnauthorizedAccessException)
        {
            Log.Write($"Lucy en esta PC: no pude mandar «{command}»: {e.Message}");
        }
    }

    /// <summary>Los micrófonos tal como los ve Lucy (los mismos nombres que guarda en sus ajustes).</summary>
    public async Task<List<string>> MicrophonesAsync()
    {
        var dir = Installed()?.dir;
        if (dir == null) return [];
        try
        {
            var psi = new ProcessStartInfo(Path.Combine(dir, "Lucy.exe")) { UseShellExecute = false, RedirectStandardOutput = true, CreateNoWindow = true, WorkingDirectory = dir };
            psi.ArgumentList.Add("--microfonos");
            using var p = Process.Start(psi)!;
            var output = await p.StandardOutput.ReadToEndAsync();
            await p.WaitForExitAsync(new CancellationTokenSource(TimeSpan.FromSeconds(30)).Token);
            var json = output.Split('\n').LastOrDefault(l => l.TrimStart().StartsWith('[')) ?? "[]";
            return JsonNode.Parse(json)!.AsArray().Select(n => n!.GetValue<string>()).ToList();
        }
        catch (Exception e)
        {
            Log.Write("Lucy en esta PC: no pude leer los micrófonos: " + e.Message);
            return [];
        }
    }

    /// <summary>El micrófono elegido en los ajustes de Lucy («» = el de Windows).</summary>
    public static string CurrentMicrophone()
    {
        try { return JsonNode.Parse(File.ReadAllText(Path.Combine(LucyHome, "settings.json")))?["microphone"]?.GetValue<string>() ?? ""; }
        catch (Exception) { return ""; }
    }

    /// <summary>Cambia el micrófono de Lucy (se guarda en sus ajustes y se reabre para usarlo).</summary>
    public async Task SetMicrophoneAsync(string name)
    {
        var file = Path.Combine(LucyHome, "settings.json");
        var wasRunning = IsRunning;
        if (wasRunning) await StopAsync();
        try
        {
            Directory.CreateDirectory(LucyHome);
            var json = File.Exists(file) ? JsonNode.Parse(File.ReadAllText(file))!.AsObject() : new JsonObject();
            json["microphone"] = name;
            File.WriteAllText(file, json.ToJsonString());
            Log.Write($"Lucy en esta PC: micrófono «{(name.Length > 0 ? name : "el de Windows")}»");
        }
        catch (Exception e) { Log.Write("Lucy en esta PC: no pude guardar el micrófono: " + e.Message); }
        if (wasRunning || AppSettings.Current.LocalLucy) await StartAsync();
    }

    /// <summary>Si hay una versión nueva del componente, la instala y reabre Lucy (sólo si está activada).</summary>
    public async Task UpdateIfNeededAsync()
    {
        if (!AppSettings.Current.LocalLucy) return;
        var installed = Installed();
        var latest = await LatestAsync(default);
        if (installed == null || latest == null || latest.Value.code <= installed.Value.code) return;
        await StopAsync();
        await StartAsync(update: true);
    }
}
