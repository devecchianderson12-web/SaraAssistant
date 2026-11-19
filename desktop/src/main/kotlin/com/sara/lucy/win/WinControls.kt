package com.sara.lucy.win

import com.sara.core.apps.AppMatcher
import com.sara.core.apps.InstalledApp
import com.sara.core.apps.MusicUsage
import com.sara.core.execution.AppLaunchResult
import com.sara.core.execution.AppLauncher
import com.sara.core.execution.CloseAppsResult
import com.sara.core.execution.MediaAction
import com.sara.core.execution.MediaActionResult
import com.sara.core.execution.MediaControl
import com.sara.core.execution.VolumeControl
import com.sara.core.execution.VolumeState
import com.sara.core.skills.TextStore
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// =============================================================================================
// Volumen del sistema (Core Audio)
// =============================================================================================

class WinVolumeControl(private val bridge: WinBridge) : VolumeControl {
    override fun state(): VolumeState {
        val v = bridge.obj("volume")
        return VolumeState(current = v.optInt("percent"), min = 0, max = 100, isMuted = v.optBoolean("muted"))
    }

    override fun setIndex(index: Int): Boolean =
        runCatching { bridge.obj("setVolume", mapOf("percent" to index.coerceIn(0, 100))) }.isSuccess

    override fun setMuted(muted: Boolean): Boolean =
        runCatching { bridge.obj("setMute", mapOf("muted" to muted)).optBoolean("muted") == muted }.getOrDefault(false)
}

// =============================================================================================
// Abrir cosas (apps del menú Inicio, páginas, ajustes)
// =============================================================================================

/** Abre una dirección o un archivo con el programa predeterminado de Windows. */
fun shellOpen(bridge: WinBridge, target: String): Boolean = runCatching { bridge.call("open", mapOf("target" to target)) }.isSuccess

private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")

