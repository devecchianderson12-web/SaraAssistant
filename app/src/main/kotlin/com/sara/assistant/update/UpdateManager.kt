package com.sara.assistant.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.sara.assistant.BuildConfig
import com.sara.assistant.container
import com.sara.core.update.UpdatePolicy
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState

    /** Falta que el usuario permita "Instalar apps desconocidas" para SaraAssistant. */
    data class NeedsPermission(val release: ReleaseInfo) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo?) : UpdateState
}

data class ReleaseInfo(val tag: String, val versionCode: Int, val apkUrl: String, val sizeBytes: Long, val notes: String)

/**
 * Busca la última versión publicada en GitHub Releases y la instala con PackageInstaller.
 * El APK descargado vive en la caché sólo durante la instalación y se borra siempre después.
 */
class UpdateManager(private val context: Context, private val logger: SaraLogger) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val updatesDir = File(context.cacheDir, "updates")
    private var lastCheck = 0L

    init {
        // Sin rastros: elimina restos de instalaciones anteriores.
        updatesDir.deleteRecursively()
    }

    suspend fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val current = _state.value
        if (current is UpdateState.Downloading || current is UpdateState.Installing) return
        if (!force && now - lastCheck < CHECK_INTERVAL_MS) return
        lastCheck = now
        if (force) _state.value = UpdateState.Checking
        _state.value = withContext(Dispatchers.IO) {
            runCatching { fetchLatest() }.fold(
                onSuccess = { release ->
                    if (release != null && release.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(release) else UpdateState.UpToDate
                },
                onFailure = {
                    logger.w(TAG, "No se pudo consultar actualizaciones", it)
                    if (force) UpdateState.Failed("No se pudo buscar actualizaciones: ${it.message}", null) else UpdateState.Idle
                },
            )
        }
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Installing) _state.value = UpdateState.Idle
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    suspend fun downloadAndInstall(release: ReleaseInfo) {
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        val apk = File(updatesDir, "SaraAssistant-update.apk")
        try {
            withContext(Dispatchers.IO) {
                updatesDir.mkdirs()
                download(release, apk)
                _state.value = UpdateState.Installing(release)
                install(apk)
            }
        } catch (e: Exception) {
            apk.delete()
            logger.w(TAG, "Actualización fallida", e)
            _state.value = UpdateState.Failed("No se pudo actualizar: ${e.message}", release)
        }
    }

    internal fun onInstallFinished(success: Boolean, message: String?) {
        updatesDir.deleteRecursively()
        val release = (_state.value as? UpdateState.Installing)?.release
        _state.value = if (success) UpdateState.UpToDate else UpdateState.Failed(message ?: "La instalación se canceló.", release)
    }

    private fun fetchLatest(): ReleaseInfo? {
        val connection = (URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SaraAssistant-Updater")
            connectTimeout = 8_000
            readTimeout = 10_000
        }
        try {
            if (connection.responseCode == 404) return null // todavía no hay releases
            if (connection.responseCode != 200) error("GitHub respondió ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name")
            val version = UpdatePolicy.versionFromTag(tag) ?: return null
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            return ReleaseInfo(
                tag = tag,
                versionCode = version,
                apkUrl = apk.getString("browser_download_url"),
                sizeBytes = apk.optLong("size", -1),
                notes = json.optString("body").trim(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun download(release: ReleaseInfo, target: File) {
        _state.value = UpdateState.Downloading(release, 0f)
        var url = URL(release.apkUrl)
        var connection: HttpURLConnection
        var redirects = 0
        // GitHub redirige a otro dominio: se siguen los saltos manualmente (sólo HTTPS).
        while (true) {
            connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SaraAssistant-Updater")
                connectTimeout = 10_000
                readTimeout = 30_000
            }
            val code = connection.responseCode
            if (code in 300..399 && redirects < 5) {
                val next = URL(url, connection.getHeaderField("Location"))
                connection.disconnect()
                require(next.protocol == "https") { "Redirección insegura" }
                url = next
                redirects++
                continue
            }
            if (code != 200) error("Descarga fallida ($code)")
            break
        }
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var reported = 0f
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val p = (copied.toFloat() / total).coerceIn(0f, 1f)
                            if (p - reported >= 0.01f) {
                                reported = p
                                _state.value = UpdateState.Downloading(release, p)
                            }
                        }
                    }
                }
            }
            if (release.sizeBytes > 0 && target.length() != release.sizeBytes) error("El archivo descargado está incompleto")
        } finally {
            connection.disconnect()
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                flags,
            )
            session.commit(callback.intentSender)
        }
        apk.delete()
    }

    private companion object {
        const val TAG = "Update"
        const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
    }
}

/** Recibe el resultado de PackageInstaller y muestra la confirmación del sistema cuando hace falta. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = context.container.updates
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { updates.onInstallFinished(false, "No se pudo abrir el instalador: ${it.message}") }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updates.onInstallFinished(true, null)
            else -> updates.onInstallFinished(
                false,
                if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Instalación cancelada."
                else intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )
        }
    }
}
