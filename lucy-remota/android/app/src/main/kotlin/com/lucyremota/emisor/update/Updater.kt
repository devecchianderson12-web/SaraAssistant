package com.lucyremota.emisor.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.lucyremota.emisor.BuildConfig
import com.lucyremota.nucleo.util.SaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualizaciones de Lucy Remota · Emisor: busca en el release «SaraAssistant» el APK cuyo nombre
 * empieza con «LucyRemota-Emisor» (nunca el de Sara) y lo instala si es más nuevo.
 */
class Updater(private val context: Context, private val logger: SaraLogger) {
    data class Release(val version: Int, val url: String, val name: String)

    private val _available = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = _available.asStateFlow()
    private val _progress = MutableStateFlow<Int?>(null)
    val progress: StateFlow<Int?> = _progress.asStateFlow()

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=100").openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "LucyRemota")
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            val releases = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            (0 until releases.length()).flatMap { i ->
                val assets = releases.getJSONObject(i).optJSONArray("assets") ?: JSONArray()
                (0 until assets.length()).map { assets.getJSONObject(it) }
            }.mapNotNull { a ->
                val name = a.optString("name")
                if (!name.startsWith(PREFIX) || !name.endsWith(".apk")) return@mapNotNull null
                val v = Regex("v1\\.0\\.(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                Release(v, a.getString("browser_download_url"), name)
            }.maxByOrNull { it.version }?.takeIf { it.version > BuildConfig.VERSION_CODE }
        }.onFailure { logger.w("Actualizar", "No pude buscar actualizaciones", it) }.getOrNull().also { _available.value = it }
    }

    suspend fun install(release: Release): String? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "actualizaciones").apply { mkdirs() }
        val apk = File(dir, release.name)
        runCatching {
            var url = URL(release.url)
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "LucyRemota")
                if (conn.responseCode in 300..399 && hops++ < 5) { url = URL(conn.getHeaderField("Location")); conn.disconnect(); continue }
                break
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _progress.value = (done * 100 / total).toInt()
                    }
                }
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".archivos", apk)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }.getOrElse { "No pude actualizar: ${it.message}" }.also { _progress.value = null }
    }

    companion object { const val PREFIX = "LucyRemota-Emisor" }
}
