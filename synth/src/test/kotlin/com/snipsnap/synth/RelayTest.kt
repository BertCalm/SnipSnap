package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun span(samples: FloatArray, from: Double, until: Double, rate: Int = Dsp.RATE): FloatArray =
        samples.copyOfRange((from * rate).toInt(), (until * rate).toInt())

    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size, "same-clock history fixtures have different durations")
        var difference = 0.0
        var reference = 0.0
        for (i in a.indices) {
            val d = a[i].toDouble() - b[i]
            difference += d * d
            reference += a[i].toDouble() * a[i]
        }
        return sqrt(difference / reference.coerceAtLeast(1e-12))
    }

    private data class HarmonicProfile(val bands: List<Double>, val even: Double, val oddDetail: Double)

    /** Gain-independent powers: root/2–3/4–7/8–16/residual, plus even and odd detail. */
    private fun harmonicPowers(samples: FloatArray, hz: Double): HarmonicProfile {
        val n = 65536
        require(samples.size in 9..n)
        val real = FloatArray(n)
        val imaginary = FloatArray(n)
        for (i in samples.indices) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (samples.size - 1))
            real[i] = (samples[i] * window).toFloat()
        }
        Fft.forward(real, imaginary)
        val powers = DoubleArray(17)
        for (bin in 1 until n / 2) {
            val frequency = bin * Dsp.RATE.toDouble() / n
            val harmonic = (frequency / hz).roundToInt()
            val band = if (harmonic in 1..16 && abs(frequency - harmonic * hz) <= 12.0) harmonic else 0
            powers[band] += real[bin].toDouble() * real[bin] + imaginary[bin].toDouble() * imaginary[bin]
        }
        val total = powers.sum()
        require(total > 1e-12) { "source contrast fixture was silent" }
        return HarmonicProfile(
            listOf(powers[1], (2..3).sumOf { powers[it] }, (4..7).sumOf { powers[it] },
                (8..16).sumOf { powers[it] }, powers[0]).map { it / total },
            (2..16 step 2).sumOf { powers[it] } / total,
            (3..15 step 2).sumOf { powers[it] } / total,
        )
    }

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
    fun `isolated source roles retain different spectra and transient jobs at one pitch`() {
        val voice = RelayVoice.KEY
        val original = Relay.phraseFor(voice)
        val macros = mapOf("TUNE" to 0f, "ORDER" to 0f, "PULSE" to 0f,
            "BLEND" to 0f, "COLOR" to 0.65f, "LENGTH" to 1f)
        val hz = Relay.frequencyFor(voice, 0f).toDouble()
        val samples = RelayRole.entries.associateWith { role ->
            val source = original.sources.indexOfFirst { it.role == role }
            assertTrue(source >= 0, "$role is absent from the shared source fixture")
            val phrase = original.copy(
                slots = List(4) { RelaySlot(source, 1f, 0.2f) },
                orderRoster = listOf(List(4) { source }), accents = emptyList(), tempoBpm = 100f,
            )
            Relay.render(voice, macros, phrase = phrase).samples
        }
        val attack = samples.mapValues { (_, audio) -> harmonicPowers(span(audio, 0.04, 0.24), hz) }
        val body = samples.mapValues { (_, audio) -> harmonicPowers(span(audio, 0.32, 0.52), hz) }
        val attackToBody = samples.mapValues { (_, audio) ->
            rms(span(audio, 0.025, 0.125)) / rms(span(audio, 0.32, 0.52)).coerceAtLeast(1e-12)
        }
        for (role in RelayRole.entries) println(
            "RELAY source $role: attack powers ${attack.getValue(role)}, body powers ${body.getValue(role)}, " +
                "early/body RMS ${attackToBody.getValue(role)}",
        )
        // These relative guards preserve the authored jobs. They catch a common
        // almost-pure root with quiet decorative partials; listening decides identity.
        val softRoot = body.getValue(RelayRole.SOFT).bands[0]
        for (role in listOf(RelayRole.PLUCK, RelayRole.ORGAN, RelayRole.BELL)) {
            val sourceRoot = if (role == RelayRole.ORGAN) body.getValue(role).bands[0] else attack.getValue(role).bands[0]
            assertTrue(softRoot - sourceRoot > 0.15,
                "$role collapsed toward the soft source's root-dominated spectrum: $sourceRoot vs $softRoot")
        }
        val bell = attack.getValue(RelayRole.BELL)
        val organ = body.getValue(RelayRole.ORGAN)
        assertTrue(bell.oddDetail > bell.even * 2.0 && organ.even > organ.oddDetail * 2.0,
            "bell odd detail and organ even drawbar registers lost their separate jobs: $bell / $organ")
        assertTrue(attackToBody.getValue(RelayRole.PLUCK) > attackToBody.getValue(RelayRole.ORGAN) * 1.5,
            "the pluck lost its decaying transient relative to the sustained organ")
        assertTrue(attackToBody.getValue(RelayRole.SOFT) < attackToBody.getValue(RelayRole.ORGAN) * 0.5,
            "the soft source lost its swelling entrance relative to the sustained organ")
    }

    @Test
    fun `subdividing an unchanged source never restarts its note history`() {
        val voice = RelayVoice.KEY
        val original = Relay.phraseFor(voice)
        val macros = mapOf("TUNE" to 0f, "ORDER" to 0f, "BLEND" to 0.4f, "COLOR" to 0.65f, "LENGTH" to 1f)
        for (role in RelayRole.entries) for (pulse in listOf(0f, Relay.defaults(voice).getValue("PULSE"))) {
            val source = original.sources.indexOfFirst { it.role == role }
            val four = original.copy(slots = List(4) { RelaySlot(source, 1f, 0.2f) },
                orderRoster = listOf(List(4) { source }), accents = emptyList(), tempoBpm = 100f)
            val eight = four.copy(slots = List(8) { RelaySlot(source, 0.5f, 0.1f) },
                orderRoster = listOf(List(8) { source }))
            val a = Relay.inspect(voice, macros + ("PULSE" to pulse), normalize = false, phrase = four)
            val b = Relay.inspect(voice, macros + ("PULSE" to pulse), normalize = false, phrase = eight)
            val difference = relativeDifference(a.raw, b.raw)
            println("RELAY shared history $role PULSE $pulse: subdivision raw difference $difference")
            // Slot subdivision has no musical effect in an unchanged source.
            // This tolerance covers numerical control interpolation, not smoothness.
            assertTrue(difference < 1e-5, "$role PULSE $pulse restarted its contour at a score boundary: $difference")
        }
        val source = original.sources.indexOfFirst { it.role == RelayRole.ORGAN }
        val four = original.copy(slots = List(4) { RelaySlot(source, 1f, 0.2f) },
            orderRoster = listOf(List(4) { source }), accents = emptyList(), tempoBpm = 100f)
        val eight = four.copy(slots = List(8) { RelaySlot(source, 0.5f, 0.1f) },
            orderRoster = listOf(List(8) { source }))
        val heldMacros = macros + mapOf("HOLD" to 1f, "PULSE" to 0f, "LENGTH" to 0.55f)
        val heldFour = Relay.inspect(voice, heldMacros, normalize = false, phrase = four)
        val heldEight = Relay.inspect(voice, heldMacros, normalize = false, phrase = eight)
        val heldDifference = relativeDifference(heldFour.raw, heldEight.raw)
        println("RELAY shared held history ORGAN PULSE 0: subdivision raw difference $heldDifference")
        assertTrue(heldDifference < 1e-5, "HOLD imposed a fresh amplitude figure for every score slot: $heldDifference")
    }

    @Test
    fun `serialized accents give high pulse stronger emphasis than the flowing defaults`() {
        val voice = RelayVoice.KEY
        val original = Relay.phraseFor(voice)
        val source = original.sources.indexOfFirst { it.role == RelayRole.ORGAN }
        val phrase = original.copy(slots = List(4) { RelaySlot(source, 1f, 0.2f) },
            orderRoster = listOf(List(4) { source }), tempoBpm = 100f,
            accents = listOf(0.5f, 1.5f, 2.5f, 3.5f).map { RelayAccent(it, 1f) })
        val macros = mapOf("TUNE" to 0f, "ORDER" to 0f, "BLEND" to 0.5f, "COLOR" to 0.65f, "LENGTH" to 1f)
        fun render(pulse: Float, score: RelayPhrase = phrase) =
            Relay.inspect(voice, macros + ("PULSE" to pulse), normalize = false, phrase = score)
        fun contrast(report: Relay.Report): Double = phrase.accents.map { event ->
            val time = event.beat / phrase.phraseBeats * report.phraseSeconds
            val before = rms(span(report.raw, time - 0.12, time - 0.05, Dsp.RATE * Dsp.OVERSAMPLE))
            val after = rms(span(report.raw, time + 0.015, time + 0.08, Dsp.RATE * Dsp.OVERSAMPLE))
            after / before.coerceAtLeast(1e-12)
        }.average()
        val flowing = render(0f)
        val defaults = render(Relay.defaults(voice).getValue("PULSE"))
        val rhythmic = render(1f)
        val noAccents = render(1f, phrase.copy(accents = emptyList()))
        val lowWithoutAccents = render(0f, phrase.copy(accents = emptyList()))
        val low = contrast(flowing)
        val middle = contrast(defaults)
        val high = contrast(rhythmic)
        val neutral = contrast(noAccents)
        println("RELAY accent contrast pre/post raw RMS: low $low, default $middle, high $high, empty roster $neutral")
        assertContentEquals(flowing.raw, lowWithoutAccents.raw, "PULSE zero still imposed audible accent events")
        assertEquals(flowing.weights, rhythmic.weights, "accent emphasis changed source order")
        assertEquals(flowing.accents.map { it.timeSeconds }, rhythmic.accents.map { it.timeSeconds }, "accent emphasis changed timing")
        assertTrue(high > middle && high > low && high > neutral,
            "high PULSE failed to reveal the serialized accents: $low / $middle / $high / $neutral")
        assertTrue(middle - low < (high - low) * 0.5,
            "default accents are as strongly articulated as the rhythmic endpoint")
    }

    @Test
    fun `voice scores author distinct entrances dwell priorities and accent figures`() {
        val phrases = RelayVoice.entries.associateWith { Relay.phraseFor(it) }
        fun path(voice: RelayVoice) = phrases.getValue(voice).let { phrase ->
            phrase.orderRoster.first().map { phrase.sources[it].role }
        }
        fun dwellFraction(voice: RelayVoice, role: RelayRole) = phrases.getValue(voice).let { phrase ->
            val slot = path(voice).indexOf(role)
            phrase.slots[slot].beats / phrase.phraseBeats
        }
        for ((voice, phrase) in phrases) println(
            "RELAY score $voice: ${phrase.tempoBpm} BPM/${phrase.phraseSeconds}s; path ${path(voice)}; " +
                "dwell fractions ${phrase.slots.map { it.beats / phrase.phraseBeats }}; accents ${phrase.accents}",
        )
        assertEquals(listOf(RelayRole.PLUCK, RelayRole.ORGAN), path(RelayVoice.KEY).take(2), "key lost its attack-to-body handoff")
        assertEquals(listOf(RelayRole.ORGAN, RelayRole.PLUCK), path(RelayVoice.BASS).take(2), "bass lost its body-to-pluck answer")
        assertEquals(listOf(RelayRole.PLUCK, RelayRole.BELL), path(RelayVoice.PLUCK).take(2), "pluck lost its bright answer")
        assertEquals(RelayRole.ORGAN, path(RelayVoice.ORGAN).first(), "organ lost its sustained entrance")
        assertEquals(RelayRole.BELL, path(RelayVoice.CHIME).first(), "chime lost its ringing entrance")
        assertEquals(RelayRole.SOFT, path(RelayVoice.FLOW).first(), "flow lost its soft entrance")
        assertTrue(dwellFraction(RelayVoice.ORGAN, RelayRole.ORGAN) > dwellFraction(RelayVoice.PLUCK, RelayRole.PLUCK),
            "sustained organ and picked pluck have the same opening dwell priority")
        assertTrue(dwellFraction(RelayVoice.CHIME, RelayRole.BELL) > dwellFraction(RelayVoice.PLUCK, RelayRole.BELL),
            "chime's ringing body became the pluck's brief bell answer")
        assertTrue(phrases.getValue(RelayVoice.BASS).accents.size > phrases.getValue(RelayVoice.FLOW).accents.size,
            "rhythmic bass and flowing texture share the same accent density")
        assertTrue(phrases.getValue(RelayVoice.FLOW).phraseSeconds > phrases.getValue(RelayVoice.PLUCK).phraseSeconds,
            "flow lost its longer phrase relative to pluck")
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

        val releaseFrames = finite.weights.filter { it.timeSeconds >= finite.gateSeconds }
        assertTrue(releaseFrames.size > 2 && releaseFrames.last().timeSeconds > finite.gateSeconds + 0.2,
            "finite diagnostics omitted the release interval")
        assertTrue(releaseFrames.all { it.weights == releaseFrames.first().weights },
            "finite release starts a fresh source handoff after its selected gate")
        val planned = Relay.weightSchedule(phrase, 0f, Relay.defaults(voice).getValue("BLEND"))
        assertTrue(planned.any { frame ->
            frame.timeSeconds > finite.gateSeconds &&
                frame.weights.zip(releaseFrames.first().weights).sumOf { (a, b) -> abs(a - b) } > 0.1
        }, "finite fixture did not exclude a later planned source handoff")

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

    @Test
    fun `an interrupted finite or held render stops instead of finishing beside its replacement`() {
        // SynthScreen previews run in runInterruptible: a newer request interrupts the worker, and the
        // frame loop, the band limit, the decimation, the level and the seam must stop on that flag.
        for ((missing, render) in listOf<Pair<String, () -> Unit>>(
            "RELAY finite render did not stop on the interrupt at its look before the wavetables" to
                { Relay.render(RelayVoice.KEY) },
            "RELAY held render did not stop on the interrupt at its look before the wavetables" to
                { Relay.render(RelayVoice.KEY, mapOf("HOLD" to 1f)) },
        )) {
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<java.util.concurrent.CancellationException>(missing) { render() }
            } finally {
                Thread.interrupted()
            }
        }
        assertTrue(Relay.render(RelayVoice.KEY).samples.any { it != 0f }, "an uninterrupted render still sounds")
    }

    @Test
    fun `a render nobody wants any more after its frame loop stops after the band limit too`() {
        // A finite phrase has no warm-up, so after one look before the wavetables the frame loop looks once per 8192
        // frames of its raw buffer and the conditioning pass looks once on entry; an interrupt that lands after those
        // looks must still stop the render before its decimation, its level and its seam instead of finishing unwanted.
        // An uninterrupted render looks exactly five times outside the loop: before the wavetables, on entry to the
        // conditioning pass, after the band limit, before the level and before the seam. Removing any one changes the count.
        val clear = LateInterrupt(clearLooks = Int.MAX_VALUE)
        val whole = clear.run { Relay.inspect(RelayVoice.KEY) }
        assertFalse(whole.loop, "the default phrase is finite")
        val loopLooks = (whole.raw.size + 8191) / 8192
        assertEquals(loopLooks + 5, clear.looks,
            "an uninterrupted finite render looks before the wavetables, $loopLooks times in its frame loop, " +
                "then on entry to the conditioning pass, after the band limit, before the level and before the seam")
        val late = LateInterrupt(clearLooks = 1 + loopLooks + 1)
        assertFailsWith<java.util.concurrent.CancellationException>(
            "RELAY finite render did not stop on the interrupt at its look after the band limit, before the decimation",
        ) { late.run { Relay.inspect(RelayVoice.KEY) } }
    }
}
