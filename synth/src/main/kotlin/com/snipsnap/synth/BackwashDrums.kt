package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** Dedicated dry kit. Ratios, weights and losses are voicing hypotheses, not measured drums.
 * A finite contact excites uneven head/shell modes; wire energy follows head motion.
 * All poles have radius below one. No room, chorus or saturation is baked into this source.
 */
internal object BackwashDrums {
    fun render(voice: BackwashVoice, tune: Float, damp: Float, velocity: Float): Snip {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val hat = voice == BackwashVoice.HAT_OPEN || voice == BackwashVoice.HAT_CLOSED
        val root = when (voice) {
            BackwashVoice.KICK -> Dsp.expMap(tune, 48f, 86f)
            BackwashVoice.SNARE -> Dsp.expMap(tune, 125f, 235f)
            BackwashVoice.TOM -> Dsp.expMap(tune, 85f, 190f)
            else -> Dsp.expMap(tune, 1800f, 3200f)
        }
        val decay = when (voice) {
            BackwashVoice.KICK -> Dsp.lin(damp, .60f, .28f)
            BackwashVoice.SNARE -> Dsp.lin(damp, .60f, .24f)
            BackwashVoice.TOM -> Dsp.lin(damp, .70f, .30f)
            BackwashVoice.HAT_CLOSED -> Dsp.lin(damp, .22f, .065f)
            BackwashVoice.HAT_OPEN -> Dsp.lin(damp, .62f, .20f)
            else -> error("new percussion uses BackwashPercussion")
        }
        val n = ((decay + .06f) * rate).toInt()
        val noise = Dsp.Noise(Dsp.seedFor("BACKWASH", "DRUMS", 3, voice.name))
        val contactMs = if (voice == BackwashVoice.KICK) 1.6f else if (hat) .08f else .35f
        val contact = (contactMs * (.7f + .8f * (1f - velocity)) * rate / 1000).toInt().coerceAtLeast(4)
        val excitation = FloatArray(n)
        for (i in 0 until contact) {
            val window = sin(PI * i / contact).pow(2).toFloat()
            excitation[i] = window * ((if (hat) .15f else .8f) + .35f * noise.next()) / contact
        }
        // Split near-neighbours represent unequal head tension and the second head.
        // They beat briefly while upper modes lose energy faster under the cloth.
        val ratios = if (hat) floatArrayOf(1f, 1.29f, 1.67f, 2.11f, 2.49f, 3.07f, 3.83f, 4.17f, 4.91f, 5.37f, 6.11f, 7.23f, 8.13f, 9.41f, 10.19f, 12.31f)
            else floatArrayOf(1f, 1.037f, 1.51f, 1.63f, 2.07f, 2.19f, 2.73f, 3.16f, 3.91f, 4.57f, 5.23f, 6.31f)
        val body = FloatArray(n)
        for ((k, ratio) in ratios.withIndex()) {
            val hz = root * ratio
            if (hz > 17000f) continue
            val seconds = decay / (1f + (if (hat) .045f else .04f + .06f * damp) * k)
            val radius = exp(-6.907755 / (seconds * rate))
            val angle = 2.0 * PI * hz / rate
            val a = 2.0 * radius * cos(angle)
            val b = -radius * radius
            val weight = if (hat) .22 / sqrt(1.0 + .1 * k) else when (k) { 0 -> .46; 1 -> .17; else -> .75 / (1 + .08 * k) }
            val touch = 1.0 / (1 + (1 - velocity) * k * .18)
            // sin(theta) cancels resonator low-frequency gain; no per-mode normalization.
            val gain = sin(angle) * weight * touch * (if (hat) 5.0 else 2.0)
            var y1 = 0.0; var y2 = 0.0
            for (i in body.indices) {
                val y = gain * excitation[i] + a * y1 + b * y2
                y2 = y1; y1 = y
                body[i] += y.toFloat()
            }
        }
        // The beater/stick has a broadband skin response distinct from the pitched head.
        // Previous revisions nearly removed this band, especially from kick and tom.
        val skinLow = Dsp.FixedOnePole(if (voice == BackwashVoice.KICK) 260f else 450f, rate)
        val skinHigh = Dsp.FixedOnePole((if (voice == BackwashVoice.KICK) 2600f else 6500f) *
            (.45f + .55f * velocity), rate)
        val wood = Dsp.Biquad().apply { bandpass(if (voice == BackwashVoice.KICK) 650f else 1450f, .8f, rate) }
        val wireLow = Dsp.FixedOnePole(1600f, rate)
        val wireHigh = Dsp.FixedOnePole(10000f, rate)
        val surface = Dsp.FixedOnePole(4000f, rate)
        val cymbalTop = Dsp.FixedOnePole(9500f, rate)
        val cymbalTop2 = Dsp.FixedOnePole(9500f, rate)
        var energy = 0f
        val releaseSeconds = if (voice == BackwashVoice.SNARE) .035 + .025 * (1 - damp) else .008
        val release = exp(-1.0 / (releaseSeconds * rate)).toFloat()
        for (i in body.indices) {
            val t = i.toDouble() / rate
            val rnd = noise.next()
            energy = max(abs(body[i]), energy * release)
            val wire = wireHigh.lp(rnd - wireLow.lp(rnd))
            val skin = skinHigh.lp(rnd - skinLow.lp(rnd))
            val knock = wood.process(rnd)
            // Cloth reduces the ring more than the initial skin slap. A short two-stage
            // decay preserves the first 15 ms instead of simply low-passing the whole hit.
            val attackDecay = if (voice == BackwashVoice.KICK) .018 else .014
            val slap = exp(-t / attackDecay).toFloat()
            val grain = (skin + .7f * knock) * slap * (.65f + .35f * velocity)
            val texture = when {
                voice == BackwashVoice.SNARE -> wire * energy * (3.5f + 2f * velocity) + grain * .8f
                hat -> cymbalTop2.lp(cymbalTop.lp(rnd - surface.lp(rnd))) * exp(-6.907755 * t / decay).toFloat() * .85f
                voice == BackwashVoice.KICK -> grain * 5.0f
                else -> grain * 1.8f
            }
            body[i] += texture * min(1f, i.toFloat() / (rate * .0004f))
        }
        Tide.bandLimit(body, rate)
        val out = Dsp.decimate(body, rate)
        val dc = Dsp.FixedOnePole(20f, Dsp.RATE)
        val mean = out.average().toFloat()
        for (i in out.indices) { val x = out[i] - mean; out[i] = x - dc.lp(x) }
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, 1, Dsp.RATE)
    }
}
