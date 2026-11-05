package com.snipsnap.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.shell.TouchSurface
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/** Real Oboe/JNI print contract. This does not measure phone deadlines or claim a listening verdict. */
@RunWith(AndroidJUnit4::class)
class SurfaceAquariumTest {
    private fun capture(engine: SurfaceEngine, seconds: Float = .125f): Snip {
        assertTrue("the stereo surface print must arm", engine.armPrint(seconds))
        val deadline = System.nanoTime() + 10_000_000_000L
        while (engine.printState() != SurfaceEngine.PrintState.DONE) {
            check(System.nanoTime() < deadline) { "SURFACE print did not finish" }
            Thread.sleep(5)
        }
        return engine.stopPrint() ?: error("SURFACE lost a completed print")
    }

    private fun voice(engine: SurfaceEngine) {
        assertTrue("a real audio stream must open", engine.start())
        engine.load(Snip(FloatArray(256) { (.4 * sin(2 * Math.PI * it / 32)).toFloat() }, 1, 48000))
        engine.control(TouchSurface.Mode.XY, TouchSurface.Reading.REST.copy(x = .5f, y = 1f, touching = true), 0f, gate = true)
        capture(engine)
    }

    @Test fun dry_and_wet_surface_prints_preserve_stereo_frame_count_and_exported_width() {
        val engine = SurfaceEngine(48000)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "surface-aquarium-${System.nanoTime()}.wav")
        try {
            voice(engine)
            val dry = capture(engine)
            assertEquals(2, dry.channels)
            assertEquals((.125f * engine.sampleRate).toInt() * 2, dry.samples.size)
            assertTrue(dry.samples.maxOf { abs(it) } > .01f)
            assertTrue(dry.samples.indices.step(2).all { dry.samples[it] == dry.samples[it + 1] })
            val settings = AquariumPerformanceSettings(AquariumRecipe(mapOf(
                "aqua_tide_mix" to 1f, "aqua_tide_depth" to .8f, "aqua_tide_rate" to 2f, "aqua_tide_stereoPhase" to 180f,
            ), seed = 0xffff_ffffL), enabled = true, outputGain = .125f)
            assertTrue(engine.configureAquarium(settings, 123.5, reset = true))
            capture(engine)
            val wet = capture(engine, .25f)
            assertEquals(2, wet.channels)
            assertEquals((.25f * engine.sampleRate).toInt() * 2, wet.samples.size)
            assertTrue("the effect must create actual left/right differences", wet.samples.indices.step(2).any {
                abs(wet.samples[it] - wet.samples[it + 1]) > 1e-4f
            })
            // The engine runs at the device rate (48 kHz here); this checks that the
            // write-and-read-back keeps the stereo width, not that the file is MPC-native.
            WavWriter.write(file, wet, allowNonMpcRate = true)
            val saved = WavReader.read(file)
            assertEquals(2, saved.channels)
            assertTrue("saving must retain effect width", saved.samples.indices.step(2).any {
                abs(saved.samples[it] - saved.samples[it + 1]) > 1e-4f
            })
            assertFalse(engine.aquariumFaulted())
        } finally { engine.close(); file.delete() }
    }

    @Test fun panic_resume_keeps_a_saved_wet_mute_and_bypass_restores_dry() {
        val engine = SurfaceEngine(48000)
        try {
            voice(engine)
            val mute = AquariumPerformanceSettings(AquariumRecipe(mapOf("aqua_tide_mix" to 1f, "aqua_tide_depth" to 0f)), true, 0f)
            assertTrue(engine.configureAquarium(mute))
            capture(engine)
            engine.panic()
            capture(engine)
            engine.control(TouchSurface.Mode.XY, TouchSurface.Reading.REST.copy(x = .5f, y = 1f, touching = true), 0f, gate = true)
            capture(engine)
            assertEquals(0f, capture(engine).samples.maxOf { abs(it) }, 0f)
            assertTrue(engine.configureAquarium(mute.copy(enabled = false)))
            capture(engine)
            assertTrue(capture(engine).samples.maxOf { abs(it) } > .01f)
        } finally { engine.close() }
        assertFalse(engine.configureAquarium(AquariumPerformanceSettings()))
    }
}
