package com.sara.core.ai

import com.sara.core.interpreter.TextNormalizer
import com.sara.core.memory.ConversationalAssistant
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Respuestas de saber general («qué es la fotosíntesis», «cómo se creó la calculadora») sacadas
 * del resumen de Wikipedia en español: gratis, sin clave y en un solo pedido (≈0,5 s).
 */
open class Wikipedia(private val logger: SaraLogger? = null) {

    /** Resumen hablado (2 o 3 oraciones) o `null` si no es una pregunta de saber general o no hay artículo. */
    open fun answer(question: String): String? {
        val topic = topicOf(question) ?: return null
        return runCatching { summary(topic) }
            .onFailure { logger?.w(TAG, "Wikipedia: ${it.message}") }
            .getOrNull()
    }

    private fun summary(topic: String): String? {
        val q = URLEncoder.encode(topic, "UTF-8")
        val url = "https://es.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1" +
            "&generator=search&gsrsearch=$q&gsrlimit=1&gsrnamespace=0" +
            "&prop=extracts&exintro=1&explaintext=1&exsentences=3"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 4_000
        conn.setRequestProperty("User-Agent", "SaraAssistant/1.0 (asistente de voz)")
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val page = JSONObject(body).optJSONObject("query")?.optJSONArray("pages")?.optJSONObject(0) ?: return null
            return clean(page.optString("extract"))
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "Wikipedia"
        private const val MAX_CHARS = 420

        private val START = Regex(
            "^(?:(?:sabes|sabe|me podes decir|me puedes decir|decime|dime|contame|cuentame|explicame|explica)\\s+)?" +
                "(?:que es|que son|que era|que fue|que fueron|que significa|quien es|quien fue|quienes son|quienes fueron|" +
                "como se creo|como se crearon|como se invento|como se inventaron|como surgio|como surgieron|como nacio|como funciona|como funcionan|" +
                "quien invento|quien creo|quien descubrio|quien escribio|quien pinto|quien compuso|cuando se invento|cuando se creo|cuando fue|cuando nacio|" +
                "donde queda|donde esta|de donde viene|de donde es|por que se llama|cual es el origen de|cual es la historia de|" +
                "la historia de|historia de|origen de|definicion de|sobre|acerca de|que sabes de|que sabes sobre|hablame de|hablame sobre)\\s+(.+)$",
        )
        /** Cosas que cambian (o personales): las contesta la IA con búsqueda, no una enciclopedia. */
        private val SKIP = setOf(
            "hoy", "ahora", "manana", "ayer", "hora", "clima", "tiempo", "temperatura", "precio", "dolar", "cotizacion",
            "partido", "resultado", "noticias", "me", "mi", "mis", "te", "tu", "vos", "sara", "lucy", "yo", "nosotros",
        )
        private val ARTICLES = Regex("^(?:(?:el|la|los|las|un|una|unos|unas|lo)\\s+)+")

        /** «Sara, ¿qué es la fotosíntesis?» → «fotosíntesis»; `null` si no es de saber general. */
        fun topicOf(question: String): String? {
            val n = TextNormalizer.normalize(question)
            val m = START.find(n) ?: return null
            val words = TextNormalizer.tokens(n)
            if (words.any { it in SKIP }) return null
            val normalizedTopic = m.groupValues[1].replace(ARTICLES, "").trim()
            if (normalizedTopic.isBlank() || normalizedTopic.split(' ').size > 7) return null
            // Se busca con las tildes originales (Wikipedia las aprovecha), si se pueden recuperar.
            val original = question.trim().trimEnd('?', '!', '.').split(Regex("\\s+"))
            val count = normalizedTopic.split(' ').size
            val tail = original.takeLast(count).joinToString(" ").trim('¿', '¡', ',', ' ')
            return if (TextNormalizer.normalize(tail) == normalizedTopic) tail else normalizedTopic
        }

        /** Saca paréntesis (pronunciación, fechas en otros idiomas) y corta en oraciones enteras. */
        fun clean(extract: String): String? {
            var text = extract.replace(Regex("\\s*\\([^()]*\\)"), "").replace(Regex("\\s*\\[[^\\]]*\\]"), "")
                .replace(Regex("\\s+"), " ").replace(" ,", ",").trim()
            if (text.length < 20 || text.contains("puede referirse a")) return null
            if (text.length > MAX_CHARS) {
                val cut = text.lastIndexOf(". ", MAX_CHARS)
                text = if (cut > 60) text.substring(0, cut + 1) else text.substring(0, MAX_CHARS).substringBeforeLast(' ') + "…"
            }
            return text
        }
    }
}

/**
 * Preguntas: la IA (con búsqueda) y Wikipedia corren a la vez. Si la IA contesta rápido, se usa la
 * IA; si tarda, o falla («no pude responder ahora»), se dice el resumen de Wikipedia enseguida.
 */
class FastKnowledgeAssistant(
    private val ai: ConversationalAssistant,
    private val wikipedia: Wikipedia,
    private val aiPatienceMillis: Long = 2_500,
) : ConversationalAssistant {
    override fun isAvailable(): Boolean = ai.isAvailable()
    override fun canAnswer(text: String): Boolean = ai.isAvailable() || Wikipedia.topicOf(text) != null

    /** Los pedidos de red no se pueden cortar: corren aparte para no esperar al que pierde. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun reply(text: String): String {
        if (Wikipedia.topicOf(text) == null) return ai.reply(text)
        val wiki = background.async { wikipedia.answer(text) }
        if (!ai.isAvailable()) return wiki.await() ?: "No encontré nada sobre eso. Para más respuestas activá la IA en Ajustes."
        val answer = background.async { ai.reply(text) }
        val quick = withTimeoutOrNull(aiPatienceMillis) { answer.await() }
        if (quick != null && !isFailure(quick)) return quick
        return wiki.await() ?: answer.await()
    }

    companion object {
        private val FAILURES = listOf(
            "No pude responder", "Google tardó", "No tengo conexión", "Llegué al límite", "La clave", "Tu clave",
            "No encontré una respuesta", "Para eso activá", "Claude está saturado",
        )
        fun isFailure(reply: String) = FAILURES.any { reply.startsWith(it) }
    }
}
