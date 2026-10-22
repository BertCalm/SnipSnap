package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.DrumClass
import kotlin.math.*
import kotlin.random.Random

/** Listening prototype: these three identities and their numeric voicing are provisional. */
enum class CreaseVoice { ROUND, PINCH, RUBBER }
enum class CreaseHistory { STATEFUL, INSTANTANEOUS }

data class CreaseTrace(
    val timeSeconds: FloatArray, val pressure: FloatArray,
    val deformationA: FloatArray, val deformationB: FloatArray,
    val loadA: FloatArray, val loadB: FloatArray,
)

data class CreaseDiagnostics(
    val rawPeak: Double, val rawRms: Double, val rawDc: Double,
    /** V1 Fourier weighted budget; null for V2's positive analytic maps. */
    val maxCoefficientBudget: Double?, val minMapDerivative: Double, val maxAbsState: Double,
    val requestedHz: Double, val renderedHz: Double,
    val held: Boolean, val loopFrames: Int, val rootCycles: Int,
    val seamError: Double, val stateError: Double, val converged: Boolean,
    val prerollCycles: Int, val renderNanos: Long,
    val maxMapDerivative: Double = 1.9, val maxMapCurvature: Double = 0.0,
    val modelVersion: Int = 1,
)

/** [prelevel] is conditioned 44.1 kHz audio before the shared melodic gain.
 * Held [previousCycle] was evolved independently before [samples], with the same final gain.
 * Boundary snapshots contain control, filter and canonical FIR-history state, not duplicated audio.
 * The rational oscillator/gesture clocks return exactly to zero at both held boundaries. */
data class CreaseProbe(
    val samples: FloatArray, val prelevel: FloatArray, val sampleRate: Int,
    val diagnostics: CreaseDiagnostics, val trace: CreaseTrace,
    val previousCycle: FloatArray = FloatArray(0),
    val loopStateStart: DoubleArray = DoubleArray(0),
    val loopStateEnd: DoubleArray = DoubleArray(0),
)

/**
 * Two same-root, fundamental-aligned phase maps with bounded, fading deformation memory.
 * FOLD changes baseline geometry; PRESSURE changes excursion and its direction; DRAG changes
 * following time; SPLIT separates geometry/recovery; RELAX changes return time and finite release.
 * HOLD >= .99 renders one converged periodic gesture. No phase reversal, detune, samples or FX.
 *
 * Model 2 replaces V1's restricted Fourier offsets with analytic positive phase maps.
 * ROUND uses a full-circle Mobius map; PINCH uses its mirrored half-cycle counterpart;
 * RUBBER composes them so pressure moves a symmetric pair of narrow phase features.
 * Every map fixes 0 and pi: its sine fundamental remains positive and phase-aligned.
 * Total absolute log compression is bounded, with a lower ceiling in high registers.
 * Model 3 keeps these waveforms and refines their pressure/response profiles. Explicit
 * model=1 and model=2 remain reproducible and never silently acquire a newer gesture.
 */
object Crease {
    const val MODEL_VERSION = 3
    const val RATE = Dsp.RATE
    const val DEFAULT_MIDI = 48
    const val MIDI_MIN = 24
    const val MIDI_MAX = 84
    const val HOLD_LOOP = .99f
    const val MAX_FINITE_SECONDS = 8.0
    /** Preserved model 1 only; model 2 is bounded by total log compression instead. */
    const val COEFFICIENT_BUDGET = .9
    const val MAX_MAP_LOG_COMPRESSION = 3.6
    const val HIGH_MAP_LOG_COMPRESSION = 2.85
    const val MAX_MAP_DERIVATIVE = 36.59823444367799 // exp(3.6)
    const val MAX_MAP_CURVATURE = 75000.0 // conservative 1.5*exp(3*3.6) bound
    const val LOOP_STATE_TOLERANCE = 1e-7
    const val MAX_PREROLL_CYCLES = 28
    const val MODEL2_MAX_PREROLL_CYCLES = 18
    private const val TAU = 2.0 * PI
    private const val TRACE_HZ = 200

