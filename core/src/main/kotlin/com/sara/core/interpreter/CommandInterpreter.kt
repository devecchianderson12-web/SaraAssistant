package com.sara.core.interpreter

import com.sara.core.memory.MemoryKeys
import com.sara.core.memory.MemoryParser
import com.sara.core.memory.MemoryStore
import com.sara.core.memory.SmallTalk
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
    /** Rutinas guardadas («cuando te diga buenas noches, …»). */
    private val memory: MemoryStore? = null,
    /** Si hay IA, lo que las reglas no entienden se le pregunta a ella. */
    private val aiAvailable: () -> Boolean = { false },
    /** Si hay planificador (IA con habilidades), los pedidos nuevos o complejos van a él. */
    private val plannerAvailable: () -> Boolean = { false },
) : CommandInterpreter {
    override suspend fun interpret(spoken: String): InterpretationResult {
        val text = SpokenForms.rewrite(spoken)
        val routine = memory?.get(MemoryKeys.routineKey(text.trim().replace(Regex("^(?i)(?:${com.sara.core.model.AssistantIdentity.NAMES})\\b"), "").trim(' ', ',')))
        // Una rutina puede tener varias órdenes («sacá la música y cerrá las apps»).
        if (routine != null) return (splitCompound(routine) ?: rules.interpret(routine)).copy(ruleId = "routine")
        splitCompound(text)?.let { return it }
        val local = rules.interpret(text)
        if (local.intent is CommandIntent.Unknown) {
            val question = looksLikeQuestion(local.normalizedText)
            when {
                // Preguntas de información: respuesta directa (rápida) con búsqueda.
                question && aiAvailable() -> return InterpretationResult(CommandIntent.Chat(text), 0.7f, local.normalizedText, "ai-chat")
                // Pedidos nuevos o con varios pasos: el planificador combina habilidades.
                plannerAvailable() -> return InterpretationResult(CommandIntent.Plan(text), 0.6f, local.normalizedText, "planner")
                // Sin IA: Sara explica cómo activarla en vez de «no te entendí».
                aiAvailable() || question -> return InterpretationResult(CommandIntent.Chat(text), 0.7f, local.normalizedText, "ai-chat")
            }
        }
        val ai = conversational
        if (local.intent !is CommandIntent.Unknown || ai == null) return local
        val remote = runCatching { ai.interpret(text) }.getOrNull() ?: return local
        return if (remote.intent is CommandIntent.Unknown) local else remote
    }
}

/** Órdenes que se pueden encadenar (no preguntas ni charla). */
private fun CommandIntent.isAction() = this !is CommandIntent.Unknown && this !is CommandIntent.Chat && this !is CommandIntent.Plan &&
    this !is CommandIntent.Cancel && this !is CommandIntent.Sequence

private val CONNECTOR = Regex(
    "\\s*(?:,\\s*)?\\b(?:y\\s+(?:despues|después|luego|tambien|también|ademas|además)|y|despues|después|luego|ademas|además)\\b\\s*,?\\s*|\\s*[,;]\\s+",
    RegexOption.IGNORE_CASE,
)

/**
 * «Poné música y bajá el volumen al 30», «cerrá las apps, después apagá la linterna»: si cada parte
 * es una orden conocida, se ejecutan en orden. Los mensajes y recuerdos se analizan enteros (su texto
 * puede tener «y»).
 */
private suspend fun CompositeCommandInterpreter.splitCompound(text: String): InterpretationResult? = splitCompoundWith(text)

internal fun splitCompoundWith(text: String): InterpretationResult? {
    if (MessageParser.parse(text) != null || MemoryParser.parse(text) != null) return null
    // «Sara, conectate al Bluetooth y …»: el nombre del principio no es parte de la primera orden.
    val body = text.replace(Regex("^\\s*(?:${com.sara.core.model.AssistantIdentity.NAMES})\\s*[,.]?\\s+", RegexOption.IGNORE_CASE), "")
    val parts = body.split(CONNECTOR).map { it.trim() }.filter { it.isNotBlank() }
    if (parts.size < 2 || parts.size > 5) return null
    val rules = RuleBasedCommandInterpreter()
    var steps = parts.map { rules.interpretNow(it).intent }
    if (!steps.all { it.isAction() }) return null
    // «Conectate al Bluetooth y conectate al Gol G3»: el «conectate» sin nombre sobra si después se nombra uno.
    fun btConnect(s: CommandIntent, named: Boolean) = s is CommandIntent.Skill && s.skill == "dispositivo" && s.action == "bluetooth" &&
        s.args["estado"] == "conectar" && (s.args["dispositivo"] != null) == named
    if (steps.any { btConnect(it, named = true) }) steps = steps.filterNot { btConnect(it, named = false) }
    // «Sacá la música, pará y cerrá las apps»: la misma orden dos veces seguidas cuenta una vez.
    val distinct = steps.filterIndexed { i, s -> i == 0 || s != steps[i - 1] }
    if (distinct.size == 1) return InterpretationResult(distinct[0], 0.85f, TextNormalizer.normalize(text), "sequence-single")
    return InterpretationResult(CommandIntent.Sequence(distinct), 0.85f, TextNormalizer.normalize(text), "sequence")
}

