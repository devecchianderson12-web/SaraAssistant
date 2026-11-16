package com.sara.core.interpreter

import com.sara.core.model.CommandIntent
import com.sara.core.model.InterpretationResult
import com.sara.core.model.VolumeChange

interface CommandInterpreter {
    suspend fun interpret(text: String): InterpretationResult
}

/**
 * Punto de extensión para un modelo conversacional futuro. Sólo se consulta cuando las reglas
 * locales no reconocen la orden, de modo que las órdenes simples (volumen, música) nunca generan
 * llamadas a IA.
 */
class CompositeCommandInterpreter(
    private val rules: CommandInterpreter,
    private val conversational: CommandInterpreter? = null,
) : CommandInterpreter {
    override suspend fun interpret(text: String): InterpretationResult {
        val local = rules.interpret(text)
        val ai = conversational
        if (local.intent !is CommandIntent.Unknown || ai == null) return local
        val remote = runCatching { ai.interpret(text) }.getOrNull() ?: return local
        return if (remote.intent is CommandIntent.Unknown) local else remote
    }
}

/** Intérprete local por reglas: intenciones, entidades (porcentaje, búsqueda) y expresiones equivalentes. */
class RuleBasedCommandInterpreter : CommandInterpreter {

    override suspend fun interpret(text: String): InterpretationResult = interpretNow(text)

    fun interpretNow(text: String): InterpretationResult {
        val normalized = TextNormalizer.normalize(text)
        val tokens = TextNormalizer.tokens(normalized)
        if (tokens.isEmpty()) return InterpretationResult(CommandIntent.Unknown(text), 0f, normalized, "empty")
        val ctx = Context(normalized, tokens)
        return detectCancel(ctx)
            ?: detectVolume(ctx)
            ?: detectMedia(ctx)
            ?: InterpretationResult(CommandIntent.Unknown(text), 0f, normalized, "no-match")
    }

    // -----------------------------------------------------------------------------------------

    private class Context(val text: String, val tokens: List<String>) {
        private val padded = " $text "
        fun any(regex: Regex) = tokens.any { regex.matches(it) }
        fun phrase(vararg phrases: String) = phrases.any { padded.contains(" $it ") }
        fun result(intent: CommandIntent, confidence: Float, rule: String) =
            InterpretationResult(intent, confidence, text, rule)
    }

    private data class Amount(val value: Int, val preposition: String?, val relativeMarker: Boolean, val absoluteMarker: Boolean)

    private fun findAmount(ctx: Context): Amount? {
        val tokens = ctx.tokens
        val mitad = tokens.indexOf("mitad")
        if (mitad >= 0) return Amount(50, tokens.getOrNull(mitad - 2), relativeMarker = false, absoluteMarker = true)
        val match = SpanishNumbers.findAll(tokens).firstOrNull() ?: return null
        val previous = tokens.getOrNull(match.start - 1)
        var afterIndex = match.endExclusive
        if (tokens.getOrNull(afterIndex) == "por" && tokens.getOrNull(afterIndex + 1) == "ciento") afterIndex += 2
        if (tokens.getOrNull(afterIndex) in setOf("puntos", "punto")) afterIndex += 1
        val next = tokens.getOrNull(afterIndex)
        val relative = previous in setOf("un", "una") || next in setOf("mas", "menos")
        val absolute = previous in setOf("al", "a", "hasta")
        return Amount(match.value, previous, relative, absolute)
    }

    private fun detectCancel(ctx: Context): InterpretationResult? {
        if (ctx.tokens.size > 3 || SpanishNumbers.findAll(ctx.tokens).isNotEmpty()) return null
        val cancel = ctx.any(CANCEL) || ctx.text in setOf("dejalo", "deja", "no importa", "nada mas", "listo gracias", "gracias")
        return if (cancel) ctx.result(CommandIntent.Cancel, 0.9f, "cancel") else null
    }

