package com.sara.core.assistant

/** Cuándo tiene el micrófono abierto esperando «Sara» / «Ey Sara». */
enum class ListenMode {
    /** Siempre (lo que más batería gasta; las demás opciones no cambian nada). */
    ALWAYS,
    /** Sólo en los momentos elegidos (se pueden combinar varios). */
    SMART,
}

/** Qué momentos abren el micrófono (el usuario elige uno o varios; alcanza con que se cumpla uno). */
data class SmartListening(
    /** Mientras la app está abierta o en Recientes. */
    val appOpen: Boolean = true,
    val bluetooth: Boolean = true,
    val charging: Boolean = true,
    val screenOn: Boolean = false,
    val schedule: Boolean = false,
    /** Horario 0-23; si «desde» es mayor que «hasta», cruza la medianoche (22 a 7). */
    val fromHour: Int = 8,
    val toHour: Int = 23,
)

/** Lo que pasa ahora en el teléfono. */
data class ListenConditions(
    /** La app está abierta o en Recientes. */
    val appOpen: Boolean,
    val bluetoothAudio: Boolean,
    val charging: Boolean,
    val screenOn: Boolean,
    val hour: Int,
)

object ListenPolicy {
    fun shouldListen(mode: ListenMode, smart: SmartListening, now: ListenConditions): Boolean = when (mode) {
        ListenMode.ALWAYS -> true
        ListenMode.SMART -> (smart.appOpen && now.appOpen) ||
            (smart.bluetooth && now.bluetoothAudio) ||
            (smart.charging && now.charging) ||
            (smart.screenOn && now.screenOn) ||
            (smart.schedule && inSchedule(now.hour, smart.fromHour, smart.toHour))
    }

    /**
     * Con la app cerrada (fuera de Recientes), ¿hay algún motivo para que el servicio siga vivo
     * esperando? Si sólo está elegido «con la app abierta», no: se apaga del todo.
     */
    fun keepsRunningWhenAppClosed(mode: ListenMode, smart: SmartListening): Boolean =
        mode == ListenMode.ALWAYS || smart.bluetooth || smart.charging || smart.screenOn || smart.schedule

    fun inSchedule(hour: Int, from: Int, to: Int): Boolean = when {
        from == to -> true
        from < to -> hour in from until to
        else -> hour >= from || hour < to
    }

    /** Por qué está escuchando o en pausa (para la notificación y Ajustes). */
    fun describe(mode: ListenMode, smart: SmartListening, now: ListenConditions): String = when (mode) {
        ListenMode.ALWAYS -> "Escucha siempre"
        ListenMode.SMART -> {
            val reasons = buildList {
                if (smart.appOpen && now.appOpen) add("app abierta")
                if (smart.bluetooth && now.bluetoothAudio) add("conectado por Bluetooth")
                if (smart.charging && now.charging) add("cargando")
                if (smart.screenOn && now.screenOn) add("pantalla encendida")
                if (smart.schedule && inSchedule(now.hour, smart.fromHour, smart.toHour)) add("en tu horario")
            }
            if (reasons.isEmpty()) "En pausa para ahorrar batería" else "Escuchando: " + reasons.joinToString(", ")
        }
    }
}
