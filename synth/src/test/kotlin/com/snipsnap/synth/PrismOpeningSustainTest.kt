package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The held instrument owns its attack and loop marker; ordinary pad recipes keep their renderer. */
class PrismOpeningSustainTest {
    private val a = List(Prism.PARTIALS) { if (it == 0) 1f else .003f / (it + 1f) }
    private val b = List(Prism.PARTIALS) { if (it == 0) 1f else if (it < 8) .75f else .01f }
    private val motion = PrismMotion(b, evolve = 1f, seconds = .4f, amplitudesA = a, repeat = true)
    private val template = PrismTemplate(PrismSpectrum.ROUNDED, PrismContour.HELD,
        ratioOverrides = List(Prism.PARTIALS) { it + 1f },
        groupProfiles = List(Prism.GROUPS) { PrismGroupProfile() }, attackSeconds = .03f)
    private val macros = Prism.defaults(PrismVoice.KEY) + mapOf("HOLD" to 1f, "DRIFT" to 0f, "GRAIN" to 0f)

    private fun rms(samples: FloatArray, from: Int = 0, until: Int = samples.size): Double =
        sqrt((from until until).sumOf { samples[it].toDouble() * samples[it] } / (until - from))

    private fun relativeError(first: FloatArray, second: FloatArray): Double {
        assertEquals(first.size, second.size)
        val error = first.indices.sumOf { val d = first[it] - second[it].toDouble(); d * d }
        val energy = second.sumOf { it.toDouble() * it }
        return error / energy.coerceAtLeast(1e-30)
    }

    private fun kept(p: Prism.OpeningProbe) = p.note.snip.samples.copyOfRange(p.loopStartFrames, p.note.snip.frameCount)

    private fun assertPeriod(p: Prism.OpeningProbe, label: String) {
        val samples = p.note.snip.samples
        val loop = kept(p)
        assertEquals(Dsp.RATE, p.note.snip.sampleRate)
        assertEquals(1, p.note.snip.channels)
        assertTrue(p.openingFrames > 0 && p.bridgeFrames > 0, "$label has no once-only opening")
        assertEquals(p.motionCycleFrames, p.bridgeFrames)
        assertEquals(p.openingFrames + p.bridgeFrames + 2 * p.loopFrames, p.loopStartFrames,
            "$label loop marker must follow the genuine settle and evidence periods")
        assertEquals(p.loopFrames, loop.size, "$label loop must run to EOF")
        assertTrue(p.loopFrames > Dsp.RATE * 1.5, "$label short motion exported as a drum hit")
        assertEquals(0, p.loopFrames % p.motionCycleFrames)
        assertContentEquals(samples.copyOfRange(p.loopStartFrames - p.loopFrames, p.loopStartFrames), p.precedingLoop,
            "$label preceding evidence must come from its separately simulated audible prefix")
        assertTrue(relativeError(p.precedingLoop, loop) < 1e-6, "$label complete filtered state is not periodic")
        val seam = Keys.seamError(p.precedingLoop + loop, p.loopFrames)
        assertEquals(seam, p.seamError, 1e-12, "$label reported seam was not measured on actual preceding audio")
        assertEquals(Keys.seamError(samples, p.loopStartFrames), p.seamError, 1e-12)
        assertTrue(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR, "$label end-to-loop-start seam $seam")
        assertTrue(abs(loop.average()) < 1e-6, "$label loop DC")
        assertTrue(abs(samples.average()) < .005, "$label whole-note DC")
        assertTrue(samples.all { it.isFinite() && abs(it) <= .99001f }, "$label output headroom")
        assertTrue(p.rawPeak <= Prism.RAW_PEAK_CEILING + 1e-6f, "$label additive headroom")
        assertTrue(p.partialHz.all { it > 0 && it < Prism.USABLE_BANDWIDTH_HZ }, "$label carrier bandwidth")
        assertTrue(p.partialWeights.drop(1).all { it <= p.partialWeights.first() / 1.119f }, "$label root anchor")
        for (hz in p.partialHz) {
            val turns = hz * p.loopFrames / Dsp.RATE
            assertEquals(turns.roundToInt().toDouble(), turns, 1e-8, "$label carrier does not close")
        }
        if (p.raw.isNotEmpty()) {
            val rate = Dsp.OVERSAMPLE
            assertEquals((samples.size + 512) * rate, p.raw.size, "$label missing conditioning postroll")
            assertContentEquals(p.raw.copyOfRange((p.loopStartFrames - p.loopFrames) * rate, p.loopStartFrames * rate),
                p.raw.copyOfRange(p.loopStartFrames * rate, samples.size * rate), "$label raw state failed to recur")
            assertTrue(p.raw.all { it.isFinite() && abs(it) <= Prism.RAW_PEAK_CEILING + 1e-6f })
        }
    }

