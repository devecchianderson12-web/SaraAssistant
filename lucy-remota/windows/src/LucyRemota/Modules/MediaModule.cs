using System.Net.Http;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using LucyRemota.Core.Commands;
using Windows.Media.Control;

namespace LucyRemota.Modules;

/// <summary>
/// Música y videos: controla lo que esté sonando en Windows (Spotify, el navegador, Media Player…)
/// con los controles multimedia del sistema, y busca canciones (YouTube o Spotify).
/// </summary>
sealed partial class MediaModule(AppsModule apps) : ISkillModule
{
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(6) };
    public string Name => "media";

    public IReadOnlyList<IntentSpec> Intents { get; } =
    [
        new("MEDIA_PLAY", "media", "Pone música: lo que estaba sonando, o busca una canción/artista.", Risk.Change,
            M.P(("query", new(ParamType.String, MaxLength: 120, Description: "Canción, artista o lista")), ("app", new(ParamType.Enum, Options: ["spotify", "youtube"])))),
        new("MEDIA_PAUSE", "media", "Pausa la música.", Risk.Change),
        new("MEDIA_RESUME", "media", "Sigue la música pausada.", Risk.Change),
        new("MEDIA_NEXT", "media", "Pasa a la siguiente canción.", Risk.Change),
        new("MEDIA_PREVIOUS", "media", "Vuelve a la canción anterior.", Risk.Change),
        new("MEDIA_NOW_PLAYING", "media", "Dice qué está sonando.", Risk.Info),
    ];

    public async Task<Outcome> ExecuteAsync(string intent, Params p, CancellationToken ct)
    {
        var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        var session = manager.GetCurrentSession();
        switch (intent)
        {
            case "MEDIA_PLAY":
                return p.Str("query") is { } q ? await Search(q, p.Str("app"), ct) : await Play(manager, session, p.Str("app"), ct);
            case "MEDIA_NOW_PLAYING":
                return await NowPlaying(session);
        }
        if (session == null) return Outcome.Fail("No está sonando nada en la computadora.", "nothing_playing");
        var info = session.GetPlaybackInfo();
        var playing = info.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
        switch (intent)
        {
            case "MEDIA_PAUSE":
                if (!playing) return Outcome.Ok("Ya estaba en pausa.");
                return await session.TryPauseAsync() ? Outcome.Ok("Listo, pausé la música.") : Outcome.Fail("Esa app no dejó pausar.");
            case "MEDIA_RESUME":
                if (playing) return Outcome.Ok("Ya está sonando.");
                return await session.TryPlayAsync() ? Outcome.Ok("Listo, sigue la música.") : Outcome.Fail("Esa app no dejó seguir.");
            case "MEDIA_NEXT":
                if (!await session.TrySkipNextAsync()) return Outcome.Fail("Esa app no deja pasar de canción.");
                return await Changed(session, "Siguiente canción");
            default:
                if (!await session.TrySkipPreviousAsync()) return Outcome.Fail("Esa app no deja volver de canción.");
                return await Changed(session, "Canción anterior");
        }
    }

    /// <summary>Espera un instante a que cambie la canción para decir cuál suena.</summary>
    static async Task<Outcome> Changed(GlobalSystemMediaTransportControlsSession session, string what)
    {
        await Task.Delay(900);
        var props = await session.TryGetMediaPropertiesAsync();
        var song = Describe(props?.Title, props?.Artist);
        return Outcome.Ok(song != null ? $"{what}: {song}." : $"{what}.", Song(props?.Title, props?.Artist));
    }

    static async Task<Outcome> NowPlaying(GlobalSystemMediaTransportControlsSession? session)
    {
        if (session == null) return Outcome.Ok("No está sonando nada en la computadora.", new JsonObject { ["playing"] = false });
        var props = await session.TryGetMediaPropertiesAsync();
        var playing = session.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
        var song = Describe(props?.Title, props?.Artist);
        if (song == null) return Outcome.Ok("No sé qué está sonando.", new JsonObject { ["playing"] = playing });
        var result = Song(props?.Title, props?.Artist);
        result["playing"] = playing;
        return Outcome.Ok(playing ? $"Está sonando {song}." : $"Lo último que sonó fue {song}; está en pausa.", result);
    }

    async Task<Outcome> Play(GlobalSystemMediaTransportControlsSessionManager manager, GlobalSystemMediaTransportControlsSession? session, string? app, CancellationToken ct)
    {
        if (session != null && app == null)
        {
            if (session.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing) return Outcome.Ok("Ya está sonando música.");
            if (await session.TryPlayAsync()) return Outcome.Ok("Listo, puse la música.");
        }
        var music = apps.MusicApp(app);
        if (music == null)
        {
            M.ShellOpen("https://music.youtube.com/");
            return Outcome.Ok("No encontré una app de música instalada: abrí YouTube Music en el navegador.");
        }
        if (WebMusic.WebAppId(music.AppId) != null)
        {
            // YouTube Music instalada: la ventana que ya está abierta, o se abre la app (nunca una pestaña).
            var (h, fresh) = await OpenWebApp(music, ct);
            if (h != 0)
            {
                await WebMusic.FocusAsync(h, ct);
                var played = await WebMusic.PlayFirstAsync(h, TimeSpan.FromSeconds(fresh ? 12 : 6), ct);
                return played != null
                    ? Outcome.Ok($"Listo, puse música en {music.Name}.", new JsonObject { ["app"] = music.Name })
                    : Outcome.Ok($"Abrí {music.Name}.", new JsonObject { ["app"] = music.Name });
            }
            return Outcome.Ok($"Abrí {music.Name}.", new JsonObject { ["app"] = music.Name });
        }
        apps.Launch(music);
        // Spotify sigue con lo último que escuchaste; las demás quedan abiertas para elegir.
        if (music.Name.Contains("spotify", StringComparison.OrdinalIgnoreCase))
        {
            for (var i = 0; i < 20 && !ct.IsCancellationRequested; i++)
            {
                await Task.Delay(500, ct);
                var s = manager.GetSessions().FirstOrDefault(x => x.SourceAppUserModelId.Contains("spotify", StringComparison.OrdinalIgnoreCase));
                if (s != null && await s.TryPlayAsync()) return Outcome.Ok("Listo, puse música en Spotify.", new JsonObject { ["app"] = music.Name });
            }
        }
        return Outcome.Ok($"Abrí {music.Name}.", new JsonObject { ["app"] = music.Name });
    }

    /// <summary>La ventana de la app web: la abierta o, si no hay, la abre (como desde su ícono) y espera a que cargue.</summary>
    async Task<(nint window, bool fresh)> OpenWebApp(InstalledApp music, CancellationToken ct)
    {
        var h = WebMusic.FindWindow(music.AppId);
        if (h != 0) return (h, false);
        apps.Launch(music);
        h = await WebMusic.WaitWindow(() => WebMusic.FindWindow(music.AppId), TimeSpan.FromSeconds(20), ct);
        if (h != 0) await WebMusic.WaitReadyAsync(h, TimeSpan.FromSeconds(15), ct);
        return (h, true);
    }

    async Task<Outcome> Search(string query, string? app, CancellationToken ct)
    {
        var music = apps.MusicApp(app);
        var isSpotify = music?.Name.Contains("spotify", StringComparison.OrdinalIgnoreCase) == true;
        if (isSpotify)
        {
            // Spotify no deja reproducir un resultado sin tocarlo: se abre la búsqueda (en la app abierta, si está).
            M.ShellOpen("spotify:search:" + Uri.EscapeDataString(query));
            return Outcome.Ok($"Te busqué «{query}» en Spotify.", new JsonObject { ["app"] = music!.Name });
        }
        if (music != null && WebMusic.WebAppId(music.AppId) != null)
        {
            // YouTube Music instalada: se busca en su ventana (la abierta, o se abre) y se pone el primer resultado.
            var (h, _) = await OpenWebApp(music, ct);
            if (h != 0 && await WebMusic.SearchAsync(h, query, ct))
            {
                await Task.Delay(1500, ct);
                var played = await WebMusic.PlayFirstAsync(h, TimeSpan.FromSeconds(10), ct);
                return played != null
                    ? Outcome.Ok($"Listo, pongo «{query}» en {music.Name}.", new JsonObject { ["query"] = query, ["app"] = music.Name })
                    : Outcome.Ok($"Te busqué «{query}» en {music.Name}.", new JsonObject { ["query"] = query, ["app"] = music.Name });
            }
            if (h != 0) return Outcome.Fail($"Abrí {music.Name}, pero Windows no me dejó escribir en la ventana. Probá de nuevo.", "focus_denied");
        }
        // Sin app de música instalada: YouTube en el navegador.
        var id = await FirstVideo(query, ct);
        if (id != null)
        {
            M.ShellOpen("https://www.youtube.com/watch?v=" + id);
            return Outcome.Ok($"Listo, pongo «{query}».", new JsonObject { ["query"] = query, ["source"] = "youtube" });
        }
        M.ShellOpen("https://www.youtube.com/results?search_query=" + Uri.EscapeDataString(query));
        return Outcome.Ok($"Te busqué «{query}» en YouTube.");
    }

    static async Task<string?> FirstVideo(string query, CancellationToken ct)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, "https://www.youtube.com/results?search_query=" + Uri.EscapeDataString(query));
            req.Headers.TryAddWithoutValidation("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            req.Headers.TryAddWithoutValidation("Accept-Language", "es");
            using var res = await Http.SendAsync(req, ct);
            var html = await res.Content.ReadAsStringAsync(ct);
            var m = VideoId().Match(html);
            return m.Success ? m.Groups[1].Value : null;
        }
        catch (Exception) { return null; }
    }

    [GeneratedRegex("\"videoId\":\"([A-Za-z0-9_-]{11})\"")]
    private static partial Regex VideoId();

    static string? Describe(string? title, string? artist) =>
        string.IsNullOrWhiteSpace(title) ? null : $"«{title}»" + (string.IsNullOrWhiteSpace(artist) ? "" : $" de {artist}");

    static JsonObject Song(string? title, string? artist) => new() { ["title"] = title ?? "", ["artist"] = artist ?? "" };
}
