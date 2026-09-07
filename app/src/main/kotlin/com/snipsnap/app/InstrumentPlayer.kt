package com.snipsnap.app

import android.content.Context
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.InstrumentEngine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The KEYS screen's voice: `:shell`'s tested [InstrumentEngine] pulled
 * block by block into an [AndroidAudioSink] on its own thread, the way
 * the loop engine and the tape voice pace themselves — the blocking
 * write is the clock. Notes come in from the UI thread; the engine is
 * touched under one lock, block by block, so a tap never waits on more
 * than a few milliseconds of audio.
 *
 * Samples are read once at [open] (mono-folded, at their own rate; the
 * engine handles the ratio to the device's).
 *
 * **Thread ownership**, the `TapeVoice` lesson: every audio thread builds,
 * writes to and releases its **own** sink, and runs only while its
 * [generation] is the current one. [open] and [close] bump the generation,
 * so a thread stuck in a blocking write (a stalled device, a route change)
 * winds down on its own and never shares a sink with its successor; the
 * worst case is one buffer's worth (~150 ms) of overlap on the device.
 * [close] still joins, for a full second, so the common case leaves no
 * thread behind the screen that owned the samples.
 */
class InstrumentPlayer(context: Context) {

    private val outRate = deviceSampleRate(context)
    private val running = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private var engine: InstrumentEngine? = null
    private val lock = Any()
    private var audioThread: Thread? = null

    /** Load the instrument the sidecar describes and start the audio thread. */
    fun open(sidecar: File, instrument: InstrumentStore.Instrument) {
        close()
        val dir = sidecar.parentFile ?: File(".")
        val samples = instrument.zones.map { zone ->
            val f = File(dir, zone.sample)
            runCatching { WavReader.read(f) }.getOrNull()?.let { s ->
                if (s.channels == 1) s.samples else Cleanup.toMono(s).samples
            } ?: FloatArray(0)
        }
        val rate = instrument.zones.firstOrNull()?.let { zone ->
            runCatching { com.snipsnap.xpm.WavInfo.read(File(dir, zone.sample)).sampleRate }.getOrNull()
        } ?: 44_100
        val loaded = InstrumentEngine.Loaded(instrument, rate, samples)
        synchronized(lock) { engine = InstrumentEngine(loaded, outRate) }
        running.set(true)
        val myGeneration = generation.incrementAndGet()
        audioThread = thread(name = "InstrumentPlayer", isDaemon = true) { runLoop(myGeneration) }
    }

    fun noteOn(note: Int, velocity: Float = 1f) {
        synchronized(lock) { engine?.noteOn(note, velocity) }
    }

    fun noteOff(note: Int) {
        synchronized(lock) { engine?.noteOff(note) }
    }

    fun allOff() {
        synchronized(lock) { engine?.allOff() }
    }

    /**
     * Stop the thread and release its sink; safe to call twice. The
     * generation moves on first, so even a thread that outlives the join
     * exits at its next block and never touches a successor's sink.
     */
    fun close() {
        running.set(false)
        generation.incrementAndGet()
        audioThread?.join(1000)
        audioThread = null
        synchronized(lock) { engine = null }
    }

    private fun runLoop(myGeneration: Int) {
        val sink = AndroidAudioSink(outRate)
        val block = FloatArray(BLOCK_FRAMES * 2)
        try {
            while (running.get() && generation.get() == myGeneration) {
                synchronized(lock) {
                    val e = engine
                    if (e == null) java.util.Arrays.fill(block, 0f) else e.render(block, BLOCK_FRAMES)
                }
                sink.write(block)
            }
        } finally {
            sink.close()
        }
    }

    private companion object {
        /** ~5 ms at 48 kHz: a tap lands inside the next block, the sink's own buffer smooths the rest. */
        const val BLOCK_FRAMES = 256
    }
}
