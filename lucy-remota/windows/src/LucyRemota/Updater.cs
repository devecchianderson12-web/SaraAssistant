using System.Net.Http;
using System.Diagnostics;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace LucyRemota;

/// <summary>
/// Actualizaciones desde la misma app (como la Lucy de Windows): busca en el release el
/// «LucyRemota-v1.0.N.exe» más nuevo, lo baja al lado del actual, abre el nuevo y el nuevo borra el
/// viejo. Sin instalador ni administrador; los datos («LucyRemota-datos») quedan donde están.
/// Sólo mira el .exe: actualizar la PC no obliga a actualizar el teléfono (ni al revés).
/// </summary>
static partial class Updater
{
    public sealed record Release(int Version, string Url, string Name);

    const string Repo = "devecchianderson12-web/SaraAssistant";
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(15) };

    [GeneratedRegex(@"^LucyRemota-v1\.0\.(\d+)\.exe$", RegexOptions.IgnoreCase)]
    private static partial Regex ExeName();

    /// <summary>Número de esta versión (el N de 1.0.N).</summary>
    public static int CurrentVersion =>
        int.TryParse(Application.ProductVersion.Split('+')[0].Split('.').ElementAtOrDefault(2), out var n) ? n : 0;

    /// <summary>La versión más nueva publicada; null si no se pudo consultar.</summary>
    public static async Task<Release?> LatestAsync(CancellationToken ct = default)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, $"https://api.github.com/repos/{Repo}/releases?per_page=30");
            req.Headers.TryAddWithoutValidation("User-Agent", "LucyRemota");
            req.Headers.TryAddWithoutValidation("Accept", "application/vnd.github+json");
            using var res = await Http.SendAsync(req, ct);
            res.EnsureSuccessStatusCode();
            var releases = JsonNode.Parse(await res.Content.ReadAsStringAsync(ct))!.AsArray();
            Release? best = null;
            foreach (var r in releases)
            {
                if (r?["draft"]?.GetValue<bool>() == true) continue;
                foreach (var a in r?["assets"]?.AsArray() ?? [])
                {
                    var name = a?["name"]?.GetValue<string>() ?? "";
                    var m = ExeName().Match(name);
                    if (!m.Success || !int.TryParse(m.Groups[1].Value, out var v)) continue;
                    var url = a!["browser_download_url"]?.GetValue<string>();
                    if (url == null || !url.StartsWith("https://github.com/", StringComparison.Ordinal)) continue;
                    if (best == null || v > best.Version) best = new Release(v, url, name);
                }
            }
            return best;
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException or InvalidOperationException)
        {
            Log.Write("No pude buscar actualizaciones: " + e.Message);
            return null;
        }
    }

    /// <summary>Hay una más nueva que esta (null si no, o si no se pudo consultar).</summary>
    public static async Task<Release?> AvailableAsync(CancellationToken ct = default)
    {
        var latest = await LatestAsync(ct);
        return latest != null && CurrentVersion > 0 && latest.Version > CurrentVersion ? latest : null;
    }

    /// <summary>Baja la versión nueva y la abre. null si salió bien (hay que cerrar ésta) o el error.</summary>
    public static async Task<string?> InstallAsync(Release release, IProgress<int> progress, CancellationToken ct = default)
    {
        var current = Environment.ProcessPath;
        if (current == null) return "No encuentro dónde está Lucy Remota.";
        var dir = Path.GetDirectoryName(current)!;
        var target = Path.Combine(dir, release.Name);
        var tmp = target + ".part";
        try
        {
            using (var res = await new HttpClient { Timeout = TimeSpan.FromMinutes(10) }.GetAsync(release.Url, HttpCompletionOption.ResponseHeadersRead, ct))
            {
                res.EnsureSuccessStatusCode();
                var total = res.Content.Headers.ContentLength ?? 0;
                await using var input = await res.Content.ReadAsStreamAsync(ct);
                await using var output = File.Create(tmp);
                var buf = new byte[1 << 16];
                long done = 0;
                int n;
                while ((n = await input.ReadAsync(buf, ct)) > 0)
                {
                    await output.WriteAsync(buf.AsMemory(0, n), ct);
                    done += n;
                    if (total > 0) progress.Report((int)(done * 100 / total));
                }
                if (total > 0 && done != total) throw new IOException("la descarga quedó incompleta");
            }
            if (new FileInfo(tmp).Length < 5_000_000) throw new IOException("el archivo bajado no parece Lucy Remota");
            File.Move(tmp, target, overwrite: true);
            Log.Write($"Actualización bajada: {release.Name}");
            using var _ = Process.Start(new ProcessStartInfo(target) { UseShellExecute = false, ArgumentList = { "--reemplazar", current } });
            return null;
        }
        catch (Exception e) when (e is HttpRequestException or IOException or UnauthorizedAccessException or TaskCanceledException or System.ComponentModel.Win32Exception)
        {
            try { File.Delete(tmp); } catch { }
            Log.Write("Actualización fallida: " + e.Message);
            return $"No pude actualizar: {e.Message}\n\nPodés bajar la versión nueva a mano desde el release SaraAssistant en GitHub.";
        }
    }

    /// <summary>
    /// La versión nueva, recién abierta por la vieja: espera a que la vieja se cierre, la borra y
    /// actualiza el inicio automático para que apunte a ésta.
    /// </summary>
    public static void FinishReplace(string oldExe)
    {
        var me = Environment.ProcessPath;
        if (me == null || string.Equals(Path.GetFullPath(oldExe), Path.GetFullPath(me), StringComparison.OrdinalIgnoreCase)) return;
        // Sólo se borra otro «LucyRemota*.exe» de la misma carpeta (nunca un archivo cualquiera).
        if (!string.Equals(Path.GetDirectoryName(Path.GetFullPath(oldExe)), Path.GetDirectoryName(me), StringComparison.OrdinalIgnoreCase)) return;
        if (!Path.GetFileName(oldExe).StartsWith("LucyRemota", StringComparison.OrdinalIgnoreCase) || !oldExe.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) return;
        try { if (Autostart.Enabled) Autostart.Enabled = true; } catch (Exception e) { Log.Write("Inicio automático: " + e.Message); }
        _ = Task.Run(async () =>
        {
            for (var i = 0; i < 30; i++)
            {
                try
                {
                    if (!File.Exists(oldExe)) return;
                    File.Delete(oldExe);
                    Log.Write("Versión anterior borrada: " + Path.GetFileName(oldExe));
                    return;
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException) { await Task.Delay(1000); }
            }
        });
    }
}
