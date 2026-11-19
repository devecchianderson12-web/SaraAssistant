package com.lucyremota.protocolo

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Constantes del protocolo (iguales a las del Receptor de Windows; ver protocol/PROTOCOLO.md). */
object Proto {
    const val MAJOR = 1
    const val MINOR = 0
    const val DISCOVERY_PORT = 47801
    const val PHONE_PORT = 47800
    const val MAX_FRAME = 256 * 1024
    const val LABEL = "lucy-remota/1"
}

class ProtocolException(message: String) : Exception(message)

/** Marcos: 4 bytes (big endian) con el largo y después los datos. */
object Wire {
    fun write(out: OutputStream, payload: ByteArray) {
        if (payload.size > Proto.MAX_FRAME) throw ProtocolException("marco demasiado grande")
        val d = DataOutputStream(out)
        d.writeInt(payload.size)
        d.write(payload)
        d.flush()
    }

    fun read(input: InputStream): ByteArray {
        val d = DataInputStream(input)
        val len = try { d.readInt() } catch (e: EOFException) { throw EOFException("la conexión se cerró") }
        if (len < 0 || len > Proto.MAX_FRAME) throw ProtocolException("largo de marco inválido: $len")
        return ByteArray(len).also { d.readFully(it) }
    }
}

object B64 {
    fun enc(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun dec(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun urlDec(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
}

/** Identidad del teléfono: en Android la clave vive en el almacén seguro y nunca sale de él. */
interface DeviceIdentity {
    /** Clave pública en formato SubjectPublicKeyInfo (DER). */
    val publicKeySpki: ByteArray
    /** Firma SHA256withECDSA (DER). */
    fun sign(data: ByteArray): ByteArray
}

/** Identidad en memoria (pruebas). */
class SoftwareIdentity : DeviceIdentity {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val publicKeySpki: ByteArray = pair.public.encoded
    override fun sign(data: ByteArray): ByteArray = Crypto.sign(pair.private, data)
}

object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun sign(key: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(key); update(data); sign() }

    fun verify(spki: ByteArray, data: ByteArray, sig: ByteArray): Boolean = runCatching {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(data); verify(sig) }
    }.getOrDefault(false)

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun constantEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}

/** Clave efímera ECDH P-256 de una conexión. */
class EphemeralKey {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val publicSpki: ByteArray = pair.public.encoded

    fun agree(otherSpki: ByteArray): ByteArray {
        val other = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(otherSpki)) }
            .getOrElse { throw ProtocolException("clave efímera inválida") }
        return KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(other, true); generateSecret() }
    }
}

object Transcript {
    fun hash(pcId: String, pcKey: ByteArray, pcEph: ByteArray, pcNonce: ByteArray, devId: String, devKey: ByteArray, devEph: ByteArray, devNonce: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun put(b: ByteArray) {
            out.write(ByteBuffer.allocate(4).putInt(b.size).array())
            out.write(b)
        }
        put(Proto.LABEL.toByteArray())
        put(pcId.toByteArray())
        put(pcKey)
        put(pcEph)
        put(pcNonce)
        put(devId.toByteArray())
        put(devKey)
        put(devEph)
        put(devNonce)
        return Crypto.sha256(out.toByteArray())
    }

    fun role(role: String, transcript: ByteArray) = role.toByteArray(Charsets.US_ASCII) + transcript

    fun pairProof(token: ByteArray, role: String, transcript: ByteArray) = Crypto.hmac(token, role(role, transcript))

    /** (pc → teléfono, teléfono → pc). */
    fun sessionKeys(shared: ByteArray, transcript: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = Crypto.hkdf(shared, transcript, "${Proto.LABEL} keys".toByteArray(), 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }
}

/** AES-256-GCM con contador por sentido (un mensaje repetido o alterado no descifra). */
class SessionCipher(sendKey: ByteArray, receiveKey: ByteArray) {
    private val sendKey = SecretKeySpec(sendKey, "AES")
    private val receiveKey = SecretKeySpec(receiveKey, "AES")
    private var sendCounter = 0L
    private var receiveCounter = 0L

    private fun nonce(counter: Long) = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    @Synchronized
    fun seal(plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sendCounter++))); doFinal(plain) }

    fun open(data: ByteArray): ByteArray {
        if (data.size < 16) throw ProtocolException("mensaje cifrado incompleto")
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(128, nonce(receiveCounter))); doFinal(data) }
                .also { receiveCounter++ }
        } catch (e: AEADBadTagException) {
            throw ProtocolException("mensaje cifrado inválido (alterado o repetido)")
        }
    }
}

object PairingCodes {
    fun normalizeManual(code: String) = code.uppercase().filter { it.isLetterOrDigit() }
    fun manualToken(code: String) = ("manual:" + normalizeManual(code)).toByteArray()
}

/** Lo que trae el QR que muestra la PC. */
data class PairingQr(val pcId: String, val pcName: String, val fingerprint: ByteArray, val token: ByteArray) {
    companion object {
        /** «lucyremota://pair?v=1&pc=…&n=…&f=…&t=…» → datos, o null si no es un QR de Lucy Remota. */
        fun parse(text: String): PairingQr? {
            if (!text.startsWith("lucyremota://pair?")) return null
            val q = text.substringAfter('?').split('&').mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.substring(0, i) to java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }.toMap()
            if (q["v"] != "1") return null
            return runCatching {
                PairingQr(q.getValue("pc"), q["n"] ?: "Computadora", B64.urlDec(q.getValue("f")), B64.urlDec(q.getValue("t")))
            }.getOrNull()?.takeIf { it.fingerprint.size == 32 && it.token.size >= 16 }
        }
    }
}
