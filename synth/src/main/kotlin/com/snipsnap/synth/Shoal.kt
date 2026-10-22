package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** First listening proof: a struck glass population and a supplied, migrating population. */
enum class ShoalVoice { GLASS, RIPPLE }

/**
 * SHOAL. Model 3 separates resonating material from GLASS/RIPPLE motion. Models 1 and 2
 * retain their exact harmonic synthesis. Material models are finite; harmonic HOLD alone uses
 * the captured periodic trajectory described below.
 * Harmonics exchange nonnegative energy before they become oscillator amplitudes.
 * CURRENT biases conservative neighbor transfers; COHESION reads the previous energy snapshot.
 * The protected fundamental never participates in this graph.
 *
 * HOLD deliberately uses the engineering proposal's captured-trajectory approximation: a bounded
 * run of the same population is captured, joined in CONTROL space with a smooth convex overlap,
 * and replayed periodically. It is not a claim that the nonlinear population itself converged.
 * Neither the energy distribution nor STRAY is frozen. Mean frequency departure is removed in Hz
 * per partial so every carrier closes, rather than merely repeating its detune targets. Adjacent
 * cycles are synthesized independently through continuously running conditioning/decimation.
 * All voicing numbers are provisional and await the owner's listening verdict.
 */
object Shoal {
    const val MODEL_VERSION = 3
    const val LEGACY_MODEL_VERSION = 1
    const val HARMONIC_MODEL_VERSION = 2
    fun supportsModel(model: Int): Boolean = model in LEGACY_MODEL_VERSION..MODEL_VERSION
    fun supportsMaterial(model: Int, material: ShoalMaterial): Boolean =
        supportsModel(model) && (model == MODEL_VERSION || material == ShoalMaterial.HARMONIC)
    fun supportsHold(model: Int, material: ShoalMaterial): Boolean =
        supportsMaterial(model, material) && material == ShoalMaterial.HARMONIC
    fun defaultMaterial(model: Int = MODEL_VERSION): ShoalMaterial =
        if (model == MODEL_VERSION) ShoalMaterial.WOOD else ShoalMaterial.HARMONIC
    const val MIN_MIDI = 24
    const val MAX_MIDI = 84
    const val MOVING_PARTIALS = 24
    const val LOOP_THRESHOLD = .975f
    const val HOLD_LOOP = LOOP_THRESHOLD
    const val MAX_FINITE_SECONDS = 8f
    const val MAX_MATERIAL_SECONDS = 12f
    private const val CONTROL_FRAMES = 128
    private const val TWO_PI = 2.0 * PI
    private const val BAND_START = 14_000.0
    private const val BAND_END = 18_000.0
    private const val ENERGY_LIMIT = 2.0
    private const val MOVING_GAIN = .10
    private const val ROOT_GAIN = .26

    fun rootMidi(voice: ShoalVoice): Int = if (voice == ShoalVoice.GLASS) 60 else 48

    fun macrosFor(voice: ShoalVoice): List<MacroSpec> {
        val v = if (voice == ShoalVoice.GLASS) floatArrayOf(.30f, .20f, .70f, .05f, .40f)
            else floatArrayOf(.45f, .70f, .60f, .15f, .50f)
        return listOf(MacroSpec("DENSITY", v[0], neutral = .40f),
            MacroSpec("CURRENT", v[1], neutral = .30f), MacroSpec("COHESION", v[2], neutral = .55f),
            MacroSpec("STRAY", v[3], neutral = .15f), MacroSpec("GLOW", v[4], neutral = .45f),
            MacroSpec("HOLD", 0f, neutral = 0f))
    }

