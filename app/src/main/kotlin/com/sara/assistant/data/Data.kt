package com.sara.assistant.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.sara.core.assistant.CommandHistory
import com.sara.core.engine.ConnectivityStatus
import com.sara.core.model.HistoryEntry
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credenciales cifradas con una clave AES-GCM del Android Keystore (la clave nunca sale del
 * hardware seguro). Las claves de API las carga el usuario en Ajustes: nunca están en el código.
 */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sara_secrets", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)

    /** Cambia cada vez que se modifica un secreto (para refrescar la UI). */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.getOrNull()
    }

    fun has(name: String) = !get(name).isNullOrBlank()

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.edit().remove(name).apply()
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
            prefs.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        }
        _version.value++
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val AZURE_KEY = "azure_speech_key"
        const val PORCUPINE_ACCESS_KEY = "porcupine_access_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "sara_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}

class ConnectivityMonitor(context: Context) : ConnectivityStatus {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val caps = manager?.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/** Log del sistema + registro en memoria para la pantalla de diagnóstico (no se guarda en disco). */
class DiagnosticsLog : SaraLogger {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private val format = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    override fun d(tag: String, message: String) {
        Log.d("Sara/$tag", message)
        append("· $tag: $message")
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        Log.w("Sara/$tag", message, error)
        append("! $tag: $message" + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
    }

    @Synchronized
    private fun append(line: String) {
        _lines.value = (_lines.value + "${format.format(Date())} $line").takeLast(150)
    }

    fun clear() {
        _lines.value = emptyList()
    }
}

/**
 * Historial mínimo: sólo texto (nunca audio), máximo [MAX_ENTRIES] entradas, en almacenamiento
 * privado de la app. Se puede desactivar y borrar desde Privacidad.
 */
class FileCommandHistory(context: Context, private val enabled: () -> Boolean) : CommandHistory {
    private val file = File(context.filesDir, "history.json")
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    suspend fun load() = mutex.withLock {
        _entries.value = withContext(Dispatchers.IO) { readFile() }
    }

    override suspend fun record(entry: HistoryEntry) {
        if (!enabled()) return
        mutex.withLock {
            val updated = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
            _entries.value = updated
            withContext(Dispatchers.IO) { writeFile(updated) }
        }
    }

    suspend fun clear() = mutex.withLock {
        _entries.value = emptyList()
        withContext(Dispatchers.IO) { file.delete() }
    }

    private fun readFile(): List<HistoryEntry> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            HistoryEntry(o.getLong("t"), o.getString("heard"), o.getString("intent"), o.getString("response"), o.getBoolean("ok"))
        }
    }.getOrDefault(emptyList())

    private fun writeFile(list: List<HistoryEntry>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject().put("t", it.timestampMillis).put("heard", it.heard).put("intent", it.intentLabel)
                    .put("response", it.response).put("ok", it.success),
            )
        }
        val tmp = File(file.parentFile, "history.json.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val MAX_ENTRIES = 100
    }
}
