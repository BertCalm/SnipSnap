package com.snipsnap.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Real Oboe/JNI host controls and stereo capture; no claim about audible hardware output. */
@RunWith(AndroidJUnit4::class)
class AquariumPerformanceTest {
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Aquarium timed out waiting for $description" }
            Thread.sleep(5)
        }
    }

    private fun settle(engine: PadEngine) {
        val target = engine.framesRendered() + (engine.sampleRate() * .1).toLong()
        await("live transition and callback") { engine.framesRendered() >= target }
    }

    private fun capture(engine: PadEngine, pad: KitPad, id: Int): Snip {
        assertTrue("the loaded pad must play", engine.hit(pad, 1f, id))
        assertTrue("the stereo print must arm", engine.armPrint(.04f))
        await("the print to fill") { engine.printState() == PadEngine.PrintState.DONE }
        val result = engine.stopPrint()
        assertNotNull(result)
        return result!!
    }

    @Test fun configured_wet_mute_and_global_bypass_reach_the_real_stereo_print() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "aquarium-performance-${System.nanoTime()}").apply { check(mkdirs()) }
        val engine = PadEngine(48000)
        try {
            // Different channel values prove stereo survives the real print.
            val audio = Snip(FloatArray(44100 * 4) { if (it % 2 == 0) .3f else -.1f }, 2, 44100)
            WavWriter.write(File(root, "pad.wav"), audio)
            val pad = KitPad(1, "pad.wav")
            val kit = Kit("Aquarium performance", listOf(pad), tempoBpm = 123.5f)
            KitStore.save(kit, root)
            assertTrue("the emulator/device must provide the same stream the app uses", engine.start())
            engine.load(KitShelf.Entry(root, kit))
            settle(engine)
            val dry = capture(engine, pad, 1)
            assertEquals(2, dry.channels)
            assertEquals(engine.sampleRate(), dry.sampleRate)
            assertTrue("the unmuted fixture must contain actual pad audio", dry.samples.maxOf { abs(it) } > .01f)
            assertTrue("left and right must remain separate", dry.samples.indices.step(2).any {
                abs(dry.samples[it] - dry.samples[it + 1]) > .01f
            })
            engine.allOff()
            settle(engine)
            val controls = AquariumRecipe(mapOf("aqua_tide_mix" to 1f, "aqua_tide_depth" to 0f), seed = 0xffff_ffffL)
            val wetMute = AquariumPerformanceSettings(controls, enabled = true, outputGain = 0f)
            assertTrue(engine.setAquariumTempo(123.5))
            assertTrue(engine.configureAquarium(wetMute, reset = true))
            settle(engine)
            assertEquals(wetMute, engine.aquariumSettings())
            val muted = capture(engine, pad, 2)
            assertEquals(0f, muted.samples.maxOf { abs(it) }, 0f)
            assertFalse(engine.aquariumFaulted())
            assertEquals(.99f, NativePads.aquariumOutputCeiling(), 0f)
            engine.allOff()
            settle(engine)
            val bypass = wetMute.copy(enabled = false)
            assertTrue(engine.configureAquarium(bypass))
            settle(engine)
            val restored = capture(engine, pad, 3)
            assertTrue("bypass must restore dry even when the saved wet gain is zero", restored.samples.maxOf { abs(it) } > .01f)
            assertEquals(bypass, engine.aquariumSettings())
        } finally {
            engine.close()
            root.deleteRecursively()
        }
    }

    @Test fun settings_tempo_validation_and_closed_handle_refusal_are_explicit() {
        val engine = PadEngine(48000)
        try {
            val settings = AquariumPerformanceSettings(
                AquariumRecipe(mapOf("aqua_surface_mix" to 1f), seed = 0x8000_0000L), true, .125f,
            )
            assertTrue(engine.configureAquarium(settings))
            assertTrue(engine.setAquariumTempo(1000.0))
            assertEquals(settings, engine.aquariumSettings())
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -.001, 1000.001)) {
                var refused = false
                try { engine.setAquariumTempo(bad) } catch (error: IllegalArgumentException) {
                    refused = error.message!!.startsWith("Aquarium host BPM must be finite and in 0..1000:")
                }
                assertTrue("invalid live tempo must refuse with its own guard", refused)
            }
        } finally { engine.close() }
        assertFalse(engine.configureAquarium(AquariumPerformanceSettings()))
        assertFalse(engine.setAquariumTempo(120.0))
        assertEquals(0L, engine.aquariumLimitedFrames())
    }
}