    fun defaults(voice: ShoalVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    fun settled(voice: ShoalVoice, macros: Map<String, Float>): Map<String, Float> = defaults(voice).mapValues { (k, v) ->
        (macros[k]?.takeIf { it.isFinite() } ?: v).coerceIn(0f, 1f)
    }
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= LOOP_THRESHOLD
    fun drumClassFor(voice: ShoalVoice, macros: Map<String, Float> = emptyMap(),
        model: Int = MODEL_VERSION, material: ShoalMaterial = ShoalMaterial.HARMONIC): DrumClass {
        require(supportsMaterial(model, material)) { "unsupported SHOAL model/material combination" }
        val held = isLoop(settled(voice, macros).getValue("HOLD"))
        require(!held || supportsHold(model, material)) { "SHOAL $material does not support HOLD" }
        return if (held) DrumClass.LOOP else DrumClass.TONAL
    }
    fun scramble(voice: ShoalVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = base + near?.macros.orEmpty().filterKeys { it in base }
        val amount = (temperature.takeIf { it.isFinite() } ?: .35f).coerceIn(0f, 1f)
        return Dsp.scrambleNear(seed, amount, random) + ("HOLD" to 0f)
    }

    /** NO_TRANSPORT freezes the full model's energy-weighted mean population while preserving
     * its total-energy envelope and STRAY; it differs deliberately from setting CURRENT to zero. */
    internal enum class Ablation { NONE, NO_TRANSPORT, NO_LOCAL_COHESION, NO_STRAY }
    internal data class EnergyFrame(val seconds: Double, val energy: DoubleArray, val centers: DoubleArray,
        val strayCents: DoubleArray, val totalEnergy: Double, val supply: Double)
    internal data class Diagnostics(val rawPeak: Double, val rawRms: Double, val rawDc: Double,
        val minEnergy: Double, val maxEnergySum: Double, val maxTransferResidual: Double,
        val activePartials: Int, val maxFrequencyHz: Double, val phaseClosureError: Double)
    /** Material radiating energy, separate from the conservative population controller. */
    internal data class RadiationFrame(val seconds: Double, val energy: DoubleArray, val rootEnergy: Double,
        val supply: Double, val strayCents: DoubleArray)
    internal data class MaterialDiagnostics(val material: ShoalMaterial, val modeHz: DoubleArray,
        val modeRatios: DoubleArray, val modeT60Seconds: DoubleArray, val rootT60Seconds: Double,
        val rootGain: Double, val contactSeconds: Double, val contactCutoffHz: Double,
        val excitationWeights: DoubleArray, val headroomTrim: Double, val radiationEnergyScale: Double,
        val retainedEnergyRatio: Double, val rootPowerFraction: Double, val sourceDurationSeconds: Double,
        val passiveTailSeconds: Double, val maxRadiationEnergySum: Double, val maxRootEnergy: Double,
        val velocityEnergyBudget: Double)
    internal data class Probe(val samples: FloatArray, val raw: FloatArray, val diagnostics: Diagnostics,
        val energyTrace: List<EnergyFrame>, val previousCycle: FloatArray? = null, val seamError: Double = 0.0,
        val stateError: Double = 0.0, val loopFrames: Int = 0, val effectiveHz: Double,
        val supplyCutoffSeconds: Double, val periodicCapture: Boolean = false,
        val radiationTrace: List<RadiationFrame> = emptyList(), val materialDiagnostics: MaterialDiagnostics? = null,
        val material: ShoalMaterial = ShoalMaterial.HARMONIC, val model: Int = HARMONIC_MODEL_VERSION) {
        val rawPeak get() = diagnostics.rawPeak
        val rawRms get() = diagnostics.rawRms
        val rawDc get() = diagnostics.rawDc
        val minEnergy get() = diagnostics.minEnergy
        val maxEnergySum get() = diagnostics.maxEnergySum
        val maxTransferResidual get() = diagnostics.maxTransferResidual
        val activePartials get() = diagnostics.activePartials
    }

    /** fractions[donor][receiver]. Read the complete old state; never cascade in iteration order. */
    internal fun isolatedTransfer(energy: DoubleArray, fractions: Array<DoubleArray>): DoubleArray {
        require(fractions.size == energy.size && fractions.all { it.size == energy.size })
        require(energy.all { it.isFinite() && it >= 0.0 })
        val next = DoubleArray(energy.size)
        for (j in energy.indices) {
            val row = fractions[j]
            require(row.all { it.isFinite() && it >= 0.0 })
            val outgoing = row.sum()
            require(outgoing <= 1.0 + 1e-12) { "SHOAL outgoing transfer exceeds donor energy" }
            next[j] += energy[j] * max(0.0, 1.0 - outgoing)
            for (i in row.indices) next[i] += row[i] * energy[j]
        }
        return next
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("SHOAL render cancelled")
    }
    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    private fun guard(hz: Double): Double = 1.0 - smooth((hz - BAND_START) / (BAND_END - BAND_START))
    private fun gaussian(x: Double, width: Double): Double = exp(-.5 * (x / width).pow(2))
    private fun release(t: Double, duration: Double, glow: Double): Double =
        smooth((duration - t) / (.35 + .30 * glow))
    private fun normalized(x: DoubleArray): DoubleArray {
        val sum = x.sum().coerceAtLeast(1e-30)
        return DoubleArray(x.size) { x[it] / sum }
    }
    private data class Node(val energy: DoubleArray, val centers: DoubleArray, val cents: DoubleArray, val supply: Double)
    private data class Trajectory(val nodes: List<Node>, val stepSeconds: Double, val frames: Int,
        val hz: Double, val cutoff: Double, val transferResidual: Double, val naturalSeconds: Double)

    private fun centers(t: Double, current: Double, voice: ShoalVoice): DoubleArray {
        val speed = .16 + .52 * current
        val phase = TWO_PI * speed * t
        val width = if (voice == ShoalVoice.GLASS) .8 else 1.0
        return doubleArrayOf(3.7 + width * 1.9 * sin(phase), 8.8 + width * 3.1 * sin(phase + .85))
    }

    /** A frame-fitted root, with control boundaries at exact output frames. */
    private fun loopPlan(hz: Double, seconds: Double): Pair<Int, Double> {
        val center = (seconds * hz).roundToInt()
        var bestFrames = 0
        var bestHz = hz
        var bestScore = Double.POSITIVE_INFINITY
        val search = max(8, (.45 * hz).roundToInt())
        for (cycles in (center - search).coerceAtLeast(8)..center + search) {
            val frames = ((cycles * Dsp.RATE / hz / CONTROL_FRAMES).roundToInt() * CONTROL_FRAMES).coerceAtLeast(1024)
            val fit = cycles * Dsp.RATE.toDouble() / frames
            val cents = abs(1200.0 * ln(fit / hz) / ln(2.0))
            val score = cents + .0005 * abs(frames.toDouble() / Dsp.RATE - seconds)
            if (score < bestScore) { bestScore = score; bestFrames = frames; bestHz = fit }
        }
        return bestFrames to bestHz
    }

    private fun legacyTrajectory(voice: ShoalVoice, m: Map<String, Float>, noteHz: Double, velocity: Double,
        seconds: Float?, ablation: Ablation, cancel: () -> Boolean): Trajectory {
        val density = m.getValue("DENSITY").toDouble()
        val current = m.getValue("CURRENT").toDouble()
        val cohesion = m.getValue("COHESION").toDouble()
        val glow = m.getValue("GLOW").toDouble()
        val stray = if (ablation == Ablation.NO_STRAY) 0.0 else m.getValue("STRAY").toDouble()
        val held = isLoop(m.getValue("HOLD"))
        val fullSeconds = if (voice == ShoalVoice.GLASS) 3.4 + 2.7 * glow else 4.2 + 3.2 * glow
        val finiteSeconds = seconds?.takeIf { it.isFinite() && it > 0f }?.toDouble()?.coerceAtMost(fullSeconds) ?: fullSeconds
        val plan = if (held) loopPlan(noteHz, if (voice == ShoalVoice.GLASS) 3.4 else 4.0)
            else (finiteSeconds * Dsp.RATE).roundToInt() to noteHz
        val frames = plan.first
        val hz = plan.second
        val steps = ceil(frames.toDouble() / CONTROL_FRAMES).toInt()
        val dt = CONTROL_FRAMES.toDouble() / Dsp.RATE
        // Maximal departure is reserved when choosing the bank; eligibility never toggles in motion.
        val possibleStrayRatio = 2.0.pow((if (held) 72.0 else 36.0) / 1200.0)
        val count = (2..MOVING_PARTIALS + 1).count { it * hz * possibleStrayRatio < BAND_END }
        val profile = normalized(DoubleArray(count) { i ->
            val n = i + 2.0
            val sparse = gaussian(n - 3.0, .45 + density * 1.7) + .60 * gaussian(n - 7.5, .65 + density * 2.1)
            val broad = 1.0 / n.pow(.9)
            (sparse * (1.0 - .70 * density) + broad * (.05 + 1.8 * density)) * guard(n * hz * possibleStrayRatio).pow(2)
        })
        // Velocity also changes the excitation geometry. Normalize again so concentration
        // never covertly changes the moving energy budget (nor makes DENSITY a gain knob).
        val effortWidth = .62 + .65 * velocity
        val initialProfile = normalized(DoubleArray(count) { i ->
            val n = i + 2.0
            val effort = .14 + gaussian(n - (3.0 + .25 * velocity), effortWidth * (.7 + density)) +
                .65 * gaussian(n - 7.5, effortWidth * (1.0 + density))
            profile[i] * effort
        })
        var energy = DoubleArray(count) { initialProfile[it] * (.40 + .60 * velocity) * velocity * velocity }
        val offsets = DoubleArray(count)
        val cutoff = if (held) Double.POSITIVE_INFINITY else if (voice == ShoalVoice.GLASS) .10 + .35 * glow else .55 + 1.25 * glow
        val captureBlend = if (held) max(16, steps / 5) else 0
        val preroll = if (held) steps else 0
        val runSteps = if (held) preroll + steps + captureBlend else steps
        val captured = ArrayList<Node>(runSteps + 1)
        var residual = 0.0
        for (step in 0..runSteps) {
            if (step % 32 == 0) checkCancelled(cancel)
            val t = step * dt
            val center = centers(t, current, voice)
            val supply = if (t < cutoff) (if (voice == ShoalVoice.GLASS) .30 else .72) * (.35 + .65 * glow) * velocity else 0.0
            captured += Node(energy.copyOf(), center, offsets.copyOf(), supply)
            if (step == runSteps) break
            val old = energy
            val total = old.sum().coerceAtLeast(1e-30)
            val local = DoubleArray(count) { i ->
                (old[i] + (if (i > 0) old[i - 1] else 0.0) + (if (i + 1 < count) old[i + 1] else 0.0)) / total
            }
            val flow = DoubleArray(count)
            val next = old.copyOf()
            if (count > 1) {
                val direction = cos(TWO_PI * (.16 + .52 * current) * t + .25)
                val fraction = 1.0 - exp(-(current * 15.0 + cohesion * 2.0) * dt)
                for (j in 0 until count) {
                    fun weight(i: Int): Double {
                        val n = i + 2.0
                        val centerAttraction = gaussian(n - center[0], .65 + 1.35 * density) + .7 * gaussian(n - center[1], .9 + density)
                        val occupied = if (ablation == Ablation.NO_LOCAL_COHESION) 0.0 else local[i]
                        val gathering = .25 + cohesion * (5.5 * centerAttraction + 8.0 * occupied)
                        val baseline = (profile[i].coerceAtLeast(1e-12) / profile[j].coerceAtLeast(1e-12)).pow(.22).coerceIn(.3, 3.0)
                        return gathering * baseline * exp((i - j) * current * direction * 1.6)
                    }
                    val left = if (j > 0) weight(j - 1) else 0.0
                    val right = if (j + 1 < count) weight(j + 1) else 0.0
                    val sum = left + right
                    // Only valid neighbors receive the bounded outgoing amount; boundaries retain
                    // unavailable routes through normalization rather than dropping energy.
                    if (sum > 0.0) {
                        val outgoing = old[j] * fraction
                        next[j] -= outgoing
                        flow[j] -= outgoing
                        if (j > 0) { val e = outgoing * left / sum; next[j - 1] += e; flow[j - 1] += e }
                        if (j + 1 < count) { val e = outgoing * right / sum; next[j + 1] += e; flow[j + 1] += e }
                    }
                }
                residual = max(residual, abs(next.sum() - old.sum()))
            }
            // Damping follows transport, then bounded supply; neither the controller nor cohesion
            // adds energy. Strictly positive losses persist at the all-high finite corner.
            for (i in 0 until count) {
                val region = i.toDouble() / max(1, count - 1)
                val loss = (1.35 - 1.00 * glow) + region * (2.8 - 2.5 * glow)
                next[i] = next[i].coerceAtLeast(0.0) * exp(-loss * dt) + supply * profile[i] * dt
                if (!held) {
                    // A source release, not an output repair: its squared ratio terminates the
                    // population smoothly and is visible in energy diagnostics. Earlier evidence
                    // windows keep the same natural note timeline.
                    val a = release(t, fullSeconds, glow)
                    val b = release(t + dt, fullSeconds, glow)
                    next[i] *= if (a > 1e-20) (b / a).pow(2) else 0.0
                }
            }
            val sum = next.sum()
            if (sum > ENERGY_LIMIT) for (i in next.indices) next[i] *= ENERGY_LIMIT / sum
            energy = next
            for (i in offsets.indices) {
                val n = i + 2.0
                val cluster0 = gaussian(n - center[0], 1.4 + density)
                val cluster1 = gaussian(n - center[1], 1.8 + density)
                val clustered = (cluster0 * sin(TWO_PI * .31 * t) + cluster1 * sin(TWO_PI * .23 * t + 1.7)) /
                    (cluster0 + cluster1 + .18)
                val loosened = .45 * sin(TWO_PI * (.17 + .018 * i) * t + i * .71)
                val coordinated = cohesion * clustered + (1.0 - cohesion) * loosened
                val motion = if (total > 1e-20) (flow[i] / total / dt).coerceIn(-1.0, 1.0) else 0.0
                val target = 36.0 * stray.pow(.75) * (.80 * coordinated + .20 * motion)
                offsets[i] += (target - offsets[i]) * (1.0 - exp(-dt / (.055 + .20 * glow)))
            }
        }
        if (!held) return Trajectory(freezeMean(captured, ablation), dt, frames, hz, cutoff, residual, fullSeconds)
        // Close only the control trajectory. Convex interpolation preserves positivity and budget.
        val nodes = ArrayList<Node>(steps + 1)
        for (i in 0 until steps) {
            val a = captured[preroll + i]
            if (i < captureBlend) {
                val b = captured[preroll + steps + i]
                val w = smooth(i.toDouble() / captureBlend)
                nodes += Node(DoubleArray(count) { b.energy[it] * (1 - w) + a.energy[it] * w },
                    DoubleArray(2) { b.centers[it] * (1 - w) + a.centers[it] * w },
                    DoubleArray(count) { b.cents[it] * (1 - w) + a.cents[it] * w }, a.supply)
            } else nodes += a
        }
        nodes += nodes.first().let { Node(it.energy.copyOf(), it.centers.copyOf(), it.cents.copyOf(), it.supply) }
        return Trajectory(freezeMean(nodes, ablation), dt, frames, hz, cutoff, residual, fullSeconds)
    }

    /** Model 2 is a material change, not a set of different knob defaults. GLASS excites separated
     * upper harmonic resonances and exchanges mainly along four-harmonic links. RIPPLE starts in
     * a rounded low/middle body, receives a traveling supply packet, and flows through adjacent
     * harmonics. Both remain integer-harmonic instruments with the same protected fundamental. */
    private fun materialTrajectory(voice: ShoalVoice, m: Map<String, Float>, noteHz: Double, velocity: Double,
        seconds: Float?, ablation: Ablation, cancel: () -> Boolean, modeRatios: DoubleArray? = null): Trajectory {
        val glass = voice == ShoalVoice.GLASS
        val density = m.getValue("DENSITY").toDouble()
        val current = m.getValue("CURRENT").toDouble()
        val cohesion = m.getValue("COHESION").toDouble()
        val glow = m.getValue("GLOW").toDouble()
        val stray = if (ablation == Ablation.NO_STRAY) 0.0 else m.getValue("STRAY").toDouble()
        val held = isLoop(m.getValue("HOLD"))
        val fullSeconds = if (glass) 2.4 + 3.4 * glow else 4.4 + 2.8 * glow
        val finiteSeconds = seconds?.takeIf { it.isFinite() && it > 0f }?.toDouble()?.coerceAtMost(fullSeconds) ?: fullSeconds
        val plan = if (held) loopPlan(noteHz, if (glass) 3.4 else 4.0)
            else (finiteSeconds * Dsp.RATE).roundToInt() to noteHz
        val frames = plan.first
        val hz = plan.second
        val steps = ceil(frames.toDouble() / CONTROL_FRAMES).toInt()
        val dt = CONTROL_FRAMES.toDouble() / Dsp.RATE
        val reserve = 2.0.pow((if (held) 84.0 else 42.0) / 1200.0)
        val count = modeRatios?.size ?: (2..MOVING_PARTIALS + 1).count { it * hz * reserve < BAND_END }
        val profile = normalized(DoubleArray(count) { i ->
            val n = i + 2.0
            val material = if (glass) {
                val w = .17 + .58 * density
                .10 * gaussian(n - 3.0, w) + .35 * gaussian(n - 7.0, w) +
                    .32 * gaussian(n - 11.0, w * 1.10) + .20 * gaussian(n - 16.0, w * 1.20) +
                    .13 * gaussian(n - 21.0, w * 1.25) + .045 * gaussian(n - 2.5, .65) +
                    (.004 + .15 * density.pow(1.5)) / n.pow(.65)
            } else {
                (gaussian(n - 3.3, 1.05 + 2.0 * density) + .35 * gaussian(n - 7.0, 1.4 + 1.5 * density) +
                    (.05 + .40 * density) / n.pow(.8)) * exp(-max(0.0, n - 10.0) / 3.0)
            }
            material * guard((modeRatios?.get(i) ?: n) * hz * reserve).pow(2)
        })
        val initialProfile = normalized(DoubleArray(count) { i ->
            val n = i + 2.0
            val effort = if (glass) {
                // Hard strikes reach the upper glass resonances; soft strikes keep their sparse
                // locations while weighting the lower members. This changes shape at equal gain.
                (.35 + .65 * velocity) + (.20 + .80 * velocity) * (n / 11.0).pow(.75)
            } else {
                .18 + gaussian(n - (2.5 + .7 * velocity), (1.0 + 1.7 * density) * (.7 + .5 * velocity)) +
                    .30 * gaussian(n - 6.0, 1.2 + density)
            }
            profile[i] * effort
        })
        // Both families start with the same finite spectral budget. Their identities come from
        // where this energy is put, how it is fed, and which graph moves it, not initial gain.
        var energy = DoubleArray(count) { initialProfile[it] * (.40 + .60 * velocity) * velocity * velocity }
        val offsets = DoubleArray(count)
        val cutoff = if (held) Double.POSITIVE_INFINITY else if (glass) .035 + .11 * glow else 1.6 + 1.5 * glow
        val blend = if (held) max(16, steps / 5) else 0
        val preroll = if (held) steps else 0
        val runSteps = if (held) preroll + steps + blend else steps
        val captured = ArrayList<Node>(runSteps + 1)
        var residual = 0.0
        val centerScale = min(1.0, (count + 1.0) / 22.0)
        val motionHz = if (glass) .10 + .24 * current else .16 + .90 * current
        for (step in 0..runSteps) {
            if (step % 32 == 0) checkCancelled(cancel)
            val t = step * dt
            val phase = TWO_PI * motionHz * t
            val centers = if (glass) doubleArrayOf((7.0 + .85 * sin(phase)) * centerScale,
                (14.0 + 1.9 * sin(phase + .9)) * centerScale)
            else doubleArrayOf(4.5 + 2.5 * sin(phase - .7), 8.0 + 2.5 * sin(phase - 1.5))
            val supply = if (t < cutoff) {
                // Supply is ENERGY, so it follows the same velocity effort as the initial
                // bank. An amplitude-like v supply over a v² strike made soft notes grow toward
                // a late loud crest instead of establishing a quiet, sustained population.
                (if (glass) { if (held) .34 else .45 } else .88) * (.40 + .60 * glow) *
                    (.40 + .60 * velocity) * velocity * velocity
            } else 0.0
            captured += Node(energy.copyOf(), centers, offsets.copyOf(), supply)
            if (step == runSteps) break
            val old = energy
            val total = old.sum().coerceAtLeast(1e-30)
            val local = DoubleArray(count) { i ->
                (old[i] + (if (i > 0) old[i - 1] else 0.0) + (if (i + 1 < count) old[i + 1] else 0.0)) / total
            }
            val next = old.copyOf()
            val flow = DoubleArray(count)
            val direction = if (glass) cos(phase + .3) else cos(phase - .7)
            val rate = if (glass) current * 7.0 + cohesion * .9 else current * 20.0 + cohesion * 3.0
            val fraction = 1.0 - exp(-rate * dt)
            for (j in 0 until count) {
                val receivers = if (glass) intArrayOf(j - 4, j - 1, j + 1, j + 4)
                    else intArrayOf(j - 1, j + 1, j + 2)
                val weights = DoubleArray(receivers.size)
                for (edge in receivers.indices) {
                    val i = receivers[edge]
                    if (i !in 0 until count) continue
                    val n = i + 2.0
                    val width = if (glass) .55 + .70 * density else 1.0 + 1.15 * density
                    val attraction = gaussian(n - centers[0], width) + .65 * gaussian(n - centers[1], width * 1.2)
                    val occupied = if (ablation == Ablation.NO_LOCAL_COHESION) 0.0 else local[i]
                    val gathering = .18 + cohesion * (5.0 * attraction + 7.0 * occupied)
                    val baseline = (profile[i].coerceAtLeast(1e-12) / profile[j].coerceAtLeast(1e-12))
                        .pow(if (glass) .65 else .27).coerceIn(.10, 8.0)
                    val link = if (glass) {
                        if (abs(i - j) == 4) 1.0 else .035 + .65 * density
                    } else if (i - j == 2) .18 + .35 * current else 1.0
                    weights[edge] = link * gathering * baseline * exp((i - j).coerceIn(-1, 1) * current * direction * (if (glass) .65 else 1.8))
                }
                val sum = weights.sum()
                if (sum > 0.0) {
                    val outgoing = old[j] * fraction
                    next[j] -= outgoing
                    flow[j] -= outgoing
                    for (edge in receivers.indices) if (weights[edge] > 0.0) {
                        val i = receivers[edge]
                        val amount = outgoing * weights[edge] / sum
                        next[i] += amount
                        flow[i] += amount
                    }
                }
            }
            residual = max(residual, abs(next.sum() - old.sum()))
            // RIPPLE's source travels inside the bank. It feeds a rounded packet around the
            // passing center, rather than sweeping a filter or the finished audio's brightness.
            val feed = if (glass) profile else normalized(DoubleArray(count) { i ->
                profile[i] * (.16 + gaussian(i + 2.0 - centers[0], .75 + .85 * density))
            })
            for (i in 0 until count) {
                val n = i + 2.0
                val region = i.toDouble() / max(1, count - 1)
                val loss = if (glass) {
                    // Modal residence differs even among upper glass groups: the sparse groups
                    // ring rather than dissolving into a common envelope. GLOW lengthens them.
                    (1.35 - .90 * glow) + region * (.8 - .6 * glow) +
                        (.35 - .22 * glow) * (.5 + .5 * cos(n * 1.63))
                } else (.98 - .48 * glow) + region * (2.0 - 1.2 * glow)
                next[i] = next[i].coerceAtLeast(0.0) * exp(-loss * dt) + supply * feed[i] * dt
                if (!held) {
                    val a = release(t, fullSeconds, glow)
                    val b = release(t + dt, fullSeconds, glow)
                    next[i] *= if (a > 1e-20) (b / a).pow(2) else 0.0
                }
            }
            val sum = next.sum()
            // With a .15 moving amplitude scale and at most 24 partials, this tighter model-2
            // supply budget preserves a coherent mix bound below .96, even in the top register.
            if (sum > 1.2) for (i in next.indices) next[i] *= 1.2 / sum
            energy = next
            for (i in offsets.indices) {
                val n = i + 2.0
                val c0 = gaussian(n - centers[0], if (glass) 1.1 else 1.8)
                val c1 = gaussian(n - centers[1], if (glass) 1.6 else 2.3)
                val grouped = (c0 * sin(phase * (if (glass) .67 else 1.0)) +
                    c1 * sin(phase * (if (glass) .91 else 1.0) + 1.2)) / (c0 + c1 + .15)
                val loose = .40 * sin(TWO_PI * (.17 + .018 * i) * t + i * .71)
                val moving = (flow[i] / total / dt).coerceIn(-1.0, 1.0)
                val target = (if (glass) 40.0 else 28.0) * stray.pow(.75) *
                    (.80 * (cohesion * grouped + (1.0 - cohesion) * loose) + .20 * moving)
                offsets[i] += (target - offsets[i]) * (1.0 - exp(-dt / (if (glass) .045 + .18 * glow else .08 + .25 * glow)))
            }
        }
        if (!held) return Trajectory(freezeMean(captured, ablation), dt, frames, hz, cutoff, residual, fullSeconds)
        val nodes = ArrayList<Node>(steps + 1)
        for (i in 0 until steps) {
            val a = captured[preroll + i]
            if (i < blend) {
                val b = captured[preroll + steps + i]
                val w = smooth(i.toDouble() / blend)
                nodes += Node(DoubleArray(count) { b.energy[it] * (1 - w) + a.energy[it] * w },
                    DoubleArray(2) { b.centers[it] * (1 - w) + a.centers[it] * w },
                    DoubleArray(count) { b.cents[it] * (1 - w) + a.cents[it] * w }, a.supply)
            } else nodes += a
        }
        nodes += nodes.first().let { Node(it.energy.copyOf(), it.centers.copyOf(), it.cents.copyOf(), it.supply) }
        return Trajectory(freezeMean(nodes, ablation), dt, frames, hz, cutoff, residual, fullSeconds)
    }

    /** Counterfactual audition: remove distribution motion without changing the average spectrum.
     * Trapezoidal control-time weights preserve each partial's integrated energy, total energy at
     * every node, and the original supply/STRAY/center trajectories. The normal model is simulated
     * first so this ablation cannot accidentally remove its flow-driven detune or gain envelope. */
    private fun freezeMean(nodes: List<Node>, ablation: Ablation): List<Node> {
        if (ablation != Ablation.NO_TRANSPORT) return nodes
        val mean = DoubleArray(nodes.first().energy.size)
        for (k in nodes.indices) {
            val weight = if (k == 0 || k == nodes.lastIndex) .5 else 1.0
            for (p in mean.indices) mean[p] += nodes[k].energy[p] * weight
        }
        val profile = normalized(mean)
        return nodes.map { node ->
            val total = node.energy.sum()
            node.copy(energy = DoubleArray(profile.size) { profile[it] * total })
        }
    }

    private fun harmonicProbe(voice: ShoalVoice, macros: Map<String, Float> = emptyMap(), midi: Int = rootMidi(voice),
        velocity: Float = 1f, seconds: Float? = null, ablation: Ablation = Ablation.NONE,
        cancelled: () -> Boolean = { false }, model: Int = HARMONIC_MODEL_VERSION): Probe {
        checkCancelled(cancelled)
        require(supportsModel(model)) { "unsupported SHOAL model $model" }
        require(midi in MIN_MIDI..MAX_MIDI) { "SHOAL MIDI must be $MIN_MIDI..$MAX_MIDI" }
        val v = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f).toDouble()
        val m = settled(voice, macros)
        val held = isLoop(m.getValue("HOLD"))
        val trajectory = if (model == LEGACY_MODEL_VERSION)
            legacyTrajectory(voice, m, Keys.midiHz(midi).toDouble(), v, seconds, ablation, cancelled)
        else materialTrajectory(voice, m, Keys.midiHz(midi).toDouble(), v, seconds, ablation, cancelled)
        val nodes = trajectory.nodes
        val count = nodes.first().energy.size
        val dt = trajectory.stepSeconds
        val hz = trajectory.hz
        val intervals = nodes.size - 1
        val delta = Array(nodes.size) { k -> DoubleArray(count) { p -> (p + 2.0) * hz * (2.0.pow(nodes[k].cents[p] / 1200.0) - 1.0) } }
        if (held) {
            // Trapezoidal integration is exact for the linear frequency interpolation used below.
            for (p in 0 until count) {
                var area = 0.0
                for (k in 0 until intervals) area += .5 * (delta[k][p] + delta[k + 1][p]) * dt
                val mean = area / (intervals * dt)
                for (k in delta.indices) delta[k][p] -= mean
            }
        }
        val integral = Array(nodes.size) { DoubleArray(count) }
        for (k in 0 until intervals) for (p in 0 until count)
            integral[k + 1][p] = integral[k][p] + .5 * (delta[k][p] + delta[k + 1][p]) * dt
        // Model 1's constants and arithmetic remain untouched. Model 2 gives the population
        // comparable power to its root, and restores extra root support only in the top register.
        val upperRegister = smooth((hz - 420.0) / 470.0)
        val movingGain = if (model == LEGACY_MODEL_VERSION) MOVING_GAIN
            else if (voice == ShoalVoice.GLASS) .15 - .01 * upperRegister else .15 - .015 * upperRegister
        val rootGain = if (model == LEGACY_MODEL_VERSION) ROOT_GAIN
            else if (voice == ShoalVoice.GLASS) .125 + .065 * upperRegister else .14 + .06 * upperRegister
        val amplitude = Array(nodes.size) { k -> DoubleArray(count) { p ->
            movingGain * sqrt(nodes[k].energy[p]) * guard((p + 2.0) * hz + delta[k][p])
        } }
        val phases = Dsp.phases(count + 1, Dsp.seedFor("SHOAL", model, voice.name, midi))
        val frames = if (held) 3 * trajectory.frames + 512 else trajectory.frames
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = FloatArray(frames * Dsp.OVERSAMPLE)
        val glow = m.getValue("GLOW").toDouble()
        val density = m.getValue("DENSITY").toDouble()
        val rootAttack = (if (model == LEGACY_MODEL_VERSION) {
            if (voice == ShoalVoice.GLASS) .004 else .012
        } else if (voice == ShoalVoice.GLASS) .0016 else .024) * (1.65 - .80 * v)
        val rootLoss = if (model == LEGACY_MODEL_VERSION) {
            if (voice == ShoalVoice.GLASS) 1.05 - .72 * glow else .75 - .49 * glow
        } else if (voice == ShoalVoice.GLASS) .80 - .45 * glow else .35 - .20 * glow
        val rootLevel = rootGain * v.pow(.85) * (1.0 - .12 * density)
        val loopSeconds = trajectory.frames.toDouble() / Dsp.RATE
        var peak = 0.0
        var sum = 0.0
        var squares = 0.0
        for (i in raw.indices) {
            if (i % (CONTROL_FRAMES * Dsp.OVERSAMPLE) == 0) checkCancelled(cancelled)
            val time = i.toDouble() / rate
            val within = if (held) (i % (trajectory.frames * Dsp.OVERSAMPLE)).toDouble() / rate else time
            val segment = min((within / dt).toInt(), intervals - 1).coerceAtLeast(0)
            val tau = within - segment * dt
            val mix = (tau / dt).coerceIn(0.0, 1.0)
            val rootEnvelope = if (held) 1.0 else (1.0 - exp(-time / rootAttack)) * exp(-rootLoss * time) * release(time, trajectory.naturalSeconds, glow)
            var sample = rootLevel * rootEnvelope * sin(TWO_PI * (phases[0] + hz * time))
            val attack = if (held) 1.0 else (1.0 - exp(-time / (rootAttack * 1.5)))
            for (p in 0 until count) {
                val departure = integral[segment][p] + delta[segment][p] * tau +
                    .5 * (delta[segment + 1][p] - delta[segment][p]) / dt * tau * tau
                val phase = phases[p + 1] + (p + 2.0) * hz * time + departure
                val a = amplitude[segment][p] * (1.0 - mix) + amplitude[segment + 1][p] * mix
                sample += a * attack * sin(TWO_PI * phase)
            }
            raw[i] = sample.toFloat()
            peak = max(peak, abs(sample)); sum += sample; squares += sample * sample
        }
        checkCancelled(cancelled)
        val conditioned = raw.copyOf()
        Tide.bandLimit(conditioned, rate)
        checkCancelled(cancelled)
        val down = Dsp.decimate(conditioned, Dsp.RATE)
        val out: FloatArray
        val previous: FloatArray?
        var seam = 0.0
        if (held) {
            previous = down.copyOfRange(trajectory.frames, 2 * trajectory.frames)
            out = down.copyOfRange(2 * trajectory.frames, 3 * trajectory.frames)
            val mean = out.sumOf { it.toDouble() } / out.size
            for (i in out.indices) { out[i] = (out[i] - mean).toFloat(); previous[i] = (previous[i] - mean).toFloat() }
            if (v > 0.0) seam = Keys.seamError(previous + out, trajectory.frames)
        } else {
            out = down
            previous = null
            val mean = out.sumOf { it.toDouble() } / out.size
            val dc = Dsp.OnePole(Dsp.RATE)
            for (i in out.indices) { val x = (out[i] - mean).toFloat(); out[i] = x - dc.lp(x, 8f) }
            if (seconds == null) Dsp.fadeTail(out)
        }
        val traceStride = max(1, (1.0 / (30 * dt)).roundToInt())
        val trace = nodes.indices.filter { it % traceStride == 0 || it == nodes.lastIndex }.map { k ->
            EnergyFrame(k * dt, nodes[k].energy.copyOf(), nodes[k].centers.copyOf(),
                DoubleArray(count) { p -> 1200 * ln(1 + delta[k][p] / ((p + 2.0) * hz)) / ln(2.0) },
                nodes[k].energy.sum(), nodes[k].supply)
        }
        val stateError = if (held) nodes.first().energy.indices.maxOfOrNull { abs(nodes.first().energy[it] - nodes.last().energy[it]) } ?: 0.0 else 0.0
        val closureError = if (held) (0 until count).maxOfOrNull { p ->
            val cycles = (p + 2.0) * hz * loopSeconds + integral.last()[p]
            abs(cycles - cycles.roundToInt())
        } ?: 0.0 else 0.0
        val diagnostics = Diagnostics(peak, sqrt(squares / raw.size), sum / raw.size,
            nodes.minOf { it.energy.minOrNull() ?: 0.0 }, nodes.maxOf { it.energy.sum() }, trajectory.transferResidual,
            count, delta.indices.maxOf { k -> (0 until count).maxOfOrNull { p -> (p + 2.0) * hz + delta[k][p] } ?: hz }, closureError)
        require(raw.all { it.isFinite() } && out.all { it.isFinite() }) { "SHOAL non-finite output" }
        require(!held || v == 0.0 || seam < Keys.MAX_SEAM_ERROR) { "SHOAL captured HOLD does not close: $seam" }
        return Probe(out, raw, diagnostics, trace, previous, seam, stateError,
            if (held) trajectory.frames else 0, hz, trajectory.cutoff, held)
    }

