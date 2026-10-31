package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Model 2's audible changes, measured independently of sample gain and oscillator phase. */
class StoopModel2DspTest {
    @Test
    fun `finite clav retains contrasting body harmonics beyond its pick transient`() {
        fun direct(name: String, model: Int) = StoopPresets.all().single { it.name == name }.let { preset ->
            preset.copy(model = model, macros = preset.macros + mapOf("WEAR" to 0f, "SHADOW" to 0f, "HOLD" to 0f))
        }
        val soft = direct("Soft Clav", Stoop.MODEL_VERSION)
        val bright = direct("Short Wire", Stoop.MODEL_VERSION)
        val legacyBright = bright.copy(model = Stoop.LEGACY_MODEL_VERSION)
        val softSnip = soft.render()
        val brightSnip = bright.render()
        val legacySnip = legacyBright.render()
        val profiles = listOf(softSnip, brightSnip, legacySnip).map { harmonicProfile(it, Keys.midiHz(bright.midi).toDouble(), .15, .35) }
        println("STOOP CLAV body: model2 soft=${profiles[0]}, model2 bright=${profiles[1]}, legacy bright=${profiles[2]}")
        assertTrue(profiles[1].upperShare >= .03, "a bright finite clav needs measurable pitched body above the root: $profiles")
        assertTrue(profiles[1].upperShare - profiles[0].upperShare >= .02,
            "the bright and muted body must retain registration contrast after the pick transient: $profiles")
        assertTrue(profiles[1].upperShare - profiles[2].upperShare >= .02,
            "model 2 must restore body harmonics beyond the legacy filter decay: $profiles")
        for ((patch, snip) in listOf(soft to softSnip, bright to brightSnip)) {
            val profile = harmonicProfile(snip, Keys.midiHz(patch.midi).toDouble(), .15, .35)
            assertTrue(profile.rootShare >= .15 && profile.capture >= .85,
                "${patch.name}: the fuller body must remain pitched and rooted: $profile")
            assertRoot(snip, patch.midi)
        }
    }

    @Test
    fun `finite bright clav stays bounded and rooted at both supported register boundaries`() {
        val factory = StoopPresets.all().single { it.name == "Short Wire" }
        for (midi in listOf(Stoop.MIDI_MIN, Stoop.MIDI_MAX)) {
            val patch = factory.copy(model = Stoop.MODEL_VERSION, midi = midi,
                macros = factory.macros + mapOf("TOUCH" to 1f, "COLOR" to 1f, "LENGTH" to .75f, "WEAR" to 0f, "SHADOW" to 0f, "HOLD" to 0f))
            val raw = Stoop.renderRaw(patch)
            assertTrue(raw.samples.all { it.isFinite() && abs(it) < 2f }, "CLAV MIDI $midi finite raw bounds")
            assertTrue(raw.peak() > .001f, "CLAV MIDI $midi must retain an audible body")
            val profile = harmonicProfile(raw, Keys.midiHz(midi).toDouble(), .15, .35)
            assertTrue(profile.rootShare >= .10 && profile.capture >= .75, "CLAV MIDI $midi lost pitched root support: $profile")
            assertRoot(raw, midi)
            assertTrue(abs(raw.samples.average()) < .005, "CLAV MIDI $midi excessive DC")
        }
    }

    @Test
    fun `a barely opened held shadow does not retune or restart the main loop plan`() {
        val patch = StoopPatch("Stable held chord", StoopVoice.CHORD,
            Stoop.defaults(StoopVoice.CHORD) + mapOf("HOLD" to 1f, "WEAR" to .8f, "SHADOW" to 0f, "LENGTH" to .75f),
            midi = 48, source = Stoop.defaultSource(StoopVoice.CHORD).copy(detuneCents = 7f),
            shadowInterval = 7, chordIntervals = listOf(0, 3, 7), model = Stoop.MODEL_VERSION)
        val closed = Stoop.renderProbe(patch)
        val barelyOpen = Stoop.renderProbe(patch.copy(macros = patch.macros + ("SHADOW" to .0001f)))
        assertEquals(closed.samples.size, barelyOpen.samples.size, "the SHADOW zero crossing must retain loop duration")
        assertEquals(closed.prerollFrames, barelyOpen.prerollFrames, "the SHADOW zero crossing must retain preroll")
        val mainFrequencyCount = patch.chordIntervals.size * 3
        assertEquals(closed.sourceFrequencies.take(mainFrequencyCount), barelyOpen.sourceFrequencies.take(mainFrequencyCount),
            "the main root, modulators and twins must keep the same fitted frequencies")
        assertContentEquals(closed.main, barelyOpen.main, "a tiny companion must not change the main's pitch or wear clock")
        assertTrue(closed.events.isEmpty() && closed.shadow.all { it == 0f }, "SHADOW zero must remain a true bypass")
        assertTrue(barelyOpen.events.isNotEmpty() && barelyOpen.shadow.any { it != 0f }, "the continuity check must include a real companion")
        for (probe in listOf(closed, barelyOpen)) {
            val rootCents = FineTuning.cents(probe.sourceFrequencies.first(), Keys.midiHz(patch.midi).toDouble())
            assertTrue(abs(rootCents) <= .02, "the primary root must not inherit companion-fitting drift: $rootCents cents")
            assertTrue(probe.maxPitchAdjustmentCents <= 5, "held companions must remain within their pitch-fitting budget")
            assertTrue(Keys.seamError(probe.previousCycle + probe.samples, probe.previousCycle.size) < Keys.MAX_SEAM_ERROR)
        }
    }

