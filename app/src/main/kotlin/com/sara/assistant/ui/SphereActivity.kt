package com.sara.assistant.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sara.assistant.container
import com.sara.core.model.AssistantState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Capa que aparece al llamar a Sara fuera de la app (inicio, otra app, pantalla bloqueada o
 * apagada): fondo negro al 70 % y la esfera en el centro, que reacciona a la voz. Entra y sale en
 * ~120 ms y se cierra sola cuando Sara termina; tocar fuera de la esfera la cancela.
 */
class SphereActivity : ComponentActivity() {
    private lateinit var sphere: SphereView
    private lateinit var root: FrameLayout
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sphere = SphereView(this)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb((255 * 0.7f).toInt(), 0, 0, 0))
            alpha = 0f
            addView(sphere, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            setOnClickListener {
                container.orchestrator.cancelInteraction()
                close()
            }
        }
        setContentView(root)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        // Entrada ultra rápida: aparece y crece desde el centro.
        sphere.scaleX = 0.6f
        sphere.scaleY = 0.6f
        root.animate().alpha(1f).setDuration(90).start()
        sphere.animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(DecelerateInterpolator()).start()

        val c = container
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { c.levels.level.collect { sphere.level = it } }
                launch {
                    c.orchestrator.state.collect { state ->
                        sphere.energy = when (state) {
                            is AssistantState.Listening -> 1f
                            is AssistantState.Speaking -> 0.85f
                            is AssistantState.Processing, is AssistantState.Executing -> 0.7f
                            else -> 0.4f
                        }
                        if (state is AssistantState.Speaking) sphere.level = maxOf(sphere.level, 0.35f)
                    }
                }
                // Se cierra cuando Sara termina (con un margen para no parpadear entre pasos).
                launch {
                    while (true) {
                        delay(250)
                        if (!c.orchestrator.isInteractionActive) {
                            delay(350)
                            if (!c.orchestrator.isInteractionActive) {
                                close()
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    private fun close() {
        if (closing) return
        closing = true
        root.animate().alpha(0f).setDuration(110).withEndAction {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }.start()
        sphere.animate().scaleX(0.7f).scaleY(0.7f).setDuration(110).start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closing = false
        root.animate().cancel()
        root.alpha = 1f
    }

    companion object {
        /**
         * Muestra la esfera si se puede: hace falta «Mostrar sobre otras apps» para abrirla desde
         * segundo plano. Si la app de Sara está a la vista, no hace falta (ya está la nebulosa).
         */
        fun show(context: Context, appVisible: Boolean) {
            if (appVisible || !Settings.canDrawOverlays(context)) return
            runCatching {
                context.startActivity(
                    Intent(context, SphereActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
                )
            }
        }
    }
}
