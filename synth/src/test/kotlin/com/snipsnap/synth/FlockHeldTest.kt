package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlockHeldTest {
    private fun patch(flow: Float = .78f, midi: Int = 60, velocity: Float = 1f, model: Int = 2) =
        FlockPatch("Held ribbon", FlockVoice.RIBBON, Flock.defaults(FlockVoice.RIBBON) + ("FLOW" to flow),
            midi, velocity, 42001L, model, FlockPlayback.HELD)

    @Test
    fun `native capture retains exact source attack body and independent natural wrap context`() {
        for (model in listOf(1, 2)) {
            val p = patch(flow = .3f, velocity = .37f, model = model)
            val held = FlockHeld.renderUncached(p, oversample = 1)
            val source = Flock.renderContinuousRibbon(8.2, p.macros, p.midi, p.velocity, p.seed,
                oversample = 1, model = model).rawSamples
            val intro = held.loopStartFrame
            val end = held.loopEndExclusive
            val blend = Dsp.RATE / 2
            val gain = held.diagnostics.commonGain
            assertEquals(2 * Dsp.RATE, intro)
            assertEquals(end, held.snip.samples.size)
            assertTrue(end - intro in (5.9 * Dsp.RATE).toInt()..(6.1 * Dsp.RATE).toInt())
            assertEquals(357_226, held.diagnostics.sourceFrames)
            for (i in 2_000 until end - blend step 101)
                assertEquals((source[i] * gain).toFloat(), held.snip.samples[i], "model$model source frame$i")
            // This compares the actual outgoing tail to independently rendered source
            // samples before the start, rather than comparing an already repeated loop.
            assertEquals((source[intro - 1] * gain).toFloat(), held.snip.samples.last())
            assertEquals((source[intro] * gain).toFloat(), held.snip.samples[intro])
            assertEquals((source[end - blend] * gain).toFloat(), held.snip.samples[end - blend])
            assertEquals(0f, held.snip.samples.first())
            assertTrue(abs(held.snip.samples.last()) > 1e-8, "loop sample was tail faded")
        }
    }

    @Test
    fun `short four times capture retains the full continuation prefix through every endpoint`() {
        val p = patch(flow = .35f, velocity = .6f)
        val short = Flock.captureContinuousRibbon(357_226, p.macros, p.midi, p.velocity, p.seed, p.model, { false })
        val long = Flock.renderContinuousRibbon(8.2, p.macros, p.midi, p.velocity, p.seed, model = p.model).rawSamples
        assertContentEquals(long.copyOfRange(0, 357_210), short.copyOfRange(0, 357_210),
            "short source lost decimator right context")
    }

    @Test
    fun `flow register and soft strikes retain bounded power and natural phase steps`() {
        for (model in listOf(1, 2)) for (flow in listOf(0f, .78f, 1f)) for (midi in listOf(36, 60, 84)) {
            val velocity = if (midi == 60) 1f else .2f
            val h = FlockHeld.renderUncached(patch(flow, midi, velocity, model), oversample = 1)
            val x = h.snip.samples
            val intro = h.loopStartFrame
            val search = h.diagnostics.endpointSearch
            assertTrue(x.all { it.isFinite() }, "$model/$flow/$midi")
            assertTrue(h.diagnostics.outputPeak in 0.001..0.900001, "$model/$flow/$midi peak")
            assertTrue(abs(x.average()) < .005, "$model/$flow/$midi DC")
            assertTrue(search.selectedMin50msDb + 1e-10 >= search.baselineMin50msDb,
                "$model/$flow/$midi endpoint search worsened cancellation: $search")
            // A blend may beat against itself, but the chosen endpoint should
            // preserve useful body energy even at soft velocity and either clock.
            assertTrue(search.selectedMin50msDb > -7.0, "$model/$flow/$midi blend dropout: $search")
            assertTrue(search.selectedMax50msDb < 5.0, "$model/$flow/$midi blend swelling: $search")
            val naturalStepMax = (intro until x.size - Dsp.RATE / 2 - 1).maxOf { abs(x[it + 1] - x[it]) }
            val naturalSlopeMax = (intro until x.size - Dsp.RATE / 2 - 2).maxOf {
                abs(x[it + 2].toDouble() - 2.0 * x[it + 1] + x[it])
            }
            val jump = x[intro].toDouble() - x.last()
            val before = abs(jump - (x.last().toDouble() - x[x.lastIndex - 1]))
            val after = abs((x[intro + 1].toDouble() - x[intro]) - jump)
            assertTrue(abs(jump) <= naturalStepMax * 1.02 + 1e-7, "$model/$flow/$midi seam jump")
            assertTrue(max(before, after) <= naturalSlopeMax * 1.1 + 1e-6, "$model/$flow/$midi seam slope")
            assertTrue(Loudness.of(h.snip) > .015f, "$model/$flow/$midi lost body")
        }
        val zero = FlockHeld.renderUncached(patch(velocity = 0f), oversample = 1)
        assertTrue(zero.snip.samples.all { it == 0f })
        val soft = FlockHeld.renderUncached(patch(velocity = .2f), oversample = 1)
        val hard = FlockHeld.renderUncached(patch(), oversample = 1)
        fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)
        assertTrue(rms(soft.snip.samples) < .65 * rms(hard.snip.samples), "velocity was leveled away")
    }

    @Test
    fun `nine native roots contain the saved note and stay in supported register`() {
        for (midi in Flock.MIN_MIDI..Flock.MAX_MIDI) {
            val midis = FlockHeld.midis(patch(midi = midi))
            assertEquals(9, midis.size)
            assertTrue(midi in midis)
            assertTrue(midis.all { it in Flock.MIN_MIDI..Flock.MAX_MIDI })
            assertTrue(midis.zipWithNext().all { (a, b) -> b - a == 3 })
        }
        assertEquals((48..72 step 3).toList(), FlockHeld.midis(patch()))
        assertFailsWith<IllegalArgumentException> { FlockHeld.midis(patch().copy(playback = FlockPlayback.FINITE)) }
    }

    @Test
    fun `cache ignores only inaudible state owns its arrays and remains bounded`() {
        FlockHeld.clearCache()
        // Off the shared default anchor, model 1 and model 2 really differ.
        val p = patch(flow = .35f, velocity = .6f)
        val first = FlockHeld.render(p)
        val expected = first.snip.samples.copyOf()
        first.snip.samples.fill(Float.NaN)
        val same = FlockHeld.render(p.copy(name = "Renamed", releaseSeconds = 1.2f, macros = p.macros + ("HOLD" to 0f)))
        assertContentEquals(expected, same.snip.samples, "caller mutated cached audio")
        assertEquals(1, FlockHeld.cacheSize().entries)
        val variations = listOf(p.copy(seed = Long.MIN_VALUE), p.copy(model = 1), p.copy(midi = 61),
            p.copy(velocity = .3f), p.copy(macros = p.macros + ("EDGE" to .9f)))
        for (other in variations) {
            val result = FlockHeld.render(other)
            assertFalse(expected.contentEquals(result.snip.samples), "cache reused a different source: ${other.toJsonText()}")
        }
        assertEquals(6, FlockHeld.cacheSize().entries)
        for (seed in 0L..8L) FlockHeld.render(p.copy(seed = seed))
        assertTrue(FlockHeld.cacheSize().entries <= FlockHeld.CACHE_MAX_ENTRIES)
        assertTrue(FlockHeld.cacheSize().bytes <= FlockHeld.CACHE_MAX_BYTES)
        assertContentEquals(expected, FlockHeld.render(p).snip.samples, "evicted note changed on regeneration")
        FlockHeld.clearCache()
    }

    @Test
    fun `cancellation is checked before cache hits through synthesis and endpoint search`() {
        FlockHeld.clearCache()
        val p = patch()
        var checks = 0
        assertFailsWith<CancellationException> {
            FlockHeld.render(p, cancelled = { ++checks > 3 })
        }
        assertEquals(0, FlockHeld.cacheSize().entries)
        // A callback observes enough native source checks to let synthesis end,
        // then interrupts the shared endpoint search before assembly or caching.
        checks = 0
        assertFailsWith<CancellationException> {
            FlockHeld.renderUncached(p, oversample = 1, cancelled = { ++checks > 350 })
        }
        FlockHeld.render(p)
        assertFailsWith<CancellationException> { FlockHeld.render(p, cancelled = { true }) }
        FlockHeld.clearCache()
    }
}
