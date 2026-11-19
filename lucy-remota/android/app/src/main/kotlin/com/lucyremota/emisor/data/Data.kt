package com.lucyremota.emisor.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.lucyremota.nucleo.memory.MemoryStore
import com.lucyremota.nucleo.util.SaraLogger
import com.lucyremota.nucleo.voice.PiperVoice
import com.lucyremota.protocolo.B64
import com.lucyremota.protocolo.DeviceIdentity
import com.lucyremota.protocolo.PairedPc
import com.lucyremota.protocolo.PhoneStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ajustes de Lucy Remota (pocos y claros). */
data class Settings(
    val wakeWord: Boolean = true,
    val sensitivity: Float = 0.5f,
    val usePiper: Boolean = true,
    val piperVoice: PiperVoice = PiperVoice.DANIELA,
    val quickResponse: Boolean = true,
    val chatFollowUp: Boolean = true,
    /** «Ey Lucy» en lugar de «Lucy» sola (menos activaciones por error). */
    val heyPrefix: Boolean = false,
    /** «Repetir tu orden cuando la menciones»: apagado = un tono cuando la PC lo hizo. */
    val repeatCommands: Boolean = false,
    val speechRate: Float = 1f,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val current: Settings get() = _settings.value

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            wakeWord = prefs.getBoolean("wake", d.wakeWord),
            sensitivity = prefs.getFloat("sensibilidad", d.sensitivity),
            usePiper = prefs.getBoolean("piper", d.usePiper),
            piperVoice = runCatching { PiperVoice.valueOf(prefs.getString("voz", d.piperVoice.name)!!) }.getOrDefault(d.piperVoice),
            quickResponse = prefs.getBoolean("rapida", d.quickResponse),
            chatFollowUp = prefs.getBoolean("charla", d.chatFollowUp),
            heyPrefix = prefs.getBoolean("ey", d.heyPrefix),
            repeatCommands = prefs.getBoolean("repetir", d.repeatCommands),
            speechRate = prefs.getFloat("velocidad", d.speechRate),
        )
    }

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        prefs.edit().putBoolean("wake", s.wakeWord).putFloat("sensibilidad", s.sensitivity).putBoolean("piper", s.usePiper)
            .putString("voz", s.piperVoice.name).putBoolean("rapida", s.quickResponse).putBoolean("charla", s.chatFollowUp)
            .putBoolean("ey", s.heyPrefix).putBoolean("repetir", s.repeatCommands).putFloat("velocidad", s.speechRate).apply()
        _settings.value = s
    }
}

/**
 * Identidad del teléfono: clave EC P-256 creada en el almacén seguro de Android (no se puede sacar
 * del teléfono). Con ella firma cada conexión; la PC guarda sólo la parte pública.
 */
class KeystoreIdentity : DeviceIdentity {
    private val alias = "lucyremota_identidad"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!keyStore.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()
        }
    }

    override val publicKeySpki: ByteArray get() = keyStore.getCertificate(alias).publicKey.encoded

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(keyStore.getKey(alias, null) as PrivateKey); update(data); sign() }
}

/** Identificador del teléfono y PC vinculada (la clave pública de la PC, no hay secretos). */
class PrefsPhoneStore(context: Context) : PhoneStore {
    private val prefs = context.getSharedPreferences("vinculacion", Context.MODE_PRIVATE)
    private val _paired = MutableStateFlow(read())
    val paired: StateFlow<PairedPc?> = _paired.asStateFlow()

    override val deviceId: String = prefs.getString("id", null) ?: ("tel-" + UUID.randomUUID().toString().replace("-", "").take(16)).also {
        prefs.edit().putString("id", it).apply()
    }

    override val deviceName: String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ")

    override var pairedPc: PairedPc?
        get() = _paired.value
        set(value) {
            val e = prefs.edit()
            if (value == null) e.remove("pc") else e.putString("pc", JSONObject().put("id", value.id).put("name", value.name).put("key", B64.enc(value.publicKey)).toString())
            e.apply()
            _paired.value = value
        }

    private fun read(): PairedPc? = prefs.getString("pc", null)?.let {
        runCatching { JSONObject(it).let { j -> PairedPc(j.getString("id"), j.getString("name"), B64.dec(j.getString("key"))) } }.getOrNull()
    }
}

/** Claves (la de la IA): cifradas con una clave del almacén seguro de Android. */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secretos", Context.MODE_PRIVATE)
    private val alias = "lucyremota_secretos"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun get(name: String): String? = runCatching {
        val raw = prefs.getString(name, null) ?: return null
        val data = B64.dec(raw)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        String(c.doFinal(data.copyOfRange(12, data.size)))
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) { prefs.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        prefs.edit().putString(name, B64.enc(c.iv + c.doFinal(value.toByteArray()))).apply()
    }

    companion object { const val GEMINI_KEY = "gemini" }
}

/** Preferencias y rutinas que se le enseñan a Lucy («cuando te diga…»). */
class PrefsMemoryStore(context: Context) : MemoryStore {
    private val prefs = context.getSharedPreferences("memoria", Context.MODE_PRIVATE)
    override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String): Boolean = prefs.contains(key).also { prefs.edit().remove(key).apply() }
}

/** Registro para el diagnóstico (últimas 200 líneas). */
class DiagnosticsLog : SaraLogger {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private val format = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    override fun d(tag: String, message: String) {
        Log.d("LucyRemota/$tag", message)
        append("· $tag: $message")
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        Log.w("LucyRemota/$tag", message, error)
        append("! $tag: $message" + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
    }

    @Synchronized
    private fun append(line: String) {
        _lines.value = (_lines.value + "${format.format(Date())} $line").takeLast(200)
    }
}

