package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Model 1's sample-distance checks accepted a nearly invariant octave whistle. These checks
 * inspect relative spectral bands and absolute-time energy envelopes after matching, so a
 * changed phase or output gain alone cannot establish control authority.
 */
class OhmV2Test {
    private val base = OhmV2.defaults() + mapOf(
        "TOUCH" to .5f, "BODY" to .5f, "LENGTH" to .65f, "MEDDLING" to .75f, "COMMUNE" to .5f,
    )
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)

    private fun rms(x: FloatArray, from: Double, until: Double): Double {
        val first = (from * Dsp.RATE).toInt()
        val end = minOf(x.size, (until * Dsp.RATE).toInt())
        var energy = 0.0
        for (i in first until end) energy += x[i].toDouble() * x[i]
        return sqrt(energy / (end - first).coerceAtLeast(1))
    }

    /** Full-mix energy in fundamental, octave, upper-string and pick bands, normalized to unity. */
    private fun bands(snip: Snip, from: Double = .035, until: Double = 2.8): DoubleArray {
        val n = 8192
        val first = (from * Dsp.RATE).toInt()
        val end = minOf(snip.samples.size, (until * Dsp.RATE).toInt())
        val root = Keys.midiHz(60)
        val edges = doubleArrayOf(.72 * root, 1.30 * root, 2.55 * root, 5.5 * root, 15.0 * root)
        val power = DoubleArray(edges.size - 1)
        var offset = first
        while (offset + n <= end) {
            val re = FloatArray(n) { i -> (snip.samples[offset + i] * (.5 - .5 * cos(2 * PI * i / n))).toFloat() }
            val im = FloatArray(n)
            Fft.forward(re, im)
            for (bin in 1 until n / 2) {
                val hz = bin.toDouble() * Dsp.RATE / n
                val p = re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
                for (band in power.indices) if (hz >= edges[band] && hz < edges[band + 1]) power[band] += p
            }
            offset += n / 2
        }
        val sum = power.sum().coerceAtLeast(1e-30)
        return DoubleArray(power.size) { power[it] / sum }
    }

    private fun bandShift(a: DoubleArray, b: DoubleArray): Double =
        a.indices.sumOf { abs(10 * log10((a[it] + 1e-4) / (b[it] + 1e-4))) } / a.size

    /** Ignore merely moving tiny bands: this measures how much spectral energy changes bands. */
    private fun spectralMass(a: DoubleArray, b: DoubleArray): Double =
        .5 * a.indices.sumOf { abs(a[it] - b[it]) }

    private fun matched(macros: Map<String, Float>) = AuditionLevel.level(OhmV2.renderRaw(macros, seed = 731))

    @Test
    fun `touch and body change neighboring active ensemble spectra after matching`() {
        for (macro in listOf("TOUCH", "BODY")) {
            val spectra = steps.map { bands(matched(base + (macro to it))) }
            for (i in 0 until steps.lastIndex) {
                val shift = bandShift(spectra[i], spectra[i + 1])
                val mass = spectralMass(spectra[i], spectra[i + 1])
                println("OHM V2 $macro ${steps[i]}..${steps[i + 1]} matched band shift $shift dB, mass $mass")
                assertTrue(shift > .65 && mass > .005, "$macro has an inaudible interior region: $shift dB, mass $mass")
            }
            assertTrue(bandShift(spectra.first(), spectra.last()) > 4.0, "$macro lost broad timbral authority in the active ensemble")
        }
    }

    @Test
    fun `meddling and commune retain interior spectral or envelope differences`() {
        for (macro in listOf("MEDDLING", "COMMUNE")) {
            val clips = steps.map { matched(base + (macro to it)) }
            // Skip the pluck's first half-second: an onset-only change must not pass this check.
            val spectra = clips.map { bands(it, from = .50) }
            val envelopes = clips.map { clip ->
                val early = rms(clip.samples, .06, .24).coerceAtLeast(1e-12)
                doubleArrayOf(rms(clip.samples, .40, .72) / early,
                    rms(clip.samples, .9, 1.3) / early, rms(clip.samples, 1.5, 2.1) / early)
            }
            for (i in 0 until steps.lastIndex) {
                val spectral = bandShift(spectra[i], spectra[i + 1])
                val mass = spectralMass(spectra[i], spectra[i + 1])
                val temporal = envelopes[i].indices.maxOf { band ->
                    abs(20 * log10((envelopes[i][band] + 1e-8) / (envelopes[i + 1][band] + 1e-8)))
                }
                println("OHM V2 $macro ${steps[i]}..${steps[i + 1]} bands $spectral dB, mass $mass, envelope $temporal dB")
                assertTrue(spectral > .50 && mass > .005 || temporal > 1.2,
                    "$macro has an interior plateau: bands $spectral dB, mass $mass, envelope $temporal dB")
            }
        }
    }

    @Test
    fun `active ensemble retains a substantial fundamental and a persistent foreground`() {
        for ((touch, body) in listOf(.25f to .25f, .5f to .5f, .75f to .75f)) {
            val macros = base + mapOf("TOUCH" to touch, "BODY" to body, "MEDDLING" to .9f, "COMMUNE" to .75f)
            val probe = OhmV2.probe(macros, seed = 731)
            val shares = bands(probe.snip)
            val lateShares = bands(probe.snip, from = .50)
            val persistence = rms(probe.foreground, .60, 1.0) / rms(probe.foreground, .06, .24)
            println("OHM V2 T$touch B$body fundamental share ${shares[0]}, late ${lateShares[0]}, foreground persistence $persistence")
            assertTrue(shares[0] > .30, "the companions masked the requested root: ${shares[0]}")
            assertTrue(lateShares[0] > .20, "the root survived only the initial transient: ${lateShares[0]}")
            assertTrue(persistence > .15, "the recognizable foreground disappeared after the onset: $persistence")
        }
    }

    @Test
    fun `length extends audible decay while the companions are active`() {
        val short = matched(base + ("LENGTH" to 0f))
        val long = matched(base + ("LENGTH" to 1f))
        val shortRetained = rms(short.samples, .65, 1.10) / rms(short.samples, .06, .24)
        val longRetained = rms(long.samples, .65, 1.10) / rms(long.samples, .06, .24)
        println("OHM V2 active LENGTH retained short $shortRetained, long $longRetained")
        assertTrue(longRetained > 3 * shortRetained, "active LENGTH changed the file without extending the audible instrument")
    }

    @Test
    fun `coupling preserves the foreground root instead of paying for it with rapid damping`() {
        val macros = base + mapOf("MEDDLING" to 1f, "COMMUNE" to 0f)
        val free = OhmV2.probe(macros, seed = 731, companionExcitation = false)
        val coupled = OhmV2.probe(macros + ("COMMUNE" to 1f), seed = 731, companionExcitation = false)
        val kept = rms(coupled.foreground, .6, 1.0) / rms(free.foreground, .6, 1.0)
        println("OHM V2 coupled foreground retained $kept of uncoupled RMS")
        assertTrue(kept > .65, "the bridge erased the foreground: retained $kept")
    }

    @Test
    fun `reciprocal influence remains causal and solo bypass remains exact`() {
        val macros = base + mapOf("LENGTH" to .35f, "MEDDLING" to 1f)
        val free = OhmV2.probe(macros + ("COMMUNE" to 0f), seed = 731)
        val freeMuted = OhmV2.probe(macros + ("COMMUNE" to 0f), seed = 731, companionExcitation = false)
        assertContentEquals(free.foreground, freeMuted.foreground)
        val coupled = OhmV2.probe(macros + ("COMMUNE" to 1f), seed = 731)
        val muted = OhmV2.probe(macros + ("COMMUNE" to 1f), seed = 731, companionExcitation = false)
        var change = 0.0
        var energy = 0.0
        for (i in coupled.foreground.indices) {
            val diff = coupled.foreground[i].toDouble() - muted.foreground[i]
            change += diff * diff
            energy += muted.foreground[i].toDouble() * muted.foreground[i]
        }
        assertTrue(sqrt(change / energy) > .01, "companions changed only the output mix")
        val solo = macros + mapOf("MEDDLING" to 0f, "COMMUNE" to 0f)
        assertContentEquals(OhmV2.renderRaw(solo, seed = 731).samples,
            OhmV2.renderRaw(solo + ("COMMUNE" to 1f), seed = 731, gesture = OhmGesture.GATHER).samples)
    }

    @Test
    fun `register extremes and gathering retain pitch raw headroom and quiet natural endpoints`() {
        for (midi in listOf(48, 72)) for (gesture in OhmGesture.entries) {
            val macros = OhmV2.MACROS.associate { it.name to 1f }
            val probe = OhmV2.probe(macros, midi, seed = 731, gesture = gesture, recordPorts = false)
            val x = probe.snip.samples
            val pitch = Pitch.detect(probe.snip, .08f, .28f)
            assertTrue(pitch != null, "MIDI $midi $gesture lost its root")
            val cents = 1200 * ln(pitch.hz / Keys.midiHz(midi)) / ln(2.0)
            val tail = rms(x, probe.snip.durationSeconds - .33, probe.snip.durationSeconds - .17)
            val onset = rms(x, .03, .25)
            println("OHM V2 MIDI$midi $gesture pitch $cents cents, raw peak ${x.maxOf { abs(it) }}, state ${probe.maxState}, pre-fade tail/onset ${tail / onset}")
            assertTrue(abs(cents) < 12, "MIDI $midi $gesture pitch $cents cents")
            assertTrue(x.all { it.isFinite() } && x.maxOf { abs(it) } < .8f && probe.maxState < .9f)
            assertTrue(tail < .02 * onset, "the endpoint fade concealed a still-loud tail")
        }
    }

    @Test
    fun `explicit velocity remains audible after final melodic leveling`() {
        val soft = OhmV2.render(base, velocity = .3f, seed = 731)
        val firm = OhmV2.render(base, velocity = 1f, seed = 731)
        assertTrue(Loudness.of(soft) < .6 * Loudness.of(firm))
        assertTrue(OhmV2.render(base, velocity = 0f).samples.all { it == 0f })
    }
}
