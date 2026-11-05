package com.snipsnap.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.loop.*
import com.snipsnap.shell.SnipStore
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/** Real JVM mixer → JNI bus → storage. This does not measure phone deadlines. */
@RunWith(AndroidJUnit4::class)
class OrbitAquariumTest {
    private val rate = 48000
    private val set = OrbitSet(listOf(Orbit("TONE", 16, SnipOrbit("tone.wav"))), 123.5f, rate)
    private val source = object : SampleSource {
        override fun loop(sampleFile: String) = Snip(FloatArray(96000) { (.4 * sin(.1 * it)).toFloat() }, 1, rate)
        override fun pad(kit: String, slot: Int): Snip? = null
    }
    private val sound = AquariumPerformanceSettings(AquariumRecipe(mapOf(
        "aqua_tide_mix" to 1f, "aqua_tide_depth" to .8f, "aqua_tide_stereoPhase" to 180f,
        "aqua_reef_mix" to .7f,
    ), seed = 0xffff_ffffL), true, .125f)

    @Test fun fresh_live_mix_and_bounce_match_and_zero_input_tail_and_stereo_survive_the_shelf() {
        val bank = OrbitBank.prepare(set, source)
        val frames = 2048 * 6
        val live = FloatArray(frames * 2)
        val sink = object : AudioSink {
            override val sampleRate = rate; override val channels = 2
            private var at = 0
            override fun write(block: FloatArray) { block.copyInto(live, at); at += block.size }
            override fun close() {}
        }
        OrbitAquariumProcessor(rate, sound).use { fx ->
            OrbitEngine(set, bank, sink, processor = fx).runFor(6)
            assertFalse(fx.faulted)
        }
        fun bounce() = OrbitAquariumProcessor(rate, sound).use { fx ->
            OrbitEngine.render(set, bank, frames, processor = fx, tailFrames = 12001)
        }
        val bounced = bounce()
        assertArrayEquals(live, bounced.samples.copyOf(live.size), 0f)
        assertArrayEquals(bounced.samples, bounce().samples, 0f)
        assertEquals((frames + 12001) * 2, bounced.samples.size)
        assertTrue(bounced.samples.drop(frames * 2).any { abs(it) > 1e-7f })
        assertTrue(live.indices.step(2).any { abs(live[it] - live[it + 1]) > 1e-4f })
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "orbit-aquarium-${System.nanoTime()}").apply { check(mkdirs()) }
        try {
            val saved = SnipStore.import(bounced, root, System.currentTimeMillis(), preserveStereo = true)
            val decoded = WavReader.read(saved.file)
            assertEquals(44100, decoded.sampleRate)
            assertEquals(2, decoded.channels)
            assertTrue(decoded.samples.indices.step(2).any { abs(decoded.samples[it] - decoded.samples[it + 1]) > 1e-4f })
        } finally { root.deleteRecursively() }
    }

    @Test fun dry_is_exact_wet_mute_survives_reset_and_bypass_restores_dry() {
        val original = FloatArray(12000) { if (it % 2 == 0) 1.5f else -.5f }
        val dry = original.copyOf()
        val fx = OrbitAquariumProcessor(rate, AquariumPerformanceSettings())
        try {
            fx.process(dry, 123.5f, false)
            assertArrayEquals(original, dry, 0f)
            val mute = sound.copy(outputGain = 0f)
            fx.configure(mute)
            fx.process(original.copyOf(), 90f, false)
            fx.resetTail()
            fx.process(original.copyOf(), 90f, true)
            val quiet = original.copyOf(); fx.process(quiet, 90f, false)
            assertEquals(0f, quiet.maxOf { abs(it) }, 0f)
            fx.configure(mute.copy(enabled = false))
            fx.process(original.copyOf(), 90f, false)
            val restored = original.copyOf(); fx.process(restored, 90f, false)
            assertArrayEquals(original, restored, 0f)
            assertFalse(fx.faulted)
        } finally { fx.close(); fx.close() }
        assertTrue(runCatching { fx.process(dry, 90f, false) }.isFailure)
    }

    @Test fun a_coalesced_settings_edit_keeps_the_earlier_reset_request() {
        OrbitAquariumProcessor(rate, sound).use { actual ->
            OrbitAquariumProcessor(rate, sound).use { reference ->
                val input = FloatArray(16000) { (.4 * sin(.1 * (it / 2))).toFloat() }
                actual.process(input.copyOf(), 123.5f, false)
                reference.process(input.copyOf(), 123.5f, false)
                actual.configure(sound, reset = true)
                actual.configure(sound)
                reference.resetTail()
                val a = input.copyOf(); val b = input.copyOf()
                actual.process(a, 123.5f, false); reference.process(b, 123.5f, false)
                assertArrayEquals(b, a, 0f)
            }
        }
    }

    @Test fun unavailable_optional_preparation_keeps_default_dry_and_refuses_a_wet_request() {
        OrbitAquariumProcessor(1, AquariumPerformanceSettings()).use { fx ->
            val dry = floatArrayOf(1.5f, -.5f)
            fx.process(dry, 90f, false)
            assertArrayEquals(floatArrayOf(1.5f, -.5f), dry, 0f)
            assertTrue(fx.faulted)
            fx.configure(sound)
            assertTrue(runCatching { fx.process(dry, 90f, false) }.exceptionOrNull() is IllegalStateException)
        }
    }
}
