package com.sara.assistant.skills

import android.content.Context
import com.sara.assistant.apps.ActivityStarter
import com.sara.assistant.apps.ChatReadResult
import com.sara.assistant.apps.SaraAccessibilityService
import com.sara.assistant.apps.StartOutcome
import com.sara.assistant.media.SaraNotificationListener
import com.sara.core.apps.AppMatcher
import com.sara.core.apps.InstalledApp
import com.sara.core.model.PermissionKind
import com.sara.core.skills.Param
import com.sara.core.skills.ParamType
import com.sara.core.skills.Skill
import com.sara.core.skills.SkillAction
import com.sara.core.skills.SkillResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lee en voz alta un chat de WhatsApp. Si hay mensajes sin leer de ese chat en las notificaciones,
 * los lee de ahí (rápido y sin marcarlos como leídos); si no, abre el chat con Accesibilidad y lee
 * los últimos mensajes de la pantalla.
 */
class WhatsAppReadSkill(private val context: Context, private val starter: ActivityStarter) : Skill {
    override val id = "whatsapp"
    override val description = "Lee los mensajes de un chat de WhatsApp."
    override val actions = listOf(
        SkillAction(
            "leer_chat", "Lee los últimos mensajes de un chat (contacto o grupo).",
            listOf(Param("contacto", ParamType.STRING, "Nombre del chat.")),
            permissions = setOf(PermissionKind.ACCESSIBILITY),
        ),
    )

    override suspend fun run(action: String, args: Map<String, String>): SkillResult {
        val chat = args["contacto"]?.trim().orEmpty()
        if (chat.isEmpty()) return SkillResult.Failed("¿De qué chat?")
        val pkg = PACKAGES.firstOrNull { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }
            ?: return SkillResult.Failed("WhatsApp no está instalado.")
        fromNotifications(pkg, chat)?.let { return it }
        if (!SaraAccessibilityService.isEnabled(context)) {
            return SkillResult.NeedsPermission(PermissionKind.ACCESSIBILITY, "Para leer chats necesito Accesibilidad. Te dejé el botón.")
        }
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return SkillResult.Failed("No pude abrir WhatsApp.")
        when (val outcome = withContext(Dispatchers.Main) { starter.start(launch, "WhatsApp") }) {
            StartOutcome.NeedsTap -> return SkillResult.Partial("Tocá la notificación para abrir WhatsApp.")
            is StartOutcome.Failed -> return SkillResult.Failed("No pude abrir WhatsApp.")
            is StartOutcome.Started -> if (outcome.deviceLocked) return SkillResult.Failed("Desbloqueá el teléfono y pedímelo de nuevo.")
        }
        return when (val r = SaraAccessibilityService.readChat(context, pkg, chat)) {
            ChatReadResult.ChatNotFound -> SkillResult.Failed("No encontré el chat $chat.")
            is ChatReadResult.Failed -> SkillResult.Failed("No pude leer el chat: ${r.message}.")
            is ChatReadResult.Read -> {
                if (r.lines.isEmpty()) return SkillResult.Done("El chat ${r.chat} está vacío.")
                val last = r.lines.takeLast(MAX_LINES)
                var previous: String? = null
                val spoken = last.joinToString(" ") { line ->
                    val who = when {
                        line.fromMe -> "Vos"
                        line.sender != null -> line.sender
                        else -> r.chat
                    }
                    // No se repite el nombre en mensajes seguidos de la misma persona.
                    val prefix = if (who == previous) "" else "$who: "
                    previous = who
                    prefix + line.text.trimEnd('.') + "."
                }
                SkillResult.Done("Últimos mensajes de ${r.chat}. $spoken")
            }
        }
    }

    /** Mensajes sin leer de [chat] que están en las notificaciones de WhatsApp. */
    private fun fromNotifications(pkg: String, chat: String): SkillResult? {
        val listener = SaraNotificationListener.instance ?: return null
        val items = NotificationsSkill.current(context, listener).filter { it.packageName == pkg && it.lines.isNotEmpty() }
        if (items.isEmpty()) return null
        val best = AppMatcher.best(chat, items.mapIndexed { i, it -> InstalledApp(it.title, i.toString()) }, threshold = 0.75) ?: return null
        val item = items[best.id.toInt()]
        val lines = item.lines.takeLast(MAX_LINES).joinToString(" ") { it.trimEnd('.') + "." }
        val count = if (item.lines.size == 1) "un mensaje sin leer" else "${item.lines.size} mensajes sin leer"
        return SkillResult.Done("${item.title} te dejó $count. $lines")
    }

    companion object {
        private const val MAX_LINES = 8
        private val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
