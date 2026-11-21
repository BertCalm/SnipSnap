package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The owner's verdicts of 20 Nov 2026: the six presets were "all pretty similar" and soft,
 * normal and hard strikes "all sound the same". These tests hold the measured answers.
 *
 * Spectra are of the whole rendered note (zero-padded, no window). A third-octave distance
 * is the RMS difference, in dB, between two notes' 26 band levels from 50 Hz to 16 kHz,
 * each note's levels taken relative to its own mean and floored 60 dB under its strongest band.
 */
class ChamberTouchTest {
    private fun power(x: FloatArray): DoubleArray {
        val size = Fft.floorPowerOfTwo(x.size).let { if (it < x.size) it * 2 else it }
        val re = x.copyOf(size); val im = FloatArray(size)
        Fft.forward(re, im)
        return DoubleArray(size / 2 + 1) { re[it].toDouble().pow(2) + im[it].toDouble().pow(2) }
    }
    private fun centroid(x: FloatArray): Double {
        val p = power(x); val hz = Dsp.RATE.toDouble() / ((p.size - 1) * 2)
        return p.indices.sumOf { it * hz * p[it] } / p.sum()
    }
    private fun thirdOctaves(x: FloatArray): DoubleArray {
        val p = power(x); val hz = Dsp.RATE.toDouble() / ((p.size - 1) * 2)
        val bands = DoubleArray(26) { band ->
            val centre = 1000.0 * 2.0.pow((band - 13) / 3.0)
            val lo = centre * 2.0.pow(-1.0 / 6); val hi = centre * 2.0.pow(1.0 / 6)
            var sum = 1e-20
            for (i in p.indices) { val f = i * hz; if (f >= lo && f < hi) sum += p[i] }
            10 * log10(sum)
        }
        val floor = bands.max() - 60
        for (i in bands.indices) bands[i] = maxOf(bands[i], floor)
        val mean = bands.average()
        return DoubleArray(26) { bands[it] - mean }
    }
    private fun distance(a: FloatArray, b: FloatArray): Double {
        val x = thirdOctaves(a); val y = thirdOctaves(b)
        return sqrt(x.indices.sumOf { (x[it] - y[it]).pow(2) } / x.size)
    }
    private fun rmsDb(x: FloatArray) = 10 * log10(x.sumOf { it.toDouble().pow(2) } / x.size)

    @Test
    fun `soft normal and hard are three different strikes - brighter and louder with touch`() {
        val presets = ChamberPresets.all()
        val cases = listOf(presets.single { it.name == "Four Answers" }, presets.single { it.name == "Four Answers" }.copy(midi = 48),
            presets.single { it.name == "Aquarium Ceiling" })
        for (patch in cases) {
            val soft = patch.copy(velocity = .3f).render().samples
            val normal = patch.copy(velocity = Chamber.NORMAL_VELOCITY).render().samples
            val hard = patch.copy(velocity = 1f).render().samples
            val softCentroid = centroid(soft) / centroid(normal); val hardCentroid = centroid(hard) / centroid(normal)
            val softLevel = rmsDb(soft) - rmsDb(normal); val hardLevel = rmsDb(hard) - rmsDb(normal)
            println("CHAMBER touch ${patch.name} midi ${patch.midi}: centroid soft ${"%.0f".format(centroid(soft))} normal ${"%.0f".format(centroid(normal))} " +
                "hard ${"%.0f".format(centroid(hard))} Hz (x${"%.3f".format(softCentroid)}, x${"%.3f".format(hardCentroid)}); level soft ${"%.2f".format(softLevel)} dB, hard +${"%.2f".format(hardLevel)} dB; " +
                "third-octave distance soft ${"%.1f".format(distance(soft, normal))} dB, hard ${"%.1f".format(distance(hard, normal))} dB")
            assertTrue(hardCentroid >= MIN_HARD_CENTROID, "${patch.name} ${patch.midi}: a hard strike must be brighter: x$hardCentroid")
            assertTrue(softCentroid <= MAX_SOFT_CENTROID, "${patch.name} ${patch.midi}: a soft strike must be duller: x$softCentroid")
            assertTrue(softLevel <= -MIN_SOFT_STEP_DB, "${patch.name} ${patch.midi}: a soft strike must be quieter: $softLevel dB")
            assertTrue(hardLevel >= MIN_HARD_STEP_DB, "${patch.name} ${patch.midi}: a hard strike must be louder: $hardLevel dB")
            assertTrue(distance(soft, normal) >= MIN_TOUCH_DISTANCE_DB && distance(hard, normal) >= MIN_TOUCH_DISTANCE_DB,
                "${patch.name} ${patch.midi}: each touch must change the tone, not only the level")
            assertTrue(hard.maxOf { kotlin.math.abs(it) } <= .99f)
        }
    }

    @Test
    fun `the normal strike is the accepted sound - touch pivots on velocity three quarters`() {
        // ChamberProductionCompatibilityTest pins the default rooms at this velocity by hash; this holds the law's shape.
        val patch = ChamberPresets.all().first()
        assertContentEquals(patch.render().samples, patch.copy(velocity = Chamber.NORMAL_VELOCITY).render().samples)
        assertTrue(Chamber.touch(Chamber.NORMAL_VELOCITY) == 0.0)
    }

    @Test
    fun `the six factory recipes are six different sounds`() {
        val presets = ChamberPresets.all()
        val renders = presets.map { it.render().samples }
        var least = Double.MAX_VALUE
        for (a in presets.indices) for (b in 0 until a) {
            val d = distance(renders[a], renders[b])
            println("CHAMBER presets ${presets[b].name} / ${presets[a].name}: third-octave distance ${"%.1f".format(d)} dB")
            least = minOf(least, d)
            assertTrue(d >= MIN_PRESET_DISTANCE_DB, "${presets[b].name} and ${presets[a].name} are too alike: $d dB")
        }
        println("CHAMBER presets least distance ${"%.1f".format(least)} dB (the six the owner called similar: least 2.5 dB)")
    }

    private companion object {
        /** A hard strike (velocity 1) raises the whole-note spectral centroid by at least this factor over normal (.75). */
        const val MIN_HARD_CENTROID = 1.2
        /** A soft strike (velocity .3) lowers it to at most this factor. */
        const val MAX_SOFT_CENTROID = .92
        /** Whole-note RMS: soft is at least this much under normal, hard at least this much over. */
        const val MIN_SOFT_STEP_DB = 5.0
        const val MIN_HARD_STEP_DB = 1.2
        /** Third-octave distance of soft and of hard from normal; all three were within 0.4 dB of each other. */
        const val MIN_TOUCH_DISTANCE_DB = 3.5
        /** Least third-octave distance between any two factory recipes; the original six had pairs at 2.5 dB. */
        const val MIN_PRESET_DISTANCE_DB = 5.5
    }
}