    private fun detectVolume(ctx: Context): InterpretationResult? {
        if (ctx.any(UNMUTE_TOKENS) || ctx.phrase(*UNMUTE_PHRASES)) {
            return ctx.result(CommandIntent.AdjustVolume(VolumeChange.Unmute), 0.9f, "volume-unmute")
        }
        if (ctx.any(MUTE) || ctx.phrase("sin sonido", "sin volumen", "pone silencio", "pone en silencio")) {
            return ctx.result(CommandIntent.AdjustVolume(VolumeChange.Mute), 0.9f, "volume-mute")
        }
        val up = ctx.any(UP) || ctx.phrase(*UP_PHRASES)
        val down = ctx.any(DOWN) || ctx.phrase(*DOWN_PHRASES)
        val set = ctx.any(SET)
        val volumeNoun = ctx.any(VOLUME_NOUN)
        val amount = findAmount(ctx)
        val isMax = ctx.any(MAX_TOKENS) || ctx.phrase(*MAX_PHRASES)
        val isMin = ctx.any(MIN_TOKENS)

        val volumeContext = volumeNoun || up || down || isMax || (set && (amount != null || isMin))
        if (!volumeContext) return null

        val change: VolumeChange? = when {
            isMax && !down -> VolumeChange.Max
            isMin -> VolumeChange.SetTo(0)
            amount != null -> {
                val relative = when {
                    amount.relativeMarker -> true
                    amount.absoluteMarker -> false
                    else -> up || down
                }
                val value = amount.value.coerceIn(0, 100)
                when {
                    !relative -> VolumeChange.SetTo(value)
                    down -> VolumeChange.Decrease(value)
                    up -> VolumeChange.Increase(value)
                    ctx.phrase("menos") -> VolumeChange.Decrease(value)
                    else -> VolumeChange.Increase(value)
                }
            }
            up && !down -> VolumeChange.Increase(stepWord(ctx))
            down && !up -> VolumeChange.Decrease(stepWord(ctx))
            else -> null
        }
        return if (change != null) {
            ctx.result(CommandIntent.AdjustVolume(change), if (amount != null || volumeNoun) 0.95f else 0.8f, "volume")
        } else {
            ctx.result(CommandIntent.Unknown(ctx.text), 0.3f, "volume-incomplete")
        }
    }

    private fun stepWord(ctx: Context): Int? = when {
        ctx.phrase("un poco", "un toque", "un poquito", "un cachito", "apenas", "un pelin", "un poquitito") -> 5
        ctx.phrase("mucho", "bastante", "un monton", "un monto") -> 25
        else -> null
    }

    private fun detectMedia(ctx: Context): InterpretationResult? {
        if (ctx.any(PREVIOUS) || ctx.phrase(*PREVIOUS_PHRASES)) return ctx.result(CommandIntent.Previous, 0.9f, "previous")
        if (ctx.any(NEXT) || ctx.phrase(*NEXT_PHRASES)) return ctx.result(CommandIntent.Next, 0.9f, "next")
        if (ctx.any(PAUSE)) return ctx.result(CommandIntent.Pause, 0.9f, "pause")
        val stopByPara = ctx.tokens.first() in setOf("para", "pare") || ctx.phrase("para la musica", "para la cancion", "para el tema", "para todo")
        if (ctx.any(STOP) || stopByPara || ctx.phrase(*STOP_PHRASES)) return ctx.result(CommandIntent.StopMusic, 0.9f, "stop")
        if (ctx.any(RESUME) || ctx.phrase(*RESUME_PHRASES)) return ctx.result(CommandIntent.Resume, 0.85f, "resume")

        val musicNoun = ctx.any(MUSIC_NOUN)
        val playVerb = ctx.any(PLAY_VERB)
        if (ctx.phrase(*PLAY_PHRASES) || (playVerb && (musicNoun || ctx.phrase("algo")))) {
            return ctx.result(CommandIntent.PlayMusic(extractQuery(ctx.text)), 0.9f, "play")
        }
        if (musicNoun) return ctx.result(CommandIntent.PlayMusic(extractQuery(ctx.text)), 0.6f, "play-noun")
        return null
    }

    private fun extractQuery(text: String): String? {
        val match = QUERY.find(text) ?: return null
        val query = match.groupValues[1].trim()
        if (query.isEmpty() || query.split(' ').first() in QUERY_STOPWORDS) return null
        return query
    }

