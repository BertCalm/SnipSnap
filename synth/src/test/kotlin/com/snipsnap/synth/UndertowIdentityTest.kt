package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Protect the six inlet/material roles after loudness matching. These deliberately use one
 * common control recipe: different defaults, a carrier phase shift or final gain cannot
 * supply a voice identity. Listening remains the acceptance test for their musical value.
 */
class UndertowIdentityTest {
    private companion object {
        val neutral = mapOf(
            "TUNE" to .5f, "DRAW" to .45f, "FLAP" to .40f, "WEIGHT" to .50f,
            "SPIRAL" to .40f, "LEAK" to .30f, "HOLD" to 0f,
        )
        val voices by lazy {
            UndertowVoice.entries.associateWith {
                Undertow.probe(it, neutral, energy = 1f,
                    options = Undertow.ProbeOptions(durationSeconds = 2.4f))
            }
        }
        val stopped by lazy {
            listOf(UndertowVoice.BREATH, UndertowVoice.HOLLOW).associateWith {
                Undertow.probe(it, neutral, energy = 1f,
                    options = Undertow.ProbeOptions(durationSeconds = 3f, driveStopSeconds = .6f))
            }
        }
    }

    private fun energy(samples: FloatArray, from: Double, to: Double): Double {
        val start = (from * Dsp.RATE).toInt().coerceIn(0, samples.size)
        val end = (to * Dsp.RATE).toInt().coerceIn(start, samples.size)
        return (start until end).sumOf { samples[it].toDouble() * samples[it] }
    }

    /** Sum stem energies rather than their waveforms, so cancellation cannot fake a role. */
    private fun stemShare(probe: Undertow.Probe, stem: FloatArray, from: Double, to: Double): Double {
        val total = listOf(probe.ceramic, probe.airflow, probe.shell).sumOf { energy(it, from, to) }
        return energy(stem, from, to) / total.coerceAtLeast(1e-30)
    }

    /** Pooled Hann power, independent of phase and normalized later by audible power. */
    private fun spectrum(samples: FloatArray, from: Double = .30, to: Double = .80, size: Int = 8192): DoubleArray {
        val first = (from * Dsp.RATE).toInt()
        val end = minOf((to * Dsp.RATE).toInt(), samples.size)
        val power = DoubleArray(size / 2 + 1)
        var windows = 0
        for (start in first..(end - size) step (size / 2)) {
            val re = FloatArray(size) { samples[start + it] * (.5 - .5 * cos(2 * PI * it / (size - 1))).toFloat() }
            val im = FloatArray(size)
            Fft.forward(re, im)
            for (i in power.indices) power[i] += re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
            windows++
        }
        assertTrue(windows > 0, "identity window contains no complete spectrum")
        return power
    }

    private fun harmonicShare(samples: FloatArray, harmonics: IntRange): Double {
        val power = spectrum(samples)
        val binHz = Dsp.RATE.toDouble() / ((power.size - 1) * 2)
        val root = Undertow.frequencyFor(UndertowVoice.BREATH, neutral.getValue("TUNE")).toDouble()
        var total = 0.0
        var harmonic = 0.0
        for (i in power.indices) {
            val hz = i * binHz
            if (hz in 40.0..8000.0) {
                total += power[i]
                if (harmonics.any { abs(hz - root * it) <= 15.0 }) harmonic += power[i]
            }
        }
        return harmonic / total.coerceAtLeast(1e-30)
    }

    /** Broad fixed bands tolerate small pitch pulling and keep phase out of comparisons. */
    private fun spectralProfile(samples: FloatArray): DoubleArray {
        val power = spectrum(samples)
        val binHz = Dsp.RATE.toDouble() / ((power.size - 1) * 2)
        val edges = doubleArrayOf(40.0, 150.0, 350.0, 500.0, 700.0, 950.0, 1200.0, 2000.0, 4000.0, 8000.0, 18000.0)
        val bands = DoubleArray(edges.size - 1)
        for (i in bands.indices) {
            bands[i] = power.indices.filter { it * binHz >= edges[i] && it * binHz < edges[i + 1] }.sumOf { power[it] }
        }
        val total = bands.sum().coerceAtLeast(1e-30)
        return DoubleArray(bands.size) { bands[it] / total }
    }

    /** Hellinger distance between normalized band powers: zero means identical balance. */
    private fun spectralDistance(a: DoubleArray, b: DoubleArray): Double = sqrt(
        a.indices.sumOf { val d = sqrt(a[it]) - sqrt(b[it]); d * d } / 2,
    )

    /** Root-adjacent modulation sidebands, excluding the carrier and its Hann main lobe. */
    private fun sidebandShare(samples: FloatArray): Double {
        val power = spectrum(samples, from = .35, to = 1.10, size = 32768)
        val binHz = Dsp.RATE.toDouble() / ((power.size - 1) * 2)
        val root = Undertow.frequencyFor(UndertowVoice.BREATH, neutral.getValue("TUNE")).toDouble()
        val carrierRegion = power.indices.filter { abs(it * binHz - root) <= 80.0 }.sumOf { power[it] }
        val sidebands = power.indices.filter { abs(it * binHz - root) in 8.0..80.0 }.sumOf { power[it] }
        return sidebands / carrierRegion.coerceAtLeast(1e-30)
    }

