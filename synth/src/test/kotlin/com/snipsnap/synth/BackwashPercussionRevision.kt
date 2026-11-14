package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** Listening candidate 2: sharper contact, larger bell, loose jingles and long plate ring. Not a saved engine version. */
internal object BackwashPercussionRevision {
    fun render(voice: BackwashVoice, tune: Float, damp: Float, velocity: Float): Snip {
        require(!voice.legacy)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val decay = when (voice) {
            BackwashVoice.CROSS_STICK -> Dsp.lin(damp, .24f, .09f)
            BackwashVoice.TAMBOURINE -> Dsp.lin(damp, .95f, .55f)
            BackwashVoice.COWBELL -> Dsp.lin(damp, 1.9f, .85f)
            BackwashVoice.RIDE -> Dsp.lin(damp, 3.8f, 2.2f)
            else -> error("not a percussion voice")
        }
        val out = FloatArray(((decay + .25f) * rate).toInt())
        val excitation = FloatArray(out.size)
        val noise = Dsp.Noise(Dsp.seedFor("BACKWASH", "PERCUSSION", 2, voice.name))
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
            for (hit in 0..19) {
                contact((seconds * rate).toInt(), (.65f + .35f * noise.next()) * exp(-seconds / .38f), .055f)
                seconds += .014f + .022f * (noise.next() + 1f) / 2f
            }
        } else {
            contact(0, 1f, if (voice == BackwashVoice.CROSS_STICK) .065f else .12f)
            // A quiet, delayed rim/body response adds crack without an electronic click train.
            if (voice == BackwashVoice.CROSS_STICK) contact((.0008f * rate).toInt(), .3f, .11f)
        }
        val frequencies = when (voice) {
            BackwashVoice.CROSS_STICK -> floatArrayOf(740f, 1350f, 2180f, 3370f, 4850f, 6400f)
            BackwashVoice.COWBELL -> floatArrayOf(355f, 538f, 970f, 1390f, 1930f, 2780f, 3970f)
            BackwashVoice.TAMBOURINE -> FloatArray(24) { k -> (2800f + 370f * (k / 2)) * if (k % 2 == 0) 1f else 1.017f }
            // Dense, slightly split modes beat against one another as the plate rings.
            BackwashVoice.RIDE -> FloatArray(64) { k ->
                (610f + 96f * (k / 2).toFloat().pow(1.32f)) * if (k % 2 == 0) 1f else 1.006f
            }
            else -> error("not a percussion voice")
        }
        for ((k, f) in frequencies.withIndex()) {
            val hz = f * pitch
            if (hz > 17000f) continue
            val t60 = when (voice) {
                BackwashVoice.TAMBOURINE -> (.13 + .12 * (1 + sin(k * 2.4))) / (1 + .015 * k)
                BackwashVoice.RIDE -> decay / (1 + .009 * k)
                else -> decay / (1 + .08 * k)
            }
            val radius = exp(-6.907755 / (t60 * rate))
            val angle = 2 * PI * hz / rate
            val a = 2 * radius * cos(angle); val b = -radius * radius
            // The pole radius is strictly below unity. sin(angle) compensates low-frequency modal gain.
            val weight = (when {
                voice == BackwashVoice.COWBELL && k < 2 -> 1.0
                voice == BackwashVoice.CROSS_STICK -> .8 / (1 + .08 * k)
                voice == BackwashVoice.RIDE -> .45 / (1 + .012 * k)
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
        val low = Dsp.FixedOnePole(if (voice == BackwashVoice.CROSS_STICK) 700f else 2400f, rate)
        val high = Dsp.FixedOnePole((5500f + 6500f * velocity) * pitch, rate)
        for (i in out.indices) {
            val t = i.toDouble() / rate
            val noiseSample = noise.next()
            val grain = high.lp(noiseSample - low.lp(noiseSample))
            val wash = when (voice) {
                BackwashVoice.RIDE -> .00015 * (1 - exp(-t / .003)) * exp(-6.907755 * t / decay)
                BackwashVoice.TAMBOURINE -> .00009 * exp(-6.907755 * t / decay)
                BackwashVoice.CROSS_STICK -> .0025 * exp(-t / .003)
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
