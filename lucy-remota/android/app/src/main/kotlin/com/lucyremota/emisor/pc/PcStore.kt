package com.lucyremota.emisor.pc

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lucyremota.protocolo.B64
import com.lucyremota.protocolo.DeviceIdentity
import com.lucyremota.protocolo.PairedPc
import com.lucyremota.protocolo.PhoneStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Identidad del teléfono: clave EC en el almacén seguro de Android (no sale del teléfono). */
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
    private val _pcs = MutableStateFlow(readAll())
    // «activa» vacía = desconectado a propósito (no se elige otra sola al abrir).
    private val _active = MutableStateFlow(
        if (prefs.contains("activa")) prefs.getString("activa", "")!!.ifEmpty { null } else _pcs.value.firstOrNull()?.id,
    )
    /** Todas las PC vinculadas (para la lista en pantalla). */
    val pcs: StateFlow<List<PairedPc>> = _pcs.asStateFlow()
    /** La PC elegida. */
    val active: StateFlow<String?> = _active.asStateFlow()

    override val deviceId: String = prefs.getString("id", null) ?: ("tel-" + UUID.randomUUID().toString().replace("-", "").take(16)).also {
        prefs.edit().putString("id", it).apply()
    }

    override val deviceName: String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ")

    override val pairedPcs: List<PairedPc> get() = _pcs.value

    override var pairedPc: PairedPc?
        get() = _pcs.value.firstOrNull { it.id == _active.value }
        @Synchronized set(value) {
            if (value == null) {
                _active.value?.let(::removePc)
                return
            }
            save(_pcs.value.filter { it.id != value.id } + value, value.id)
        }

    @Synchronized override fun selectPc(id: String): Boolean {
        if (_pcs.value.none { it.id == id }) return false
        save(_pcs.value, id)
        return true
    }

    @Synchronized override fun clearSelection() = save(_pcs.value, null)

    @Synchronized override fun removePc(id: String) {
        val rest = _pcs.value.filter { it.id != id }
        save(rest, if (_active.value == id) rest.lastOrNull()?.id else _active.value)
    }

    private fun save(list: List<PairedPc>, active: String?) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("key", B64.enc(it.publicKey))) }
        prefs.edit().putString("pcs", arr.toString()).putString("activa", active ?: "").remove("pc").apply()
        _pcs.value = list
        _active.value = active
    }

    private fun parse(j: JSONObject) = PairedPc(j.getString("id"), j.getString("name"), B64.dec(j.getString("key")))

    private fun readAll(): List<PairedPc> {
        prefs.getString("pcs", null)?.let { raw ->
            return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { parse(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        }
        // Versiones anteriores guardaban una sola PC.
        return listOfNotNull(prefs.getString("pc", null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() })
    }
}
