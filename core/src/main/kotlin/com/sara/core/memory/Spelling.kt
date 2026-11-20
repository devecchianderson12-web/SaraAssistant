package com.sara.core.memory

import com.sara.core.interpreter.TextNormalizer
import com.sara.core.model.AssistantIdentity
import com.sara.core.model.CommandIntent

/**
 * Cómo se escriben los nombres que el reconocimiento entiende mal. Se enseña hablando:
 * «Yesica se escribe con y griega, no con jota», «cuando digo Ceci me refiero a Yessi»,
 * «no es Jessi, es Yessi». Desde ahí, lo que se escucha mal se corrige antes de entender la orden
 * (y así «llamá a Jessi» busca a «Yessi» en los contactos, y un mensaje dice «Yessi»).
 *
 * Se guarda en la memoria: «escritura: yesica» = «Yesica|jesica,llesica,iesica».
 */
object Spelling {
    const val PREFIX = "escritura: "

    /** Cómo se nombran las letras al dictar. */
    private val LETTERS = listOf(
        "y griega" to "y", "i griega" to "y", "ye" to "y", "jota" to "j", "i latina" to "i", "doble ele" to "ll", "elle" to "ll",
        "doble ese" to "ss", "dos eses" to "ss", "ese" to "s", "ce" to "c", "zeta" to "z", "ceta" to "z", "be larga" to "b",
        "be grande" to "b", "be alta" to "b", "ve corta" to "v", "ve chica" to "v", "ve baja" to "v", "uve" to "v", "be" to "b", "ve" to "v",
        "hache" to "h", "ka" to "k", "cu" to "q", "ge" to "g", "equis" to "x", "eñe" to "ñ", "ele" to "l", "ere" to "r", "erre" to "rr",
        "y" to "y", "j" to "j", "i" to "i", "c" to "c", "s" to "s", "z" to "z", "b" to "b", "v" to "v", "h" to "h", "k" to "k", "g" to "g", "x" to "x",
    ).sortedByDescending { it.first.length }

    /** Letras que el reconocimiento confunde entre sí (suenan igual o parecido). */
    private val CONFUSIONS = mapOf(
        "y" to listOf("j", "ll", "i"), "j" to listOf("y", "g"), "ll" to listOf("y", "j"), "i" to listOf("y"),
        "c" to listOf("s", "z"), "s" to listOf("c", "z"), "z" to listOf("s", "c"), "ss" to listOf("s"),
        "b" to listOf("v"), "v" to listOf("b"), "g" to listOf("j"), "k" to listOf("c", "qu"), "x" to listOf("s", "j"), "h" to listOf(""),
    )

    private val LETTER = LETTERS.joinToString("|") { Regex.escape(it.first) }
    private val LEAD = Regex("^\\s*(?:(?:${AssistantIdentity.NAMES})\\s*[,.]?\\s+)?", RegexOption.IGNORE_CASE)

    /** «(el nombre) Yesica se escribe con y griega (y no con jota)». */
    private val SPELL = Regex(
        "^(?:(?:el\\s+nombre|la\\s+palabra|el\\s+apellido)\\s+(?:de\\s+)?)?([\\p{L}]+(?:\\s+[\\p{L}]+){0,2}?)\\s+se\\s+(?:escribe|escriben|dice|pronuncia)\\s+con\\s+(?:una?\\s+|la\\s+)?($LETTER)" +
            "(?:\\s*,?\\s*(?:y\\s+)?(?:no|ni)\\s+con\\s+(?:una?\\s+|la\\s+)?($LETTER))?$",
    )

    /** «Cuando digo Ceci me refiero a Yessi», «cuando escuches jessi es Yessi». */
    private val ALIAS = Regex(
        "^cuando\\s+(?:yo\\s+)?(?:te\\s+)?(?:digo|diga|escuch[aá]s|escuches|oigas|o[ií]s|entend[eé]s|entiendas)\\s+[\"«“]?([\\p{L}]+(?:\\s+[\\p{L}]+)?)[\"»”]?\\s*,?\\s+" +
            "(?:me\\s+refiero\\s+a|quiero\\s+decir|es|significa|hablo\\s+de|digo)\\s+[\"«“]?([\\p{L}]+(?:\\s+[\\p{L}]+)?)[\"»”]?$",
    )

