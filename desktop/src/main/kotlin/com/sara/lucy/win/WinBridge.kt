package com.sara.lucy.win

import com.sara.core.util.SaraLogger
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Puente con Windows: un PowerShell (viene con Windows, sin administrador) que queda abierto y
 * atiende pedidos JSON por línea (ver `lucy-helper.ps1`). Si se cae o se corta, se vuelve a abrir.
 * Hay uno para la voz (hablar bloquea) y otro para el resto del sistema.
 */
class WinBridge(private val mode: String, private val logger: SaraLogger) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var nextId = 1
    private val lock = Any()

    /** PID del PowerShell (para no bajarle el volumen a la voz de Lucy). */
    @Volatile var pid: Long = -1
        private set

    class BridgeException(message: String) : IOException(message)

    /** Ejecuta un comando y devuelve `result` (puede ser JSONObject, JSONArray, String, número o null). */
    fun call(cmd: String, args: Map<String, Any?> = emptyMap(), timeoutMillis: Long = 15_000): Any? = synchronized(lock) {
        ensureStarted()
        val id = nextId++
        val request = JSONObject().put("id", id).put("cmd", cmd).put("args", JSONObject(args))
        try {
            writer!!.apply { write(request.toString()); newLine(); flush() }
            val deadline = System.currentTimeMillis() + timeoutMillis
            var response: JSONObject? = null
            while (response == null) {
                val line = readLine(deadline) ?: throw BridgeException("Windows no respondió ($cmd).")
                if (line.startsWith("{")) response = JSONObject(line).takeIf { it.optInt("id", -1) == id }
            }
            if (!response.optBoolean("ok")) throw BridgeException(response.optString("error", "error"))
            if (response.isNull("result")) null else response.get("result")
        } catch (e: BridgeException) {
            if (e.message?.startsWith("Windows no respondió") == true) stopLocked()
            throw e
        } catch (e: IOException) {
            stopLocked()
            throw BridgeException("Se cortó el puente con Windows: ${e.message}")
        }
    }

    fun obj(cmd: String, args: Map<String, Any?> = emptyMap(), timeoutMillis: Long = 15_000): JSONObject =
        call(cmd, args, timeoutMillis) as? JSONObject ?: JSONObject()

    /** Corta lo que esté haciendo (por ejemplo, hablar) matando el proceso; el próximo pedido lo reabre. */
    fun interrupt() {
        val p = process ?: return
        runCatching { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        runCatching { process?.destroyForcibly() }
        process = null
        writer = null
        reader = null
        pid = -1
    }

    private fun readLine(deadline: Long): String? {
        val r = reader ?: return null
        val p = process ?: return null
        while (System.currentTimeMillis() < deadline) {
            if (r.ready()) return r.readLine()
            if (!p.isAlive) return null
            Thread.sleep(5)
        }
        return null
    }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        stopLocked()
        val script = scriptFile()
        val p = ProcessBuilder(
            "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", script.absolutePath, "-Mode", mode,
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = p
        pid = p.pid()
        writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
        reader = p.inputStream.bufferedReader(Charsets.UTF_8)
        // Espera el «listo» (la primera vez compila el código de audio: 1-3 s).
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            val line = readLine(deadline)
            if (line == null) {
                val code = if (p.waitFor(100, TimeUnit.MILLISECONDS)) p.exitValue() else null
                stopLocked()
                throw BridgeException("No pude abrir el puente con Windows${code?.let { " (código $it)" } ?: ""}.")
            }
            if (line.contains("\"ready\"")) break
        }
        logger.d(TAG, "Puente $mode listo (pid $pid)")
    }

    companion object {
        private const val TAG = "WinBridge"

        /** Copia el script a la carpeta de Lucy (PowerShell necesita un archivo .ps1 real). */
        @Synchronized fun scriptFile(): File {
            val target = File(LucyPaths.home, "lucy-helper.ps1")
            // Con BOM UTF-8 para que PowerShell 5.1 lea bien los acentos.
            val content = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                WinBridge::class.java.getResourceAsStream("/lucy-helper.ps1")!!.use { it.readBytes() }
            if (!target.exists() || !target.readBytes().contentEquals(content)) target.writeBytes(content)
            return target
        }
    }
}

/** Carpetas de Lucy (todas del usuario: no hace falta administrador). */
object LucyPaths {
    val home: File by lazy {
        val base = System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".config")
        File(base, "Lucy").apply { mkdirs() }
    }
}
