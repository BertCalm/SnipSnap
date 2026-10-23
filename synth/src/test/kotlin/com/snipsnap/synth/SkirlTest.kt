package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Acceptance of the native dry instrument, beyond the isolated solver and
 * contact-budget probes. Measured values are printed so the first Kotlin
 * audition can distinguish numerical evidence from the owner's Python listen.
 */
class SkirlTest {
    private val normalMacros = listOf("TUNE", "LEAN", "BRAKE", "EDGE", "PLATE", "COLLAPSE")
    private val drumClasses = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)

    private fun rms(samples: FloatArray, from: Int = 0, until: Int = samples.size): Double {
        require(from in 0..until && until <= samples.size)
        var energy = 0.0
        for (i in from until until) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (until - from).coerceAtLeast(1))
    }

    /** Match the complete gesture's energy and retain the longer tail as signal.
     * A render cut before terminal onset cannot prove COLLAPSE has any effect. */
    private fun normalized(samples: FloatArray): DoubleArray {
        val level = rms(samples).coerceAtLeast(1e-20)
        return DoubleArray(samples.size) { samples[it] / level }
    }

    private fun distance(a: FloatArray, b: FloatArray): Double {
        val x = normalized(a)
        val y = normalized(b)
        val n = maxOf(x.size, y.size)
        var difference = 0.0
        for (i in 0 until n) {
            val d = (x.getOrNull(i) ?: 0.0) - (y.getOrNull(i) ?: 0.0)
            difference += d * d
        }
        return sqrt(difference / n)
    }

    /** A second finite difference vanishes for two additive independent controls.
     * Each corner is gain matched so output leveling alone cannot create a pass. */
    private fun interaction(corners: List<FloatArray>): Double {
        require(corners.size == 4)
        val v = corners.map(::normalized)
        val n = v.maxOf { it.size }
        var energy = 0.0
        for (i in 0 until n) {
            val d = (v[3].getOrNull(i) ?: 0.0) - (v[2].getOrNull(i) ?: 0.0) -
                (v[1].getOrNull(i) ?: 0.0) + (v[0].getOrNull(i) ?: 0.0)
            energy += d * d
        }
        return sqrt(energy / n)
    }

    private fun cents(measured: Float, expected: Float): Double =
        1200.0 * ln(measured / expected.toDouble()) / ln(2.0)

    private fun assertRoot(snip: Snip, midi: Int, from: Double, label: String): Double {
        val estimate = assertNotNull(Pitch.detect(snip, from.toFloat(), .30f),
            "$label: no fundamental at $from seconds")
        val error = cents(estimate.hz, Keys.midiHz(midi))
        println("SKIRL ROOT $label at $from s: ${estimate.hz} Hz, $error cents, confidence ${estimate.confidence}")
        assertTrue(estimate.confidence >= .30f, "$label: weak pitch evidence ${estimate.confidence}")
        // A half-semitone is the initial native acceptance bound, not a claim
        // of precision tuning. The unconstrained detector can expose octaves.
        assertTrue(abs(error) < 50.0, "$label: root is $error cents off at $from seconds")
        return abs(error)
    }

    @Test
    fun `both voices retain their note and dry output health from C2 through C6 and soft through firm touch`() {
        var worstPitch = 0.0
        var lowestLoudness = Float.POSITIVE_INFINITY
        var largestDc = 0.0
        val pitchFailures = ArrayList<String>()
        fun checkRoot(snip: Snip, midi: Int, from: Double, label: String) {
            try {
                worstPitch = maxOf(worstPitch, assertRoot(snip, midi, from, label))
            } catch (failure: AssertionError) {
                // Finish the register matrix so one failed low note cannot
                // conceal a second register's fault. The test still fails.
                val message = failure.message ?: label
                pitchFailures += message
                println("SKIRL ROOT FAILURE $message")
            }
        }
        for (voice in SkirlVoice.entries) for (midi in listOf(36, 48, 60, 72, 84)) {
            for (velocity in listOf(.25f, 1f)) {
                val p = Skirl.inspect(voice, midi = midi, velocity = velocity)
                val s = p.snip
                val label = "$voice MIDI $midi velocity $velocity"
                assertEquals(1, s.channels, label)
                assertEquals(44_100, s.sampleRate, label)
                assertTrue(s.samples.all { it.isFinite() }, "$label: nonfinite output")
                assertTrue(s.peak() in .01f..1f, "$label: output peak ${s.peak()}")
                val dc = abs(s.samples.average())
                largestDc = maxOf(largestDc, dc)
                assertTrue(dc < .005, "$label: DC $dc")
                val loudness = Loudness.of(s)
                lowestLoudness = minOf(lowestLoudness, loudness)
                assertTrue(loudness >= .9f * Dsp.MELODIC_LOUDNESS_TARGET || s.peak() >= .95f,
                    "$label: shared level stage landed at $loudness, peak ${s.peak()}")
                assertEquals(SkirlDynamics.Exit.TERMINAL, p.motion.exit, label)
                assertEquals(SkirlDynamics.Reason.TERMINAL_THRESHOLD, p.motion.reason, label)
                assertTrue(p.acoustics.restStartSeconds > p.motion.duration)
                assertTrue(s.durationSeconds > p.acoustics.restStartSeconds + 1.9,
                    "$label: render truncates the resonant tail")
                assertTrue(s.durationSeconds < 16f, "$label: unbounded finite render ${s.durationSeconds}")
                checkRoot(s, midi, .25, "$label rolling")
                if (voice == SkirlVoice.WHIRR && midi <= 48) {
                    // Coherent quarter-contact forcing previously replaced
                    // these roots after their first freely ringing transient.
                    checkRoot(s, midi, .75, "$label later rolling")
                    checkRoot(s, midi, p.motion.duration * .5, "$label middle rolling")
                }
                checkRoot(s, midi, p.acoustics.restStartSeconds + .10, "$label tail")
                assertEquals(DrumClass.TONAL, Skirl.drumClassFor(voice), label)
                val classified = Classifier.classify(s).drumClass
                assertTrue(classified !in drumClasses,
                    "$label: actual dry render filed as $classified")
                // Duration's generic LOOP bucket describes the rendered file;
                // the saved finite pitched intent remains TONAL.
                println("SKIRL OUTPUT $label: ${s.durationSeconds} s, peak ${s.peak()}, DC $dc, loudness $loudness, classifier $classified")
            }
        }
        println("SKIRL native C2-C6 matrix: worst root $worstPitch cents, min loudness $lowestLoudness, max DC $largestDc")
        assertTrue(pitchFailures.isEmpty(), pitchFailures.joinToString("\n"))
    }

    @Test
    fun `low and high registers retain their root at every edge and plate corner`() {
        val failures = ArrayList<String>()
        var worstPitch = 0.0
        for (voice in SkirlVoice.entries) for (midi in listOf(36, 72, 84)) {
            for (edge in listOf(0f, 1f)) for (plate in listOf(0f, 1f)) {
                val p = Skirl.inspect(voice, mapOf("EDGE" to edge, "PLATE" to plate), midi = midi)
                val label = "$voice MIDI $midi EDGE $edge PLATE $plate"
                assertTrue(p.snip.samples.all { it.isFinite() }, "$label: nonfinite output")
                assertTrue(p.snip.peak() in .01f..1f, "$label: output peak ${p.snip.peak()}")
                assertTrue(abs(p.snip.samples.average()) < .005, "$label: output DC")
                val loudness = Loudness.of(p.snip)
                assertTrue(loudness >= .9f * Dsp.MELODIC_LOUDNESS_TARGET || p.snip.peak() >= .95f,
                    "$label: shared level stage landed at $loudness")
                for ((part, from) in listOf("rolling" to .25, "tail" to p.acoustics.restStartSeconds + .10)) {
                    try {
                        worstPitch = maxOf(worstPitch, assertRoot(p.snip, midi, from, "$label $part"))
                    } catch (failure: AssertionError) {
                        val message = failure.message ?: "$label $part"
                        failures += message
                        println("SKIRL CORNER ROOT FAILURE $message")
                    }
                }
            }
        }
        println("SKIRL material/register corners: worst accepted root $worstPitch cents")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `all six ordinary macros alter the complete gain-matched gesture`() {
        for (voice in SkirlVoice.entries) for (macro in normalMacros) {
            val a = Skirl.inspect(voice, mapOf(macro to .25f))
            val b = Skirl.inspect(voice, mapOf(macro to .75f))
            val delta = distance(a.snip.samples, b.snip.samples)
            assertTrue(a.snip.durationSeconds > a.acoustics.restStartSeconds)
            assertTrue(b.snip.durationSeconds > b.acoustics.restStartSeconds)
            println("SKIRL MACRO $voice $macro .25/.75: complete gain-matched distance $delta")
            assertTrue(delta > .03, "$voice $macro changes only gain or is inactive: $delta")
        }
    }

    @Test
    fun `launch and resistance interact and resistance changes how much happens before collapse`() {
        for (voice in SkirlVoice.entries) for ((first, second) in
            listOf("LEAN" to "BRAKE", "BRAKE" to "COLLAPSE")) {
            val durations = listOf(.15f to .15f, .15f to .85f, .85f to .15f, .85f to .85f).map { (a, b) ->
                val trace = SkirlDynamics.trace(Skirl.parametersFor(voice, mapOf(first to a, second to b)))
                assertEquals(SkirlDynamics.Exit.TERMINAL, trace.exit)
                trace.duration
            }
            val interaction = abs(durations[3] - durations[2] - durations[1] + durations[0])
            println("SKIRL MECHANICAL PAIR $voice $first x $second: durations $durations, interaction $interaction s")
            assertTrue(interaction > .002, "$voice $first x $second did not interact: $interaction seconds")
        }
    }

    @Test
    fun `edge detail interacts with plate material and with the terminal contact`() {
        for (voice in SkirlVoice.entries) for ((first, second) in
            listOf("EDGE" to "PLATE", "EDGE" to "COLLAPSE")) {
            val corners = listOf(.15f to .15f, .15f to .85f, .85f to .15f, .85f to .85f).map { (a, b) ->
                Skirl.render(voice, mapOf(first to a, second to b)).samples
            }
            val delta = interaction(corners)
            println("SKIRL ACOUSTIC PAIR $voice $first x $second: gain-matched interaction $delta")
            assertTrue(delta > .03, "$voice $first x $second has no measurable interaction: $delta")
        }
    }

    @Test
    fun `rest stops contact input and leaves a pitched freely decaying body`() {
        for (voice in SkirlVoice.entries) {
            val p = Skirl.inspect(voice)
            val d = p.acoustics
            assertEquals(0f, d.restExcitationPeak, "$voice: contact input remains after REST")
            assertTrue(d.terminalEvents.isNotEmpty())
            assertTrue(d.assignedTerminalEnergy > 0.0)
            assertTrue(d.assignedTerminalEnergy <= .05 * d.assignedRollingEnergy + 1e-10)
            val rest = (d.restStartSeconds * Skirl.RATE).toInt()
            val early = rms(p.raw, rest + Skirl.RATE / 20, rest + Skirl.RATE / 5)
            val late = rms(p.raw, p.raw.size - Skirl.RATE / 4, p.raw.size - Skirl.RATE / 20)
            assertTrue(early > 1e-4, "$voice: no resonant tail remains after contact stops ($early)")
            assertTrue(late < early * .1, "$voice: unforced tail fails to decay, $early -> $late")
            assertRoot(p.snip, Skirl.DEFAULT_MIDI, d.restStartSeconds + .1, "$voice REST tail")
            println("SKIRL REST $voice: input ${d.restExcitationPeak}, raw early tail $early, late $late")
        }
    }

    @Test
    fun `note material edge and touch reuse one mechanical history while velocity changes contact`() {
        for (voice in SkirlVoice.entries) {
            val firm = Skirl.inspect(voice)
            val moved = Skirl.inspect(voice, mapOf("TUNE" to .75f, "EDGE" to .9f, "PLATE" to .8f),
                midi = 60, velocity = .25f)
            assertSame(firm.motion, moved.motion, "$voice: acoustic edits rebuilt mechanics")
            val soft = Skirl.inspect(voice, velocity = .25f)
            assertSame(firm.motion, soft.motion)
            assertTrue(soft.acoustics.assignedRollingEnergy < firm.acoustics.assignedRollingEnergy * .1,
                "$voice: a soft touch does not reduce available acoustic input")
            val delta = distance(firm.snip.samples, soft.snip.samples)
            assertTrue(delta > .01, "$voice: velocity changes only final gain ($delta)")
            val zero = Skirl.inspect(voice, velocity = 0f)
            assertSame(firm.motion, zero.motion)
            assertTrue(zero.snip.samples.all { it == 0f }, "$voice: zero touch creates sound")
            assertEquals(0.0, zero.acoustics.assignedRollingEnergy)
            assertEquals(0.0, zero.acoustics.assignedTerminalEnergy)
            println("SKIRL TOUCH $voice: soft/firm assigned work ${soft.acoustics.assignedRollingEnergy}/${firm.acoustics.assignedRollingEnergy}, gain-matched distance $delta")
        }
    }

    @Test
    fun `direct render ignores unknown keys defaults nonfinite values and clamps finite controls`() {
        for (voice in SkirlVoice.entries) {
            val normal = Skirl.render(voice).samples
            assertContentEquals(normal, Skirl.render(voice, mapOf("UNKNOWN" to .8f)).samples)
            val nonfinite = Skirl.macrosFor(voice).mapIndexed { i, spec ->
                spec.name to listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)[i % 3]
            }.toMap()
            assertContentEquals(normal, Skirl.render(voice, nonfinite).samples)
            val malformed = mapOf("TUNE" to -2f, "LEAN" to -1f, "BRAKE" to 2f,
                "EDGE" to 2f, "PLATE" to -1f, "COLLAPSE" to 2f, "HOLD" to -1f)
            val bounded = malformed.mapValues { (_, value) -> value.coerceIn(0f, 1f) }
            assertContentEquals(Skirl.render(voice, bounded).samples, Skirl.render(voice, malformed).samples)
            for (velocity in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
                assertFailsWith<IllegalArgumentException> { Skirl.render(voice, velocity = velocity) }
            }
            for (midi in listOf(Skirl.MIDI_MIN - 1, Skirl.MIDI_MAX + 1)) {
                assertFailsWith<IllegalArgumentException> { Skirl.render(voice, midi = midi) }
            }
        }
    }

    @Test
    fun `ordinary scramble remains finite deterministic and explicitly one shot`() {
        assertEquals(listOf("ROLL", "WHIRR"), SkirlVoice.entries.map { it.name })
        for (voice in SkirlVoice.entries) {
            val specs = Skirl.macrosFor(voice)
            assertEquals(normalMacros + "HOLD", specs.map { it.name })
            assertTrue(specs.all { it.default.isFinite() && it.default in 0f..1f })
            for (seed in 0..20) {
                val a = Skirl.scramble(voice, Random(seed), temperature = .8f)
                assertEquals(a, Skirl.scramble(voice, Random(seed), temperature = .8f))
                assertEquals(specs.map { it.name }.toSet(), a.keys)
                assertTrue(a.values.all { it.isFinite() && it in 0f..1f })
                assertEquals(0f, a.getValue("HOLD"))
            }
            for (hold in listOf(.001f, .5f, .99f, 1f)) {
                assertFailsWith<IllegalArgumentException>("$voice HOLD $hold") {
                    Skirl.render(voice, mapOf("HOLD" to hold))
                }
                assertFailsWith<IllegalArgumentException> {
                    Skirl.drumClassFor(voice, mapOf("HOLD" to hold))
                }
            }
            assertEquals(48, Skirl.midiFor(voice, .5f))
            assertEquals(36, Skirl.midiFor(voice, 0f))
            assertEquals(60, Skirl.midiFor(voice, 1f))
        }
    }

    @Test
    fun `an interrupted render stops before replacing the current gesture`() {
        for (voice in SkirlVoice.entries) {
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<CancellationException> { Skirl.render(voice) }
            } finally {
                Thread.interrupted()
            }
        }
        assertTrue(Skirl.render(SkirlVoice.ROLL).samples.any { it != 0f })
    }
}