/** Apps del menú Inicio + sitios conocidos. «Abrí Spotify», «abrí la calculadora», «abrí YouTube». */
class WinAppLauncher(
    private val bridge: WinBridge,
    private val logger: SaraLogger,
    /** PIDs de Lucy (Java y sus PowerShell) que nunca se cierran. */
    private val ownPids: () -> List<Long>,
) : AppLauncher {
    private data class Site(val label: String, val url: String, val aliases: List<String> = emptyList())

    @Volatile private var cache: List<InstalledApp> = emptyList()
    @Volatile private var cachedAt = 0L

    fun installed(): List<InstalledApp> {
        if (System.currentTimeMillis() - cachedAt > CACHE_MILLIS || cache.isEmpty()) {
            runCatching {
                val arr = bridge.call("apps", timeoutMillis = 20_000) as? JSONArray ?: JSONArray()
                cache = (0 until arr.length()).map { arr.getJSONObject(it) }.map { InstalledApp(it.optString("name"), it.optString("id")) }
                    .filter { it.label.isNotBlank() && it.id.isNotBlank() }
                cachedAt = System.currentTimeMillis()
            }.onFailure { logger.w(TAG, "No pude leer las apps instaladas", it) }
        }
        return cache
    }

    fun hasApp(name: String): Boolean = AppMatcher.best(name, installed(), threshold = 0.8) != null

    override suspend fun findLabel(name: String): String? = withContext(Dispatchers.IO) {
        AppMatcher.best(name, installed())?.label ?: AppMatcher.best(name, siteCandidates())?.label
    }

    override suspend fun open(name: String): AppLaunchResult = withContext(Dispatchers.IO) {
        val query = name.trim()
        // Lo instalado gana (WhatsApp de escritorio antes que la web).
        AppMatcher.best(query, installed())?.let { app ->
            return@withContext if (runCatching { bridge.call("openApp", mapOf("id" to app.id)) }.isSuccess) AppLaunchResult.Opened(app.label, false)
            else AppLaunchResult.Failed("Windows no la dejó abrir.")
        }
        AppMatcher.best(query, siteCandidates())?.let { c ->
            val site = SITES[c.id.toInt()]
            return@withContext if (shellOpen(bridge, site.url)) AppLaunchResult.Opened(site.label, false) else AppLaunchResult.Failed("No pude abrir el navegador.")
        }
        AppLaunchResult.NotFound(query)
    }

    private fun siteCandidates(): List<InstalledApp> = SITES.flatMapIndexed { i, s -> (listOf(s.label) + s.aliases).map { InstalledApp(it, i.toString()) } }

    override suspend fun closeBackgroundApps(): CloseAppsResult = withContext(Dispatchers.IO) {
        runCatching { bridge.obj("closeApps", mapOf("exclude" to ownPids()), timeoutMillis = 20_000) }
            .fold({ CloseAppsResult.Closed }, { CloseAppsResult.Failed(it.message ?: "error") })
    }

    private companion object {
        const val TAG = "WinApps"
        const val CACHE_MILLIS = 5 * 60_000L
        val SITES = listOf(
            Site("YouTube", "https://www.youtube.com/", listOf("yutub", "youtube")),
            Site("YouTube Music", "https://music.youtube.com/", listOf("youtube musica", "yutub music")),
            Site("Spotify", "https://open.spotify.com/"),
            Site("WhatsApp", "https://web.whatsapp.com/", listOf("guasap", "wasap", "whatsapp web")),
            Site("Gmail", "https://mail.google.com/", listOf("correo", "mail", "email")),
            Site("Google", "https://www.google.com/"),
            Site("Google Maps", "https://www.google.com/maps", listOf("mapas", "maps")),
            Site("Google Drive", "https://drive.google.com/", listOf("drive")),
            Site("Google Calendar", "https://calendar.google.com/", listOf("calendario", "agenda")),
            Site("Netflix", "https://www.netflix.com/"),
            Site("Disney Plus", "https://www.disneyplus.com/", listOf("disney")),
            Site("Prime Video", "https://www.primevideo.com/", listOf("amazon prime")),
            Site("Twitch", "https://www.twitch.tv/"),
            Site("Facebook", "https://www.facebook.com/", listOf("feisbuk")),
            Site("Instagram", "https://www.instagram.com/"),
            Site("X", "https://x.com/", listOf("twitter")),
            Site("TikTok", "https://www.tiktok.com/"),
            Site("Mercado Libre", "https://www.mercadolibre.com.ar/", listOf("mercadolibre")),
            Site("ChatGPT", "https://chatgpt.com/"),
            Site("Claude", "https://claude.ai/"),
            Site("Deezer", "https://www.deezer.com/"),
            Site("SoundCloud", "https://soundcloud.com/"),
            Site("Configuración", "ms-settings:", listOf("ajustes", "configuracion de windows", "settings")),
            Site("Bloc de notas", "notepad.exe", listOf("notepad", "block de notas")),
            Site("Calculadora", "calc.exe", listOf("calculator")),
            Site("Explorador de archivos", "explorer.exe", listOf("explorador", "mis archivos", "archivos")),
            Site("Administrador de tareas", "taskmgr.exe", listOf("task manager")),
            Site("Descargas", "shell:Downloads", listOf("carpeta de descargas")),
            Site("Documentos", "shell:Personal", listOf("mis documentos")),
        )
    }
}

// =============================================================================================
// Música: lo que suena (sesiones multimedia de Windows) y buscar/poner temas
// =============================================================================================

/**
 * Multimedia en Windows. Controla lo que suena (Spotify, navegador, reproductores) con las sesiones
 * multimedia del sistema y pone música en la app que más usás (aprende mirando qué suena), igual que
 * en el teléfono: YouTube/YouTube Music arrancan el primer resultado solos; Spotify abre la búsqueda.
 */
