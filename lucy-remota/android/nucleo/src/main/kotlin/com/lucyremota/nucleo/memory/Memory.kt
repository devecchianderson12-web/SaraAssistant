package com.lucyremota.nucleo.memory

import com.lucyremota.nucleo.model.AssistantIdentity

import com.lucyremota.nucleo.interpreter.TextNormalizer
import com.lucyremota.nucleo.model.CommandIntent
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Lo que Sara recuerda del usuario (preferencias, rutinas, datos). Persistente en la app. */
interface MemoryStore {
    fun all(): Map<String, String>
    fun get(key: String): String? = all()[key]
    fun put(key: String, value: String)
    fun remove(key: String): Boolean
}

class InMemoryStore : MemoryStore {
    private val map = linkedMapOf<String, String>()
    @Synchronized override fun all(): Map<String, String> = map.toMap()
    @Synchronized override fun put(key: String, value: String) { map[key] = value }
    @Synchronized override fun remove(key: String): Boolean = map.remove(key) != null
}

object MemoryKeys {
    /** App para «poné música». Sin este dato se usa la app de siempre (la más usada o la del sistema). */
    const val MUSIC_APP = "app_musica"
    /** Artistas que más escucha (lo aprende solo de las apps de música). */
    const val MUSIC_TASTE = "gustos musicales"
    /** «rutina: buenas noches» -> «sacá la música y cerrá las apps». */
    const val ROUTINE_PREFIX = "rutina: "
    const val NOTE_PREFIX = "dato: "

    fun routineKey(trigger: String) = ROUTINE_PREFIX + TextNormalizer.normalize(trigger)

    /** Cómo se lee en voz alta un recuerdo. */
    fun describe(key: String, value: String): String = when {
        key == MUSIC_APP -> "la música la pongo en $value"
        key == MUSIC_TASTE -> "lo que más escuchás es $value"
        key.startsWith(ROUTINE_PREFIX) -> "cuando decís «${key.removePrefix(ROUTINE_PREFIX)}», $value"
        key.startsWith(NOTE_PREFIX) -> value
        else -> "$key: $value"
    }
}

/**
 * Órdenes para enseñarle cosas a Sara, sin IA:
 * «cada vez que te pida música abrí BitChord», «usá Spotify para la música», «olvidate de la app de
 * música», «cuando te diga buenas noches, sacá la música», «acordate que …», «¿qué recordás?».
 */
object MemoryParser {
    private val LEAD = Regex("^\\s*(?:(?:${AssistantIdentity.NAMES})\\s*[,.]?\\s+)?")
    private val WHEN = Regex("\\b(?:cada vez|cada que|siempre|cuando|de ahora en m[aá]s|a partir de ahora|desde ahora)\\b")
    private val MUSIC = Regex("\\b(?:m[uú]sica|canci[oó]n|canciones|temas)\\b")
    private val APP_AFTER = Regex(
        "\\b(?:abr[ií]s?|abras|abre|abrir|ejecut[aáe]s?|ejecutar|us[aáe]s?|usar|utiliz[aáe]s?|pon[eé]s?|pongas|entr[aáe]s?)\\s+" +
            "(?:en\\s+|con\\s+|a\\s+)?(?:la\\s+|el\\s+)?(?:aplicaci[oó]n|app)?\\s*(?:de\\s+|que\\s+se\\s+llama\\s+)?(.+)$",
    )
    private val APP_BEFORE = Regex(
        "^(?:(?:siempre|de ahora en m[aá]s|a partir de ahora)\\s*,?\\s+)?(?:us[aá]|abr[ií]|pon[eé])\\s+(?:la\\s+app\\s+(?:de\\s+)?)?(.+?)\\s+(?:para|con)\\s+(?:la\\s+|las\\s+|los\\s+)?(?:m[uú]sica|canciones|temas)",
    )
    private val TRIGGER_START = "^(?:cada\\s+vez\\s+que|cada\\s+que|siempre\\s+que|cuando|si)\\s+(?:yo\\s+)?(?:te\\s+)?(?:diga|digo)\\s+(?:que\\s+)?"
    /** «Cuando te diga X, hacé Y», «cada que te diga "X" quiero que Y», «si te digo X tenés que Y». */
    private val ROUTINE = Regex(
        TRIGGER_START + "[\"«“]?(.+?)[\"»”]?\\s*,?\\s*(?:hac[eé]|haz|que\\s+hagas|quiero\\s+que|necesito\\s+que|ten[eé]s\\s+que|tienes\\s+que|deb[eé]s|vos)\\s+(.+)$",
    )
    /** Con la frase entre comillas no hace falta «quiero que»: «cada vez que te diga "buenas noches" sacá la música». */
    private val ROUTINE_QUOTED = Regex(TRIGGER_START + "[\"«“](.+?)[\"»”]\\s*,?\\s*(.+)$")

