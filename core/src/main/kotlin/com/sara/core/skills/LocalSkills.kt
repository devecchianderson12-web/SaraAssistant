package com.sara.core.skills

import com.sara.core.execution.CommandExecutor
import com.sara.core.execution.ExecutionResult
import com.sara.core.memory.MemoryKeys
import com.sara.core.model.CommandIntent
import com.sara.core.model.VolumeChange

/** Almacenamiento simple de texto (en el teléfono: SharedPreferences). */
interface TextStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class InMemoryTextStore : TextStore {
    private val map = mutableMapOf<String, String>()
    @Synchronized override fun get(key: String) = map[key]
    @Synchronized override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/** Listas persistentes: notas, tareas y compras. Cada elemento puede estar hecho o pendiente. */
class ListBook(private val store: TextStore) {
    data class Item(val text: String, val done: Boolean = false)

    fun items(list: String): List<Item> = store.get(key(list)).orEmpty().lines().filter { it.isNotBlank() }.map { line ->
        Item(line.drop(2).replace("\\n", "\n"), line.startsWith("x "))
    }

    private fun save(list: String, items: List<Item>) =
        store.put(key(list), items.joinToString("\n") { (if (it.done) "x " else "- ") + it.text.replace("\n", "\\n") }.ifEmpty { null })

    @Synchronized fun add(list: String, text: String) = save(list, items(list) + Item(text.trim()))

    /** Quita el elemento que mejor coincide (o null si ninguno se parece). */
    @Synchronized fun remove(list: String, text: String): Item? {
        val all = items(list)
        val match = best(all, text) ?: return null
        save(list, all - match)
        return match
    }

    @Synchronized fun complete(list: String, text: String): Item? {
        val all = items(list)
        val match = best(all, text) ?: return null
        save(list, all.map { if (it == match) it.copy(done = true) else it })
        return match
    }

    @Synchronized fun clear(list: String) = save(list, emptyList())

    private fun best(items: List<Item>, text: String): Item? {
        val candidates = items.mapIndexed { i, it -> com.sara.core.apps.InstalledApp(it.text, i.toString()) }
        val found = com.sara.core.apps.AppMatcher.best(text, candidates, threshold = 0.55) ?: return null
        return items[found.id.toInt()]
    }

    private fun key(list: String) = "list_$list"

    companion object {
        const val NOTES = "notas"
        const val TASKS = "tareas"
        const val SHOPPING = "compras"
    }
}

private fun spoken(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> items.dropLast(1).joinToString(", ") + " y " + items.last()
}

private val TEXT = Param("texto", ParamType.STRING, "El texto del elemento.")

/** Notas, tareas y lista de compras (en el teléfono, sin Internet). */
class ListsSkill(private val book: ListBook) : Skill {
    override val id = "listas"
    override val description = "Notas, tareas pendientes y lista de compras guardadas en el teléfono."
    private val listParam = Param("lista", ParamType.STRING, "Qué lista: notas, tareas o compras.", options = listOf(ListBook.NOTES, ListBook.TASKS, ListBook.SHOPPING))