private val WAKE_NAMES = com.sara.core.model.AssistantIdentity.NAMES.split('|').toSet()

private val QUESTION_START = setOf(
    "que", "como", "cual", "cuales", "cuando", "donde", "quien", "quienes", "cuanto", "cuanta", "cuantos", "cuantas", "por", "sabes", "sabias",
)
private val QUESTION_WORDS = setOf("clima", "tiempo", "temperatura", "llueve", "lluvia", "noticias", "partido", "dolar", "pronostico")

/** Pregunta (no orden): «¿cómo está el clima?», «¿quién ganó?», «¿cuánto está el dólar?». */
fun looksLikeQuestion(normalized: String): Boolean {
    val tokens = normalized.split(' ').filter { it.isNotBlank() }.dropWhile { it == "y" || it in WAKE_NAMES }
    if (tokens.isEmpty()) return false
    return tokens.first() in QUESTION_START || tokens.any { it in QUESTION_WORDS }
}

/** Pedidos amables que igual son órdenes: «¿podés poner música?», «¿me subís el volumen?». */
private val POLITE_START = setOf("podes", "puedes", "podrias", "podria", "puede", "pueden", "me", "queres", "quieres", "quisieras", "necesito", "quiero")

/** Palabras que hacen que no sea «qué está sonando» (gustos, la app de música, recomendaciones). */
private val NOT_NOW_PLAYING = setOf("gusta", "gustan", "gustos", "app", "aplicacion", "recomenda", "recomendame", "recomiendas", "favorita", "favorito", "pone", "poneme", "reproduci", "busca")

/**
 * Pregunta y no orden: lo marca la entonación (el reconocedor pone «¿…?») o que empiece con «qué»,
 * «cómo», «cuál»… Los pedidos amables («¿podés subir el volumen?») siguen siendo órdenes.
 */
internal fun isQuestion(raw: String, tokens: List<String>): Boolean {
    val words = tokens.dropWhile { it == "y" || it == "ey" || it == "hey" || it == "oye" || it in WAKE_NAMES }
    if (words.isEmpty() || words.first() in POLITE_START) return false
    val marked = '¿' in raw || raw.trimEnd().endsWith("?")
    return marked || words.first() in QUESTION_START
}

/** Intenciones que cambian algo (música, volumen, apps): una pregunta no las debe disparar. */
private fun CommandIntent.changesSomething(): Boolean = when (this) {
    is CommandIntent.PlayMusic, CommandIntent.Pause, CommandIntent.Resume, CommandIntent.Next, CommandIntent.Previous,
    CommandIntent.StopMusic, is CommandIntent.AdjustVolume, is CommandIntent.OpenApp, CommandIntent.CloseBackgroundApps -> true
    else -> false
}

/** Intérprete local por reglas: intenciones, entidades (porcentaje, búsqueda) y expresiones equivalentes. */
class RuleBasedCommandInterpreter : CommandInterpreter {

    override suspend fun interpret(text: String): InterpretationResult = interpretNow(text)