    /** «te conectes a…» → «conectate a…», «me pongas…» → «poneme…» (lo que se va a ejecutar es una orden). */
    private val SUBJUNCTIVE = mapOf(
        "conectes" to "conecta", "desconectes" to "desconecta", "pongas" to "pone", "abras" to "abri", "cierres" to "cerra", "subas" to "subi",
        "bajes" to "baja", "prendas" to "prende", "apagues" to "apaga", "pases" to "pasa", "saques" to "saca", "mandes" to "manda", "llames" to "llama",
        "digas" to "deci", "leas" to "lee", "avises" to "avisa", "busques" to "busca", "reproduzcas" to "reproduci", "actives" to "activa",
        "desactives" to "desactiva", "silencies" to "silencia", "pauses" to "pausa", "sigas" to "segui", "cambies" to "cambia", "pares" to "para",
        "recuerdes" to "recorda", "anotes" to "anota", "agregues" to "agrega", "hagas" to "hace", "vayas" to "anda",
    )
    private val ACCENTED = mapOf('a' to 'á', 'e' to 'é', 'i' to 'í')

    internal fun toOrder(action: String): String {
        val m = Regex("^(?:(te|me|lo|la|le)\\s+)?(\\p{L}+)(.*)$", RegexOption.IGNORE_CASE).find(action.trim()) ?: return action
        val (clitic, verb, rest) = m.destructured
        val stem = SUBJUNCTIVE[verb.lowercase()] ?: return action
        val head = if (clitic.isNotEmpty()) stem + clitic.lowercase() else stem.dropLast(1) + ACCENTED.getValue(stem.last())
        return head + rest
    }
    private val NOTE = Regex("^(?:acordate|record[aá]|record[aá]te|anot[aá]|guard[aá])\\s+(?:de\\s+)?que\\s+(.+)$")
    private val FORGET_MUSIC = Regex("(?:olvid|borr|sac|quit)[a-záéíóú]*.*(?:app|aplicaci[oó]n).*m[uú]sica|m[uú]sica.*(?:por defecto|predeterminad|de siempre)")
    private val CHANGE_MUSIC = Regex("^cambi[aá]\\s+(?:la\\s+)?(?:app|aplicaci[oó]n)\\s+(?:de\\s+(?:la\\s+)?)?m[uú]sica\\s+(?:a|por)\\s+(?:la\\s+)?(?:app\\s+(?:de\\s+)?)?(.+)$")
    private val FORGET = Regex("^(?:olvid[aá]te|olvid[aá])\\s+(?:de\\s+)?(?:lo\\s+de\\s+|la\\s+rutina\\s+(?:de\\s+)?)?[\"«“]?(.+?)[\"»”]?$")
    private val RECALL = Regex("^(?:qu[eé]|que cosas)\\s+(?:record[aá]s|te acord[aá]s|sab[eé]s de m[ií]|ten[eé]s guardado|aprendiste)")

