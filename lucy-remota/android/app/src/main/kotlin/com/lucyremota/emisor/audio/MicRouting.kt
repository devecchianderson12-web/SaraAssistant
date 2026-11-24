package com.lucyremota.emisor.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.lucyremota.nucleo.util.SaraLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Qué micrófono usa Sara para las órdenes cuando hay un Bluetooth (auto, auriculares) conectado. */
enum class MicChoice { AUTO, PHONE, BLUETOOTH }

/** Qué hace con la música mientras te escucha y te contesta. */
enum class MusicWhileListening { DUCK, PAUSE_BLUETOOTH, PAUSE }

/**
 * Elige el micrófono de las órdenes y lo enciende:
 * - «Sara» siempre se escucha con el micrófono del teléfono: el del Bluetooth obligaría a dejar el
 *   auto en «modo llamada» todo el tiempo (la música se escucharía como una llamada).
 * - La orden se graba con el micrófono elegido. Para el del Bluetooth se abre el canal de voz (SCO)
 *   sólo mientras Sara te escucha y se cierra al terminar.
 * - En Automático aprende de cada orden qué tan bien te escucha cada micrófono (cuánto se destaca
 *   tu voz sobre el ruido) y usa el mejor; también se puede comparar a mano desde Ajustes.
 */
class MicRouter(
    context: Context,
    private val choice: () -> MicChoice,
    private val logger: SaraLogger,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val prefs = context.getSharedPreferences("lucy_mic_quality", Context.MODE_PRIVATE)

    /** `true` mientras el canal de voz del Bluetooth está abierto para esta interacción. */
    @Volatile var bluetoothActive = false
        private set

    fun bluetoothMic(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    /** Hay audio Bluetooth conectado (auto, parlante, auriculares). */
    fun bluetoothAudioConnected(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    fun bluetoothName(): String? = (bluetoothMic() ?: audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP })?.productName?.toString()?.ifBlank { null }

    /** Si la próxima orden se graba con el micrófono del Bluetooth. */
    fun commandUsesBluetooth(): Boolean {
        if (bluetoothMic() == null) return false
        return when (choice()) {
            MicChoice.PHONE -> false
            MicChoice.BLUETOOTH -> true
            MicChoice.AUTO -> prefersBluetooth()
        }
    }

    // ---------------------------------------------------------------- Canal de voz del Bluetooth

    /** Al empezar a escucharte: abre el micrófono del Bluetooth si corresponde. */
    fun beginInteraction(force: Boolean = false) {
        if (!(force && bluetoothMic() != null) && !commandUsesBluetooth()) return
        bluetoothActive = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val sco = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: return@runCatching false
                audio.setCommunicationDevice(sco)
            } else {
                @Suppress("DEPRECATION")
                audio.startBluetoothSco()
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = true
                true
            }
        }.onFailure { logger.w(TAG, "No pude abrir el micrófono del Bluetooth", it) }.getOrDefault(false)
        if (bluetoothActive) logger.d(TAG, "Orden con el micrófono del Bluetooth (${bluetoothName()})")
    }

    fun endInteraction() {
        if (!bluetoothActive) return
        bluetoothActive = false
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audio.stopBluetoothSco()
            }
        }
    }

    private fun scoReady(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        else @Suppress("DEPRECATION") audio.isBluetoothScoOn

    /**
     * Micrófono preferido para grabar ahora: el del Bluetooth sólo si su canal está abierto para esta
     * orden (espera hasta 2,5 s a que conecte). En cualquier otro caso (la espera de «Sara», órdenes con
     * el del teléfono) no se fuerza nada: Android usa el del teléfono con su cancelación de eco, que es
     * lo que permite escucharte con música (forzar el micrófono la desactivaba en algunos teléfonos).
     */
    suspend fun captureDevice(): AudioDeviceInfo? {
        if (!bluetoothActive) return null
        withTimeoutOrNull(2_500) { while (!scoReady()) delay(100) }
        if (scoReady()) return bluetoothMic()
        logger.w(TAG, "El Bluetooth no abrió su micrófono a tiempo; uso el del teléfono")
        return null
    }

    /** Qué micrófono se usa ahora para las órdenes (para la pantalla). */
    fun currentLabel(): String = when {
        bluetoothMic() == null -> "Del teléfono (no hay Bluetooth con micrófono)"
        commandUsesBluetooth() -> "Del Bluetooth (${bluetoothName() ?: "conectado"})"
        else -> "Del teléfono (hay Bluetooth: ${bluetoothName() ?: "conectado"})"
    }

    // ---------------------------------------------------------------- Calidad de cada micrófono

    /**
     * Cuánto se destaca tu voz sobre el ruido en una escucha (0 a 1), a partir de los niveles de
     * audio: la diferencia entre lo más fuerte (tu voz) y el fondo (motor, música).
     */
    fun contrastOf(levels: List<Float>): Float? {
        if (levels.size < 8) return null
        val sorted = levels.sorted()
        val loud = sorted[(sorted.size * 0.9f).toInt().coerceAtMost(sorted.size - 1)]
        val floor = sorted[(sorted.size * 0.15f).toInt()]
        return (loud - floor).coerceIn(0f, 1f)
    }

    /** Guarda cómo escuchó una orden (promedio móvil por micrófono). */
    fun record(bluetooth: Boolean, contrast: Float) {
        val key = sourceKey(bluetooth)
        val n = prefs.getInt("$key.n", 0)
        val old = prefs.getFloat("$key.q", contrast)
        val q = if (n == 0) contrast else old * 0.7f + contrast * 0.3f
        prefs.edit().putInt("$key.n", n + 1).putFloat("$key.q", q).apply()
        logger.d(TAG, "Calidad ${if (bluetooth) "Bluetooth" else "teléfono"}: ${(contrast * 100).roundToInt()} (promedio ${(q * 100).roundToInt()})")
    }

    data class Quality(val samples: Int, val score: Float)

    fun quality(bluetooth: Boolean): Quality? {
        val key = sourceKey(bluetooth)
        val n = prefs.getInt("$key.n", 0)
        return if (n == 0) null else Quality(n, prefs.getFloat("$key.q", 0f))
    }

    /** Las mediciones del Bluetooth son de cada dispositivo (el auto no escucha igual que unos auriculares). */
    private fun sourceKey(bluetooth: Boolean) = if (bluetooth) "bt.${bluetoothName() ?: "?"}" else "phone"

    private fun prefersBluetooth(): Boolean {
        val phone = quality(false)
        val bt = quality(true)
        return when {
            bt == null -> phone != null && phone.samples >= 3 && phone.score < POOR
            phone == null -> bt.score >= POOR
            else -> bt.score > phone.score + MARGIN
        }
    }

    fun describeQuality(): String {
        fun q(b: Boolean) = quality(b)?.let { "${(it.score * 100).roundToInt()}/100 (${it.samples} órdenes)" } ?: "sin medir"
        return "Teléfono: ${q(false)} · Bluetooth: ${q(true)}"
    }

    private companion object {
        const val TAG = "MicRouter"
        /** Por debajo de esto el teléfono te escucha mal: se prueba el del Bluetooth. */
        const val POOR = 0.22f
        const val MARGIN = 0.03f
    }
}
