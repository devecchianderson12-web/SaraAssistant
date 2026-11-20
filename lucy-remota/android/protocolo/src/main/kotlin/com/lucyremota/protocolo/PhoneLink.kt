package com.lucyremota.protocolo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** La PC vinculada: su clave pública queda fija (nadie más puede hacerse pasar por ella). */
data class PairedPc(val id: String, val name: String, val publicKey: ByteArray)

/**
 * Dónde guarda el teléfono su identidad y las PC vinculadas. Se pueden vincular varias (cada una con
 * su QR, una sola vez) y se elige con cuál conectarse: [pairedPc] es la elegida.
 */
interface PhoneStore {
    val deviceId: String
    val deviceName: String
    /** La PC elegida. Asignar una la agrega (o actualiza) y la elige; asignar null la desvincula. */
    var pairedPc: PairedPc?
    /** Todas las PC vinculadas. */
    val pairedPcs: List<PairedPc> get() = listOfNotNull(pairedPc)
    /** Elige otra PC ya vinculada. false si no está. */
    fun selectPc(id: String): Boolean = pairedPc?.id == id
    /** Desvincula una PC (si era la elegida, queda elegida otra de las vinculadas). */
    fun removePc(id: String) { if (pairedPc?.id == id) pairedPc = null }
}

/** Una intención que la PC sabe hacer (del catálogo que manda al conectarse). */
data class RemoteIntent(val name: String, val module: String, val description: String, val risk: String, val params: Set<String>)

sealed interface LinkState {
    data object Unpaired : LinkState
    /** Esperando que la PC (con el QR o el código) se conecte. */
    data class Pairing(val pcName: String?) : LinkState
    data class PairingFailed(val reason: String) : LinkState
    /** Vinculada, buscando la PC en la red. */
    data class Searching(val pcName: String) : LinkState
    data class Connected(val pcName: String, val address: String, val latencyMs: Long) : LinkState
}

/** Estado de una orden (los mismos que la PC). */
data class CommandStatus(val commandId: String, val status: String, val message: String, val result: JSONObject?, val code: String?) {
    val final: Boolean get() = status != RECEIVED && status != PROCESSING

    companion object {
        const val RECEIVED = "received"
        const val PROCESSING = "processing"
        const val SUCCESS = "success"
        const val ERROR = "error"
        const val UNSUPPORTED = "unsupported"
        const val INVALID = "invalid"
        const val EXPIRED = "expired"
        const val NEEDS_CONFIRMATION = "needs_confirmation"
        /** Del lado del teléfono: no se pudo mandar (sin conexión) o la PC no contestó. */
        const val NOT_CONNECTED = "not_connected"
        const val NO_ANSWER = "no_answer"
    }
}

/**
 * Conexión del teléfono (Emisor) con la PC (Receptor).
 *
 * La PC anuncia por UDP que está; el teléfono contesta con su puerto y la PC se conecta a él (así la
 * PC no necesita permisos de administrador para el firewall). Después: negociación con claves,
 * mensajes cifrados, latido y reconexión sola.
 *
 * Si están en redes distintas (datos móviles), se encuentran en el relé de Internet ([relayUrl]):
 * la misma negociación y el mismo cifrado de punta a punta, por otro camino. En cuanto vuelven a
 * estar en la misma red, la PC se conecta directo y se deja el relé.
 */
