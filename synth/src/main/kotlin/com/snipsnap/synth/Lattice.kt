package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The accepted source characters plus the accepted pluck-to-ring motion. */
enum class LatticeVoice { WARM, PLUCK, HOLLOW, TRACE }

/**
 * LATTICE, directional passage and a rejected outline, model 2.
 *
 * A deterministic harmonic source passes through ordered real two-component projections. Each
 * rejection coefficient is computed from the state surviving the preceding gates, not from the
 * original source again. Static coefficients are compiled per spectral group and rejection slot;
 * the resulting scalar multiplications are exactly the same ordered linear model.
 *
 * The source has a fixed gain for a given note, velocity and source profile. No output loudness normalization,
 * independent branch normalization, bypass oscillator or samples are used here. Finite notes
 * retain the model-2 source and projection path. Source-voice HOLD extends the same effort and
 * returns periodic sustain at its top step. TRACE instead gives the accepted moving pluck a
 * saved SPEED and a bounded finite HOLD; it never promises a loop.
 * Timbral constants are authored choices, not measured physical parameters.
 */
object Lattice {
    const val MODEL_VERSION = 2
    const val DEFAULT_MIDI = 48
    const val MIDI_MIN = 36
    const val MIDI_MAX = 84
    const val TUNE_SEMITONES = 24
    const val HOLD_LOOP = .99f
    const val LOOP_THRESHOLD = HOLD_LOOP
    const val GROUPS = 4
    const val PARTIALS = 24
    const val SLOTS = 16
    const val HALO_MODES = 12
    /** Conservative triangle bound from the 16 summed-stage taps to mono, before output conditioning. */
    const val HALO_STAGE_VECTOR_GAIN_BOUND = 12.6
    /** Four unrestricted coherent group copies add a factor sqrt(4) to the stage-vector bound. */
    const val HALO_FULL_VECTOR_GAIN_BOUND = 25.2
    const val RESTORATION_DURATION_SECONDS = 9.6
    const val RESTORATION_EXCITATION_SECONDS = 8.5
    internal const val CONTROL_BLOCK = 64
    private const val SOURCE_RMS = .13
    private const val HALO_GAIN = 1.5
    private const val BANDWIDTH_HZ = 18_000.0
    private const val LOG_1000 = 6.907755278982137

    fun macrosFor(voice: LatticeVoice): List<MacroSpec> {
        // TRACE exposes the one accepted motion and its timing, rather than duplicating its
        // authored geometry as extra controls. Existing source voices retain their full contract.
        if (voice == LatticeVoice.TRACE) return listOf(MacroSpec("TUNE", .5f, .5f),
            MacroSpec("SPEED", .5f, .5f), MacroSpec("HOLD", 0f, 0f))
        val d = when (voice) {
            LatticeVoice.WARM -> floatArrayOf(1f, sqrt(6f / 14f), .5f, .45f, 0f)
            LatticeVoice.PLUCK -> floatArrayOf(.85f, sqrt(2f / 14f), .78f, .7f, .35f)
            LatticeVoice.HOLLOW -> floatArrayOf(.85f, sqrt(2f / 14f), .5f, .7f, .15f)
            LatticeVoice.TRACE -> error("TRACE uses its motion controls")
        }
        return listOf(MacroSpec("TUNE", .5f, .5f), MacroSpec("TURN", d[0], 0f),
            MacroSpec("STAGES", d[1], 0f), MacroSpec("TWIST", d[2], .5f),
            MacroSpec("COLOR", d[3], 0f), MacroSpec("REJECT", d[4], 0f),
            MacroSpec("HOLD", 0f, 0f))
    }