    /** «No es Jessi, es Yessi», «no se dice ceci, se dice Yessi». */
    private val NOT_BUT = Regex(
        "^no\\s+(?:es|se\\s+dice|se\\s+escribe|dije)\\s+[\"«“]?([\\p{L}]+)[\"»”]?\\s*,?\\s*(?:es|se\\s+dice|se\\s+escribe|dije|sino)\\s+[\"«“]?([\\p{L}]+)[\"»”]?$",
    )

    fun parse(text: String): CommandIntent.Remember? {
        val original = text.trim().trimEnd('.', '!', '?')
        val body = original.substring(LEAD.find(original)?.value?.length ?: 0).trimStart('¿', '¡').trim()
        val lower = body.lowercase()
        SPELL.find(lower)?.let { m ->
            val word = originalWords(body, m.groupValues[1]).split(' ').last()
            val right = letter(m.groupValues[2]) ?: return null
            val wrong = m.groupValues[3].takeIf { it.isNotEmpty() }?.let(::letter)
            return spelled(word, right, wrong)
        }
        (ALIAS.find(lower) ?: NOT_BUT.find(lower))?.let { m ->
            val heard = m.groupValues[1]
            val correct = originalWords(body, m.groupValues[2])
            if (TextNormalizer.normalize(heard) == TextNormalizer.normalize(correct)) return null
            return CommandIntent.Remember(PREFIX + TextNormalizer.normalize(correct), capitalize(correct) + "|" + heard.lowercase())
        }
        return null
    }

    private fun letter(spoken: String): String? = LETTERS.firstOrNull { it.first == spoken.trim() }?.second

    /** La palabra bien escrita y las formas en que el reconocimiento la escribe mal. */
    internal fun spelled(heard: String, right: String, wrong: String?): CommandIntent.Remember {
        val w = heard.lowercase()
        val alternatives = (listOfNotNull(wrong) + CONFUSIONS[right].orEmpty()).distinct()
        val correct = when {
            w.contains(right) -> w
            wrong != null && w.contains(wrong) -> w.replaceFirst(wrong, right)
            else -> alternatives.firstOrNull { it.isNotEmpty() && w.contains(it) }?.let { w.replaceFirst(it, right) } ?: w
        }
        val variants = linkedSetOf<String>()
        if (correct.contains(right)) alternatives.forEach { variants += correct.replaceFirst(right, it) }
        if (w != correct) variants += w
        // «Yessi» también se escucha «Yesi» (y al revés).
        variants.toList().forEach { v -> if (v.contains("ss")) variants += v.replace("ss", "s") else if (v.contains('s')) variants += v.replaceFirst("s", "ss") }
        variants.removeAll { TextNormalizer.normalize(it) == TextNormalizer.normalize(correct) || it.isBlank() }
        return CommandIntent.Remember(PREFIX + TextNormalizer.normalize(correct), capitalize(correct) + "|" + variants.joinToString(","))
    }

    /** Junta lo nuevo con lo que ya estaba guardado para esa palabra (no se pierden variantes). */
    fun merge(old: String?, new: String): String {
        if (old == null) return new
        val (word, variants) = split(new)
        val oldVariants = split(old).second
        return word + "|" + (oldVariants + variants).distinct().joinToString(",")
    }

    fun word(value: String): String = split(value).first

    private fun split(value: String): Pair<String, List<String>> {
        val parts = value.split('|', limit = 2)
        return parts[0] to (parts.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList())
    }

    /** Corrige lo que se escuchó con lo que se enseñó. */
    fun apply(text: String, memory: MemoryStore?): String {
        val rules = memory?.all()?.filterKeys { it.startsWith(PREFIX) }?.values.orEmpty()
        if (rules.isEmpty()) return text
        val fixes = HashMap<String, String>()
        for (rule in rules) {
            val (word, variants) = split(rule)
            variants.forEach { fixes[TextNormalizer.normalize(it)] = word }
        }
        var out = text
        // Variantes de varias palabras («ce ci» → «Yessi»).
        fixes.filterKeys { ' ' in it }.forEach { (heard, word) ->
            out = Regex("(?i)\\b" + heard.split(' ').joinToString("\\s+") { Regex.escape(it) } + "\\b").replace(out, word)
        }
        return Regex("\\p{L}+").replace(out) { m -> fixes[TextNormalizer.normalize(m.value)] ?: m.value }
    }

    private fun originalWords(body: String, lowerWords: String): String {
        val i = body.lowercase().indexOf(lowerWords)
        return if (i >= 0) body.substring(i, i + lowerWords.length) else lowerWords
    }

    private fun capitalize(s: String) = s.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
