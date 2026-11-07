package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Model-four behavior; frozen audition PCM is independently covered by the compatibility fixture. */
class OnsetExtensionsTest {
    private val voices = listOf(OnsetVoice.TOM, OnsetVoice.PERC_PLUCK, OnsetVoice.PERC_WOOD, OnsetVoice.PERC_METAL)

    @Test fun `new voices start in model four and defaults retain the audition registers`() {
        val roots = listOf(125f, 300f, 360f, 930f)
        for ((voice, root) in voices.zip(roots)) {
            val s = Onset.settingsFor(voice)
            assertEquals(4, s.modelVersion)
            assertEquals(root, assertNotNull(s.rootHz), .001f, "$voice register")
            assertEquals(s.rootHz, s.electronicRootHz)
            assertEquals(s.rootHz, s.physicalRootHz)
            assertEquals(listOf("TUNE", "DECAY", "BLEND", "SNAP", "BODY", "EDGE"), s.macros.keys.toList())
            for (version in 1..3) {
                assertFailsWith<IllegalArgumentException>("$voice model $version") {
                    Onset.render(voice, modelVersion = version)
                }
            }
        }
    }

    @Test fun `defaults are deterministic and invalid controls fall back without poisoning a render`() {
        for (voice in voices) {
            val expected = Onset.render(voice)
            assertContentEquals(expected.samples, Onset.render(voice).samples, "$voice determinism")
            val invalid = mapOf("TUNE" to Float.NaN, "EDGE" to Float.POSITIVE_INFINITY, "UNKNOWN" to .9f)
            assertEquals(Onset.defaults(voice), Onset.settingsFor(voice, invalid).macros)
            assertContentEquals(expected.samples, Onset.render(voice, invalid).samples, "$voice invalid controls")
            val clamped = Onset.settingsFor(voice, mapOf("TUNE" to -5f, "EDGE" to 4f))
            assertEquals(0f, clamped.macros.getValue("TUNE"))
            assertEquals(1f, clamped.macros.getValue("EDGE"))
        }
    }

    @Test fun `all extension macro corners are finite bounded one shots with a decaying body`() {
        for (voice in voices) {
            val names = Onset.macrosFor(voice).map { it.name }
            for (bits in 0 until (1 shl names.size)) {
                val macros = names.mapIndexed { index, name ->
                    name to if (bits and (1 shl index) == 0) 0f else 1f
                }.toMap()
                val snip = Onset.render(voice, macros)
                val context = "$voice corner $bits"
                assertTrue(snip.samples.all { it.isFinite() }, "$context finite")
                assertTrue(snip.peak() in .005f.. .99f, "$context fixed headroom: ${snip.peak()}")
                assertEquals(1, snip.channels)
                assertEquals(44100, snip.sampleRate)
                assertTrue(snip.durationSeconds < 1.5f, "$context finite one-shot length")
                assertTrue(abs(snip.samples.average()) < .005, "$context DC")
                // The reference is a separate early window, never the whole-buffer peak.
                val early = windowRms(snip.samples, 0, minOf(snip.samples.size, 1764))
                val late = windowRms(snip.samples, snip.samples.size * 3 / 4, snip.samples.size)
                assertTrue(late < early * .05, "$context late energy grew: $late / $early")
            }
        }
    }

    @Test fun `every control changes level independent shape at low and middle tuning`() {
        for (voice in voices) for (tune in listOf(.25f, .5f)) {
            for (macro in Onset.macrosFor(voice).map { it.name }) {
                val a = Onset.render(voice, mapOf("TUNE" to tune, macro to .2f)).samples
                val b = Onset.render(voice, mapOf("TUNE" to tune, macro to .8f)).samples
                assertTrue(shapeDistance(a, b) > .01, "$voice $macro tune=$tune has no shape response")
            }
        }
    }

    @Test fun `pluck and wood hand electronic contact into material body without ring multiplication`() {
        for (voice in listOf(OnsetVoice.PERC_PLUCK, OnsetVoice.PERC_WOOD)) {
            val p = Onset.probe(voice, mapOf("BLEND" to .5f))
            val electronic = assertNotNull(p.electronicWeight)
            val physical = assertNotNull(p.physicalWeight)
            val afterContact = minOf(electronic.lastIndex, (Dsp.RATE * Dsp.OVERSAMPLE * .04).toInt())
            assertTrue(electronic[afterContact] < electronic[0] * .7f, "$voice electronic front should settle")
            assertTrue(physical[afterContact] > physical[0] * 3f, "$voice material body should arrive")
            assertTrue(electronic.all { it in 0f..1f } && physical.all { it in 0f..1f })
            for (i in 1 until electronic.size) {
                assertTrue(electronic[i] <= electronic[i - 1], "$voice electronic handoff reverses")
                assertTrue(physical[i] >= physical[i - 1], "$voice material handoff reverses")
            }
            val electronicEnd = Onset.probe(voice, mapOf("BLEND" to 0f))
            val materialEnd = Onset.probe(voice, mapOf("BLEND" to 1f))
            assertTrue(assertNotNull(electronicEnd.electronicWeight).all { it == 1f })
            assertTrue(assertNotNull(electronicEnd.physicalWeight).all { it == 0f })
            assertTrue(assertNotNull(materialEnd.electronicWeight).all { it == 0f })
            assertTrue(assertNotNull(materialEnd.physicalWeight).all { it == 1f })
        }
    }

    @Test fun `tom and metal retain bounded amplitude modulation inside their blend`() {
        for (voice in listOf(OnsetVoice.TOM, OnsetVoice.PERC_METAL)) {
            val p = Onset.probe(voice, mapOf("BLEND" to .5f))
            val electronic = assertNotNull(p.electronicWeight)
            val physical = assertNotNull(p.physicalWeight)
            assertTrue(electronic.all { it == electronic[0] }, "$voice electronic source is the root reference")
            assertTrue(physical.any { it < 0f }, "$voice material AM lost its translated partials")
            assertTrue(physical.all { abs(it) <= 1f }, "$voice material AM exceeded its bound")
        }
    }

    private fun windowRms(samples: FloatArray, start: Int, end: Int): Double {
        var energy = 0.0
        for (i in start until end) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (end - start))
    }

    private fun shapeDistance(a: FloatArray, b: FloatArray): Double {
        var aa = 0.0
        var bb = 0.0
        for (v in a) aa += v.toDouble() * v
        for (v in b) bb += v.toDouble() * v
        val gain = sqrt(aa / bb.coerceAtLeast(1e-30))
        var difference = 0.0
        for (i in 0 until maxOf(a.size, b.size)) {
            val delta = a.getOrElse(i) { 0f } - b.getOrElse(i) { 0f } * gain
            difference += delta * delta
        }
        return sqrt(difference / aa.coerceAtLeast(1e-30))
    }
}
