package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A repeating colour is synthesized continuously, including the independently retained preceding cycle. */
class PrismRepeatingMotionTest {
    private val a = List(Prism.PARTIALS) { if (it == 0) 1f else .008f / (it + 1f) }
    private val b = List(Prism.PARTIALS) { if (it == 0) 1f else if (it < 7) .8f else .02f }
    private val motion = PrismMotion(b, evolve = 1f, seconds = .8f, amplitudesA = a, repeat = true)

    private fun relativeError(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        var error = 0.0
        var energy = 0.0
        for (i in a.indices) {
            val difference = a[i] - b[i].toDouble()
            error += difference * difference
            energy += b[i].toDouble() * b[i]
        }
        return error / energy.coerceAtLeast(1e-30)
    }

    @Test
    fun `default fields preserve old JSON finite travel and metadata does not alter audio`() {
        val old = PrismMotion(b, morph = .17f, evolve = .63f, seconds = .7f, groupLag = .4f, amplitudesA = a)
        assertFalse("repeat" in old.toJsonValue().entries)
        assertFalse("sourceId" in old.toJsonValue().entries)
        val explicit = PrismMotion.fromJsonValue(JsonValue.Obj(old.toJsonValue().entries + mapOf(
            "repeat" to JsonValue.Bool(false), "sourceId" to JsonValue.Null)))
        assertEquals(old, explicit)
        assertEquals(old.hashCode(), explicit.hashCode())
        assertEquals(old.toString(), explicit.toString())
        assertEquals(Json.write(old.toJsonValue()), Json.write(explicit.toJsonValue()))
        // Independent original finite equation: adding repeat must not change its lag or rounding.
        for (group in 0 until Prism.GROUPS) for (time in listOf(0.0, .13, .7, 1.8)) {
            val delay = old.groupLag.toDouble() * old.seconds * group / (Prism.GROUPS - 1)
            val x = ((time - delay) / old.seconds).coerceIn(0.0, 1.0)
            val expected = old.morph + (1.0 - old.morph) * old.evolve * (x * x * (3.0 - 2.0 * x))
            assertEquals(expected, old.positionAt(time, group))
            assertEquals(expected, old.positionAt(time, group, actualPeriodSeconds = 2.0))
        }
        val original = Prism.probe(PrismVoice.KEY, seconds = .22f, motion = old)
        val labelled = Prism.probe(PrismVoice.KEY, seconds = .22f, motion = old.copy(sourceId = "saved/unknown-source"))
        assertContentEquals(original.raw, labelled.raw)
        assertContentEquals(original.samples, labelled.samples)
    }

    @Test
    fun `full cycle returns smoothly to A and every lagged group uses the fitted period`() {
        assertEquals(0.0, motion.positionAt(0.0, 0), 1e-12)
        assertEquals(1.0, motion.positionAt(motion.seconds.toDouble() / 2, 0), 1e-12)
        assertEquals(0.0, motion.positionAt(motion.seconds.toDouble(), 0), 1e-12)
        val fitted = .7937
        val staggered = motion.copy(morph = .2f, evolve = .7f, groupLag = .63f)
        for (group in 0 until Prism.GROUPS) for (time in listOf(0.0, .13, .42, .79)) {
            val x = staggered.positionAt(time, group, fitted)
            assertTrue(x in .199999.. .760001)
            assertEquals(x, staggered.positionAt(time + fitted, group, fitted), 1e-12)
        }
        val epsilon = 1e-5
        assertTrue(motion.positionAt(epsilon, 0) < 1e-8)
        assertEquals(motion.positionAt(epsilon, 0), motion.positionAt(motion.seconds - epsilon, 0), 1e-12)
        assertEquals(1.0, motion.positionAt(fitted / 2, 0, fitted), 1e-12)
    }

