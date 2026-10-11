package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The phrase's causal contract, measured before and after the shared output level. */
class RelayTest {
    private val timbral = listOf("ORDER", "PULSE", "BLEND", "COLOR", "LENGTH")
    private val drums = setOf(
        DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM,
    )

    /** Four handoffs and four fixed accents, on a shorter clock for endpoint grids. */
    private fun compactPhrase(voice: RelayVoice): RelayPhrase = Relay.phraseFor(voice).let { phrase ->
        phrase.copy(
            tempoBpm = 180f,
            slots = phrase.slots.map { it.copy(beats = 0.5f, transitionBeats = 0.12f) },
            accents = listOf(RelayAccent(0f, 1f), RelayAccent(0.5f, 0.45f),
                RelayAccent(1f, 0.8f), RelayAccent(1.5f, 0.35f)),
        )
    }

    private fun rms(samples: FloatArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    /** Match level and pad the shorter finite tail with silence: a gain-only knob must fail. */
    private fun matchedDifference(a: FloatArray, b: FloatArray): Double {
        val ra = rms(a)
        val rb = rms(b)
        if (ra < 1e-12 || rb < 1e-12) return 0.0
        val n = maxOf(a.size, b.size)
        var energy = 0.0
        for (i in 0 until n) {
            val x = if (i < a.size) a[i] / ra else 0.0
            val y = if (i < b.size) b[i] / rb else 0.0
            energy += (x - y) * (x - y)
        }
        return sqrt(energy / n)
    }

    /** Compare response waveforms: equal response magnitudes can still have different shapes. */
    private fun interactionResidual(corners: List<FloatArray>): Double {
        val levels = corners.map { rms(it).coerceAtLeast(1e-12) }
        val n = corners.maxOf { it.size }
        var difference = 0.0
        var reference = 0.0
        for (i in 0 until n) {
            val values = DoubleArray(4) { corner ->
                if (i < corners[corner].size) corners[corner][i] / levels[corner] else 0.0
            }
            val lowResponse = values[2] - values[0]
            val highResponse = values[3] - values[1]
            val residual = highResponse - lowResponse
            difference += residual * residual
            reference += values.sumOf { it * it } / 4.0
        }
        return sqrt(difference / reference.coerceAtLeast(1e-12))
    }

    private fun weightDifference(a: List<Relay.WeightFrame>, b: List<Relay.WeightFrame>): Double {
        assertEquals(a.size, b.size, "ORDER changed the phrase clock")
        var total = 0.0
        for ((x, y) in a.zip(b)) {
            assertEquals(x.timeSeconds, y.timeSeconds, "ORDER changed the frame times")
            assertEquals(x.weights.size, y.weights.size)
            total += x.weights.zip(y.weights).sumOf { (p, q) -> abs(p - q) }
        }
        return total / a.size.coerceAtLeast(1)
    }

    /** Inspect the actual played wrap as well as the adjacent-cycle energy metric. */
    private fun assertPlayedWrap(samples: FloatArray, label: String) {
        var steepest = 0.0
        var largestKink = 0.0
        for (i in 1 until samples.size) {
            steepest = maxOf(steepest, abs(samples[i] - samples[i - 1]).toDouble())
            if (i > 1) largestKink = maxOf(largestKink,
                abs((samples[i] - samples[i - 1]) - (samples[i - 1] - samples[i - 2])).toDouble())
        }
        val before = (samples.last() - samples[samples.lastIndex - 1]).toDouble()
        val across = (samples.first() - samples.last()).toDouble()
        val after = (samples[1] - samples[0]).toDouble()
        assertTrue(abs(across) <= steepest * 1.25 + 1e-6, "$label wrap has an exceptional sample step")
        assertTrue(maxOf(abs(across - before), abs(after - across)) <= largestKink * 2.0 + 1e-6,
            "$label wrap has a slope discontinuity")
    }

    @Test
    fun `the five timbral controls use normalized macros and hold starts finite`() {
        for (voice in RelayVoice.entries) {
            val specs = Relay.macrosFor(voice)
            assertEquals(listOf("TUNE") + timbral + "HOLD", specs.map { it.name }, "$voice macros")
            assertTrue(specs.all { it.default.isFinite() && it.default in 0f..1f && it.neutral in 0f..1f })
            assertEquals(0f, Relay.defaults(voice).getValue("HOLD"), "$voice default hold")
        }
    }

    @Test
    fun `identical recipes reproduce audio accents and weights at 44k mono`() {
        val macros = mapOf("ORDER" to 0.43f, "PULSE" to 0.75f, "BLEND" to 0.62f)
        val a = Relay.inspect(RelayVoice.KEY, macros, velocity = 0.7f)
        val b = Relay.inspect(RelayVoice.KEY, macros, velocity = 0.7f)
        assertEquals(44100, a.snip.sampleRate)
        assertEquals(1, a.snip.channels)
        assertContentEquals(a.raw, b.raw)
        assertContentEquals(a.snip.samples, b.snip.samples)
        assertEquals(a.accents, b.accents)
        assertEquals(a.weights, b.weights)
        assertTrue(a.accents.isNotEmpty() && a.weights.isNotEmpty(), "determinism case did not exercise a phrase")
    }

    @Test
    fun `raw defaults and bright rhythmic extremes stay bounded and finish their tails`() {
        val cases = RelayVoice.entries.map { it to emptyMap<String, Float>() } + listOf(
            RelayVoice.BASS to timbral.associateWith { 1f },
            RelayVoice.CHIME to (timbral.associateWith { 1f } + ("TUNE" to 1f)),
            RelayVoice.FLOW to timbral.associateWith { 0f },
        )
        for ((voice, macros) in cases) {
            val report = Relay.inspect(voice, macros, normalize = false)
            val rawRms = rms(report.raw)
            assertTrue(report.raw.isNotEmpty() && report.raw.all { it.isFinite() }, "$voice $macros invalid raw audio")
            assertTrue(report.rawPeak.isFinite() && report.rawPeak in 0.00001..4.0,
                "$voice $macros uncontrolled or silent raw peak ${report.rawPeak}")
            assertTrue(rawRms > 1e-6, "$voice $macros silent raw source")
            assertTrue(abs(report.raw.average()) < rawRms * 0.02 + 1e-5,
                "$voice $macros raw DC ${report.raw.average()} at RMS $rawRms")
            assertTrue(report.snip.samples.all { it.isFinite() }, "$voice $macros invalid output")
            assertTrue(report.snip.durationSeconds >= report.gateSeconds,
                "$voice output ends before its selected gate")
            val tail = report.snip.samples.takeLast(256)
            assertTrue(tail.maxOf { abs(it) } < report.snip.peak() * 0.01 + 1e-6f,
                "$voice finite output cuts an audible tail")
        }
    }

    @Test
    fun `order changes handoffs on the same clock and morphs continuously at roster boundaries`() {
        for (voice in RelayVoice.entries) {
            val phrase = Relay.phraseFor(voice)
            val first = Relay.weightSchedule(phrase, 0f, 0.25f)
            val last = Relay.weightSchedule(phrase, 1f, 0.25f)
            assertTrue(weightDifference(first, last) > 0.1, "$voice ORDER endpoints have the same handoffs")
            val boundaries = (1 until phrase.orderRoster.lastIndex).map { it / phrase.orderRoster.lastIndex.toFloat() }
            for (order in boundaries + 0.5f) {
                val before = Relay.weightSchedule(phrase, order - 0.0001f, 0.25f)
                val after = Relay.weightSchedule(phrase, order + 0.0001f, 0.25f)
                val difference = weightDifference(before, after)
                assertTrue(difference < 0.005, "$voice ORDER jumps near $order: $difference")
            }
            for (frame in first + last) {
                assertTrue(frame.weights.all { it.isFinite() && it in 0.0..1.000001 }, "$voice invalid vector weights")
                assertEquals(1.0, frame.weights.sum(), 1e-5, "$voice vector weights lost calibrated total gain")
            }
        }
    }

    @Test
    fun `pulse strengthens the fixed accent roster without changing handoff order`() {
        val flowing = Relay.inspect(RelayVoice.KEY, mapOf("ORDER" to 0f, "PULSE" to 0f, "LENGTH" to 1f))
        val reordered = Relay.inspect(RelayVoice.KEY, mapOf("ORDER" to 1f, "PULSE" to 0f, "LENGTH" to 1f))
        val pulsed = Relay.inspect(RelayVoice.KEY, mapOf("ORDER" to 0f, "PULSE" to 1f, "LENGTH" to 1f))
        assertEquals(flowing.accents, reordered.accents, "ORDER altered independently placed accents")
        assertEquals(flowing.accents.map { it.beat to it.timeSeconds }, pulsed.accents.map { it.beat to it.timeSeconds },
            "PULSE altered tempo or invented events")
        assertEquals(flowing.weights, pulsed.weights, "PULSE altered source handoffs")
        assertFalse(flowing.accents.map { it.strength } == pulsed.accents.map { it.strength }, "PULSE left accent energy unchanged")
        assertTrue(matchedDifference(flowing.snip.samples, pulsed.snip.samples) > 0.01, "PULSE changed only output gain")
    }

    @Test
    fun `finite release starts no late accents while hold preserves the serialized figure`() {
        val voice = RelayVoice.KEY
        val phrase = compactPhrase(voice)
        val macros = mapOf("LENGTH" to 0f, "ORDER" to 0f, "PULSE" to 1f)
        val finite = Relay.inspect(voice, macros, phrase = phrase)
        assertEquals(listOf(0.0, 0.5), finite.accents.map { it.beat }, "short finite gate admitted a late attack")
        assertTrue(finite.accents.all { it.timeSeconds < finite.gateSeconds })
        val changed = Relay.inspect(voice, macros + mapOf("ORDER" to 1f, "PULSE" to 0f), phrase = phrase)
        assertEquals(finite.accents.map { it.beat to it.timeSeconds }, changed.accents.map { it.beat to it.timeSeconds },
            "ORDER/PULSE changed which fixed accents the finite gate admits")

        // Removing the two late serialized events must leave finite audio
        // unchanged, proving the admission rule reaches synthesis and release.
        val earlierOnly = phrase.copy(accents = phrase.accents.take(2))
        assertContentEquals(finite.snip.samples, Relay.render(voice, macros, phrase = earlierOnly).samples,
            "late serialized accents restarted the finite release")
        val afterGate = (finite.gateSeconds * Dsp.RATE).toInt()
        val releasing = finite.snip.samples.copyOfRange(afterGate, afterGate + Dsp.RATE / 10)
        assertTrue(rms(releasing) > 1e-5, "the admitted figure was cut to silence at the gate")

        val heldMacros = macros + ("HOLD" to 1f)
        val held = Relay.inspect(voice, heldMacros, phrase = phrase)
        assertEquals(phrase.accents.map { it.beat.toDouble() }, held.accents.map { it.beat },
            "held articulation discarded part of the serialized repeating figure")
        assertTrue(held.accents.any { it.timeSeconds >= held.gateSeconds }, "held fixture did not exercise accents beyond its articulation gate")
        assertTrue(matchedDifference(held.snip.samples, Relay.render(voice, heldMacros, phrase = earlierOnly).samples) > 0.01,
            "late serialized accents disappeared from held audio")
        assertEquals(4, phrase.accents.size, "rendering mutated the saved accent roster")
    }

    @Test
    fun `blend broadens overlap rather than changing the order or clock`() {
        fun concentration(frames: List<Relay.WeightFrame>) =
            frames.map { frame -> frame.weights.sumOf { it * it } }.average()
        for (voice in RelayVoice.entries) {
            val phrase = Relay.phraseFor(voice)
            val tight = Relay.weightSchedule(phrase, 0.3f, 0f)
            val broad = Relay.weightSchedule(phrase, 0.3f, 1f)
            assertTrue(weightDifference(tight, broad) > 0.05, "$voice BLEND did not change the transitions")
            assertTrue(concentration(broad) < concentration(tight) - 0.02,
                "$voice BLEND did not create useful mixed-source passages")
        }
    }

    @Test
    fun `velocity reaches raw excitation and changes articulation after level matching`() {
        for (voice in listOf(RelayVoice.KEY, RelayVoice.BASS, RelayVoice.CHIME)) {
            val phrase = compactPhrase(voice)
            val soft = Relay.inspect(voice, velocity = 0.25f, normalize = false, phrase = phrase)
            val hard = Relay.inspect(voice, velocity = 1f, normalize = false, phrase = phrase)
            assertTrue(rms(hard.raw) > rms(soft.raw) * 1.5, "$voice velocity lost its excitation response")
            assertTrue(matchedDifference(soft.snip.samples, hard.snip.samples) > 0.01,
                "$voice velocity changes only gain, leaving articulation unchanged")
            assertEquals(soft.weights, hard.weights, "$voice velocity altered source order")
            assertEquals(soft.accents.map { it.timeSeconds }, hard.accents.map { it.timeSeconds }, "$voice velocity altered phrase clock")
        }
    }

    @Test
    fun `finite timbral handoffs retain the requested root at three tune positions`() {
        for (voice in RelayVoice.entries) for (tune in listOf(0f, 0.5f, 1f)) {
            val snip = Relay.render(voice, mapOf("TUNE" to tune, "PULSE" to 1f, "COLOR" to 0.8f))
            val hz = Relay.frequencyFor(voice, tune)
            val cents = FineTuning.cents(FineTuning.measuredHz(snip, hz, 0.07f, 0.4f), hz.toDouble())
            assertTrue(abs(cents) < 5.0, "$voice TUNE $tune plays $cents cents from its requested root")
            assertTrue(Classifier.classify(snip).drumClass !in drums, "$voice TUNE $tune classified as percussion")
        }
    }

    @Test
    fun `every timbral macro changes every dry voice after matching output level`() {
        for (voice in RelayVoice.entries) for (name in timbral) {
            val phrase = compactPhrase(voice)
            val low = Relay.render(voice, mapOf(name to 0f), phrase = phrase).samples
            val high = Relay.render(voice, mapOf(name to 1f), phrase = phrase).samples
            val distance = matchedDifference(low, high)
            assertTrue(distance > 0.01, "$voice $name has only a gain change or no useful effect: $distance")
        }
    }

    @Test
    fun `the required macro pairs interact in the evolving audio`() {
        val pairs = listOf("ORDER" to "PULSE", "ORDER" to "BLEND", "BLEND" to "COLOR", "PULSE" to "LENGTH")
        for ((a, b) in pairs) {
            val corners = listOf(0f to 0f, 0f to 1f, 1f to 0f, 1f to 1f).map { (x, y) ->
                Relay.render(RelayVoice.KEY, mapOf(a to x, b to y), phrase = compactPhrase(RelayVoice.KEY)).samples
            }
            val atLow = matchedDifference(corners[0], corners[2])
            val atHigh = matchedDifference(corners[1], corners[3])
            assertTrue(atLow > 0.01 && atHigh > 0.01, "$a became ineffective at an endpoint of $b")
            val residual = interactionResidual(corners)
            println("RELAY interaction $a/$b: endpoint distances $atLow / $atHigh; mixed waveform residual $residual")
            assertTrue(residual > 0.01, "$a/$b has no useful mixed waveform response: $residual")
        }
    }

    @Test
    fun `held phrase closes against its preceding cycle without losing requested pitch`() {
        for (voice in RelayVoice.entries) {
            val report = Relay.inspect(voice, mapOf("HOLD" to 1f, "PULSE" to 1f, "COLOR" to 0.8f))
            assertTrue(report.loop, "$voice HOLD did not select the repeating phrase")
            assertTrue(report.previousCycle.size >= 256, "$voice HOLD omitted its preceding cycle")
            val samples = report.snip.samples
            val loopStart = report.previousCycle.size
            val adjacent = report.previousCycle + samples
            val seam = Keys.seamError(adjacent, loopStart)
            assertTrue(seam.isFinite() && seam < 1e-3, "$voice actual preceding-cycle seam $seam")
            assertTrue(report.seamError.isFinite() && report.seamError < 1e-3, "$voice reported seam ${report.seamError}")
            assertTrue(samples.all { it.isFinite() && abs(it) <= 0.991f }, "$voice invalid held output")
            assertPlayedWrap(samples, voice.name)
            val hz = report.requestedHz.toFloat()
            val cents = FineTuning.cents(FineTuning.measuredHz(report.snip, hz, 0.07f, 0.4f), hz.toDouble())
            assertTrue(abs(cents) < 5.0, "$voice HOLD detuned the root by $cents cents")
        }
    }

    @Test
    fun `a minimum phrase with transitions wider than a whole cycle remains periodic`() {
        val voice = RelayVoice.KEY
        val phrase = Relay.phraseFor(voice).let { initial ->
            initial.copy(tempoBpm = 60f,
                slots = initial.slots.map { it.copy(beats = 0.25f, transitionBeats = 2f) },
                accents = listOf(RelayAccent(0f, 1f), RelayAccent(0.5f, 0.5f)))
        }
        val frames = Relay.weightSchedule(phrase, 0.45f, 1f, periodic = true)
        assertEquals(frames.first().weights, frames.last().weights, "periodic endpoint weights differ")
        val steps = frames.zipWithNext().map { (a, b) ->
            a.weights.zip(b.weights).sumOf { (x, y) -> abs(x - y) }
        }
        val ordinaryStep = steps.dropLast(1).max()
        assertTrue(steps.last() <= ordinaryStep * 1.25 + 1e-8,
            "widest transitions jump when the short clock wraps: ${steps.last()} vs $ordinaryStep")
        val report = Relay.inspect(voice, mapOf("HOLD" to 1f, "BLEND" to 1f, "ORDER" to 0.45f), phrase = phrase)
        val seam = Keys.seamError(report.previousCycle + report.snip.samples, report.previousCycle.size)
        assertTrue(seam.isFinite() && seam < 1e-3, "short broad phrase seam $seam")
        assertPlayedWrap(report.snip.samples, "short broad phrase")
        val hz = report.requestedHz.toFloat()
        val cents = FineTuning.cents(FineTuning.measuredHz(report.snip, hz, 0.07f, 0.4f), hz.toDouble())
        assertTrue(abs(cents) < 5.0, "short broad phrase detunes by $cents cents")
    }
}