class PhoneLink(
    private val store: PhoneStore,
    private val identity: DeviceIdentity,
    private val log: (String) -> Unit = {},
    private val discoveryPort: Int = Proto.DISCOVERY_PORT,
    private val tcpPort: Int = Proto.PHONE_PORT,
    /** Relé para redes distintas (null: sólo red local). */
    private val relayUrl: String? = RelayClient.DEFAULT_URL,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<LinkState>(LinkState.Unpaired)
    val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _catalog = MutableStateFlow<List<RemoteIntent>>(emptyList())
    /** Lo que sabe hacer la PC conectada. */
    val catalog: StateFlow<List<RemoteIntent>> = _catalog.asStateFlow()

    @Volatile private var pendingQr: PairingQr? = null
    @Volatile private var pendingCode: String? = null
    @Volatile private var session: Session? = null
    @Volatile private var handshakeAt = 0L
    private var udp: DatagramSocket? = null
    private var server: ServerSocket? = null
    private val waiters = ConcurrentHashMap<String, (CommandStatus) -> Unit>()
    @Volatile private var relay: RelayClient? = null

    /** Puerto TCP en el que escucha (el habitual, u otro si estaba ocupado). */
    val listeningPort: Int get() = server?.localPort ?: -1

    fun start() {
        refreshState()
        server = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(tcpPort)) } }
            .getOrElse { ServerSocket(0) }
        udp = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(discoveryPort)) }
        scope.launch { acceptLoop() }
        scope.launch { beaconLoop() }
        if (relayUrl != null) scope.launch { relayLoop(relayUrl) }
        log("Escuchando la PC (UDP $discoveryPort, TCP $listeningPort)" + if (relayUrl != null) " y por el relé" else "")
    }

    private fun refreshState() {
        val pc = store.pairedPc
        val s = session
        _state.value = when {
            s != null -> LinkState.Connected(s.pcName, s.address, s.latencyMs)
            pendingQr != null -> LinkState.Pairing(pendingQr?.pcName)
            pendingCode != null -> LinkState.Pairing(null)
            pc != null -> LinkState.Searching(pc.name)
            else -> LinkState.Unpaired
        }
    }

    /** Escaneaste el QR de la PC: queda esperando que se conecte (la PC lo hace sola). */
    fun pairWithQr(qr: PairingQr) {
        pendingCode = null
        pendingQr = qr
        session?.close()
        refreshState()
    }

    /** Escribiste el código que muestra la PC. */
    fun pairWithCode(code: String) {
        pendingQr = null
        pendingCode = PairingCodes.normalizeManual(code)
        session?.close()
        refreshState()
    }

    fun cancelPairing() {
        pendingQr = null
        pendingCode = null
        refreshState()
    }

    /** Desvincula la PC elegida: avisa a la PC (si está conectada) y borra su clave. */
    suspend fun unpair() {
        session?.let { runCatching { it.send(JSONObject().put("t", "unpair")) }; it.close() }
        store.pairedPc = null
        _catalog.value = emptyList()
        refreshState()
    }

    /** Desvincula otra PC (no la elegida): sólo se borra su clave acá. */
    fun forget(pcId: String) {
        if (store.pairedPc?.id == pcId) return
        store.removePc(pcId)
        refreshState()
    }

    /**
     * Cambia a otra PC vinculada: se corta la conexión actual y se busca la elegida (en la red local
     * o por el relé). Las demás quedan vinculadas para cambiar cuando quieras.
     */
    fun selectPc(pcId: String): Boolean {
        if (store.pairedPc?.id == pcId) return true
        if (!store.selectPc(pcId)) return false
        session?.close()
        relay?.close()
        _catalog.value = emptyList()
        refreshState()
        log("Cambiada a ${store.pairedPc?.name}")
        return true
    }

    // ------------------------------------------------------------------ Anuncios de la PC

    private fun beaconLoop() {
        val buf = ByteArray(2048)
        val socket = udp ?: return
        while (scope.isActive) {
            val packet = DatagramPacket(buf, buf.size)
            try { socket.receive(packet) } catch (e: SocketException) { return } catch (e: Exception) { continue }
            val msg = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
            // Ya conectada o conectándose: no se contesta (una sola conexión con la PC). Por el relé sí:
            // si la PC está en la misma red, se pasa a la conexión directa.
            if (msg.optString("t") != "beacon" || session?.viaRelay == false || System.currentTimeMillis() - handshakeAt < 8_000) continue
            val pc = msg.optString("pc")
            val interested = store.pairedPc?.id == pc || pendingQr?.pcId == pc || (pendingCode != null && msg.optBoolean("pairing"))
            if (!interested) continue
            val reply = JSONObject().put("lr", Proto.MAJOR).put("t", "here").put("pc", pc).put("dev", store.deviceId).put("port", listeningPort)
                .toString().toByteArray()
            runCatching { socket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
        }
    }

    // ------------------------------------------------------------------ Conexión de la PC

    private fun acceptLoop() {
        val srv = server ?: return
        while (scope.isActive) {
            val socket = try { srv.accept() } catch (e: SocketException) { return } catch (e: Exception) { continue }
            scope.launch { handle(TcpConn(socket)) }
        }
    }

    // ------------------------------------------------------------------ Relé (redes distintas)

    /** Con la PC vinculada y sin conexión directa, espera a la PC en el relé. */
    private suspend fun relayLoop(url: String) {
        var failures = 0
        while (scope.isActive) {
            val pc = store.pairedPc
            if (pc == null || session?.viaRelay == false || pendingQr != null || pendingCode != null) { delay(2_000); continue }
            val client = RelayClient.connect(RelayClient.roomUrl(url, RelayClient.roomId(pc.publicKey, identity.publicKeySpki), "dev"))
            relay = client
            try {
                if (!client.awaitOpen(12_000)) throw java.io.IOException("no se pudo conectar")
                if (failures > 0) log("Relé de Internet disponible otra vez")
                failures = 0
                // Si la PC aparece en la red local (o se desvincula), se deja el relé.
                val watcher = scope.launch {
                    while (isActive) {
                        delay(1_000)
                        if (session?.viaRelay == false || store.pairedPc?.id != pc.id) { client.close(); break }
                    }
                }
                try {
                    while (true) {
                        val peer = client.nextPeer() ?: break
                        scope.launch { handle(RelayConn(peer)) }
                    }
                } finally { watcher.cancel() }
            } catch (e: Exception) {
                if (failures++ == 0) log("Relé de Internet no disponible: ${e.message}")
            } finally {
                client.close()
                if (relay === client) relay = null
            }
            // Reintento con espera creciente (hasta 30 s) para no gastar datos si el relé no anda.
            delay(1_000L * minOf(30, 1 shl minOf(failures, 5)))
        }
    }

    /** Cambió la red del teléfono (Wi‑Fi ↔ datos): se rehace la conexión con el relé enseguida. */
    fun networkChanged() {
        relay?.close()
    }

    private interface Conn {
        val input: InputStream
        val output: OutputStream
        val address: String
        val viaRelay: Boolean
        fun setTimeout(ms: Int)
        fun close()
    }

    private class TcpConn(private val socket: Socket) : Conn {
        override val input: InputStream = socket.getInputStream().buffered()
        override val output: OutputStream = socket.getOutputStream()
        override val address: String = socket.inetAddress.hostAddress ?: ""
        override val viaRelay = false
        init { socket.tcpNoDelay = true }
        override fun setTimeout(ms: Int) { socket.soTimeout = ms }
        override fun close() { runCatching { socket.close() } }
    }

    private class RelayConn(private val stream: RelayStream) : Conn {
        override val input: InputStream = stream.input
        override val output: OutputStream = stream.output
        override val address = "Internet"
        override val viaRelay = true
        override fun setTimeout(ms: Int) { stream.readTimeoutMs = ms }
        override fun close() = stream.close()
    }

    private suspend fun handle(socket: Conn) {
        // Otra conexión de la misma PC negociando ahora: ésta sobra.
        if (System.currentTimeMillis() - handshakeAt < 8_000 && session == null) { socket.close(); return }
        handshakeAt = System.currentTimeMillis()
        socket.setTimeout(if (socket.viaRelay) 15_000 else 10_000)
        val input = socket.input
        val output = socket.output
        try {
            val hello = JSONObject(String(Wire.read(input)))
            if (hello.optString("t") != "hello") throw ProtocolException("se esperaba «hello»")
            if (hello.optInt("v") != Proto.MAJOR) throw ProtocolException("versión de protocolo incompatible")
            val pcId = hello.getString("pc")
            val pcName = hello.optString("name", "Computadora")
            val pcKey = B64.dec(hello.getString("pc_key"))
            val pcEph = B64.dec(hello.getString("eph"))
            val pcNonce = B64.dec(hello.getString("nonce"))
            val paired = store.pairedPc
            val qr = pendingQr
            val code = pendingCode
            var mode: String? = null
            var token: ByteArray? = null
            when {
                paired != null && paired.id == pcId -> if (!Crypto.constantEquals(paired.publicKey, pcKey)) {
                    log("Rechazada: la clave de la PC cambió (¿otra computadora con el mismo nombre?)")
                    socket.close(); return
                }
                // Por el relé no se vincula: sólo la PC ya vinculada en persona.
                socket.viaRelay -> { socket.close(); return }
                qr != null && qr.pcId == pcId -> {
                    if (!Crypto.constantEquals(Crypto.sha256(pcKey), qr.fingerprint)) {
                        log("Rechazada: la PC no coincide con el QR")
                        socket.close(); return
                    }
                    mode = "qr"; token = qr.token
                }
                code != null -> { mode = "code"; token = PairingCodes.manualToken(code) }
                else -> { socket.close(); return }
            }
            val eph = EphemeralKey()
            val nonce = Crypto.randomBytes(32)
            val transcript = Transcript.hash(pcId, pcKey, pcEph, pcNonce, store.deviceId, identity.publicKeySpki, eph.publicSpki, nonce)
            val auth = JSONObject().put("t", "auth").put("v", Proto.MAJOR).put("minor", Proto.MINOR).put("dev", store.deviceId)
                .put("name", store.deviceName).put("dev_key", B64.enc(identity.publicKeySpki)).put("eph", B64.enc(eph.publicSpki))
                .put("nonce", B64.enc(nonce)).put("now", System.currentTimeMillis())
                .put("sig", B64.enc(identity.sign(Transcript.role("dev", transcript))))
            if (token != null) auth.put("pair", B64.enc(Transcript.pairProof(token, "dev", transcript))).put("pair_mode", mode)
            Wire.write(output, auth.toString().toByteArray())
            val answer = JSONObject(String(Wire.read(input)))
            if (answer.optString("t") != "auth_ok") {
                val reason = answer.optString("reason", "rechazada")
                log("La PC rechazó la conexión: $reason")
                if (reason == "bad_pairing_code" || (reason == "unauthorized" && token != null)) {
                    // Código equivocado o vencido: se deja de intentar (no gasta los intentos de la PC).
                    pendingQr = null; pendingCode = null
                    _state.value = LinkState.PairingFailed(if (mode == "code") "El código no coincide o venció." else "El QR venció. Generá uno nuevo en la PC.")
                } else if (reason == "unauthorized" && paired?.id == pcId && !socket.viaRelay) {
                    // La PC ya no nos tiene vinculados (la desvincularon desde allá). Por el relé no se
                    // le cree (un rechazo sin firma no alcanza para borrar la vinculación).
                    store.removePc(pcId)
                    refreshState()
                }
                socket.close(); return
            }
            if (!Crypto.verify(pcKey, Transcript.role("pc", transcript), B64.dec(answer.getString("sig")))) throw ProtocolException("firma de la PC inválida")
            if (mode == "code") {
                val proof = answer.optString("pair").takeIf { it.isNotEmpty() }?.let(B64::dec)
                if (proof == null || !Crypto.constantEquals(proof, Transcript.pairProof(token!!, "pc", transcript))) throw ProtocolException("la PC no conoce el código")
            }
            val (pcToDev, devToPc) = Transcript.sessionKeys(eph.agree(pcEph), transcript)
            if (token != null) {
                store.pairedPc = PairedPc(pcId, pcName, pcKey)
                pendingQr = null; pendingCode = null
                log("Vinculada con $pcName")
            }
            socket.setTimeout(0)
            val s = Session(socket, SessionCipher(devToPc, pcToDev), pcName)
            session?.close()
            session = s
            handshakeAt = 0L
            refreshState()
            log("Conectada con $pcName (${s.address})")
            try { s.run() } finally {
                if (session === s) session = null
                refreshState()
                log("Se cortó la conexión con $pcName")
            }
        } catch (e: Exception) {
            log("Conexión fallida: ${e.message}")
            socket.close()
        } finally {
            handshakeAt = 0L
        }
    }

    // ------------------------------------------------------------------ Órdenes

    /**
     * Manda una orden y espera el resultado final. [onStatus] recibe cada estado (recibida, en
     * curso…). Si la conexión se corta antes de que la PC la reciba, se reintenta con el mismo id
     * mientras no venza (la PC no la ejecuta dos veces).
     */
    suspend fun send(
        intent: String,
        params: JSONObject = JSONObject(),
        confirmed: Boolean = false,
        expiresMs: Int = 10_000,
        onStatus: (CommandStatus) -> Unit = {},
    ): CommandStatus {
        val id = UUID.randomUUID().toString()
        val sentAt = System.currentTimeMillis()
        val cmd = JSONObject().put("t", "command").put("v", Proto.MAJOR).put("command_id", id).put("intent", intent)
            .put("params", params).put("sent_at", sentAt).put("expires_ms", expiresMs).put("confirmed", confirmed)
        val final = CompletableDeferred<CommandStatus>()
        var received = false
        waiters[id] = { st ->
            if (st.status == CommandStatus.RECEIVED) received = true
            onStatus(st)
            if (st.final) final.complete(st)
        }
        try {
            while (!final.isCompleted) {
                val s = session ?: awaitSession(sentAt + expiresMs - System.currentTimeMillis())
                if (s == null) return CommandStatus(id, CommandStatus.NOT_CONNECTED, "La computadora no está conectada.", null, null)
                runCatching { s.send(cmd) }
                // Si la PC la recibió, se espera el resultado (la ejecución puede tardar).
                val answer = withTimeoutOrNull(if (received) 30_000 else 4_000) { final.await() }
                if (answer != null) return answer
                if (received) return CommandStatus(id, CommandStatus.NO_ANSWER, "La computadora no contestó.", null, null)
                if (System.currentTimeMillis() - sentAt > expiresMs) return CommandStatus(id, CommandStatus.NO_ANSWER, "La computadora no contestó.", null, null)
                // No llegó: se reintenta con el mismo id (si llegó y se perdió la respuesta, la PC devuelve el resultado guardado).
            }
            return final.await()
        } finally {
            waiters.remove(id)
        }
    }

    private suspend fun awaitSession(maxMs: Long): Session? {
        if (store.pairedPc == null || maxMs <= 0) return null
        return withTimeoutOrNull(maxMs.coerceAtMost(6_000)) {
            while (session == null) delay(100)
            session
        }
    }

    // ------------------------------------------------------------------ Sesión

    private inner class Session(
        private val socket: Conn,
        private val cipher: SessionCipher,
        val pcName: String,
    ) {
        private val input = socket.input
        private val output = socket.output
        val address: String get() = socket.address
        val viaRelay: Boolean get() = socket.viaRelay
        @Volatile var latencyMs = 0L
        private var pingJob: Job? = null
        private var lastPingAt = 0L

        fun send(msg: JSONObject) = synchronized(this) { Wire.write(output, cipher.seal(msg.toString().toByteArray())) }

        fun close() {
            pingJob?.cancel()
            socket.close()
        }

        fun run() {
            socket.setTimeout(45_000) // sin nada en 45 s (la PC manda un latido cada 15 s): se corta y se reconecta
            pingJob = scope.launch {
                while (isActive) {
                    delay(20_000)
                    lastPingAt = System.currentTimeMillis()
                    runCatching { send(JSONObject().put("t", "ping").put("at", lastPingAt)) }
                }
            }
            try {
                while (true) {
                    val msg = JSONObject(String(cipher.open(Wire.read(input))))
                    when (msg.optString("t")) {
                        "capabilities" -> _catalog.value = parseCatalog(msg.optJSONArray("intents"))
                        "status" -> {
                            val st = CommandStatus(
                                msg.optString("command_id"), msg.optString("status"), msg.optString("message"),
                                msg.optJSONObject("result"), msg.optString("code").ifEmpty { null },
                            )
                            waiters[st.commandId]?.invoke(st)
                        }
                        "ping" -> send(JSONObject().put("t", "pong").put("at", msg.optLong("at")))
                        "pong" -> if (msg.optLong("at") == lastPingAt) { latencyMs = System.currentTimeMillis() - lastPingAt; refreshState() }
                        "unpaired" -> {
                            log("La PC desvinculó este teléfono")
                            store.pairedPc = null
                            _catalog.value = emptyList()
                            return
                        }
                        "bye" -> return
                    }
                }
            } catch (e: Exception) {
                log("Sesión terminada: ${e.message}")
            } finally {
                close()
            }
        }
    }

    private fun parseCatalog(arr: JSONArray?): List<RemoteIntent> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
        RemoteIntent(o.optString("intent"), o.optString("module"), o.optString("description"), o.optString("risk"), o.optJSONObject("params")?.keySet() ?: emptySet())
    }

    override fun close() {
        session?.close()
        relay?.close()
        runCatching { udp?.close() }
        runCatching { server?.close() }
        scope.coroutineContext[Job]?.cancel()
    }
}