    /** A completed down/up excursion must cross both fixed aperture thresholds. */
    private fun apertureCycles(probe: Undertow.Probe, chamber: Int): Int {
        var atHigh = false
        var atLow = false
        var cycles = 0
        for (state in probe.snapshots.filter { it.timeSeconds in .25f..1.2f }) {
            val opening = state.apertures[chamber]
            if (opening >= .65f) {
                if (atHigh && atLow) cycles++
                atHigh = true
                atLow = false
            } else if (opening <= .40f && atHigh) {
                atLow = true
            }
        }
        return cycles
    }

    private fun reopens(probe: Undertow.Probe): Int {
        var count = 0
        for (chamber in 0..3) {
            var sealed = false
            for (state in probe.snapshots.filter { it.timeSeconds <= 1.4f }) {
                if (sealed && !state.sealed[chamber]) count++
                sealed = state.sealed[chamber]
            }
        }
        return count
    }

    @Test
    fun `knock catches ceramic early while breath opens smoothly`() {
        val knock = voices.getValue(UndertowVoice.KNOCK)
        val breath = voices.getValue(UndertowVoice.BREATH)
        val ceramicKnock = stemShare(knock, knock.ceramic, .02, .20)
        val ceramicBreath = stemShare(breath, breath.ceramic, .02, .20)
        val firstKnock = knock.contacts.firstOrNull()?.timeSeconds ?: Float.POSITIVE_INFINITY
        val firstBreath = breath.contacts.firstOrNull()?.timeSeconds ?: Float.POSITIVE_INFINITY
        assertTrue(ceramicKnock > .55 && ceramicBreath < .05,
            "early ceramic fractions KNOCK=$ceramicKnock BREATH=$ceramicBreath")
        assertTrue(firstKnock < .15f && firstKnock < firstBreath * .70f,
            "contact onset KNOCK=$firstKnock BREATH=$firstBreath")
    }

    @Test
    fun `surge excites upper cavity harmonics while breath keeps a clear root`() {
        val surge = voices.getValue(UndertowVoice.SURGE)
        val breath = voices.getValue(UndertowVoice.BREATH)
        val surgeUpper = harmonicShare(surge.samples, 2..8)
        val breathUpper = harmonicShare(breath.samples, 2..8)
        val breathRoot = harmonicShare(breath.samples, 1..1)
        assertTrue(surgeUpper > .15 && surgeUpper > 3 * breathUpper,
            "H2–H8 power fractions SURGE=$surgeUpper BREATH=$breathUpper")
        assertTrue(breathRoot > .90, "BREATH lost its root-rich wind: root fraction $breathRoot")
    }

    @Test
    fun `flutter repeatedly throttles the shared flow instead of staying open`() {
        val flutter = voices.getValue(UndertowVoice.FLUTTER)
        val breath = voices.getValue(UndertowVoice.BREATH)
        val flutterCycles = apertureCycles(flutter, 0)
        val breathCycles = apertureCycles(breath, 0)
        val flutterStates = flutter.snapshots.filter { it.timeSeconds in .25f..1.2f }
        val flowRange = flutterStates.maxOf { it.flows[0] } - flutterStates.minOf { it.flows[0] }
        val flutterSidebands = sidebandShare(flutter.samples)
        val breathSidebands = sidebandShare(breath.samples)
        assertTrue(flutterCycles >= 5 && breathCycles == 0,
            "large repeated aperture cycles FLUTTER=$flutterCycles BREATH=$breathCycles")
        assertTrue(flowRange > .06f, "FLUTTER aperture motion did not throttle inward flow: range $flowRange")
        assertTrue(flutterSidebands > .001 && flutterSidebands > 2 * breathSidebands,
            "FLUTTER motion did not reach audible root sidebands: FLUTTER=$flutterSidebands BREATH=$breathSidebands")
    }

    @Test
    fun `seal closes and reopens repeatedly during the powered draw`() {
        val seal = voices.getValue(UndertowVoice.SEAL)
        val breath = voices.getValue(UndertowVoice.BREATH)
        val sealReopens = reopens(seal)
        val breathReopens = reopens(breath)
        assertTrue(sealReopens >= 3 && breathReopens == 0,
            "powered seal-to-open transitions SEAL=$sealReopens BREATH=$breathReopens")
    }

    @Test
    fun `hollow leaves a resonant shell tail after the same piston withdrawal`() {
        val hollow = stopped.getValue(UndertowVoice.HOLLOW)
        val breath = stopped.getValue(UndertowVoice.BREATH)
        val hollowShell = stemShare(hollow, hollow.shell, .8, 1.4)
        val breathShell = stemShare(breath, breath.shell, .8, 1.4)
        val hollowTail = energy(hollow.shell, .8, 1.4) / energy(hollow.samples, .3, .6).coerceAtLeast(1e-30)
        val breathTail = energy(breath.shell, .8, 1.4) / energy(breath.samples, .3, .6).coerceAtLeast(1e-30)
        assertTrue(hollowShell > .08 && hollowShell > 20 * breathShell,
            "tail shell fractions HOLLOW=$hollowShell BREATH=$breathShell")
        assertTrue(hollowTail > .0003 && hollowTail > 100 * breathTail,
            "shell-tail/active-total energy after equal piston stop HOLLOW=$hollowTail BREATH=$breathTail")
    }

    @Test
    fun `every pair keeps a different normalized spectral balance at the common recipe`() {
        val profiles = voices.mapValues { spectralProfile(it.value.samples) }
        val names = UndertowVoice.entries
        for (i in names.indices) for (j in i + 1 until names.size) {
            val distance = spectralDistance(profiles.getValue(names[i]), profiles.getValue(names[j]))
            assertTrue(distance > .05,
                "${names[i]} and ${names[j]} collapsed to the same normalized power balance: distance $distance")
        }
    }
}
