package com.lucyremota.remoto

import com.lucyremota.nucleo.interpreter.CommandInterpreter
import com.lucyremota.nucleo.interpreter.SpokenForms
import com.lucyremota.nucleo.interpreter.TextNormalizer
import com.lucyremota.nucleo.model.CommandIntent
import com.lucyremota.nucleo.model.InterpretationResult

/**
 * Frases propias de la computadora («sacá una captura», «minimizá todo», «abrí descargas»,
 * «apagá la compu») → intención de Windows. Lo demás («poné música», «subí el volumen», «abrí
 * Chrome», charla) lo entiende el intérprete de Sara ([fallback]).
 *
 * Devuelve `CommandIntent.Skill("pc", INTENCION, parámetros)`: el ejecutor lo manda tal cual.
 */
class PcInterpreter(private val fallback: CommandInterpreter) : CommandInterpreter {

    override suspend fun interpret(text: String): InterpretationResult {
        val spoken = SpokenForms.rewrite(text)
        val n = TextNormalizer.normalize(spoken)
        parse(n, spoken)?.let { (intent, params) ->
            return InterpretationResult(CommandIntent.Skill(PC, intent, params), 0.9f, n, "pc")
        }
        return fallback.interpret(text)
    }

    companion object {
        const val PC = "pc"
        private val COMPUTER = "(?:la |el |mi )?(?:compu|computadora|pc|equipo|notebook|laptop|maquina|ordenador)"

        private val PAGES = linkedMapOf(
            "bluetooth" to "bluetooth", "wifi" to "wifi", "wi fi" to "wifi", "red" to "network", "internet" to "network",
            "sonido" to "sound", "audio" to "sound", "pantalla" to "display", "monitor" to "display", "notificaciones" to "notifications",
            "impresoras" to "printers", "impresora" to "printers", "bateria" to "battery", "actualizaciones" to "updates",
            "windows update" to "updates", "aplicaciones" to "apps", "programas" to "apps", "personalizacion" to "personalization",
            "fondo de pantalla" to "personalization",
        )
        private val FOLDERS = linkedMapOf(
            "descargas" to "downloads", "documentos" to "documents", "imagenes" to "pictures", "fotos" to "pictures",
            "musica" to "music", "videos" to "videos", "escritorio" to "desktop", "papelera" to "recycle_bin",
        )

        /** (intención, parámetros) o null si no es una frase propia de la PC. */
        fun parse(n: String, original: String): Pair<String, Map<String, String>>? {
            val p = " $n "
            fun has(vararg w: String) = w.any { p.contains(" $it ") }
            fun re(r: String) = Regex(r).containsMatchIn(n)

            // Capturas, ventanas y pantalla.
            if (has("captura", "capturas", "screenshot", "pantallazo", "captura de pantalla")) return "TAKE_SCREENSHOT" to emptyMap()
            if (re("^(minimiza|minimizame|minimizar|minimizalas|minimizalo)\\b") || has("mostra el escritorio", "mostrame el escritorio", "anda al escritorio", "ir al escritorio", "ver el escritorio")) {
                return "MINIMIZE_WINDOWS" to emptyMap()
            }
            if (re("^(restaura|restaurar|restaurame)\\b") || has("volve a mostrar las ventanas", "mostrame las ventanas", "mostra las ventanas", "abri las ventanas")) {
                return "RESTORE_WINDOWS" to emptyMap()
            }
            if (re("^(apaga|apagame|apagar)\\s+(la |el )?(pantalla|monitor|pantallas)\\b")) return "SCREEN_OFF" to emptyMap()
            if (re("^(bloquea|bloqueame|bloquear|bloqueate)\\b") && (re(COMPUTER) || has("sesion", "pantalla", "windows") || n.matches(Regex("^(bloquea|bloqueate|bloquear)$")))) {
                return "LOCK_SESSION" to emptyMap()
            }

            // Energía (piden confirmación en el teléfono).
            if (re("^(cancela|cancelame|cancelar|frena|para)\\s+(el |la )?(apagado|reinicio|apagada)") || has("no apagues", "no la apagues", "no reinicies")) {
                return "CANCEL_SHUTDOWN" to emptyMap()
            }
            if (re("^(apaga|apagame|apagar)\\s+$COMPUTER")) return "SHUTDOWN" to emptyMap()
            if (re("^(reinicia|reiniciame|reiniciar)\\s+$COMPUTER") || re("^(reinicia|reiniciar)$")) return "RESTART" to emptyMap()
            if (re("^(suspende|suspendeme|suspender)\\s+$COMPUTER") || re("^(pone|poner)\\s+a dormir\\s+$COMPUTER") || has("modo suspension")) return "SLEEP" to emptyMap()

            // Configuración y carpetas.
            val opener = re("^(abri|abrime|abrir|mostrame|mostra|anda a|ir a|entra a|entrar a|llevame a)\\b")
            if (opener || has("abri")) {
                if (has("administrador de tareas", "task manager")) return page("task_manager")
                if (has("panel de control")) return page("control_panel")
                if (has("este equipo", "mi pc", "mi equipo")) return page("this_pc")
                if (has("explorador", "explorador de archivos", "mis archivos", "archivos")) return page("explorer")
                if (has("configuracion", "ajustes", "configuraciones")) {
                    PAGES.entries.firstOrNull { has(it.key) }?.let { return page(it.value) }
                    return page("settings")
                }
                // «Abrí descargas» se entiende solo; «abrí música» podría ser poner música: ahí hace falta «carpeta».
                FOLDERS.entries.firstOrNull { has(it.key) && (has("carpeta") || (it.key in setOf("descargas", "documentos", "papelera", "escritorio") && n.split(' ').size <= 4)) }
                    ?.let { return page(it.value) }
            }

            // Información de la computadora.
            if (has("que hora es", "que hora son", "me decis la hora", "decime la hora", "dime la hora", "hora es")) return "GET_TIME" to emptyMap()
            if (re(COMPUTER) && has("bateria", "carga", "espacio", "disco", "memoria", "estado", "como esta", "como anda")) return "GET_SYSTEM_INFO" to emptyMap()

            // Buscar en Google (sólo si se pide la web; «buscá Despacito» es música).
            Regex("^(?:busca|buscame|buscar|googlea|googleame)\\s+(?:en (?:google|internet|la web)\\s+)?(.+?)(?:\\s+en (?:google|internet|la web))?$").find(n)?.let { m ->
                if (has("google", "internet", "web") || n.startsWith("googlea")) return "WEB_SEARCH" to mapOf("query" to originalTail(original, m.groupValues[1]))
            }

            // Cerrar o mostrar un programa.
            Regex("^(?:cerra|cerrame|cierra|cerrar|cerrale)\\s+(?:el |la |los |las )?(?:programa |aplicacion |app |ventana (?:de )?)?(.+)$").find(n)?.let { m ->
                val name = m.groupValues[1]
                if (name !in setOf("todo", "todas", "las apps", "apps", "aplicaciones", "todas las apps", "todos", "las aplicaciones", "sesion")) {
                    return "CLOSE_APPLICATION" to mapOf("name" to originalTail(original, name))
                }
            }
            Regex("^(?:cambia a|cambiate a|pasa a|anda a|volve a|traeme|trae)\\s+(?:el |la )?(.+)$").find(n)?.let { m ->
                return "FOCUS_APPLICATION" to mapOf("name" to originalTail(original, m.groupValues[1]))
            }
            return null
        }

        private fun page(id: String) = "OPEN_SYSTEM_PAGE" to mapOf("page" to id)

        /** El final del texto original (con mayúsculas y tildes) que corresponde a [normalizedTail]. */
        private fun originalTail(text: String, normalizedTail: String): String {
            val words = normalizedTail.split(' ').size
            return text.trim().trimEnd('.', '?', '!').split(Regex("\\s+")).takeLast(words).joinToString(" ").ifBlank { normalizedTail }
        }
    }
}
