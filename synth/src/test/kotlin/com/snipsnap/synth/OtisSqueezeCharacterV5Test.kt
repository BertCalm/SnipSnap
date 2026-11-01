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

/** Emitted-audio evidence at the audition's actual four-times clock, not just coefficient tests. */
class OtisSqueezeCharacterV5Test {
    private val rate = Dsp.RATE * Dsp.OVERSAMPLE
    private val dry = OtisAirSettings(musette = 0f, bellowsPulse = 0f)
    private val note = OtisPrototypeNote(60, gateSeconds = .7, velocity = .8f)

    @Test
    fun `nasal reed character survives zero musette and equal loudness against model four`() {
        val current = finished(source(listOf(note)))
        val old = finished(OtisAirPrototypeV4.synthesize(OtisPrototypeVoice.SQUEEZE, listOf(note), dry, 41, rate))
        val a = match(old, .25, .6)
        val b = match(current, .25, .6)
        val root = hz(60)
        val oldEven = share(a, root, listOf(2, 4, 6), .25, .6)
        val newEven = share(b, root, listOf(2, 4, 6), .25, .6)
        assertTrue(oldEven < 1e-5 && newEven > .012,
            "zero-musette character is not a new pitched spectrum: even/root $oldEven -> $newEven")
        assertTrue(relative(a, b, .25, .6) > .14, "new source differs only by gain or inaudible arithmetic")
        println("OTIS SQUEEZE5 matched even/root: model4=$oldEven model5=$newEven")
    }

    @Test
    fun `shared pressure closes upper reed opening after body level is matched`() {
        val full = match(finished(source(listOf(note), available = 1.0)), .25, .6)
        val drawn = match(finished(source(listOf(note), available = .55)), .25, .6)
        val a = share(full, hz(60), listOf(5, 7, 9, 11), .25, .6)
        val b = share(drawn, hz(60), listOf(5, 7, 9, 11), .25, .6)
        assertTrue(a > .01 && b < a * .20, "pressure is only an output gain: upper/root $a -> $b")
        assertTrue(rms(full, .25, .6) > .99 && rms(drawn, .25, .6) > .99)
        println("OTIS SQUEEZE5 matched pressure upper/root: full=$a drawn=$b")
    }

    @Test
    fun `pressure preserves each requested root across the audition register`() {
        for (midi in listOf(48, 60, 72)) {
            val n = note.copy(midi = midi)
            val full = finished(source(listOf(n), available = 1.0))
            val drawn = finished(source(listOf(n), available = .45))
            val a = measuredCents(full, hz(midi), .25, .6)
            val b = measuredCents(drawn, hz(midi), .25, .6)
            assertTrue(abs(a) <= 1 && abs(b) <= 1 && abs(a - b) <= 1,
                "pressure changed MIDI$midi pitch: $a -> $b cents")
            assertTrue(power(full, hz(midi), .25, .6) > power(full, hz(midi) * 2, .25, .6) * 4,
                "a case weight promoted an overtone above the root at MIDI$midi")
        }
    }

    @Test
    fun `upper reeds speak behind the root and choke before its released body`() {
        val x = finished(source(listOf(note)))
        val early = share(x, hz(60), listOf(3, 5, 7), .004, .024)
        val body = share(x, hz(60), listOf(3, 5, 7), .25, .5)
        val leaving = share(x, hz(60), listOf(3, 5, 7), .735, .775)
        assertTrue(early < body * .70, "upper reed attack has no lag: early=$early body=$body")
        assertTrue(leaving < body * .20, "upper reeds do not choke ahead of root: release=$leaving body=$body")
        assertTrue(rms(x, .735, .775) > rms(x, .25, .5) * .10, "release choke also erased the root")
        val short = source(listOf(note.copy(gateSeconds = .04)), settings = dry.copy(release = 1f))
        assertTrue(rawRms(short, .09, .12) < rawRms(short, .03, .04), "unfinished upper attack bloomed after key-up")
    }

    @Test
    fun `velocity opens the reed spectrum independently of level`() {
        val soft = match(finished(source(listOf(note.copy(velocity = .25f)))), .25, .6)
        val hard = match(finished(source(listOf(note.copy(velocity = .95f)))), .25, .6)
        val a = share(soft, hz(60), listOf(2, 3, 4, 5, 7), .25, .6)
        val b = share(hard, hz(60), listOf(2, 3, 4, 5, 7), .25, .6)
        assertTrue(b > a * 1.7, "velocity changes only level: upper/root $a -> $b")
    }

