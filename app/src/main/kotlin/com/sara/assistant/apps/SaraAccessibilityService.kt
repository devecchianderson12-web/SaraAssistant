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

    // ---------------------------------------------------------------- WhatsApp
    // Se usan búsquedas puntuales (por id o texto): recorrer toda la pantalla nodo por nodo es lento.

    private fun appRoots(pkg: String): List<AccessibilityNodeInfo> =
        (windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow))
            .filter { it.packageName == pkg }.distinctBy { it.windowId }

    private fun byId(pkg: String, id: String): List<AccessibilityNodeInfo> =
        appRoots(pkg).flatMap { it.findAccessibilityNodeInfosByViewId("$pkg:id/$id").orEmpty() }

    private fun byText(pkg: String, text: String): List<AccessibilityNodeInfo> =
        appRoots(pkg).flatMap { it.findAccessibilityNodeInfosByText(text).orEmpty() }

    /** Sólo para el diagnóstico cuando algo falla. */
    private fun pkgNodes(pkg: String): List<AccessibilityNodeInfo> = appRoots(pkg).flatMap { it.descendants() }

    /** Espera el chat de WhatsApp y toca «Enviar». */
    private suspend fun tapSend(pkg: String): Boolean = withContext(Dispatchers.Main) {
        repeat(25) { attempt ->
            delay(if (attempt == 0) 600 else 250)
            val send = byId(pkg, "send").firstOrNull() ?: byText(pkg, "Enviar").firstOrNull { it.isVisibleToUser }
            if (send != null && tap(send)) {
                log("Mensaje enviado")
                return@withContext true
            }
        }
        log("No encontré el botón «Enviar» de WhatsApp")
        false
    }

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
     * Chats que no están en contactos (grupos, «Notas»…): igual que lo haría el usuario, abre el chat
     * (de la lista, de «Archivados» o buscándolo), escribe el mensaje y toca «Enviar».
     */
    private suspend fun sendBySearch(pkg: String, chat: String, message: String): ChatSendResult = withContext(Dispatchers.Main) {
        if (!reachChatList(pkg)) {
            log("WhatsApp no mostró la lista de chats. En pantalla: ${screenSummary(pkg)}")
            return@withContext ChatSendResult.Failed("WhatsApp no respondió")
        }
        val opened = openFromList(pkg, chat) || openFromArchived(pkg, chat) || openFromSearch(pkg, chat)
        if (!opened) {
            log("No encontré «$chat». En pantalla: ${screenSummary(pkg)}")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return@withContext ChatSendResult.ChatNotFound
        }
        repeat(15) {
            delay(300)
            val entry = byId(pkg, "entry").firstOrNull() ?: return@repeat
            if (!entry.type(message)) return@repeat
            delay(250)
            val send = byId(pkg, "send").firstOrNull() ?: byText(pkg, "Enviar").firstOrNull { it.isVisibleToUser } ?: return@repeat
            if (tap(send)) {
                log("Mensaje enviado a «$chat»")
                return@withContext ChatSendResult.Sent
            }
        }
        ChatSendResult.Failed("No pude escribir en el chat").also { log("${it.message}. En pantalla: ${screenSummary(pkg)}") }
    }

    // ---------------------------------------------------------------- Ajustes del sistema
    // Android no deja que una app prenda el Bluetooth/Wi-Fi ni elija a qué conectarse; Sara lo hace
    // como el usuario: en la pantalla de Ajustes toca el interruptor o el nombre del dispositivo.

    private fun activeNodes(): List<AccessibilityNodeInfo> =
        (listOfNotNull(rootInActiveWindow) + windows.mapNotNull { it.root }).distinctBy { it.windowId }
            .filter { it.packageName != packageName }.flatMap { it.descendants() }

    private suspend fun toggleSwitch(on: Boolean): Boolean = withContext(Dispatchers.Main) {
        repeat(15) { attempt ->
            delay(if (attempt == 0) 900 else 300)
            val sw = activeNodes().firstOrNull { n ->
                n.isCheckable && n.isVisibleToUser && n.className?.toString()?.let { "Switch" in it || "Toggle" in it || "CheckBox" in it } == true
            } ?: return@repeat
            if (sw.isChecked == on) return@withContext true
            // Mientras cambia de estado el interruptor queda deshabilitado: no tocarlo (lo revertiría).
            if (!sw.isEnabled) return@repeat
            if (tap(sw)) {
                delay(700)
                return@withContext true
            }
        }
        log("No encontré el interruptor. En pantalla: ${activeNodes().flatMap { it.labels() }.distinct().take(40)}")
        false
    }

    /** Toca el elemento cuyo texto más se parece a [name] (baja la lista si no está a la vista). */
    private suspend fun tapLabel(name: String): String? = withContext(Dispatchers.Main) {
        repeat(4) { round ->
            delay(if (round == 0) 900 else 500)
            // Sólo nombres de dispositivos: nunca el interruptor ni las filas fijas de la pantalla.
            val texts = activeNodes().filter { n ->
                !n.text.isNullOrBlank() && n.isVisibleToUser && !n.isCheckable &&
                    n.text.toString().trim().lowercase() !in FIXED_LABELS
            }
            val candidates = texts.mapIndexed { i, n -> com.sara.core.apps.InstalledApp(n.text.toString(), i.toString()) }
            val best = com.sara.core.apps.AppMatcher.best(name, candidates, threshold = 0.7)
            if (best != null && tap(texts[best.id.toInt()])) return@withContext best.label
            activeNodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        log("No encontré «$name». En pantalla: ${activeNodes().mapNotNull { it.text?.toString() }.distinct().take(40)}")
        null
    }

    private fun screenSummary(pkg: String) =
        pkgNodes(pkg).flatMap { it.labels() + listOfNotNull(it.viewIdResourceName?.substringAfter(":id/")) }.distinct().take(50)

    /** WhatsApp en la lista de chats (si quedó dentro de un chat o de la búsqueda, vuelve atrás). */
    private suspend fun reachChatList(pkg: String): Boolean {
        repeat(12) { attempt ->
            delay(if (attempt == 0) 700 else 350)
            if (appRoots(pkg).isEmpty()) return@repeat
            if (byId(pkg, "entry").isNotEmpty() || byId(pkg, "search_input").isNotEmpty() || byId(pkg, "search_src_text").isNotEmpty()) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return@repeat
            }
            return true
        }
        return false
    }

    private fun chatRows(pkg: String): List<AccessibilityNodeInfo> =
        CHAT_NAME_IDS.flatMap { byId(pkg, it) }.filter { !it.text.isNullOrBlank() }

    /** Abre [chat] si está entre los nombres visibles; baja la lista unas veces si no aparece. */
    private suspend fun openFromList(pkg: String, chat: String, scrolls: Int = 2): Boolean {
        repeat(scrolls + 1) { round ->
            val rows = chatRows(pkg)
            val best = com.sara.core.apps.AppMatcher.best(chat, rows.mapIndexed { i, n -> com.sara.core.apps.InstalledApp(n.text.toString(), i.toString()) })
            if (best != null && tap(rows[best.id.toInt()])) {
                log("Abrí el chat «${best.label}»")
                return true
            }
            if (round < scrolls) {
                val list = rows.firstOrNull()?.scrollableAncestor() ?: return false
                list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(450)
            }
        }
        return false
    }

    private fun AccessibilityNodeInfo.scrollableAncestor(): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = parent
        repeat(8) {
            if (node == null || node!!.isScrollable) return node
            node = node!!.parent
        }
        return null
    }

    private suspend fun openFromArchived(pkg: String, chat: String): Boolean {
        fun archivedRow() = ARCHIVED_WORDS.flatMap { byText(pkg, it) }.firstOrNull { n ->
            n.labels().any { ARCHIVED.matches(it) }
        }
        var archived = archivedRow()
        if (archived == null) {
            // «Archivados» está arriba de todo: subir la lista.
            chatRows(pkg).firstOrNull()?.scrollableAncestor()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            delay(400)
            archived = archivedRow()
        }
        if (archived == null || !tap(archived)) return false
        delay(700)
        if (openFromList(pkg, chat, scrolls = 4)) return true
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(400)
        return false
    }

    private suspend fun openFromSearch(pkg: String, chat: String): Boolean {
        val button = SEARCH_IDS.flatMap { byId(pkg, it) }.firstOrNull()
            ?: listOf("buscar", "Search").flatMap { byText(pkg, it) }.firstOrNull()
            ?: return false
        if (!tap(button)) return false
        repeat(6) {
            delay(400)
            val field = SEARCH_IDS.flatMap { byId(pkg, it) }.firstOrNull { it.isEditable }
                ?: appRoots(pkg).firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
                ?: return@repeat
            if (field.type(chat)) {
                repeat(5) {
                    delay(500)
                    if (openFromList(pkg, chat, scrolls = 0)) return true
                }
                return false
            }
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
        return kotlinx.coroutines.withTimeoutOrNull(1_500) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
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
        } ?: false
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

        private val FIXED_LABELS = setOf(
            "bluetooth", "wi-fi", "wifi", "nombre del dispositivo", "configuración adicional", "configuracion adicional",
            "dispositivos disponibles", "dispositivos vinculados", "vincular nuevo dispositivo", "device name", "paired devices",
            "available devices", "pair new device", "usar bluetooth", "use bluetooth", "usar wi-fi", "use wi-fi",
        )
        private val CHAT_NAME_IDS = listOf("conversations_row_contact_name", "contact_name", "conversation_contact_name")
        private val ARCHIVED_WORDS = listOf("Archivados", "Archivadas", "Archived")
        private val ARCHIVED = Regex("archivad[oa]s?(\\s*\\(\\d+\\))?|archived(\\s*\\(\\d+\\))?|\\d+\\s+archivad[oa]s?")
        private val SEARCH_IDS = listOf("menuitem_search", "my_search_bar", "search_bar", "search_bar_inner", "search_input", "search_src_text")

        suspend fun sendToChat(context: Context, pkg: String, chat: String, message: String): ChatSendResult {
            val service = awaitInstance(context) ?: return ChatSendResult.Failed("Accesibilidad detenida")
            return service.sendBySearch(pkg, chat, message)
        }

        /** En la pantalla de Ajustes abierta, deja el interruptor principal en [on]. null = sin accesibilidad. */
        suspend fun setScreenSwitch(context: Context, on: Boolean): Boolean? {
            val service = awaitInstance(context) ?: return null
            return service.toggleSwitch(on)
        }

        /** Toca en pantalla el texto que mejor coincide con [name]; devuelve el texto tocado. */
        suspend fun tapOnScreen(context: Context, name: String): String? {
            val service = awaitInstance(context) ?: return null
            return service.tapLabel(name)
        }

        fun back(context: Context) {
            instance?.takeIf { isEnabled(context) }?.performGlobalAction(GLOBAL_ACTION_BACK)
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
