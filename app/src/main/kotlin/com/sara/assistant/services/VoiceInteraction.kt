package com.sara.assistant.services

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.sara.assistant.container
import com.sara.core.engine.EngineErrorType
import com.sara.core.engine.RecognitionRequest
import com.sara.core.engine.RecognitionResult
import com.sara.core.model.AssistantEvent
import com.sara.core.model.AssistantState
import com.sara.core.model.Trigger
import com.sara.core.permissions.StartContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Servicio de interacción de voz: permite elegir a Sara como "Asistente digital predeterminado".
 * Con ese rol el sistema mantiene vinculado este servicio y Android permite que la app inicie su
 * servicio de micrófono desde segundo plano (excepción oficial para quien provee el
 * VoiceInteractionService). El gesto de asistente (mantener Inicio / deslizar desde la esquina)
 * abre una sesión de Sara incluso sobre la pantalla de bloqueo.
 */
class SaraVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        val c = container
        c.logger.d("VIS", "Sara es el asistente predeterminado")
        c.scope.launch {
            val settings = c.settings.current()
            if (settings.wakeWordEnabled && settings.autostart && settings.listenMode != com.sara.core.assistant.ListenMode.APP_ONLY &&
                !SaraListeningService.running.value
            ) {
                SaraListeningService.start(this@SaraVoiceInteractionService, StartContext.VOICE_INTERACTION_SERVICE)
            }
        }
    }
}

class SaraSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SaraSession(this)
}

/** Sesión mostrada por el gesto de asistente: tarjeta inferior con el estado y la transcripción. */
class SaraSession(context: Context) : VoiceInteractionSession(context) {
    private var scope: CoroutineScope? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView

    override fun onCreateContentView(): View {
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt() }
        status = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }
        transcript = TextView(context).apply {
            setTextColor(Color.parseColor("#B8BCD0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(8f), 0, 0)
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(22f), dp(24f), dp(26f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#1B1A2E"), Color.parseColor("#12141C")),
            ).apply { cornerRadius = dp(28f).toFloat() }
            addView(TextView(context).apply {
                text = "SARA"
                letterSpacing = 0.3f
                setTextColor(Color.parseColor("#8B7BFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
            addView(status)
            addView(transcript)
            setOnClickListener { /* evita cerrar al tocar la tarjeta */ }
        }
        return FrameLayout(context).apply {
            setOnClickListener { hide() }
            // La tarjeta queda por encima de la barra de navegación.
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(0, 0, 0, bars.bottom)
                insets
            }
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(dp(12f), 0, dp(12f), dp(16f))
            })
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val c = context.container
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = sessionScope
        status.text = "Decime…"
        transcript.text = ""
        sessionScope.launch {
            c.orchestrator.state.collect { state ->
                status.text = when (state) {
                    is AssistantState.Listening -> "Decime…"
                    is AssistantState.Processing -> "Procesando…"
                    is AssistantState.Executing -> "En eso…"
                    is AssistantState.Speaking -> state.text
                    is AssistantState.Error -> state.error.message
                    else -> status.text
                }
                if (state is AssistantState.Listening && state.partialText.isNotBlank()) transcript.text = "«${state.partialText}»"
            }
        }
        sessionScope.launch {
            c.orchestrator.events.collect { if (it is AssistantEvent.Heard) transcript.text = "«${it.text}»" }
        }
        val started = c.orchestrator.activate(Trigger.ASSIST_GESTURE)
        sessionScope.launch {
            // Se cierra sola cuando termina la interacción.
            delay(400)
            while (c.orchestrator.isInteractionActive) delay(200)
            if (started) delay(900)
            hide()
        }
    }

    override fun onHide() {
        scope?.cancel()
        scope = null
        super.onHide()
    }
}

/**
 * Servicio de reconocimiento requerido por el sistema para registrar un asistente. Usa el motor
 * offline (Vosk) de Sara; pausa la detección de "Sara" mientras otra app lo usa.
 */
class SaraRecognitionService : RecognitionService() {
    private var job: Job? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val callback = listener ?: return
        val c = container
        val language = recognizerIntent?.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE) ?: c.settings.settings.value.language
        job?.cancel()
        job = c.scope.launch(Dispatchers.Main) {
            runCatching { callback.readyForSpeech(Bundle()) }
            val result = c.orchestrator.withMicrophoneReleased {
                c.voskRecognizer.recognize(RecognitionRequest(language)) { partial ->
                    c.scope.launch(Dispatchers.Main) {
                        runCatching {
                            callback.partialResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(partial)) })
                        }
                    }
                }
            }
            runCatching {
                when (result) {
                    is RecognitionResult.Success -> callback.results(
                        Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(result.text)) },
                    )
                    is RecognitionResult.Failure -> callback.error(
                        when (result.error.type) {
                            EngineErrorType.NO_SPEECH -> SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            EngineErrorType.NO_MATCH -> SpeechRecognizer.ERROR_NO_MATCH
                            EngineErrorType.PERMISSION -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                            EngineErrorType.AUDIO_INPUT, EngineErrorType.BUSY -> SpeechRecognizer.ERROR_AUDIO
                            else -> SpeechRecognizer.ERROR_CLIENT
                        },
                    )
                }
            }
        }
    }

    override fun onStopListening(listener: Callback?) = Unit

    override fun onCancel(listener: Callback?) {
        job?.cancel()
        job = null
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