    @Test
    fun `repeat fits all registers while complete carrier control and conditioned cycles close`() {
        for (voice in PrismVoice.entries) for (tune in listOf(0f, 1f)) {
            val requested = if (tune == 0f) .1f else .37f
            val macros = Prism.defaults(voice) + mapOf("TUNE" to tune, "HOLD" to 1f,
                "DRIFT" to 1f, "GRAIN" to 1f, "SHAPE" to 1f)
            val moving = motion.copy(seconds = requested, groupLag = .71f, morph = .13f, evolve = .87f)
            val p = Prism.probe(voice, macros, velocity = .65f, motion = moving)
            val label = "$voice TUNE $tune period $requested"
            val previous = assertNotNull(p.previousCycle, label)
            assertEquals(p.samples.size, p.loopFrames)
            assertTrue(p.loopFrames > 1.5 * Dsp.RATE, "$label short motion must still export a sustained loop")
            assertTrue(p.motionCycleFrames > 0 && p.loopFrames % p.motionCycleFrames == 0)
            assertEquals((p.motionCycleFrames.toDouble() / Dsp.RATE).toFloat(), Prism.motionCycleSeconds(voice, macros, moving))
            assertEquals(requested, Prism.motionCycleSeconds(voice, macros + ("HOLD" to 0f), moving))
            assertTrue(abs(FineTuning.cents(p.effectiveHz, Prism.frequencyFor(voice, tune).toDouble())) < .1,
                "$label fitted root moved")
            // A fitted period is explicit evidence, not a claim that the requested float is exact.
            assertTrue(abs(p.motionCycleFrames.toDouble() / Dsp.RATE - requested) < .09,
                "$label needlessly stretched the requested gesture")
            assertTrue(p.partialHz.all { it > 0 && it < Prism.USABLE_BANDWIDTH_HZ }, "$label carrier bandwidth")
            for (hz in p.partialHz) {
                val turns = hz * p.loopFrames / Dsp.RATE
                assertEquals(turns.roundToInt().toDouble(), turns, 1e-8, "$label a carrier fails to close")
            }
            assertTrue(p.partialWeights.drop(1).all { it <= p.partialWeights.first() / 1.119f }, "$label root anchor")
            assertTrue(p.raw.all { it.isFinite() && abs(it) <= Prism.RAW_PEAK_CEILING + 1e-6f }, "$label raw gain")
            assertTrue(p.samples.all { it.isFinite() && abs(it) <= Prism.RAW_PEAK_CEILING + 1e-5f }, "$label output gain")
            assertTrue(abs(p.samples.average()) < 1e-6, "$label DC")
            val internalCycle = p.loopFrames * Dsp.OVERSAMPLE
            assertContentEquals(p.raw.copyOfRange(internalCycle, 2 * internalCycle),
                p.raw.copyOfRange(2 * internalCycle, 3 * internalCycle), "$label complete raw state is not periodic")
            assertTrue(relativeError(previous, p.samples) < 1e-5, "$label independently conditioned cycles diverge")
            val seam = Keys.seamError(previous + p.samples, previous.size)
            assertEquals(seam, p.seamError, 1e-12, "$label reported seam must use the actual preceding cycle")
            assertTrue(seam < Keys.MAX_SEAM_ERROR, "$label measured seam $seam")
        }
    }

    @Test
    fun `dyadic bell carriers retain their actual ratios at a short motion period`() {
        val voice = PrismVoice.GLASS
        val macros = Prism.defaults(voice) + mapOf("TUNE" to 0f, "HOLD" to 1f)
        val custom = PrismTemplate(PrismSpectrum.BELL, PrismContour.RINGING,
            ratioOverrides = listOf(1f, 1.5f, 2.75f))
        for (template in listOf(custom, Prism.templateFor(voice))) {
            val repeating = motion.copy(seconds = .1f)
            val p = Prism.probe(voice, macros, template = template, motion = repeating)
            assertEquals(Prism.PARTIALS, p.partialHz.size, "low glass keeps every band for this ratio check")
            for ((index, ratio) in template.ratioOverrides.withIndex()) {
                assertEquals(ratio.toDouble(), p.partialHz[index] / p.effectiveHz, 1e-12,
                    "ratio $index must retain its dyadic spacing instead of rounding to a harmonic")
            }
            assertEquals((p.motionCycleFrames.toDouble() / Dsp.RATE).toFloat(),
                Prism.motionCycleSeconds(voice, macros, repeating, template), "readout must use the actual custom template")
            assertTrue(relativeError(assertNotNull(p.previousCycle), p.samples) < 1e-5)
        }
    }

    @Test
    fun `short repeating holds export as public loops including the lowest bass`() {
        for (voice in listOf(PrismVoice.KEY, PrismVoice.BASS)) for (seconds in listOf(.1f, .31f)) {
            val macros = Prism.defaults(voice) + mapOf("TUNE" to 0f, "HOLD" to 1f)
            val samples = Prism.renderLoop(voice, macros, motion = motion.copy(seconds = seconds))
            assertTrue(samples.size.toDouble() / Dsp.RATE > 1.5, "$voice $seconds HOLD was exported as a short hit")
            assertEquals(DrumClass.LOOP, Prism.drumClassFor(voice, macros))
            assertEquals(DrumClass.LOOP, Classifier.classify(Snip(samples, 1, Dsp.RATE)).drumClass,
                "$voice $seconds HOLD reimports into a drum choke family")
        }
    }

