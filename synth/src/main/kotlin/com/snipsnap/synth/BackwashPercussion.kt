package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** Four contrasting acoustic gestures. Frequencies/decays are design values, not measured instruments. */
internal object BackwashPercussion {
    fun render(voice: BackwashVoice, tune: Float, damp: Float, velocity: Float): Snip {
        require(!voice.legacy)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val decay = when (voice) {
            BackwashVoice.CROSS_STICK -> Dsp.lin(damp, .12f, .025f)
            BackwashVoice.TAMBOURINE -> Dsp.lin(damp, .64f, .14f)
            BackwashVoice.COWBELL -> Dsp.lin(damp, .60f, .13f)
            BackwashVoice.RIDE -> Dsp.lin(damp, .78f, .28f)
            else -> error("not a percussion voice")
        }
        val out = FloatArray(((decay + .04f) * rate).toInt())
        val excitation = FloatArray(out.size)
        val noise = Dsp.Noise(Dsp.seedFor("BACKWASH", "PERCUSSION", 1, voice.name))
        val pitch = Dsp.expMap(tune, .72f, 1.4f)
        fun contact(start: Int, strength: Float, millis: Float) {
            val count = (millis * (.65f + .7f * (1f - velocity)) * rate / 1000).toInt().coerceAtLeast(4)
            for (i in 0 until count) {
                val index = start + i
                if (index < excitation.size) excitation[index] += strength * sin(PI * i / count).pow(2).toFloat() *
                    (.55f + .45f * noise.next()) / count
            }
        }
        if (voice == BackwashVoice.TAMBOURINE) {
            // A single hand hit drives a loose train of secondary jingle collisions.
            for (hit in 0..8) {
                val seconds = if (hit == 0) 0f else (hit * .009f + .003f * noise.next()) * (1.2f - .65f * damp)
                contact((seconds * rate).toInt(), exp(-hit * .24f), .12f)
            }
        } else contact(0, 1f, if (voice == BackwashVoice.CROSS_STICK) .45f else .18f)
        val frequencies = when (voice) {
            BackwashVoice.CROSS_STICK -> floatArrayOf(920f, 1710f, 2480f, 3840f)
            BackwashVoice.COWBELL -> floatArrayOf(560f, 845f, 1510f, 2030f, 2740f, 3910f, 5280f)
            BackwashVoice.TAMBOURINE -> FloatArray(24) { k -> (2800f + 370f * (k / 2)) * if (k % 2 == 0) 1f else 1.017f }
            BackwashVoice.RIDE -> floatArrayOf(430f, 683f, 997f, 1439f, 1903f, 2341f, 2917f, 3541f,
                4219f, 4903f, 5651f, 6419f, 7219f, 8059f, 8923f, 9829f, 10831f, 11939f)
            else -> error("not a percussion voice")
        }
        for ((k, f) in frequencies.withIndex()) {
            val hz = f * pitch
            if (hz > 17000f) continue
            val t60 = decay / (1 + (if (voice == BackwashVoice.RIDE) .045 else .11) * k)
            val radius = exp(-6.907755 / (t60 * rate))
            val angle = 2 * PI * hz / rate
            val a = 2 * radius * cos(angle); val b = -radius * radius
            // The pole radius is strictly below unity. sin(angle) compensates low-frequency modal gain.
            val weight = (if (voice == BackwashVoice.COWBELL && k < 2) 1.0 else .65 / sqrt(1.0 + k)) /
                (1 + (1 - velocity) * .24 * k)
            val gain = sin(angle) * weight
            var y1 = 0.0; var y2 = 0.0
            for (i in out.indices) {
                val y = gain * excitation[i] + a * y1 + b * y2
                y2 = y1; y1 = y; out[i] += y.toFloat()
            }
        }
        val low = Dsp.FixedOnePole(if (voice == BackwashVoice.CROSS_STICK) 700f else 2400f, rate)
        val high = Dsp.FixedOnePole((5500f + 6500f * velocity) * pitch, rate)
        for (i in out.indices) {
            val t = i.toDouble() / rate
            val noiseSample = noise.next()
            val grain = high.lp(noiseSample - low.lp(noiseSample))
            val wash = when (voice) {
                BackwashVoice.RIDE -> .00055 * (1 - exp(-t / .003)) * exp(-6.907755 * t / decay)
                BackwashVoice.TAMBOURINE -> .00009 * exp(-6.907755 * t / decay)
                else -> .0008 * exp(-t / .0018)
            }
            out[i] += (grain * wash * (.6 + .4 * velocity)).toFloat()
        }
        Tide.bandLimit(out, rate)
        val samples = Dsp.decimate(out, rate)
        val mean = samples.average().toFloat(); val dc = Dsp.FixedOnePole(20f, Dsp.RATE)
        for (i in samples.indices) { val x = samples[i] - mean; samples[i] = x - dc.lp(x) }
        Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(samples)
        return Snip(samples, 1, Dsp.RATE)
    }
}