    @Test
    fun `finite feedforward source is deterministic order stable and silent without effort or pressure`() {
        val notes = listOf(note.copy(startSeconds = .1), note.copy(midi = 63, startSeconds = .25),
            note.copy(midi = 67, startSeconds = .4))
        val a = source(notes, settings = OtisAirSettings())
        val b = source(notes, settings = OtisAirSettings())
        assertContentEquals(a, b)
        val reversed = source(notes.reversed(), settings = OtisAirSettings())
        assertTrue(a.indices.maxOf { abs(a[it] - reversed[it]) } < 3e-7, "score order reset a source identity")
        assertTrue(a.all { it.isFinite() && abs(it) < 3f }, "three-key source exceeded its stated raw bound")
        assertTrue(a.takeLast(256).all { abs(it) < 1e-6 }, "finite exponential release was cut while audible")
        assertTrue(source(notes.map { it.copy(velocity = 0f) }).all { it == 0f }, "muted notes did work")
        assertTrue(source(notes, available = 0.0).all { it == 0f }, "empty shared pressure still voiced")
        assertTrue(!a.contentEquals(source(notes, settings = OtisAirSettings(), seed = 42)), "seed cannot vary reed speech")
    }

    @Test
    fun `low and high boundary notes stay finite and pressure trace clocks are explicit`() {
        for (clock in listOf(8000, rate)) for (midi in listOf(36, 84)) {
            val notes = listOf(note.copy(midi = midi, velocity = 1f, gateSeconds = .2))
            val settings = dry.copy(pressure = 1f, musette = 1f, bellowsPulse = 1f, release = 1f)
            val x = source(notes, settings, clock = clock)
            assertTrue(x.all { it.isFinite() && abs(it) < 1.2f }, "unbounded MIDI$midi at $clock Hz")
            assertTrue(rawRms(x, .05, .15, clock) > .01, "pruning lost the MIDI$midi root")
        }
        val frames = OtisAirCharacterV5.frameCount(OtisPrototypeVoice.SQUEEZE, listOf(note), dry, rate)
        assertFailsWith<IllegalArgumentException> {
            OtisSqueezeCharacterV5.synthesize(listOf(note), dry, 41, rate, trace(frames, rate / 2, 1.0))
        }
        assertFailsWith<IllegalArgumentException> {
            OtisSqueezeCharacterV5.synthesize(listOf(note), dry, 41, rate, trace(frames, rate, Double.NaN))
        }
    }

    private fun source(notes: List<OtisPrototypeNote>, settings: OtisAirSettings = dry,
        available: Double = 1.0, clock: Int = rate, seed: Int = 41): FloatArray {
        val frames = OtisAirCharacterV5.frameCount(OtisPrototypeVoice.SQUEEZE, notes, settings, clock)
        return OtisSqueezeCharacterV5.synthesize(notes, settings, seed, clock, trace(frames, clock, available))
    }

    private fun trace(frames: Int, clock: Int, available: Double): OtisPhrasePressureTrace {
        val stride = (clock / 1000).coerceAtLeast(1)
        val count = frames / stride + 2
        return OtisPhrasePressureTrace(DoubleArray(count) { available }, DoubleArray(count),
            DoubleArray(count) { available }, stride, clock)
    }

    private fun finished(x: FloatArray): FloatArray = OtisPrototypeV4.condition(x).samples
    private fun hz(midi: Int): Double = 440 * 2.0.pow((midi - 69) / 12.0)
    private fun rms(x: FloatArray, from: Double, to: Double): Double = rawRms(x, from, to, Dsp.RATE)
    private fun rawRms(x: FloatArray, from: Double, to: Double, clock: Int = rate): Double {
        val a = (from * clock).toInt()
        val b = (to * clock).toInt()
        return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    }

    private fun match(x: FloatArray, from: Double, to: Double): FloatArray {
        val gain = 1 / rms(x, from, to)
        return FloatArray(x.size) { (x[it] * gain).toFloat() }
    }

    private fun share(x: FloatArray, root: Double, harmonics: List<Int>, from: Double, to: Double): Double =
        harmonics.sumOf { power(x, root * it, from, to) } / power(x, root, from, to)

    private fun power(x: FloatArray, hz: Double, from: Double, to: Double): Double {
        val start = (from * Dsp.RATE).toInt()
        val count = ((to - from) * Dsp.RATE).toInt()
        var re = 0.0
        var im = 0.0
        for (i in 0 until count) {
            val v = x[start + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))
            val phase = 2 * PI * hz * i / Dsp.RATE
            re += v * cos(phase)
            im += v * sin(phase)
        }
        return re * re + im * im
    }

    private fun measuredCents(x: FloatArray, root: Double, from: Double, to: Double): Int =
        (-8..8).maxBy { power(x, root * 2.0.pow(it / 1200.0), from, to) }

    private fun relative(a: FloatArray, b: FloatArray, from: Double, to: Double): Double {
        val start = (from * Dsp.RATE).toInt()
        val end = (to * Dsp.RATE).toInt()
        return sqrt((start until end).sumOf { (a[it].toDouble() - b[it]).pow(2) } /
            (start until end).sumOf { a[it].toDouble().pow(2) })
    }
}
