package com.sara.assistant.ai

import com.sara.assistant.settings.AiProvider
import com.sara.core.memory.ConversationalAssistant

/** Elige la IA según Ajustes; si la elegida no tiene clave, usa la otra si está configurada. */
class AiRouter(
    private val provider: () -> AiProvider,
    val google: GeminiAssistant,
    val claude: ClaudeAssistant,
) : ConversationalAssistant {
    private fun current(): ConversationalAssistant? {
        val preferred = if (provider() == AiProvider.GOOGLE) google else claude
        val other = if (preferred === google) claude else google
        return preferred.takeIf { it.isAvailable() } ?: other.takeIf { it.isAvailable() }
    }

    override fun isAvailable(): Boolean = current() != null

    override suspend fun reply(text: String): String =
        current()?.reply(text) ?: "Para eso activá la IA en Ajustes."

    fun reset() {
        google.reset()
        claude.reset()
    }
}
