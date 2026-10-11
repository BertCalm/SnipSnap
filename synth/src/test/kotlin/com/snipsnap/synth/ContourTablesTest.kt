package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContourTablesTest {
    @Test
    fun `all families are finite bounded and periodic across pitch and scan`() {
        for (table in ContourTable.entries) {
            for (hz in doubleArrayOf(20.0, 55.0, 220.0, 1_760.0, 8_000.0, 17_999.0, 25_000.0)) {
                val lookup = ContourTables.lookup(table, hz)
                for (scan in doubleArrayOf(-4.0, 0.0, 0.17, 0.5, 0.83, 1.0, 4.0)) {
                    for (i in 0 until 257) {
                        val phase = i / 257.0 - 1.37
                        val value = ContourTables.sample(table, phase, scan, hz)
                        assertTrue(value.isFinite() && abs(value) <= 0.900001, "$table scan=$scan hz=$hz: $value")
                        assertEquals(value, lookup.sample(phase, scan), "Compiled lookup differs")
                        assertEquals(value, ContourTables.sample(table, phase + 3.0, scan, hz), 1e-10)
                    }
                }
            }
        }
        assertEquals(0.0, ContourTables.sample(ContourTable.CLUSTER, Double.NaN, 0.5, 220.0))
        assertEquals(0.0, ContourTables.sample(ContourTable.CLUSTER, 0.3, Double.NaN, 220.0))
        assertEquals(0.0, ContourTables.sample(ContourTable.CLUSTER, 0.3, 0.5, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `phase wrap frame boundaries and pitch level changes are continuous`() {
        val limits = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256)
        for (table in ContourTable.entries) {
            for (scan in doubleArrayOf(0.0, 0.31, 0.67, 1.0)) {
                val before = ContourTables.sample(table, -1e-9, scan, 55.0)
                val after = ContourTables.sample(table, 1e-9, scan, 55.0)
                assertTrue(abs(before - after) < 1e-5, "$table phase wrap at $scan")
            }
            for (frame in 0 until ContourTables.FRAME_COUNT) {
                val scan = inverseSmoothstep(frame.toDouble() / (ContourTables.FRAME_COUNT - 1))
                for (i in 0 until 32) {
                    val phase = i / 32.0 + 0.013
                    val before = ContourTables.sample(table, phase, scan - 1e-7, 110.0)
                    val after = ContourTables.sample(table, phase, scan + 1e-7, 110.0)
                    assertTrue(abs(before - after) < 1e-4, "$table frame $frame phase $phase")
                }
            }
            for (limit in limits) for (i in 0 until 32) {
                val hz = ContourTables.BANDWIDTH_HZ / limit
                val phase = i / 32.0 + 0.007
                val before = ContourTables.sample(table, phase, 0.72, hz * (1.0 - 1e-7))
                val after = ContourTables.sample(table, phase, 0.72, hz * (1.0 + 1e-7))
                assertTrue(abs(before - after) < 1e-4, "$table pitch level $limit phase $phase")
            }
        }
    }

    @Test
    fun `scanning changes harmonics while retaining root and smooth energy`() {
        val size = ContourTables.TABLE_SIZE
        for (table in ContourTable.entries) {
            var minimumRoot = Double.POSITIVE_INFINITY
            var maximumRoot = 0.0
            var previousRms = 0.0
            var difference = 0.0
            for (step in 0..32) {
                var energy = 0.0
                var root = 0.0
                for (i in 0 until size) {
                    val phase = i.toDouble() / size
                    val value = ContourTables.sample(table, phase, step / 32.0, 55.0)
                    root += value * sin(2.0 * PI * phase) * 2.0 / size
                    energy += value * value
                    if (step == 32) {
                        val start = ContourTables.sample(table, phase, 0.0, 55.0)
                        difference += (value - start) * (value - start)
                    }
                }
                val rms = sqrt(energy / size)
                minimumRoot = minOf(minimumRoot, root)
                maximumRoot = maxOf(maximumRoot, root)
                assertTrue(rms > 0.12, "$table scan $step lost its body: $rms")
                if (step > 0) assertTrue(maxOf(rms / previousRms, previousRms / rms) < 1.15, "$table energy jump at $step")
                previousRms = rms
            }
            // The cluster family has the largest intended partial energy: its global
            // peak policy leaves a 0.185 root amplitude, still constant throughout scan.
            assertTrue(minimumRoot > 0.15, "$table weak root: $minimumRoot")
            assertTrue(maximumRoot - minimumRoot < 1e-6, "$table root changes during scan")
            assertTrue(sqrt(difference / size) > 0.07, "$table scan has little harmonic effect")
        }
    }

    @Test
    fun `pitch limits prevent unsafe harmonics and suppress interpolation aliases`() {
        // Odd coherent FFT bins exercise fractional phase interpolation without window
        // leakage. Test the rendered oscillator, including table-interpolation images.
        val size = 65_536
        val rate = 44_100.0
        for (table in ContourTable.entries) for (fundamentalBin in intArrayOf(83, 523, 2_617)) {
            val hz = fundamentalBin * rate / size
            val permitted = (ContourTables.BANDWIDTH_HZ / hz).toInt()
            for (scan in doubleArrayOf(0.0, 0.5, 1.0)) {
                val re = FloatArray(size) { i -> ContourTables.sample(table, i.toDouble() * fundamentalBin / size, scan, hz).toFloat() }
                val im = FloatArray(size)
                Fft.forward(re, im)
                var total = 0.0
                var unintended = 0.0
                for (bin in 1 until size / 2) {
                    val energy = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
                    total += energy
                    val isSafeHarmonic = bin % fundamentalBin == 0 && bin / fundamentalBin <= permitted
                    if (!isSafeHarmonic) unintended += energy
                }
                assertTrue(unintended / total < 1e-7, "$table scan=$scan hz=$hz unintended share=${unintended / total}")
                assertTrue(abs(re[0]) / size < 1e-6, "$table DC at hz=$hz")
            }
        }
    }

    @Test
    fun `moving scans retain intended sidebands without folded bandwidth`() {
        // The coherent 8x reference includes the wanted scan sidebands. Comparing
        // corresponding complex FFT bins, rather than rejecting every non-harmonic
        // bin, measures energy folded into the native band without penalizing motion.
        // These roots cover about 1.05, 2.09 and 4.09 kHz; the two-harmonic scan
        // gesture runs at 5.4 and 18.8 Hz, faster than ordinary sustained motion.
        var worstAlias = 0.0
        for (table in ContourTable.entries) {
            for (fundamentalBin in intArrayOf(389, 777, 1_519)) for (motionBin in intArrayOf(2, 7)) {
                val (aliasShare, motionShare) = movingSpectrum(table, fundamentalBin, motionBin)
                worstAlias = maxOf(worstAlias, aliasShare)
                // Calibrated worst error is 3.3e-9. The -60 dB gate leaves room for
                // FFT rounding and evolving recipes, while rejecting actual folds.
                assertTrue(aliasShare < 1e-6, "$table rootBin=$fundamentalBin motionBin=$motionBin folded share=$aliasShare")
                // A frozen trajectory cannot pass the bandwidth test as a moving
                // source: at least 0.1% of reference energy must be scan sidebands.
                assertTrue(motionShare > .001, "$table rootBin=$fundamentalBin scan sideband share=$motionShare")
            }
        }
        // Deliberately ask for a bass mip while advancing the oscillator at 2.09 kHz.
        // CLUSTER's moving upper partials then cross Nyquist and fold; the same
        // reference comparison must detect them rather than accepting matched gain.
        val (aliasedControl, _) = movingSpectrum(ContourTable.CLUSTER, 777, 7, bypassPitchLimit = true)
        assertTrue(aliasedControl > .05, "Unbandlimited moving positive control escaped: $aliasedControl")
        println("CONTOUR moving table worst folded share=$worstAlias; bypassed-mip control=$aliasedControl")
    }

    private fun movingSpectrum(table: ContourTable, fundamentalBin: Int, motionBin: Int,
        bypassPitchLimit: Boolean = false): Pair<Double, Double> {
        val size = 16_384
        val oversample = 8
        val hz = fundamentalBin * 44_100.0 / size
        val lookup = ContourTables.lookup(table, if (bypassPitchLimit) 20.0 else hz)
        val nativeRe = FloatArray(size)
        val nativeIm = FloatArray(size)
        val referenceRe = FloatArray(size * oversample)
        val referenceIm = FloatArray(referenceRe.size)
        for (i in referenceRe.indices) {
            val time = i.toDouble() / referenceRe.size
            val angle = 2.0 * PI * motionBin * time
            val scan = .5 + .42 * sin(angle) + .06 * sin(2.0 * angle)
            val value = lookup.sample(fundamentalBin * time, scan).toFloat()
            referenceRe[i] = value
            if (i % oversample == 0) nativeRe[i / oversample] = value
        }
        Fft.forward(nativeRe, nativeIm)
        Fft.forward(referenceRe, referenceIm)
        var referenceEnergy = 0.0
        var foldedEnergy = 0.0
        var sidebandEnergy = 0.0
        // Equal durations make bin frequencies identical. Dividing reference FFT
        // amplitudes by 8 matches normalization; its upper bins are the ideal
        // antialias rejection region, so no resampler response clouds this probe.
        for (bin in 1 until size / 2) {
            val re = referenceRe[bin].toDouble() / oversample
            val im = referenceIm[bin].toDouble() / oversample
            val energy = re * re + im * im
            val differenceRe = nativeRe[bin] - re
            val differenceIm = nativeIm[bin] - im
            referenceEnergy += energy
            foldedEnergy += differenceRe * differenceRe + differenceIm * differenceIm
            if (bin % fundamentalBin != 0) sidebandEnergy += energy
        }
        return foldedEnergy / referenceEnergy to sidebandEnergy / referenceEnergy
    }

    private fun inverseSmoothstep(value: Double): Double {
        var low = 0.0
        var high = 1.0
        repeat(50) {
            val middle = (low + high) * 0.5
            if (middle * middle * (3.0 - 2.0 * middle) < value) low = middle else high = middle
        }
        return (low + high) * 0.5
    }
}