    private companion object {
        val CANCEL = Regex("cancel[a-z]*|nada|olvidalo|olvidate|ninguna|nadie|chau")
        val UNMUTE_TOKENS = Regex("desmute[a-z]*|desilenci[a-z]*")
        val UNMUTE_PHRASES = arrayOf(
            "quita el silencio", "quitale el silencio", "saca el silencio", "sacale el silencio",
            "desactiva el silencio", "activa el sonido", "volve a activar el sonido", "devolve el sonido",
            "prende el sonido", "activa el volumen",
        )
        val MUTE = Regex("silenci[a-z]*|mute[a-z]*|enmudec[a-z]*|callala|callalo|calla")
        val UP = Regex("sub[ei][a-z]*|subir[a-z]*|aument[a-z]*|levant[a-z]*|increment[a-z]*|alz[a-z]*|subamos")
        val UP_PHRASES = arrayOf("mas fuerte", "mas alto", "mas volumen", "que suene mas", "mas fuertecito")
        val DOWN = Regex("baj[a-z]*|disminu[a-z]*|reduc[a-z]*|achic[a-z]*")
        val DOWN_PHRASES = arrayOf("mas despacio", "mas suave", "menos fuerte", "menos volumen", "mas bajito")
        val SET = Regex("pon|pone[a-z]*|poner[a-z]*|ponlo|ponla|ponme|dej[a-z]*|configur[a-z]*|ajust[a-z]*|fij[a-z]*|establec[a-z]*|cambi[a-z]*|lleva[a-z]*")
        val VOLUME_NOUN = Regex("volumen|sonido|audio|vol")
        val MAX_TOKENS = Regex("maximo|maxima|max")
        val MAX_PHRASES = arrayOf("a full", "al palo", "a todo volumen", "a todo lo que da", "todo el volumen", "al tope")
        val MIN_TOKENS = Regex("minimo|minima")

        val NEXT = Regex("siguiente|proxima|proximo|adelant[a-z]*|salt[ae][a-z]*|saltea[a-z]*|skip|skipea[a-z]*|pasala|pasalo|pasame")
        val NEXT_PHRASES = arrayOf(
            "pasa de tema", "pasa de cancion", "pasa el tema", "pasa la cancion", "pasa a la otra",
            "cambia de tema", "cambia el tema", "cambia de cancion", "cambia la cancion", "otra cancion", "otro tema",
        )
        val PREVIOUS = Regex("anterior|previa|previo|retroced[a-z]*")
        val PREVIOUS_PHRASES = arrayOf("la de antes", "el de antes", "tema de antes", "cancion de antes", "para atras", "volve atras", "volver atras")
        val PAUSE = Regex("paus[a-z]*|fren[a-z]*")
        val STOP = Regex("deten[a-z]*|stop|parala|paralo|apag[a-z]*|cort[a-z]*|termin[a-z]*")
        val STOP_PHRASES = arrayOf("quita la musica", "saca la musica", "basta de musica", "no quiero mas musica")
        val RESUME = Regex("continu[a-z]*|segui[a-z]*|sigue|sigan|reanud[a-z]*|despaus[a-z]*|retom[a-z]*|play")
        val RESUME_PHRASES = arrayOf("que siga", "volve a reproducir", "dale play", "dale que siga")
        val MUSIC_NOUN = Regex("musica|musiquita|cancion|canciones|cancioncita|tema|temas|temazo|temita|playlist|album|disco|radio")
        val PLAY_VERB = Regex("pon|pone[a-z]*|poner[a-z]*|ponme|reproduc[a-z]*|toc[a-z]*|escuch[a-z]*|arranc[a-z]*|empez[a-z]*|inici[a-z]*|met[ea][a-z]*|tira[a-z]*|larg[a-z]*|mand[a-z]*|quiero")
        val PLAY_PHRASES = arrayOf(
            "quiero escuchar", "me gustaria escuchar", "tengo ganas de escuchar", "quisiera escuchar",
            "quiero musica", "algo de musica", "un poco de musica",
        )
        val QUERY = Regex("(?:musica|canciones|cancion|temas|tema|algo|album|disco) de (.+)$")
        val QUERY_STOPWORDS = setOf("musica", "fondo", "nuevo", "antes", "volumen")
    }
}
