package com.sara.core.assistant

/** Cuándo tiene el micrófono abierto esperando «Sara» / «Ey Sara». */
enum class ListenMode {
    /** Siempre (lo que más batería gasta). */
    ALWAYS,
    /** Escucha inteligente: sólo cuando tiene sentido (auto, cargando, pantalla encendida, horario). */
    SMART,
    /** Sólo mientras la app está abierta o en Recientes; al sacarla de Recientes se apaga. */
    APP_ONLY,
}

/** Qué momentos activan la escucha inteligente (los elige el usuario). */
data class SmartListening(
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
    val bluetoothAudio: Boolean,
    val charging: Boolean,
    val screenOn: Boolean,
    val hour: Int,
)

object ListenPolicy {
    fun shouldListen(mode: ListenMode, smart: SmartListening, now: ListenConditions): Boolean = when (mode) {
        ListenMode.ALWAYS, ListenMode.APP_ONLY -> true
        ListenMode.SMART -> (smart.bluetooth && now.bluetoothAudio) ||
            (smart.charging && now.charging) ||
            (smart.screenOn && now.screenOn) ||
            (smart.schedule && inSchedule(now.hour, smart.fromHour, smart.toHour))
    }

    fun inSchedule(hour: Int, from: Int, to: Int): Boolean = when {
        from == to -> true
        from < to -> hour in from until to
        else -> hour >= from || hour < to
    }

    /** Por qué está escuchando o en pausa (para la notificación y Ajustes). */
    fun describe(mode: ListenMode, smart: SmartListening, now: ListenConditions): String = when (mode) {
        ListenMode.ALWAYS -> "Escucha siempre"
        ListenMode.APP_ONLY -> "Escucha mientras la app está abierta o en Recientes"
        ListenMode.SMART -> {
            val reasons = buildList {
                if (smart.bluetooth && now.bluetoothAudio) add("conectado por Bluetooth")
                if (smart.charging && now.charging) add("cargando")
                if (smart.screenOn && now.screenOn) add("pantalla encendida")
                if (smart.schedule && inSchedule(now.hour, smart.fromHour, smart.toHour)) add("en tu horario")
            }
            if (reasons.isEmpty()) "En pausa para ahorrar batería" else "Escuchando: " + reasons.joinToString(", ")
        }
    }
}
