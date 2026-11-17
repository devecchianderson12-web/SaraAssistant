package com.sara.lucy

import com.sara.core.interpreter.CompositeCommandInterpreter
import com.sara.core.interpreter.RuleBasedCommandInterpreter
import com.sara.core.memory.InMemoryStore
import com.sara.core.model.AssistantEvent
import com.sara.core.model.CommandIntent
import com.sara.core.util.WakeWordTuning
import com.sara.lucy.audio.Microphone
import com.sara.lucy.ui.MainWindow
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Autoprueba en una Windows real (la compilación en la nube la corre antes de publicar): el mismo
 * cerebro que Android entiende las órdenes con «Lucy», el puente con Windows responde, el modelo de
 * voz carga, Lucy contesta por texto y la ventana con la esfera se dibuja.
 */
class SelfTest(private val dir: File) {
    private val report = StringBuilder()
    private var failures = 0

    private fun ok(name: String, detail: String = "") = line("OK   $name ${detail.take(300)}")
    private fun fail(name: String, detail: String) { failures++; line("FAIL $name ${detail.take(500)}") }
    private fun info(name: String, detail: String) = line("INFO $name ${detail.take(500)}")
    private fun line(text: String) { println(text); report.appendLine(text) }

    fun run(): Int {
        dir.mkdirs()
        LucyIdentity.apply()
        runCatching { interpreter() }.onFailure { fail("intérprete", it.toString()) }
        val app = runCatching { LucyApp() }.getOrElse { fail("arranque", it.toString()); return finish() }
        runCatching { windows(app) }.onFailure { fail("windows", it.toString()) }
        runCatching { voice(app) }.onFailure { fail("voz", it.toString()) }
        runCatching { answers(app) }.onFailure { fail("respuestas", it.toString()) }
        runCatching { window(app) }.onFailure { fail("ventana", it.toString()) }
        runCatching { app.shutdown() }
        return finish()
    }

    private fun finish(): Int {
        line(if (failures == 0) "RESULTADO: todo OK" else "RESULTADO: $failures fallas")
        File(dir, "selftest.txt").writeText(report.toString())
        return if (failures == 0) 0 else 1
    }

    /** Las mismas frases que en el teléfono, con «Lucy». */
    private fun interpreter() = runBlocking {
        val interpreter = CompositeCommandInterpreter(RuleBasedCommandInterpreter(), memory = InMemoryStore())
        val cases = listOf(
            "Lucy, reproducí música" to CommandIntent.PlayMusic::class,
            "Lucy reproduci musica" to CommandIntent.PlayMusic::class,
            "luci poné música de queen en spotify" to CommandIntent.PlayMusic::class,
            "Lucy, pausá la música" to CommandIntent.Pause::class,
            "Lucy, siguiente canción" to CommandIntent.Next::class,
            "Lucy, subí el volumen" to CommandIntent.AdjustVolume::class,
            "Lucy, abrí la calculadora" to CommandIntent.OpenApp::class,
            "Lucy, poné un temporizador de 5 minutos" to CommandIntent.Skill::class,
            "Lucy, recordame mañana a las 9 comprar pan" to CommandIntent.Skill::class,
            "Lucy, cómo va a estar el clima mañana" to CommandIntent.Skill::class,
            "¿cómo estás Lucy?" to CommandIntent.Chat::class,
            "Lucy, ¿qué hora es?" to CommandIntent.Chat::class,
        )
        for ((text, expected) in cases) {
            val intent = interpreter.interpret(text).intent
            if (expected.isInstance(intent)) ok("entiende «$text»", intent.toString()) else fail("entiende «$text»", "dio $intent")
        }
        if (com.sara.core.memory.SmallTalk.answer("¿cómo estás Lucy?") != null) ok("charla «cómo estás Lucy»") else fail("charla «cómo estás Lucy»", "sin respuesta")
    }

    private fun windows(app: LucyApp) {
        if (app.system.call("ping") == "pong") ok("puente con Windows") else fail("puente con Windows", "no respondió")
        runCatching { app.system.obj("volume") }.fold({ ok("volumen", it.toString()) }, { info("volumen", "sin placa de audio: ${it.message}") })
        runCatching { app.system.obj("mediaInfo") }.fold({ ok("sesiones multimedia", it.toString()) }, { fail("sesiones multimedia", it.toString()) })
        val apps = app.appLauncher.installed()
        if (apps.isNotEmpty()) ok("apps del menú Inicio", "${apps.size}: " + apps.take(8).joinToString { it.label }) else fail("apps del menú Inicio", "vacío")
        runCatching { app.system.obj("battery") }.fold({ ok("batería", it.toString()) }, { fail("batería", it.toString()) })
        runCatching { app.system.obj("info", timeoutMillis = 30_000) }.fold({ ok("info", it.toString()) }, { fail("info", it.toString()) })
        runCatching { app.system.obj("radio", mapOf("kind" to "WiFi", "state" to "query")) }.fold({ ok("radios", it.toString()) }, { info("radios", it.message ?: "") })
        val mic = runBlocking { withTimeoutOrNull(12_000) { var n = 0; app.microphone.capture { _, _ -> ++n < 10 } } }
        info("micrófono", "${app.microphone.mode}: $mic")
        val sealed = app.system.call("protect", mapOf("text" to "clave-de-prueba")) as String
        if (app.system.call("unprotect", mapOf("text" to sealed)) == "clave-de-prueba") ok("cifrado de claves") else fail("cifrado de claves", "no coincide")
    }

