package com.sara.assistant.apps

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sara.assistant.SaraApp
import com.sara.core.execution.CloseAppsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Android no deja que una app cierre a otras. La única forma es la misma que haría el usuario:
 * abrir «Recientes» y tocar «Cerrar todo». Este servicio de accesibilidad sólo hace eso cuando se
 * lo piden por voz; no lee ni guarda lo que hay en pantalla.
 */
class SaraAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private suspend fun clearRecents(): CloseAppsResult = withContext(Dispatchers.Main) {
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return@withContext CloseAppsResult.DeviceLocked
        if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) return@withContext CloseAppsResult.Failed("no se abrió Recientes")
        // Recientes tarda en dibujarse; se busca el botón varias veces.
        repeat(12) { attempt ->
            delay(if (attempt == 0) 600 else 250)
            val button = findClearAll()
            if (button != null) {
                val clicked = button.clickableSelfOrParent()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (clicked) {
                    delay(400)
                    // Algunos teléfonos se quedan en Recientes vacío: volver al inicio.
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return@withContext CloseAppsResult.Closed
                }
            }
        }
        log("No encontré «Cerrar todo». En pantalla: ${visibleLabels().take(40)}")
        performGlobalAction(GLOBAL_ACTION_HOME)
        CloseAppsResult.Failed("no encontré el botón para cerrar todo")
    }

    /** Espera el chat de WhatsApp y toca «Enviar». */
    private suspend fun tapSend(pkg: String): Boolean = withContext(Dispatchers.Main) {
        repeat(30) { attempt ->
            delay(if (attempt == 0) 700 else 250)
            val nodes = windows.mapNotNull { it.root }.filter { it.packageName == pkg }.flatMap { it.descendants() }
            val send = nodes.firstOrNull { it.viewIdResourceName == "$pkg:id/send" }
                ?: nodes.firstOrNull { n -> n.labels().any { it == "enviar" || it == "send" } }
            if (send != null && send.clickableSelfOrParent()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                log("Mensaje enviado")
                return@withContext true
            }
        }
        log("No encontré el botón «Enviar» de WhatsApp")
        false
    }

    private fun roots(): List<AccessibilityNodeInfo> =
        windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }

    private fun findClearAll(): AccessibilityNodeInfo? {
        val all = roots().flatMap { it.descendants() }
        return all.firstOrNull { node -> node.labels().any { label -> CLEAR_ALL.matches(label) } }
            ?: all.firstOrNull { node -> node.viewIdResourceName?.let { CLEAR_ID.containsMatchIn(it) } == true }
    }

    private fun visibleLabels(): List<String> = roots().flatMap { it.descendants() }
        .flatMap { n -> n.labels() + listOfNotNull(n.viewIdResourceName) }.distinct()

    private fun AccessibilityNodeInfo.labels(): List<String> =
        listOfNotNull(text?.toString(), contentDescription?.toString()).map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun AccessibilityNodeInfo.descendants(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque(listOf(this))
        while (stack.isNotEmpty() && out.size < 800) {
            val node = stack.removeLast()
            out += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(stack::addLast)
        }
        return out
    }

    private fun AccessibilityNodeInfo.clickableSelfOrParent(): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = this
        repeat(5) {
            if (node?.isClickable == true) return node
            node = node?.parent
        }
        return null
    }

    private fun log(message: String) {
        SaraApp.containerOrNull(this)?.logger?.d(TAG, message)
    }

    companion object {
        private const val TAG = "CerrarApps"

        /** Textos del botón en distintas marcas (Xiaomi, Samsung, Motorola, Pixel) y en inglés. */
        private val CLEAR_ALL = Regex(
            "(cerrar|borrar|limpiar|eliminar|quitar|descartar) ?(todo|todas|todas las apps|todas las aplicaciones)|" +
                "cerrar apps|limpiar memoria|limpiar|clear all|close all|clear|dismiss all|remove all",
        )
        private val CLEAR_ID = Regex("clear_?all|clearAnimView|clear_button|close_all|clear_recents|dismiss_all", RegexOption.IGNORE_CASE)

        @Volatile
        private var instance: SaraAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { it.startsWith(context.packageName + "/") }
        }

        suspend fun clickSend(context: Context, pkg: String): Boolean {
            val service = instance ?: return false
            if (!isEnabled(context)) return false
            return service.tapSend(pkg)
        }

        suspend fun closeRecentApps(context: Context): CloseAppsResult {
            val service = instance
            if (service == null || !isEnabled(context)) return CloseAppsResult.NeedsAccessibility
            return service.clearRecents()
        }
    }
}
