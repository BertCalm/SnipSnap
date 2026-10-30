package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FerryTest {
    private fun energy(state: Pair<DoubleArray, DoubleArray>, indices: IntRange = state.first.indices): Double =
        indices.sumOf { state.first[it].pow(2) + state.second[it].pow(2) }

    @Test
    fun `matched pair follows analytic transfer and detuning reduces acquisition`() {
        val omega = 2 * PI * 130.8127826502993
        val g = PI / .8
        fun pair(detune: Double = 0.0) = FerryNetwork.Compiled(FerryNetwork.Model(
            arrayOf(doubleArrayOf(omega, g), doubleArrayOf(g, omega + detune)),
            doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0),
        ), Ferry.INTERNAL_RATE)
        val initial = doubleArrayOf(1.0, 0.0)
        val matched = pair()
        val transfer = matched.state(initial, (Ferry.INTERNAL_RATE * .4).toInt())
        assertTrue(energy(transfer, 1..1) > .99999999, "A-only initial energy must reach B at 400ms")
        for (seconds in listOf(.1, .2, .4, .8)) {
            assertTrue(abs(energy(matched.state(initial, (Ferry.INTERNAL_RATE * seconds).toInt())) - 1.0) < 1e-8)
        }
        val delta = omega * (2.0.pow(30.0 / 1200) - 1)
        val rotation = sqrt(g * g + delta * delta / 4)
        val firstPeak = PI / (2 * rotation)
        val detuned = pair(delta).state(initial, (Ferry.INTERNAL_RATE * firstPeak).toInt())
        assertTrue(abs(energy(detuned, 1..1) - g * g / (rotation * rotation)) < 1e-7)
        assertTrue(energy(detuned, 1..1) < .24, "Mistuning B must change physical transfer")
    }

    @Test
    fun `zero handoff isolates B and removal of support silences nine receivers`() {
        for (midi in listOf(36, 48, 72)) {
            val isolated = FerryNetwork.Compiled(FerryNetwork.build(midi, 0.0), Ferry.INTERNAL_RATE)
            val noSupport = FerryNetwork.Compiled(FerryNetwork.build(midi, 1.0, support = false), Ferry.INTERNAL_RATE)
            assertEquals(0.0, energy(isolated.state(Ferry.initial(), 4 * Ferry.INTERNAL_RATE), 4..7))
            assertEquals(0.0, energy(noSupport.state(Ferry.initial(), 4 * Ferry.INTERNAL_RATE), 12..20))
        }
    }

    @Test
    fun `unforced energy decays against an independent bound and negative damping is rejected`() {
        val model = FerryNetwork.build(48, 1.0)
        val network = FerryNetwork.Compiled(model, Ferry.INTERNAL_RATE)
        val initial = Ferry.initial()
        val e0 = initial.sumOf { it * it }
        var previous = e0
        for (step in 0..20) {
            val t = step * .2
            val value = energy(network.state(initial, (t * Ferry.INTERNAL_RATE).toInt()))
            assertTrue(value <= previous + 1e-9, "Unforced energy grew at $t")
            assertTrue(value <= e0 * exp(-2 * model.gamma.min() * t) + 1e-9)
            previous = value
        }
        val rejected = assertFailsWith<IllegalArgumentException> {
            FerryNetwork.Compiled(model.copy(gamma = model.gamma.map { -it }.toDoubleArray()), Ferry.INTERNAL_RATE)
        }
        assertTrue("damping" in rejected.message.orEmpty())
    }

    @Test
    fun `blocked readout reproduces actual sample by sample network state`() {
        val plan = Ferry.plan(48, .5f)
        val initial = Ferry.initial()
        val output = plan.render(initial, 1271)
        var re = initial.copyOf(); var im = DoubleArray(initial.size)
        var nr = DoubleArray(initial.size); var ni = DoubleArray(initial.size)
        for (frame in output.indices) {
            val exact = plan.model.pickup.indices.sumOf { plan.model.pickup[it] * re[it] }
            assertTrue(abs(output[frame] - exact) < 2e-7, "Block/readout mismatch at $frame")
            plan.step.apply(re, im, nr, ni)
            val tr = re; re = nr; nr = tr; val ti = im; im = ni; ni = ti
        }
    }

    @Test
    fun `all approved networks agree with independent exact full-system fixtures`() {
        val text = javaClass.getResourceAsStream("/ferry-reference.json")!!.bufferedReader().use { it.readText() }
        val cases = Json.parse(text).obj().getValue("cases").arr()
        for (raw in cases) {
            val case = raw.obj()
            val midi = case.getValue("midi").int()
            val handoff = case.getValue("handoff").num().toFloat()
            val model = FerryNetwork.build(midi, Ferry.pace(handoff))
            val plan = Ferry.plan(midi, handoff)
            val oracleH = case.getValue("h").arr()
            for (i in model.h.indices) for (j in model.h.indices) {
                assertTrue(abs(model.h[i][j] - oracleH[i].arr()[j].num()) < 1e-8, "$midi/$handoff H $i,$j")
            }
            assertTrue(plan.eigenResidual < 1e-11 && plan.orthogonalityError < 1e-11)
            for (pointRaw in case.getValue("observations").arr()) {
                val point = pointRaw.obj()
                val frame = point.getValue("frame").int()
                val (re, im) = plan.state(Ferry.initial(), frame)
                val expectedRe = point.getValue("real").arr()
                val expectedIm = point.getValue("imaginary").arr()
                val maxError = re.indices.maxOf { max(abs(re[it] - expectedRe[it].num()), abs(im[it] - expectedIm[it].num())) }
                assertTrue(maxError < 2e-6, "$midi/$handoff at $frame: split/exact error $maxError")
            }
            val hz = 440 * 2.0.pow((midi - 69) / 12.0)
            for (omega in plan.eigenvalues.take(4)) {
                assertTrue(abs(1200 * ln(omega / (2 * PI * hz)) / ln(2.0)) <= 5.0000001)
            }
            assertTrue(plan.eigenvalues.last() / (2 * PI) < 22050, "Every coupled frequency must remain in the output band")
        }
    }

    @Test
    fun `render keeps native format finite peaks DC and pitched classification`() {
        for (midi in listOf(36, 48, 72)) for (handoff in listOf(.25f, .5f, 1f)) {
            val snip = Ferry.render(macros = mapOf("HANDOFF" to handoff), midi = midi)
            assertEquals(1, snip.channels)
            assertEquals(44100, snip.sampleRate)
            assertEquals(4 * 44100, snip.samples.size)
            assertTrue(snip.samples.all { it.isFinite() })
            assertTrue(snip.samples.maxOf { abs(it) } in .01f..1f)
            assertTrue(abs(snip.samples.sumOf { it.toDouble() } / snip.samples.size) < .001)
            assertTrue(abs(Loudness.of(snip) - Dsp.MELODIC_LOUDNESS_TARGET) < .001)
            val requested = 440 * 2.0.pow((midi - 69) / 12.0)
            // The native audition measured at most 14.21 cents and confidence
            // at least .927 here. This bound allows a small implementation margin;
            // the separate five-cent gate concerns eigenfrequencies, not readout pitch.
            for (start in listOf(.05f, 1.3f, 3.2f)) {
                val pitch = assertNotNull(Pitch.detect(snip, start), "$midi/$handoff has no root at $start")
                assertTrue(abs(1200 * ln(pitch.hz / requested) / ln(2.0)) < 20, "$midi/$handoff root at $start")
                assertTrue(pitch.confidence >= .9f, "$midi/$handoff root confidence at $start")
            }
            assertTrue(Classifier.classify(snip).drumClass !in setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM))
            assertEquals(DrumClass.TONAL, Ferry.drumClassFor(FerryVoice.PASSAGE, mapOf("HANDOFF" to handoff)))
        }
    }

    @Test
    fun `macro sanitation cache reuse and seed streams preserve deterministic comparisons`() {
        val baseline = Ferry.render().samples
        assertContentEquals(baseline, Ferry.render(macros = mapOf("HANDOFF" to Float.NaN, "unknown" to .2f)).samples)
        assertFalse(baseline.contentEquals(Ferry.render(macros = mapOf("HANDOFF" to .25f)).samples))
        assertFalse(baseline.contentEquals(Ferry.render(velocity = .25f).samples))
        assertFalse(baseline.contentEquals(Ferry.render(seed = 17).samples))
        assertContentEquals(FloatArray(4 * 44100), Ferry.render(velocity = 0f).samples)
        assertTrue(assertFailsWith<IllegalArgumentException> { Ferry.render(macros = mapOf("HOLD" to 1f)) }.message.orEmpty().contains("HOLD"))
        assertTrue(assertFailsWith<IllegalArgumentException> { Ferry.render(velocity = Float.NaN) }.message.orEmpty().contains("velocity"))
    }

    @Test
    fun `saved passage retains all performance fields and refuses lossy malformed metadata`() {
        for (seed in listOf(Long.MIN_VALUE, Long.MAX_VALUE, 9_007_199_254_740_993L)) {
            val patch = FerryPatch("Saved passage", macros = mapOf("HANDOFF" to .25f), midi = 57, velocity = .6f, seed = seed)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch.copy(name = "Edited"), Patches.edited(patch, "Edited", patch.macros))
            assertContentEquals(patch.render().samples, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).render().samples)
        }
        val text = FerryPatch("Malformed").toJsonText()
        assertTrue(assertFailsWith<com.snipsnap.json.JsonException> {
            FerryPatch.fromJsonText(text.replace("\"seed\": \"7\"", "\"seed\": \"007\""))
        }.message.orEmpty().contains("seed"))
        assertTrue(assertFailsWith<IllegalArgumentException> { FerryPatch("Held", macros = mapOf("HOLD" to .01f)) }.message.orEmpty().contains("HOLD"))
    }
}