    override val actions = listOf(
        SkillAction("agregar", "Agrega un elemento a una lista.", listOf(listParam, TEXT), Risk.CHANGE, verification = "Se relee la lista guardada."),
        SkillAction("leer", "Lee los elementos pendientes de una lista.", listOf(listParam)),
        SkillAction("completar", "Marca un elemento como hecho.", listOf(listParam, TEXT), Risk.CHANGE),
        SkillAction("quitar", "Quita un elemento de una lista.", listOf(listParam, TEXT), Risk.CHANGE),
        SkillAction(
            "vaciar", "Borra todos los elementos de una lista.", listOf(listParam), Risk.SENSITIVE,
            confirm = { "¿Borro toda la lista de ${it["lista"]}?" },
        ),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        val list = args.getValue("lista")
        val name = when (list) { ListBook.NOTES -> "notas"; ListBook.TASKS -> "tareas"; else -> "la lista de compras" }
        return when (action) {
            "agregar" -> {
                val text = args.getValue("texto").trim()
                book.add(list, text)
                if (book.items(list).any { it.text == text }) SkillResult.Done(if (list == ListBook.NOTES) "Anotado." else "Agregué $text.")
                else SkillResult.Failed("No pude guardarlo.")
            }
            "leer" -> {
                val pending = book.items(list).filterNot { it.done }.map { it.text }
                if (pending.isEmpty()) SkillResult.Done("No tenés nada en $name.")
                else SkillResult.Done("En $name tenés: ${spoken(pending.take(10))}.")
            }
            "completar" -> book.complete(list, args.getValue("texto"))?.let { SkillResult.Done("Listo, ${it.text} hecho.") }
                ?: SkillResult.Failed("No encontré eso en $name.")
            "quitar" -> book.remove(list, args.getValue("texto"))?.let { SkillResult.Done("Saqué ${it.text}.") }
                ?: SkillResult.Failed("No encontré eso en $name.")
            "vaciar" -> {
                book.clear(list)
                SkillResult.Done("Listo, vacié $name.")
            }
            else -> SkillResult.Failed("No sé hacer eso.")
        }
    }
}

/** Cuentas y conversiones locales. */
class CalculatorSkill : Skill {
    override val id = "calculadora"
    override val description = "Hace cuentas y conversiones de unidades sin Internet."
    override val actions = listOf(
        SkillAction("calcular", "Resuelve una cuenta escrita en palabras o símbolos.", listOf(Param("cuenta", ParamType.STRING, "Por ejemplo «25 por 4» o «15 por ciento de 200»."))),
        SkillAction("convertir", "Convierte unidades (longitud, peso, volumen, temperatura, tiempo, datos).", listOf(Param("pedido", ParamType.STRING, "Por ejemplo «3 pulgadas a centímetros»."))),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult = when (action) {
        "calcular" -> Calculator.answer(args.getValue("cuenta"))?.let { SkillResult.Done(it) } ?: SkillResult.Failed("No entendí la cuenta.")
        "convertir" -> UnitConverter.answer(args.getValue("pedido"))?.let { SkillResult.Done(it) } ?: SkillResult.Failed("No sé convertir eso.")
        else -> SkillResult.Failed("No sé hacer eso.")
    }
}

/**
 * Las órdenes de siempre (música, volumen, apps, WhatsApp, memoria) como habilidades, para que la IA
 * las combine. Pasan por el mismo ejecutor que la voz: misma lógica, mismas verificaciones.
 */
class CommandSkills(private val executor: () -> CommandExecutor) : Skill {
    override val id = "telefono"
    override val description = "Música, volumen, abrir y cerrar apps, WhatsApp y recuerdos."

