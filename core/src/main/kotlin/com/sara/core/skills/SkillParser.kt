package com.sara.core.skills

import com.sara.core.model.AssistantIdentity

import com.sara.core.interpreter.SpanishNumbers
import com.sara.core.interpreter.TextNormalizer
import com.sara.core.model.CommandIntent
import java.time.LocalDateTime

/**
 * Entiende sin IA los pedidos de las habilidades del teléfono (alarmas, recordatorios, clima,
 * cuentas, linterna, brillo, llamadas, calendario, navegación, listas…). Lo que no reconoce lo
 * deja pasar para que lo resuelvan las demás reglas o la IA.
 */
object SkillParser {
    private fun skill(skill: String, action: String, vararg args: Pair<String, String?>) =
        CommandIntent.Skill(skill, action, args.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap())

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now()): CommandIntent.Skill? {
        val n = TextNormalizer.normalize(text)
        if (n.isBlank()) return null
        val p = " $n "
        val tokens = TextNormalizer.tokens(n)
        fun has(vararg words: String) = words.any { p.contains(" $it ") }
        fun starts(vararg words: String) = words.any { n.startsWith("$it ") || n == it }
        val on = has("prende", "prendeme", "encende", "encendeme", "activa", "activame", "enciende", "prender", "encender", "activar", "pone", "abri")
        val off = has("apaga", "apagame", "desactiva", "desactivame", "apagar", "desactivar", "saca", "quita", "cerra")

        // «Anotá …» siempre es una nota (aunque el texto hable de alarmas, Wi-Fi, etc.).
        if (Regex("^(anota|anotame|toma nota|apunta)\\b").containsMatchIn(n)) listIntent(text, n, p)?.let { return it }

        // ---------------------------------------------------------- Alarmas y temporizadores
        if (has("temporizador", "timer", "cuenta regresiva", "cronometro")) {
            val seconds = TimeParser.duration(text) ?: return skill("alarma", "ver_temporizadores")
            return skill("alarma", "temporizador", "segundos" to seconds.toString())
        }
        if (has("alarma", "alarmas", "despertador", "despertame", "levantame")) {
            if (has("que alarmas", "cuales alarmas", "mis alarmas", "ver alarmas", "proxima alarma")) return skill("alarma", "ver")
            val clock = TimeParser.clock(text) ?: TimeParser.moment(text, now)?.let { TimeParser.Clock(it.hour, it.minute) }
                ?: return skill("alarma", "ver")
            return skill("alarma", "alarma", "hora" to clock.hour.toString(), "minuto" to clock.minute.toString())
        }

        // ---------------------------------------------------------- Recordatorios
        if (Regex("^(recordame|recordarme|recorda me|avisame|acordame) ").containsMatchIn("$n ") ||
            has("poneme un recordatorio", "pone un recordatorio", "crea un recordatorio", "agrega un recordatorio")
        ) {
            val at = TimeParser.moment(text, now) ?: return null
            val what = reminderText(text).ifBlank { "lo que me pediste" }
            return skill("recordatorios", "crear", "texto" to what, "cuando" to at.toString())
        }
        if (has("recordatorio", "recordatorios")) {
            if (off || has("borra", "elimina", "cancela")) {
                return skill("recordatorios", "borrar", "texto" to afterWord(text, "recordatorio").ifBlank { null })
            }
            return skill("recordatorios", "ver")
        }

        // ---------------------------------------------------------- Rutinas programadas
        Regex("^(todos los dias|cada dia|todas las mananas|todas las noches|de lunes a viernes|los fines de semana) ").find("$n ")?.let { m ->
            val clock = TimeParser.clock(text) ?: return@let
            val days = when {
                m.groupValues[1] == "de lunes a viernes" -> "lun-vie"
                m.groupValues[1] == "los fines de semana" -> "sab-dom"
                else -> "todos"
            }
            val order = routineOrder(text)
            if (order.isNotBlank()) {
                return skill("rutinas", "programar", "hora" to clock.hour.toString(), "minuto" to clock.minute.toString(), "dias" to days, "orden" to order)
            }
        }
        if (has("rutina", "rutinas")) {
            if (has("que rutinas", "mis rutinas", "cuales rutinas", "rutinas tengo")) return skill("rutinas", "ver")
            if (has("borra", "elimina", "cancela", "quita", "saca")) return skill("rutinas", "borrar", "texto" to afterWord(text, "rutina"))
            if (has("pausa", "desactiva", "frena", "suspende")) return skill("rutinas", "pausar", "texto" to afterWord(text, "rutina"))
            if (has("reactiva", "activa", "segui", "renauda", "reanuda")) return skill("rutinas", "reanudar", "texto" to afterWord(text, "rutina"))
        }

        // ---------------------------------------------------------- Clima
        if (has("clima", "pronostico", "temperatura", "llover", "llueve", "lluvia", "va a llover", "hace frio", "hace calor") ||
            Regex("\\b(que|como esta el|como va a estar el) tiempo\\b|\\btiempo (hace|va a hacer)\\b").containsMatchIn(n)
        ) {
            val day = when { has("pasado manana") -> "pasado_manana"; has("manana") && !has("de la manana", "esta manana") -> "manana"; else -> "hoy" }
            return skill("clima", "consultar", "lugar" to placeAfter(text), "dia" to day)
        }

        // ---------------------------------------------------------- Cuentas y conversiones
        UnitConverter.answer(text)?.let { return skill("calculadora", "convertir", "pedido" to text) }
        if (Calculator.toExpression(text) != null) return skill("calculadora", "calcular", "cuenta" to text)

        // ---------------------------------------------------------- Ajustes del teléfono
        if (has("linterna", "flash", "luz del celular", "luz del telefono")) return skill("dispositivo", "linterna", "estado" to if (off) "apagar" else "prender")
        if (has("brillo", "pantalla mas clara", "pantalla mas oscura")) {
            val pct = SpanishNumbers.findAll(tokens).firstOrNull()?.value?.coerceIn(0, 100)
            val action = when {
                has("maximo", "al maximo", "todo") -> "fijar" to "100"
                has("minimo", "al minimo") -> "fijar" to "5"
                pct != null && !has("mas", "menos") -> "fijar" to pct.toString()
                has("subi", "subime", "aumenta", "mas", "clara", "sube", "subir") -> "subir" to null
                has("baja", "bajame", "bajale", "reduci", "menos", "oscura", "bajar") -> "bajar" to null
                else -> "consultar" to null
            }
            return skill("dispositivo", "brillo", "accion" to action.first, "porcentaje" to (action.second ?: pct?.takeIf { action.first != "consultar" }?.toString()))
        }
        if (has("wifi", "wi fi", "wifi", "wi-fi")) return skill("dispositivo", "wifi", "estado" to when { off -> "apagar"; on -> "prender"; else -> "consultar" })
        // «Conectate a los auriculares JBL», «conectá el bluetooth al auto».
        Regex("^(?:conecta|conectate|conectame|conectar|vincula|vinculate|enlaza)\\s+(?:el\\s+(?:bluetooth|blutu|celular|telefono)\\s+)?(?:a|al|con|a los|a las|a la|con el|con los|con la)\\s+(.+)$").find(n)?.let { m ->
            val target = originalTail(text, m.groupValues[1]).replace(Regex("^(?:(?:a|al|con|mis|mi|los|las|el|la)\\s+)+", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+por bluetooth$", RegexOption.IGNORE_CASE), "")
            if (target.isNotBlank() && !target.equals("internet", true) && !target.lowercase().startsWith("wifi")) {
                // «Conectate a mi Bluetooth»: no nombra un dispositivo; prender y esperar a que se conecte solo.
                if (TextNormalizer.normalize(target) in GENERIC_BLUETOOTH) return skill("dispositivo", "bluetooth", "estado" to "conectar")
                return skill("dispositivo", "bluetooth", "estado" to "conectar", "dispositivo" to target)
            }
        }
        if (has("bluetooth", "blutu", "bluetu")) return skill("dispositivo", "bluetooth", "estado" to when { off -> "apagar"; on || has("conecta") -> "prender"; else -> "consultar" })
        if (has("no molestar", "modo no molestar")) return skill("dispositivo", "no_molestar", "estado" to when { off -> "apagar"; on -> "prender"; else -> "consultar" })
        if (has("bateria", "cuanta carga", "carga tengo", "porcentaje de carga")) return skill("dispositivo", "bateria")
        if (has("que telefono tengo", "que celular tengo", "version de android", "informacion del telefono", "info del telefono", "almacenamiento", "espacio libre", "cuanto espacio", "memoria libre")) {
            return skill("dispositivo", "info")
        }

        // ---------------------------------------------------------- Llamadas y SMS
        Regex("^(?:llama|llamame|llamar|llamale|marca|marcale|discar|disca)\\s+(?:a\\s+|al\\s+)?(.+)$").find(n)?.let { m ->
            val who = m.groupValues[1].replace(Regex("\\s+por (telefono|whatsapp)$"), "").trim()
            if (who.isNotBlank()) return skill("llamadas", "llamar", "contacto" to who)
        }
        Regex("^(?:manda|mandale|envia|enviale|escribi|escribile)\\s+(?:un\\s+)?(?:sms|mensaje de texto)\\s+(?:a|al|para)\\s+(.+?)\\s+(?:que diga|diciendo|diciendole)\\s+(.+)$").find(n)?.let { m ->
            val message = Regex("(?:que diga|diciendo|diciendole)\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim() ?: m.groupValues[2]
            return skill("llamadas", "sms", "contacto" to m.groupValues[1], "mensaje" to message)
        }

        // ---------------------------------------------------------- Calendario
        if (Regex("^(agenda|agendame|agendar|anota en el calendario|crea un evento|crear un evento|crea una reunion|agrega al calendario|agrega un evento)\\b").containsMatchIn(n)) {
            val at = TimeParser.moment(text, now) ?: return null
            val title = eventTitle(text).ifBlank { "Evento" }
            return skill("calendario", "crear", "titulo" to title, "cuando" to at.toString())
        }
        if (has("calendario", "agenda", "mis eventos", "que eventos", "reuniones", "que tengo hoy", "que tengo manana", "que tengo para hoy", "que tengo para manana", "tengo algo hoy", "tengo algo manana")) {
            val day = if (has("manana") && !has("esta manana")) "manana" else "hoy"
            return skill("calendario", "ver", "dia" to day)
        }

        // ---------------------------------------------------------- Navegación y lugares
        Regex("^(?:llevame|navega|navegar|como llego|como voy|indicaciones|ruta|guiame|quiero ir)\\s+(?:a|al|hasta|para|hacia)\\s+(.+)$").find(n)?.let { m ->
            return skill("mapas", "navegar", "destino" to originalTail(text, m.groupValues[1]))
        }
        Regex("^(?:donde hay|busca|buscame|mostrame)\\s+(.+?)\\s+(?:cerca|cercanos|cercanas|por aca|cerca de aca|cerca de mi)$").find(n)?.let { m ->
            return skill("mapas", "cerca", "lugar" to m.groupValues[1])
        }
        Regex("^(.+?)\\s+(?:cerca de mi|cerca de aca|cercanos|cercanas|mas cercano|mas cercana)$").find(n)?.let { m ->
            if (m.groupValues[1].split(' ').size <= 4) return skill("mapas", "cerca", "lugar" to m.groupValues[1].removePrefix("la ").removePrefix("el "))
        }
        if (has("donde estoy", "mi ubicacion", "en que calle estoy", "donde me encuentro")) return skill("mapas", "ubicacion")

        // ---------------------------------------------------------- Notificaciones
        if (has("notificaciones", "notificacion") && !has("acceso")) return skill("notificaciones", "leer")

        // ---------------------------------------------------------- Traducción
        Regex("^(?:como se dice|traduci|traduceme|traducime|traduce|traducir)\\s+(.+?)\\s+(?:al|en|a)\\s+(ingles|portugues|frances|italiano|aleman|japones|chino|ruso|coreano|espanol|castellano)$").find(n)?.let { m ->
            val original = Regex("(?i)(?:c[oó]mo se dice|traduc\\S*)\\s+(.+?)\\s+(?:al|en|a)\\s+\\S+\\s*[?.!]*$").find(text.trim())?.groupValues?.get(1)
            return skill("traductor", "traducir", "texto" to (original ?: m.groupValues[1]).trim('"', '«', '»', ' '), "idioma" to m.groupValues[2])
        }

        // ---------------------------------------------------------- Búsqueda en Google
        Regex("^(?:busca|buscame|googlea|googleame)\\s+(?:en\\s+(?:google|internet)\\s+)?(.+?)(?:\\s+en\\s+(?:google|internet))?$").find(n)?.let { m ->
            if (has("google", "internet") || n.startsWith("googlea")) return skill("google", "buscar", "consulta" to m.groupValues[1])
        }

        // ---------------------------------------------------------- Listas: notas, tareas, compras
        listIntent(text, n, p)?.let { return it }

        // ---------------------------------------------------------- Hogar inteligente
        if ((on || off) && has("luces", "luz del living", "luz de la cocina", "luz del cuarto", "aire acondicionado", "el aire", "enchufe", "calefaccion", "la tele", "televisor")) {
            return skill("hogar", "controlar", "pedido" to text)
        }

        // ---------------------------------------------------------- Diagnóstico
        if (has("diagnostico", "que te falta", "revisa tus permisos", "estas bien configurada", "estado del sistema", "que permisos te faltan")) {
            return skill("diagnostico", "revisar")
        }
        return null
    }

    private fun listIntent(text: String, n: String, p: String): CommandIntent.Skill? {
        fun has(vararg w: String) = w.any { p.contains(" $it ") }
        val list = when {
            has("lista de compras", "lista del super", "lista del supermercado", "comprar", "compras") -> ListBook.SHOPPING
            has("tareas", "tarea", "pendientes", "pendiente", "lista de tareas") -> ListBook.TASKS
            has("notas", "nota") || n.startsWith("anota ") || n.startsWith("toma nota") -> ListBook.NOTES
            else -> return null
        }
        val read = has("que tengo que comprar", "que hay en", "leeme", "lee", "decime", "que tengo en", "mis notas", "mis tareas", "que tareas", "que notas", "que pendientes", "mostrame", "cuales son")
        val remove = Regex("^(saca|quita|borra|elimina)\\b").containsMatchIn(n)
        val done = Regex("^(marca|tacha|complete|termine|ya hice|ya compre)\\b").containsMatchIn(n)
        val clear = has("vacia", "borra toda", "borra todas", "limpia la lista", "borra la lista")
        val item = listItem(text)
        return when {
            clear -> CommandIntent.Skill("listas", "vaciar", mapOf("lista" to list))
            remove && item.isNotBlank() -> CommandIntent.Skill("listas", "quitar", mapOf("lista" to list, "texto" to item))
            done && item.isNotBlank() -> CommandIntent.Skill("listas", "completar", mapOf("lista" to list, "texto" to item))
            read || item.isBlank() -> CommandIntent.Skill("listas", "leer", mapOf("lista" to list))
            Regex("^(agrega|agregame|anota|anotame|suma|sumame|pone|poneme|toma nota|guarda|apunta|necesito|tengo que comprar)\\b").containsMatchIn(n) ->
                CommandIntent.Skill("listas", "agregar", mapOf("lista" to list, "texto" to item))
            else -> null
        }
    }

    /** «agregá leche y pan a la lista de compras» -> «leche y pan». */
    private fun listItem(text: String): String {
        var t = text.trim().trimEnd('.', '!', '?')
        t = t.replace(Regex("^\\s*(?:(?:${AssistantIdentity.NAMES})\\s*[,.]?\\s+)?", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^(agreg[aá]me|agreg[aá]|anot[aá]me|anot[aá]|sum[aá]me|sum[aá]|pon[eé]me|pon[eé]|tom[aá] nota(?: de)?|guard[aá]|apunt[aá]|sac[aá]|quit[aá]|borr[aá]|elimin[aá]|marc[aá]|tach[aá]|necesito|tengo que comprar|ya compr[eé]|ya hice)\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s*(?:a|en|de|del|como hech[oa]s?|de la|en la|a la|a mis|en mis|de mis)?\\s*(?:la |mis |las |el )?(?:lista de compras|lista del super(?:mercado)?|lista de tareas|tareas|notas|nota|compras|pendientes)\\s*(?:como hech[oa]s?)?$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^(?:una nota|nota|la nota)\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+como hech[oa]s?$", RegexOption.IGNORE_CASE), "")
        return t.trim(' ', ',', ':', '"', '«', '»')
    }

    // Primero la hora («a las 9 de la mañana»), después el día («mañana»).
    private val TIME_BITS = listOf(
        "\\b(?:hoy\\s+)?a\\s+las?\\s+\\S+(?:\\s+y\\s+(?:media|cuarto|\\d+|\\S+))?(?:\\s+de\\s+la\\s+(?:ma[ñn]ana|tarde|noche|madrugada))?",
        "\\ba\\s+la\\s+una(?:\\s+y\\s+(?:media|cuarto))?",
        "\\b\\d{1,2}[:.]\\d{2}\\b",
        "\\b(?:en|dentro\\s+de)\\s+(?:un|una|\\S+)\\s+(?:minutos?|horas?|segundos?|d[ií]as?)(?:\\s+y\\s+media)?",
        "\\b(?:pasado\\s+)?ma[ñn]ana\\b", "\\bhoy\\b", "\\besta\\s+(?:noche|tarde)\\b",
    )

    private fun stripTime(text: String): String {
        var t = text
        for (bit in TIME_BITS) t = t.replace(Regex(bit, RegexOption.IGNORE_CASE), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** «recordame mañana a las 9 que llame al médico» -> «llamar al médico» (aprox.: «llame al médico»). */
    private fun reminderText(text: String): String {
        var t = stripTime(text)
        t = t.replace(Regex("^\\s*(?:(?:${AssistantIdentity.NAMES})\\s*[,.]?\\s+)?", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^(record[aá]me|recordarme|record[aá] me|av[ií]same|acord[aá]me|pon[eé]me un recordatorio|pon[eé] un recordatorio|cre[aá] un recordatorio|agreg[aá] un recordatorio)\\s*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^(?:de|que|para|el|la)\\s+", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^(?:de|que|para)\\s+", RegexOption.IGNORE_CASE), "")
        return t.trim(' ', ',', '.')
    }

    private fun eventTitle(text: String): String {
        var t = stripTime(text)
        t = t.replace(Regex("^\\s*(?:(?:${AssistantIdentity.NAMES})\\s*[,.]?\\s+)?", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^(agend[aá]me|agend[aá]|agendar|anot[aá] en el calendario|cre[aá] un evento|crear un evento|cre[aá] una reuni[oó]n|agreg[aá] al calendario|agreg[aá] un evento)\\s*(?:de|que|para|:)?\\s*", RegexOption.IGNORE_CASE), "")
        return t.trim(' ', ',', '.').replaceFirstChar { it.uppercase() }
    }

    /** «todos los días a las 8 decime el clima» -> «decime el clima». */
    private fun routineOrder(text: String): String {
        var t = stripTime(text)
        t = t.replace(Regex("^\\s*(?:(?:${AssistantIdentity.NAMES})\\s*[,.]?\\s+)?", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^(todos los d[ií]as|cada d[ií]a|todas las ma[ñn]anas|todas las noches|de lunes a viernes|los fines de semana)\\s*,?\\s*", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("^(?:quiero que|que)\\s+", RegexOption.IGNORE_CASE), "")
        return t.trim(' ', ',', '.')
    }

    /** «cómo está el clima en Mar del Plata mañana» -> «Mar del Plata». */
    private fun placeAfter(text: String): String? {
        val m = Regex("\\b(?:en|de|para)\\s+([A-Za-zÁÉÍÓÚÜÑáéíóúüñ][\\wÁÉÍÓÚÜÑáéíóúüñ .'-]{1,40}?)\\s*(?:hoy|ma[ñn]ana|pasado ma[ñn]ana|ahora)?\\s*[?.!]*$", RegexOption.IGNORE_CASE)
            .find(text.trim()) ?: return null
        val place = m.groupValues[1].trim()
        val ignored = setOf("hoy", "mañana", "manana", "la semana", "el dia", "este momento", "la tarde", "la noche", "la mañana", "la manana", "mi zona", "mi ciudad", "aca", "acá")
        return place.takeUnless { it.lowercase() in ignored || it.lowercase().startsWith("la tarde") }
    }

    private fun afterWord(text: String, word: String): String {
        val i = text.lowercase().indexOf(word)
        if (i < 0) return ""
        return text.substring(i + word.length).trimStart('s').trim().removePrefix("de ").removePrefix("del ").removePrefix("que ").trim(' ', '.', '?')
    }

    /** Devuelve el fragmento del texto original (con mayúsculas y tildes) que corresponde a [normalizedTail]. */
    private val GENERIC_BLUETOOTH = setOf("bluetooth", "blutu", "bluetu", "mi bluetooth", "el bluetooth", "algo", "algun dispositivo", "lo de siempre", "lo que siempre")

    private fun originalTail(text: String, normalizedTail: String): String {
        val words = normalizedTail.split(' ').size
        val original = text.trim().trimEnd('.', '?', '!').split(Regex("\\s+"))
        return original.takeLast(words).joinToString(" ").ifBlank { normalizedTail }
    }
}
