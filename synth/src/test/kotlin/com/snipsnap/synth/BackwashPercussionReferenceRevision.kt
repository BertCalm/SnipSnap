package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** Listening candidate 3: reference-guided wooden contact, cowbell, mounted jingles and ride bell. Not a saved engine version. */
internal object BackwashPercussionReferenceRevision {
    fun render(voice: BackwashVoice, tune: Float, damp: Float, velocity: Float): Snip {
        require(!voice.legacy)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val decay = when (voice) {
            BackwashVoice.CROSS_STICK -> Dsp.lin(damp, .095f, .045f)
            BackwashVoice.TAMBOURINE -> Dsp.lin(damp, .65f, .4f)
            BackwashVoice.COWBELL -> Dsp.lin(damp, 1.5f, .75f)
            BackwashVoice.RIDE -> Dsp.lin(damp, 7.5f, 6f)
            else -> error("not a percussion voice")
        }
        val duration = if (voice == BackwashVoice.RIDE) 4.5f else decay + .25f
        val out = FloatArray((duration * rate).toInt())
        val excitation = FloatArray(out.size)
        val noise = Dsp.Noise(Dsp.seedFor("BACKWASH", "PERCUSSION", 3, voice.name))
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
            // A hand impact followed by loose jingle rebounds, separated enough to hear as rattle.
            var seconds = 0f
            for (hit in 0..15) {
                contact((seconds * rate).toInt(), (.65f + .35f * noise.next()) * exp(-seconds / .13f), .055f)
                seconds += .003f + .014f * (noise.next() + 1f) / 2f
            }
        } else {
            contact(0, 1f, if (voice == BackwashVoice.CROSS_STICK) .065f else .12f)
            // A quiet, delayed rim/body response adds crack without an electronic click train.
            if (voice == BackwashVoice.CROSS_STICK) contact((.0008f * rate).toInt(), .3f, .11f)
        }
        val frequencies = when (voice) {
            BackwashVoice.CROSS_STICK -> floatArrayOf(520f, 950f, 1560f, 2380f)
            BackwashVoice.COWBELL -> floatArrayOf(610f, 1233f, 1720f, 2470f, 3520f, 4360f, 4890f)
            BackwashVoice.TAMBOURINE -> FloatArray(72) { k -> (5600f + 225f * (k / 2) + 83f * sin(k * 1.7f)) * if (k % 2 == 0) 1f else 1.011f }
            // The reference is bell playing: emphasize the ~2.85 kHz bell with a quieter plate bed.
            BackwashVoice.RIDE -> floatArrayOf(2838f, 2854f, 671f, 881f, 1393f, 1927f, 3521f, 4289f,
                5327f, 5791f, 6397f, 7141f, 7883f, 8419f, 9361f, 10331f, 11717f, 12983f)
            else -> error("not a percussion voice")
        }
        for ((k, f) in frequencies.withIndex()) {
            val hz = f * pitch
            if (hz > 17000f) continue
            val t60 = when (voice) {
                BackwashVoice.TAMBOURINE -> (.045 + .065 * (1 + sin(k * 2.4))) / (1 + .004 * k)
                BackwashVoice.RIDE -> if (k < 2) decay.toDouble() else 1.8 / (1 + .04 * k)
                else -> decay / (1 + .08 * k)
            }
            val radius = exp(-6.907755 / (t60 * rate))
            val angle = 2 * PI * hz / rate
            val a = 2 * radius * cos(angle); val b = -radius * radius
            // The pole radius is strictly below unity. sin(angle) compensates low-frequency modal gain.
            val weight = (when {
                voice == BackwashVoice.COWBELL -> if (k == 0) 1.0 else if (k == 1) .45 else .3 / sqrt(1.0 + k)
                voice == BackwashVoice.CROSS_STICK -> .35 / (1 + .3 * k)
                voice == BackwashVoice.RIDE -> if (k < 2) 2.0 else .12 / (1 + .03 * k)
                voice == BackwashVoice.TAMBOURINE -> .5
                else -> .65 / sqrt(1.0 + k)
            }) /
                (1 + (1 - velocity) * .24 * k)
            val gain = sin(angle) * weight
            var y1 = 0.0; var y2 = 0.0
            for (i in out.indices) {
                // Stagger jingle-pair contact by a few ms instead of locking every disc together.
                val lag = if (voice == BackwashVoice.TAMBOURINE) (k % 7) * rate / 1800 else 0
                val y = gain * excitation.getOrElse(i - lag) { 0f } + a * y1 + b * y2
                y2 = y1; y1 = y; out[i] += y.toFloat()
            }
        }
        val low = Dsp.FixedOnePole(if (voice == BackwashVoice.CROSS_STICK) 450f else if (voice == BackwashVoice.TAMBOURINE) 5500f else 2400f, rate)
        val high = Dsp.FixedOnePole((if (voice == BackwashVoice.CROSS_STICK) 3800f else 9000f + 6500f * velocity) * pitch, rate)
        for (i in out.indices) {
            val t = i.toDouble() / rate
            val noiseSample = noise.next()
            val grain = high.lp(noiseSample - low.lp(noiseSample))
            val wash = when (voice) {
                BackwashVoice.RIDE -> .00015 * (1 - exp(-t / .003)) * exp(-6.907755 * t / decay)
                BackwashVoice.TAMBOURINE -> .0015 * exp(-t / .08) * (1 + .65 * sin(2 * PI * 67 * t + 2 * sin(2 * PI * 19 * t)))
                BackwashVoice.CROSS_STICK -> .0045 * exp(-t / .0025)
                else -> .0008 * exp(-t / .0018)
            }
            out[i] += (grain * wash * (.6 + .4 * velocity)).toFloat()
        }
        // End the long listening render gently rather than chopping a live bell mode.
        if (voice == BackwashVoice.RIDE) {
            val fade = (rate * .35).toInt()
            for (i in 0 until fade) out[out.size - fade + i] *= (.5 + .5 * cos(PI * i / (fade - 1))).toFloat()
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