    /** Voz: la de Windows suena y el modelo de voz entiende a la voz de Windows diciendo «Lucy». */
    private fun voice(app: LucyApp) {
        val voices = app.system.call("voices") as? JSONArray ?: JSONArray()
        info("voces", (0 until voices.length()).joinToString { voices.getJSONObject(it).let { v -> "${v.optString("name")} (${v.optString("culture")})" } })
        val model = app.models.model()
        if (model == null) { fail("modelo de voz", app.models.error ?: "no está"); return }
        ok("modelo de voz", app.models.folder().toString())
        val spanish = (0 until voices.length()).map { voices.getJSONObject(it) }.firstOrNull { it.optString("culture").startsWith("es") }?.optString("name")
        for (phrase in listOf("Lucy.", "Lucy, reproducí música.")) {
            val wav = File(dir, "oido.wav")
            app.system.call("speakToWav", mapOf("text" to phrase, "path" to wav.absolutePath, "voice" to spanish), timeoutMillis = 30_000)
            val pcm = wav.readBytes().let { it.copyOfRange(44.coerceAtMost(it.size), it.size) }
            val heard = Recognizer(model, Microphone.SAMPLE_RATE.toFloat()).use { r ->
                r.acceptWaveForm(pcm, pcm.size)
                JSONObject(r.finalResult).optString("text")
            }
            val chunks = pcm.toList().chunked(Microphone.CHUNK_BYTES).map { it.toByteArray() }.takeLast(24)
            val woke = app.wake.verify(model, chunks, WakeWordTuning.DEFAULT_SENSITIVITY, requirePrefix = false)
            info("oído «$phrase»", "entendió «$heard»; activación: $woke (voz ${spanish ?: "en inglés: sólo orientativo"})")
            if ('Ã' in heard) fail("acentos de Vosk", "llegan mal: $heard")
        }
        runCatching { app.voiceBridge.call("speak", mapOf("text" to "Hola, soy Lucy.", "rate" to 0, "volume" to 100), timeoutMillis = 30_000) }
            .fold({ ok("hablar", it.toString()) }, { info("hablar", "sin salida de audio: ${it.message}") })
    }

    /** Respuestas reales del mismo ejecutor que en el teléfono (por texto, sin micrófono). */
    private fun answers(app: LucyApp) = runBlocking {
        app.orchestrator.setWakeWordEnabled(false)
        val cases = listOf(
            "¿Cómo estás, Lucy?" to "bien",
            "Lucy, cuánto es 25 por 4" to "100",
            "Lucy, qué podés hacer" to "Música",
            "Lucy, agregá leche a la lista de compras" to "",
        )
        for ((text, expected) in cases) {
            val response = async { withTimeoutOrNull(20_000) { app.orchestrator.events.filterIsInstance<AssistantEvent.Responded>().first() } }
            kotlinx.coroutines.delay(100)
            if (!app.orchestrator.submitText(text)) { fail("responde «$text»", "ocupada"); continue }
            val r = response.await()
            when {
                r == null -> fail("responde «$text»", "sin respuesta")
                !r.success -> fail("responde «$text»", r.text)
                expected.isNotEmpty() && !r.text.contains(expected, ignoreCase = true) -> fail("responde «$text»", r.text)
                else -> ok("responde «$text»", r.text)
            }
            withTimeoutOrNull(30_000) { while (app.orchestrator.isInteractionActive) kotlinx.coroutines.delay(100) }
        }
    }

    private fun window(app: LucyApp) {
        // Para que la esfera flotante quede visible en la captura.
        app.settings.update { it.copy(sphereVisible = true, sphereAlways = true) }
        var w: MainWindow? = null
        SwingUtilities.invokeAndWait {
            w = MainWindow(app)
            w!!.showWindow()
            w!!.sphereWindow.isVisible = true
            w!!.sphereWindow.setCaption("Te escucho…")
        }
        Thread.sleep(2_500)
        val screen = Rectangle(Toolkit.getDefaultToolkit().screenSize)
        ImageIO.write(Robot().createScreenCapture(screen), "png", File(dir, "lucy.png"))
        ok("ventana y esfera", "captura en lucy.png")
        SwingUtilities.invokeAndWait { w?.sphereWindow?.dispose(); w?.dispose() }
    }
}
