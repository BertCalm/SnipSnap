package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Candidate FILAMENT voices for listening round filament-voices-1. Nothing here is heard yet.
 *
 * THREAD, PULSE and CURL send one harmonic source down every strand and hear the halo only as
 * a colour behind the routes. Each candidate reads a part of the same network those three leave
 * unused, through the same junction, collectors, halo and held-loop proof:
 * - BRAID: the strands as separate players. Each strand gets its own copy of the source, a whole
 *   number of cycles apart over the held loop, so the routes beat against each other like an
 *   ensemble, and each copy wanders in pitch and level on its own seeded drift.
 * - HALO: the halo modes as the instrument (a glass harmonica). A slow rubbed source leaks
 *   almost wholly into long-ringing root-related modes; a finite note keeps ringing after the rub.
 * - FRAY: air at the leaks (a glass flute). Breath noise travels with the tone and escapes at the
 *   bend sites, where the root-related halo modes turn it into pitched breath.
 * - SPRING (spare): the collector heard through a dispersive cascade, so a plucked arrival chirps
 *   like a struck spring or a steel tongue.
 * Every candidate's sustained part drifts slowly on a seeded [HoldDrift] that closes over the held
 * loop, so a held key is never a frozen cycle.
 */
internal object FilamentCandidates {
    /** A candidate's held loop spans twice the kept voices' 2.3 s, so its drift is slow. */
    const val LOOP_SECONDS = 4.6

    /** Whole-cycle offsets per strand for BRAID: beats of about .2, .4 and .65 Hz on every key. */
    val BRAID_CYCLES = intArrayOf(0, 1, -2, 3)
    const val BRAID_SHARE = .40

    class Tables(
        val defaults: FloatArray,
        val pathNarrow: DoubleArray,
        val pathWide: DoubleArray,
        val common: Double,
        val spread: Double,
        val pathDistance: DoubleArray,
        val profile: DoubleArray,
        val sensitivity: DoubleArray,
        val highRatio: Double,
        val highMix: Double,
        val t60Base: Double,
        val t60Length: Double,
        val ratios: DoubleArray,
        val modeWeights: DoubleArray,
        val modeT60Slope: Double,
        val release: Double,
        val within: Double,
        val recipe: DoubleArray,
        val sourceSeconds: Double,
        val sourceHold: Double,
    )

    fun tables(voice: FilamentVoice): Tables = when (voice) {
        FilamentVoice.BRAID -> braid
        FilamentVoice.HALO -> halo
        FilamentVoice.FRAY -> fray
        FilamentVoice.SPRING -> spring
        else -> error("$voice is not a FILAMENT candidate")
    }

