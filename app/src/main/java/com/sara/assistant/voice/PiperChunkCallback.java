package com.sara.assistant.voice;

import kotlin.jvm.functions.Function1;

/**
 * Recibe el audio de Daniela a medida que se genera. Está en Java a propósito: la librería nativa
 * (sherpa-onnx) busca exactamente el método {@code Integer invoke(float[])}, que una lambda de
 * Kotlin moderna no tiene (y la app se cerraba al hablar).
 */
public final class PiperChunkCallback implements Function1<float[], Integer> {
    /** Devuelve false para cortar la generación (por ejemplo, si cancelaron la respuesta). */
    public interface Sink {
        boolean onChunk(float[] samples);
    }

    private final Sink sink;

    public PiperChunkCallback(Sink sink) {
        this.sink = sink;
    }

    @Override
    public Integer invoke(float[] samples) {
        return sink.onChunk(samples) ? 1 : 0;
    }
}
