package com.lucyremota.emisor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lucyremota.protocolo.PairingQr

/**
 * SÓLO EN DEBUG (emulador, por adb):
 *  --es qr "lucyremota://pair?..."  → vincula como si se hubiera escaneado el QR.
 *  --es text "poné música"          → como si se lo hubieran dicho a Lucy.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        intent.getStringExtra("qr")?.let { qr ->
            val parsed = PairingQr.parse(qr)
            if (parsed != null) c.link.pairWithQr(parsed)
            c.logger.d("Debug", "QR: ${parsed != null}")
            return
        }
        intent.getStringExtra("text")?.let { text ->
            c.logger.d("Debug", "Texto: $text → ${c.orchestrator.submitText(text)}")
        }
    }
}
