package com.sara.lucy.win

import com.sara.core.util.SaraLogger
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread

/** Dónde está Lucy: el .exe portable (si se abrió desde ahí) o el lanzador de la app. */
object LucyLocation {
    /** El .exe portable que pasa el lanzador; si no, el ejecutable de la app. */
    @Volatile var portableExe: File? = null

    fun executable(): File? = portableExe ?: System.getProperty("jpackage.app-path")?.let(::File)

    val version: Int by lazy {
        LucyLocation::class.java.getResourceAsStream("/lucy-version.txt")?.use { it.readBytes().decodeToString().trim().toIntOrNull() } ?: 0
    }
}

/** «Iniciar con Windows» sólo para este usuario (HKCU\…\Run): no necesita administrador. */
object Autostart {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Lucy"

    fun isEnabled(): Boolean = runCatching { Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME) }.getOrDefault(false)

    fun set(enabled: Boolean): Boolean = runCatching {
        if (enabled) {
            val exe = LucyLocation.executable() ?: return false
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, NAME, "\"${exe.absolutePath}\" --hidden")
        } else if (isEnabled()) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, NAME)
        }
        true
    }.getOrDefault(false)

    /** Si Lucy se movió o se actualizó, la entrada apunta al .exe actual. */
    fun refresh() {
        if (isEnabled()) set(true)
    }
}

/** Atajo global Ctrl+Alt+L para llamarla sin hablar. */
object Hotkey {
    private const val ID = 0x4C55

    fun register(logger: SaraLogger, onPress: () -> Unit) {
        thread(isDaemon = true, name = "lucy-hotkey") {
            val user32 = User32.INSTANCE
            if (!user32.RegisterHotKey(null, ID, WinUser.MOD_CONTROL or WinUser.MOD_ALT or WinUser.MOD_NOREPEAT, 'L'.code)) {
                logger.w("Hotkey", "Ctrl+Alt+L ya lo usa otro programa")
                return@thread
            }
            val msg = WinUser.MSG()
            while (user32.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WinUser.WM_HOTKEY) runCatching(onPress)
            }
        }
    }
}

/** Una sola Lucy a la vez: si se abre otra, le pide a la primera que se muestre y se cierra. */
class SingleInstance(private val dir: File) {
    private var lock: FileLock? = null
    private var channel: FileChannel? = null

    fun acquire(onShowRequest: () -> Unit): Boolean {
        val ch = FileChannel.open(File(dir, "lucy.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val l = runCatching { ch.tryLock() }.getOrNull()
        if (l == null) {
            ch.close()
            runCatching {
                val port = File(dir, "lucy.port").readText().trim().toInt()
                Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write("show\n".toByteArray()) }
            }
            return false
        }
        channel = ch
        lock = l
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        File(dir, "lucy.port").writeText(server.localPort.toString())
        thread(isDaemon = true, name = "lucy-instance") {
            while (true) {
                runCatching {
                    server.accept().use { s -> if (s.getInputStream().bufferedReader().readLine() == "show") onShowRequest() }
                }
            }
        }
        return true
    }
}

/**
 * Actualizaciones: mira la última versión publicada (la misma que la del APK), baja el .exe nuevo al
 * lado del actual, abre el nuevo y el nuevo borra el viejo. Sin instalador ni administrador.
 */
class Updater(private val logger: SaraLogger) {
    data class Release(val version: Int, val url: String, val name: String)

    /**
     * Última versión de Lucy. Lucy y el APK se publican por separado (una actualización de Windows
     * no actualiza el teléfono y viceversa): se busca la release más nueva que traiga el .exe.
     */
    fun latest(): Release? = runCatching {
        val conn = URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Lucy")
        val releases = org.json.JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        (0 until releases.length()).map { releases.getJSONObject(it) }
            .filter { !it.optBoolean("draft") }
            .mapNotNull { json ->
                val assets = json.optJSONArray("assets") ?: return@mapNotNull null
                val exe = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").let { n -> n.startsWith("Lucy-Windows") && n.endsWith(".exe") } } ?: return@mapNotNull null
                val version = Regex("(\\d+)\\.exe$").find(exe.getString("name"))?.groupValues?.get(1)?.toInt()
                    ?: Regex("(\\d+)$").find(json.optString("tag_name"))?.value?.toInt() ?: return@mapNotNull null
                Release(version, exe.getString("browser_download_url"), exe.getString("name"))
            }
            .maxByOrNull { it.version }
    }.onFailure { logger.w(TAG, "No pude buscar actualizaciones", it) }.getOrNull()

    fun available(): Release? = latest()?.takeIf { LucyLocation.version in 1 until it.version }

    /** Baja y abre la versión nueva. Devuelve null si salió bien (hay que cerrar esta) o el error. */
    fun install(release: Release, progress: (Int) -> Unit): String? {
        val current = LucyLocation.portableExe ?: return "Esta copia no es la portable: bajá la nueva desde la página de Lucy."
        val target = File(current.parentFile, release.name)
        val tmp = File(current.parentFile, release.name + ".part")
        return runCatching {
            val conn = URL(release.url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Lucy")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
            if (target.exists() && target != current) target.delete()
            if (!tmp.renameTo(target)) error("no pude guardar el archivo")
            ProcessBuilder(target.absolutePath, "--replace", current.absolutePath).start()
            null
        }.getOrElse {
            tmp.delete()
            logger.w(TAG, "Actualización fallida", it)
            "No pude actualizar: ${it.message}"
        }
    }

    /** La versión nueva borra el .exe viejo (cuando ya se cerró). */
    fun cleanup(old: File) {
        thread(isDaemon = true) {
            repeat(30) {
                if (!old.exists() || old.delete()) return@thread
                Thread.sleep(1_000)
            }
        }
    }

    private companion object {
        const val TAG = "Updater"
        const val REPO = "devecchianderson12-web/SaraAssistant"
    }
}
