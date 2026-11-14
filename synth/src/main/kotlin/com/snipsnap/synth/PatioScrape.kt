package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** Reference-guided bright scrape; gesture constants are authored, not fitted rib geometry. */
object PatioScrape {
    const val VERSION = 3
    enum class Direction { FORWARD, RETURN }
    fun render(direction: Direction, touch: Float = .6f, hold: Float = .45f,
               seed: Int = Dsp.seedFor("PATIO_SCRAPE", direction), modelVersion: Int = VERSION): Snip {
        require(listOf(touch, hold).all { it.isFinite() && it in 0f..1f })
        require(modelVersion in 1..VERSION)
        val bold = modelVersion >= 3
        val caughtReturn = modelVersion >= 2 && direction == Direction.RETURN
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val duration = (.12 + .42 * hold) * (if (bold && direction == Direction.RETURN) .80 else if (caughtReturn) .44 else if (direction == Direction.RETURN) .72 else 1.0)
        val raw = FloatArray(((duration + .07) * rate).toInt())
        val noise = Dsp.Noise(seed)
        var time = .003
        while (time < duration) {
            val phase = time / duration
            // The return lifts pressure quickly and slows into separated catches.
            // This is an authored hand gesture, not an inherent direction property.
            val pressure = if (bold) {
                if (direction == Direction.FORWARD) .8 * exp(-phase / .045) + sin(PI * phase).pow(.45) * (.8 + .6 * phase)
                else sin(PI * phase).pow(.45) * (1.2 - .5 * phase)
            } else if (caughtReturn) (1 - exp(-70 * phase)) * exp(-3.5 * phase) else sin(PI * phase).pow(.55)
            val speed = if (bold) {
                if (direction == Direction.FORWARD) 110 + (300 + 110 * touch) * phase.pow(.7)
                else 300 + 90 * touch - 190 * phase
            } else if (caughtReturn) 65 + (160 + 80 * touch) * (1 - phase).pow(2) else 120 + (200 + 100 * touch) * sin(PI * phase)
            // Three prongs cross each rib with small offsets. Noise contacts are
            // bipolar, avoiding the old positive pulses' dominant bass component.
            repeat(if (bold) 4 else 3) { prong ->
                val at = ((time + prong * .00019) * rate).toInt()
                val width = (rate * (.0008 - .00035 * touch)).toInt()
                for (j in 0 until width) {
                    if (at + j < raw.size) raw[at + j] += (noise.next() * pressure * sin(PI * j / width).pow(2) / sqrt(3.0)).toFloat()
                }
            }
            time += (1 + .32 * noise.next()) / speed
        }
        // Broad shell resonances add contact weight, not a sustained tuned note.
        // Normalize paths separately before balancing to avoid low modes taking over.
        val body = if (bold) {
            val wood = Dsp.Biquad().apply { bandpass(850f, 1.1f, rate) }
            val edge = Dsp.Biquad().apply { bandpass(1450f, .8f, rate) }
            FloatArray(raw.size) { i -> .65f * wood.process(raw[i]) + .35f * edge.process(raw[i]) }
        } else null
        val mid = Dsp.Biquad().apply { bandpass(if (direction == Direction.FORWARD) 2900f else 3400f, 1.1f, rate) }
        val high = Dsp.Biquad().apply { bandpass(6200f, .9f, rate) }
        val lowCut = Dsp.Biquad().apply { bandpass(4000f, .45f, rate) }
        for (i in raw.indices) raw[i] = lowCut.process(.72f * mid.process(raw[i]) + .48f * high.process(raw[i]))
        if (body != null) {
            Dsp.normalize(raw, .8f); Dsp.normalize(body, .8f)
            for (i in raw.indices) raw[i] = .82f * raw[i] + (.55f + .20f * touch) * body[i]
        }
        Tide.bandLimit(raw, rate)
        val out = PatioWear.finish(Snip(Dsp.decimate(raw, Dsp.RATE), 1, Dsp.RATE))
        Dsp.levelTo(out.samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out.samples)
        return out
    }
}
