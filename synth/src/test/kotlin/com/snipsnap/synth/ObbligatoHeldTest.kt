package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Judge the introduced blend against the independent capture as well as the assembled wrap. */
class ObbligatoHeldTest {
    private val relationships = listOf(ObbligatoRelationship.HARMONY, ObbligatoRelationship.PLAY,
        ObbligatoRelationship.FIGHT, ObbligatoRelationship.PROTECT)

    private fun patch(relationship: ObbligatoRelationship = ObbligatoRelationship.HARMONY,
        midi: Int = 60, seed: Int = 0) = ObbligatoPatch("Held $relationship",
        Obbligato.defaults() + mapOf("BOND" to relationship.bond, "HOLD" to 1f), midi = midi,
        seed = seed, model = Obbligato.PERFORMANCE_MODEL, playback = ObbligatoPlayback.HELD)

    private fun energy(samples: FloatArray, first: Int, last: Int): Double {
        require(first >= 0 && last <= samples.size && last > first)
        return (first until last).sumOf { samples[it].toDouble() * samples[it] } / (last - first)
    }

    private fun leadingSeconds(air: FloatArray, reed: FloatArray, first: Int, last: Int): Pair<Double, Double> {
        val window = (.1 * Dsp.RATE).toInt()
        val hop = (.025 * Dsp.RATE).toInt()
        var a = 0
        var r = 0
        var frame = first
        while (frame + window <= last) {
            val ap = energy(air, frame, frame + window)
            val rp = energy(reed, frame, frame + window)
            if (ap > rp * 1.3225) a += hop
            if (rp > ap * 1.3225) r += hop
            frame += hop
        }
        return a.toDouble() / Dsp.RATE to r.toDouble() / Dsp.RATE
    }

    @Test
    fun `held phrases retain both heard leaders and finite bounded audio across the wider register`() {
        for (relationship in relationships) for (midi in listOf(48, 60, 84)) for (velocity in listOf(.3f, .8f)) {
            val held = ObbligatoHeld.render(patch(relationship, midi).copy(velocity = velocity))
            val label = "$relationship MIDI $midi velocity $velocity"
            val samples = held.snip.samples
            assertEquals(1, held.snip.channels)
            assertEquals(44_100, held.snip.sampleRate)
            assertEquals(samples.size, held.loopEndExclusive)
            assertTrue(held.loopStartFrame >= (1.2 * Dsp.RATE).toInt(), "$label lost its entry")
            assertTrue(held.diagnostics.loopSeconds in 4.0..8.0, "$label lost its bounded, complete phrase")
            assertTrue(samples.all { it.isFinite() && it in -1f..1f })
            assertTrue(abs(samples.average()) < .015, "$label excessive DC")
            assertTrue(samples.indices.all { abs(held.air[it] + held.reed[it] - samples[it]) < 1e-6f },
                "$label taps no longer reconstruct the captured duet")
            assertTrue(Keys.seamError(samples, held.loopStartFrame) < Keys.MAX_SEAM_ERROR,
                "$label the assembled continuation does not close")
            val blend = (held.diagnostics.blendSeconds * Dsp.RATE).roundToInt()
            val fade = samples.size - 256 - blend
            assertTrue(held.conversationEvents.none { it.kind == "SETTLE" }, "$label repeats the ending gesture")
            assertTrue(energy(held.air, held.loopStartFrame, fade) > 1e-8 &&
                energy(held.reed, held.loopStartFrame, fade) > 1e-8, "$label loses a pipe in its sustain")
            if (relationship == ObbligatoRelationship.PLAY || relationship == ObbligatoRelationship.FIGHT) {
                val (air, reed) = leadingSeconds(held.air, held.reed, held.loopStartFrame, fade)
                assertTrue(air >= .05 && reed >= .05, "$label sustain freezes one player's assertion")
                assertTrue(held.diagnostics.actorChanges >= 2, "$label sustain contains no complete exchange")
            }
            if (relationship == ObbligatoRelationship.PROTECT) {
                val openingAir = energy(held.air, (.25 * Dsp.RATE).toInt(), (.60 * Dsp.RATE).toInt())
                val openingReed = energy(held.reed, (.25 * Dsp.RATE).toInt(), (.60 * Dsp.RATE).toInt())
                assertTrue(openingAir > 1e-8 && openingReed < openingAir * .0001,
                    "$label the held articulation obscures the initial flute")
                var frame = held.loopStartFrame
                val window = (.10 * Dsp.RATE).toInt()
                while (frame + window < held.loopEndExclusive) {
                    assertTrue(energy(held.reed, frame, frame + window) > 1e-8,
                        "$label reintroduces the silent supporting-reed entrance inside the loop")
                    frame += window
                }
            }
        }
    }

