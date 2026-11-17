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
import kotlin.coroutines.resume

sealed interface ChatSendResult {
    data object Sent : ChatSendResult
    data object ChatNotFound : ChatSendResult
    data class Failed(val message: String) : ChatSendResult
}

/**
 * Android no deja que una app cierre a otras. La única forma es la misma que haría el usuario:
 * abrir «Recientes» y tocar «Cerrar todo». Este servicio de accesibilidad sólo hace eso cuando se
 * lo piden por voz; no lee ni guarda lo que hay en pantalla.
 */
class SaraAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        log("Accesibilidad conectada")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
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

    private fun pkgNodes(pkg: String): List<AccessibilityNodeInfo> =
        (windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow))
            .filter { it.packageName == pkg }.distinctBy { it.windowId }.flatMap { it.descendants() }

    private fun AccessibilityNodeInfo.click(): Boolean =
        clickableSelfOrParent()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    private fun AccessibilityNodeInfo.type(text: String): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Chats que no están en contactos (grupos, «Notas»…): igual que lo haría el usuario, busca el
     * chat en WhatsApp, lo abre, escribe el mensaje y toca «Enviar». WhatsApp ya debe estar abierto.
     */
    private suspend fun sendBySearch(pkg: String, chat: String, message: String): ChatSendResult = withContext(Dispatchers.Main) {
        // 1. Llegar a la lista de chats (si WhatsApp quedó dentro de un chat, se vuelve atrás).
        if (!reachChatList(pkg)) {
            log("WhatsApp no mostró la lista de chats. En pantalla: ${screenSummary(pkg)}")
            return@withContext ChatSendResult.Failed("WhatsApp no respondió")
        }
        // 2. Abrir el chat: primero en la lista visible, después en «Archivados» y por último buscándolo.
        val opened = openFromList(pkg, chat) || openFromArchived(pkg, chat) || openFromSearch(pkg, chat)
        if (!opened) {
            log("No encontré «$chat». En pantalla: ${screenSummary(pkg)}")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return@withContext ChatSendResult.ChatNotFound
        }

        // 3. Escribir y enviar.
        for (attempt in 0 until 12) {
            delay(400)
            val nodes = pkgNodes(pkg)
            val entry = nodes.firstOrNull { it.viewIdResourceName == "$pkg:id/entry" }
                ?: nodes.firstOrNull { it.isEditable && !it.isSearchLike() }
                ?: continue
            if (!entry.type(message)) continue
            delay(300)
            val send = pkgNodes(pkg).let { all ->
                all.firstOrNull { it.viewIdResourceName == "$pkg:id/send" }
                    ?: all.firstOrNull { n -> n.labels().any { it == "enviar" || it == "send" } }
            } ?: continue
            if (send.click()) {
                log("Mensaje enviado a «$chat» por búsqueda")
                return@withContext ChatSendResult.Sent
            }
        }
        ChatSendResult.Failed("No pude escribir en el chat").also { log(it.message) }
    }

    private fun screenSummary(pkg: String) =
        pkgNodes(pkg).flatMap { it.labels() + listOfNotNull(it.viewIdResourceName?.substringAfter(":id/")) }.distinct().take(50)

    private suspend fun reachChatList(pkg: String): Boolean {
        repeat(10) { attempt ->
            delay(if (attempt == 0) 1_000 else 450)
            val nodes = pkgNodes(pkg)
            if (nodes.isEmpty()) return@repeat
            // Dentro de un chat o de la búsqueda: atrás.
            if (nodes.any { it.viewIdResourceName == "$pkg:id/entry" || (it.isEditable && it.isFocused) }) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return@repeat
            }
            return true
        }
        return pkgNodes(pkg).isNotEmpty()
    }

    /** Busca [chat] entre los nombres visibles y lo abre; baja la lista unas veces si no aparece. */
    private suspend fun openFromList(pkg: String, chat: String, scrolls: Int = 2): Boolean {
        repeat(scrolls + 1) { round ->
            val rows = chatNames(pkg)
            val best = com.sara.core.apps.AppMatcher.best(chat, rows.mapIndexed { i, n -> com.sara.core.apps.InstalledApp(n.text.toString(), i.toString()) })
            if (best != null && tap(rows[best.id.toInt()])) return true
            if (round < scrolls) {
                val list = pkgNodes(pkg).firstOrNull { it.isScrollable } ?: return false
                list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(500)
            }
        }
        return false
    }

    private fun chatNames(pkg: String): List<AccessibilityNodeInfo> {
        val texts = pkgNodes(pkg).filter { !it.isEditable && !it.text.isNullOrBlank() }
        return texts.filter { n ->
            n.viewIdResourceName?.let { id -> "contact_name" in id || "conversations_row" in id || "contact_row" in id } == true
        }.ifEmpty { texts.filterNot { it.isSearchLike() || ARCHIVED.matches(it.text.toString().trim().lowercase()) } }
    }

    private suspend fun openFromArchived(pkg: String, chat: String): Boolean {
        // «Archivados» está arriba de la lista; si no se ve, se sube.
        var archived = pkgNodes(pkg).firstOrNull { n -> n.labels().any { ARCHIVED.matches(it) } }
        if (archived == null) {
            pkgNodes(pkg).firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            delay(400)
            archived = pkgNodes(pkg).firstOrNull { n -> n.labels().any { ARCHIVED.matches(it) } }
        }
        if (archived == null || !tap(archived)) return false
        delay(800)
        if (openFromList(pkg, chat, scrolls = 4)) return true
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(500)
        return false
    }

    private suspend fun openFromSearch(pkg: String, chat: String): Boolean {
        var clicked = false
        repeat(8) {
            val nodes = pkgNodes(pkg)
            val field = if (clicked) nodes.firstOrNull { it.isEditable } else null
            if (field != null && field.type(chat)) {
                repeat(6) {
                    delay(600)
                    if (openFromList(pkg, chat, scrolls = 0)) return true
                }
                return false
            }
            val button = nodes.firstOrNull { it.viewIdResourceName in SEARCH_IDS.map { id -> "$pkg:id/$id" } }
                ?: nodes.firstOrNull { it.isSearchLike() }
            if (button != null) clicked = tap(button) || button.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || clicked
            delay(500)
        }
        return false
    }

    /** Toca el elemento: con la acción de accesibilidad o, si no es tocable, con un gesto en su centro. */
    private suspend fun tap(node: AccessibilityNodeInfo): Boolean {
        if (node.click()) return true
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val path = android.graphics.Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val sent = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!sent && cont.isActive) cont.resume(false)
        }
    }

    private fun AccessibilityNodeInfo.isSearchLike(): Boolean =
        viewIdResourceName?.contains("search", ignoreCase = true) == true ||
            labels().any { SEARCH_LABEL.containsMatchIn(it) }

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
        listOfNotNull(text?.toString(), contentDescription?.toString(), hintText?.toString())
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }

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

        /** Activada en Ajustes › Accesibilidad (según el sistema o la lista de servicios en uso). */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val running = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                ?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName } == true
            if (running) return true
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { it.trim().startsWith(context.packageName + "/") }
        }

        /** Activada y conectada con Sara (algunos teléfonos la dejan «activa» pero detenida). */
        fun isWorking(context: Context): Boolean = instance != null && isEnabled(context)

        /** Tras reiniciar Sara o actualizarla, el sistema tarda un momento en reconectar el servicio. */
        private suspend fun awaitInstance(context: Context): SaraAccessibilityService? {
            if (!isEnabled(context)) return null
            repeat(15) {
                instance?.let { return it }
                delay(200)
            }
            return instance
        }

        private val SEARCH_LABEL = Regex("busc|search|pregunt[aá]r? a meta")
        private val ARCHIVED = Regex("archivad[oa]s?(\\s*\\(\\d+\\))?|archived(\\s*\\(\\d+\\))?|\\d+\\s+archivad[oa]s?")
        private val SEARCH_IDS = listOf("menuitem_search", "my_search_bar", "search_bar", "search_bar_inner", "search_input", "search_src_text")

        suspend fun sendToChat(context: Context, pkg: String, chat: String, message: String): ChatSendResult {
            val service = awaitInstance(context) ?: return ChatSendResult.Failed("Accesibilidad detenida")
            return service.sendBySearch(pkg, chat, message)
        }

        suspend fun clickSend(context: Context, pkg: String): Boolean {
            val service = awaitInstance(context) ?: return false
            return service.tapSend(pkg)
        }

        suspend fun closeRecentApps(context: Context): CloseAppsResult {
            if (!isEnabled(context)) return CloseAppsResult.NeedsAccessibility
            val service = awaitInstance(context) ?: return CloseAppsResult.NotResponding
            return service.clearRecents()
        }
    }
}
