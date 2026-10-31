package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** The finite dry sources are tested before any audition gain, rack effect or decimation. */
class OtisAirPrototypeTest {
    private val rate = 44_100
    private val voices = listOf(OtisPrototypeVoice.STEAM, OtisPrototypeVoice.HARP, OtisPrototypeVoice.SQUEEZE)
    private val plain = OtisAirSettings(steamInstability = 0f, harpBend = 0f, breath = 0f,
        harpVibrato = 0f, musette = 0f, bellowsPulse = 0f)

    private fun render(voice: OtisPrototypeVoice, velocity: Float = .8f,
        settings: OtisAirSettings = OtisAirSettings(), midi: Int = 60, gate: Double = 1.5,
        start: Double = 0.0, seed: Int = 871) = OtisAirPrototype.synthesize(voice,
        listOf(OtisPrototypeNote(midi, start, gate, velocity)), settings, seed, rate)

    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val first = (from * rate).toInt().coerceIn(0, x.size)
        val end = (to * rate).toInt().coerceIn(first, x.size)
        return sqrt((first until end).sumOf { x[it].toDouble() * x[it] } / maxOf(1, end - first))
    }

    /** A windowed projection measures emitted partial energy, independent of carrier phase. */
    private fun power(x: FloatArray, hz: Double, from: Double, to: Double): Double {
        val first = (from * rate).toInt()
        val count = ((to - from) * rate).toInt()
        var real = 0.0
        var imaginary = 0.0
        for (k in 0 until count) {
            val value = x[first + k] * (.5 - .5 * cos(2 * PI * k / (count - 1)))
            val phase = 2 * PI * hz * k / rate
            real += value * cos(phase)
            imaginary += value * sin(phase)
        }
        return real * real + imaginary * imaginary
    }

    private fun measuredCents(x: FloatArray, root: Double, from: Double, to: Double,
        lower: Int = -200, upper: Int = 40): Double = (lower..upper step 2).maxBy {
            power(x, root * 2.0.pow(it / 1200.0), from, to)
        }.toDouble()

    private fun oldRender(voice: OtisPrototypeVoice, settings: OtisAirSettings = OtisAirSettings(),
        midi: Int = 60, gate: Double = 1.5) = OtisAirPrototypeV1.synthesize(voice,
        listOf(OtisPrototypeNote(midi, 0.0, gate, .8f)), settings, 871, rate)

    private fun matched(x: FloatArray): FloatArray {
        val gain = rms(x, .3, .8).coerceAtLeast(1e-12)
        return FloatArray(x.size) { (x[it] / gain).toFloat() }
    }

    private fun highShare(x: FloatArray, root: Double): Double =
        (2..8).sumOf { power(x, root * it, .3, .8) } / power(x, root, .3, .8)

    @Test
    fun `zero velocity is silent and seeded sources reproduce independently of score order`() {
        for (voice in voices) {
            assertTrue(render(voice, velocity = 0f).all { it == 0f }, "$voice zero velocity supplies work")
            val original = render(voice)
            render(voice, midi = 65, seed = 12)
            assertContentEquals(original, render(voice), "$voice source is not reproducible")
            val a = OtisPrototypeNote(60, .2, .6, .7f)
            val b = OtisPrototypeNote(67, .5, .4, .6f)
            val first = OtisAirPrototype.synthesize(voice, listOf(a, b), OtisAirSettings(), 71, rate)
            val reversed = OtisAirPrototype.synthesize(voice, listOf(b, a), OtisAirSettings(), 71, rate)
            assertContentEquals(first, reversed, "$voice assigning pipes depends on list order")
        }
    }

    @Test
    fun `harp scoops into the requested root rather than transposing its sustained body`() {
        val root = 440 * 2.0.pow((72 - 69) / 12.0)
        val x = render(OtisPrototypeVoice.HARP, .9f,
            plain.copy(harpBend = 1f), midi = 72, gate = .7)
        val entry = measuredCents(x, root, .015, .055)
        val body = measuredCents(x, root, .30, .50)
        assertTrue(entry < -25, "harp has no audible bent entrance: $entry cents")
        assertTrue(abs(body) <= 4, "harp did not reach the saved note: $body cents")
    }

    @Test
    fun `hard breath changes reed spectrum after RMS matching`() {
        val root = 440 * 2.0.pow((60 - 69) / 12.0)
        val soft = render(OtisPrototypeVoice.HARP, .35f, plain)
        val hard = render(OtisPrototypeVoice.HARP, .90f, plain)
        val a = highShare(matched(soft), root)
        val b = highShare(matched(hard), root)
        assertTrue(b > a * 1.7, "RMS-matched hard breath has the soft spectrum: $a -> $b")
    }

    @Test
    fun `musette creates sustained beating that the unison reed does not have`() {
        val unison = render(OtisPrototypeVoice.SQUEEZE, settings = plain, gate = 2.0)
        val pair = render(OtisPrototypeVoice.SQUEEZE, settings = plain.copy(musette = .8f), gate = 2.0)
        fun variation(x: FloatArray): Double {
            val levels = (0 until 75).map { rms(x, .25 + it * .02, .27 + it * .02) }
            val mean = levels.average()
            return sqrt(levels.sumOf { (it - mean).pow(2) } / levels.size) / mean
        }
        val a = variation(unison)
        val b = variation(pair)
        assertTrue(a < .02 && b > .20, "twin reeds do not beat beyond their unison control: $a -> $b")
    }

    @Test
    fun `steam pressure makes the emitted pitch wander over time around the saved note`() {
        val root = 440 * 2.0.pow((72 - 69) / 12.0)
        val steady = render(OtisPrototypeVoice.STEAM, settings = plain, midi = 72)
        val pipe = render(OtisPrototypeVoice.STEAM, settings = plain.copy(steamInstability = 1f), midi = 72)
        val times = listOf(.22, .37, .59, .86, 1.13, 1.37)
        val stablePitch = times.map { measuredCents(steady, root, it, it + .08, -70, 70) }
        val wanderingPitch = times.map { measuredCents(pipe, root, it, it + .08, -70, 70) }
        assertTrue(stablePitch.max() - stablePitch.min() <= 2, "zero-instability steam wanders: $stablePitch")
        assertTrue(wanderingPitch.max() - wanderingPitch.min() >= 25,
            "steam has no temporal pressure motion: $wanderingPitch")
        // Model 2 deliberately exceeds the old 24-cent ceiling: the first listening round
        // requested a characterful pressure drift. The source clamps excursions to 60 cents;
        // the emitted pitch must stay inside that bound, with a wider search that cannot hide it.
        assertTrue(wanderingPitch.all { abs(it) <= 60 }, "steam exceeds its bounded pressure range: $wanderingPitch")
    }

    @Test
    fun `revised steam has larger emitted drift and a stronger valve chuff at equal body level`() {
        val root = 440 * 2.0.pow((72 - 69) / 12.0)
        val original = oldRender(OtisPrototypeVoice.STEAM, midi = 72)
        val revised = render(OtisPrototypeVoice.STEAM, midi = 72)
        val times = listOf(.16, .29, .41, .56, .73, .89, 1.06, 1.23, 1.39)
        fun range(x: FloatArray): Double {
            val pitch = times.map { measuredCents(x, root, it, it + .04, -70, 70) }
            return pitch.max() - pitch.min()
        }
        val a = range(original)
        val b = range(revised)
        assertTrue(b >= 25 && b > 1.8 * a, "steam pressure drift did not grow: $a -> $b cents")
        val noisy = plain.copy(breath = .35f)
        fun chuff(old: Boolean): Double {
            val dry = if (old) oldRender(OtisPrototypeVoice.STEAM, plain) else render(OtisPrototypeVoice.STEAM, settings = plain)
            val air = if (old) oldRender(OtisPrototypeVoice.STEAM, noisy) else render(OtisPrototypeVoice.STEAM, settings = noisy)
            val residual = FloatArray(air.size) { air[it] - dry[it] }
            return rms(residual, .005, .10) / rms(dry, .3, .8)
        }
        val oldChuff = chuff(true)
        val newChuff = chuff(false)
        assertTrue(newChuff > oldChuff * 2, "matched steam has no stronger valve chuff: $oldChuff -> $newChuff")
    }

    @Test
    fun `revised harp gives a deeper lingering entrance and more strained matched harmonics`() {
        val root = 440 * 2.0.pow((72 - 69) / 12.0)
        val bent = plain.copy(harpBend = .65f)
        val original = oldRender(OtisPrototypeVoice.HARP, bent, midi = 72)
        val revised = render(OtisPrototypeVoice.HARP, settings = bent, midi = 72)
        val a = measuredCents(original, root, .025, .075, -240, 40)
        val b = measuredCents(revised, root, .025, .075, -240, 40)
        assertTrue(b < a - 25, "harp entrance did not become deeper and slower: $a -> $b cents")
        assertTrue(abs(measuredCents(revised, root, .40, .60)) <= 4, "revised harp does not settle to its note")
        val oldSpectrum = highShare(matched(oldRender(OtisPrototypeVoice.HARP, plain)), root / 2)
        val newSpectrum = highShare(matched(render(OtisPrototypeVoice.HARP, settings = plain)), root / 2)
        assertTrue(newSpectrum > oldSpectrum * 1.4,
            "revised harp lacks stronger strain after matching: $oldSpectrum -> $newSpectrum")
    }

    @Test
    fun `unequal musette ranks retain a body through their beats instead of periodically nulling`() {
        val settings = plain.copy(musette = .8f)
        val old = oldRender(OtisPrototypeVoice.SQUEEZE, settings, gate = 2.0)
        val revised = render(OtisPrototypeVoice.SQUEEZE, settings = settings, gate = 2.0)
        fun floor(x: FloatArray): Double {
            val levels = (0 until 75).map { rms(x, .25 + it * .02, .27 + it * .02) }
            return levels.min() / levels.average()
        }
        val a = floor(old)
        val b = floor(revised)
        assertTrue(b > .25 && b > a * 2, "musette still collapses to periodic silence: $a -> $b")
    }

    @Test
    fun `pipe single reed and twin free reeds have different dry harmonic signatures`() {
        val root = 440 * 2.0.pow((60 - 69) / 12.0)
        fun ratio(voice: OtisPrototypeVoice, harmonic: Int): Double {
            val x = render(voice, settings = plain, gate = .7)
            return power(x, harmonic * root, .2, .6) / power(x, root, .2, .6)
        }
        // Model 3 intentionally fills STEAM's sustained pipe body, replacing the old
        // near-sine ceiling. It still leads with its root and stays clearer than the harp.
        val steamSecond = ratio(OtisPrototypeVoice.STEAM, 2)
        val harpSecond = ratio(OtisPrototypeVoice.HARP, 2)
        assertTrue(steamSecond in .08.. .22, "steam lost its fundamental-led pipe spectrum: $steamSecond")
        assertTrue(harpSecond > steamSecond * 2, "pipe and strained reed lost their separation: $steamSecond -> $harpSecond")
        assertTrue(ratio(OtisPrototypeVoice.SQUEEZE, 2) < .001, "free reeds acquired an even-rich harp spectrum")
        assertTrue(ratio(OtisPrototypeVoice.SQUEEZE, 3) > .06, "free reeds lost their persistent odd spectrum")
    }

    @Test
    fun `steam gains coherent sustained body after RMS matching against model two`() {
        val voice = OtisPrototypeVoice.STEAM
        val notes = listOf(OtisPrototypeNote(60, 0.0, 1.5, .8f))
        val old = matched(OtisAirPrototypeV2.synthesize(voice, notes, plain, 871, rate))
        val revised = matched(render(voice, settings = plain))
        val root = 440 * 2.0.pow((60 - 69) / 12.0)
        val a = highShare(old, root)
        val b = highShare(revised, root)
        assertTrue(abs(rms(old, .3, .8) - rms(revised, .3, .8)) < 1e-6,
            "steam body comparison is not level matched")
        assertTrue(b > a * 4 && b > .20,
            "steam power is only gain rather than sustained harmonics: $a -> $b")
        assertTrue(power(revised, root, .3, .8) > (2..5).sumOf { power(revised, root * it, .3, .8) },
            "stronger steam lost its root-bearing pipe")
    }

    @Test
    fun `pressure and velocity open steam harmonics without clipping the finite source`() {
        val voice = OtisPrototypeVoice.STEAM
        val root = 440 * 2.0.pow((60 - 69) / 12.0)
        val lowPressure = highShare(matched(render(voice, settings = plain.copy(pressure = 0f))), root)
        val highPressure = highShare(matched(render(voice, settings = plain.copy(pressure = 1f))), root)
        val soft = highShare(matched(render(voice, velocity = .35f, settings = plain)), root)
        val hard = highShare(matched(render(voice, velocity = .90f, settings = plain)), root)
        assertTrue(highPressure > lowPressure * 1.7,
            "pressure does not feed the matched pipe body: $lowPressure -> $highPressure")
        assertTrue(hard > soft * 1.5, "hard steam is only louder: $soft -> $hard")
        for (midi in listOf(36, 60, 84)) {
            val raw = render(voice, 1f, OtisAirSettings(pressure = 1f, breath = 1f, steamInstability = 1f), midi)
            assertTrue(raw.all { it.isFinite() && abs(it) < 1f }, "steam MIDI $midi requires clipping at full pressure")
            assertTrue(raw.takeLast(256).all { abs(it) < 1e-6 }, "steam MIDI $midi lost its natural tail")
        }
    }

    @Test
    fun `steam revision preserves exact model two harp and squeeze audio`() {
        val notes = listOf(OtisPrototypeNote(60, 0.0, .4, .35f), OtisPrototypeNote(72, .2, .4, .9f))
        val boundary = OtisAirSettings(pressure = 1f, breath = 1f, harpBend = 1f,
            harpVibrato = 1f, musette = 1f, bellowsPulse = 1f, release = 1f)
        for (voice in listOf(OtisPrototypeVoice.HARP, OtisPrototypeVoice.SQUEEZE))
            for (settings in listOf(OtisAirSettings(), boundary)) {
                val old = OtisAirPrototypeV2.synthesize(voice, notes, settings, 415, rate)
                val current = OtisAirPrototype.synthesize(voice, notes, settings, 415, rate)
                assertContentEquals(old, current, "$voice changed during the steam power revision")
            }
    }

    @Test
    fun `finite gates leave quiet natural tails and high notes stay bounded`() {
        for (voice in voices) for (midi in listOf(36, 60, 84)) {
            val x = render(voice, velocity = 1f, midi = midi, gate = .12, start = .04)
            assertTrue(x.all { it.isFinite() && abs(it) < 1.5f }, "$voice MIDI $midi has unbounded samples")
            assertTrue(x.take((.04 * rate).toInt()).all { it == 0f }, "$voice sounds before its score onset")
            assertTrue(rms(x, .06, .14) > 1e-3, "$voice MIDI $midi is silent")
            assertTrue(x.takeLast(256).all { abs(it) < 1e-6f }, "$voice MIDI $midi tail was audibly cut")
        }
    }
}
