package com.snipsnap.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.audio.Separate
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Kit
import com.snipsnap.shell.Layers
import com.snipsnap.shell.SplitAquarium
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class SplitAquariumRendererTest {
    private val rate = 48000
    private val parts = Separate.Stn(
        Snip(FloatArray(2049) { (.7 * sin(.1 * it)).toFloat() }, 1, rate),
        Snip(FloatArray(2049) { (.4 * sin(.07 * it)).toFloat() }, 1, rate),
        Snip(FloatArray(2049) { ((it % 11) - 5) * .04f }, 1, rate),
    )
    private val desk = Layers.Desk(Layers.Strip(2f, reverse = true), Layers.Strip(1.5f), Layers.Strip(.5f))
    private val sound = AquariumPerformanceSettings(AquariumRecipe(mapOf(
        "aqua_tide_mix" to 1f, "aqua_tide_depth" to .8f, "aqua_tide_stereoPhase" to 180f,
        "aqua_reef_mix" to .7f,
    ), seed = 0xffff_ffffL), true, .125f)
    private val kit = Kit("K", emptyList(), tempoBpm = 123.5f, aquariumPerformance = sound.toJsonValue())

    @Test fun old_dry_print_is_exact_and_patched_stereo_matches_the_selected_bus_with_an_exact_tail() = runBlocking {
        val off = SplitAquarium.Settings()
        val raw = Layers.render(parts, desk)
        assertTrue(raw.samples.maxOf { abs(it) } > 1f)
        val dry = SplitAquariumRenderer.render(parts, desk, off, SplitAquarium.route(kit, off))
        assertEquals(raw.channels, dry.channels); assertArrayEquals(raw.samples, dry.samples, 0f)
        val on = SplitAquarium.Settings(true, .125f)
        val route = SplitAquarium.route(kit, on)
        val actual = SplitAquariumRenderer.render(parts, desk, on, route)
        assertEquals(2, actual.channels); assertEquals((2049 + 6000) * 2, actual.samples.size)
        val expected = FloatArray(actual.samples.size) { i ->
            val frame = i / 2
            if (frame < raw.frameCount) raw.samples[frame].coerceIn(-1f, 1f) else 0f
        }
        AquariumPerformanceProcessor(rate, sound, initialBpm = 123.5).use { bus -> bus.process(expected, 123.5) }
        assertArrayEquals(expected, actual.samples, 1e-6f)
        assertTrue(actual.samples.indices.step(2).any { abs(actual.samples[it] - actual.samples[it + 1]) > 1e-4f })
        assertTrue(actual.samples.drop(2049 * 2).any { abs(it) > 1e-7f })
        assertTrue(actual.samples.maxOf { abs(it) } <= .99f)
    }

    @Test fun saved_wet_mute_is_silent_from_frame_zero_and_global_bypass_has_no_tail() = runBlocking {
        val on = SplitAquarium.Settings(true)
        val mute = kit.copy(aquariumPerformance = sound.copy(outputGain = 0f).toJsonValue())
        val muted = SplitAquariumRenderer.render(parts, desk, on, SplitAquarium.route(mute, on))
        assertEquals(0f, muted.samples.maxOf { abs(it) }, 0f)
        val bypass = kit.copy(aquariumPerformance = sound.copy(enabled = false, outputGain = 0f).toJsonValue())
        val dry = SplitAquariumRenderer.render(parts, desk, on, SplitAquarium.route(bypass, on))
        val raw = Layers.render(parts, desk)
        assertEquals(2049 * 2, dry.samples.size)
        assertTrue(dry.samples.maxOf { abs(it) } > .01f)
        for (f in 0 until raw.frameCount) {
            assertEquals(raw.samples[f].coerceIn(-1f, 1f), dry.samples[2 * f], 0f)
            assertEquals(dry.samples[2 * f], dry.samples[2 * f + 1], 0f)
        }
    }

    @Test fun an_already_cancelled_print_refuses_before_source_validation_or_native_preparation() {
        val cancelled = Job().apply { cancel(CancellationException("cancelled SPLIT print")) }
        val empty = Snip(FloatArray(0), 1, rate)
        val invalid = Separate.Stn(empty, empty, empty)
        var result: Result<Snip>? = null
        val render: suspend () -> Snip = {
            SplitAquariumRenderer.render(invalid, desk, SplitAquarium.Settings(), SplitAquarium.route(null, SplitAquarium.Settings()))
        }
        render.startCoroutine(object : Continuation<Snip> {
            override val context: CoroutineContext = cancelled
            override fun resumeWith(value: Result<Snip>) { result = value }
        })
        assertTrue(result?.exceptionOrNull() is CancellationException)
    }
}