    private val braid = Tables(
        defaults = floatArrayOf(.45f, .30f, .55f, .85f, .30f),
        pathNarrow = doubleArrayOf(.60, .20, .13, .07), pathWide = doubleArrayOf(.42, .24, .20, .14),
        common = .10, spread = .045,
        pathDistance = doubleArrayOf(0.0, .17, .46, 1.05),
        profile = doubleArrayOf(.45, .70, .90, .40),
        sensitivity = doubleArrayOf(.80, 1.10, 1.50, 1.85),
        highRatio = 1.10, highMix = .80,
        t60Base = .70, t60Length = .90,
        ratios = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0),
        modeWeights = doubleArrayOf(.30, .24, .17, .12, .08, .05, .025, .015),
        modeT60Slope = .30,
        release = .45, within = .35,
        // A bowed-ensemble recipe: full lower harmonics, an early roll-off above the fifth.
        recipe = doubleArrayOf(1.0, .62, .40, .26, .15, .08, .045, .025, .014, .008, .005, .003),
        sourceSeconds = 2.6, sourceHold = 1.8,
    )

    private val halo = Tables(
        defaults = floatArrayOf(.35f, .75f, .60f, .45f, .85f),
        pathNarrow = doubleArrayOf(.55, .22, .15, .08), pathWide = doubleArrayOf(.40, .25, .21, .14),
        common = .08, spread = .040,
        pathDistance = doubleArrayOf(0.0, .12, .40, .95),
        profile = doubleArrayOf(.70, 1.0, .90, .60),
        sensitivity = doubleArrayOf(.95, 1.20, 1.50, 1.80),
        highRatio = .75, highMix = .70,
        t60Base = 2.2, t60Length = 2.4,
        // A rubbed glass rim: the root and its octave carry it, a faintly stretched third
        // and fourth mode give the glass its sheen.
        ratios = doubleArrayOf(1.0, 2.0, 3.004, 4.012, 5.02, 6.03, 8.06, 10.1),
        modeWeights = doubleArrayOf(.46, .26, .12, .07, .045, .025, .012, .008),
        modeT60Slope = .22,
        release = .80, within = .30,
        recipe = doubleArrayOf(1.0, .55, .26, .16, .09, .05, .03, .02, .012, .008, .005, .003),
        sourceSeconds = 1.7, sourceHold = 1.6,
    )

    private val fray = Tables(
        defaults = floatArrayOf(.50f, .60f, .45f, .35f, .55f),
        pathNarrow = doubleArrayOf(.60, .21, .12, .07), pathWide = doubleArrayOf(.44, .24, .19, .13),
        common = .07, spread = .035,
        pathDistance = doubleArrayOf(0.0, .15, .45, 1.0),
        profile = doubleArrayOf(.80, .95, .70, .45),
        sensitivity = doubleArrayOf(.90, 1.15, 1.45, 1.75),
        highRatio = .90, highMix = .75,
        t60Base = .55, t60Length = .70,
        ratios = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0),
        modeWeights = doubleArrayOf(.42, .22, .14, .09, .06, .035, .02, .015),
        modeT60Slope = .35,
        release = .30, within = .45,
        // A flute: a strong root, a soft octave and twelfth, almost nothing above.
        recipe = doubleArrayOf(1.0, .30, .16, .07, .035, .02, .012, .008, .005, .003, .002, .001),
        sourceSeconds = 2.3, sourceHold = 1.8,
    )

    private val spring = Tables(
        defaults = floatArrayOf(.60f, .35f, .50f, .30f, .30f),
        pathNarrow = doubleArrayOf(.56, .22, .14, .08), pathWide = doubleArrayOf(.40, .24, .21, .15),
        common = .06, spread = .030,
        pathDistance = doubleArrayOf(0.0, .20, .55, 1.10),
        profile = doubleArrayOf(.30, .85, .60, .30),
        sensitivity = doubleArrayOf(.70, 1.10, 1.55, 1.95),
        highRatio = 1.40, highMix = .85,
        t60Base = .45, t60Length = .80,
        ratios = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0),
        modeWeights = doubleArrayOf(.24, .20, .18, .14, .10, .07, .045, .025),
        modeT60Slope = .40,
        release = .25, within = .50,
        recipe = doubleArrayOf(1.0, .62, .48, .36, .27, .20, .14, .10, .07, .05, .03, .02),
        sourceSeconds = 1.6, sourceHold = 1.6,
    )

    /** The seeded drift for one candidate, strand or role. Three slow components per loop span. */
    fun drift(voice: FilamentVoice, seed: Int, role: Int): HoldDrift =
        HoldDrift(Dsp.seedFor("filament-candidate-drift", seed, voice, role), intArrayOf(1, 2, 3))

    /** How strongly each candidate's sustained part wanders: level, brightness and cents. */
    fun driftDepth(voice: FilamentVoice): DoubleArray = when (voice) {
        FilamentVoice.BRAID -> doubleArrayOf(.14, .25, 4.0)
        FilamentVoice.HALO -> doubleArrayOf(.22, .30, 1.5)
        FilamentVoice.FRAY -> doubleArrayOf(.22, .30, 3.0)
        else -> doubleArrayOf(.18, .25, 1.0)
    }

    /**
     * FRAY's breath: seeded noise shaped to sit between a little under the root and about
     * 2.4 kHz. For a held loop the shaping filters run round the table twice, so the
     * returned noise is periodic over [frames] and the loop closes.
     */
    fun breath(seed: Int, hz: Double, frames: Int, rate: Int, periodic: Boolean): DoubleArray {
        val random = Random(Dsp.seedFor("filament-fray-breath", seed))
        val white = DoubleArray(frames) { random.nextDouble() * 2.0 - 1.0 }
        // Three low-pass poles at four times the root (350 Hz to 2.4 kHz): low keys breathe low.
        val lowHz = (4.0 * hz).coerceIn(350.0, 2_400.0)
        val highHz = .7 * hz
        val a = 1.0 - exp(-2.0 * PI * lowHz / rate)
        val h = 1.0 - exp(-2.0 * PI * highHz / rate)
        val out = DoubleArray(frames)
        var l1 = 0.0; var l2 = 0.0; var l3 = 0.0; var hp = 0.0
        val passes = if (periodic) 2 else 1
        for (pass in 0 until passes) for (i in 0 until frames) {
            l1 += a * (white[i] - l1)
            l2 += a * (l1 - l2)
            l3 += a * (l2 - l3)
            hp += h * (l3 - hp)
            if (pass == passes - 1) out[i] = l3 - hp
        }
        var square = 0.0
        for (v in out) square += v * v
        val rms = kotlin.math.sqrt(square / frames.coerceAtLeast(1))
        if (rms > 0.0) for (i in out.indices) out[i] /= rms
        return out
    }

    /** A plucked opening: a fast rise and an exponential fall. */
    fun pluck(time: Double, attack: Double, tau: Double): Double =
        (1.0 - exp(-time / attack)).pow(2) * exp(-time / tau)

    /** A smooth slow rise for a rubbed rim. */
    fun rub(time: Double, seconds: Double): Double = sin(min(1.0, time / seconds) * PI * .5).pow(2)

    /**
     * A candidate's source table: the same twelve-partial source with three envelope families
     * (root, partials 2 to 4, partials 5 up) as the kept voices, plus the candidate's own reading.
     * Touch scales the upper partials (soft is darker, not only quieter). Every envelope is in
     * [0, 1]; the partial weights sum to one, so the table is bounded by effort as before.
     * [check] is called every 1024 frames so a cancelled render stops.
     */
    fun source(voice: FilamentVoice, inject: Double, hold: Double, hz: Double, effort: Double, velocity: Double,
               frames: Int, loop: Boolean, carrierCycles: Int, config: FilamentConfig, rate: Int,
               heldTilt: Double, strand: Int, check: () -> Unit): FloatArray {
        val t = tables(voice)
        val count = min(t.recipe.size, floor(16_000.0 / hz).toInt()).coerceAtLeast(1)
        // Touch must be heard as tone (the owner, 20 Nov: "All sound the same" at 0.5 to 5 dB):
        // a soft note loses most of its upper partials and opens slowly, a hard one is bright and quick.
        val bright = .12 + .88 * velocity
        val weights = DoubleArray(count) { h ->
            if (h == 0) t.recipe[0] else t.recipe[h] * (.55 + .65 * inject) * bright.pow(.7 + .4 * h)
        }
        val norm = weights.sum()
        for (h in weights.indices) weights[h] /= norm
        // The held-key thinning the kept voices take (Filament.HELD_TILT), applied the same way.
        if (heldTilt != 0.0) {
            fun tiltGain(f: Double) = (1.0 + (f / Filament.HELD_TILT_CORNER_HZ).pow(2)).pow(-heldTilt / 2.0)
            for (h in 1 until weights.size) weights[h] *= tiltGain((h + 1.0) * hz) / tiltGain(hz)
        }
        val seeded = Random(Dsp.seedFor("filament-articulation-v2", config.seed, voice, strand))
        val timing = .93 + .14 * seeded.nextDouble()
        val attack = (.004 + .018 * (1.0 - inject)) * (1.22 - .35 * velocity) * timing * (1.0 + 3.0 * (1.0 - velocity))
        val duration = frames.toDouble() / rate
        val offset = if (voice == FilamentVoice.BRAID) BRAID_CYCLES[strand] else 0
        val depth = driftDepth(voice)
        val level = drift(voice, config.seed, 10 * strand)
        val tone = drift(voice, config.seed, 10 * strand + 1)
        val pitch = drift(voice, config.seed, 10 * strand + 2)
        // Phase is the integral of the drifting frequency; the integral closes over the loop too.
        val pitchScale = 2.0 * PI * hz * LOOP_SECONDS * depth[2] * ln(2.0) / 1200.0
        val air = if (voice == FilamentVoice.FRAY) breath(config.seed, hz, frames, rate, loop) else null
        // A softer breath carries more air against its tone, as on a real flute.
        val airAmount = .08 + .22 * (1.0 - velocity)
        // BRAID: the first strand leads and the other players sit under it, so their beating
        // shimmers (a few dB) instead of swinging the whole note in and out.
        val share = if (voice == FilamentVoice.BRAID && strand > 0) BRAID_SHARE else 1.0
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            if (i and 1023 == 0) check()
            val time = i.toDouble() / rate
            val progress = i.toDouble() / frames
            val position = if (loop) progress else time / LOOP_SECONDS
            val base = if (loop) 2.0 * PI * (carrierCycles + offset) * i / frames
                else 2.0 * PI * (hz + offset / LOOP_SECONDS) * time
            val phase = base + pitchScale * pitch.integral(position)
            val lift = 1.0 + depth[0] * level.at(position)
            val shine = 1.0 + depth[1] * tone.at(position)
            val root: Double
            val middle: Double
            val edge: Double
            var breathing = 0.0
            if (loop) {
                when (voice) {
                    FilamentVoice.BRAID -> { root = .92; middle = .80 * shine; edge = .55 * shine }
                    FilamentVoice.HALO -> { root = .90; middle = .55 * shine; edge = .30 * shine }
                    FilamentVoice.FRAY -> { root = .90; middle = .55 * shine; edge = .25 * shine; breathing = .90 * shine }
                    else -> { root = .85; middle = .50 * shine; edge = .30 * shine }
                }
            } else {
                val fade = ((duration - time) / .030).coerceIn(0.0, 1.0)
                fun opening(seconds: Double): Double = (1.0 - exp(-time / seconds)).pow(2)
                when (voice) {
                    FilamentVoice.BRAID -> {
                        val sustain = exp(-max(0.0, time - .55 - .7 * hold) / (.85 + .8 * hold))
                        root = opening(attack * 3.5) * sustain
                        middle = opening(attack * 4.5) * sustain * shine
                        edge = opening(attack * 6.0) * exp(-max(0.0, time - .35) / (.6 + .6 * hold)) * shine
                    }
                    FilamentVoice.HALO -> {
                        val rubbing = .12 + .30 * (1.0 - inject) + .15 * (1.0 - velocity)
                        // The finger keeps rubbing, then lifts over a quarter second; the glass rings on.
                        val sustain = exp(-time / (3.0 + 2.0 * hold)) * ((duration - time) / .25).coerceIn(0.0, 1.0)
                        root = rub(time, rubbing) * sustain
                        middle = rub(time, rubbing * 1.3) * sustain * shine
                        edge = .8 * rub(time, rubbing * 1.6) * sustain * shine
                    }
                    FilamentVoice.FRAY -> {
                        val sustain = exp(-max(0.0, time - .75 - .7 * hold) / (.7 + .6 * hold))
                        root = opening(attack * 3.0) * sustain
                        middle = opening(attack * 2.0) * sustain * shine
                        // The chiff: the edge speaks first, then settles.
                        edge = opening(attack * .8) * (.35 + .65 * exp(-time / .07)) * sustain * shine
                        breathing = opening(attack * 1.2) * sustain * (1.0 + .8 * exp(-time / .05)) * shine
                    }
                    else -> {
                        root = pluck(time, attack * .6, .45 + .6 * hold)
                        middle = pluck(time, attack * .5, .26 + .4 * hold) * shine
                        edge = pluck(time, attack * .4, .10 + .2 * hold) * shine
                    }
                }
                val r = root * fade; val mi = middle * fade; val e = edge * fade
                var sum = 0.0
                for (h in weights.indices) {
                    val envelope = when (h) { 0 -> r; in 1..3 -> mi; else -> e }
                    sum += weights[h] * envelope.coerceAtMost(1.0) * sin((h + 1.0) * phase)
                }
                if (air != null) sum += airAmount * breathing * fade * air[i]
                out[i] = (effort * share * lift * sum).toFloat()
                continue
            }
            var sum = 0.0
            for (h in weights.indices) {
                val envelope = when (h) { 0 -> root; in 1..3 -> middle; else -> edge }
                sum += weights[h] * envelope.coerceAtMost(1.0) * sin((h + 1.0) * phase)
            }
            if (air != null) sum += airAmount * breathing * air[i]
            out[i] = (effort * share * lift * sum).toFloat()
        }
        return out
    }

    /**
     * SPRING's dispersion: a cascade of narrow second-order allpasses spread from 1.5 to 6 times
     * the root (capped at 3 kHz). It never changes a partial's level or pitch; it delays each
     * partial by a different amount, so a plucked arrival smears into a chirp. Its state is part
     * of the held-loop proof.
     */
    class Dispersion(hz: Double, rate: Int) {
        private val stages = 16
        private val b0 = DoubleArray(stages)
        private val b1 = DoubleArray(stages)
        private val a2n = DoubleArray(stages)
        private val s1 = DoubleArray(stages)
        private val s2 = DoubleArray(stages)

        init {
            val low = 1.5 * hz
            val high = min(3_000.0, 6.0 * hz).coerceAtLeast(low * 1.01)
            for (k in 0 until stages) {
                val f = low * (high / low).pow(k / (stages - 1.0))
                val w = 2.0 * PI * f / rate
                val alpha = sin(w) / (2.0 * 4.0)
                // RBJ allpass, normalized: b0 = a2 = (1-alpha)/(1+alpha), b1 = a1 = -2cos(w)/(1+alpha), b2 = 1.
                b0[k] = (1.0 - alpha) / (1.0 + alpha)
                b1[k] = -2.0 * cos(w) / (1.0 + alpha)
                a2n[k] = b0[k]
            }
        }

        fun next(input: Double): Double {
            var x = input
            for (k in 0 until stages) {
                val y = b0[k] * x + s1[k]
                s1[k] = Filament.floored(b1[k] * x - b1[k] * y + s2[k])
                s2[k] = Filament.floored(x - a2n[k] * y)
                x = y
            }
            return x
        }

        fun state(out: MutableList<Double>) { for (k in 0 until stages) { out += s1[k]; out += s2[k] } }
    }
}
