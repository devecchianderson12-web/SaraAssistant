package com.sara.core.interpreter

import com.sara.core.model.CommandIntent

/**
 * «Mandale un WhatsApp a Mi Amor que diga llego en diez», «enviá un mensaje por WhatsApp a Juan
 * diciendo hola», «decile a mamá por WhatsApp que ya salgo».
 * Trabaja sobre el texto original (en minúsculas, mismo largo) para conservar el mensaje tal cual.
 */
object MessageParser {
    private const val WA = "(?:whatsapp|whats app|wasap|guasap|wsp|wpp|whatsap)"
    private const val VIA = "(?:por|en|de)\\s+$WA"
    private val LEAD = Regex(
        "^\\s*(?:sara\\s*[,.]?\\s+)?(?:(?:por\\s+favor|porfa)\\s*,?\\s+)?" +
            "(?:(?:me\\s+)?(?:pod[eé]s|puedes|podr[ií]as|quiero\\s+que|necesito\\s+que)\\s+)?",
    )
    private const val WHAT = "(?:(?:un|una|los|las|el|este|estos|unos)\\s+)?(?:mensajes?|mensajito|msj|$WA)?"

    /** Verbo + (un mensaje) + (por WhatsApp) + a CONTACTO + (por WhatsApp) + que diga MENSAJE. */
    private val SEND = Regex(
        "^(?:mand|env[ií]|escrib)[a-záéíóú]*\\s+$WHAT\\s*,?\\s*(?:$VIA\\s+)?" +
            "(?:a|al|para)\\s+(.+)\\s*,?\\s*(?:$VIA\\s*,?\\s*)?" +
            "(?:que\\s+(?:diga|dice|le\\s+diga)|diciendo(?:le)?|diciéndole|con\\s+el\\s+(?:texto|mensaje)|:)\\s*[:,]?\\s*(.+)$",
    )

    /** «Decile a Juan (por WhatsApp) que …», «escribile a mamá que …». */
    private val TELL = Regex(
        "^(?:dec[ií]le|dile|escrib[ií]le|mand[aá]le|env[ií]ale)\\s+(?:$VIA\\s+)?(?:a|al)\\s+(.+?)\\s*,?\\s*(?:$VIA\\s*,?\\s*)?que\\s+(.+)$",
    )

    /** Pedido sin el texto: «mandá un mensaje al grupo Notas» -> Sara pregunta qué decir. */
    private val SEND_NO_TEXT = Regex(
        "^(?:mand|env[ií]|escrib)[a-záéíóú]*\\s+$WHAT\\s*,?\\s*(?:$VIA\\s+)?(?:a|al|para)\\s+(.+)$",
    )

    /** «Enviame un mensaje a/por WhatsApp que diga hola a Juan»: el contacto va al final. */
    private val SEND_CONTACT_LAST = Regex(
        "^(?:mand|env[ií]|escrib)[a-záéíóú]*\\s+$WHAT\\s*,?\\s*(?:a|al|por|en|de)\\s+$WA\\s*,?\\s*" +
            "(?:que\\s+(?:diga|dice|le\\s+diga)|diciendo(?:le)?|diciéndole|con\\s+el\\s+(?:texto|mensaje)|:)\\s*[:,]?\\s*(.+)$",
    )
    private val TRAILING_CONTACT = Regex("^(.+)\\s+(?:a|al|para)\\s+(.+)$")
    private val ONLY_WA = Regex("^$WA$")