    @Test
    fun `new blend correlation energy and waveform steps agree with independent unmodified source`() {
        for (patch in listOf(patch(ObbligatoRelationship.PLAY), patch(ObbligatoRelationship.FIGHT),
            patch(ObbligatoRelationship.PROTECT), patch(ObbligatoRelationship.PROTECT, 48).copy(velocity = .3f))) {
            val relationship = "${patch.name} MIDI ${patch.midi} velocity ${patch.velocity}"
            val held = ObbligatoHeld.render(patch)
            val sourceSeconds = held.diagnostics.sourceSeconds - .75
            if (patch.midi == 48) assertEquals(14.0, sourceSeconds,
                "soft Protect C3 must exercise the bounded longer-source path")
            val source = Obbligato.heldReport(patch.macros, patch.midi, patch.velocity, patch.seed, patch.model,
                sourceSeconds = sourceSeconds)
            val x = source.snip.samples
            val y = held.snip.samples
            val gain = held.diagnostics.gain
            val blend = (held.diagnostics.blendSeconds * Dsp.RATE).roundToInt()
            val length = held.loopEndExclusive - held.loopStartFrame
            val a0 = held.loopStartFrame - 256 - blend
            val b0 = held.loopEndExclusive - 256 - blend
            var aa = 0.0
            var bb = 0.0
            var dot = 0.0
            for (j in 0 until blend) {
                aa += x[a0 + j].toDouble() * x[a0 + j]
                bb += x[b0 + j].toDouble() * x[b0 + j]
                dot += x[a0 + j].toDouble() * x[b0 + j]
            }
            val correlation = dot / sqrt(aa * bb)
            assertTrue(correlation >= .9, "$relationship independent blend endpoints do not correlate")
            assertEquals(correlation, held.diagnostics.correlation, 1e-6)
            // This compares the introduced blend with independently observed source
            // envelopes. The zero assembled tail error alone would be insufficient.
            val window = (.025 * Dsp.RATE).toInt()
            val hop = window / 2
            var worst = 0.0
            var j = 0
            while (j + window <= blend) {
                val oldPower = energy(x, b0 + j, b0 + j + window)
                val nextPower = energy(x, a0 + j, a0 + j + window)
                val theta = .5 * PI * (j + window * .5) / blend
                val fraction = sin(theta) / (cos(theta) + sin(theta))
                val expected = (oldPower * (1.0 - fraction) + nextPower * fraction) * gain * gain
                val actual = energy(y, b0 + j, b0 + j + window)
                if (expected > 1e-24) worst = minOf(worst, 10.0 * log10(actual / expected))
                j += hop
            }
            assertTrue(worst >= -1.5, "$relationship introduced blend loses $worst dB of local source energy")
            assertEquals(worst, held.diagnostics.blendDipDb, 1e-4)
            assertTrue(abs(held.diagnostics.levelStepDb) <= 2.5)
            val expectedStep = abs(x[held.loopStartFrame].toDouble() - x[held.loopStartFrame - 1]) * gain
            val actualStep = abs(y[held.loopStartFrame].toDouble() - y.last())
            assertEquals(expectedStep, actualStep, 2e-6, "$relationship wrap differs from its physical source neighbour")
            for (i in (.05 * Dsp.RATE).toInt() until (.6 * Dsp.RATE).toInt())
                assertTrue(abs(y[i] - x[i] * gain) < 1e-6, "$relationship held capture changes its opening")
            // Play four actual wrap boundaries. Each joins the same physical neighbour;
            // correlation and local-energy measurements above judge the new fade.
            val repeated = y.copyOfRange(0, held.loopStartFrame) +
                FloatArray(length * 4) { y[held.loopStartFrame + it % length] }
            for (cycle in 1..3) {
                val boundary = held.loopStartFrame + cycle * length
                assertEquals(actualStep, abs(repeated[boundary].toDouble() - repeated[boundary - 1]), 1e-9)
            }
        }
    }

    @Test
    fun `completed note cache owns its audio and distinguishes every audible playing input`() {
        ObbligatoHeld.clearCache()
        val source = patch()
        val first = ObbligatoHeld.render(source)
        val sum = first.snip.samples.copyOf()
        val air = first.air.copyOf()
        val reed = first.reed.copyOf()
        first.snip.samples.fill(Float.NaN)
        first.air.fill(Float.NaN)
        first.reed.fill(Float.NaN)
        val recalled = ObbligatoHeld.render(source.copy(name = "Another name", releaseSeconds = .95f))
        assertContentEquals(sum, recalled.snip.samples)
        assertContentEquals(air, recalled.air)
        assertContentEquals(reed, recalled.reed)
        assertEquals(1, ObbligatoHeld.cacheStats().entries)
        assertEquals(1L, ObbligatoHeld.cacheStats().hits)
        val changed = listOf(
            source.copy(seed = -17), source.copy(velocity = .6f), source.copy(midi = 67),
            source.copy(macros = source.macros + ("BREATH" to .55f)),
            source.copy(macros = source.macros + ("MISCHIEF" to .37f)),
            source.copy(macros = source.macros + ("BOND" to ObbligatoRelationship.PLAY.bond)),
        )
        for (patch in changed) {
            val before = ObbligatoHeld.cacheStats()
            val captured = ObbligatoHeld.render(patch)
            val after = ObbligatoHeld.cacheStats()
            assertEquals(before.misses + 1, after.misses, "${patch.name}: changed source state reused another note")
            assertTrue(!sum.contentEquals(captured.snip.samples), "an audible playing input has no effect")
        }
        // Cross the entry limit with real different notes, rather than asserting a
        // bound over a cache that has never needed to evict anything.
        ObbligatoHeld.render(source.copy(midi = 63))
        ObbligatoHeld.render(source.copy(midi = 72))
        val stats = ObbligatoHeld.cacheStats()
        assertTrue(stats.entries <= 8 && stats.bytes <= 32L * 1024 * 1024)
    }

    @Test
    fun `cancelled captures never publish partial cache entries and silent controls remain silent`() {
        ObbligatoHeld.clearCache()
        var calls = 0
        assertFailsWith<CancellationException> {
            ObbligatoHeld.render(patch()) { ++calls >= 40 }
        }
        assertTrue(calls >= 40, "the physical render does not offer bounded cancellation checks")
        assertEquals(0, ObbligatoHeld.cacheStats().entries)
        val silent = patch().copy(velocity = 0f)
        val captured = ObbligatoHeld.render(silent)
        assertTrue(captured.snip.samples.all { it == 0f })
        assertTrue(captured.air.all { it == 0f } && captured.reed.all { it == 0f })
        assertTrue(captured.diagnostics.silent)
        assertFailsWith<CancellationException> { ObbligatoHeld.render(silent) { true } }
    }
}
