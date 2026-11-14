package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/**
 * Revision-two contact studies, deliberately separate from the replayable v1 kit.
 * Gesture laws and modal weights are design hypotheses, not measured instrument fits.
 * No sample recordings, frequency sweeps, or feedback solvers are used.
 */
object PatioCharacter {
    const val VERSION = 1
    enum class Voice { OPEN, SLAP, SCRAPER, SEEDS, BUZZ_BAR }

    fun render(voice: Voice, tune: Float = .5f, touch: Float = .6f, hold: Float = .45f,
               seed: Int = Dsp.seedFor("PATIO_CHARACTER", voice)): Snip {
        require(listOf(tune, touch, hold).all { it.isFinite() && it in 0f..1f })
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val duration = .5 + .7 * hold
        val size = (duration * rate).toInt()
        val drive = FloatArray(size)
        val noise = Dsp.Noise(seed)
        // A finite contact has a width and a surface texture. A slap is several
        // finger contacts, not an open drum with a brighter EQ and shorter decay.
        fun contact(at: Double, width: Double, gain: Double, roughness: Double) {
            val start = (at * rate).toInt()
            val frames = max(4, (width * rate).toInt())
            for (j in 0 until frames) {
                val i = start + j
                if (i >= size) break
                val w = sin(PI * j / frames).pow(2)
                drive[i] += (gain * w * ((1 - roughness) + roughness * noise.next()) / frames).toFloat()
            }
        }
        val hz: Float
        val modes: List<Modes.Mode>
        when (voice) {
            Voice.OPEN, Voice.SLAP -> {
                val slap = voice == Voice.SLAP
                hz = Dsp.expMap(tune, 135f, 330f)
                contact(.003, if (slap) .0007 else .0035 - .002 * touch, 1.0, if (slap) .8 else .18)
                if (slap) repeat(4) { finger ->
                    contact(.0045 + finger * .0014, .0004 + .0004 * (1 - touch), .65, .9)
                }
                val ratios = floatArrayOf(1f, 1.593f, 2.136f, 2.296f, 2.653f, 2.918f, 3.598f, 4.06f)
                modes = ratios.mapIndexed { i, r ->
                    val gain = if (slap) (if (i == 0) .08f else 1f / sqrt(i.toFloat())) else 1f / (1f + i * .7f)
                    val decay = if (slap) (.075f + .16f * hold) else (.36f + .55f * hold)
                    // Remaining palm contact damps the low mode of the slap;
                    // open strokes retain a long fundamental beneath short skin modes.
                    Modes.Mode(r, gain * r, decay / (1f + i * .3f))
                }
            }
            Voice.SCRAPER, Voice.SEEDS -> {
                val scrape = voice == Voice.SCRAPER
                hz = if (scrape) Dsp.expMap(tune, 650f, 1600f) else Dsp.expMap(tune, 1800f, 4200f)
                val active = .13 + .52 * hold
                var t = .004
                while (t < active) {
                    val phase = t / active
                    // Scraping accelerates across ribs then slows; shaking has
                    // two collision clouds at reversals, with a quiet middle.
                    val envelope = if (scrape) sin(PI * phase).pow(.5)
                        else exp(-((phase - .22) / .14).pow(2)) + .8 * exp(-((phase - .72) / .16).pow(2))
                    val density = if (scrape) 45 + 110 * touch * sin(PI * phase) else 120 + (350 + 800 * touch) * envelope
                    contact(t, if (scrape) .0018 - .001 * touch else .0008 - .0005 * touch,
                        envelope * (.5 + .5 * (noise.next() + 1) / 2), if (scrape) .72 else 1.0)
                    t += (if (scrape) 1 + .25 * noise.next() else .2 + 1.6 * (noise.next() + 1) / 2) / density
                }
                val ratios = if (scrape) floatArrayOf(1f, 1.83f, 3.17f) else floatArrayOf(1f, 1.37f, 2.19f, 3.07f)
                modes = ratios.mapIndexed { i, r -> Modes.Mode(r, r / (1f + i), if (scrape) .009f else .004f) }
            }
            Voice.BUZZ_BAR -> {
                hz = Dsp.expMap(tune, 155f, 440f)
                contact(.003, .0025 - .0015 * touch, 1.0, .08)
                modes = listOf(Modes.Mode(1f, 1f, .7f + hold), Modes.Mode(4f, 1.8f, .18f + .2f * hold),
                    Modes.Mode(9.2f, 1.1f, .065f), Modes.Mode(15.8f, .5f, .025f))
            }
        }
        val body = Modes.ring(drive, hz, modes, rate)
        Dsp.normalize(body, .8f)
        val contactPeak = drive.maxOf { abs(it) }.coerceAtLeast(1e-9f)
        if (voice == Voice.BUZZ_BAR) {
            // Bar pressure feeds a separate air resonance. Its amplitude opens
            // a smooth buzz threshold, so the buzz disappears before the note.
            val cavity = Modes.ring(body, hz, listOf(Modes.Mode(.995f, 1f, .075f)), rate)
            Dsp.normalize(cavity, .8f)
            for (i in body.indices) {
                val x = cavity[i].toDouble()
                val gate = max(0.0, abs(x) - (.20 - .10 * touch))
                val buzz = tanh(16 * x) * gate
                body[i] = (.60 * body[i] + .20 * x + .65 * buzz).toFloat()
            }
        } else {
            val grit = when (voice) { Voice.SLAP -> .28f; Voice.SCRAPER -> .55f; Voice.SEEDS -> .85f; else -> .09f }
            for (i in body.indices) body[i] = (1 - grit) * body[i] + grit * drive[i] / contactPeak
        }
        Tide.bandLimit(body, rate)
        val out = PatioWear.finish(Snip(Dsp.decimate(body, Dsp.RATE), 1, Dsp.RATE))
        Dsp.levelTo(out.samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out.samples)
        return out
    }
}
