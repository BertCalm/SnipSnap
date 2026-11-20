package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * The touch of ARCO's and BORE's candidate voices (rounds voices-arco-1 and voices-bore-1, 20 Nov 2026). The owner kept every
 * candidate's range clip and said of every soft, medium, hard clip "All sound the same": the touch moved only the model's bow or
 * breath, the engine then levelled every note to one loudness, and soft and hard were 1.2 to 2.7 dB apart by level-matched
 * third-octave shape. LATTICE's candidates had the same note and the same kind of answer (a lean of the source around the kept touch).
 *
 * A real player's lighter bow or softer breath is quieter, darker and slower to speak: a light bow far from the bridge (flautando)
 * and a soft breath both leave mostly the fundamental. So a candidate played under its default BOW (ARCO) or BREATH (BORE) is
 * finished by [touched]: [depth] is how far under the default it is, as a share of the default (the held keys' soft layer is
 * at 0.55 to 0.72). With depth u the note
 *  - loses its upper partials: a shelf above [Lean.cornerRatio] times the note, [Lean.shelfDbPerDepth] times u deep, made as the
 *    dry note blended toward its own two-pole low-pass (so u 0 is the note itself);
 *  - is quieter by [Lean.levelDbPerDepth] times u (never levelled back);
 *  - speaks more slowly: a raised-cosine rise of [Lean.riseSecondsPerDepth] times u over the start.
 * At the default and above, the note is not touched at all, so what was kept is what plays.
 */
internal object CandidateTouch {

    class Lean(
        val shelfDbPerDepth: Float,
        val levelDbPerDepth: Float,
        val riseSecondsPerDepth: Float,
        val cornerRatio: Float,
    )

    /** Under this depth the note is the kept one (a share computed in Float can land a hair under the default). */
    const val MIN_DEPTH = 1e-3f

    /** How far [value] is under [default], as a share of [default]; 0 at the default and above. */
    fun depth(value: Float, default: Float): Float = ((default - value) / default).coerceIn(0f, 1f)

    /** [x] (at [rate]) as played [depth] under the default by a [lean]: in place; untouched under [MIN_DEPTH]. */
    fun touched(x: FloatArray, hz: Float, depth: Float, lean: Lean, rate: Int = Dsp.RATE): FloatArray {
        if (!(depth >= MIN_DEPTH)) return x
        val keep = 10.0.pow(-lean.shelfDbPerDepth * depth / 20.0)
        val blend = 1.0 - keep
        val corner = min(hz * lean.cornerRatio, 0.45f * rate)
        val k = exp(-2.0 * PI * corner / rate)
        var a = 0.0
        var b = 0.0
        val gain = 10.0.pow(-lean.levelDbPerDepth * depth / 20.0)
        val riseN = (lean.riseSecondsPerDepth * depth * rate).toInt()
        for (i in x.indices) {
            val v = x[i].toDouble()
            a = v + k * (a - v)
            b = a + k * (b - a)
            val rise = if (i < riseN) 0.5 - 0.5 * cos(PI * i / riseN) else 1.0
            x[i] = ((v + blend * (b - v)) * gain * rise).toFloat()
        }
        return x
    }
}
