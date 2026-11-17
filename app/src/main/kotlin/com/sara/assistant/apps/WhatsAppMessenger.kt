package com.sara.assistant.apps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import com.sara.core.apps.AppMatcher
import com.sara.core.apps.InstalledApp
import com.sara.core.execution.MessageContact
import com.sara.core.execution.MessagePrepareResult
import com.sara.core.execution.MessageSendResult
import com.sara.core.execution.Messenger
import com.sara.core.model.PermissionKind
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manda WhatsApp: busca el contacto por nombre parecido, abre el chat con el texto escrito y, si el
 * servicio de accesibilidad está activo, toca «Enviar». Sin accesibilidad queda listo para enviar.
 */
class WhatsAppMessenger(
    private val context: Context,
    private val starter: ActivityStarter,
    private val logger: SaraLogger,
) : Messenger {

    private fun whatsAppPackage(): String? = PACKAGES.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    override suspend fun prepare(contact: String, group: Boolean): MessagePrepareResult = withContext(Dispatchers.IO) {
        if (whatsAppPackage() == null) return@withContext MessagePrepareResult.NoApp
        // Grupos y chats fuera de contactos («Notas»): se buscan dentro de WhatsApp.
        val bySearch = MessagePrepareResult.Found(MessageContact(contact.replaceFirstChar { it.uppercase() }, SEARCH))
        if (group) {
            return@withContext if (SaraAccessibilityService.isEnabled(context)) bySearch
            else MessagePrepareResult.NeedsPermission(PermissionKind.ACCESSIBILITY)
        }
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext if (SaraAccessibilityService.isEnabled(context)) bySearch
            else MessagePrepareResult.NeedsPermission(PermissionKind.CONTACTS)
        }
        val contacts = loadContacts()
        val best = AppMatcher.best(contact, contacts.map { InstalledApp(it.name, it.address) })
        if (best == null) {
            return@withContext if (SaraAccessibilityService.isEnabled(context)) bySearch else MessagePrepareResult.ContactNotFound
        }
        logger.d(TAG, "«$contact» -> ${best.label}")
        MessagePrepareResult.Found(MessageContact(best.label, best.id))
    }

    override suspend fun send(contact: MessageContact, message: String): MessageSendResult {
        val pkg = whatsAppPackage() ?: return MessageSendResult.Failed("WhatsApp no está instalado")
        if (contact.address == SEARCH) return sendBySearch(pkg, contact.name, message)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${contact.address}&text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
        return when (val outcome = withContext(Dispatchers.Main) { starter.start(intent, "WhatsApp") }) {
            StartOutcome.NeedsTap -> MessageSendResult.NeedsTap
            is StartOutcome.Failed -> MessageSendResult.Failed(outcome.message)
            is StartOutcome.Started -> when {
                outcome.deviceLocked -> MessageSendResult.DeviceLocked
                !SaraAccessibilityService.isEnabled(context) -> MessageSendResult.ReadyToSend
                SaraAccessibilityService.clickSend(context, pkg) -> MessageSendResult.Sent
                else -> MessageSendResult.ReadyToSend
            }
        }
    }

    private suspend fun sendBySearch(pkg: String, chat: String, message: String): MessageSendResult {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return MessageSendResult.Failed("No se pudo abrir WhatsApp")
        return when (val outcome = withContext(Dispatchers.Main) { starter.start(launch, "WhatsApp") }) {
            StartOutcome.NeedsTap -> MessageSendResult.NeedsTap
            is StartOutcome.Failed -> MessageSendResult.Failed(outcome.message)
            is StartOutcome.Started -> when {
                outcome.deviceLocked -> MessageSendResult.DeviceLocked
                else -> when (val r = SaraAccessibilityService.sendToChat(context, pkg, chat, message)) {
                    ChatSendResult.Sent -> MessageSendResult.Sent
                    ChatSendResult.ChatNotFound -> MessageSendResult.ChatNotFound
                    is ChatSendResult.Failed -> MessageSendResult.Failed(r.message)
                }
            }
        }
    }

    /** Contactos con WhatsApp primero (su número ya viene en el formato que usa WhatsApp); después el resto. */
    private fun loadContacts(): List<MessageContact> {
        val resolver = context.contentResolver
        val result = linkedMapOf<String, MessageContact>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} IN (?, ?)",
            arrayOf(WA_MIMETYPE, WA_BUSINESS_MIMETYPE),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val digits = c.getString(1)?.substringBefore('@')?.filter(Char::isDigit) ?: continue
                if (digits.length >= 8) result.putIfAbsent(name, MessageContact(name, digits))
            }
        }
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                if (name in result) continue
                val number = c.getString(1) ?: c.getString(2) ?: continue
                val digits = number.filter(Char::isDigit)
                if (digits.length >= 8) result[name] = MessageContact(name, digits)
            }
        }
        return result.values.toList()
    }

    private companion object {
        const val TAG = "WhatsApp"
        /** Dirección especial: el chat se busca por nombre dentro de WhatsApp. */
        const val SEARCH = "buscar"
        val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        const val WA_MIMETYPE = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
        const val WA_BUSINESS_MIMETYPE = "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile"
    }
}