    fun parse(text: String): CommandIntent.SendMessage? {
        val original = text.trim()
        val lower = original.lowercase()
        if (lower.length != original.length) return null
        val lead = LEAD.find(lower)?.value?.length ?: 0
        val body = lower.substring(lead)
        SEND_CONTACT_LAST.find(body)?.let { m -> return messageThenContact(original, lead + m.groups[1]!!.range.first) }
        val match = SEND.find(body) ?: TELL.find(body)
        if (match == null) {
            val partial = SEND_NO_TEXT.find(body) ?: return null
            val r = partial.groups[1]!!.range
            val target = cleanContact(original.substring(lead + r.first, lead + r.last + 1).trimEnd('?', '.', '!'))
            if (target.first.isEmpty()) return null
            return CommandIntent.SendMessage(target.first, "", group = target.second)
        }
        val target = cleanContact(original.substring(lead + match.groups[1]!!.range.first, lead + match.groups[1]!!.range.last + 1))
        // «… a WhatsApp que diga hola a Juan»: lo que parecía el contacto era la app.
        if (ONLY_WA.matches(target.first)) return messageThenContact(original, lead + match.groups[2]!!.range.first)
        val contact = target.first
        val range = match.groups[2]!!.range
        val message = original.substring(lead + range.first, lead + range.last + 1)
            .trim().trim('"', '“', '”', '«', '»', '\'', ' ')
        if (contact.isEmpty() || message.isEmpty()) return null
        return CommandIntent.SendMessage(contact, message.replaceFirstChar { it.uppercase() }, group = target.second)
    }

    /** Texto desde [start]: «hola a Juan» -> mensaje «Hola», contacto «juan» (el último «a»). */
    private fun messageThenContact(original: String, start: Int): CommandIntent.SendMessage? {
        val rest = original.substring(start).trim().trimEnd('.', '!', '?')
        val m = TRAILING_CONTACT.find(rest) ?: return null
        val message = m.groupValues[1].trim().trim('"', '“', '”', '«', '»', '\'', ' ', ',')
        val target = cleanContact(m.groupValues[2])
        if (message.isEmpty() || target.first.isEmpty()) return null
        return CommandIntent.SendMessage(target.first, message.replaceFirstChar { it.uppercase() }, group = target.second)
    }

    private val GROUP = Regex("^(?:el\\s+|un\\s+|mi\\s+)?(?:grupo|chat)\\b\\s*(?:que\\s+(?:dice|se\\s+llama|tiene\\s+de\\s+nombre)|llamado|de\\s+nombre|de)?\\s*", RegexOption.IGNORE_CASE)
    private val TAIL = Regex("\\s*(?:,|\\s(?:en|de)\\s+(?:mi\\s+)?$WA\\b|\\s(?:en|de)\\s+el\\s+(?:cual|que)\\b|\\sdonde\\b|\\sque\\s+(?:es|tiene)\\b).*$", RegexOption.IGNORE_CASE)
    private val QUOTED = Regex("[\"«“]([^\"»”]+)[\"»”]")

    /** «grupo que dice "notas" en mi WhatsApp en el cual solo estoy yo» -> ("notas", grupo). */
    private fun cleanContact(raw: String): Pair<String, Boolean> {
        var text = raw.trim().replace(Regex("\\s*(?:por|en|de)\\s+$WA\\s*$", RegexOption.IGNORE_CASE), "")
        val group = GROUP.containsMatchIn(text)
        QUOTED.find(text)?.let { return it.groupValues[1].trim() to group }
        text = text.replace(GROUP, "").replace(TAIL, "")
        return text.trim(' ', ',', '.').lowercase() to group
    }
}

/** Respuesta a «¿Lo mando?»: `true` sí, `false` no, `null` no se entendió. */
object ConfirmationParser {
    private val YES = setOf(
        "si", "dale", "mandalo", "mandaselo", "mandale", "manda", "envialo", "enviaselo", "envia", "ok", "okay", "oka",
        "obvio", "claro", "correcto", "confirmo", "listo", "perfecto", "bueno", "va", "sip", "sisi", "afirmativo", "exacto", "eso",
    )
    private val NO = setOf("no", "nop", "nah", "cancela", "cancelalo", "cancelar", "deja", "dejalo", "negativo")

    fun parse(text: String): Boolean? {
        val tokens = TextNormalizer.tokens(TextNormalizer.normalize(text))
        if (tokens.isEmpty()) return null
        if (tokens.first() in NO || tokens.any { it in setOf("no", "cancela", "cancelalo", "cancelar") }) return false
        if (tokens.any { it in YES } || text.contains("de una")) return true
        return null
    }
}