    fun macrosFor(voice: CreaseVoice, model: Int = MODEL_VERSION): List<MacroSpec> {
        require(model in 1..MODEL_VERSION) { "Unknown CREASE model $model" }
        if (model == 1) return CreaseV1.macrosFor(voice)
        val values = when (voice) {
            CreaseVoice.ROUND -> floatArrayOf(.20f, .25f, .15f, .15f, .30f)
            CreaseVoice.PINCH -> floatArrayOf(.70f, .55f, .20f, .45f, .35f)
            CreaseVoice.RUBBER -> floatArrayOf(.50f, .70f, .75f, .55f, .65f)
        }
        return listOf("FOLD", "PRESSURE", "DRAG", "SPLIT", "RELAX").mapIndexed { i, name ->
            MacroSpec(name, values[i], values[i])
        } + MacroSpec("HOLD", 0f, 0f)
    }

    fun defaults(voice: CreaseVoice, model: Int = MODEL_VERSION): Map<String, Float> =
        macrosFor(voice, model).associate { it.name to it.default }
    fun isLoop(hold: Float) = hold >= HOLD_LOOP
    fun supportsModel(model: Int): Boolean = model in 1..MODEL_VERSION

    /** Native placement keeps finite pitched notes tonal, including long unfolding tails. */
    fun drumClassFor(voice: CreaseVoice, macros: Map<String, Float> = emptyMap(), model: Int = MODEL_VERSION): DrumClass =
        if (isLoop(settled(voice, macros, model).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    /** Scramble the timbral controls while leaving the explicit note/model in the patch.
     * The top HOLD step is an intentional loop choice, never an accidental random result. */
    fun scramble(voice: CreaseVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val model = (near as? CreasePatch)?.model ?: MODEL_VERSION
        val base = settled(voice, near?.macros ?: emptyMap(), model)
        val amount = temperature.takeIf { it.isFinite() } ?: .35f
        return Dsp.scrambleNear(base, amount, random).toMutableMap().also {
            it["HOLD"] = it.getValue("HOLD").coerceAtMost(.98f)
        }
    }

    private fun settled(voice: CreaseVoice, macros: Map<String, Float>, model: Int) =
        defaults(voice, model).toMutableMap().also { result ->
            for ((key, value) in macros) if (key in result && value.isFinite()) result[key] = value.coerceIn(0f, 1f)
        }

    fun render(voice: CreaseVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = .75f, model: Int = MODEL_VERSION): Snip =
        Snip(synthesize(voice, macros, midi, velocity, model = model).samples, 1, RATE)

    fun probe(voice: CreaseVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = .75f,
        history: CreaseHistory = CreaseHistory.STATEFUL, coupling: Boolean = true,
        oversample: Int = 4, normalize: Boolean = true, model: Int = MODEL_VERSION): CreaseProbe =
        synthesize(voice, macros, midi, velocity, history, coupling, oversample, normalize, model)

    fun synthesize(voice: CreaseVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = .75f,
        history: CreaseHistory = CreaseHistory.STATEFUL, coupling: Boolean = true,
        oversample: Int = 4, normalize: Boolean = true, model: Int = MODEL_VERSION): CreaseProbe {
        require(model in 1..MODEL_VERSION) { "Unknown CREASE model $model" }
        if (model == 1) return CreaseV1.synthesize(voice, macros, midi, velocity, history, coupling, oversample, normalize)
        require(midi in MIDI_MIN..MIDI_MAX) { "CREASE supports MIDI $MIDI_MIN..$MIDI_MAX" }
        require(oversample == 4 || oversample == 16) { "CREASE diagnostic oversample must be 4 or 16" }
        val started = System.nanoTime()
        val m = settled(voice, macros, model)
        val vel = if (velocity.isFinite()) velocity.coerceIn(0f, 1f).toDouble() else .75
        val requestedHz = Keys.midiHz(midi).toDouble()
        val held = isLoop(m.getValue("HOLD"))
        val shape = Shape(voice, m, vel, model)
        check(held || shape.duration <= MAX_FINITE_SECONDS) { "CREASE finite note exceeded duration cap" }
        // Same principle as Keys.planLoop: integer root cycles, then a tiny documented retuning
        // to integer output frames. Both layers have this exact root, with no detune or fifth.
        val rootCycles = if (held) (shape.loopSeconds * requestedHz).roundToInt().coerceAtLeast(8) else 0
        val frames = if (held) (rootCycles * RATE / requestedHz).roundToInt()
            else (shape.duration * RATE).roundToInt()
        val renderedHz = if (held) rootCycles * RATE.toDouble() / frames else requestedHz
        val rate = RATE * oversample
        val circuit = Model(shape, rate, renderedHz, history, coupling)
        val output = OutputPath(rate, oversample)
        val stats = Stats()
        val traces = TraceBuilder()
        var preroll = 0
        var converged = !held
        var stateError = 0.0
        var startState = DoubleArray(0)
        var endState = DoubleArray(0)
        var previous = FloatArray(0)

        fun state() = circuit.state() + output.state()
        fun cycle(record: Boolean): FloatArray {
            val out = FloatArray(frames)
            val internalFrames = frames * oversample
            val traceStride = max(oversample, rate / TRACE_HZ)
            var at = 0
            for (i in 0 until internalFrames) {
                if (i % 8192 == 0 && Thread.currentThread().isInterrupted)
                    throw java.util.concurrent.CancellationException("CREASE render no longer wanted")
                val time = i.toDouble() / rate
                // A rational, periodic integer clock also closes at 16x. No floating phase
                // accumulator is reset while carrying residual phase error across the seam.
                val phase = if (held) TAU * ((i.toDouble() * rootCycles / internalFrames) % 1.0)
                    else TAU * ((time * renderedHz) % 1.0)
                val raw = circuit.step(phase, time, if (held) i.toDouble() / internalFrames else null)
                val value = output.step(raw)
                if (value != null) out[at++] = value.toFloat()
                if (record) {
                    stats.add(raw, circuit)
                    if (i % traceStride == 0) traces.add(time, circuit)
                }
            }
            check(at == frames) { "CREASE decimator did not complete a period" }
            return out
        }

        val prelevel: FloatArray
        if (held) {
            var before = state()
            val prerollCap = if (model == 2) MODEL2_MAX_PREROLL_CYCLES else MAX_PREROLL_CYCLES
            for (period in 1..prerollCap) {
                cycle(false)
                val after = state()
                stateError = distance(before, after)
                before = after
                preroll = period
                if (period >= 3 && stateError < LOOP_STATE_TOLERANCE) {
                    converged = true
                    break
                }
            }
            check(converged) { "CREASE $voice HOLD did not converge after $preroll periods: $stateError" }
            previous = cycle(false)
            startState = state()
            prelevel = cycle(true)
            endState = state()
            stateError = distance(startState, endState)
            check(stateError < LOOP_STATE_TOLERANCE) { "CREASE HOLD state recurrence regressed: $stateError" }
        } else {
            prelevel = cycle(true)
            // The envelope completes a true -60 dB release before this conventional final fade.
            Dsp.fadeTail(prelevel)
        }

        val samples = prelevel.copyOf()
        if (normalize && vel > 0.0) Dsp.levelTo(samples, RATE, Dsp.MELODIC_LOUDNESS_TARGET * vel.toFloat().pow(.4f))
        if (held && normalize) {
            // Apply one scalar to both independently evolved cycles. Never normalize each
            // separately and accidentally conceal failure to reach a periodic orbit.
            val index = prelevel.indices.maxByOrNull { abs(prelevel[it]) } ?: 0
            val gain = if (prelevel[index] == 0f) 1f else samples[index] / prelevel[index]
            for (i in previous.indices) previous[i] *= gain
        }
        val seam = if (held && vel > 0.0) Keys.seamError(previous + samples, previous.size) else 0.0
        check(!held || seam < 1e-3) { "CREASE $voice HOLD audio did not recur: $seam" }
        return CreaseProbe(samples, prelevel, RATE, CreaseDiagnostics(
            stats.peak, sqrt(stats.square / stats.count.coerceAtLeast(1)), stats.sum / stats.count.coerceAtLeast(1),
            null, stats.derivative, max(stats.state, output.maxAbsState),
            requestedHz, renderedHz, held, if (held) frames else 0, rootCycles,
            seam, stateError, converged, preroll, System.nanoTime() - started,
            stats.maxDerivative, stats.curvature, model,
        ), traces.finish(), previous, startState, endState)
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double = a.indices.maxOf { abs(a[it] - b[it]) }
    private fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t
    private fun smooth(t: Double): Double = t.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    private class Shape(val voice: CreaseVoice, m: Map<String, Float>, val velocity: Double, val version: Int) {
        val fold = m.getValue("FOLD").toDouble().pow(.65)
        val pressure = m.getValue("PRESSURE").toDouble().pow(.65)
        val drag = m.getValue("DRAG").toDouble()
        val split = m.getValue("SPLIT").toDouble().pow(.65)
        val relax = m.getValue("RELAX").toDouble()
        val held = isLoop(m.getValue("HOLD"))
        private val finiteHold = (m.getValue("HOLD") / HOLD_LOOP).coerceIn(0f, 1f).toDouble()
        val attack = when (voice) { CreaseVoice.ROUND -> .009; CreaseVoice.PINCH -> .014; CreaseVoice.RUBBER -> .027 }
        val gate = (when (voice) { CreaseVoice.ROUND -> .66; CreaseVoice.PINCH -> .88; CreaseVoice.RUBBER -> 1.35 }) *
            2.0.pow(finiteHold)
        val releaseT60 = when (voice) { CreaseVoice.ROUND -> .35 + 1.5 * sqrt(relax)
            CreaseVoice.PINCH -> .35 + 1.8 * sqrt(relax); CreaseVoice.RUBBER -> .65 + 2.5 * sqrt(relax) }
        val duration = gate + releaseT60 + .035
        val loopSeconds = when (voice) { CreaseVoice.ROUND -> 1.45; CreaseVoice.PINCH -> 1.65; CreaseVoice.RUBBER -> 2.1 }
        private val model2Rise = .002 * 40.0.pow(sqrt(drag)) * if (voice == CreaseVoice.RUBBER) 1.5 else 1.0
        private val defaultDrag = when (voice) {
            CreaseVoice.ROUND -> .15f.toDouble(); CreaseVoice.PINCH -> .20f.toDouble(); CreaseVoice.RUBBER -> .75f.toDouble()
        }
        // The accepted default response is retained. Beyond it, DRAG gains a useful long
        // following range: .24/.28/.48 seconds, without altering the pressure source itself.
        val rise = if (version == 2) model2Rise else {
            val beyondDefault = ((drag - defaultDrag) / (1.0 - defaultDrag)).coerceIn(0.0, 1.0).pow(.65)
            val extension = when (voice) { CreaseVoice.ROUND -> 3.0; CreaseVoice.PINCH -> 3.5; CreaseVoice.RUBBER -> 4.0 }
            model2Rise * extension.pow(beyondDefault)
        }
        // These ranges describe T60; the one-pole solver takes tau. RUBBER's accepted
        // default unfolding time is an anchor between its faster and much slower extremes.
        val recovery = if (version == 2) (.10 * 18.0.pow(relax)) / ln(1000.0) else {
            val t60 = when (voice) {
                CreaseVoice.ROUND -> .07 * 50.0.pow(relax)
                CreaseVoice.PINCH -> .09 * 40.0.pow(relax)
                CreaseVoice.RUBBER -> {
                    val pivot = .65f.toDouble()
                    val anchor = .10 * 18.0.pow(pivot)
                    if (relax <= pivot) .12 * (anchor / .12).pow(relax / pivot)
                    else anchor * (4.32 / anchor).pow((relax - pivot) / (1.0 - pivot))
                }
            }
            t60 / ln(1000.0)
        }

        fun amplitude(t: Double): Double {
            if (held) return velocity * .68
            val sustain = if (voice == CreaseVoice.ROUND) .38 else .62
            val decay = if (voice == CreaseVoice.RUBBER) .42 else .19
            val body = sustain + (1.0 - sustain) * exp(-min(t, gate) / decay)
            val release = if (t <= gate) 1.0 else exp(-ln(1000.0) * (t - gate) / releaseT60)
            return velocity * smooth(t / attack) * body * release
        }

        fun forcing(t: Double, loop: Double?): Double {
            if (version >= 3) return gesture(t, loop)
            if (loop != null) {
                // Smooth periodic pressure, never an amplitude attack/retrigger. The second
                // harmonic makes rise and unfolding asymmetrical while retaining exact closure.
                val a = .5 - .5 * cos(TAU * loop)
                val b = .5 - .5 * cos(2.0 * TAU * loop + .65)
                return (.10 + .73 * a.pow(2.0) + .17 * b).coerceIn(0.0, 1.0)
            }
            val onset = exp(-t / if (voice == CreaseVoice.RUBBER) .13 else .065)
            val push = exp(-((t - (.17 + .15 * drag)) / (.055 + .07 * drag)).pow(2.0))
            val late = exp(-((t - gate * .74) / (.08 + .12 * relax)).pow(2.0))
            val release = if (t <= gate) 1.0 else exp(-(t - gate) / (.025 + .10 * relax))
            return ((.09 + .73 * onset + .63 * push + .24 * late) * release).coerceIn(0.0, 1.0)
        }

        /** Voice phrasing is fixed for a given note/HOLD. DRAG and RELAX act on the
         * causal response, so their audible delay/unfolding is independent of this source. */
        private fun gesture(t: Double, loop: Double?): Double {
            if (loop != null) {
                val a = .5 - .5 * cos(TAU * loop)
                return when (voice) {
                    CreaseVoice.ROUND -> {
                        val b = .5 - .5 * cos(2.0 * TAU * loop + .40)
                        .10 + .80 * a.pow(3.0) + .10 * b
                    }
                    CreaseVoice.PINCH -> {
                        val b = .5 - .5 * cos(2.0 * TAU * loop + .65)
                        .10 + .73 * a.pow(2.0) + .17 * b
                    }
                    CreaseVoice.RUBBER -> {
                        val b = .5 - .5 * cos(2.0 * TAU * loop + 1.0)
                        .08 + .74 * a + .18 * b
                    }
                }.coerceIn(0.0, 1.0)
            }
            return when (voice) {
                CreaseVoice.ROUND -> {
                    val onset = exp(-t / .065)
                    val push = exp(-((t - .15) / .055).pow(2.0))
                    val late = exp(-((t - gate * .72) / .075).pow(2.0))
                    val release = if (t <= gate) 1.0 else exp(-(t - gate) / .045)
                    ((.09 + .73 * onset + .44 * push + .08 * late) * release).coerceIn(0.0, 1.0)
                }
                CreaseVoice.PINCH -> {
                    // Keep the accepted reed's default phrase, separating its source from
                    // the DRAG/RELAX knobs while their response ranges become more useful.
                    val referenceDrag = .20f.toDouble()
                    val referenceRelax = .35f.toDouble()
                    val onset = exp(-t / .065)
                    val push = exp(-((t - (.17 + .15 * referenceDrag)) / (.055 + .07 * referenceDrag)).pow(2.0))
                    val late = exp(-((t - gate * .74) / (.08 + .12 * referenceRelax)).pow(2.0))
                    val release = if (t <= gate) 1.0 else exp(-(t - gate) / (.025 + .10 * referenceRelax))
                    ((.09 + .73 * onset + .63 * push + .24 * late) * release).coerceIn(0.0, 1.0)
                }
                CreaseVoice.RUBBER -> {
                    val onset = exp(-t / .18)
                    val push = exp(-((t - .38) / .145).pow(2.0))
                    val late = exp(-((t - gate * .74) / .20).pow(2.0))
                    val release = if (t <= gate) 1.0 else exp(-(t - gate) / .10)
                    ((.07 + .50 * onset + .78 * push + .22 * late) * release).coerceIn(0.0, 1.0)
                }
            }
        }
    }

    /** Analytic composition E_b(F_a(phi)). F is a full-circle Mobius phase map;
     * E is atan2(exp(b)*sin(phi), cos(phi)), evaluated without atan or wrap branches.
     * Their positive derivatives are bounded by exp(abs(a)+abs(b)); both fix 0 and pi.
     * Negative b pinches each half cycle, preserving odd-only PINCH harmonics.
     */
    private class Warp {
        var sine = 0.0; var cosine = 1.0
        var derivative = 1.0; var curvature = 0.0; var gain = 1.0
        fun evaluate(s: Double, c: Double, a: Double, b: Double) {
            val qa = exp(a)
            val ch = .5 * (qa + 1.0 / qa)
            val sh = .5 * (qa - 1.0 / qa)
            val den = ch - sh * c
            val sm = s / den
            val cm = (ch * c - sh) / den
            val dm = 1.0 / den
            val ddm = -sh * s / (den * den)
            val qb = exp(b)
            val qb2 = qb * qb
            val len2 = cm * cm + qb2 * sm * sm
            val len = sqrt(len2)
            sine = qb * sm / len
            cosine = cm / len
            val de = qb / len2
            val dde = -2.0 * qb * (qb2 - 1.0) * sm * cm / (len2 * len2)
            derivative = de * dm
            curvature = dde * dm * dm + de * ddm
            // Exact RMS compensation for each elementary map, bounded for compositions.
            // This keeps a narrow phase ribbon audible relative to the restrained root.
            gain = min(3.0, sqrt((ch + 1.0) * .5) * sqrt((1.0 + qb) / (2.0 * qb)))
        }
    }

    private class Model(val shape: Shape, rate: Int, hz: Double,
        val history: CreaseHistory, val coupled: Boolean) {
        var pressure = 0.0
        var dA = 0.0; var dB = 0.0
        var recoveryA = 0.0; var recoveryB = 0.0
        var loadA = 0.0; var loadB = 0.0
        private var energyA = 0.0; private var energyB = 0.0
        var minDerivative = 1.0; var maxDerivative = 1.0; var maxCurvature = 0.0
        private val warpA = Warp(); private val warpB = Warp()
        private val dt = 1.0 / rate
        private val riseA = exp(-dt / shape.rise)
        private val riseB = exp(-dt / (shape.rise * (1.0 + 2.8 * shape.split)))
        private val fallA = exp(-dt / shape.recovery)
        private val fallB = exp(-dt / (shape.recovery * (1.0 + 1.8 * shape.split)))
        private val recoverA = exp(-dt / (.012 + shape.recovery * 1.5))
        private val recoverB = exp(-dt / ((.012 + shape.recovery * 1.5) * (1.0 + 2.2 * shape.split)))
        private val pressureRise = exp(-dt / .003)
        private val pressureFall = exp(-dt / .022)
        private val loadPole = exp(-dt / max(2.0 / hz, .008 + .060 * shape.drag))
        // Start reducing compression above C4; C6 retains a broad positive 17.3:1 speed range.
        private val register = (ln(hz / 261.6255653) / ln(4.0)).coerceIn(0.0, 1.0)
        private val logCeiling = mix(MAX_MAP_LOG_COMPRESSION, HIGH_MAP_LOG_COMPRESSION, register)
        private val residualScale = MAX_MAP_DERIVATIVE + 1.0
        private val loadHalf = .35 / (residualScale * residualScale)

        fun state() = doubleArrayOf(pressure, dA, dB, recoveryA, recoveryB, energyA, energyB, loadA, loadB)

        private fun map(warp: Warp, s: Double, c: Double, a: Double, b: Double) {
            val total = abs(a) + abs(b)
            val scale = if (total > logCeiling) logCeiling / total else 1.0
            warp.evaluate(s, c, a * scale, b * scale)
        }

        fun step(phase: Double, t: Double, loop: Double?): Double {
            val amplitude = shape.amplitude(t)
            val force = shape.forcing(t, loop) * 1.25 * shape.velocity.pow(1.25)
            val excitation = if (shape.velocity == 0.0) 0.0 else (amplitude / shape.velocity).pow(2.0)
            val pTarget = (force * (.90 + .10 * excitation)).coerceIn(0.0, 1.0)
            val pp = if (pTarget > pressure) pressureRise else pressureFall
            pressure = pTarget + (pressure - pTarget) * pp
            // Previous completed outputs load the two layers; coupling has its own bound.
            val coupling = if (coupled) .55 * (.30 + .70 * shape.split) else 0.0
            val targetA = (pressure * (1.0 - coupling * loadB)).coerceIn(0.0, 1.0)
            val targetB = (pressure * (1.0 + .40 * coupling * loadA)).coerceIn(0.0, 1.0)
            if (history == CreaseHistory.INSTANTANEOUS) {
                dA = targetA; dB = targetB; recoveryA = dA; recoveryB = dB
            } else {
                dA = targetA + (dA - targetA) * if (targetA > dA) riseA else fallA
                dB = targetB + (dB - targetB) * if (targetB > dB) riseB else fallB
                recoveryA = dA + (recoveryA - dA) * recoverA
                recoveryB = dB + (recoveryB - dB) * recoverB
            }
            val s = sin(phase); val c = cos(phase)
            when (shape.voice) {
                CreaseVoice.ROUND -> {
                    val base = .02 + .65 * shape.fold
                    val excursion = .10 + 2.0 * shape.pressure
                    val a = base + excursion * (.80 * dA + .20 * recoveryA)
                    val b = base * (1.0 + .30 * shape.split) + excursion *
                        (.55 * dB + .45 * recoveryB) * (1.0 + .40 * shape.split)
                    map(warpA, s, c, a, 0.0)
                    map(warpB, s, c, b, 0.0)
                }
                CreaseVoice.PINCH -> {
                    val base = .55 + .70 * shape.fold
                    val excursion = .45 + 3.60 * shape.pressure
                    // The fast state bends ahead of the recovery state on a push, then
                    // opens again as pressure falls. At equilibrium the lead is exactly d.
                    // This is bounded two-state spectral overshoot, not amplitude clipping.
                    val depthA = (dA + .65 * (dA - recoveryA)).coerceIn(0.0, 1.0)
                    val depthB = (dB + .65 * (dB - recoveryB)).coerceIn(0.0, 1.0)
                    val a = base + excursion * depthA
                    val b = (base + excursion * depthB) * (1.0 + .16 * shape.split)
                    map(warpA, s, c, 0.0, -a)
                    map(warpB, s, c, 0.0, -b)
                }
                CreaseVoice.RUBBER -> {
                    val base = .20 + 1.15 * shape.fold
                    val excursion = 1.45 + .65 * shape.pressure
                    val unfold = .65 + .35 * shape.pressure
                    val a = (base + excursion * dA - unfold * recoveryA).coerceIn(.10, 2.4)
                    val common = base + excursion * dB - unfold * recoveryB
                    val other = base * (1.0 - .45 * shape.split) + excursion *
                        (.35 * dB + .65 * recoveryB) - unfold * (.85 * dB + .15 * recoveryB)
                    val b = mix(common, other, shape.split).coerceIn(.10, 2.4)
                    val width = .80 + .70 * shape.fold
                    val squeeze = .80 + .80 * shape.pressure
                    val widthA = width + squeeze * (.80 * dA + .20 * recoveryA)
                    val widthB = (width + squeeze * (.20 * dB + .80 * recoveryB)) * (1.0 + .20 * shape.split)
                    map(warpA, s, c, a, -widthA)
                    map(warpB, s, c, b, -widthB)
                }
            }
            // Smooth the bounded residual ENERGY before compressing its mean. Pointwise
            // clipping would erase the sharp features that distinguish these stronger maps.
            val residualA = (warpA.cosine * warpA.derivative - c) / residualScale
            val residualB = (warpB.cosine * warpB.derivative - c) / residualScale
            energyA = residualA * residualA + (energyA - residualA * residualA) * loadPole
            energyB = residualB * residualB + (energyB - residualB * residualB) * loadPole
            loadA = energyA / (energyA + loadHalf)
            loadB = energyB / (energyB + loadHalf)
            minDerivative = min(warpA.derivative, warpB.derivative)
            maxDerivative = max(warpA.derivative, warpB.derivative)
            maxCurvature = max(abs(warpA.curvature), abs(warpB.curvature))
            // Bounded analytic RMS compensation plus fixed linear headroom. Even if both
            // maps and the root peak together, abs(raw) <= .32*(.92*3+.08)*velocity < .91.
            return .32 * amplitude * (.48 * warpA.gain * warpA.sine + .44 * warpB.gain * warpB.sine + .08 * s)
        }
    }

    /** Streaming equivalent of Tide.bandLimit's four fixed TPT low-pass sections. Keeping their
     * two integrators explicit makes all conditioning state part of the held convergence proof. */
    private class LowPass(rate: Int, damping: Double) {
        private val g = tan(PI * Tide.BAND_LIMIT_HZ / rate)
        private val a1 = 1.0 / (1.0 + g * (g + damping))
        private val a2 = g * a1
        private val a3 = g * a2
        var ic1 = 0.0; var ic2 = 0.0
        fun step(x: Double): Double {
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2.0 * v1 - ic1
            ic2 = 2.0 * v2 - ic2
            return v2
        }
    }

    /** The Blackman-windowed sinc used by Skitter, evaluated causally with a 24-output-frame
     * delay. Unlike a reset batch converter, its FIR history is carried and checked at wraps.
     * This supports the same output filter at both 4x and the diagnostic 16x reference. */
    private class OutputPath(rate: Int, private val oversample: Int) {
        private val damping = doubleArrayOf(1.9616, 1.6629, 1.1111, .3902)
        private val filters = Array(damping.size) { LowPass(rate, damping[it]) }
        private val radius = 24 * oversample
        private val kernel = DoubleArray(radius * 2 + 1)
        private val ring = DoubleArray(kernel.size)
        private var write = 0
        private var phase = 0
        private var dc = 0.0
        var maxAbsState = 0.0
            private set
        private val dcPole = exp(-TAU * 8.0 / rate)
        init {
            var total = 0.0
            for (i in kernel.indices) {
                val j = i - radius
                val z = j.toDouble() / oversample
                val sinc = if (j == 0) .84 else sin(PI * .84 * z) / (PI * z)
                kernel[i] = sinc * (.42 + .5 * cos(PI * j / radius) + .08 * cos(TAU * j / radius))
                total += kernel[i]
            }
            for (i in kernel.indices) kernel[i] /= total
        }
        fun step(raw: Double): Double? {
            var x = raw
            for (filter in filters) {
                x = filter.step(x)
                maxAbsState = max(maxAbsState, max(abs(filter.ic1), abs(filter.ic2)))
            }
            dc = x + (dc - x) * dcPole
            ring[write] = x - dc
            maxAbsState = max(maxAbsState, max(abs(dc), abs(ring[write])))
            write++
            if (write == ring.size) write = 0
            phase++
            if (phase < oversample) return null
            phase = 0
            var out = 0.0
            var at = write
            for (tap in kernel) {
                out += tap * ring[at]
                at++
                if (at == ring.size) at = 0
            }
            return out
        }
        fun state(): DoubleArray {
            val state = DoubleArray(filters.size * 2 + 2 + ring.size)
            for ((i, filter) in filters.withIndex()) { state[i * 2] = filter.ic1; state[i * 2 + 1] = filter.ic2 }
            state[8] = dc; state[9] = phase.toDouble()
            // Canonical chronological order, rather than the arbitrary circular write index.
            for (i in ring.indices) state[10 + i] = ring[(write + i) % ring.size]
            return state
        }
    }

    private class Stats {
        var count = 0L; var sum = 0.0; var square = 0.0; var peak = 0.0
        var derivative = 1.0; var maxDerivative = 1.0; var curvature = 0.0; var state = 0.0
        fun add(raw: Double, model: Model) {
            check(raw.isFinite()) { "CREASE produced a non-finite sample" }
            count++; sum += raw; square += raw * raw; peak = max(peak, abs(raw))
            derivative = min(derivative, model.minDerivative)
            maxDerivative = max(maxDerivative, model.maxDerivative); curvature = max(curvature, model.maxCurvature)
            state = max(state, max(max(model.pressure, max(model.dA, model.dB)),
                max(max(model.recoveryA, model.recoveryB), max(model.loadA, model.loadB))))
        }
    }

    private class TraceBuilder {
        private val times = ArrayList<Float>(); private val p = ArrayList<Float>()
        private val a = ArrayList<Float>(); private val b = ArrayList<Float>()
        private val la = ArrayList<Float>(); private val lb = ArrayList<Float>()
        fun add(time: Double, model: Model) {
            times.add(time.toFloat()); p.add(model.pressure.toFloat())
            a.add(model.dA.toFloat()); b.add(model.dB.toFloat())
            la.add(model.loadA.toFloat()); lb.add(model.loadB.toFloat())
        }
        fun finish() = CreaseTrace(times.toFloatArray(), p.toFloatArray(), a.toFloatArray(),
            b.toFloatArray(), la.toFloatArray(), lb.toFloatArray())
    }
}
