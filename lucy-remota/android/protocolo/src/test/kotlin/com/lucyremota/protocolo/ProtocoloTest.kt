package com.lucyremota.protocolo

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ProtocoloTest {
    @Test
    fun `cifrado y claves coinciden en los dos lados`() {
        val a = EphemeralKey()
        val b = EphemeralKey()
        val shared = a.agree(b.publicSpki)
        assertTrue(shared.contentEquals(b.agree(a.publicSpki)))
        val (k1, k2) = Transcript.sessionKeys(shared, ByteArray(32))
        val pc = SessionCipher(k1, k2)
        val phone = SessionCipher(k2, k1)
        val sealed = pc.seal("hola".toByteArray())
        assertEquals("hola", String(phone.open(sealed)))
        // Repetido: no descifra.
        assertTrue(runCatching { phone.open(sealed) }.exceptionOrNull() is ProtocolException)

        val id = SoftwareIdentity()
        val sig = id.sign("dato".toByteArray())
        assertTrue(Crypto.verify(id.publicKeySpki, "dato".toByteArray(), sig))
        assertTrue(!Crypto.verify(id.publicKeySpki, "otro".toByteArray(), sig))
    }

    @Test
    fun `hkdf igual a la referencia RFC 5869`() {
        // Caso de prueba 1 de la RFC 5869.
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = Crypto.hkdf(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `lee el QR de la PC y normaliza el codigo manual`() {
        val qr = PairingQr.parse("lucyremota://pair?v=1&pc=pc-1&n=Mi%20PC&f=${"A".repeat(43)}&t=${"B".repeat(22)}")
        assertNotNull(qr)
        assertEquals("Mi PC", qr!!.pcName)
        assertEquals(null, PairingQr.parse("https://otra-cosa"))
        assertTrue(PairingCodes.manualToken("k7m2-9xq4").contentEquals(PairingCodes.manualToken("K7M29XQ4")))
    }

    private class MemStore : PhoneStore {
        override val deviceId = "tel-interop-1"
        override val deviceName = "Teléfono (prueba)"
        override var pairedPc: PairedPc? = null
    }

    /** Contra el Receptor real de Windows (.NET): la compilación en la nube arranca la PC simulada. */
    @Test
    fun `interoperabilidad con el Receptor de Windows`() = runBlocking {
        val qrFile = System.getenv("LUCY_INTEROP_QR")
        assumeTrue("sin PC simulada", qrFile != null)
        val file = File(qrFile!!)
        withTimeout(30_000) { while (!file.exists()) delay(200) }
        val qr = PairingQr.parse(file.readText().trim())!!
        val store = MemStore()
        val identity = SoftwareIdentity()
        val link = PhoneLink(store, identity, { println("[tel] $it") }, relayUrl = null)
        link.start()
        link.pairWithQr(qr)
        withTimeout(15_000) { while (link.state.value !is LinkState.Connected) delay(100) }
        assertNotNull(store.pairedPc)
        withTimeout(5_000) { while (link.catalog.value.isEmpty()) delay(50) }
        assertTrue(link.catalog.value.any { it.name == "SET_VOLUME" })

        val statuses = mutableListOf<String>()
        val ok = link.send("SET_VOLUME", JSONObject().put("level", 70)) { statuses += it.status }
        assertEquals(CommandStatus.SUCCESS, ok.status)
        assertEquals(listOf("received", "processing", "success"), statuses)
        assertEquals(70, ok.result!!.getInt("level"))
        assertEquals(CommandStatus.INVALID, link.send("SET_VOLUME", JSONObject().put("level", 300)).status)
        assertEquals(CommandStatus.UNSUPPORTED, link.send("FORMAT_DISK").status)
        assertEquals(CommandStatus.NEEDS_CONFIRMATION, link.send("SHUTDOWN").status)
        assertEquals(CommandStatus.SUCCESS, link.send("SHUTDOWN", confirmed = true).status)

        val relay = System.getenv("LUCY_RELAY_URL")
        if (relay == null) {
            link.unpair()
            assertEquals(null, store.pairedPc)
            link.close()
            return@runBlocking
        }
        // Redes distintas: el teléfono ya no oye los anuncios de la PC; se encuentran en el relé.
        link.close()
        val far = PhoneLink(store, identity, { println("[tel-relé] $it") }, discoveryPort = 47_999, tcpPort = 0, relayUrl = relay)
        far.start()
        withTimeout(30_000) { while ((far.state.value as? LinkState.Connected)?.address != "Internet") delay(100) }
        withTimeout(5_000) { while (far.catalog.value.isEmpty()) delay(50) }
        val viaRelay = far.send("SET_VOLUME", JSONObject().put("level", 55))
        assertEquals(CommandStatus.SUCCESS, viaRelay.status)
        assertEquals(55, viaRelay.result!!.getInt("level"))
        far.unpair()
        assertEquals(null, store.pairedPc)
        far.close()
    }
}