    fun defaults(voice: LatticeVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    fun settled(voice: LatticeVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).toMutableMap().also { m ->
            for ((key, value) in macros) if (key in m && value.isFinite()) m[key] = value.coerceIn(0f, 1f)
        }

    fun midiFor(@Suppress("UNUSED_PARAMETER") voice: LatticeVoice, tune: Float, midi: Int = DEFAULT_MIDI): Int {
        require(midi in MIDI_MIN..MIDI_MAX) { "LATTICE MIDI must be $MIDI_MIN..$MIDI_MAX" }
        val t = (tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f)
        return (midi + ((t - .5f) * TUNE_SEMITONES).roundToInt()).coerceIn(MIDI_MIN, MIDI_MAX)
    }

    fun frequencyFor(voice: LatticeVoice, tune: Float, midi: Int = DEFAULT_MIDI): Float =
        Keys.midiHz(midiFor(voice, tune, midi))

    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= HOLD_LOOP

    /** TRACE HOLD is a bounded note extension, not a periodic sustain segment. */
    fun isLoop(voice: LatticeVoice, hold: Float): Boolean = voice != LatticeVoice.TRACE && isLoop(hold)

    fun drumClassFor(voice: LatticeVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(voice, settled(voice, macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    fun scramble(voice: LatticeVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(settled(voice, near?.macros ?: emptyMap()), temperature, random)
            .toMutableMap().also { it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f) }

    /**
     * Native instrument entry point. Source voices retain their established static geometry and
     * periodic HOLD. TRACE renders one saved motion with finite effort; no route adds strikes
     * or levels the result.
     */
    fun render(voice: LatticeVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = .75f, cancelled: () -> Boolean = { false }): Snip {
        stopIfUnwanted(cancelled)
        val m = settled(voice, macros)
        if (voice == LatticeVoice.TRACE) return traceProbe(m, midi, velocity, cancelled).snip
        val hold = m.getValue("HOLD")
        val settings = settingsFor(voice, m, midi, velocity)
        if (isLoop(hold)) return heldProbe(settings, cancelled).snip
        return perform(settings, controls = null, effortHoldSeconds = settings.excitationSeconds - .26, cancelled = cancelled).snip
    }

    /** Authoritative control-to-model mapping shared by the instrument and its audition. */
    fun settingsFor(voice: LatticeVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = .75f): Settings {
        val m = settled(voice, macros)
        val hold = m.getValue("HOLD")
        if (voice == LatticeVoice.TRACE) return LatticeMotion.settings(m.getValue("SPEED"), hold,
            midiFor(voice, m.getValue("TUNE"), midi), velocity)
        val excitation = .26 + 3.24 * (hold / HOLD_LOOP).coerceIn(0f, 1f)
        return Settings(midi = midiFor(voice, m.getValue("TUNE"), midi), velocity = velocity,
            turn = m.getValue("TURN").toDouble(), stages = m.getValue("STAGES").toDouble(),
            twist = m.getValue("TWIST").toDouble(), color = m.getValue("COLOR").toDouble(),
            reject = m.getValue("REJECT").toDouble(), durationSeconds = min(6.0, excitation + 1.8),
            excitationSeconds = excitation, sourceProfile = SourceProfile.valueOf(voice.name), contour = Contour.PLUCK)
    }

    fun renderLoop(voice: LatticeVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = .75f, cancelled: () -> Boolean = { false }): FloatArray {
        require(voice != LatticeVoice.TRACE) { "TRACE HOLD extends one finite motion; it does not render a loop" }
        return render(voice, macros + ("HOLD" to 1f), midi, velocity, cancelled).samples
    }

    /** The native motion's real branch output, available to numerical and audition probes. */
    internal fun traceProbe(macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = .75f, cancelled: () -> Boolean = { false }): Probe {
        stopIfUnwanted(cancelled)
        val m = settled(LatticeVoice.TRACE, macros)
        val plan = LatticeMotion.plan(m.getValue("SPEED"), cancelled)
        val holdSeconds = LatticeMotion.MAX_HOLD_SECONDS * m.getValue("HOLD")
        val referenceHold = holdSeconds / plan.scale
        val settings = settingsFor(LatticeVoice.TRACE, m, midi, velocity)
        return perform(settings, controls = null, effortHoldSeconds = referenceHold, cancelled = cancelled,
            motion = plan, referenceExcitationSeconds = LatticeMotion.REFERENCE_EXCITATION_SECONDS + referenceHold)
    }

    private fun stopIfUnwanted(cancelled: () -> Boolean = { false }) {
        if (Thread.currentThread().isInterrupted || cancelled())
            throw java.util.concurrent.CancellationException("LATTICE render interrupted")
    }

    /**
     * R2 listening proposals, deliberately softer than the original undamped harmonic stack.
     * WARM uses h^-1.05 * exp(-(h-1)/6.5). PLUCK's first eight amplitudes are
     * [1, .8, .5, .3, .15, .09, .05, .025], followed by .012 * exp(-(h-9)/1.8).
     * HOLLOW starts [1, .04, .65, .03, .26, .02, .08, .012]; its upper odd harmonics start
     * at .025 on h9 and decay over 2.5 harmonic steps, while upper evens start at .005 on h10.
     * These spectra are authored source choices awaiting listening, not physical instrument models.
     */
    enum class SourceProfile { WARM, PLUCK, HOLLOW }

    /** SUSTAIN keeps effort constant for causal fixtures; PLUCK supplies one finite decaying excitation. */
    enum class Contour { SUSTAIN, PLUCK }

    data class Settings(
        val midi: Int = 60,
        val velocity: Float = .75f,
        val turn: Double = .75,
        val stages: Double = .4,
        val twist: Double = .5,
        val color: Double = 0.0,
        val reject: Double = 0.0,
        val durationSeconds: Double = 3.0,
        val excitationSeconds: Double = 2.0,
        val gateCount: Double? = null,
        val haloEnabled: Boolean = true,
        val sourceProfile: SourceProfile = SourceProfile.WARM,
        val contour: Contour = Contour.SUSTAIN,
    ) {
        init {
            require(midi in 36..84) { "LATTICE MIDI must be 36..84" }
            require(velocity.isFinite() && velocity in 0f..1f) { "LATTICE velocity must be 0..1" }
            require(durationSeconds.isFinite() && durationSeconds in .05..30.0) { "LATTICE finite duration must be .05..30 seconds" }
            require(excitationSeconds.isFinite() && excitationSeconds > 0.0 && excitationSeconds <= durationSeconds) {
                "LATTICE excitation must be positive and fit inside the finite render"
            }
            controls()
        }

        fun controls(): Controls = Controls(turn, stages, twist, color, reject, gateCount)
    }

    data class Controls(
        val turn: Double = .75,
        val stages: Double = .4,
        val twist: Double = .5,
        val color: Double = 0.0,
        val reject: Double = 0.0,
        val gateCount: Double? = null,
    ) {
        init {
            require(listOf(turn, stages, twist, color, reject).all { it.isFinite() && it in 0.0..1.0 }) {
                "LATTICE controls must be finite and in 0..1"
            }
            require(gateCount == null || gateCount.isFinite() && gateCount in 2.0..16.0) {
                "LATTICE explicit total gate count must be 2..16"
            }
        }
    }

    data class Vector(val x: Double, val y: Double)
    data class GateResult(val forward: Vector, val rejected: Double)
    data class ChainResult(
        val forward: Vector,
        val passed: Double,
        val rejected: DoubleArray,
        val allocationResidual: Double,
    )

    data class GroupGeometry(
        val sourceAngle: Double,
        val angles: DoubleArray,
        val insertions: DoubleArray,
        val transmission: Double,
        val rejection: DoubleArray,
        val allocationResidual: Double,
    )

    data class Geometry(val groups: List<GroupGeometry>, val effectiveGates: Double)

    /**
     * The fundamental alone retains the common reference geometry. R2 moves the audible second
     * and third harmonics into a colored group, rather than shielding the first three harmonics
     * together and leaving COLOR to work mainly on the quiet top of the source.
     */
    fun groupForHarmonic(harmonic: Int): Int {
        require(harmonic in 1..PARTIALS) { "LATTICE harmonic must be 1..$PARTIALS" }
        return when (harmonic) {
            1 -> 0
            in 2..3 -> 1
            in 4..6 -> 2
            else -> 3
        }
    }

    data class Diagnostics(
        val groupTransmission: DoubleArray,
        /** Integrated squared rejection before collection, summed over independently tracked groups. */
        val stageRejectedEnergy: DoubleArray,
        /** Maximum complete-chain geometric allocation error over the render's control blocks. */
        val allocationResidual: Double,
        val sourceGain: Double,
        val renderMillis: Double,
        val rawPeak: Double,
        val peak: Double,
        val rms: Double,
        val dc: Double,
        val dryPeak: Double,
        val dryRms: Double,
        val haloPeak: Double,
        val haloRms: Double,
        val branchCorrelation: Double,
    )

    data class Probe(val snip: Snip, val dry: Snip, val halo: Snip, val diagnostics: Diagnostics)

    /**
     * A partial insertion contracts the orthogonal component by (1-a). Its signed collection
     * coefficient is sqrt(1-(1-a)^2), so forward and rejected squared norms partition the input.
     */
    fun project(input: Vector, theta: Double, insertion: Double = 1.0): GateResult {
        require(input.x.isFinite() && input.y.isFinite() && theta.isFinite()) { "LATTICE projection needs finite state and angle" }
        require(insertion.isFinite() && insertion in 0.0..1.0) { "LATTICE insertion must be 0..1" }
        if (insertion == 0.0) return GateResult(input, 0.0)
        val c = cos(theta)
        val s = sin(theta)
        val passed = c * input.x + s * input.y
        val rejected = -s * input.x + c * input.y
        val remaining = 1.0 - insertion
        return GateResult(
            Vector(passed * c - remaining * rejected * s, passed * s + remaining * rejected * c),
            sqrt(max(0.0, 1.0 - remaining * remaining)) * rejected,
        )
    }

    /** An ordered path, including its entrance and analyzer. The last read is in the analyzer's basis. */
    fun trace(input: Vector, angles: DoubleArray, insertions: DoubleArray): ChainResult {
        require(angles.isNotEmpty() && angles.size == insertions.size) { "LATTICE trace needs matching nonempty gates" }
        var v = input
        val rejected = DoubleArray(angles.size)
        for (j in angles.indices) {
            val result = project(v, angles[j], insertions[j])
            v = result.forward
            rejected[j] = result.rejected
        }
        val finalAngle = angles.last()
        val passed = cos(finalAngle) * v.x + sin(finalAngle) * v.y
        val residual = input.x * input.x + input.y * input.y - v.x * v.x - v.y * v.y - rejected.sumOf { it * it }
        return ChainResult(v, passed, rejected, residual)
    }

    /**
     * Slots 0 and 15 are fixed entrance and analyzer IDs. Intermediate IDs 1..14 activate in
     * order. During the k-to-(k+1) transition, existing progress values migrate from j/(k+1)
     * to j/(k+2), while slot k+1 inserts continuously at (k+1)/(k+2). Integer counts therefore
     * recover exactly equally spaced active gates; no surviving slot or halo state is reset.
     *
     * TWIST is a monotonic power curve from t^.22 to t^4.5, with t at the reference .5 setting.
     * COLOR uses different source, middle and final offsets for each group, not common rotation.
     */
    fun compile(controls: Controls): Geometry {
        val count = controls.gateCount ?: (2.0 + 14.0 * controls.stages * controls.stages)
        val full = min(14, floor(count - 2.0).toInt())
        val fraction = if (full < 14) count - 2.0 - full else 0.0
        val insert = smooth(fraction)
        val progress = DoubleArray(SLOTS)
        val strengths = DoubleArray(SLOTS)
        strengths[0] = 1.0
        strengths[SLOTS - 1] = 1.0
        progress[SLOTS - 1] = 1.0
        for (j in 1 until SLOTS - 1) {
            if (j <= full) {
                progress[j] = mix(j.toDouble() / (full + 1), j.toDouble() / (full + 2), insert)
                strengths[j] = 1.0
            } else if (j == full + 1 && full < 14) {
                progress[j] = j.toDouble() / (full + 2)
                strengths[j] = insert
            } else {
                progress[j] = j.toDouble() / (SLOTS - 1)
            }
        }
        val exponent = exp((controls.twist - .5) * 2.0 * kotlin.math.ln(4.5))
        val sourceOffsets = doubleArrayOf(0.0, .16, -.22, .28)
        val middleOffsets = doubleArrayOf(0.0, .58, -.72, .92)
        val finalOffsets = doubleArrayOf(0.0, .35, -.60, .70)
        val groups = (0 until GROUPS).map { g ->
            val sourceAngle = controls.color * sourceOffsets[g]
            val angles = DoubleArray(SLOTS) { j ->
                val t = progress[j]
                val shaped = t.pow(exponent)
                controls.turn * PI / 2.0 * shaped + controls.color *
                    (middleOffsets[g] * sin(PI * t) + finalOffsets[g] * t)
            }
            val result = trace(Vector(cos(sourceAngle), sin(sourceAngle)), angles, strengths)
            GroupGeometry(sourceAngle, angles, strengths.copyOf(), result.passed, result.rejected, result.allocationResidual)
        }
        return Geometry(groups, count)
    }

    fun render(settings: Settings = Settings()): Snip = probe(settings).snip

    /**
     * Controls, when supplied, are sampled at 64 internal frames (0.363 ms at 4x). Geometry is
     * compiled exactly at every control block; no gate or collector state is reset. Smooth authored
     * trajectories are expected. There is no interpolation of completed audio or makeup gain.
     */
    fun probe(settings: Settings = Settings(), controls: ((Double) -> Controls)? = null): Probe =
        perform(settings, controls)

    private fun perform(settings: Settings, controls: ((Double) -> Controls)?, effortHoldSeconds: Double = 0.0,
        cancelled: () -> Boolean = { false }, motion: LatticeMotion.Plan? = null,
        referenceExcitationSeconds: Double = settings.excitationSeconds): Probe {
        val started = System.nanoTime()
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        // Native motion owns a whole output-frame budget. Rounding at the internal rate
        // first can leave two surplus samples, which the two 2:1 decimators floor away at
        // half-frame durations. The established probe/static path keeps its original budget.
        val frames = if (motion != null) {
            (settings.durationSeconds * Dsp.RATE).roundToInt().coerceAtLeast(1) * Dsp.OVERSAMPLE
        } else (settings.durationSeconds * rate).roundToInt().coerceAtLeast(1)
        val dryRaw = FloatArray(frames)
        val haloRaw = FloatArray(frames)
        val mixedRaw = FloatArray(frames)
        val bank = Source(settings, rate)
        val halo = if (settings.haloEnabled) Halo(Keys.midiHz(settings.midi).toDouble(), rate) else null
        val stageEnergy = DoubleArray(SLOTS)
        val groupEnergy = DoubleArray(GROUPS)
        val groupSamples = DoubleArray(GROUPS)
        var geometry = motion?.block(0)?.geometry ?: compile(controls?.invoke(0.0) ?: settings.controls())
        var drives = if (halo == null) null else motion?.block(0)?.drives ?: halo.drives(geometry)
        var residual = geometry.groups.maxOf { abs(it.allocationResidual) }
        var rawPeak = 0.0
        var mixAmount = settings.reject
        val attack = when (settings.contour) {
            Contour.SUSTAIN -> .040 - .020 * settings.velocity
            Contour.PLUCK -> .025 - .013 * settings.velocity
        }
        val release = min(.16, referenceExcitationSeconds * .3)
        // One decaying source excites the same gates and collector. No unrelated pluck or
        // post-chain filter is mixed in to disguise the original steady source.
        val decaySeconds = when (settings.sourceProfile) {
            SourceProfile.WARM -> .32
            SourceProfile.PLUCK -> .23
            SourceProfile.HOLLOW -> .38
        }

        fun collectBlockEnergy() {
            for (s in 0 until SLOTS) {
                for (g in 0 until GROUPS) {
                    val r = geometry.groups[g].rejection[s]
                    stageEnergy[s] += r * r * groupEnergy[g] / rate
                }
            }
            groupEnergy.fill(0.0)
        }

        for (i in 0 until frames) {
            if (i % CONTROL_BLOCK == 0) {
                stopIfUnwanted(cancelled)
                if (i > 0) collectBlockEnergy()
                if (motion != null) {
                    val block = motion.block(i / CONTROL_BLOCK)
                    geometry = block.geometry
                    drives = if (halo == null) null else block.drives
                    residual = max(residual, geometry.groups.maxOf { abs(it.allocationResidual) })
                    mixAmount = block.reject
                } else {
                    val c = controls?.invoke(i.toDouble() / rate) ?: settings.controls()
                    if (controls != null || i == 0) {
                        geometry = compile(c)
                        drives = halo?.drives(geometry)
                        residual = max(residual, geometry.groups.maxOf { abs(it.allocationResidual) })
                    }
                    mixAmount = c.reject
                }
            }
            val physicalTime = i.toDouble() / rate
            val t = motion?.sourceTime(physicalTime) ?: physicalTime
            val envelope = when {
                t >= referenceExcitationSeconds -> 0.0
                t < attack -> .5 - .5 * cos(PI * t / attack)
                t > referenceExcitationSeconds - release -> .5 - .5 * cos(PI * (referenceExcitationSeconds - t) / release)
                else -> 1.0
            }
            val decayTime = if (effortHoldSeconds > 0.0) max(0.0, t - effortHoldSeconds) else t
            val articulation = if (settings.contour == Contour.PLUCK) exp(-decayTime / decaySeconds) else 1.0
            bank.next(groupSamples, envelope * articulation)
            var dry = 0.0
            for (g in 0 until GROUPS) {
                dry += groupSamples[g] * geometry.groups[g].transmission
                groupEnergy[g] += groupSamples[g] * groupSamples[g]
            }
            val collected = if (halo != null && drives != null) halo.process(groupSamples, drives!!) else 0.0
            check(dry.isFinite() && collected.isFinite()) { "LATTICE generated a non-finite branch at frame $i" }
            dryRaw[i] = dry.toFloat()
            haloRaw[i] = collected.toFloat()
            val mixed = (1.0 - mixAmount) * dry + mixAmount * collected
            mixedRaw[i] = mixed.toFloat()
            rawPeak = max(rawPeak, max(abs(dry), max(abs(collected), abs(mixed))))
        }
        collectBlockEnergy()

        // Identical linear conditioning on both branches preserves the measured reference ratios.
        val dry = condition(dryRaw)
        val rejected = condition(haloRaw)
        // Mix before antialiasing/decimation so even an authored changing REJECT trajectory
        // takes the same oversampled output path. The isolated stems remain at the common gain.
        val out = condition(mixedRaw)
        // A convex collector mix has no correlation-dependent equal-power boost. Its RMS includes
        // the actual dry/halo covariance; stems retain one common source scale, even at REJECT=1.
        // No limiter is allowed to conceal a source/collector gain error in this listening proof.
        check(out.all { it.isFinite() } && out.all { abs(it) <= .99f }) {
            "LATTICE exceeded declared raw headroom; fix source/collector gain before audition"
        }
        val cross = dry.indices.sumOf { dry[it].toDouble() * rejected[it] }
        val dryPower = dry.sumOf { it.toDouble() * it }
        val haloPower = rejected.sumOf { it.toDouble() * it }
        val correlation = if (dryPower > 1e-30 && haloPower > 1e-30) cross / sqrt(dryPower * haloPower) else 0.0
        val diagnostics = Diagnostics(
            geometry.groups.map { it.transmission }.toDoubleArray(), stageEnergy, residual,
            bank.gain, (System.nanoTime() - started) / 1_000_000.0, rawPeak,
            peak(out), rms(out), out.sumOf { it.toDouble() } / out.size,
            peak(dry), rms(dry), peak(rejected), rms(rejected), correlation,
        )
        return Probe(
            Snip(out, channels = 1, sampleRate = Dsp.RATE),
            Snip(dry, channels = 1, sampleRate = Dsp.RATE),
            Snip(rejected, channels = 1, sampleRate = Dsp.RATE), diagnostics,
        )
    }

    /** Public cue recipe for the single-source, common-gain restoration demonstration. */
    fun restorationControls(seconds: Double): Controls {
        val times = doubleArrayOf(0.0, 1.0, 1.6, 2.3, 3.1, 4.1, 5.0, 6.0, 7.0, 8.5)
        val turns = doubleArrayOf(0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
        val counts = doubleArrayOf(2.0, 2.0, 2.0, 2.0, 3.0, 3.0, 4.0, 4.0, 16.0, 16.0)
        var j = 0
        while (j < times.lastIndex - 1 && seconds >= times[j + 1]) j++
        val amount = smooth(((seconds - times[j]) / (times[j + 1] - times[j])).coerceIn(0.0, 1.0))
        return Controls(turn = mix(turns[j], turns[j + 1], amount), color = 0.0, reject = 0.0,
            gateCount = mix(counts[j], counts[j + 1], amount))
    }

    fun restorationGesture(midi: Int = 48): Probe = probe(Settings(
        midi = midi, velocity = .75f, durationSeconds = RESTORATION_DURATION_SECONDS,
        excitationSeconds = RESTORATION_EXCITATION_SECONDS, haloEnabled = false,
    ), ::restorationControls)

    /**
     * Euclidean frequency-response gain from the 16 summed-stage taps to the mono halo. This
     * uses the actual collector rows and resonator coefficients, so an audition can measure the
     * bank instead of confusing a per-mode collector bound with the complete output bound.
     * Independent copies of all four group tap vectors have a further sqrt(4) worst-case factor;
     * the static harmonic source partitions those groups into disjoint harmonic regions.
     */
    fun haloStageVectorGain(midi: Int, frequencyHz: Double): Double {
        require(midi in 36..84 && frequencyHz.isFinite() && frequencyHz in 0.0..(Dsp.RATE / 2.0))
        return Halo(Keys.midiHz(midi).toDouble(), Dsp.RATE * Dsp.OVERSAMPLE).operatorGain(frequencyHz)
    }

    data class HeldProbe(
        val snip: Snip,
        /** A separately generated preceding cycle, retained only for seam evidence. */
        val previous: FloatArray,
        val seamError: Double,
        val fittedHz: Double,
        val requestedHz: Double,
        val loopFrames: Int,
        val renderMillis: Double,
    )

    private data class Carrier(val harmonic: Int, val group: Int, val amplitude: Double,
        val sine: Double, val cosine: Double)
    private data class Response(val real: Double, val imaginary: Double)

    /**
     * A two-second held note, made from the same source and the exact static halo response.
     * The gate chain and feed-forward collector are linear. Evaluating their transfer at the
     * source's own harmonics therefore gives the settled result of those filters, rather than
     * inventing a parallel oscillator or waiting several decay times for a cold halo to settle.
     *
     * An integer number of note periods is fitted to whole output frames. The pitch movement
     * is below .02 cents across the supported notes. Source weights, phases and fixed gain stay
     * those of the requested MIDI note. The usual 4x band limit, decimation and DC blocker still
     * run on real oversampled audio. A half-second conditioning guard precedes two independently
     * generated cycles; a postroll keeps the resampler's edge outside both. No fade, crossfade,
     * normalization, loop repair or second attack is used to make the evidence pass.
     *
     * Setup is bounded: one four-group geometry, 12x24 scalar frequency responses and 24 carrier
     * coefficients per note. These small coefficients are computed once, never in the audio loop.
     */
    fun heldProbe(settings: Settings = Settings(midi = DEFAULT_MIDI), cancelled: () -> Boolean = { false }): HeldProbe {
        val started = System.nanoTime()
        stopIfUnwanted(cancelled)
        val requested = Keys.midiHz(settings.midi).toDouble()
        val periods = (2.0 * requested).roundToInt().coerceAtLeast(1)
        val frames = (periods * Dsp.RATE.toDouble() / requested).roundToInt()
        val fitted = periods * Dsp.RATE.toDouble() / frames
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val geometry = compile(settings.controls())
        val source = Source(settings, rate)
        val halo = if (settings.haloEnabled && settings.reject > 0.0) Halo(fitted, rate) else null
        val drives = halo?.drives(geometry)
        val carriers = source.heldCarriers()
        val sine = DoubleArray(PARTIALS)
        val cosine = DoubleArray(PARTIALS)
        val stepSin = DoubleArray(PARTIALS)
        val stepCos = DoubleArray(PARTIALS)
        for ((p, carrier) in carriers.withIndex()) {
            val hz = fitted * carrier.harmonic
            val h = if (halo != null && drives != null) halo.response(drives, carrier.group, hz) else Response(0.0, 0.0)
            val real = (1.0 - settings.reject) * geometry.groups[carrier.group].transmission + settings.reject * h.real
            val imaginary = settings.reject * h.imaginary
            sine[p] = carrier.amplitude * (carrier.sine * real + carrier.cosine * imaginary)
            cosine[p] = carrier.amplitude * (carrier.cosine * real - carrier.sine * imaginary)
            val angle = 2.0 * PI * hz / rate
            stepSin[p] = sin(angle)
            stepCos[p] = cos(angle)
        }
        val preroll = Dsp.RATE / 2
        val postroll = 512
        val raw = FloatArray((preroll + 2 * frames + postroll) * Dsp.OVERSAMPLE)
        for (i in raw.indices) {
            if (i % CONTROL_BLOCK == 0) stopIfUnwanted(cancelled)
            var sum = 0.0
            for (p in 0 until PARTIALS) {
                sum += sine[p]
                val next = sine[p] * stepCos[p] + cosine[p] * stepSin[p]
                cosine[p] = cosine[p] * stepCos[p] - sine[p] * stepSin[p]
                sine[p] = next
            }
            raw[i] = sum.toFloat()
        }
        stopIfUnwanted(cancelled)
        Tide.bandLimit(raw, rate)
        stopIfUnwanted(cancelled)
        val conditioned = Dsp.decimate(raw, Dsp.RATE)
        stopIfUnwanted(cancelled)
        val dc = Dsp.OnePole(Dsp.RATE)
        for (i in conditioned.indices) {
            if (i % 1024 == 0) stopIfUnwanted(cancelled)
            conditioned[i] -= dc.lp(conditioned[i], 8f)
        }
        val previous = conditioned.copyOfRange(preroll, preroll + frames)
        val out = conditioned.copyOfRange(preroll + frames, preroll + 2 * frames)
        check(out.all { it.isFinite() && abs(it) <= .99f }) { "LATTICE held response exceeds fixed-gain headroom" }
        val seam = if (out.all { it == 0f }) 0.0 else Keys.seamError(previous + out, frames)
        check(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR) { "LATTICE held response does not close: $seam" }
        stopIfUnwanted(cancelled)
        return HeldProbe(Snip(out, channels = 1, sampleRate = Dsp.RATE), previous, seam, fitted,
            requested, frames, (System.nanoTime() - started) / 1_000_000.0)
    }

    private fun condition(raw: FloatArray): FloatArray {
        Tide.bandLimit(raw, Dsp.RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(raw, Dsp.RATE)
        val dc = Dsp.OnePole(Dsp.RATE)
        for (i in out.indices) out[i] -= dc.lp(out[i], 8f)
        // Declared capped-tail treatment, shared by dry and halo. Long outline evidence measures
        // decay before this final 30 ms guard, not a fade masquerading as filter stability.
        Dsp.fadeTail(out, ms = 30f)
        return out
    }

    private class Source(settings: Settings, rate: Int) {
        private val sine = DoubleArray(PARTIALS)
        private val cosine = DoubleArray(PARTIALS)
        private val stepSin = DoubleArray(PARTIALS)
        private val stepCos = DoubleArray(PARTIALS)
        private val weights = DoubleArray(PARTIALS)
        private val groups = IntArray(PARTIALS) { groupForHarmonic(it + 1) }
        val gain: Double

        init {
            val frequency = Keys.midiHz(settings.midi).toDouble()
            val phases = Dsp.phases(PARTIALS, Dsp.seedFor("LATTICE", MODEL_VERSION, settings.midi, "harmonic-source"))
            val participation = .15 * (settings.velocity - .5)
            for (p in 0 until PARTIALS) {
                val harmonic = p + 1
                val hz = harmonic * frequency
                val profileWeight = when (settings.sourceProfile) {
                    SourceProfile.WARM -> harmonic.toDouble().pow(-1.05) * exp(-(harmonic - 1) / 6.5)
                    SourceProfile.PLUCK -> when (harmonic) {
                        1 -> 1.0
                        2 -> .8
                        3 -> .5
                        4 -> .3
                        5 -> .15
                        6 -> .09
                        7 -> .05
                        8 -> .025
                        else -> .012 * exp(-(harmonic - 9) / 1.8)
                    }
                    SourceProfile.HOLLOW -> when (harmonic) {
                        1 -> 1.0
                        2 -> .04
                        3 -> .65
                        4 -> .03
                        5 -> .26
                        6 -> .02
                        7 -> .08
                        8 -> .012
                        else -> if (harmonic % 2 == 1) .025 * exp(-(harmonic - 9) / 2.5)
                            else .005 * exp(-(harmonic - 10) / 2.5)
                    }
                }
                weights[p] = if (hz < BANDWIDTH_HZ) profileWeight * harmonic.toDouble().pow(participation) else 0.0
                val phase = 2.0 * PI * phases[p]
                sine[p] = sin(phase)
                cosine[p] = cos(phase)
                val step = 2.0 * PI * hz / rate
                stepSin[p] = sin(step)
                stepCos[p] = cos(step)
            }
            val norm = sqrt(weights.sumOf { it * it })
            gain = if (norm > 0.0) sqrt(2.0) * SOURCE_RMS * settings.velocity.toDouble().pow(.72) / norm else 0.0
        }

        fun next(groups: DoubleArray, envelope: Double) {
            groups.fill(0.0)
            for (p in 0 until PARTIALS) {
                groups[this.groups[p]] += weights[p] * sine[p]
                val s = sine[p] * stepCos[p] + cosine[p] * stepSin[p]
                cosine[p] = cosine[p] * stepCos[p] - sine[p] * stepSin[p]
                sine[p] = s
            }
            val effort = gain * envelope
            for (g in groups.indices) groups[g] *= effort
        }

        /** Read before any ticks: the held solver uses exactly this source, not a second palette. */
        fun heldCarriers(): List<Carrier> = (0 until PARTIALS).map { p ->
            Carrier(p + 1, groups[p], weights[p] * gain, sine[p], cosine[p])
        }
    }

    /**
     * Double constant-peak RBJ bandpasses, driven by signed stage rejects only. Every mode's
     * collector row has unit Euclidean norm across the 16 slots, a per-mode input bound. Different
     * rows favor different stage positions and therefore expose TWIST. The modes are correlated;
     * unit collector rows do not imply a unit-gain complete bank. The conservative stage-vector
     * bound is 12 x .7 x 1.5 = 12.6, with a further sqrt(4) for unrestricted coherent group copies.
     * A 49-note, 2,000-point logarithmic sweep plus dense samples around every mode measured a
     * maximum stage-vector gain of 1.050673 before output conditioning (not a certified supremum).
     * [haloStageVectorGain] exposes the actual coefficient response for repeatable measurements.
     * The shared bank is feed-forward and strictly stable (all pole radii <1). Its separate fixed
     * HALO_GAIN is documented rather than hidden behind per-branch peak/loudness matching.
     */
    private class Halo(fundamental: Double, private val rate: Int) {
        private val ratios = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 14.0, 18.0, 22.0)
        private val b0 = DoubleArray(HALO_MODES)
        private val a1 = DoubleArray(HALO_MODES)
        private val a2 = DoubleArray(HALO_MODES)
        private val x1 = DoubleArray(HALO_MODES)
        private val x2 = DoubleArray(HALO_MODES)
        private val y1 = DoubleArray(HALO_MODES)
        private val y2 = DoubleArray(HALO_MODES)
        private val collector = COLLECTOR

        init {
            for (m in 0 until HALO_MODES) {
                val hz = ratios[m] * fundamental
                if (hz >= BANDWIDTH_HZ) continue
                val t60 = 1.8 - 1.0 * m / (HALO_MODES - 1)
                val q = max(.5, PI * hz * t60 / LOG_1000)
                val angle = 2.0 * PI * hz / rate
                val alpha = sin(angle) / (2.0 * q)
                val denominator = 1.0 + alpha
                b0[m] = alpha / denominator
                a1[m] = -2.0 * cos(angle) / denominator
                a2[m] = (1.0 - alpha) / denominator
            }
        }

        fun drives(geometry: Geometry): Array<DoubleArray> = drivesFor(geometry)

        companion object {
            // These normalized rows depend on stage position only. Sharing them lets a SPEED
            // plan cache its collector sends across notes without changing resonator state.
            private val COLLECTOR = Array(HALO_MODES) { m ->
                val preferred = .88 - .76 * m / (HALO_MODES - 1)
                val row = DoubleArray(SLOTS) { s ->
                    val position = s.toDouble() / (SLOTS - 1)
                    val distance = (position - preferred) / .24
                    .18 + .82 * exp(-distance * distance)
                }
                val norm = sqrt(row.sumOf { it * it })
                for (s in row.indices) row[s] /= norm
                row
            }

            fun drivesFor(geometry: Geometry): Array<DoubleArray> = Array(HALO_MODES) { m ->
                DoubleArray(GROUPS) { g ->
                    var sum = 0.0
                    for (s in 0 until SLOTS) sum += COLLECTOR[m][s] * geometry.groups[g].rejection[s]
                    sum
                }
            }
        }

        fun process(groups: DoubleArray, drives: Array<DoubleArray>): Double {
            var out = 0.0
            for (m in 0 until HALO_MODES) {
                if (b0[m] == 0.0) continue
                var input = 0.0
                for (g in 0 until GROUPS) input += groups[g] * drives[m][g]
                val value = b0[m] * (input - x2[m]) - a1[m] * y1[m] - a2[m] * y2[m]
                x2[m] = x1[m]
                x1[m] = input
                y2[m] = y1[m]
                y1[m] = value
                // Most energy at a given harmonic enters one corresponding mode. These are
                // fixed authored gains, never recomputed from the rejected output's loudness.
                out += .7 * value
            }
            return HALO_GAIN * out
        }

        /** Actual settled response from one source group through signed rejects and all modes. */
        fun response(drives: Array<DoubleArray>, group: Int, frequencyHz: Double): Response {
            val angle = 2.0 * PI * frequencyHz / rate
            val c1 = cos(angle)
            val s1 = sin(angle)
            val c2 = cos(2.0 * angle)
            val s2 = sin(2.0 * angle)
            var real = 0.0
            var imaginary = 0.0
            for (m in 0 until HALO_MODES) {
                if (b0[m] == 0.0) continue
                val nr = b0[m] * (1.0 - c2)
                val ni = b0[m] * s2
                val dr = 1.0 + a1[m] * c1 + a2[m] * c2
                val di = -a1[m] * s1 - a2[m] * s2
                val denominator = dr * dr + di * di
                val gain = .7 * HALO_GAIN * drives[m][group]
                real += gain * (nr * dr + ni * di) / denominator
                imaginary += gain * (ni * dr - nr * di) / denominator
            }
            return Response(real, imaginary)
        }

        fun operatorGain(frequencyHz: Double): Double {
            val angle = 2.0 * PI * frequencyHz / rate
            val c1 = cos(angle)
            val s1 = sin(angle)
            val c2 = cos(2.0 * angle)
            val s2 = sin(2.0 * angle)
            val real = DoubleArray(SLOTS)
            val imaginary = DoubleArray(SLOTS)
            for (m in 0 until HALO_MODES) {
                if (b0[m] == 0.0) continue
                val nr = b0[m] * (1.0 - c2)
                val ni = b0[m] * s2
                val dr = 1.0 + a1[m] * c1 + a2[m] * c2
                val di = -a1[m] * s1 - a2[m] * s2
                val denominator = dr * dr + di * di
                val hr = .7 * HALO_GAIN * (nr * dr + ni * di) / denominator
                val hi = .7 * HALO_GAIN * (ni * dr - nr * di) / denominator
                for (s in 0 until SLOTS) {
                    real[s] += collector[m][s] * hr
                    imaginary[s] += collector[m][s] * hi
                }
            }
            return sqrt(real.indices.sumOf { real[it] * real[it] + imaginary[it] * imaginary[it] })
        }
    }

    /** The collector map is independent of pitch; native motion caches it with geometry. */
    internal fun motionDrives(geometry: Geometry): Array<DoubleArray> = Halo.drivesFor(geometry)

    private fun smooth(t: Double): Double = t * t * (3.0 - 2.0 * t)
    private fun mix(a: Double, b: Double, amount: Double): Double = a + (b - a) * amount
    private fun peak(samples: FloatArray): Double = samples.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
}