    /** An independent circular carrier projection, measured from rendered audio rather than saved coefficients. */
    private fun upperShare(p: Prism.OpeningProbe, center: Int): Double {
        val samples = p.note.snip.samples
        val count = (Dsp.RATE * .075).roundToInt()
        require(center >= count / 2 && center + count / 2 < samples.size)
        val power = p.partialHz.take(8).map { hz ->
            var real = 0.0
            var imaginary = 0.0
            for (i in 0 until count) {
                val x = samples[center - count / 2 + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))
                val phase = 2 * PI * hz * i / Dsp.RATE
                real += x * cos(phase)
                imaginary += x * sin(phase)
            }
            real * real + imaginary * imaginary
        }
        return power.drop(1).sum() / power.sum()
    }

    private fun assertSmoothBoundary(samples: FloatArray, boundary: Int, label: String) {
        require(boundary > 1024 && boundary + 1024 < samples.size)
        var largestStep = 0.0
        var largestKink = 0.0
        for (i in boundary - 1024 until boundary + 1024) {
            if (i in boundary - 2..boundary + 2) continue
            largestStep = maxOf(largestStep, abs(samples[i] - samples[i - 1]).toDouble())
            largestKink = maxOf(largestKink,
                abs((samples[i] - samples[i - 1]) - (samples[i - 1] - samples[i - 2])).toDouble())
        }
        val jump = (samples[boundary] - samples[boundary - 1]).toDouble()
        val before = (samples[boundary - 1] - samples[boundary - 2]).toDouble()
        val after = (samples[boundary + 1] - samples[boundary]).toDouble()
        assertTrue(abs(jump) <= largestStep * 1.1 + 2e-6, "$label exceptional sample jump")
        assertTrue(maxOf(abs(jump - before), abs(after - jump)) <= largestKink * 1.15 + 2e-6,
            "$label exceptional slope change")
    }

    @Test
    fun `a body opens once then the audible sustain cycles through colour without replaying the attack`() {
        val p = Prism.openingProbe(PrismVoice.KEY, macros, template = template, motion = motion)
        assertPeriod(p, "body gesture")
        val early = upperShare(p, (Dsp.RATE * .07).roundToInt())
        val opened = upperShare(p, p.openingFrames - (Dsp.RATE * .04).roundToInt())
        val sustainBody = upperShare(p, p.loopStartFrames + p.motionCycleFrames / 2)
        val sustainColour = upperShare(p, p.loopStartFrames + p.motionCycleFrames)
        assertTrue(opened > early + .1, "the once-only opening must redistribute actual harmonic energy")
        assertTrue(sustainColour > sustainBody + .1, "sustain must continue moving through the two endpoints")
        val samples = p.note.snip.samples
        assertTrue(rms(samples, 0, (Dsp.RATE * .003).roundToInt()) < rms(kept(p)) * .2,
            "the new instrument must begin with its one-time attack")
        val bodyAt = p.loopStartFrames + p.motionCycleFrames / 2
        assertTrue(rms(samples, bodyAt - 256, bodyAt + 256) > rms(kept(p)) * .1,
            "returning to A must not replay a silent attack")
        assertSmoothBoundary(samples, p.openingFrames, "A-to-B handoff")
        assertSmoothBoundary(samples, p.openingFrames + p.bridgeFrames, "first-cycle lag introduction")
        val loop = kept(p)
        val shift = (Dsp.RATE / p.effectiveHz / 2).roundToInt()
        val misjoined = FloatArray(loop.size) { loop[(it + shift) % loop.size] }
        assertTrue(Keys.seamError(p.precedingLoop + misjoined, p.loopFrames) > .1,
            "the seam probe must detect a half-root-period wrong continuation")
    }

    @Test
    fun `all four delivered body recipes retain their pitch and supply real held instrument loops`() {
        for (base in PrismOpeningPresets.all()) {
            val repeating = assertNotNull(base.motion).copy(repeat = true)
            val held = base.macros + ("HOLD" to 1f)
            val p = Prism.openingProbe(base.voice, held, base.velocity, base.template, repeating)
            assertPeriod(p, base.name)
            val wanted = Prism.frequencyFor(base.voice, held.getValue("TUNE"))
            assertTrue(abs(FineTuning.cents(p.effectiveHz, wanted.toDouble())) < .1, "${base.name} fitted root")
            assertEquals(Prism.motionCycleSeconds(base.voice, held, repeating, base.template).toDouble(),
                p.actualPeriodSeconds, 1e-7)
            val loop = kept(p)
            val measured = FineTuning.measuredHz(loop, Dsp.RATE, wanted, .05f, .5f)
            assertTrue(abs(FineTuning.cents(measured, wanted.toDouble())) < 1.0, "${base.name} audible root")
            assertTrue(assertNotNull(Pitch.detect(Snip(loop, 1, Dsp.RATE))).confidence >= .3f,
                "${base.name} public root confidence")
            assertEquals(DrumClass.LOOP, Classifier.classify(p.note.snip).drumClass)
        }
    }

    @Test
    fun `lag drift note and bell ratio extremes converge without changing pitch or stepping at the handoff`() {
        data class Case(val voice: PrismVoice, val tune: Float, val lag: Float, val period: Float,
            val template: PrismTemplate = Prism.templateFor(voice))
        val cases = listOf(
            Case(PrismVoice.BASS, 0f, 1f, .1f),
            Case(PrismVoice.KEY, 1f, 0f, .31f),
            Case(PrismVoice.PLUCK, 0f, 1f, .31f),
            Case(PrismVoice.ORGAN, 1f, 0f, .31f),
            Case(PrismVoice.VEIL, 1f, 1f, .31f),
            Case(PrismVoice.GLASS, 1f, 1f, .1f),
            Case(PrismVoice.GLASS, 0f, 1f, .1f, PrismTemplate(PrismSpectrum.BELL, PrismContour.RINGING,
                ratioOverrides = listOf(1f, 1.5f, 2.75f))),
        )
        for (case in cases) {
            val m = Prism.defaults(case.voice) + mapOf("HOLD" to 1f, "TUNE" to case.tune,
                "GRAIN" to 1f, "SHAPE" to 1f, "DRIFT" to 1f, "STRIKE" to 1f)
            val p = Prism.openingProbe(case.voice, m, .35f, case.template,
                motion.copy(seconds = case.period, groupLag = case.lag), keepRaw = false)
            val label = "${case.voice} TUNE ${case.tune} lag ${case.lag}"
            assertPeriod(p, label)
            val wanted = Prism.frequencyFor(case.voice, case.tune)
            assertTrue(abs(FineTuning.cents(p.effectiveHz, wanted.toDouble())) < .1, "$label root movement")
            assertSmoothBoundary(p.note.snip.samples, p.openingFrames, "$label opening")
            assertSmoothBoundary(p.note.snip.samples, p.openingFrames + p.bridgeFrames, "$label settled lag")
            for ((i, ratio) in case.template.ratioOverrides.withIndex()) {
                if (i >= p.partialHz.size) break
                assertEquals(ratio.toDouble(), p.partialHz[i] / p.effectiveHz, 1e-12, "$label dyadic ratio")
            }
            if (case.voice == PrismVoice.GLASS && case.tune == 1f) {
                val re = FloatArray(65536)
                val im = FloatArray(re.size)
                val loop = kept(p)
                val count = 16384
                for (i in 0 until count) re[i] = loop[i] * (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
                Fft.forward(re, im)
                var total = 0.0
                var nearNyquist = 0.0
                for (i in 1 until re.size / 2) {
                    val power = re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
                    total += power
                    if (i * Dsp.RATE.toDouble() / re.size > 20_000) nearNyquist += power
                }
                assertTrue(nearNyquist / total < 1e-4, "$label leaves substantial energy near output Nyquist")
            }
        }
    }

    @Test
    fun `saved patch replay reproduces the new performance and touch while old pad output stays unchanged`() {
        val base = PrismOpeningPresets.all()[2]
        val held = base.copy(macros = base.macros + ("HOLD" to 1f), motion = base.motion!!.copy(repeat = true))
        val originalText = held.toJsonText()
        val saved = PrismPatch.fromJsonText(originalText)
        fun performance(patch: PrismPatch, velocity: Float = patch.velocity) =
            Prism.openingNote(patch.voice, patch.macros, velocity, patch.template, patch.motion!!)
        val oldHeld = held.render().samples
        val once = performance(held)
        val replay = performance(saved)
        assertEquals(once.loopStartFrame, replay.loopStartFrame)
        assertContentEquals(once.snip.samples, replay.snip.samples)
        assertEquals(originalText, held.toJsonText(), "the new delivery must not mutate a saved timbre")
        assertContentEquals(oldHeld, held.render().samples, "pure HOLD remains a separate unchanged renderer")
        val soft = performance(held, .25f)
        val firm = performance(held, 1f)
        assertEquals(soft.loopStartFrame, firm.loopStartFrame)
        assertTrue(rms(firm.snip.samples) > rms(soft.snip.samples) * 1.5, "output gain erased physical velocity")
        val silent = performance(held, 0f)
        assertEquals(once.loopStartFrame, silent.loopStartFrame)
        assertTrue(silent.snip.samples.all { it == 0f })
    }

    @Test
    fun `unsupported modes refuse before rendering and cancellation stops a started long performance`() {
        assertEquals(1, Prism.OPENING_MODEL_VERSION)
        assertFailsWith<IllegalArgumentException> { Prism.openingNote(PrismVoice.KEY, motion = motion) }
        assertFailsWith<IllegalArgumentException> {
            Prism.openingNote(PrismVoice.KEY, macros, motion = motion.copy(repeat = false))
        }
        for (velocity in listOf(-.1f, 1.1f, Float.NaN)) {
            assertFailsWith<IllegalArgumentException> { Prism.openingNote(PrismVoice.KEY, macros, velocity, motion = motion) }
        }
        var looks = 0
        assertFailsWith<CancellationException> {
            Prism.openingNote(PrismVoice.BASS, macros + ("TUNE" to 0f), motion = motion.copy(seconds = 6f, groupLag = 1f),
                cancelled = { ++looks >= 16 })
        }
        assertEquals(16, looks, "cancellation must be observed after synthesis has started")
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { Prism.openingNote(PrismVoice.KEY, macros, motion = motion) }
        } finally {
            Thread.interrupted()
        }
    }
}
