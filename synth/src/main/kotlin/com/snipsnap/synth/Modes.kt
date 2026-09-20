package com.snipsnap.synth

import com.snipsnap.synth.Dsp.RATE
import kotlin.math.cos
import kotlin.math.exp

/**
 * MODES — a bank of tuned resonators, each with its own decay.
 *
 * `audio/Body.kt` got here first and got the hard part right: a mode is a
 * two-pole resonator, the hit is the mallet. What it does not have is decay
 * *independence* — it computes one pole radius before its mode loop and every
 * mode inherits it, so everything it rings falls silent together. That is an
 * organ, not a bell. High partials dying before low ones is most of what
 * makes a sound read as *struck*.
 *
 * Offline rendering is what makes this affordable: a two-hundred-mode bank is
 * unthinkable inside a realtime mobile voice and costs nothing here.
 */
internal object Modes {

    /**
     * One partial: where it sits relative to the fundamental, how hard the
     * strike excites it, and how long it takes to fall 60 dB. [ratio] is
     * dimensionless on purpose — it is the physics of the body, independent
     * of what note the body is tuned to, and independent of sample rate.
     */
    data class Mode(val ratio: Float, val gain: Float, val t60: Float)

    /**
     * Rings [modes] with [excitation] — the mallet — at [fundamentalHz].
     *
     * Each mode is `y[n] = g*x[n] + a1*y[n-1] + a2*y[n-2]`, the same two-pole
     * form `Body.ring` uses, but `r` is derived per mode from its own t60
     * rather than once for the bank.
     *
     * `Body.ring`'s `(1 - r)` gain term does NOT carry over here, and this
     * is the part worth being deliberate about. `Body.ring` uses `(1 - r)`
     * to keep a *single shared r* from dominating a whole bank's level as
     * the DECAY macro moves — that is a resonant-gain correction, and it is
     * the right fix when the excitation is broadband and sustained, because
     * a narrowband resonator's *steady-state* gain genuinely scales with
     * `1/(1 - r)`.
     *
     * A [mode] here rings off a single hit, not a sustained tone, and this
     * two-pole form's impulse response is exactly solvable:
     * `y[n] = g * r^n * sin((n+1)*theta) / sin(theta)`. Its peak is reached
     * within the first quarter-cycle, where `r^n` has barely moved off 1 for
     * any t60 that outlasts a few periods — so the *onset* peak of a click
     * response is almost independent of r, and scaling `g` by `(1 - r)` (or
     * even by the energy-preserving `sqrt(1 - r^2)`) does the opposite of
     * what's wanted: it makes a fast-decaying mode ring far LOUDER than a
     * slow one for the identical strike, an 8-80x swing measured across
     * `t60` 0.05s..4s — the very volume-knob failure this task exists to
     * avoid, just relocated rather than removed. Leaving `g` as plain
     * [Mode.gain] keeps onset level flat (<1.2x drift end to end) and lets
     * t60 govern only how long the ring lasts, which is what DAMP should do.
     */
    fun ring(
        excitation: FloatArray,
        fundamentalHz: Float,
        modes: List<Mode>,
        rate: Int = RATE,
    ): FloatArray {
        val out = FloatArray(excitation.size)
        if (excitation.isEmpty() || modes.isEmpty() || fundamentalHz <= 0f) return out
        val nyquist = rate / 2f

        for (mode in modes) {
            val hz = fundamentalHz * mode.ratio
            // Skipped, not folded: a resonator tuned past Nyquist would alias
            // down to an arbitrary audible pitch that belongs to no body.
            if (hz <= 0f || hz >= nyquist) continue
            if (mode.t60 <= 0f || mode.gain == 0f) continue

            val r = exp(-6.9078 / (mode.t60.toDouble() * rate)).toFloat()
            val theta = 2.0 * Math.PI * hz / rate
            val a1 = (2.0 * r * cos(theta)).toFloat()
            val a2 = -(r * r)
            val g = mode.gain

            var y1 = 0f
            var y2 = 0f
            for (i in out.indices) {
                val y = g * excitation[i] + a1 * y1 + a2 * y2
                y2 = y1
                y1 = y
                out[i] += y
            }
        }
        return out
    }
}