    /** Project the first eight actual carriers in a circular Hann window around a cycle position. */
    private fun upperShare(p: Prism.Probe, center: Int): Double {
        val count = minOf((Dsp.RATE * .12).roundToInt(), p.samples.size)
        val powers = p.partialHz.take(8).map { hz ->
            var real = 0.0
            var imaginary = 0.0
            for (i in 0 until count) {
                val sampleIndex = Math.floorMod(center - count / 2 + i, p.samples.size)
                val sample = p.samples[sampleIndex] * (.5 - .5 * cos(2 * PI * i / (count - 1)))
                val phase = 2 * PI * hz * i / Dsp.RATE
                real += sample * cos(phase)
                imaginary += sample * sin(phase)
            }
            real * real + imaginary * imaginary
        }
        return powers.drop(1).sum() / powers.sum()
    }

    @Test
    fun `audible repeat leaves and returns to the body without a tail crossfade or pitch loss`() {
        val template = PrismTemplate(PrismSpectrum.ROUNDED, PrismContour.HELD,
            ratioOverrides = List(Prism.PARTIALS) { it + 1f },
            groupProfiles = List(Prism.GROUPS) { PrismGroupProfile() })
        val macros = Prism.defaults(PrismVoice.KEY) + mapOf("HOLD" to 1f, "DRIFT" to 0f, "GRAIN" to 0f)
        val p = Prism.probe(PrismVoice.KEY, macros, template = template, motion = motion)
        assertTrue(upperShare(p, p.motionCycleFrames / 2) > upperShare(p, 0) + .1,
            "the middle must reach upper colour while both ends return to the quiet-harmonic body")
        val wanted = Prism.frequencyFor(PrismVoice.KEY, macros.getValue("TUNE"))
        val measured = FineTuning.measuredHz(p.samples, Dsp.RATE, wanted, fromSec = .1f, bodySeconds = .5f)
        assertTrue(abs(FineTuning.cents(measured, wanted.toDouble())) < 1.0, "audible repeating root")
        val detected = Pitch.detect(Snip(p.samples, 1, Dsp.RATE), fromSec = .1f, windowSec = .5f)
        assertTrue(assertNotNull(detected).confidence > .3f, "public detector retains a confident root")
        var n = 65536
        while (n < p.samples.size) n *= 2
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in p.samples.indices) re[i] = p.samples[i] * (.5 - .5 * cos(2 * PI * i / (p.samples.size - 1))).toFloat()
        Fft.forward(re, im)
        val total = (1 until n / 2).sumOf { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        val top = (1 until n / 2).filter { it * Dsp.RATE.toDouble() / n > 20_000 }
            .sumOf { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        assertTrue(top / total < 1e-4, "motion must not produce substantial energy at output Nyquist")
    }

    @Test
    fun `repeat saves complete state replays velocity and cancels after work has started`() {
        val patch = PrismPatch("Repeating body", PrismVoice.KEY, mapOf("HOLD" to 1f), velocity = .6f,
            motion = motion.copy(sourceId = "factory/key-colour", groupLag = .4f, seconds = .31f))
        val recipe = PadRecipe(patch)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        val decoded = assertIs<PrismPatch>(restored.patch)
        assertEquals(recipe, restored)
        assertEquals(patch.motion, decoded.motion)
        val savedMotion = assertNotNull(decoded.motion)
        assertEquals("factory/key-colour", savedMotion.sourceId)
        assertTrue(savedMotion.repeat)
        assertContentEquals(recipe.render().samples, restored.render().samples)
        assertContentEquals(patch.render().samples, Velocity.atVelocity(patch.copy(velocity = 1f), .6f).samples)
        val soft = patch.copy(velocity = .25f).render().samples
        val firm = patch.copy(velocity = 1f).render().samples
        fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
        assertTrue(rms(firm) > rms(soft) * 1.5, "normalization retains performance velocity")
        assertTrue(patch.copy(velocity = 0f).render().samples.all { it == 0f })
        var blocks = 0
        assertFailsWith<CancellationException> {
            Prism.renderLoop(patch.voice, patch.macros, patch.velocity, patch.template,
                cancelled = { ++blocks >= 16 }, motion = patch.motion!!)
        }
        assertEquals(16, blocks)
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { patch.render() }
        } finally {
            Thread.interrupted()
        }
        assertFailsWith<IllegalArgumentException> { patch.copy(motion = motion.copy(repeat = false)) }
        assertFailsWith<IllegalArgumentException> { motion.copy(sourceId = "  ") }
        val invalid = JsonValue.Obj(motion.toJsonValue().entries + ("repeat" to JsonValue.Str("true")))
        assertFailsWith<JsonException> { PrismMotion.fromJsonValue(invalid) }
    }
}
