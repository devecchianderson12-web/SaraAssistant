package com.sara.assistant

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sara.assistant.audio.AndroidVolumeControl
import com.sara.assistant.media.AndroidMediaControl
import com.sara.core.execution.DefaultCommandExecutor
import com.sara.core.execution.MediaAction
import com.sara.core.execution.MediaActionResult
import com.sara.core.model.CommandIntent
import com.sara.core.model.VolumeChange
import com.sara.core.util.SaraLogger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pruebas de integración con las APIs reales de Android (AudioManager y MediaSession).
 * Se ejecutan en emulador o dispositivo: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AndroidIntegrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private var originalVolume = 0
    private var session: MediaSession? = null

    @Before
    fun setUp() {
        originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd notification allow_listener ${context.packageName}/${context.packageName}.media.SaraNotificationListener")
            .close()
        Thread.sleep(1_500)
    }

    @After
    fun tearDown() {
        session?.release()
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
    }

    @Test
    fun volumenMultimediaRealConPorcentajes() = runBlocking {
        val volume = AndroidVolumeControl(context)
        val executor = DefaultCommandExecutor(NoMedia, volume)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)

        executor.execute(CommandIntent.AdjustVolume(VolumeChange.SetTo(50)))
        assertEquals(min + Math.round((max - min) * 0.5f), audio.getStreamVolume(AudioManager.STREAM_MUSIC))

        executor.execute(CommandIntent.AdjustVolume(VolumeChange.Max))
        assertEquals(max, audio.getStreamVolume(AudioManager.STREAM_MUSIC))

        executor.execute(CommandIntent.AdjustVolume(VolumeChange.Mute))
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))

        executor.execute(CommandIntent.AdjustVolume(VolumeChange.Unmute))
        assertTrue(!audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun controlaSesionMultimediaYDetectaAccionesNoSoportadas() = runBlocking {
        val ready = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            session = MediaSession(context, "SaraTest").apply {
                var track = 1
                fun publish(state: Int) {
                    setPlaybackState(
                        PlaybackState.Builder()
                            // Sin SKIP_TO_PREVIOUS a propósito.
                            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
                            .setState(state, 0, 1f)
                            .build(),
                    )
                    setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "track$track").putString(MediaMetadata.METADATA_KEY_TITLE, "Tema $track").build())
                }
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = publish(PlaybackState.STATE_PLAYING)
                    override fun onPause() = publish(PlaybackState.STATE_PAUSED)
                    override fun onSkipToNext() {
                        track++
                        publish(PlaybackState.STATE_PLAYING)
                    }
                })
                publish(PlaybackState.STATE_PLAYING)
                isActive = true
            }
            ready.countDown()
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        Thread.sleep(500)

        val media = AndroidMediaControl(context, { false }, SaraLogger.None)
        assertTrue("Falta acceso a notificaciones", media.hasNotificationAccess())
        assertTrue(media.perform(MediaAction.PAUSE) is MediaActionResult.Done)
        assertTrue(media.perform(MediaAction.RESUME) is MediaActionResult.Done)
        assertTrue(media.perform(MediaAction.NEXT) is MediaActionResult.Done)
        assertTrue(media.perform(MediaAction.PREVIOUS) is MediaActionResult.Unsupported)
        val stop = media.perform(MediaAction.STOP)
        assertTrue("STOP sin soporte debe pausar: $stop", stop is MediaActionResult.PausedInstead)
    }

    private object NoMedia : com.sara.core.execution.MediaControl {
        override suspend fun perform(action: MediaAction, query: String?, app: String?) = MediaActionResult.NothingPlaying
    }
}
