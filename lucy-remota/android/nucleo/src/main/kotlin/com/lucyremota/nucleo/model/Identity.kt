package com.lucyremota.nucleo.model

/**
 * Quién es la asistente en esta plataforma: «Sara» en Android, «Lucy» en Windows. El cerebro
 * (reglas, memoria, habilidades, IA) es el mismo; sólo cambian el nombre y el dispositivo.
 */
object AssistantIdentity {
    @Volatile var name: String = "Sara"
    /** Cómo se nombra el aparato en las respuestas: «teléfono» o «computadora». */
    @Volatile var device: String = "teléfono"
    /** Para la IA: «un teléfono Android» o «una computadora con Windows». */
    @Volatile var platform: String = "un teléfono Android"
    /** Respuesta a «¿qué podés hacer?». */
    @Volatile var capabilities: String = "Música, volumen, apps, WhatsApp y preguntas."

    /** Nombres con los que se la llama (las dos versiones), para quitarlos del principio de una orden. */
    const val NAMES = "sara|sarah|zara|zarah|lucy|luci|lusi|lucie|luchi"
}