    /** Versioned dispatcher. Harmonic model 3 deliberately keeps model 2's exact oscillator
     * identity; material bodies are finite and never silently fall back to a harmonic HOLD. */
    internal fun probe(voice: ShoalVoice, macros: Map<String, Float> = emptyMap(), midi: Int = rootMidi(voice),
        velocity: Float = 1f, seconds: Float? = null, ablation: Ablation = Ablation.NONE,
        cancelled: () -> Boolean = { false }, model: Int = MODEL_VERSION,
        material: ShoalMaterial = ShoalMaterial.HARMONIC): Probe {
        checkCancelled(cancelled)
        require(supportsMaterial(model, material)) { "unsupported SHOAL model $model / material $material" }
        require(midi in MIN_MIDI..MAX_MIDI) { "SHOAL MIDI must be $MIN_MIDI..$MAX_MIDI" }
        val m = settled(voice, macros)
        require(!isLoop(m.getValue("HOLD")) || supportsHold(model, material)) {
            "SHOAL $material is finite only; HOLD requires HARMONIC"
        }
        if (material == ShoalMaterial.HARMONIC) {
            val harmonicModel = if (model == MODEL_VERSION) HARMONIC_MODEL_VERSION else model
            return harmonicProbe(voice, macros, midi, velocity, seconds, ablation, cancelled, harmonicModel)
                .copy(model = model, material = material)
        }
        return modalProbe(voice, m, midi, velocity, seconds, ablation, cancelled, material)
    }

