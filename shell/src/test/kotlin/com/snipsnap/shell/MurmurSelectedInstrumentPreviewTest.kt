package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurSelectedInstrumentSource
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Reference-player plumbing with generated synthetic captures, not MURMUR native audio proof. */
class MurmurSelectedInstrumentPreviewTest {
    @Test
    fun `single held preview uses middle touch and declared sampler key up through silent end`() {
        val spec = MurmurSelectedInstrumentMaker.Spec(MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch())
        val seen = mutableListOf<MurmurSelectedInstrumentSource.CellRecipe>()
        val preview = MurmurSelectedInstrumentPreview.renderUsing(spec, { false }) { recipe ->
            seen += recipe
            fake(recipe)
        }
        assertEquals(1, seen.size)
        assertEquals(.86f, seen.single().sourcePatch().velocity)
        assertEquals(.65f, seen.single().cellPatch().velocity)
        assertEquals(spec.sourcePatch().midi, seen.single().midi)
        assertEquals(2, preview.channels)
        assertTrue(preview.frameCount > 3 * 1024)
        assertTrue(preview.samples.any { it != 0f })
        assertTrue(preview.samples.takeLast(64).all { it == 0f })
    }

    @Test
    fun `generated chord with one unsupported final tone refuses before any capture`() {
        val source = MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch().copy(midi = 48)
        val spec = MurmurSelectedInstrumentMaker.Spec(source)
        var calls = 0
        val failure = assertFailsWith<IllegalArgumentException> {
            MurmurSelectedInstrumentPreview.renderChordUsing(spec, listOf(48, 51, 55), { false }) { recipe ->
                calls++
                fake(recipe)
            }
        }
        assertTrue(failure.message.orEmpty().contains("outside"))
        assertEquals(0, calls)
    }

    @Test
    fun `in range offline chord freezes authored source and releases every reference voice`() {
        val spec = MurmurSelectedInstrumentMaker.Spec(MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch())
        val seen = mutableListOf<MurmurSelectedInstrumentSource.CellRecipe>()
        val preview = MurmurSelectedInstrumentPreview.renderChordUsing(spec, listOf(44, 48, 51), { false }) { recipe ->
            seen += recipe
            fake(recipe)
        }
        assertEquals(listOf(45, 48, 51), seen.map { it.midi }, "preview uses actual covering native grid roots")
        assertTrue(seen.all { it.sourcePatch() == spec.sourcePatch() })
        assertTrue(seen.all { it.cellPatch().velocity == .65f })
        assertTrue(preview.samples.any { it != 0f })
        assertTrue(preview.samples.takeLast(64).all { it == 0f })
    }

    @Test
    fun `cancel during a chord capture suppresses preview construction`() {
        val spec = MurmurSelectedInstrumentMaker.Spec(MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch())
        var cancelled = false
        var calls = 0
        assertFailsWith<CancellationException> {
            MurmurSelectedInstrumentPreview.renderChordUsing(spec, listOf(48, 51, 55), { cancelled }) { recipe ->
                calls++
                cancelled = true
                fake(recipe)
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `chord preview uses emitted grid roots and sampler transposition rather than new per tone native renders`() {
        val spec = MurmurSelectedInstrumentMaker.Spec(MurmurKeptSources.byId(MurmurKeptSources.HELD_WIRE).factoryPatch())
        val roots = mutableListOf<Int>()
        MurmurSelectedInstrumentPreview.renderChordUsing(spec, listOf(60, 64, 67), { false }) { recipe ->
            roots += recipe.midi
            fake(recipe)
        }
        assertEquals(listOf(60, 63, 66), roots)
        roots.clear()
        MurmurSelectedInstrumentPreview.renderChordUsing(spec, listOf(63, 64), { false }) { recipe ->
            roots += recipe.midi
            fake(recipe)
        }
        assertEquals(listOf(63), roots, "two played notes may share one real instrument sample root")
    }

    @Test
    fun `finite preview exercises short key up before the complete source would finish`() {
        val spec = MurmurSelectedInstrumentMaker.Spec(MurmurKeptSources.byId(MurmurKeptSources.FINITE_WIRE).factoryPatch(), .01f)
        val preview = MurmurSelectedInstrumentPreview.renderUsing(spec, { false }) { recipe ->
            val samples = FloatArray(WavWriter.MPC_SAMPLE_RATE) { .2f }
            samples[samples.lastIndex] = 0f
            val hash = MurmurSelectedInstrumentSource.floatSha256(samples)
            MurmurSelectedInstrumentSource.Capture(recipe, Snip(samples, 1, WavWriter.MPC_SAMPLE_RATE),
                0, samples.size, hash, hash, hash, 0.0, 261.625565)
        }
        assertTrue(preview.frameCount < WavWriter.MPC_SAMPLE_RATE, "a short press must not wait for the whole finite source")
        assertTrue(preview.samples.any { it != 0f })
        assertTrue(preview.samples.takeLast(64).all { it == 0f })
    }

    private fun fake(recipe: MurmurSelectedInstrumentSource.CellRecipe): MurmurSelectedInstrumentSource.Capture {
        val held = recipe.outputPolicy == MurmurSelectedInstrumentSource.OutputPolicy.HOUSE_ADJACENT_CONTEXT_V1
        val frames = if (held) 2048 else 1024
        // Independently generated continuous sine frames; no native predecessor is claimed.
        val samples = FloatArray(frames) { (.15 * sin(2 * PI * it / 128)).toFloat() }
        if (!held) samples[samples.lastIndex] = 0f
        val hash = MurmurSelectedInstrumentSource.floatSha256(samples)
        return MurmurSelectedInstrumentSource.Capture(recipe, Snip(samples, 1, WavWriter.MPC_SAMPLE_RATE),
            if (held) 1024 else 0, frames, hash, hash, hash, 0.0, 261.625565)
    }
}
