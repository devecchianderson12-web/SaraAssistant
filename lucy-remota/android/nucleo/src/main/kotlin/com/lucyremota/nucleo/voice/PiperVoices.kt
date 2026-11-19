package com.lucyremota.nucleo.voice

/**
 * Voces femeninas de Piper (libres, gratis y sin Internet). Son todas las voces femeninas en español
 * que publica Piper: no hay de Chile, Uruguay ni Colombia.
 * [speed] corrige el ritmo propio de cada voz (Daniela habla rápido y suena cortante: se la frena).
 * En estos modelos la velocidad no es lineal: 0,65 la hace hablar alrededor de un 15 % más lento.
 */
enum class PiperVoice(val label: String, val country: String, val folder: String, val onnx: String, val sid: Int, val speed: Float, val megabytes: Int) {
    DANIELA("Daniela", "Argentina", "vits-piper-es_AR-daniela-high", "es_AR-daniela-high.onnx", 0, 0.65f, 115),
    CLAUDE("Claude", "México", "vits-piper-es_MX-claude-high", "es_MX-claude-high.onnx", 0, 1.0f, 67),
    SHARVARD("Sharvard", "España", "vits-piper-es_ES-sharvard-medium", "es_ES-sharvard-medium.onnx", 1, 1.0f, 80),
    ;

    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$folder.tar.bz2"
}
