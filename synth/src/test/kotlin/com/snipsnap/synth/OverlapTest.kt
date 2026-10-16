package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OverlapTest {
    private val plain = mapOf("KNOT" to 1f, "PULSE" to 0f, "LOCK" to 0f,
        "GLOW" to 0f, "RING" to .65f, "HOLD" to 0f)

    @Test
    fun `serialized pad recipe and edits preserve the actual sound and note`() {
        val patch = OverlapPatch("Saved network", OverlapTopology.TREFOIL, plain, 57, .65f)
        val recipe = PadRecipe(patch = patch)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(patch, restored.patch)
        assertContentEquals(recipe.render().samples, restored.render().samples)
        val edited = Patches.edited(patch, "Edited network", plain + ("GLOW" to .4f)) as OverlapPatch
        assertEquals(57, edited.midi)
        assertEquals(.65f, edited.velocity)
        assertContentEquals(patch.copy(velocity = .25f).render().samples, Velocity.atVelocity(patch, .25f).samples)
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("GLOW" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(macros = mapOf("UNKNOWN" to .5f)) }
        assertFailsWith<IllegalArgumentException> { patch.copy(velocity = Float.POSITIVE_INFINITY) }
        assertTrue(patch.copy(velocity = 0f).render().samples.all { it == 0f })
    }

    @Test
    fun `zero routing depth produces the identical self network for every topology`() {
        val expected = Overlap.probe(OverlapTopology.SELF, plain + ("KNOT" to 0f), seconds = 2.0)
        for (topology in OverlapTopology.entries) {
            val actual = Overlap.probe(topology, plain + ("KNOT" to 0f), seconds = 2.0)
            assertContentEquals(expected.raw, actual.raw, "$topology changes the zero-depth network")
        }
    }

    @Test
    fun `raw impulse responses decay without limiting at extreme routing and glow settings`() {
        for (topology in OverlapTopology.entries) for (depth in listOf(0f, .5f, 1f)) {
            val p = Overlap.probe(topology, plain + mapOf("KNOT" to depth, "RING" to 1f, "GLOW" to 1f), seconds = 4.0)
            assertEquals(44_100, p.sampleRate)
            assertTrue(p.raw.all { it.isFinite() }, "$topology $depth became nonfinite")
            assertTrue(p.diagnostics.maxLoopGain < 1.0, "$topology $depth can expand feedback")
            assertTrue(p.diagnostics.maxBufferAbs < 1.0, "$topology $depth grew internally")
            assertTrue(p.diagnostics.rawPeak in .000001.. .99, "$topology $depth raw output clips or is silent")
            assertTrue(p.diagnostics.lateRms < p.diagnostics.earlyRms, "$topology $depth impulse response does not decay")
        }
    }

    @Test
    fun `legacy routing endpoints still carry the requested root at two registers`() {
        for (topology in OverlapTopology.entries) for (midi in listOf(48, 60)) {
            val p = Overlap.probe(topology, plain, midi = midi, seconds = 2.0, model = 1)
            val estimate = assertNotNull(Pitch.detect(Snip(p.raw, 1, p.sampleRate), .10f, .30f), "$topology MIDI $midi has no detectable note")
            val cents = 1200.0 * ln(estimate.hz / p.diagnostics.targetHz) / ln(2.0)
            assertTrue(abs(cents) < 50.0, "$topology MIDI $midi root differs by $cents cents (${estimate.hz} Hz)")
        }
    }

    @Test
    fun `legacy recipes without model retain the exact published first audition`() {
        val legacy = OverlapPatch("Trefoil", OverlapTopology.TREFOIL, plain, 48, .8f, model = 1)
        val restored = OverlapPatch.fromJsonValue(JsonValue.Obj(legacy.toJsonValue().entries - "model")) as OverlapPatch
        assertEquals(1, restored.model)
        assertContentEquals(legacy.render().samples, restored.render().samples)
        val raw = Overlap.probe(restored.topology, restored.macros, restored.midi, restored.velocity, model = restored.model)
        val encoded = ByteArrayOutputStream().also { WavWriter.write(it, Snip(raw.raw, 1, raw.sampleRate)) }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
        assertEquals("8f768d259acf8695b6f78688de9becba923ffa4dcf36d26d91d97442a77af7c0", hash)
        assertFailsWith<IllegalArgumentException> { legacy.copy(model = 3) }
    }

    @Test
    fun `every intermediate scatter preserves the norm of every input direction`() {
        for (topology in OverlapTopology.entries) for (depth in listOf(0.0, .125, .5, .875, 1.0)) {
            val columns = (0..5).map { j -> OverlapV2.scatter(topology, depth, DoubleArray(6) { if (it == j) 1.0 else 0.0 }) }
            for (i in 0..5) for (j in 0..5) {
                val dot = (0..5).sumOf { columns[i][it] * columns[j][it] }
                assertTrue(abs(dot - if (i == j) 1.0 else 0.0) < 1e-10, "$topology depth $depth loses or adds scatter energy")
            }
        }
    }

    @Test
    fun `halfway knot retains sustained body energy after the strike`() {
        val p = Overlap.probe(OverlapTopology.TREFOIL, plain + mapOf("KNOT" to .5f, "RING" to .8f), seconds = 2.0)
        val initial = rms(p.raw, .03, .15)
        val body = rms(p.raw, .4, 1.0)
        val retainedDb = 20.0 * log10(body.coerceAtLeast(1e-12) / initial.coerceAtLeast(1e-12))
        assertTrue(retainedDb > -20.0, "Half-depth body collapses by $retainedDb dB")
    }

    @Test
    fun `phase locking creates deep recurring breaths in the actual rendered audio`() {
        val macros = plain + mapOf("PULSE" to .65f, "HOLD" to 1f, "RING" to .8f)
        val free = Overlap.probe(OverlapTopology.TREFOIL, macros + ("LOCK" to 0f))
        val locked = Overlap.probe(OverlapTopology.TREFOIL, macros + ("LOCK" to 1f))
        val freeDb = modulationDb(free.raw)
        val lockedDb = modulationDb(locked.raw)
        assertTrue(lockedDb >= 12.0 && lockedDb >= freeDb + 6.0,
            "Breathing is still weak: locked $lockedDb dB, independent $freeDb dB")
    }

    @Test
    fun `lock synchronizes the slow phases while glow leaves network state intact`() {
        val macros = plain + mapOf("PULSE" to .65f, "HOLD" to 1f, "RING" to .8f)
        val free = Overlap.probe(OverlapTopology.TREFOIL, macros + ("LOCK" to 0f), seconds = 6.0)
        val locked = Overlap.probe(OverlapTopology.TREFOIL, macros + ("LOCK" to 1f), seconds = 6.0)
        val lockedOrder = locked.diagnostics.phaseOrderHistory.drop(10).average()
        val freeOrder = free.diagnostics.phaseOrderHistory.drop(10).average()
        assertTrue(lockedOrder > .8, "locked phases did not synchronize")
        assertTrue(lockedOrder > freeOrder + .2, "LOCK does not change sustained synchronization")
        val glowing = Overlap.probe(OverlapTopology.TREFOIL, macros + mapOf("LOCK" to 1f, "GLOW" to .7f), seconds = 6.0)
        assertEquals(locked.diagnostics.delaySamples, glowing.diagnostics.delaySamples)
        assertEquals(locked.diagnostics.phaseOrderHistory, glowing.diagnostics.phaseOrderHistory)
        assertEquals(locked.diagnostics.maxBufferAbs, glowing.diagnostics.maxBufferAbs)
        assertTrue(!locked.raw.contentEquals(glowing.raw), "GLOW does not alter the output")
    }

    private fun rms(samples: FloatArray, start: Double, end: Double): Double {
        val a = (start * 44_100).toInt()
        val b = (end * 44_100).toInt().coerceAtMost(samples.size)
        return sqrt((a until b).sumOf { samples[it].toDouble() * samples[it] } / (b - a))
    }
    private fun modulationDb(samples: FloatArray): Double {
        val levels = generateSequence(1.5) { it + .025 }.takeWhile { it + .05 < 5.5 }
            .map { rms(samples, it, it + .05) }.sorted().toList()
        return 20.0 * log10(levels[(levels.size * .9).toInt()].coerceAtLeast(1e-12) /
            levels[(levels.size * .1).toInt()].coerceAtLeast(1e-12))
    }
}
