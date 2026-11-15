package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** Finite scraper and shaker studies. Event schedules are gesture analogies, not particle simulation. */
object PatioTexture {
    enum class Voice { SCRAPE, SEEDS }
    data class Settings(val tune: Float = .5f, val touch: Float = .5f, val hold: Float = .3f) {
        init { require(listOf(tune, touch, hold).all { it.isFinite() && it in 0f..1f }) }
    }
    data class Contact(val seconds: Double, val amplitude: Float)

    /** The same event plan at every sample rate; no render-loop random draws. */
    internal fun contacts(voice: Voice, m: Settings, seed: Int): List<Contact> {
        val noise = Dsp.Noise(seed)
        val duration = .09 + .70 * m.hold
        val result = mutableListOf<Contact>()
        var t = .003
        while (t < duration) {
            val phase = t / duration
            val shape = sin(PI * phase).pow(.65)
            val speed = if (voice == Voice.SCRAPE) 110.0 + 390.0 * m.touch else 210.0 + 500.0 * m.touch
            val density = speed * (.25 + .75 * shape)
            val jitter = if (voice == Voice.SCRAPE) 1.0 + .12 * noise.next() else .25 + 1.5 * (noise.next() + 1.0) * .5
            // More contacts share the gesture's energy; none is normalized independently.
            val energy = sqrt(250.0 / speed) * shape * (.65 + .35 * (noise.next() + 1.0) * .5)
            result += Contact(t, energy.toFloat())
            t += jitter / density
        }
        return result
    }

    fun render(voice: Voice, m: Settings, seed: Int): Snip {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val seconds = .09 + .70 * m.hold + .13
        val excitation = FloatArray(ceil(seconds * rate).toInt())
        val noise = Dsp.Noise(Dsp.seedFor(seed, "surface"))
        val width = (rate * (.00048 - .00028 * m.touch)).roundToInt().coerceAtLeast(4)
        for (contact in contacts(voice, m, seed)) {
            val start = (contact.seconds * rate).roundToInt()
            val grain = .15f + .25f * m.touch
            for (j in 0 until width) {
                val i = start + j
                if (i >= excitation.size) break
                val pulse = (.5 - .5 * cos(2.0 * PI * j / (width - 1))).toFloat()
                excitation[i] += contact.amplitude * pulse * (1f + grain * noise.next()) / width
            }
        }
        // Shape tables, not measured guacharaca/guache spectra. Ring times are deliberately short.
        val ratios = if (voice == Voice.SCRAPE) floatArrayOf(1f, 1.71f, 2.83f, 4.19f)
            else floatArrayOf(1f, 1.43f, 2.37f, 3.91f, 5.17f)
        val hz = Dsp.expMap(m.tune, if (voice == Voice.SCRAPE) 480f else 850f,
            if (voice == Voice.SCRAPE) 1_400f else 2_300f)
        val modes = ratios.mapIndexed { i, r -> Modes.Mode(r, 1f / (1f + i),
            (if (voice == Voice.SCRAPE) .025f else .045f) / (1f + i * .35f)) }
        val body = Modes.ring(excitation, hz, modes, rate)
        Dsp.normalize(body)
        // Retain broadband contact detail; otherwise a scraper becomes a little pitched ratchet.
        val peak = excitation.maxOf { abs(it) }.coerceAtLeast(1e-9f)
        for (i in body.indices) body[i] = .65f * body[i] + .20f * excitation[i] / peak
        Tide.bandLimit(body, rate)
        val out = Dsp.decimate(body, Dsp.RATE)
        val clean = PatioWear.finish(Snip(out, 1, Dsp.RATE))
        Dsp.levelTo(clean.samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(clean.samples)
        return clean
    }
}
