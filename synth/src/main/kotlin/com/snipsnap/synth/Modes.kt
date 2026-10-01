package com.snipsnap.synth

import com.snipsnap.synth.Dsp.RATE
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

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
     *
     * [pan] is 0 hard left, 1 hard right, 0.5 centred — the position this
     * mode radiates from. Defaulted to centre so every existing 3-arg
     * construction still compiles and still renders mono-identical.
     */
    data class Mode(val ratio: Float, val gain: Float, val t60: Float, val pan: Float = 0.5f)

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
     *
     * That same closed form has a second hazard, orthogonal to the one
     * above and NOT fixed by anything here: peak amplitude also carries a
     * `1/sin(theta)` term, and `theta = 2*pi*hz/rate` shrinks both as `hz`
     * falls and as `rate` rises — so a low-fundamental mode rings far
     * louder than a high one for the identical [Mode.gain], and rendering
     * at an oversampled rate compounds it further: a 4x-oversampled engine
     * sees roughly 4x the `1/sin(theta)` a 1x engine would at the same
     * pitch. Measured, not estimated: at SNARE's ~180 Hz fundamental
     * rendered at THUMP's 4x-oversampled 176,400 Hz, `1/sin(theta)` for the
     * fundamental mode alone is ~156, and a bare bank render came out
     * ~250x louder than the snare's non-modal wire layer before either was
     * gain-staged. This is not something [ring] should correct — a fixed
     * per-call fudge factor would be wrong at every other pitch and rate —
     * it is a property of the resonator every caller mixing this output
     * against a non-modal layer (noise, a sample, another synthesis path)
     * needs to plan for: peak-normalize (or otherwise scale) the modal
     * output before balancing it against anything else. `Thump.snare`'s
     * `bodyPeak` normalization, right after its `Modes.ring` call, is a
     * worked example.
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
     * A body resonance at an absolute frequency. A fixed body does not track
     * the note, so callers ring these against a 1 Hz "fundamental":
     * `ring(drive, 1f, listOf(fixed(98f, 1f, 0.45f)), rate)`. [hz] lands in
     * [Mode.ratio], which [ring] multiplies by that 1 Hz.
     */
    fun fixed(hz: Float, gain: Float, t60: Float): Mode = Mode(ratio = hz, gain = gain, t60 = t60)

    /**
     * A floor on how fast the extrapolation in [resample] is allowed to
     * decelerate per additional slot. Without it, two sourced steps that
     * happen to be very close together (the membrane's dense Bessel-zero
     * ordering produces this) would compute a near-zero decay ratio and
     * collapse every slot past that point onto almost the same frequency.
     * 0.3 is a judgment call, not a measurement — it keeps the trend
     * decelerating without letting one noisy pair of steps freeze it.
     */
    private const val STEP_DECAY_FLOOR = 0.3

    /**
     * [material]'s partials resampled onto [slots] ordinal positions.
     *
     * Slot k is "this body's k-th ascending partial." Where a body runs out
     * of sourced partials, this continues its trend in LOG-RATIO space
     * (partial series grow geometrically, so a linear continuation would
     * flatten exactly the character that distinguishes one body from
     * another) — but "the trend" is read from the LAST TWO observed
     * log-steps, not a global average across the whole table.
     *
     * That distinction mattered in practice: a global average is dragged
     * upward by whichever transition happens to be biggest, almost always
     * mode 1->2, while real partial spacing decelerates as index rises
     * (METAL_BAR's own steps are 1.014, 0.673, 0.503 — each smaller than the
     * last). Averaging those into one flat 0.730 stretched METAL_BAR's
     * extrapolated slot 6 to ratio 38.5, more than double the free-free
     * beam's own asymptote (βL ≈ (n+0.5)π gives ≈18.6). For WOOD_MARIMBA the
     * same bug put slots 5 and 6 above 20 kHz at a 220 Hz fundamental —
     * `ring()`'s Nyquist guard then dropped them, silently reintroducing
     * the exact silent-slot thinning this whole scheme exists to avoid.
     *
     * Reading the slope from the last two steps and decaying it further by
     * that same ratio (floored at [STEP_DECAY_FLOOR] so a noisy pair of
     * steps can't collapse the series) keeps a decelerating body
     * decelerating instead of running it out straight. It is still a
     * generic heuristic, not per-material physics — the membrane's
     * Bessel-zero ordering is a 2D lattice, not a smooth sequence, so its
     * "trend" is noisier than the bar's — but it no longer manufactures
     * frequencies a real body's own growth curve would never reach.
     *
     * Extrapolated slots ring quieter than sourced ones, which is both true
     * of real upper partials and an honest marker that they are inference
     * rather than data.
     *
     * The alternative — padding short tables with silent modes — was
     * rejected: the length difference between a 3-partial tuned bar and a
     * 5-partial membrane is structural, not an amplitude gap, and
     * crossfading real partials against silence would thin the sound
     * halfway through a sweep.
     *
     * **High-fundamental ceiling, at 6 slots and [Dsp.RATE] (44.1 kHz,
     * Nyquist 22.05 kHz).** `ring()` skips a mode once its frequency clears
     * Nyquist rather than aliasing it (see `ring`'s KDoc), so above these
     * fundamentals the affected slot(s) simply stop sounding — the body
     * thins by losing its top partial(s), not by artifacting. Recomputed
     * directly from this function's own output, not copied from a review:
     *
     * ```
     * Material        Fundamental above which a slot is lost
     * WOOD_MARIMBA    ~619 Hz (slot 6), ~806 Hz (slot 5 too)
     * METAL_BAR       ~1282 Hz (slot 6)
     * WOOD_XYLO       ~1513 Hz (slot 6)
     * MEMBRANE        ~5948 Hz (slot 6)
     * BELL            ~8269 Hz (slot 6)
     * ```
     *
     * WOOD_MARIMBA is the outlier by roughly 2x, because 1:4:10 grows
     * faster than any other table and its extrapolated slots reach Nyquist
     * soonest. ~619 Hz is D#5 — an entirely ordinary pitch for a struck
     * one-shot, not a theoretical edge case, so a small high marimba WILL
     * render with a thinner top end than the same body played lower.
     *
     * Adaptive slot count (fewer slots at high fundamentals, so the top
     * never gets silently amputated) is deliberately deferred to whichever
     * Phase 1B engine adopts this bank and can make that call with a real
     * voice's CPU and pitch range in view — not guessed at here.
     */
    internal fun resample(material: Material, slots: Int): List<Mode> {
        val sourced = tableFor(material)
        if (slots <= sourced.size) return sourced.take(slots)

        val logs = sourced.map { kotlin.math.ln(it.ratio.toDouble()) }

        // The most recent observed step. With only one sourced step to read
        // (two partials total) there's no deceleration to measure, so hold
        // it steady; with none (one partial) fall back to the harmonic
        // series, the least-assuming continuation available.
        var step = if (logs.size >= 2) logs.last() - logs[logs.size - 2] else kotlin.math.ln(2.0)

        // How much smaller each further step gets, read from the ratio of
        // the last two observed steps and clamped to [FLOOR, 1.0] — never
        // an acceleration (a body's spacing can occasionally widen locally,
        // as the membrane's does, but extrapolating that as a trend would
        // be inventing growth no sourced data showed) and never collapsed
        // to near-zero by one noisy pair.
        val decay = if (logs.size >= 3) {
            (step / (logs[logs.size - 2] - logs[logs.size - 3])).coerceIn(STEP_DECAY_FLOOR, 1.0)
        } else {
            1.0
        }

        val out = sourced.toMutableList()
        var logRatio = logs.last()
        for (k in sourced.size until slots) {
            step *= decay
            logRatio += step
            val last = out.last()
            out.add(
                Mode(
                    ratio = kotlin.math.exp(logRatio).toFloat(),
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
     * `|sin(n*pi*position)|` is the exact mode shape of a uniform bar or
     * string, where mode n really is a sine with n antinodes — hit it at a
     * node and that mode genuinely does not sound. It is NOT the true mode
     * shape of the membrane or bell tables this is also applied to: the
     * membrane's modes are a 2D Bessel pattern (slot 2 is (1,1), one nodal
     * diameter, not a 1D sine), and a bell's named partials each have their
     * own measured nodal geometry. Used here as a control law regardless —
     * a physically-motivated, cheap, and audibly correct-shaped way to make
     * strike position matter at all — not as a claim that any table but the
     * bar's is being modeled exactly.
     */
    fun atPosition(modes: List<Mode>, position: Float): List<Mode> {
        val p = position.coerceIn(0f, 1f)
        return modes.mapIndexed { i, mode ->
            val n = i + 1
            val weight = kotlin.math.abs(kotlin.math.sin(n * Math.PI * p)).toFloat()
            mode.copy(gain = mode.gain * weight)
        }
    }

    /**
     * [modes] given stereo positions, [width] 0 (all centred) to 1 (full
     * spread). Seeded from [seed] so a body's image is reproducible — two
     * renders of the same patch must be byte-identical, and a random image
     * per render would break that as surely as a random oscillator phase.
     *
     * Positions alternate outward rather than landing randomly: a real body's
     * low modes are its least directional, so the fundamental stays near the
     * middle and the upper partials fan out, which is both what a physical
     * radiator does and what keeps a fold-down's low end solid.
     */
    fun spread(modes: List<Mode>, width: Float, seed: Int): List<Mode> {
        val w = width.coerceIn(0f, 1f)
        val random = Random(seed)
        return modes.mapIndexed { i, mode ->
            // Alternating sides, widening with index, jittered so a bank
            // never sounds like a row of evenly spaced points.
            val side = if (i % 2 == 0) -1f else 1f
            val reach = if (modes.size <= 1) 0f else i.toFloat() / (modes.size - 1)
            val jitter = (random.nextFloat() - 0.5f) * 0.25f
            mode.copy(pan = (0.5f + side * w * reach * (0.5f + jitter)).coerceIn(0f, 1f))
        }
    }

    /**
     * [modes] rung into an interleaved stereo buffer, each at its own [Mode.pan].
     *
     * Panning is LINEAR — `L = (1-p)·x`, `R = p·x` — so, at THIS function's
     * own output, L+R sums to exactly `x` for every position: a mono fold
     * of the buffer [ringStereo] returns has no comb notching, which
     * equal-power panning cannot promise (it sums to √2 at centre). That is
     * a property of this function's output, not a promise about what a
     * caller eventually exports — a caller's own level stages downstream
     * (gain-staging, [Punch], export) can and do rescale the fold relative
     * to a same-settings mono render; see [Thump.render]'s own KDoc (and
     * its private `snare` helper) for what the shipped SNARE voice actually
     * delivers end to end. These files end up on SD cards and club systems, so the
     * no-comb-notching property this function itself guarantees is not
     * hypothetical — it just isn't the whole story past this call.
     */
    fun ringStereo(
        excitation: FloatArray,
        fundamentalHz: Float,
        modes: List<Mode>,
        rate: Int = RATE,
    ): FloatArray {
        val out = FloatArray(excitation.size * 2)
        if (excitation.isEmpty() || modes.isEmpty() || fundamentalHz <= 0f) return out
        for (mode in modes) {
            val one = ring(excitation, fundamentalHz, listOf(mode.copy(pan = 0.5f)), rate)
            val p = mode.pan.coerceIn(0f, 1f)
            for (i in one.indices) {
                out[i * 2] += one[i] * (1f - p)
                out[i * 2 + 1] += one[i] * p
            }
        }
        return out
    }

    /**
     * A bank of modes that can be driven, retuned on any sample, and coupled to
     * each other. [ring] can do none of these: it is buffer-in, buffer-out, with
     * its coefficients fixed for the whole call. MERCURY's R0. The design is
     * `docs/superpowers/specs/2026-10-01-mercury-modal-glass-engine-design.md`
     * ("Phase 0, as measured"); every number below is from the record,
     * `docs/superpowers/plans/2026-10-01-mercury-phase-0-spike.md`.
     *
     * **A mode is one complex number**, `z = ω·q − i·v`, in mass-normalised
     * coordinates (q is the displacement, v the velocity). Over one sample, a
     * free, damped mode's motion is *exactly* a rotation of z by `e^{iωT}` and a
     * shrink by `r = e^{−6.9078·T/t60}`. Two things follow:
     * - `|z|² = v² + ω²q²` is twice the mode's energy, so [tune] can move ω on
     *   any sample without pumping the level. Phase 0 measured energy within
     *   0.1 % under a ±12-semitone sweep, where a retuned trapezoidal SVF
     *   swung ±6 dB.
     * - The step is exact, so even a very high-Q mode keeps its pitch and its
     *   decay.
     *
     * **Coupling** is a set of reciprocal springs between modes, [connect]ed in
     * pairs. Each is applied as a velocity kick after the rotation. That kick is
     * the exact flow of the spring potential, so the split stays passive even
     * though the kick is explicit; Phase 0's trapezoid, coupled one sample late,
     * diverged at COUPLE 0.3. The stiffness matrix is `K = diag(ω²) − A`: the
     * springs `½·k·(q_i − q_j)²` sit on modes whose own stiffness is
     * pre-reduced by the sum of their springs, so an uncoupled mode keeps its ω
     * on the diagonal. Each edge's stiffness is `kappa·min(ω_i, ω_j)²`, and a
     * mode's kappas must sum below [MAX_NODE_KAPPA]. That keeps K strictly
     * diagonally dominant, and therefore positive definite, *at every tuning*:
     * the bound is structural, not tuned by ear. Phase 0 also measured the
     * plain form, `diag(ω²) + L`. It is equally passive but moves the note
     * further, so it is not offered here.
     *
     * **Springs move the coupled pitch.** Near modes repel, and COUPLE flattened
     * Phase 0's anchor by 13–85 cents. [coupledHz] is where the anchor really
     * rings. [anchorScale] is the factor between the anchor's tuning and that.
     * Multiply every mode's frequency by it and the coupled anchor lands on
     * the frequency the anchor was tuned to before the multiply. Because every
     * spring scales with ω², that is exact, and the factor itself does not
     * change under the retune.
     *
     * **One sample** is [step] (rotate, then springs), then any number of
     * [drive] calls (the strike, the contact). A drive lands as velocity, so the
     * next [velocityAlong] or [velocity] read sees it. Every mode must be
     * [tune]d before the first [step].
     */
    class Bank(val size: Int, val rate: Int) {
        private val dt = 1.0 / rate
        private val re = DoubleArray(size)
        private val im = DoubleArray(size)
        private val omega = DoubleArray(size)
        private val cr = DoubleArray(size)
        private val sr = DoubleArray(size)
        private val q = DoubleArray(size)
        private val kick = DoubleArray(size)
        private val nodeKappa = DoubleArray(size)
        private var untuned = size
        private var edgeI = IntArray(0)
        private var edgeJ = IntArray(0)
        private var kappa = DoubleArray(0)
        private var k = DoubleArray(0)
        private var springsStale = false

        init {
            require(size > 0) { "a bank needs at least one mode" }
            require(rate > 0) { "rate must be positive: $rate" }
        }

        val edges: Int get() = kappa.size

        /** Sets mode [i] to [hz], decaying 60 dB in [t60] seconds. Its state is kept, so its energy is too. */
        fun tune(i: Int, hz: Double, t60: Double) {
            require(hz > 0.0 && hz < rate / 2.0) { "mode $i at $hz Hz is outside (0, ${rate / 2}) Hz" }
            require(t60 > 0.0) { "mode $i t60 must be positive: $t60" }
            if (omega[i] == 0.0) untuned--
            val w = 2.0 * Math.PI * hz
            omega[i] = w
            val r = exp(-T60_LN * dt / t60)
            cr[i] = r * cos(w * dt)
            sr[i] = r * sin(w * dt)
            springsStale = true
        }

        /** Adds a spring between modes [i] and [j]; returns its index for [setKappa]. */
        fun connect(i: Int, j: Int, kappa: Double): Int {
            require(i != j && i in 0 until size && j in 0 until size) { "bad spring $i–$j in a bank of $size" }
            require(kappa >= 0.0) { "kappa must be non-negative: $kappa" }
            edgeI = edgeI.copyOf(edgeI.size + 1).also { it[it.size - 1] = i }
            edgeJ = edgeJ.copyOf(edgeJ.size + 1).also { it[it.size - 1] = j }
            this.kappa = this.kappa.copyOf(this.kappa.size + 1)
            k = k.copyOf(k.size + 1)
            setKappa(this.kappa.size - 1, kappa)
            return this.kappa.size - 1
        }

        /** Re-sets spring [edge]'s kappa, holding both of its modes' kappa sums under [MAX_NODE_KAPPA]. */
        fun setKappa(edge: Int, kappa: Double) {
            require(kappa >= 0.0) { "kappa must be non-negative: $kappa" }
            val i = edgeI[edge]
            val j = edgeJ[edge]
            val old = this.kappa[edge]
            val si = nodeKappa[i] - old + kappa
            val sj = nodeKappa[j] - old + kappa
            require(si < MAX_NODE_KAPPA && sj < MAX_NODE_KAPPA) {
                "spring $edge (modes $i–$j) at kappa $kappa takes a mode's kappa sum to ${maxOf(si, sj)}; " +
                    "it must stay under $MAX_NODE_KAPPA for K to stay positive definite"
            }
            nodeKappa[i] = si
            nodeKappa[j] = sj
            this.kappa[edge] = kappa
            springsStale = true
        }

        private fun refreshSprings() {
            for (e in k.indices) {
                val w = minOf(omega[edgeI[e]], omega[edgeJ[e]])
                k[e] = kappa[e] * w * w
            }
            springsStale = false
        }

        /** One sample of free motion: every mode rotates and decays, then the springs kick. */
        fun step() {
            check(untuned == 0) { "$untuned of $size modes were never tuned" }
            if (springsStale) refreshSprings()
            for (i in 0 until size) {
                val r0 = re[i]
                val i0 = im[i]
                re[i] = cr[i] * r0 - sr[i] * i0
                im[i] = sr[i] * r0 + cr[i] * i0
            }
            if (k.isEmpty()) return
            for (i in 0 until size) {
                q[i] = re[i] / omega[i]
                kick[i] = 0.0
            }
            for (e in k.indices) {
                val i = edgeI[e]
                val j = edgeJ[e]
                kick[i] += k[e] * q[j]
                kick[j] += k[e] * q[i]
            }
            for (i in 0 until size) im[i] -= kick[i] * dt
        }

        /** Applies [force] through the participation vector [b] for one sample: `v_i += b_i·force·T`. */
        fun drive(b: DoubleArray, force: Double) {
            val f = force * dt
            for (i in 0 until size) im[i] -= b[i] * f
        }

        fun velocity(i: Int): Double = -im[i]

        fun displacement(i: Int): Double = re[i] / omega[i]

        /** `Σ w_i·v_i`: a pickup when [w] is pickup weights, the surface speed when it is a contact vector. */
        fun velocityAlong(w: DoubleArray): Double {
            var s = 0.0
            for (i in 0 until size) s -= w[i] * im[i]
            return s
        }

        /** How far one unit of force through [b] moves `velocityAlong(b)` in one sample: `T·Σ b_i²`. */
        fun compliance(b: DoubleArray): Double {
            var s = 0.0
            for (i in 0 until size) s += b[i] * b[i]
            return s * dt
        }

        /** Kinetic plus modal plus spring energy: `½·vᵀv + ½·qᵀKq`. */
        fun energy(): Double {
            if (springsStale) refreshSprings()
            var e = 0.0
            for (i in 0 until size) e += 0.5 * (re[i] * re[i] + im[i] * im[i])
            for (s in k.indices) e -= k[s] * (re[edgeI[s]] / omega[edgeI[s]]) * (re[edgeJ[s]] / omega[edgeJ[s]])
            return e
        }

        /**
         * The factor to multiply every mode's frequency by so that the coupled
         * normal mode that is mostly [anchor] rings where [anchor] is tuned now.
         * It is exact, because every spring scales with ω². It is also
         * invariant: after the retune it returns the same factor, while
         * [coupledHz] reads the note. Returns 1 when there are no springs.
         */
        fun anchorScale(anchor: Int = 0): Double {
            if (k.isEmpty()) {
                check(untuned == 0) { "$untuned of $size modes were never tuned" }
                return 1.0
            }
            return omega[anchor] / sqrt(anchorEigenvalue(anchor))
        }

        /** Where the coupled normal mode that is mostly [anchor] rings, in Hz. */
        fun coupledHz(anchor: Int = 0): Double {
            if (k.isEmpty()) {
                check(untuned == 0) { "$untuned of $size modes were never tuned" }
                return omega[anchor] / (2.0 * Math.PI)
            }
            return sqrt(anchorEigenvalue(anchor)) / (2.0 * Math.PI)
        }

        private fun anchorEigenvalue(anchor: Int): Double {
            check(untuned == 0) { "$untuned of $size modes were never tuned" }
            refreshSprings()
            val m = Array(size) { DoubleArray(size) }
            for (i in 0 until size) m[i][i] = omega[i] * omega[i]
            for (e in k.indices) {
                m[edgeI[e]][edgeJ[e]] -= k[e]
                m[edgeJ[e]][edgeI[e]] -= k[e]
            }
            val (values, vectors) = symmetricEigen(m)
            var best = 0
            for (c in 0 until size) if (abs(vectors[anchor][c]) > abs(vectors[anchor][best])) best = c
            return values[best]
        }
    }

    /**
     * A friction contact: a finger on glass, a bow on a saw. The friction curve
     * is `φ(η) = √(2a)·η·e^(−aη² + ½)`, with η the slip (driver speed minus
     * surface speed). It peaks at 1 when `η = η* = 1/√(2a)`, and its falling
     * side beyond η* is the negative damping that lets a contact sustain a
     * mode. MERCURY's Phase 0 used `a = 5000` (η* = 0.01) with the finger at 3η*.
     *
     * [force] is solved *implicitly*. The force moves the very surface speed it
     * depends on, by `compliance·force` (see [Bank.compliance]). So each sample
     * solves `η + p·c·φ(η) = v_driver − v_surface`, with p the pressure and c
     * the compliance.
     * - The root is unique while `p·c·√(2a)·2/e < 1` (the curve's steepest
     *   fall), which [force] requires. The left side is then strictly
     *   increasing.
     * - Because `|φ| ≤ 1`, the root lies within `±p·c` of the free slip. Newton's
     *   method, warm-started from the last slip, runs inside that bracket and
     *   bisects whenever a step would leave it. Plain Newton can oscillate
     *   near the bound, where the slope approaches 0.
     *
     * One instance per contact per render: it carries the last slip.
     */
    class Friction(val a: Double) {
        private val root2a = sqrt(2.0 * a)
        private var slip = 0.0

        init {
            require(a > 0.0) { "friction curve a must be positive: $a" }
        }

        fun curve(eta: Double): Double = root2a * eta * exp(-a * eta * eta + 0.5)

        fun slope(eta: Double): Double = root2a * exp(-a * eta * eta + 0.5) * (1.0 - 2.0 * a * eta * eta)

        /** The steepest the curve falls: `√(2a)·2/e`, at `aη² = 3/2`. */
        val steepestFall: Double get() = root2a * 2.0 / Math.E

        /**
         * The contact force for this sample. [surfaceVelocity] is read before the
         * force is applied (`bank.velocityAlong(b)`), and [compliance] is
         * `bank.compliance(b)`. Apply the result with `bank.drive(b, force)`.
         */
        fun force(driverVelocity: Double, surfaceVelocity: Double, pressure: Double, compliance: Double): Double {
            val rhs = driverVelocity - surfaceVelocity
            if (pressure <= 0.0) {
                slip = rhs
                return 0.0
            }
            val c = pressure * compliance
            require(c * steepestFall < 1.0) {
                "pressure $pressure × compliance $compliance is past the unique-root bound (${1.0 / steepestFall})"
            }
            var lo = rhs - c
            var hi = rhs + c
            var x = slip.coerceIn(lo, hi)
            for (n in 0 until SOLVE_STEPS) {
                val g = x + c * curve(x) - rhs
                if (g == 0.0) break
                if (g > 0.0) hi = x else lo = x
                var next = x - g / (1.0 + c * slope(x))
                if (!(next > lo && next < hi)) next = 0.5 * (lo + hi)
                if (next == x) break
                x = next
            }
            slip = x
            return pressure * curve(x)
        }
    }

    /**
     * Eigenvalues and eigenvectors of a small symmetric matrix, by cyclic
     * Jacobi rotations. The eigenvectors are the *columns* of the second array.
     * It is meant for [Bank.anchorScale]'s 16×16 solve, once per note, not for
     * anything per sample.
     */
    fun symmetricEigen(m: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val n = m.size
        val a = Array(n) { r -> require(m[r].size == n) { "matrix is not square" }; m[r].copyOf() }
        for (r in 0 until n) for (c in r + 1 until n) {
            require(abs(a[r][c] - a[c][r]) <= 1e-12 * (abs(a[r][c]) + abs(a[c][r]) + 1e-300)) { "matrix is not symmetric at $r,$c" }
        }
        val v = Array(n) { r -> DoubleArray(n) { c -> if (r == c) 1.0 else 0.0 } }
        for (sweep in 0 until JACOBI_SWEEPS) {
            var off = 0.0
            var diag = 0.0
            for (r in 0 until n) {
                diag += a[r][r] * a[r][r]
                for (c in r + 1 until n) off += a[r][c] * a[r][c]
            }
            if (off <= 1e-30 * diag) break
            for (p in 0 until n) for (qq in p + 1 until n) {
                if (a[p][qq] == 0.0) continue
                val theta = (a[qq][qq] - a[p][p]) / (2.0 * a[p][qq])
                val t = (if (theta >= 0.0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
                val c = 1.0 / sqrt(t * t + 1.0)
                val s = t * c
                for (r in 0 until n) {
                    val arp = a[r][p]
                    val arq = a[r][qq]
                    a[r][p] = c * arp - s * arq
                    a[r][qq] = s * arp + c * arq
                }
                for (r in 0 until n) {
                    val apr = a[p][r]
                    val aqr = a[qq][r]
                    a[p][r] = c * apr - s * aqr
                    a[qq][r] = s * apr + c * aqr
                }
                for (r in 0 until n) {
                    val vrp = v[r][p]
                    val vrq = v[r][qq]
                    v[r][p] = c * vrp - s * vrq
                    v[r][qq] = s * vrp + c * vrq
                }
            }
        }
        return DoubleArray(n) { a[it][it] } to v
    }

    /** A mode's kappa sum must stay under this, so K stays strictly diagonally dominant. */
    const val MAX_NODE_KAPPA = 1.0

    /** ln(1000): the decay of 60 dB, in nepers. */
    private const val T60_LN = 6.907755278982137
    private const val SOLVE_STEPS = 64
    private const val JACOBI_SWEEPS = 100
}
