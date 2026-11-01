package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtisOrganPrototypeV4Test {
    private val rate = Dsp.RATE * Dsp.OVERSAMPLE

    private fun isolated(bar: Int) = OtisOrganSettings(
        drawbars = List(9) { if (it == bar) 1f else 0f },
        click = 0f, percussionLevel = 0f, leakage = 0f, drive = 0f, cabinetHz = 20_000.0,
    )

    @Test
    fun `a short key gate releases its unfinished attack instead of swelling after key up`() {
        val notes = listOf(OtisPrototypeNote(midi = 69, gateSeconds = .04))
        val settings = isolated(2).copy(attackSeconds = 2.0, releaseSeconds = 3.0)
        val fixed = OtisOrganPrototypeV4.synthesize(notes, settings, 611, rate)
        val legacy = OtisOrganPrototype.synthesize(notes, settings, 611, rate)
        val atKeyUp = rms(fixed, .03, .04, rate)
        val afterRelease = rms(fixed, .95, 1.05, rate)
        val legacyAfterRelease = rms(legacy, .95, 1.05, rate)
        assertTrue(atKeyUp > .001, "the short key gate lost its initial tone")
        assertTrue(afterRelease < atKeyUp * 1.15,
            "attack continued rising after key-up: $atKeyUp -> $afterRelease")
        assertTrue(afterRelease < legacyAfterRelease * .08,
            "the original post-release swell remains: $legacyAfterRelease -> $afterRelease")
        assertTrue(rms(fixed, 2.4, 2.5, rate) < afterRelease * .25, "release did not keep decaying")
        assertTrue(abs(fixed.last()) < 1e-8, "the released note's cabinet did not settle")
        println("OTIS ORGAN4 release: keyUpRms=$atKeyUp lateRms=$afterRelease legacyLateRms=$legacyAfterRelease")
    }

    @Test
    fun `every independent stop retains its own frequency through the optimized loop`() {
        val ratios = listOf(.5, 1.5, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0)
        for ((bar, ratio) in ratios.withIndex()) {
            val raw = OtisOrganPrototypeV4.synthesize(
                listOf(OtisPrototypeNote(midi = 69, gateSeconds = .55)), isolated(bar), 612, rate,
            )
            val out = Dsp.decimate(raw, Dsp.RATE)
            val target = amplitude(out, 440.0 * ratio, .1, .5, Dsp.RATE)
            val nearby = amplitude(out, 440.0 * ratio + 5.0, .1, .5, Dsp.RATE)
            assertTrue(target > .08 && target > nearby * 100,
                "drawbar $bar is missing or moved: target=$target nearby=$nearby")
        }
        val pruned = OtisOrganPrototypeV4.synthesize(
            listOf(OtisPrototypeNote(midi = 84, gateSeconds = .2)), isolated(8), 612, 8000,
        )
        assertTrue(pruned.all { it == 0f }, "a pruned stop entered the recurrence bank")
    }

    @Test
    fun `low stop recurrence keeps pitch and amplitude over a long held note`() {
        val notes = listOf(OtisPrototypeNote(midi = 36, gateSeconds = 8.0))
        val settings = isolated(0)
        val fixed = Dsp.decimate(OtisOrganPrototypeV4.synthesize(notes, settings, 613, rate), Dsp.RATE)
        val legacy = Dsp.decimate(OtisOrganPrototype.synthesize(notes, settings, 613, rate), Dsp.RATE)
        val target = 440.0 * 2.0.pow((36 - 69) / 12.0) * .5
        val pitch = crossingFrequency(fixed, 1.0, 7.0, Dsp.RATE)
        val cents = 1200.0 * ln(pitch / target) / ln(2.0)
        val amplitudeRatio = rms(fixed, 1.0, 7.0, Dsp.RATE) / rms(legacy, 1.0, 7.0, Dsp.RATE)
        assertTrue(abs(cents) < .1, "low stop drifted $cents cents over its hold")
        assertTrue(abs(amplitudeRatio - 1.0) < 1e-5, "low stop recurrence changed amplitude: $amplitudeRatio")
        assertClose(legacy, fixed, "eight-second low stop")
        println("OTIS ORGAN4 low stop: pitchHz=$pitch cents=$cents amplitudeRatio=$amplitudeRatio")
    }

    @Test
    fun `ordinary dry driven and rotating phrases keep the legacy spectrum and articulation`() {
        val chord = listOf(60, 63, 67, 70).map { OtisPrototypeNote(it, gateSeconds = .45, velocity = .68f) }
        val phrase = listOf(72, 75, 79).mapIndexed { i, midi ->
            OtisPrototypeNote(midi, startSeconds = i * .2, gateSeconds = .3)
        }
        val probes = listOf(
            Triple("dry C3", listOf(OtisPrototypeNote(midi = 48, gateSeconds = .45)), OtisOrganSettings()),
            Triple("driven Cm7", chord, OtisOrganSettings(drawbars = List(9) { 1f }, leakage = 1f, drive = .75f)),
            Triple("rotating phrase", phrase, OtisOrganSettings(rotary = true, rotaryChangeSeconds = .1)),
        )
        for ((name, notes, settings) in probes) {
            val legacy = OtisOrganPrototype.synthesize(notes, settings, 614, rate)
            val fixed = OtisOrganPrototypeV4.synthesize(notes, settings, 614, rate)
            assertClose(legacy, fixed, name)
            assertContentEquals(fixed, OtisOrganPrototypeV4.synthesize(notes, settings, 614, rate), "$name does not replay")
        }
    }

    @Test
    fun `percussion still waits for every held key and ignores muted notes`() {
        val settings = isolated(0).copy(drawbars = List(9) { 0f }, percussionLevel = .8f)
        val first = OtisPrototypeNote(midi = 60, gateSeconds = .12)
        val notes = listOf(
            first,
            OtisPrototypeNote(midi = 63, startSeconds = .06, gateSeconds = .3),
            OtisPrototypeNote(midi = 67, startSeconds = .2, gateSeconds = .1),
            OtisPrototypeNote(midi = 70, startSeconds = .4, gateSeconds = .5, velocity = 0f),
            OtisPrototypeNote(midi = 60, startSeconds = .5, gateSeconds = .1),
        )
        val reference = OtisOrganPrototypeV4.synthesize(listOf(first), settings, 615, rate)
        val actual = OtisOrganPrototypeV4.synthesize(notes, settings, 615, rate)
        for (i in 0 until (rate * .25).toInt()) assertEquals(reference[i], actual[i], "legato note triggered percussion at frame $i")
        assertTrue(rms(actual, .4, .45, rate) < 1e-8, "silent held keys made sound")
        assertTrue(rms(actual, .51, .55, rate) > .02, "a muted key blocked percussion rearming")
    }

    @Test
    fun `simultaneous chord percussion intentionally retains its first listed note policy`() {
        val settings = isolated(0).copy(drawbars = List(9) { 0f }, percussionLevel = .8f)
        for (midis in listOf(listOf(60, 63, 67, 70), listOf(70, 67, 63, 60))) {
            val notes = midis.map { OtisPrototypeNote(it, gateSeconds = .3) }
            val chord = OtisOrganPrototypeV4.synthesize(notes, settings, 616, rate)
            val firstOnly = OtisOrganPrototypeV4.synthesize(listOf(notes.first()), settings, 616, rate)
            assertContentEquals(firstOnly, chord, "V4 changed the disclosed first-listed chord percussion policy")
        }
    }

    @Test
    fun `chords share one amp rather than adding separately saturated voices`() {
        val notes = listOf(60, 63, 67).map { OtisPrototypeNote(it, gateSeconds = .35) }
        fun separated(settings: OtisOrganSettings): FloatArray {
            val solos = notes.indices.map { active ->
                // Keep event indices/seeds while muting the other chord notes.
                val score = notes.mapIndexed { i, note -> if (i == active) note else note.copy(velocity = 0f) }
                OtisOrganPrototypeV4.synthesize(score, settings, 617, rate)
            }
            return FloatArray(solos[0].size) { frame -> solos.sumOf { it[frame].toDouble() }.toFloat() }
        }
        val linear = OtisOrganSettings(click = 0f, percussionLevel = 0f, leakage = 0f, drive = 0f)
        val linearChord = OtisOrganPrototypeV4.synthesize(notes, linear, 617, rate)
        assertClose(separated(linear), linearChord, "linear chord mix")
        val driven = linear.copy(drive = .6f)
        val drivenChord = OtisOrganPrototypeV4.synthesize(notes, driven, 617, rate)
        val error = relativeError(drivenChord, separated(driven))
        assertTrue(error > .04, "chord amp behaved as independent per-note saturation: $error")
    }

    @Test
    fun `the shared rotor advances on the score clock before a delayed note starts`() {
        val note = OtisPrototypeNote(midi = 69, gateSeconds = .4)
        val delayed = note.copy(startSeconds = .5)
        val dry = isolated(2)
        fun difference(settings: OtisOrganSettings): Double {
            val early = OtisOrganPrototypeV4.synthesize(listOf(note), settings, 618, rate)
            val late = OtisOrganPrototypeV4.synthesize(listOf(delayed), settings, 618, rate)
            val from = (rate * .05).toInt()
            val to = (rate * .35).toInt()
            val offset = (rate * .5).toInt()
            var delta = 0.0
            var power = 0.0
            for (i in from until to) {
                val d = early[i] - late[i + offset]
                delta += d * d
                power += early[i] * early[i]
            }
            return sqrt(delta / power)
        }
        assertTrue(difference(dry) < 2e-6, "the dry note changed merely by moving later in the score")
        val rotor = dry.copy(rotary = true, rotaryDepth = .9f, rotaryChangeSeconds = .1)
        assertTrue(difference(rotor) > .02, "a delayed note restarted the rotor at its own onset")
    }

    private fun assertClose(expected: FloatArray, actual: FloatArray, label: String) {
        assertEquals(expected.size, actual.size, "$label length changed")
        assertTrue(actual.all { it.isFinite() }, "$label produced nonfinite audio")
        val maximum = expected.indices.maxOf { abs(expected[it] - actual[it]) }
        val relative = relativeError(expected, actual)
        assertTrue(maximum < 2e-6 && relative < 2e-6,
            "$label changed beyond recurrence tolerance: max=$maximum relativeRms=$relative")
        println("OTIS ORGAN4 null $label: max=$maximum relativeRms=$relative")
    }

    private fun relativeError(expected: FloatArray, actual: FloatArray): Double {
        var difference = 0.0
        var power = 0.0
        for (i in expected.indices) {
            val d = expected[i].toDouble() - actual[i]
            difference += d * d
            power += expected[i].toDouble() * expected[i]
        }
        return sqrt(difference / power.coerceAtLeast(1e-30))
    }

    private fun rms(samples: FloatArray, start: Double, end: Double, sampleRate: Int): Double {
        val from = (start * sampleRate).toInt()
        val to = (end * sampleRate).toInt()
        return sqrt((from until to).sumOf { samples[it].toDouble() * samples[it] } / (to - from))
    }

    private fun amplitude(samples: FloatArray, hz: Double, start: Double, end: Double, sampleRate: Int): Double {
        val from = (start * sampleRate).toInt()
        val to = (end * sampleRate).toInt()
        var real = 0.0
        var imaginary = 0.0
        for (i in from until to) {
            val angle = 2.0 * PI * hz * i / sampleRate
            real += samples[i] * cos(angle)
            imaginary += samples[i] * sin(angle)
        }
        return 2.0 * sqrt(real * real + imaginary * imaginary) / (to - from)
    }

    private fun crossingFrequency(samples: FloatArray, start: Double, end: Double, sampleRate: Int): Double {
        var first = 0.0
        var last = 0.0
        var count = 0
        for (i in (start * sampleRate).toInt() until (end * sampleRate).toInt()) {
            if (samples[i - 1] < 0f && samples[i] >= 0f) {
                val fraction = -samples[i - 1].toDouble() / (samples[i] - samples[i - 1])
                val time = (i - 1 + fraction) / sampleRate
                if (count++ == 0) first = time
                last = time
            }
        }
        assertTrue(count > 100, "not enough cycles to measure the low stop")
        return (count - 1) / (last - first)
    }
}