    override val actions = listOf(
        SkillAction(
            "poner_musica", "Pone música; opcionalmente busca un tema/artista y/o usa una app.",
            listOf(Param("busqueda", ParamType.STRING, "Tema, artista o género.", required = false), Param("app", ParamType.STRING, "App de música.", required = false)),
            Risk.CHANGE, verification = "Confirma que la sesión multimedia quedó reproduciendo.",
        ),
        SkillAction("controlar_musica", "Pausa, sigue, pasa, vuelve o saca la música.", listOf(Param("accion", ParamType.STRING, "Qué hacer.", options = listOf("pausar", "seguir", "siguiente", "anterior", "detener"))), Risk.CHANGE),
        SkillAction("volumen", "Cambia el volumen multimedia.", listOf(
            Param("accion", ParamType.STRING, "Qué hacer.", options = listOf("subir", "bajar", "fijar", "silenciar", "quitar_silencio", "maximo")),
            Param("porcentaje", ParamType.INTEGER, "Porcentaje (0 a 100).", required = false),
        ), Risk.CHANGE, verification = "Relee el volumen del sistema."),
        SkillAction("abrir_app", "Abre una app instalada por su nombre.", listOf(Param("nombre", ParamType.STRING, "Nombre de la app.")), Risk.CHANGE),
        SkillAction("cerrar_apps", "Cierra las apps en segundo plano (necesita Accesibilidad).", risk = Risk.CHANGE),
        SkillAction(
            "whatsapp", "Manda un WhatsApp a un contacto o grupo.",
            listOf(
                Param("destino", ParamType.STRING, "Contacto o grupo."), Param("mensaje", ParamType.STRING, "Texto a mandar."),
                Param("es_grupo", ParamType.BOOLEAN, "true si es un grupo o chat que no está en contactos.", required = false),
            ),
            Risk.SENSITIVE, verification = "Toca «Enviar» y confirma que la caja de texto quedó vacía.",
            confirm = { "¿Le mando «${it["mensaje"]}» a ${it["destino"]}?" },
        ),
        SkillAction("recordar", "Guarda un recuerdo o preferencia del usuario (sólo si lo pidió).", listOf(
            Param("clave", ParamType.STRING, "«${MemoryKeys.MUSIC_APP}», «${MemoryKeys.ROUTINE_PREFIX}<frase>» o «${MemoryKeys.NOTE_PREFIX}<tema>»."),
            Param("valor", ParamType.STRING, "Lo que hay que recordar."),
        ), Risk.CHANGE),
        SkillAction("olvidar", "Borra un recuerdo.", listOf(Param("clave", ParamType.STRING, "Clave o tema.")), Risk.SENSITIVE, confirm = { "¿Olvido «${it["clave"]}»?" }),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        val intent = when (action) {
            "poner_musica" -> CommandIntent.PlayMusic(args["busqueda"]?.ifBlank { null }, args["app"]?.ifBlank { null })
            "controlar_musica" -> when (args["accion"]) {
                "pausar" -> CommandIntent.Pause
                "seguir" -> CommandIntent.Resume
                "siguiente" -> CommandIntent.Next
                "anterior" -> CommandIntent.Previous
                else -> CommandIntent.StopMusic
            }
            "volumen" -> {
                val pct = args["porcentaje"]?.toIntOrNull()
                CommandIntent.AdjustVolume(
                    when (args["accion"]) {
                        "subir" -> VolumeChange.Increase(pct)
                        "bajar" -> VolumeChange.Decrease(pct)
                        "fijar" -> VolumeChange.SetTo(pct ?: return SkillResult.Failed("Falta el porcentaje."))
                        "silenciar" -> VolumeChange.Mute
                        "quitar_silencio" -> VolumeChange.Unmute
                        else -> VolumeChange.Max
                    },
                )
            }
            "abrir_app" -> CommandIntent.OpenApp(args.getValue("nombre"))
            "cerrar_apps" -> CommandIntent.CloseBackgroundApps
            "whatsapp" -> CommandIntent.SendMessage(
                args.getValue("destino"), args.getValue("mensaje"),
                group = args["es_grupo"]?.lowercase() in setOf("true", "si", "sí"),
            )
            "recordar" -> CommandIntent.Remember(args.getValue("clave"), args.getValue("valor"))
            "olvidar" -> CommandIntent.Forget(args.getValue("clave"))
            else -> return SkillResult.Failed("No sé hacer eso.")
        }
        var result: ExecutionResult = executor().execute(intent)
        // La confirmación ya la dio el usuario en este paso (la pidió el registro de habilidades).
        result.confirmation?.let { result = it.onConfirm() }
        if (result.followUp != null) return SkillResult.Failed("Falta información para hacerlo.")
        result.needsPermission?.let { return SkillResult.NeedsPermission(it, result.response) }
        return if (result.success) SkillResult.Done(result.response) else SkillResult.Failed(result.response)
    }
}