    /** Model 3 ports the approved dry material study. The population remains the model-2 motion
     * donor, while separately driven modal and fundamental energies radiate through a material.
     * These energies are not claimed to be the conservative transport network. Velocity changes
     * contact time and normalized modal excitation, rather than only changing final amplitude. */
    private fun modalProbe(voice: ShoalVoice, m: Map<String, Float>, midi: Int, velocity: Float,
        seconds: Float?, ablation: Ablation, cancelled: () -> Boolean, material: ShoalMaterial): Probe {
        val v = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f).toDouble()
        val hz = Keys.midiHz(midi).toDouble()
        val reserve = 2.0.pow(42.0 / 1200.0)
        // Modal ratios are monotone. Reduce the actual physical bank, not an unrelated harmonic
        // bank, and normalize the donor's budget over those eligible modes. This is particularly
        // important for the wooden bar's ratio-40 upper modes on the top keyboard octave.
        val count = (0 until MOVING_PARTIALS).takeWhile { material.ratio(it) * hz * reserve < BAND_END }.size
        require(count > 0) { "SHOAL $material has no eligible modes at MIDI $midi" }
        val ratios = DoubleArray(count) { material.ratio(it) }
        val t60 = DoubleArray(count) { material.modeT60(it) }
        // Always simulate/calibrate the natural note, even for an early evidence window.
        val tr = materialTrajectory(voice, m, hz, v, null, ablation, cancelled, ratios)
        val nodes = tr.nodes
        val dt = tr.stepSeconds
        val cutoff = material.contactCutoff(v)
        val referenceCutoff = material.contactCutoff(.8f.toDouble())
        val referenceStrike = DoubleArray(count) { material.strike(it) }
        val strike = DoubleArray(count) { p ->
            val frequency = ratios[p] * hz
            referenceStrike[p] * (1.0 + (frequency / referenceCutoff).pow(2)) /
                (1.0 + (frequency / cutoff).pow(2))
        }
        val referenceBudget = nodes.first().energy.indices.sumOf { nodes.first().energy[it] * referenceStrike[it] }
        val contactBudget = nodes.first().energy.indices.sumOf { nodes.first().energy[it] * strike[it] }
        val contactScale = if (contactBudget > 1e-30) referenceBudget / contactBudget else 1.0
        for (p in strike.indices) strike[p] *= contactScale
        val sourceSteps = nodes.size - 1
        val tailSteps = ceil(.90 * max(material.rootT60, t60.maxOrNull() ?: material.rootT60) / dt).toInt()
        val intervals = sourceSteps + tailSteps
        val ring = Array(intervals + 1) { DoubleArray(count) }
        val rootRing = DoubleArray(intervals + 1)
        rootRing[0] = nodes[0].energy.sum()
        for (p in 0 until count) ring[0][p] = nodes[0].energy[p] * strike[p]
        for (k in 0 until intervals) {
            if (k % 32 == 0) checkCancelled(cancelled)
            val sourceActive = k < sourceSteps
            val old = if (sourceActive) nodes[k] else nodes.last()
            val next = if (sourceActive) nodes[k + 1] else nodes.last()
            val total = next.energy.sum().coerceAtLeast(1e-30)
            val arrivals = if (sourceActive) next.energy.indices.sumOf { max(0.0, next.energy[it] - old.energy[it]) } else 0.0
            val rootDrive = if (sourceActive) .32 * arrivals + .80 * old.supply * dt else 0.0
            rootRing[k + 1] = rootRing[k] * exp(-2 * ln(1000.0) * dt / material.rootT60) + rootDrive
            for (p in 0 until count) {
                val influx = if (sourceActive) max(0.0, next.energy[p] - old.energy[p]) else 0.0
                val feed = if (sourceActive) old.supply * next.energy[p] / total * dt else 0.0
                val drive = (influx + .55 * feed) * strike[p] * material.drive
                ring[k + 1][p] = ring[k][p] * exp(-2 * ln(1000.0) * dt / t60[p]) + drive
            }
        }
        val cents = Array(intervals + 1) { k -> if (k <= sourceSteps) nodes[k].cents.copyOf()
            else DoubleArray(count) { p -> nodes.last().cents[p] * exp(-(k - sourceSteps) * dt / .075) } }
        val frequency = Array(intervals + 1) { k -> DoubleArray(count) { p ->
            ratios[p] * hz * 2.0.pow(cents[k][p] / 1200.0)
        } }
        // Fixed per-note source calibration, never per-frame loudness correction. Use the actual
        // post-guard moving power so a high register does not lose the body beneath its root.
        val controlArea = nodes.sumOf { it.energy.sum() }
        val ringArea = (0..sourceSteps).sumOf { k -> (0 until count).sumOf { p ->
            ring[k][p] * guard(frequency[k][p]).pow(2)
        } }.coerceAtLeast(1e-30)
        val radiationScale = controlArea / ringArea
        for (k in ring.indices) for (p in 0 until count) ring[k][p] *= radiationScale
        val phaseArea = Array(intervals + 1) { DoubleArray(count) }
        for (k in 0 until intervals) for (p in 0 until count)
            phaseArea[k + 1][p] = phaseArea[k][p] + .5 * (frequency[k][p] + frequency[k + 1][p]) * dt
        val movingGain = .16
        val rootGain = material.rootGain
        val amplitudes = Array(intervals + 1) { k -> DoubleArray(count) { p ->
            movingGain * sqrt(ring[k][p]) * guard(frequency[k][p])
        } }
        val worstL1 = amplitudes.maxOf { it.sum() } + rootGain * sqrt(rootRing.maxOrNull() ?: 0.0)
        val trim = min(1.0, .86 / worstL1.coerceAtLeast(1e-30))
        val fullFrames = intervals * CONTROL_FRAMES
        check(fullFrames <= (MAX_MATERIAL_SECONDS * Dsp.RATE).roundToInt()) { "SHOAL material render exceeds its bound" }
        val requestedFrames = seconds?.takeIf { it.isFinite() && it > 0f }?.let {
            (it * Dsp.RATE).roundToInt().coerceIn(64, fullFrames)
        } ?: fullFrames
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = FloatArray(requestedFrames * Dsp.OVERSAMPLE)
        // Keep the approved study's carrier phases. Material identity is not a random phase change.
        val phases = Dsp.phases(count + 1, Dsp.seedFor("SHOAL", HARMONIC_MODEL_VERSION, voice.name, midi))
        val contactSeconds = material.contact * (1.65 - .8 * v)
        var peak = 0.0
        var sum = 0.0
        var squares = 0.0
        var rootSquares = 0.0
        var movingSquares = 0.0
        for (i in raw.indices) {
            if (i % (CONTROL_FRAMES * Dsp.OVERSAMPLE) == 0) checkCancelled(cancelled)
            val time = i.toDouble() / rate
            val segment = min((time / dt).toInt(), intervals - 1)
            val tau = time - segment * dt
            val mix = (tau / dt).coerceIn(0.0, 1.0)
            val attack = 1.0 - exp(-time / contactSeconds)
            val rootAmplitude = sqrt(rootRing[segment]) * (1 - mix) + sqrt(rootRing[segment + 1]) * mix
            val root = rootGain * attack * rootAmplitude * sin(TWO_PI * (phases[0] + hz * time))
            var moving = 0.0
            for (p in 0 until count) {
                val phase = phases[p + 1] + phaseArea[segment][p] + frequency[segment][p] * tau +
                    .5 * (frequency[segment + 1][p] - frequency[segment][p]) / dt * tau * tau
                val a = amplitudes[segment][p] * (1 - mix) + amplitudes[segment + 1][p] * mix
                moving += a * attack * sin(TWO_PI * phase)
            }
            val sample = (root + moving) * trim
            raw[i] = sample.toFloat()
            peak = max(peak, abs(sample)); sum += sample; squares += sample * sample
            rootSquares += root * root; movingSquares += moving * moving
        }
        checkCancelled(cancelled)
        val conditioned = raw.copyOf()
        Tide.bandLimit(conditioned, rate)
        checkCancelled(cancelled)
        val out = Dsp.decimate(conditioned, Dsp.RATE)
        val mean = out.sumOf { it.toDouble() } / out.size
        val dc = Dsp.OnePole(Dsp.RATE)
        for (i in out.indices) { val x = (out[i] - mean).toFloat(); out[i] = x - dc.lp(x, 8f) }
        if (requestedFrames == fullFrames) Dsp.fadeTail(out)
        var retained = 0.0
        var beforeGuard = 0.0
        for (k in ring.indices) for (p in 0 until count) {
            beforeGuard += ring[k][p]
            retained += ring[k][p] * guard(frequency[k][p]).pow(2)
        }
        val visibleEnd = min(intervals, ceil(requestedFrames.toDouble() / CONTROL_FRAMES).toInt())
        val stride = max(1, (1.0 / (30.0 * dt)).roundToInt())
        val radiation = (0..visibleEnd).filter { it % stride == 0 || it == visibleEnd }.map { k ->
            RadiationFrame(k * dt, ring[k].copyOf(), rootRing[k],
                if (k < sourceSteps) nodes[k].supply else 0.0, cents[k].copyOf())
        }
        val controlEnd = min(sourceSteps, visibleEnd)
        val controller = (0..controlEnd).filter { it % stride == 0 || it == controlEnd }.map { k ->
            EnergyFrame(k * dt, nodes[k].energy.copyOf(), nodes[k].centers.copyOf(), nodes[k].cents.copyOf(),
                nodes[k].energy.sum(), nodes[k].supply)
        }
        val diagnostics = Diagnostics(peak, sqrt(squares / raw.size), sum / raw.size,
            nodes.minOf { it.energy.minOrNull() ?: 0.0 }, nodes.maxOf { it.energy.sum() }, tr.transferResidual,
            count, frequency.maxOf { it.maxOrNull() ?: hz }, 0.0)
        val materialDiagnostics = MaterialDiagnostics(material, DoubleArray(count) { ratios[it] * hz }, ratios, t60,
            material.rootT60, rootGain, contactSeconds, cutoff, strike, trim, radiationScale,
            retained / beforeGuard.coerceAtLeast(1e-30), rootSquares / (rootSquares + movingSquares).coerceAtLeast(1e-30),
            tr.frames.toDouble() / Dsp.RATE, (fullFrames - tr.frames).toDouble() / Dsp.RATE,
            ring.maxOf { it.sum() }, rootRing.maxOrNull() ?: 0.0, (.40 + .60 * v) * v * v)
        check(raw.all { it.isFinite() } && out.all { it.isFinite() }) { "SHOAL material non-finite output" }
        return Probe(out, raw, diagnostics, controller, effectiveHz = hz,
            supplyCutoffSeconds = tr.cutoff, radiationTrace = radiation, materialDiagnostics = materialDiagnostics,
            material = material, model = MODEL_VERSION)
    }

    fun render(voice: ShoalVoice, macros: Map<String, Float> = emptyMap(), midi: Int = rootMidi(voice),
        velocity: Float = 1f, cancelled: () -> Boolean = { false }, model: Int = MODEL_VERSION,
        material: ShoalMaterial = ShoalMaterial.HARMONIC): Snip {
        val v = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f)
        val out = probe(voice, macros, midi, v, cancelled = cancelled, model = model, material = material).samples
        checkCancelled(cancelled)
        if (v > 0f) Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * v.pow(.65f))
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }
}
