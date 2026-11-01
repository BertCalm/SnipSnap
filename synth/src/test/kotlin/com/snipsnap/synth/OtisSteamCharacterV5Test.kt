package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OtisSteamCharacterV5Test {
    private val rate = Dsp.RATE * Dsp.OVERSAMPLE
    private val plain = OtisAirSettings(steamInstability = 0f, breath = 0f)
    private val note = listOf(OtisPrototypeNote(midi = 60, gateSeconds = 1.2))

    private fun constantPressure(value: Double, sampleRate: Int = rate): OtisPhrasePressureTrace =
        OtisPhrasePressureTrace(doubleArrayOf(value, value), DoubleArray(2), doubleArrayOf(value, value),
            stride = sampleRate * 30, rate = sampleRate)

    private fun source(
        notes: List<OtisPrototypeNote> = note,
        settings: OtisAirSettings = plain,
        trace: OtisPhrasePressureTrace = constantPressure(1.0),
        sampleRate: Int = rate,
        seed: Int = 20261014,
    ) = OtisSteamCharacterV5.synthesize(notes, settings, seed, sampleRate, trace)

    private fun native(raw: FloatArray) = OtisPrototypeV4.condition(raw).samples

    @Test
    fun `power comes from the held harmonic body after level matching with noise disabled`() {
        val old = native(OtisAirPrototypeV4.synthesize(OtisPrototypeVoice.STEAM, note, plain, 20261014, rate))
        val next = native(source())
        val root = hz(60)
        val matchedOld = matched(old, .4, 1.0)
        val matchedNext = matched(next, .4, 1.0)
        val before = upperShare(matchedOld, root, .4, 1.0)
        val after = upperShare(matchedNext, root, .4, 1.0)
        assertTrue(after > before * 3.0 && after > .7,
            "STEAM gained level rather than a strong held body: upper/root $before -> $after")
        val fundamental = power(matchedNext, root, .4, 1.0)
        assertTrue((2..8).all { power(matchedNext, root * it, .4, 1.0) < fundamental },
            "the stronger pipe substituted an upper harmonic for its root")
        assertTrue(power(matchedNext, root * 6, .4, 1.0) > fundamental * .008,
            "power disappeared above the old five-partial pipe")
        println("OTIS STEAM5 matched held upper/root: model4=$before model5=$after")
    }

    @Test
    fun `available pressure changes harmonic opening as well as amplitude without retuning the pipe`() {
        val full = native(source())
        val drawn = native(source(trace = constantPressure(.55)))
        val root = hz(60)
        val a = upperShare(matched(full, .4, 1.0), root, .4, 1.0)
        val b = upperShare(matched(drawn, .4, 1.0), root, .4, 1.0)
        val fundamentalRatio = sqrt(power(drawn, root, .4, 1.0) / power(full, root, .4, 1.0))
        assertTrue(b < a * .45, "shared pressure is merely a gain control: $a -> $b")
        assertTrue(abs(fundamentalRatio - .55) < .01, "available pressure does not scale the pipe envelope: $fundamentalRatio")
        val pitchA = measuredCents(full, root, .4, 1.0, -6, 6)
        val pitchB = measuredCents(drawn, root, .4, 1.0, -6, 6)
        assertTrue(abs(pitchA) <= 2 && abs(pitchB) <= 2 && abs(pitchA - pitchB) <= 1,
            "pressure moved the saved root: $pitchA -> $pitchB cents")
        println("OTIS STEAM5 pressure: upper/root=$a->$b fundamentalRatio=$fundamentalRatio pitch=$pitchA/$pitchB cents")
    }

    @Test
    fun `a second key darkens and softens the already held pipe before source summation`() {
        val first = OtisPrototypeNote(midi = 60, gateSeconds = 1.8)
        val crowded = listOf(first, OtisPrototypeNote(midi = 63, startSeconds = .45, gateSeconds = .65))
        val frames = OtisAirCharacterV5.frameCount(OtisPrototypeVoice.STEAM, crowded, plain, rate)
        val trace = OtisAirCharacterV5.trace(OtisPrototypeVoice.STEAM, crowded, plain, frames, .85f, rate)
        // Isolate the first pipe but keep the budget drawn by both keys.
        val solo = source(notes = listOf(first))
        val loaded = source(notes = listOf(first), trace = trace)
        assertContentEquals(solo.copyOfRange(0, (.42 * rate).toInt()), loaded.copyOfRange(0, (.42 * rate).toInt()),
            "the shared controller changed the pipe before the second key entered")
        val full = native(solo)
        val shared = native(loaded)
        val root = hz(60)
        val levelRatio = rms(shared, .75, .95) / rms(full, .75, .95)
        val openingRatio = upperShare(shared, root, .75, .95) / upperShare(full, root, .75, .95)
        assertTrue(levelRatio in .4.. .85 && openingRatio < .70,
            "shared demand did not change the first source: level=$levelRatio opening=$openingRatio")
        assertTrue(rms(shared, 1.6, 1.75) > rms(full, 1.6, 1.75) * .9,
            "the held pipe did not refill after the extra key released")
        println("OTIS STEAM5 overlap: firstPipeRmsRatio=$levelRatio upperShareRatio=$openingRatio")
    }

    @Test
    fun `stronger body retains model4 steam drift instead of detuning or fixing the pitch`() {
        val notes = listOf(OtisPrototypeNote(midi = 72, gateSeconds = 1.4))
        val settings = plain.copy(steamInstability = .8f)
        val old = native(OtisAirPrototypeV4.synthesize(OtisPrototypeVoice.STEAM, notes, settings, 20261014, rate))
        val next = native(source(notes, settings))
        val root = hz(72)
        val times = listOf(.3, .6, .9, 1.2)
        val oldPitch = times.map { measuredCents(old, root, it, it + .10, -70, 70) }
        val newPitch = times.map { measuredCents(next, root, it, it + .10, -70, 70) }
        assertTrue(newPitch.max() - newPitch.min() > 10, "the powerful pipe lost its wandering steam identity: $newPitch")
        assertTrue(newPitch.all { abs(it) <= 62 }, "steam pitch escaped its declared range: $newPitch")
        assertTrue(oldPitch.zip(newPitch).all { (a, b) -> abs(a - b) <= 2 },
            "body gains changed the model4 pressure clock: $oldPitch -> $newPitch")
        println("OTIS STEAM5 drift: model4=$oldPitch model5=$newPitch cents")
    }

    @Test
    fun `upper pipe harmonics taper before the output band and cannot fold back at a low render rate`() {
        val notes = listOf(OtisPrototypeNote(midi = 69, gateSeconds = 1.2))
        val lowRate = 8000
        val low = source(notes, trace = constantPressure(1.0, lowRate), sampleRate = lowRate)
        val high = source(notes)
        val lowRatio = amplitude(low, 3520.0, .6, 1.1, lowRate) / amplitude(low, 440.0, .6, 1.1, lowRate)
        val highRatio = amplitude(high, 3520.0, .6, 1.1, rate) / amplitude(high, 440.0, .6, 1.1, rate)
        val measuredTaper = lowRatio / highRatio
        val expectedTaper = .5 + .5 * cos(PI * (3520.0 / 3600.0 - .92) / .08)
        assertTrue(abs(measuredTaper - expectedTaper) < .015,
            "upper pipe ignores the smooth band taper: expected=$expectedTaper measured=$measuredTaper")

        val upper = listOf(OtisPrototypeNote(midi = 84, gateSeconds = 1.0))
        val pruned = source(upper, trace = constantPressure(1.0, lowRate), sampleRate = lowRate)
        val root = hz(84)
        val alias = abs(lowRate - 4 * root)
        val aliasRatio = amplitude(pruned, alias, .5, .9, lowRate) / amplitude(pruned, root, .5, .9, lowRate)
        assertTrue(aliasRatio < 1e-4, "a fourth partial above the render band folded into $alias Hz: $aliasRatio")
        println("OTIS STEAM5 band: taper=$measuredTaper aliasToRoot=$aliasRatio")
    }

    @Test
    fun `finite source replays and leaves mute zero pressure and leading silence at rest`() {
        val notes = listOf(OtisPrototypeNote(midi = 48, startSeconds = .1, gateSeconds = .25),
            OtisPrototypeNote(midi = 72, startSeconds = .2, gateSeconds = .25, velocity = 1f))
        val settings = OtisAirSettings(steamInstability = 1f, pressure = 1f, breath = 1f)
        val frames = OtisAirCharacterV5.frameCount(OtisPrototypeVoice.STEAM, notes, settings, rate)
        val trace = OtisAirCharacterV5.trace(OtisPrototypeVoice.STEAM, notes, settings, frames, .85f, rate)
        val a = source(notes, settings, trace)
        val b = source(notes, settings, trace)
        assertContentEquals(a, b)
        assertTrue(a.all { it.isFinite() && abs(it) < 6f }, "bounded two-pipe source escaped its finite budget")
        assertTrue(a.take((.09 * rate).toInt()).all { it == 0f }, "source altered leading silence")
        assertTrue(a.takeLast(256).all { abs(it) < 1e-6 }, "steam tail was cut while audible")
        assertTrue(source(notes, settings, constantPressure(0.0)).all { it == 0f }, "zero shared pressure still sounds")
        assertTrue(source(notes.map { it.copy(velocity = 0f) }, settings).all { it == 0f }, "muted keys still sound")
        assertFailsWith<IllegalArgumentException> { source(trace = constantPressure(Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { source(trace = constantPressure(1.0, 8000)) }
    }

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val first = (from * Dsp.RATE).toInt()
        val end = (to * Dsp.RATE).toInt()
        return sqrt((first until end).sumOf { x[it].toDouble() * x[it] } / (end - first))
    }

    private fun matched(x: FloatArray, from: Double, to: Double): FloatArray {
        val level = rms(x, from, to)
        return FloatArray(x.size) { (x[it] / level).toFloat() }
    }

    private fun upperShare(x: FloatArray, root: Double, from: Double, to: Double): Double =
        (2..8).sumOf { power(x, root * it, from, to) } / power(x, root, from, to)

    private fun power(x: FloatArray, frequency: Double, from: Double, to: Double): Double =
        amplitude(x, frequency, from, to, Dsp.RATE).let { it * it }

    private fun amplitude(x: FloatArray, frequency: Double, from: Double, to: Double, sampleRate: Int): Double {
        val first = (from * sampleRate).toInt()
        val end = (to * sampleRate).toInt()
        var real = 0.0
        var imaginary = 0.0
        var weights = 0.0
        for (i in first until end) {
            val weight = .5 - .5 * cos(2 * PI * (i - first) / (end - first - 1))
            val phase = 2 * PI * frequency * i / sampleRate
            real += x[i] * weight * cos(phase)
            imaginary += x[i] * weight * sin(phase)
            weights += weight
        }
        return 2 * sqrt(real * real + imaginary * imaginary) / weights
    }

    private fun measuredCents(x: FloatArray, root: Double, from: Double, to: Double, low: Int, high: Int): Int =
        (low..high).maxBy { cents -> power(x, root * 2.0.pow(cents / 1200.0), from, to) }
}