class WinMediaControl(
    private val bridge: WinBridge,
    private val apps: WinAppLauncher,
    private val store: TextStore,
    private val logger: SaraLogger,
) : MediaControl {
    private val usage = MusicUsage().apply { load(store.get(USAGE_KEY)) }

    enum class Service(val label: String) { SPOTIFY("Spotify"), YOUTUBE("YouTube"), YOUTUBE_MUSIC("YouTube Music"), DEEZER("Deezer"), SOUNDCLOUD("SoundCloud"), OTHER("") }

    /** Se llama cada minuto: si algo suena, suma uso a esa app (así sabe cuál usás más). */
    fun sampleUsage() {
        val info = runCatching { bridge.obj("mediaInfo", timeoutMillis = 8_000) }.getOrNull() ?: return
        if (!info.optBoolean("session") || !info.optBoolean("playing")) return
        val app = info.optString("app").ifBlank { return }
        val now = System.currentTimeMillis()
        usage.record(app, 60_000, now)
        store.put(USAGE_KEY, usage.serialize())
        // Gustos musicales: el artista que suena (no en navegadores, que suelen ser videos).
        val browser = listOf("chrome", "msedge", "firefox", "opera", "brave", "vivaldi").any { it in app.lowercase() }
        if (!browser) info.optString("artist").takeIf { it.isNotBlank() }?.let { artist ->
            taste.record(artist, 60_000, now)
            store.put(TASTE_KEY, taste.serialize())
            taste.summary(now)?.let(onTaste)
        }
    }

    private val taste = com.sara.core.memory.MusicTaste().apply { load(store.get(TASTE_KEY)) }
    /** Resumen de gustos actualizado (lo guarda la memoria para la IA y «qué música me gusta»). */
    var onTaste: (String) -> Unit = {}

    fun favoriteService(): Service? = usage.favorite(System.currentTimeMillis())?.let { serviceOfApp(it) }

    private fun serviceOfApp(appId: String): Service {
        val id = appId.lowercase()
        return when {
            "spotify" in id -> Service.SPOTIFY
            "deezer" in id -> Service.DEEZER
            "soundcloud" in id -> Service.SOUNDCLOUD
            // Navegadores: casi siempre YouTube.
            listOf("chrome", "msedge", "edge", "firefox", "opera", "brave", "vivaldi").any { it in id } -> Service.YOUTUBE
            else -> Service.OTHER
        }
    }

    private fun serviceNamed(name: String?): Service? {
        val n = name?.lowercase()?.trim() ?: return null
        return when {
            "spotify" in n -> Service.SPOTIFY
            "youtube music" in n || "yutub music" in n || "youtube musica" in n -> Service.YOUTUBE_MUSIC
            "youtube" in n || "yutub" in n -> Service.YOUTUBE
            "deezer" in n -> Service.DEEZER
            "soundcloud" in n || "sound cloud" in n -> Service.SOUNDCLOUD
            else -> Service.OTHER
        }
    }

    private fun spotifyInstalled() = apps.hasApp("Spotify")

    override suspend fun nowPlaying(): com.sara.core.execution.NowPlayingInfo? = withContext(Dispatchers.IO) {
        val info = runCatching { bridge.obj("mediaInfo", timeoutMillis = 8_000) }.getOrNull() ?: return@withContext null
        if (!info.optBoolean("session")) return@withContext com.sara.core.execution.NowPlayingInfo(null, null, null, false)
        // Mientras Lucy escucha, la música sigue (sólo se baja): «playing» es confiable.
        com.sara.core.execution.NowPlayingInfo(info.optString("title").ifBlank { null }, info.optString("artist").ifBlank { null }, friendly(info.optString("app")), info.optBoolean("playing"))
    }

    override suspend fun perform(action: MediaAction, query: String?, app: String?): MediaActionResult = withContext(Dispatchers.IO) {
        when (action) {
            MediaAction.PLAY -> play(query?.trim()?.ifBlank { null }, app)
            MediaAction.RESUME -> control("play")
            MediaAction.PAUSE -> control("pause")
            MediaAction.STOP -> control("stop")
            MediaAction.NEXT -> control("next")
            MediaAction.PREVIOUS -> control("previous")
        }
    }

    private fun control(cmd: String): MediaActionResult {
        val r = runCatching { bridge.obj("media", mapOf("action" to cmd)) }.getOrElse { return MediaActionResult.Failed(it.message ?: "error") }
        val name = friendly(r.optString("app"))
        return when {
            !r.optBoolean("session") -> MediaActionResult.SentWithoutVerification
            r.optBoolean("nothing") -> MediaActionResult.NothingPlaying
            r.optBoolean("already") -> MediaActionResult.Done(name)
            !r.optBoolean("done") -> MediaActionResult.Unsupported(name)
            cmd == "play" && !r.optBoolean("playing") -> MediaActionResult.NotConfirmed(name)
            (cmd == "pause" || cmd == "stop") && r.optBoolean("playing") -> MediaActionResult.NotConfirmed(name)
            else -> MediaActionResult.Done(name)
        }
    }

    private suspend fun play(query: String?, app: String?): MediaActionResult {
        val asked = serviceNamed(app)
        val service = asked ?: favoriteService()?.takeIf { it != Service.OTHER } ?: if (spotifyInstalled()) Service.SPOTIFY else Service.YOUTUBE
        if (asked == Service.OTHER && app != null) return playInApp(app, query)
        if (query == null) {
            // «Poné música»: si hay algo pausado en esa app (o en cualquiera si no se pidió una), que siga.
            val info = runCatching { bridge.obj("mediaInfo", timeoutMillis = 8_000) }.getOrNull()
            if (info != null && info.optBoolean("session") && (asked == null || serviceOfApp(info.optString("app")) == asked)) {
                if (info.optBoolean("playing")) return MediaActionResult.Done(friendly(info.optString("app")))
                val r = control("play")
                if (r is MediaActionResult.Done) return r
            }
        }
        return when (service) {
            Service.SPOTIFY -> spotify(query)
            Service.DEEZER -> openUrl(if (query == null) "https://www.deezer.com/" else "https://www.deezer.com/search/" + enc(query), "Deezer")
            Service.SOUNDCLOUD -> openUrl(if (query == null) "https://soundcloud.com/discover" else "https://soundcloud.com/search?q=" + enc(query), "SoundCloud")
            else -> youtube(query ?: DEFAULT_QUERY, music = service == Service.YOUTUBE_MUSIC)
        }
    }

    private suspend fun spotify(query: String?): MediaActionResult {
        if (!spotifyInstalled()) {
            return openUrl(if (query == null) "https://open.spotify.com/" else "https://open.spotify.com/search/" + enc(query), "Spotify")
        }
        if (query != null) return openUrl("spotify:search:" + enc(query).replace("+", "%20"), "Spotify")
        // Sin tema: abre Spotify y le da play a lo último que escuchaste.
        if (!shellOpen(bridge, "spotify:")) return MediaActionResult.Failed("No pude abrir Spotify.")
        repeat(8) {
            delay(700)
            val info = runCatching { bridge.obj("mediaInfo", timeoutMillis = 5_000) }.getOrNull()
            if (info != null && info.optBoolean("session") && serviceOfApp(info.optString("app")) == Service.SPOTIFY) {
                if (info.optBoolean("playing")) return MediaActionResult.Done("Spotify")
                if (control("play") is MediaActionResult.Done) return MediaActionResult.Done("Spotify")
            }
        }
        return MediaActionResult.NotConfirmed("Spotify")
    }

    /** YouTube: busca y abre el primer resultado, que arranca solo. */
    private fun youtube(query: String, music: Boolean): MediaActionResult {
        val label = if (music) "YouTube Music" else "YouTube"
        val id = firstYouTubeVideo(query)
        val url = when {
            id == null -> "https://www.youtube.com/results?search_query=" + enc(query)
            music -> "https://music.youtube.com/watch?v=$id"
            else -> "https://www.youtube.com/watch?v=$id"
        }
        return openUrl(url, label)
    }

    private suspend fun playInApp(app: String, query: String?): MediaActionResult {
        return when (val r = apps.open(app)) {
            is AppLaunchResult.Opened -> {
                if (query == null) {
                    delay(2_500)
                    control("play").let { if (it is MediaActionResult.Done) return it }
                }
                MediaActionResult.Done(r.label)
            }
            is AppLaunchResult.NotFound -> MediaActionResult.Failed("No encontré $app.")
            else -> MediaActionResult.Failed("No pude abrir $app.")
        }
    }

    private fun openUrl(url: String, label: String): MediaActionResult =
        if (shellOpen(bridge, url)) MediaActionResult.Done(label) else MediaActionResult.Failed("No pude abrir $label.")

    private fun firstYouTubeVideo(query: String): String? = runCatching {
        val conn = URL("https://www.youtube.com/results?search_query=" + enc(query)).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 7_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
        conn.setRequestProperty("Accept-Language", "es-AR,es;q=0.9")
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
    }.onFailure { logger.w(TAG, "Búsqueda en YouTube falló", it) }.getOrNull()

    /** «Spotify.exe», «308046B0AF4A39CB» (Firefox), «Chrome» → nombre para decir. */
    private fun friendly(appId: String?): String? {
        if (appId.isNullOrBlank()) return null
        return when (serviceOfApp(appId)) {
            Service.SPOTIFY -> "Spotify"
            Service.DEEZER -> "Deezer"
            Service.SOUNDCLOUD -> "SoundCloud"
            Service.YOUTUBE -> "el navegador"
            else -> appId.substringAfterLast('\\').substringBefore('!').substringBefore(".exe").substringBefore('_').ifBlank { null }
        }
    }

    private companion object {
        const val TAG = "WinMedia"
        const val TASTE_KEY = "gustos_musicales"
        const val USAGE_KEY = "music_usage"
        const val DEFAULT_QUERY = "música mix"
    }
}
