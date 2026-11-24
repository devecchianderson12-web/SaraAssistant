package com.sara.assistant.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * ¿El teléfono está en el bolsillo (o en una cartera)? Con el sensor de proximidad tapado y poca
 * luz durante unos segundos. Ahí la tela apaga la voz, así que el micrófono se refuerza (×3) sólo
 * mientras está guardado; al sacarlo vuelve solo a lo normal.
 */
class PocketDetector(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val proximity: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val light: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)
    @Volatile private var covered = false
    @Volatile private var dark = true
    @Volatile private var coveredSince = 0L
    @Volatile private var started = false

    /** Hay sensor de proximidad (sin él no se puede saber). */
    val available: Boolean get() = proximity != null

    /** Guardado desde hace un par de segundos (así tapar el sensor con la mano no lo activa). */
    val inPocket: Boolean
        get() = covered && dark && coveredSince > 0 && SystemClock.elapsedRealtime() - coveredSince > SETTLE_MS

    @Synchronized
    fun start() {
        if (started || proximity == null) return
        started = true
        sensors?.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL)
        light?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        sensors?.unregisterListener(this)
        covered = false
        coveredSince = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val near = event.values[0] < minOf(event.sensor.maximumRange, NEAR_CM)
                if (near && !covered) coveredSince = SystemClock.elapsedRealtime()
                if (!near) coveredSince = 0L
                covered = near
            }
            Sensor.TYPE_LIGHT -> dark = event.values[0] < DARK_LUX
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val NEAR_CM = 3f
        const val DARK_LUX = 15f
        const val SETTLE_MS = 2_000L
    }
}