    fun parse(text: String): CommandIntent? {
        val lower = text.trim().lowercase().trimEnd('.', '!', '?')
        val body = lower.substring(LEAD.find(lower)?.value?.length ?: 0).trimStart('¿', '¡')

        if (RECALL.containsMatchIn(body)) return CommandIntent.RecallMemory
        (ROUTINE.find(body) ?: ROUTINE_QUOTED.find(body))?.let { m ->
            return CommandIntent.Remember(MemoryKeys.routineKey(clean(m.groupValues[1])), toOrder(clean(m.groupValues[2])))
        }
        if (FORGET_MUSIC.containsMatchIn(body) && !WHEN.containsMatchIn(body)) return CommandIntent.Forget(MemoryKeys.MUSIC_APP)

        CHANGE_MUSIC.find(body)?.let { m ->
            val app = cleanApp(m.groupValues[1])
            if (app.isNotEmpty()) return CommandIntent.Remember(MemoryKeys.MUSIC_APP, app)
        }
        val music = MUSIC.find(body)
        if (music != null && WHEN.containsMatchIn(body)) {
            APP_AFTER.find(body.substring(music.range.last + 1))?.let { m ->
                val app = cleanApp(m.groupValues[1])
                if (app.isNotEmpty()) return CommandIntent.Remember(MemoryKeys.MUSIC_APP, app)
            }
        }
        APP_BEFORE.find(body)?.let { m ->
            val app = cleanApp(m.groupValues[1])
            if (app.isNotEmpty()) return CommandIntent.Remember(MemoryKeys.MUSIC_APP, app)
        }
        NOTE.find(body)?.let { m ->
            val fact = clean(m.groupValues[1])
            val key = MemoryKeys.NOTE_PREFIX + TextNormalizer.tokens(TextNormalizer.normalize(fact)).take(4).joinToString(" ")
            return CommandIntent.Remember(key, fact)
        }
        FORGET.find(body)?.let { m -> return CommandIntent.Forget(m.groupValues[1].trim()) }
        return null
    }

    private fun clean(text: String) = text.trim().trim('"', '«', '»', '“', '”', ',', '.', ' ')

    private fun cleanApp(text: String): String {
        var app = clean(text).replace(Regex("\\s+(?:por favor|porfa|gracias)$"), "")
        app = app.replace(Regex("\\s+(?:para|cuando|cada|siempre)\\b.*$"), "").trim()
        return app.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}

/** Charla básica que Sara contesta sin Internet ni IA. */
object SmallTalk {
    fun answer(text: String, now: LocalDateTime = LocalDateTime.now()): String? {
        val t = " " + TextNormalizer.normalize(text) + " "
        fun has(vararg p: String) = p.any { t.contains(" $it ") }
        return when {
            has("que hora es", "que hora son", "hora es", "me decis la hora", "decime la hora", "dime la hora") ->
                spokenTime(now.hour, now.minute)
            has("que dia es", "que dia es hoy", "que fecha es", "fecha de hoy", "que dia estamos") -> {
                val day = now.dayOfWeek.getDisplayName(TextStyle.FULL, LOCALE)
                val month = now.month.getDisplayName(TextStyle.FULL, LOCALE)
                "Hoy es $day ${now.dayOfMonth} de $month."
            }
            has("como estas", "como andas", "como te va", "que tal", "todo bien", "como te encuentras") -> "Muy bien, ¿y vos?"
            has(
                "que podes hacer", "que sabes hacer", "que haces", "en que me ayudas", "que podes hacer por mi",
                "que puedes hacer", "que sabes", "que haces tu", "en que me puedes ayudar", "en que me podes ayudar", "para que servis", "para que sirves",
            ) -> AssistantIdentity.capabilities
            has("que te gusta", "te gusta") -> "Me encanta la música."
            has("quien sos", "quien eres", "como te llamas", "cual es tu nombre") -> "Soy ${AssistantIdentity.name}, tu asistente."
            has("te quiero", "sos genial", "sos la mejor") -> "¡Gracias! Yo también."
            else -> null
        }
    }

    private val LOCALE: Locale = Locale.forLanguageTag("es-AR")

    /** La hora como se dice («Son las 9 y cuarto», «Es la 1 y 5», «Son las 21 en punto»), sin «9:05». */
    fun spokenTime(hour: Int, minute: Int): String {
        val start = if (hour == 1 || hour == 13) "Es la" else "Son las"
        val min = when (minute) {
            0 -> " en punto"
            15 -> " y cuarto"
            30 -> " y media"
            else -> " y $minute"
        }
        return "$start $hour$min."
    }
}

/** IA conversacional (Claude): responde preguntas, recuerda cosas y puede ejecutar órdenes. */
interface ConversationalAssistant {
    fun isAvailable(): Boolean
    /** Puede contestar esta pregunta (aunque no haya IA, p. ej. con Wikipedia). */
    fun canAnswer(text: String): Boolean = isAvailable()
    /** Respuesta corta para decir en voz alta. Lanza excepción si falla la red o la clave. */
    suspend fun reply(text: String): String
}
