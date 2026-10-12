package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Measurements shared by the TANGLE acceptance tests and the actual Kotlin audition renders. */
internal object TangleMeasure {
    val drumClasses = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_OPEN, DrumClass.HAT_CLOSED, DrumClass.TOM)

    fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    /** Removes gain as an explanation for a changed control, while preserving causal phase movement. */
    fun distance(a: FloatArray, b: FloatArray): Double {
        val count = minOf(a.size, b.size)
        val ra = rms(a).coerceAtLeast(1e-12)
        val rb = rms(b).coerceAtLeast(1e-12)
        var difference = 0.0
        for (i in 0 until count) {
            val d = a[i] / ra - b[i] / rb
            difference += d * d
        }
        return sqrt(difference / count.coerceAtLeast(1))
    }

    fun spectrum(samples: FloatArray, rate: Int = Dsp.RATE): DoubleArray {
        val bands = DoubleArray(12)
        val size = 8192
        val start = minOf((rate * 0.15).toInt(), (samples.size - size).coerceAtLeast(0))
        val mag = Fft.magnitudeSpectrum(samples.copyOfRange(start, minOf(samples.size, start + size)), size)
        for (bin in 1 until mag.size) {
            val hz = bin.toDouble() * rate / size
            val band = (ln(hz.coerceAtLeast(30.0) / 30.0) / ln(2.0) * 1.5).toInt().coerceIn(0, bands.lastIndex)
            bands[band] += mag[bin].toDouble() * mag[bin]
        }
        val sum = bands.sum().coerceAtLeast(1e-30)
        return DoubleArray(bands.size) { bands[it] / sum }
    }

    /** Total variation between normalized power spectra: 0 is equal, 1 is disjoint. */
    fun spectralDistance(a: FloatArray, b: FloatArray): Double {
        val sa = spectrum(a)
        val sb = spectrum(b)
        return sa.indices.sumOf { abs(sa[it] - sb[it]) } / 2
    }

    /** Coherent tone power at the requested root, independently of the nearby-peak search. */
    fun toneShare(samples: FloatArray, hz: Double, rate: Int = Dsp.RATE): Double {
        val from = minOf((.12 * rate).toInt(), samples.size)
        val to = minOf(samples.size, from + (.4 * rate).toInt())
        var re = 0.0
        var im = 0.0
        var energy = 0.0
        for (i in from until to) {
            val sample = samples[i].toDouble()
            re += sample * cos(2 * PI * hz * i / rate)
            im += sample * sin(2 * PI * hz * i / rate)
            energy += sample * sample
        }
        return (2 * (re * re + im * im) / ((to - from) * energy).coerceAtLeast(1e-30)).coerceIn(0.0, 1.0)
    }

    fun metrics(raw: FloatArray, snip: Snip, midi: Int, renderMs: Double): Map<String, Any?> {
        val classification = Classifier.classify(snip)
        val pitch = Pitch.detect(snip, fromSec = 0.12f, windowSec = 0.3f)
        val wanted = Keys.midiHz(midi)
        val fundamental = if (snip.durationSeconds > 0.45f) {
            FineTuning.measuredHz(snip, wanted, fromSec = 0.12f, bodySeconds = 0.4f)
        } else null
        return linkedMapOf(
            "rawPeak" to raw.maxOfOrNull { abs(it).toDouble() },
            "rawRms" to rms(raw),
            "rawDc" to raw.average(),
            "peak" to snip.peak(),
            "rms" to rms(snip.samples),
            "dc" to snip.samples.average(),
            "finite" to (raw.all { it.isFinite() } && snip.samples.all { it.isFinite() }),
            "durationSeconds" to snip.durationSeconds,
            "spectralCentroidHz" to classification.features.centroidHz,
            "pitchHz" to pitch?.hz,
            "pitchConfidence" to pitch?.confidence,
            "detectorErrorCents" to pitch?.let { FineTuning.cents(it.hz.toDouble(), wanted.toDouble()) },
            "fundamentalHz" to fundamental,
            "requestedRootHz" to wanted,
            "requestedRootEnergyShare" to toneShare(snip.samples, wanted.toDouble()),
            "octaveDownEnergyShare" to toneShare(snip.samples, wanted.toDouble() / 2),
            "fundamentalErrorCents" to fundamental?.let { FineTuning.cents(it, wanted.toDouble()) },
            "fundamentalMeasurement" to "strongest spectral component within one semitone of requested pitch; independent of house autocorrelation detector",
            "pitchDetectorRange" to "40–1200 Hz; null is expected below MIDI 28 or for weakly periodic extreme textures",
            "classifier" to classification.drumClass.name,
            "classifierConfidence" to classification.confidence,
            "renderMs" to renderMs,
        )
    }
}
