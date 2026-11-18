package com.sara.assistant.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sara.assistant.container
import kotlinx.coroutines.launch

/**
 * SÓLO EN DEBUG: permite probar órdenes en un emulador sin micrófono.
 * adb shell am broadcast -a com.sara.assistant.DEBUG_COMMAND -p com.sara.assistant --es text "subí el volumen"
 * Protegido con el permiso DUMP (sólo adb/sistema pueden enviarlo). No existe en la variante release.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // «--ez listen true»: activa la escucha como si la hubieran llamado (para ver la esfera).
        if (intent.getBooleanExtra("listen", false)) {
            resultData = if (context.container.orchestrator.activate(com.sara.core.model.Trigger.MANUAL)) "OK" else "OCUPADA"
            return
        }
        // «--ez piper true»: baja la voz Daniela ya (prueba en el emulador).
        if (intent.getBooleanExtra("piper", false)) {
            context.container.ensurePiper(force = true)
            return
        }
        // «--es say "…"»: habla con la voz elegida (para medir y probar la voz).
        intent.getStringExtra("say")?.let { phrase ->
            val c = context.container
            c.scope.launch { c.logger.d("Debug", "Voz: ${c.orchestrator.speakPreview(phrase)}") }
            return
        }
        val text = intent.getStringExtra("text") ?: return
        val accepted = context.container.orchestrator.submitText(text)
        resultData = if (accepted) "OK" else "OCUPADA"
    }
}