    fun interpretNow(spoken: String): InterpretationResult {
        val text = SpokenForms.rewrite(spoken)
        val normalized = TextNormalizer.normalize(text)
        val tokens = TextNormalizer.tokens(normalized)
        if (tokens.isEmpty()) return InterpretationResult(CommandIntent.Unknown(text), 0f, normalized, "empty")
        val ctx = Context(normalized, tokens)
        MemoryParser.parse(text)?.let { return InterpretationResult(it, 0.9f, normalized, "memory") }
        MessageParser.parse(text)?.let { return InterpretationResult(it, 0.9f, normalized, "send-message") }
        if (isNowPlayingQuestion(tokens)) return ctx.result(CommandIntent.NowPlaying, 0.9f, "now-playing")
        val result = detectCancel(ctx)
            ?: SmallTalk.answer(text)?.let { ctx.result(CommandIntent.Chat(text), 0.8f, "smalltalk") }
            ?: com.sara.core.skills.SkillParser.parse(text)?.let { ctx.result(it, 0.85f, "skill") }
            ?: detectCloseApps(ctx)
            ?: detectOpenApp(ctx)
            ?: detectVolume(ctx)
            ?: detectMedia(ctx)
            ?: InterpretationResult(CommandIntent.Unknown(text), 0f, normalized, "no-match")
        // Una pregunta («¿cómo subo el volumen?», «¿qué música es esa?») no es una orden: no se ejecuta,
        // la contesta la charla.
        if (result.intent.changesSomething() && isQuestion(text, tokens)) {
            return InterpretationResult(CommandIntent.Unknown(text), 0f, normalized, "question-not-order")
        }
        return result
    }

