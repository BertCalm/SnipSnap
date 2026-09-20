package com.snipsnap.synth

import com.snipsnap.synth.Dsp.RATE
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow

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

    /**
     * The bodies. Ratios are sourced, not recalled — see
     * `mode-ratios-research.md` in the Phase 0 plan workspace for citations.
     * An earlier draft of the spec carried a table written from memory,
     * which is the exact failure this project exists to correct; do not
     * adjust a value here without a source.
     *
     * Two of these are the same object treated differently, and the
     * difference is the point: a free bar rings inharmonically (METAL_BAR),
     * while undercutting its belly pulls the partials onto near-harmonics
     * (WOOD_XYLO, WOOD_MARIMBA). That is most of what separates struck metal
     * from tuned wood.
     */
    enum class Material { METAL_BAR, MEMBRANE, WOOD_XYLO, WOOD_MARIMBA, BELL }

    /**
     * Gains and decays here are the *shape* of a strike, not measured
     * physics: every struck body excites its high partials less and lets
     * them die sooner than its low ones, and that is a defensible starting
     * point — but it is a shape, picked to sound plausible, and a candidate
     * for revision at the audition gate. The RATIOS passed in are the
     * sourced part; this function only dresses them.
     */
    private fun body(vararg ratios: Float): List<Mode> =
        ratios.mapIndexed { i, ratio ->
            // -6 dB per partial in gain; each partial rings about 30%
            // shorter than the one below it.
            Mode(
                ratio = ratio,
                gain = 0.5f.pow(i.toFloat() * 0.5f),
                t60 = 1.2f * 0.7f.pow(i.toFloat()),
            )
        }

    /** [Mode] tables for each [Material] — see the KDoc on [Material] and [body]. */
    fun tableFor(material: Material): List<Mode> = when (material) {
        // Euler-Bernoulli free-free eigenvalues 4.730/7.853/10.996/14.137
        // squared and normalized — Fletcher & Rossing, Blevins.
        Material.METAL_BAR -> body(1f, 2.756f, 5.404f, 8.933f)
        // Bessel zeros for modes (0,1)(1,1)(2,1)(0,2)(1,2), normalized to
        // (0,1). A circular membrane's modes are a 2D lattice, not a single
        // ascending series, so this ordering is "by ascending zero," not
        // "by mode number."
        Material.MEMBRANE -> body(1f, 1.5933f, 2.1354f, 2.2954f, 2.9172f)
        // Undercutting a bar's belly tunes the first overtone toward a
        // musical twelfth — Fletcher & Rossing.
        Material.WOOD_XYLO -> body(1f, 3f, 6f)
        // Deeper undercut tunes the first overtone two octaves up.
        Material.WOOD_MARIMBA -> body(1f, 4f, 10f)
        // Hum/prime/tierce/quint/nominal. Real bells land within 1-2% of
        // these idealized "true-harmonic" targets — Perrin et al. 1982.
        Material.BELL -> body(0.5f, 1f, 1.19f, 1.5f, 2f)
    }

    /**
     * A stiff string: `fn = n * f0 * sqrt(1 + B*n^2)` (Fletcher 1964). The
     * formula is settled; **B is not**. Sources disagree on its range by
     * orders of magnitude, so it is a parameter here and never a literal.
     * [B_MIN] and [B_MAX] bound a working range measured on a Steinway D —
     * a real instrument's bass-to-treble spread, not a guess at the
     * theoretical extremes. A caller reaching outside that range is making
     * a deliberate choice, not citing physics.
     */
    const val B_MIN = 0.0003f
    const val B_MAX = 0.025f

    fun stiffString(partials: Int, b: Float, rootT60: Float = 1.2f): List<Mode> =
        (1..partials).map { n ->
            val nf = n.toFloat()
            Mode(
                ratio = nf * kotlin.math.sqrt(1f + b * nf * nf),
                gain = 0.5f.pow((n - 1).toFloat() * 0.5f),
                t60 = rootT60 * 0.7f.pow((n - 1).toFloat()),
            )
        }

    /**
     * [material]'s partials resampled onto [slots] ordinal positions.
     *
     * Slot k is "this body's k-th ascending partial." Where a body runs out
     * of sourced partials, its own ratio-growth trend continues into the
     * remaining slots, fitted in LOG-RATIO space because partial series grow
     * geometrically — a linear continuation would flatten exactly the
     * character that distinguishes one body from another. Extrapolated slots
     * ring quieter than sourced ones, which is both true of real upper
     * partials and an honest marker that they are inference rather than data.
     *
     * The alternative — padding short tables with silent modes — was
     * rejected: the length difference between a 3-partial tuned bar and a
     * 5-partial membrane is structural, not an amplitude gap, and
     * crossfading real partials against silence would thin the sound
     * halfway through a sweep.
     */
    internal fun resample(material: Material, slots: Int): List<Mode> {
        val sourced = tableFor(material)
        if (slots <= sourced.size) return sourced.take(slots)

        // Growth per index in log space, read off the sourced partials. With
        // only one partial there is no trend to read, so fall back to the
        // harmonic series — the least-assuming continuation available.
        val logs = sourced.map { kotlin.math.ln(it.ratio.toDouble()) }
        val step = if (logs.size >= 2) (logs.last() - logs.first()) / (logs.size - 1) else kotlin.math.ln(2.0)

        val out = sourced.toMutableList()
        for (k in sourced.size until slots) {
            val extrapolated = kotlin.math.exp(logs.last() + step * (k - sourced.size + 1)).toFloat()
            val last = out.last()
            out.add(
                Mode(
                    ratio = extrapolated,
                    // Quieter and shorter than the partial below it, and
                    // quieter again for being inferred rather than sourced.
                    gain = last.gain * 0.5f,
                    t60 = last.t60 * 0.7f,
                ),
            )
        }
        return out
    }

    /**
     * The MATERIAL knob: [from] at amount 0, [to] at amount 1, and genuine
     * bodies-that-do-not-exist in between. Both ratio and gain crossfade per
     * slot, so every slot always carries a real partial from both endpoints
     * and the morph stays dense the whole way across.
     */
    fun morph(from: Material, to: Material, amount: Float, slots: Int = 6): List<Mode> {
        val a = resample(from, slots)
        val b = resample(to, slots)
        val t = amount.coerceIn(0f, 1f)
        return (0 until slots).map { k ->
            Mode(
                // Interpolated in log space, for the same reason the
                // extrapolation is: ratios are geometric, not linear.
                ratio = kotlin.math.exp(
                    kotlin.math.ln(a[k].ratio.toDouble()) * (1 - t) + kotlin.math.ln(b[k].ratio.toDouble()) * t,
                ).toFloat(),
                gain = a[k].gain * (1 - t) + b[k].gain * t,
                t60 = a[k].t60 * (1 - t) + b[k].t60 * t,
            )
        }
    }

    /**
     * [modes] as excited by a strike at [position] along the body, 0 to 1.
     *
     * Hit a bar at the centre and the even modes, which have a node there,
     * barely sound; hit it near the end and everything wakes up. That is
     * `|sin(n*pi*position)|` — the mode shape sampled at the striking point —
     * and it is the cheapest large timbral range in this whole document,
     * available only because there are individual modes to address.
     */
    fun atPosition(modes: List<Mode>, position: Float): List<Mode> {
        val p = position.coerceIn(0f, 1f)
        return modes.mapIndexed { i, mode ->
            val n = i + 1
            val weight = kotlin.math.abs(kotlin.math.sin(n * Math.PI * p)).toFloat()
            mode.copy(gain = mode.gain * weight)
        }
    }
}