    @Test
    fun `held length still changes high bright organ and lead magnitude spectra above the cutoff cap`() {
        for (voice in listOf(StoopVoice.ORGAN, StoopVoice.LEAD)) {
            val patch = StoopPatch("High bright $voice", voice,
                Stoop.defaults(voice) + mapOf("TOUCH" to .8f, "COLOR" to 1f, "SHADOW" to 0f, "WEAR" to 0f, "HOLD" to 1f),
                midi = 84, model = Stoop.MODEL_VERSION)
            val shorter = Stoop.renderRaw(patch.copy(macros = patch.macros + ("LENGTH" to .75f)))
            val longer = Stoop.renderRaw(patch.copy(macros = patch.macros + ("LENGTH" to 1f)))
            val distance = magnitudeDistance(shorter, longer)
            println("STOOP held high bright $voice LENGTH .75→1 magnitude distance $distance")
            assertTrue(distance > .005, "$voice LENGTH must move settled harmonic balance when the high cutoff is capped: $distance")
            assertRoot(shorter, patch.midi)
            assertRoot(longer, patch.midi)
        }
    }

    @Test
    fun `custom chord filter follows the marked root when another interval is listed first`() {
        val patch = StoopPatch("Marked-root chord", StoopVoice.CHORD,
            Stoop.defaults(StoopVoice.CHORD) + mapOf("COLOR" to .4f, "WEAR" to 0f, "SHADOW" to 0f, "HOLD" to 0f),
            midi = 48, model = Stoop.MODEL_VERSION)
        val higherFirst = Stoop.renderProbe(patch.copy(chordIntervals = listOf(7, 0, 12)))
        val lowerFirst = Stoop.renderProbe(patch.copy(chordIntervals = listOf(-12, 0, 7)))
        val highFilter = higherFirst.filterVoicings.single()
        val lowFilter = lowerFirst.filterVoicings.single()
        val requested = Keys.midiHz(patch.midi).toDouble()
        assertEquals(requested, highFilter.rootHz, .0001, "a first-listed fifth must not select the chord's filter root")
        assertEquals(requested, lowFilter.rootHz, .0001, "a first-listed lower octave must not select the chord's filter root")
        assertEquals(highFilter.cutoffBaseHz, lowFilter.cutoffBaseHz, 1e-9,
            "the actual gesture filter must retain its root-based cutoff across interval order and voicing")
    }

    private fun assertRoot(snip: Snip, midi: Int) {
        val root = Keys.midiHz(midi)
        val cents = FineTuning.cents(FineTuning.measuredHz(snip, root, .06f, .25f), root.toDouble())
        assertTrue(abs(cents) <= 15, "MIDI $midi requested root drifted $cents cents")
    }

    private data class Harmonics(val rootShare: Double, val upperShare: Double, val capture: Double)

    private fun harmonicProfile(snip: Snip, rootHz: Double, from: Double, until: Double): Harmonics {
        val (real, imaginary) = windowedFft(snip, from, until)
        val width = minOf(rootHz * .35, max(rootHz * .10, 1.5 / (until - from)))
        val partials = DoubleArray(8)
        var total = 0.0
        for (bin in 1 until real.size / 2) {
            val hz = bin.toDouble() * snip.sampleRate / real.size
            if (hz !in .75 * rootHz..minOf(8.5 * rootHz, 20_000.0)) continue
            val power = real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
            total += power
            for (h in 1..8) if (abs(hz - h * rootHz) <= width) partials[h - 1] += power
        }
        val pitched = partials.sum()
        require(pitched > 1e-20 && total > 1e-20) { "silent harmonic measurement" }
        return Harmonics(partials[0] / pitched, (pitched - partials[0]) / pitched, pitched / total)
    }

    /** Gain-normalized RMS magnitudes in triangular log bands discard FFT phase. */
    private fun magnitudeDistance(a: Snip, b: Snip): Double {
        fun bands(snip: Snip): DoubleArray {
            val (real, imaginary) = windowedFft(snip, .15, .35)
            val power = DoubleArray(112)
            for (bin in 1 until real.size / 2) {
                val hz = bin.toDouble() * snip.sampleRate / real.size
                if (hz !in 16.0..16_000.0) continue
                val position = ln(hz / 16.0) / ln(1000.0) * power.lastIndex
                val left = floor(position).toInt().coerceIn(0, power.lastIndex)
                val fraction = position - left
                val energy = real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
                power[left] += energy * (1 - fraction)
                power[minOf(left + 1, power.lastIndex)] += energy * fraction
            }
            val magnitude = DoubleArray(power.size) { sqrt(power[it]) }
            val norm = sqrt(magnitude.sumOf { it * it })
            require(norm > 1e-20) { "silent magnitude measurement" }
            return DoubleArray(magnitude.size) { magnitude[it] / norm }
        }
        val first = bands(a)
        val second = bands(b)
        return sqrt(first.indices.sumOf { val delta = first[it] - second[it]; delta * delta })
    }

    private fun windowedFft(snip: Snip, from: Double, until: Double): Pair<FloatArray, FloatArray> {
        val start = (from * snip.sampleRate).toInt()
        val end = minOf(snip.samples.size, (until * snip.sampleRate).toInt())
        val count = end - start
        require(count >= 128) { "insufficient audio for the requested spectrum window" }
        var size = 32768
        while (size < count) size *= 2
        val real = FloatArray(size)
        val imaginary = FloatArray(size)
        for (i in 0 until count) {
            val angle = 2 * PI * i / (count - 1)
            val window = .35875 - .48829 * cos(angle) + .14128 * cos(2 * angle) - .01168 * cos(3 * angle)
            real[i] = (snip.samples[start + i] * window).toFloat()
        }
        Fft.forward(real, imaginary)
        return real to imaginary
    }
}
