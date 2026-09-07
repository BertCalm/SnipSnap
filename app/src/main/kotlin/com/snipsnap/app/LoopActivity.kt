package com.snipsnap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File
import com.snipsnap.loop.KitSampleSource
import com.snipsnap.loop.LoopEngine
import com.snipsnap.loop.Residency
import com.snipsnap.loop.Session
import com.snipsnap.loop.SessionStore
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The loop player screen.
 *
 * Three threads, each with one job: the audio thread runs LoopEngine and blocks
 * on AudioTrack; a small pool bakes blocks ahead of it; the UI thread reads the
 * engine's position and draws. They share exactly two things — an AtomicInteger
 * for position and an AtomicReference for pending edits — which is the whole
 * concurrency story.
 */
class LoopActivity : ComponentActivity() {

    private val bakers = Executors.newFixedThreadPool(2)
    private var engine: LoopEngine? = null
    private var sink: AndroidAudioSink? = null
    private var audioThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dir = File(filesDir, "sessions/current")
        val rate = deviceSampleRate(this)

        // Bake at the device's rate, not the MPC's: nothing converts in the
        // callback because nothing needs to.
        val loaded = runCatching { SessionStore.load(dir) }.getOrNull()
            ?.copy(sampleRate = rate)

        setContent {
            // remember, or every recomposition resets the session to what was
            // loaded from disk and throws away the mutes the user just tapped.
            var session by remember { mutableStateOf(loaded) }
            var interval by remember { mutableIntStateOf(0) }

            val s = session
            if (s != null) {
                LoopGrid(
                    session = s,
                    interval = interval,
                    onToggleTrack = { t ->
                        val next = s.copy(
                            tracks = s.tracks.mapIndexed { i, track ->
                                if (i == t) track.copy(engaged = !track.engaged) else track
                            },
                        )
                        session = next
                        engine?.apply(next)
                    },
                    onSelectBlock = { _, _ -> /* block editing lands in a later plan */ },
                )

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    while (true) {
                        interval = engine?.position() ?: 0
                        kotlinx.coroutines.delay(50)
                    }
                }
            }
        }

        if (loaded != null) start(loaded, dir)
    }

    private fun start(session: Session, dir: File) {
        val audioSink = AndroidAudioSink(session.sampleRate)
        val residency = Residency(session, KitSampleSource(dir), bakers)
        val loopEngine = LoopEngine(residency, audioSink)

        sink = audioSink
        engine = loopEngine

        audioThread = thread(name = "snipsnap-audio", isDaemon = true) {
            // Prime here, not in onCreate. Baking interval 0 is six WAV decodes,
            // six resamples and possibly six slice-retriggers — seconds of work
            // that must never touch the main thread.
            residency.buffersFor(0)
            residency.prefetch(1)
            loopEngine.run()
        }
    }

    override fun onDestroy() {
        engine?.stop()
        audioThread?.join(1_000)
        sink?.close()
        bakers.shutdownNow()
        super.onDestroy()
    }
}
