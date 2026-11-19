package com.lucyremota.protocolo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Conexión con el relé de Internet (para cuando el teléfono y la PC están en redes distintas).
 * Ver «Relé» en PROTOCOLO.md: los dos se conectan hacia afuera a la misma sala y el relé reenvía
 * los bytes, que ya van cifrados de punta a punta. Cada vez que llega la PC («peer:1») hay un flujo
 * nuevo ([RelayStream]) para negociar desde cero.
 */
class RelayClient private constructor(url: String, http: OkHttpClient) : WebSocketListener() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opened = CompletableDeferred<Boolean>()
    private val peers = Channel<RelayStream>(Channel.UNLIMITED)
    @Volatile private var current: RelayStream? = null
    @Volatile private var lastHeardAt = System.currentTimeMillis()
    @Volatile private var closed = false
    private val ws: WebSocket = http.newWebSocket(Request.Builder().url(url).build(), this)

    /** Espera a que la conexión con el relé esté abierta. */
    suspend fun awaitOpen(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { opened.await() } ?: false

    /** Espera a que la PC esté en el relé; null si se cortó la conexión. */
    suspend fun nextPeer(): RelayStream? = peers.receiveCatching().getOrNull()

    override fun onOpen(webSocket: WebSocket, response: Response) {
        lastHeardAt = System.currentTimeMillis()
        opened.complete(true)
        scope.launch {
            while (isActive && !closed) {
                delay(KEEPALIVE_MS)
                // Sin nada del relé (ni el «pong») en un buen rato: la conexión murió sin avisar.
                if (System.currentTimeMillis() - lastHeardAt > SILENCE_MS) { close(); return@launch }
                ws.send("ping")
            }
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        lastHeardAt = System.currentTimeMillis()
        when (text) {
            "peer:1" -> {
                current?.end()
                val s = RelayStream(this)
                current = s
                peers.trySend(s)
            }
            "peer:0" -> { current?.end(); current = null }
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        lastHeardAt = System.currentTimeMillis()
        current?.push(bytes.toByteArray())
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        finish()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish()

    private fun finish() {
        closed = true
        opened.complete(false)
        current?.end()
        current = null
        peers.close()
        scope.cancel()
    }

    internal fun send(from: RelayStream, data: ByteArray) {
        if (from !== current || closed) throw IOException("la PC ya no está en el relé")
        if (!ws.send(data.toByteString())) throw IOException("se cortó la conexión con el relé")
    }

    fun close() {
        if (!closed) runCatching { ws.close(1000, "chau") }
        ws.cancel()
        finish()
    }

    companion object {
        const val DEFAULT_URL = "https://lucy-remota-relay.devecchianderson12.workers.dev"
        private const val KEEPALIVE_MS = 25_000L
        private const val SILENCE_MS = 70_000L

        /** Sala de un par PC–teléfono: sale de las dos claves públicas (sólo ellos la conocen). */
        fun roomId(pcKey: ByteArray, devKey: ByteArray): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.update("lucy-remota/relay/1".toByteArray(Charsets.US_ASCII))
            md.update(pcKey)
            md.update(devKey)
            return md.digest().joinToString("") { "%02x".format(it) }.substring(0, 40)
        }

        fun roomUrl(base: String, room: String, role: String): String {
            val b = base.trimEnd('/').replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
            return "$b/v1/$room?role=$role"
        }

        val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(0, TimeUnit.MILLISECONDS) // el latido es el «ping» de texto (no despierta la sala)
                .build()
        }

        fun connect(url: String, http: OkHttpClient = defaultHttp) = RelayClient(url, http)
    }
}

/**
 * Un flujo de bytes con la PC a través del relé. Lo escrito se junta hasta el flush y va en un solo
 * mensaje (un marco del protocolo = un mensaje). Termina (fin de flujo) si la PC se va.
 */
class RelayStream internal constructor(private val client: RelayClient) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var ended = false
    /** Como el soTimeout de un socket: 0 = esperar para siempre. */
    @Volatile var readTimeoutMs = 0

    internal fun push(data: ByteArray) { if (!ended) queue.put(data) }
    internal fun end() { if (!ended) { ended = true; queue.put(END) } }
    fun close() = end()

    val input: InputStream = object : InputStream() {
        private var pending = ByteArray(0)
        private var at = 0

        private fun fill(): Boolean {
            while (at >= pending.size) {
                if (pending === END) return false
                val t = readTimeoutMs
                val next = (if (t > 0) queue.poll(t.toLong(), TimeUnit.MILLISECONDS) else queue.take())
                    ?: throw SocketTimeoutException("la PC no mandó nada en $t ms")
                pending = next
                at = 0
                if (next === END) { queue.put(END); return false }
            }
            return true
        }

        override fun read(): Int = if (fill()) pending[at++].toInt() and 0xff else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, pending.size - at)
            System.arraycopy(pending, at, b, off, n)
            at += n
            return n
        }
    }

    val output: OutputStream = object : OutputStream() {
        private val buf = ByteArrayOutputStream()
        override fun write(b: Int) = synchronized(buf) { buf.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = synchronized(buf) { buf.write(b, off, len) }
        override fun flush() {
            val data = synchronized(buf) { buf.toByteArray().also { buf.reset() } }
            if (data.isNotEmpty()) client.send(this@RelayStream, data)
        }
    }

    private companion object { val END = ByteArray(0) }
}