    /** «¿Qué está sonando?», «¿cómo se llama este tema?», «¿quién canta?», «nombre de la canción que suena». */
    private fun isNowPlayingQuestion(tokens: List<String>): Boolean {
        val words = tokens.toSet()
        if (tokens.isEmpty() || words.any { it in NOT_NOW_PLAYING }) return false
        val asks = tokens.first() in QUESTION_START || tokens.first() in setOf("nombre", "decime", "dime", "sabes", "esta", "hay")
        val media = words.any { it in setOf("sonando", "suena", "tocando", "escuchando", "escucho", "canta", "cancion", "tema", "musica", "artista", "cantante", "temazo") }
        val current = words.any { it in setOf("sonando", "suena", "tocando", "escuchando", "escucho", "canta", "este", "esta", "esto", "esa", "ese", "llama", "nombre", "ahora") }
        return asks && media && current
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

    /** «Cerrá las apps en segundo plano», «limpiá las recientes», «cerrá todo». */
    private fun detectCloseApps(ctx: Context): InterpretationResult? {
        if (!ctx.any(CLOSE_VERB)) return null
        val target = ctx.any(APPS_NOUN) || ctx.phrase("segundo plano", "recientes", "todo", "todas")
        return if (target) ctx.result(CommandIntent.CloseBackgroundApps, 0.9f, "close-apps") else null
    }

    private fun detectOpenApp(ctx: Context): InterpretationResult? {
        val match = OPEN_APP.find(ctx.text) ?: return null
        var name = match.groupValues[1].trim()
        repeat(3) { name = name.replace(OPEN_APP_FILLER, "").trim() }
        if (name.isEmpty()) return ctx.result(CommandIntent.Unknown(ctx.text), 0.3f, "open-app-incomplete")
        return ctx.result(CommandIntent.OpenApp(name), 0.9f, "open-app")
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
        val (query, app) = extractPlayTarget(ctx.text)
        if (ctx.phrase(*PLAY_PHRASES) || (playVerb && (musicNoun || ctx.phrase("algo")))) {
            return ctx.result(CommandIntent.PlayMusic(query, app), 0.9f, "play")
        }
        // «Buscá Despacito», «poné Soda Stereo en Spotify»: verbo de reproducción + lo que se busca.
        if (ctx.tokens.first().matches(SEARCH_VERB) && (query != null || app != null)) {
            return ctx.result(CommandIntent.PlayMusic(query, app), 0.8f, "play-search")
        }
        if (musicNoun) return ctx.result(CommandIntent.PlayMusic(query, app), 0.6f, "play-noun")
        return null
    }

    /** Separa qué buscar y en qué app: «poné música de Soda Stereo en Spotify» -> ("soda stereo", "spotify"). */
    private fun extractPlayTarget(text: String): Pair<String?, String?> {
        var rest = text
        var app: String? = null
        MUSIC_APP_SUFFIX.find(rest)?.let {
            app = it.groupValues[1]
            rest = rest.substring(0, it.range.first).trim()
        }
        rest = rest.replace(PLAY_PREFIX, "").trim()
        repeat(3) { rest = rest.replace(PLAY_FILLER, "").trim() }
        if (rest in MUSIC_APPS && app == null) return null to rest
        if (rest.isEmpty() || rest in GENERIC_MUSIC || rest.split(' ').first() in QUERY_STOPWORDS) return null to app
        return rest to app
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
            "cambia de musica", "cambia la musica", "cambiala", "pone otra", "otra musica",
        )
        val PREVIOUS = Regex("anterior|previa|previo|retroced[a-z]*")
        val PREVIOUS_PHRASES = arrayOf("la de antes", "el de antes", "tema de antes", "cancion de antes", "para atras", "volve atras", "volver atras")
        val PAUSE = Regex("paus[a-z]*|fren[a-z]*")
        val STOP = Regex("deten[a-z]*|stop|parala|paralo|apag[a-z]*|cort[a-z]*|termin[a-z]*")
        val STOP_PHRASES = arrayOf(
            "quita la musica", "saca la musica", "sacala", "basta de musica", "no quiero mas musica", "cerra la musica",
            "cerra la cancion", "terminala", "por completo", "del todo",
        )
        val RESUME = Regex("continu[a-z]*|segui[a-z]*|sigue|sigan|reanud[a-z]*|despaus[a-z]*|retom[a-z]*|play")
        val RESUME_PHRASES = arrayOf("que siga", "volve a reproducir", "dale play", "dale que siga")
        val MUSIC_NOUN = Regex("musica|musiquita|cancion|canciones|cancioncita|tema|temas|temazo|temita|playlist|album|disco|radio")
        val PLAY_VERB = Regex("pon|pone[a-z]*|poner[a-z]*|ponme|reproduc[a-z]*|toc[a-z]*|escuch[a-z]*|arranc[a-z]*|empez[a-z]*|inici[a-z]*|met[ea][a-z]*|tira[a-z]*|larg[a-z]*|mand[a-z]*|quiero")
        val PLAY_PHRASES = arrayOf(
            "quiero escuchar", "me gustaria escuchar", "tengo ganas de escuchar", "quisiera escuchar",
            "quiero musica", "algo de musica", "un poco de musica",
        )
        val QUERY_STOPWORDS = setOf("fondo", "volumen", "pausa", "la de antes", "antes")
        val MUSIC_APPS = setOf(
            "spotify", "youtube music", "youtube", "yt music", "deezer", "apple music", "amazon music",
            "soundcloud", "tidal", "napster", "audiomack", "boomplay", "radio",
        )
        val MUSIC_APP_SUFFIX = Regex(" (?:en|por|con|desde) (?:la app |la aplicacion |el )?(${MUSIC_APPS.joinToString("|")})$")
        val SEARCH_VERB = Regex("pon|pone|poneme|ponme|poner|reproduc[a-z]*|busc[a-z]*|toca|tocame|escuch[a-z]*|tirame|mandame")
        val PLAY_PREFIX = Regex(
            "^(?:(?:quiero|quisiera|me gustaria|tengo ganas de|podes|podrias) )?" +
                "(?:pon|pone|poneme|ponme|poner|reproduci|reproducime|reproduce|reproducir|busca|buscame|buscar|toca|tocame|" +
                "tirame|tira|mandame|manda|larga|meteme|mete|escuchar|escucha)\\b",
        )
        val PLAY_FILLER = Regex(
            "^(?:la cancion de|una cancion de|un tema de|canciones de|temas de|musica de|algo de|la musica|la cancion|el tema|" +
                "el album|el disco|la playlist|la lista|musica|algo|una cancion|un tema|canciones|temas|un poco de|de|la|el)\\b",
        )
        val GENERIC_MUSIC = setOf("", "musica", "algo", "algo lindo", "cualquier cosa", "lo que sea", "algo para escuchar", "otra", "otra cosa")
        val CLOSE_VERB = Regex("cerr[a-z]*|cierr[a-z]*|limpi[a-z]*|mat[a-z]*|elimin[a-z]*|borr[a-z]*|quit[a-z]*|sac[a-z]*")
        val APPS_NOUN = Regex("apps?|aplicaciones|aplicacion|aplicaciones|ventanas|recientes|pestanas|programas")
        val OPEN_APP = Regex("^(?:abri|abre|abrime|abrir|abrila|abrilo|ejecuta|ejecutame|lanza|entra a|entra en|anda a|abri la app|abri la aplicacion)\\s+(.+)$")
        val OPEN_APP_FILLER = Regex("^(?:la app de|la aplicacion de|la app|la aplicacion|app|aplicacion|el|la|los|las|a|de)\\b")
    }
}
