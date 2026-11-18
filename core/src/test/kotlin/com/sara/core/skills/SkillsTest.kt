package com.sara.core.skills

import com.sara.core.execution.DefaultCommandExecutor
import com.sara.core.execution.MediaAction
import com.sara.core.execution.MediaActionResult
import com.sara.core.execution.MediaControl
import com.sara.core.execution.VolumeControl
import com.sara.core.execution.VolumeState
import com.sara.core.interpreter.CompositeCommandInterpreter
import com.sara.core.interpreter.RuleBasedCommandInterpreter
import com.sara.core.model.CommandIntent
import com.sara.core.model.PermissionKind
import com.sara.core.model.VolumeChange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SkillsTest {
    private val now = LocalDateTime.of(2026, 11, 17, 10, 0)

    private fun intentOf(text: String) = RuleBasedCommandInterpreter().interpretNow(text).intent

    @Test
    fun `pantalla, notificaciones y chats`() {
        fun sk(text: String) = intentOf(text) as? CommandIntent.Skill
        assertEquals("bloquear", sk("Sara, bloqueá la pantalla")?.action)
        assertEquals("bloquear", sk("apagá la pantalla")?.action)
        assertEquals("bloquear", sk("bloqueá el celular")?.action)
        assertEquals("desbloquear", sk("Sara desbloqueá el celular")?.action)
        assertEquals("desbloquear", sk("desbloqueá la pantalla")?.action)
        assertEquals(CommandIntent.Skill("notificaciones", "leer", mapOf("app" to "whatsapp")), sk("Sara, léeme las notificaciones de WhatsApp"))
        assertEquals(CommandIntent.Skill("notificaciones", "leer", emptyMap()), sk("leeme las notificaciones"))
        assertEquals(CommandIntent.Skill("notificaciones", "borrar", emptyMap()), sk("borrá las notificaciones"))
        assertEquals(CommandIntent.Skill("notificaciones", "borrar", mapOf("app" to "whatsapp")), sk("Sara, eliminá las notificaciones de WhatsApp"))
        assertEquals(CommandIntent.Skill("whatsapp", "leer_chat", mapOf("contacto" to "Notas")), sk("Sara, leeme el chat de Notas"))
        assertEquals(CommandIntent.Skill("whatsapp", "leer_chat", mapOf("contacto" to "mamá")), sk("leeme los mensajes de mi mamá"))
        assertEquals(CommandIntent.Skill("whatsapp", "leer_chat", mapOf("contacto" to "Juan")), sk("qué me escribió Juan"))
    }

    @Test
    fun `horas y duraciones`() {
        assertEquals(600L, TimeParser.duration("un temporizador de 10 minutos"))
        assertEquals(5_400L, TimeParser.duration("una hora y media"))
        assertEquals(TimeParser.Clock(7, 30), TimeParser.clock("poné la alarma a las 7 y media"))
        assertEquals(TimeParser.Clock(19, 0), TimeParser.clock("a las 7 de la tarde"))
        assertEquals(TimeParser.Clock(21, 15), TimeParser.clock("a las 21:15"))
        assertEquals(now.plusMinutes(20), TimeParser.moment("recordame en 20 minutos sacar la ropa", now))
        assertEquals(LocalDateTime.of(2026, 11, 18, 9, 0), TimeParser.moment("mañana a las 9 llamar al médico", now))
        // «a las 8» cuando ya son las 10: 20 hs de hoy.
        assertEquals(LocalDateTime.of(2026, 11, 17, 20, 0), TimeParser.moment("a las 8", now))
    }

    @Test
    fun `cuentas y conversiones`() {
        assertEquals("Da 100.", Calculator.answer("cuánto es 25 por 4"))
        assertEquals("Da 30.", Calculator.answer("15 por ciento de 200"))
        assertEquals("Da 350.", Calculator.answer("cuánto es el 35 porciento de 1000"))
        assertEquals("Da 350.", Calculator.answer("Sara, cuánto es el 35% de 1000"))
        assertEquals("Da 9.", Calculator.answer("raíz de 81"))
        assertEquals("Da 1024.", Calculator.answer("2 elevado a 10"))
        assertEquals("Da 1532.", Calculator.answer("cuánto es mil quinientos treinta y dos más cero"))
        assertNull(Calculator.answer("poné el volumen al 50"))
        assertEquals("3 pulgadas son 7,62 centímetros.", UnitConverter.answer("cuántos centímetros son 3 pulgadas"))
        assertEquals("100 grados Celsius son 212 grados Fahrenheit.", UnitConverter.answer("100 grados a fahrenheit"))
        assertNull(UnitConverter.answer("poné 5 temas a todo volumen"))
    }

    @Test
    fun `pedidos de habilidades`() {
        assertEquals(CommandIntent.Skill("alarma", "alarma", mapOf("hora" to "7", "minuto" to "30")), intentOf("despertame a las 7 y media"))
        assertEquals(CommandIntent.Skill("alarma", "temporizador", mapOf("segundos" to "600")), intentOf("poné un temporizador de 10 minutos"))
        val reminder = SkillParser.parse("recordame mañana a las 9 llamar al médico", now)!!
        assertEquals("recordatorios", reminder.skill)
        assertEquals("llamar al médico", reminder.args["texto"])
        assertEquals("2026-11-18T09:00", reminder.args["cuando"])
        assertEquals(CommandIntent.Skill("clima", "consultar", mapOf("lugar" to "Córdoba", "dia" to "manana")), intentOf("cómo va a estar el clima mañana en Córdoba"))
        assertEquals(CommandIntent.Skill("dispositivo", "linterna", mapOf("estado" to "prender")), intentOf("prendé la linterna"))
        assertEquals(CommandIntent.Skill("dispositivo", "linterna", mapOf("estado" to "apagar")), intentOf("apagá la linterna"))
        assertEquals(CommandIntent.Skill("dispositivo", "brillo", mapOf("accion" to "fijar", "porcentaje" to "40")), intentOf("poné el brillo al 40"))
        assertEquals(CommandIntent.Skill("dispositivo", "bateria"), intentOf("¿cuánta batería me queda?"))
        assertEquals(CommandIntent.Skill("llamadas", "llamar", mapOf("contacto" to "mama")), intentOf("llamá a mamá"))
        assertEquals(CommandIntent.Skill("calendario", "ver", mapOf("dia" to "manana")), intentOf("qué tengo mañana"))
        assertEquals(CommandIntent.Skill("mapas", "navegar", mapOf("destino" to "Plaza de Mayo")), intentOf("llevame a Plaza de Mayo"))
        assertEquals(CommandIntent.Skill("mapas", "cerca", mapOf("lugar" to "farmacias")), intentOf("farmacias cerca de mí"))
        assertEquals(CommandIntent.Skill("traductor", "traducir", mapOf("texto" to "buenos días", "idioma" to "ingles")), intentOf("cómo se dice buenos días en inglés"))
        assertEquals(CommandIntent.Skill("listas", "agregar", mapOf("lista" to "compras", "texto" to "leche y pan")), intentOf("agregá leche y pan a la lista de compras"))
        assertEquals(CommandIntent.Skill("listas", "leer", mapOf("lista" to "compras")), intentOf("qué tengo que comprar"))
        assertEquals(CommandIntent.Skill("listas", "agregar", mapOf("lista" to "notas", "texto" to "la clave del wifi es 1234")), intentOf("anotá la clave del wifi es 1234"))
        assertEquals(CommandIntent.Skill("dispositivo", "bluetooth", mapOf("estado" to "conectar", "dispositivo" to "auriculares JBL")), intentOf("conectate a los auriculares JBL"))
        assertEquals(CommandIntent.Skill("dispositivo", "bluetooth", mapOf("estado" to "prender")), intentOf("prendé el bluetooth"))
        val routine = SkillParser.parse("todos los días a las 8 decime el clima", now)!!
        assertEquals("programar", routine.action)
        assertEquals("decime el clima", routine.args["orden"])
    }

    @Test
    fun `no le roba ordenes a las reglas de siempre`() {
        assertEquals(CommandIntent.PlayMusic("soda stereo"), intentOf("poné música de Soda Stereo"))
        assertEquals(CommandIntent.AdjustVolume(VolumeChange.SetTo(50)), intentOf("poné el volumen al 50"))
        assertEquals(CommandIntent.OpenApp("youtube"), intentOf("abrí YouTube"))
        assertEquals(CommandIntent.StopMusic, intentOf("sacá la música"))
        assertEquals(CommandIntent.CloseBackgroundApps, intentOf("cerrá las apps"))
    }

    @Test
    fun `varias ordenes seguidas`() = runBlocking {
        val composite = CompositeCommandInterpreter(RuleBasedCommandInterpreter())
        val intent = composite.interpret("poné música y bajá el volumen al 30").intent
        assertEquals(
            CommandIntent.Sequence(listOf(CommandIntent.PlayMusic(null, null), CommandIntent.AdjustVolume(VolumeChange.SetTo(30)))),
            intent,
        )
        // El texto de un mensaje no se parte.
        assertTrue(composite.interpret("mandale un whatsapp a juan que diga hola y chau").intent is CommandIntent.SendMessage)
        // Una alarma «a las 7 y media» no se parte.
        assertTrue(composite.interpret("poné una alarma a las 7 y media").intent is CommandIntent.Skill)
        // La misma orden repetida cuenta una vez.
        assertEquals(CommandIntent.CloseBackgroundApps, composite.interpret("sacá la música, pará y cerrame las aplicaciones en segundo plano").intent.let {
            (it as CommandIntent.Sequence).steps.last()
        })
        // Sin IA, un pedido desconocido sigue siendo desconocido; con planificador, va al plan.
        assertTrue(composite.interpret("organizame la semana").intent is CommandIntent.Unknown)
        val withPlanner = CompositeCommandInterpreter(RuleBasedCommandInterpreter(), plannerAvailable = { true })
        assertEquals(CommandIntent.Plan("organizame la semana"), withPlanner.interpret("organizame la semana").intent)
    }

    private class FakeSkill(private val result: SkillResult) : Skill {
        var runs = 0
        override val id = "prueba"
        override val description = "Prueba"
        override val actions = listOf(
            SkillAction("consultar", "Consulta", listOf(Param("n", ParamType.INTEGER, "Número"))),
            SkillAction("borrar", "Borra", risk = Risk.SENSITIVE, confirm = { "¿Borro todo?" }),
            SkillAction("permiso", "Necesita permiso", permissions = setOf(PermissionKind.LOCATION)),
        )
        override suspend fun run(action: String, args: Map<String, String>): SkillResult {
            runs++
            return result
        }
    }

    @Test
    fun `registro valida, pide permisos y reintenta solo consultas`() = runBlocking {
        val failing = FakeSkill(SkillResult.Failed("se cortó", retryable = true))
        val registry = SkillRegistry(listOf(failing), isGranted = { it != PermissionKind.LOCATION })
        assertEquals("Falta «n».", registry.run("prueba", "consultar", emptyMap()).message)
        assertEquals("«n» no es válido.", registry.run("prueba", "consultar", mapOf("n" to "dos")).message)
        assertEquals(0, failing.runs)
        assertTrue(registry.run("prueba", "permiso", emptyMap()) is SkillResult.NeedsPermission)
        registry.run("prueba", "consultar", mapOf("n" to "2"))
        assertEquals(2, failing.runs) // una consulta se reintenta una vez
        registry.run("prueba", "borrar", emptyMap())
        assertEquals(3, failing.runs) // una acción sensible no se reintenta
    }

    private object NoMedia : MediaControl {
        override suspend fun perform(action: MediaAction, query: String?, app: String?) = MediaActionResult.Done("Música")
    }

    private object NoVolume : VolumeControl {
        override fun state() = VolumeState(5, 0, 15)
        override fun setIndex(index: Int) = true
        override fun setMuted(muted: Boolean) = true
    }

    @Test
    fun `acciones sensibles piden confirmacion y las listas funcionan`() = runBlocking {
        val skill = FakeSkill(SkillResult.Done("Borrado."))
        val book = ListBook(InMemoryTextStore())
        val executor = DefaultCommandExecutor(NoMedia, NoVolume, skills = SkillRegistry(listOf(skill, ListsSkill(book), CalculatorSkill())))

        val ask = executor.execute(CommandIntent.Skill("prueba", "borrar"))
        assertEquals("¿Borro todo?", ask.confirmation?.question)
        assertEquals(0, skill.runs)
        assertEquals("Borrado.", ask.confirmation!!.onConfirm().response)

        assertEquals("Agregué leche.", executor.execute(CommandIntent.Skill("listas", "agregar", mapOf("lista" to "compras", "texto" to "leche"))).response)
        executor.execute(CommandIntent.Skill("listas", "agregar", mapOf("lista" to "compras", "texto" to "pan")))
        assertEquals("En la lista de compras tenés: leche y pan.", executor.execute(CommandIntent.Skill("listas", "leer", mapOf("lista" to "compras"))).response)
        executor.execute(CommandIntent.Skill("listas", "completar", mapOf("lista" to "compras", "texto" to "leche")))
        assertEquals("En la lista de compras tenés: pan.", executor.execute(CommandIntent.Skill("listas", "leer", mapOf("lista" to "compras"))).response)

        assertEquals("Da 100.", executor.execute(CommandIntent.Skill("calculadora", "calcular", mapOf("cuenta" to "25 por 4"))).response)

        val sequence = executor.execute(CommandIntent.Sequence(listOf(CommandIntent.Skill("calculadora", "calcular", mapOf("cuenta" to "2 más 2")), CommandIntent.Skill("prueba", "borrar"))))
        assertNotNull(sequence.confirmation)
        val done = sequence.confirmation!!.onConfirm()
        assertEquals("Da 4. Borrado.", done.response)
        assertTrue(done.success)
        assertFalse(executor.execute(CommandIntent.Skill("nada", "nada")).success)
    }
}
