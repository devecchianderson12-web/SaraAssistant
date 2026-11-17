using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using Microsoft.Win32;
using NAudio.CoreAudioApi;
using Windows.Media.Control;

namespace Lucy;

/// <summary>
/// Multimedia en Windows sin permisos de administrador:
/// - Control de lo que suena (Spotify, navegador, YouTube, etc.) con las sesiones multimedia del
///   sistema (las mismas del panel de volumen de Windows); si no hay, teclas multimedia.
/// - Volumen del sistema con Core Audio.
/// - Buscar y reproducir: YouTube (el primer resultado empieza solo) o Spotify si está instalado.
/// </summary>
public sealed class Media
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(8) };

    static Media()
    {
        Http.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        Http.DefaultRequestHeaders.AcceptLanguage.ParseAdd("es-AR,es;q=0.9");
    }

    // ------------------------------------------------------------------ Sesiones multimedia

    private static async Task<GlobalSystemMediaTransportControlsSession?> CurrentSession()
    {
        try
        {
            var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            var sessions = manager.GetSessions();
            // La que está sonando; si no, la que Windows considera actual.
            return sessions.FirstOrDefault(s => s.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing)
                   ?? manager.GetCurrentSession();
        }
        catch
        {
            return null;
        }
    }

    private static bool IsPlaying(GlobalSystemMediaTransportControlsSession s) =>
        s.GetPlaybackInfo().PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;

    public async Task<string> Pause()
    {
        var s = await CurrentSession();
        if (s == null) { MediaKey(VK_MEDIA_PLAY_PAUSE); return "Okey."; }
        if (!IsPlaying(s)) return "No hay nada sonando.";
        await s.TryPauseAsync();
        return "Okey.";
    }

    public async Task<string> Resume()
    {
        var s = await CurrentSession();
        if (s == null) { MediaKey(VK_MEDIA_PLAY_PAUSE); return "Okey, voy."; }
        if (IsPlaying(s)) return "Ya está sonando.";
        await s.TryPlayAsync();
        return "Okey, voy.";
    }

    public async Task<string> Stop()
    {
        var s = await CurrentSession();
        if (s == null) { MediaKey(VK_MEDIA_STOP); return "Okey."; }
        if (!IsPlaying(s)) return "No hay nada sonando.";
        // Muchas apps no tienen «detener»: se pausa.
        if (!await s.TryStopAsync()) await s.TryPauseAsync();
        return "Okey.";
    }

    public async Task<string> Next()
    {
        var s = await CurrentSession();
        if (s == null) { MediaKey(VK_MEDIA_NEXT_TRACK); return "Okey, voy."; }
        return await s.TrySkipNextAsync() ? "Okey, voy." : "Esa app no deja pasar de canción.";
    }

    public async Task<string> Previous()
    {
        var s = await CurrentSession();
        if (s == null) { MediaKey(VK_MEDIA_PREV_TRACK); return "Okey, voy."; }
        return await s.TrySkipPreviousAsync() ? "Okey, voy." : "Esa app no deja volver atrás.";
    }

    public async Task<string> WhatIsPlaying()
    {
        var s = await CurrentSession();
        if (s == null) return "No hay nada sonando.";
        try
        {
            var props = await s.TryGetMediaPropertiesAsync();
            if (string.IsNullOrWhiteSpace(props.Title)) return "No sé qué está sonando.";
            return string.IsNullOrWhiteSpace(props.Artist) ? $"Está sonando {props.Title}." : $"Está sonando {props.Title}, de {props.Artist}.";
        }
        catch
        {
            return "No sé qué está sonando.";
        }
    }

    // ------------------------------------------------------------------ Volumen del sistema

    private static AudioEndpointVolume Endpoint() =>
        new MMDeviceEnumerator().GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia).AudioEndpointVolume;

    public int VolumePercent => (int)Math.Round(Endpoint().MasterVolumeLevelScalar * 100);

    public string SetVolume(int percent)
    {
        var ep = Endpoint();
        ep.Mute = false;
        ep.MasterVolumeLevelScalar = Math.Clamp(percent, 0, 100) / 100f;
        return "Okey.";
    }

    public string ChangeVolume(int delta)
    {
        var ep = Endpoint();
        var now = (int)Math.Round(ep.MasterVolumeLevelScalar * 100);
        if (delta > 0 && now >= 100 && !ep.Mute) return "Ya está al máximo.";
        if (delta < 0 && now <= 0) return "Ya está al mínimo.";
        ep.Mute = false;
        ep.MasterVolumeLevelScalar = Math.Clamp(now + delta, 0, 100) / 100f;
        return "Okey.";
    }

    public string SetMute(bool mute)
    {
        Endpoint().Mute = mute;
        return "Okey.";
    }

    // ------------------------------------------------------------------ Bajar la música mientras Lucy escucha

    private readonly Dictionary<uint, float> ducked = new();

    /// <summary>Baja el volumen de las otras apps mientras Lucy escucha o habla (como en el teléfono).</summary>
    public void Duck(bool on)
    {
        try
        {
            var device = new MMDeviceEnumerator().GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            var sessions = device.AudioSessionManager.Sessions;
            var self = (uint)Environment.ProcessId;
            for (var i = 0; i < sessions.Count; i++)
            {
                var s = sessions[i];
                var pid = s.GetProcessID;
                if (pid == self || pid == 0) continue;
                if (on)
                {
                    if (ducked.ContainsKey(pid)) continue;
                    ducked[pid] = s.SimpleAudioVolume.Volume;
                    s.SimpleAudioVolume.Volume = ducked[pid] * 0.25f;
                }
                else if (ducked.TryGetValue(pid, out var before))
                {
                    s.SimpleAudioVolume.Volume = before;
                }
            }
            if (!on) ducked.Clear();
        }
        catch
        {
            // Si una app se cerró mientras tanto, no importa.
        }
    }

    // ------------------------------------------------------------------ Buscar y poner música

    public static bool SpotifyInstalled =>
        Registry.CurrentUser.OpenSubKey(@"Software\Classes\spotify") != null || Registry.ClassesRoot.OpenSubKey("spotify") != null;

    /// <summary>«Poné X»: en YouTube abre y reproduce el primer resultado; en Spotify abre la búsqueda.</summary>
    public async Task<string> Play(string? query, string? service, string preferred)
    {
        var target = service ?? preferred;
        if (query == null)
        {
            // «Poné música» sin tema: si hay algo pausado, que siga.
            var s = await CurrentSession();
            if (s != null)
            {
                if (!IsPlaying(s)) await s.TryPlayAsync();
                return "Okey, voy.";
            }
            return target == "spotify" && SpotifyInstalled ? OpenUri("spotify:", "Okey, voy.") : OpenUri("https://music.youtube.com/", "Okey, voy.");
        }
        switch (target)
        {
            case "spotify" when SpotifyInstalled:
                return OpenUri("spotify:search:" + Uri.EscapeDataString(query), "Te abrí la búsqueda en Spotify.");
            case "spotify":
                return OpenUri("https://open.spotify.com/search/" + Uri.EscapeDataString(query), "Te abrí la búsqueda en Spotify.");
            case "deezer":
                return OpenUri("https://www.deezer.com/search/" + Uri.EscapeDataString(query), "Te abrí la búsqueda en Deezer.");
            default:
                var id = await FirstYouTubeVideo(query);
                if (id == null) return OpenUri("https://www.youtube.com/results?search_query=" + Uri.EscapeDataString(query), "Te abrí la búsqueda en YouTube.");
                var url = target == "youtube music" ? $"https://music.youtube.com/watch?v={id}" : $"https://www.youtube.com/watch?v={id}";
                return OpenUri(url, "Okey, voy.");
        }
    }

    public string OpenService(string service) => service switch
    {
        "spotify" when SpotifyInstalled => OpenUri("spotify:", "Okey."),
        "spotify" => OpenUri("https://open.spotify.com/", "Okey."),
        "youtube music" => OpenUri("https://music.youtube.com/", "Okey."),
        "deezer" => OpenUri("https://www.deezer.com/", "Okey."),
        _ => OpenUri("https://www.youtube.com/", "Okey."),
    };

    private static async Task<string?> FirstYouTubeVideo(string query)
    {
        try
        {
            var html = await Http.GetStringAsync("https://www.youtube.com/results?search_query=" + Uri.EscapeDataString(query));
            var m = Regex.Match(html, "\"videoId\":\"([A-Za-z0-9_-]{11})\"");
            return m.Success ? m.Groups[1].Value : null;
        }
        catch
        {
            return null;
        }
    }

    private static string OpenUri(string uri, string said)
    {
        try
        {
            Process.Start(new ProcessStartInfo(uri) { UseShellExecute = true });
            return said;
        }
        catch
        {
            return "No pude abrirlo.";
        }
    }

    // ------------------------------------------------------------------ Teclas multimedia (respaldo)

    private const byte VK_MEDIA_NEXT_TRACK = 0xB0, VK_MEDIA_PREV_TRACK = 0xB1, VK_MEDIA_STOP = 0xB2, VK_MEDIA_PLAY_PAUSE = 0xB3;

    [DllImport("user32.dll")]
    private static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);

    private static void MediaKey(byte vk)
    {
        keybd_event(vk, 0, 1, UIntPtr.Zero);
        keybd_event(vk, 0, 3, UIntPtr.Zero);
    }
}
