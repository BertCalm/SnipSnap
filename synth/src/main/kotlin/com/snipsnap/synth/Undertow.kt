package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class UndertowVoice { KNOCK, BREATH, FLUTTER, SEAL, HOLLOW, SURGE }

/**
 * An imaginary shell with four weighted inlets, sharing one piston-driven pressure deficit.
 * Units are normalized: pressure is exterior minus reservoir pressure, flow is reservoir
 * volumes/second, and flap travel is a fraction of available clearance. These are musical
 * design choices, not measured material properties. Positive inward flow ALWAYS removes
 * deficit. Gross mechanics run at 1 kHz; acoustic rotation, nonlinear reed feedback and
 * compliant contact pulses run at 176.4 kHz before the shared band-limited decimator.
 *
 * Update order is lagged flow -> reservoir -> local compliance -> flap -> aperture -> sound.
 * The acoustic network exchanges velocity through reciprocal contractions. Powered negative
 * damping in the reed/flap spends piston pressure; with extraction off, pressure equalizes
 * and every acoustic mode is lossy. Output gain never returns to these state equations.
 */
object Undertow {
    const val ROOT_MIDI = 48
    const val TUNE_SEMITONES = 24
    const val LOOP_THRESHOLD = .99f
    const val MAX_SECONDS = 6f
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val CONTROL = 176
    private const val CHAMBERS = 4
    private const val BODY = 3
    private const val TRIM = .38

    private data class Shape(
        val controls: FloatArray, val duration: Double, val contact: Double,
        val breath: Double, val flutter: Double, val seal: Double, val body: Double,
    )

    private fun shape(voice: UndertowVoice) = when (voice) {
        UndertowVoice.KNOCK -> Shape(floatArrayOf(.45f, .30f, .60f, .35f, .55f), .55, 1.25, .60, .35, .10, .70)
        UndertowVoice.BREATH -> Shape(floatArrayOf(.55f, .40f, .35f, .55f, .40f), 1.30, .65, 1.10, .25, .10, .85)
        UndertowVoice.FLUTTER -> Shape(floatArrayOf(.60f, .80f, .40f, .50f, .35f), 1.30, .75, .90, 1.50, .30, .75)
        UndertowVoice.SEAL -> Shape(floatArrayOf(.65f, .55f, .60f, .60f, .20f), 1.40, .90, .90, .85, .90, .90)
        UndertowVoice.HOLLOW -> Shape(floatArrayOf(.50f, .45f, .65f, .85f, .35f), 1.10, .70, .85, .40, .20, 1.40)
        UndertowVoice.SURGE -> Shape(floatArrayOf(.85f, .65f, .70f, .65f, .30f), 1.40, 1.05, 1.00, 1.20, .55, 1.00)
    }

    fun macrosFor(voice: UndertowVoice): List<MacroSpec> {
        val d = shape(voice).controls
        return listOf(
            MacroSpec("TUNE", .5f, .5f), MacroSpec("DRAW", d[0], .45f),
            MacroSpec("FLAP", d[1], .40f), MacroSpec("WEIGHT", d[2], .50f),
            MacroSpec("SPIRAL", d[3], .40f), MacroSpec("LEAK", d[4], .30f),
            MacroSpec("HOLD", 0f, 0f),
        )
    }

    fun defaults(voice: UndertowVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(voice: UndertowVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (name, default) -> finite(macros[name] ?: default, default) }
    private fun finite(value: Float, default: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else default
    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: UndertowVoice): Int = ROOT_MIDI
    fun midiFor(voice: UndertowVoice, tune: Float): Int = rootMidi(voice) + (finite(tune, .5f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(voice: UndertowVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= LOOP_THRESHOLD
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: UndertowVoice, @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap()): DrumClass = DrumClass.LOOP

    fun scramble(voice: UndertowVoice, random: Random = Random.Default): Map<String, Float> =
        scramble(voice, random, .35f)
    fun scramble(voice: UndertowVoice, random: Random, temperature: Float, near: Patch? = null): Map<String, Float> {
        val d = defaults(voice)
        val start = if (near == null) d else d + near.macros.filterKeys { it in d }
        return Dsp.scrambleNear(start, finite(temperature, .35f), random).toMutableMap().also {
            it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f)
        }
    }

    fun render(voice: UndertowVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        val result = probe(voice, macros, velocity, ProbeOptions(normalize = true, recordDiagnostics = false))
        certifyLoop(voice, result)
        return Snip(result.samples, channels = 1, sampleRate = Dsp.RATE)
    }
    internal fun renderLoop(voice: UndertowVoice, macros: Map<String, Float>, cancelled: () -> Boolean = { false }): FloatArray {
        val result = probe(voice, macros + ("HOLD" to 1f), options = ProbeOptions(normalize = true, recordDiagnostics = false), cancelled = cancelled)
        certifyLoop(voice, result)
        return result.samples
    }
    private fun certifyLoop(voice: UndertowVoice, result: Probe) {
        if (result.previousCycle.isNotEmpty()) check(result.seamError < Keys.MAX_SEAM_ERROR && result.cycleStateError < 1e-3) {
            "UNDERTOW $voice held material did not converge: state=${result.cycleStateError}, seam=${result.seamError}"
        }
    }

    internal data class ProbeOptions(
        val normalize: Boolean = false,
        val independentReservoirs: Boolean = false,
        val contactsEnabled: Boolean = true,
        val activeChambers: Int = CHAMBERS,
        val durationSeconds: Float? = null,
        val driveStopSeconds: Float? = null,
        val seedContext: Int = 0,
        val recordDiagnostics: Boolean = true,
        val controlRateHz: Int = 1000,
    )
    internal data class StateSnapshot(
        val timeSeconds: Float, val suction: Float, val localPressure: FloatArray,
        val flows: FloatArray, val apertures: FloatArray, val displacement: FloatArray,
        val velocity: FloatArray, val sealed: BooleanArray, val pistonWork: Double, val energy: Double,
    )
    internal data class ContactEvent(val timeSeconds: Float, val chamber: Int, val strength: Float, val sealed: Boolean)
    internal data class Probe(
        val samples: FloatArray, val ceramic: FloatArray, val airflow: FloatArray, val shell: FloatArray,
        val snapshots: List<StateSnapshot>, val contacts: List<ContactEvent>, val rawPeak: Float,
        val finalEnergy: Double, val previousCycle: FloatArray = FloatArray(0),
        val seamError: Double = 0.0, val cycleStateError: Double = 0.0,
    )

    /** Rotation coordinates (displacement*omega, velocity) have norm equal to modal energy. */
    private class Mode(hz: Double, loss: Double) {
        var re = 0.0
        var im = 0.0
        private val c = cos(2 * PI * hz / INTERNAL_RATE)
        private val s = sin(2 * PI * hz / INTERNAL_RATE)
        private val attenuation = exp(-loss / INTERNAL_RATE)
        fun step(feedback: Double = 0.0) {
            val radius = re * re + im * im
            // A saturating radial reed law; no phase oscillator is mixed into the output.
            val gain = attenuation * (1 + feedback / INTERNAL_RATE / (1 + 100 * radius))
            val r = gain * (c * re - s * im)
            im = gain * (s * re + c * im)
            re = r
            if (!re.isFinite() || !im.isFinite()) { re = 0.0; im = 0.0 }
            if (abs(re) + abs(im) < 1e-25) { re = 0.0; im = 0.0 }
        }
        fun energy(): Double = re * re + im * im
    }

    private class Simulation(
        val voice: UndertowVoice, val m: Map<String, Float>, val eventEnergy: Double,
        val options: ProbeOptions, val cycleFrames: Int, val cancelled: () -> Boolean,
    ) {
        val sh = shape(voice)
        val draw = m.getValue("DRAW").toDouble()
        val flap = m.getValue("FLAP").toDouble()
        val weight = m.getValue("WEIGHT").toDouble()
        val spiral = m.getValue("SPIRAL").toDouble()
        val leak = m.getValue("LEAK").toDouble()
        val hold = m.getValue("HOLD").toDouble()
        val held = cycleFrames > 0
        val active = options.activeChambers.coerceIn(1, CHAMBERS)
        val controlFrames = (INTERNAL_RATE.toDouble() / options.controlRateHz.coerceIn(500, 4000)).roundToInt()
        val requestedHz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        // Fit complete pitch periods into the host loop (at most about 4.2 cents at C3).
        val hz = if (held) (requestedHz * cycleFrames / Dsp.RATE).roundToInt() * Dsp.RATE.toDouble() / cycleFrames else requestedHz
        val stop = options.driveStopSeconds?.takeIf { it.isFinite() }?.coerceIn(0f, MAX_SECONDS)?.toDouble()
            ?: (.20 + sh.duration * (.35 + 1.3 * draw) + 1.0 * minOf(hold, .98)).coerceAtMost(3.8)
        val mass = .55 + 1.65 * weight
        val stiffness = 2500 - 1700 * flap
        val compliance = .09 + .04 * spiral
        val local = DoubleArray(CHAMBERS)
        val isolated = DoubleArray(CHAMBERS)
        val x = DoubleArray(CHAMBERS)
        val v = DoubleArray(CHAMBERS)
        val area = DoubleArray(CHAMBERS) { .16 }
        val flows = DoubleArray(CHAMBERS)
        val audibleFlow = DoubleArray(CHAMBERS)
        val sealed = BooleanArray(CHAMBERS)
        val sealMix = DoubleArray(CHAMBERS)
        val refractory = DoubleArray(CHAMBERS)
        val contactPulse = DoubleArray(CHAMBERS)
        val pulseTarget = DoubleArray(CHAMBERS)
        val tone = Array(CHAMBERS) { Mode(hz * (it + 1), 9.0 + 2.0 * it + 5.0 * leak) }
        val rim = Array(CHAMBERS * 2) { j -> Mode(hz * (j / 2 + 1) * (if (j % 2 == 0) 1.0 else 2.0), 9 + 17.0 * (1 - weight) + 4 * (j % 2)) }
        val body = Array(BODY) { j -> Mode(hz * (1 + j) * (1 + .10 * (1 - spiral) * j), 12 - 7 * spiral + 4 * j) }
        val seed = Dsp.seedFor("UNDERTOW", voice, midiFor(voice, m.getValue("TUNE")), options.seedContext)
        val noise = Dsp.Noise(seed)
        val periodicNoise = if (held) FloatArray(cycleFrames * Dsp.OVERSAMPLE) { noise.next() } else FloatArray(0)
        var noiseLow = 0.0
        var suction = 0.0
        var extraction = 0.0
        var pistonWork = 0.0
        var frame = 0
        var ceramicSample = 0.0
        var airflowSample = 0.0
        var shellSample = 0.0
        val snapshots = ArrayList<StateSnapshot>()
        val contacts = ArrayList<ContactEvent>()
        var recordFrom = 0

        private fun drive(time: Double): Double {
            if (eventEnergy <= 0.0) return 0.0
            if (options.driveStopSeconds != null && time >= stop) return 0.0
            val rise = smooth((time / (.012 + .018 * weight)).coerceIn(0.0, 1.0))
            val release = if (held) 1.0 else smooth(((stop - time) / .16).coerceIn(0.0, 1.0))
            val capacity = (.30 + .75 * draw) * (.30 + .70 * eventEnergy)
            // HOLD's powered maintenance bypass preserves a pitched reed at soft/high-leak
            // corners. It scales to zero for zero event energy and adds no retriggered contact.
            val maintenance = if (held) (.27 + .08 * leak + .08 * draw) * minOf(1.0, eventEnergy / .15) else 0.0
            return max(capacity, maintenance) * rise * release
        }

        private fun control() {
            val dt = controlFrames.toDouble() / INTERNAL_RATE
            val time = frame.toDouble() / INTERNAL_RATE
            extraction = drive(time)
            val bypass = .08 + .24 * leak
            val totalFlow = (0 until active).sumOf { flows[it] }
            // Baseline relaxation works even when every flap seals; inflow lowers deficit.
            suction = (suction + dt * (extraction - totalFlow - bypass * sqrt(max(0.0, suction))) / compliance).coerceIn(0.0, 2.0)
            pistonWork += dt * extraction * suction
            for (j in 0 until active) {
                if (options.independentReservoirs) {
                    // Same per-inlet baseline; removing another inlet cannot alter this pressure.
                    isolated[j] = (isolated[j] + dt * (extraction / CHAMBERS - flows[j] - bypass / CHAMBERS * sqrt(max(0.0, isolated[j]))) / (compliance / CHAMBERS)).coerceIn(0.0, 2.0)
                }
                val target = if (options.independentReservoirs) isolated[j] else suction
                local[j] += (target - local[j]) * (1 - exp(-dt / (.003 + .015 * spiral + .003 * j)))
                val old = x[j]
                val k = stiffness * (1 + .12 * j)
                val damping = 2 * sqrt(k * mass) * (.34 - .19 * flap + if (held) .55 else 0.0)
                val flutter = if (held) 0.0 else 125 * flap * flap * sh.flutter * local[j] * area[j]
                val boundary = .47 + .065 * j + .04 * weight
                val penetration = max(0.0, x[j] - boundary)
                val contactWindow = exp(-penetration * penetration / .0025)
                // The ceramic passes a compliant rim before reaching the distant seal seat.
                // This short reaction dissipates inward work at the audible tapping boundary.
                val rimReaction = if (options.contactsEnabled && x[j] > boundary)
                    (6500 * penetration + 20 * max(0.0, v[j])) * contactWindow else 0.0
                // The distant compliant travel stop keeps seal-seat motion bounded.
                val collision = 9000 * max(0.0, x[j] - (1.04 - .03 * j)) + 65 * max(0.0, v[j]) * smooth(((x[j] - .95) / .08).coerceIn(0.0, 1.0))
                val force = 1450 * local[j] - k * x[j] - rimReaction - collision
                val trial = v[j] + dt * force / mass
                // Implicit loss avoids explicit cubic-damping instability at high speed.
                // The exponential gain is powered aerodynamic work, absent without pressure.
                val poweredGain = exp(minOf(.3, dt * max(0.0, flutter - damping) / mass))
                v[j] = (trial * poweredGain / (1 + dt * (max(0.0, damping - flutter) + 1.8 * trial * trial) / mass)).coerceIn(-18.0, 18.0)
                x[j] = (x[j] + dt * v[j]).coerceIn(-.12, 1.16)
                // A reciprocal velocity exchange loads mechanics and lets shell vibration disturb a catch.
                val b = body[j % BODY]
                val mechanicalVelocity = v[j] * .008 * sqrt(mass)
                val exchange = (mechanicalVelocity - b.im) * (.008 + .018 * spiral) * flap * controlFrames / CONTROL
                v[j] -= exchange / (.008 * sqrt(mass))
                b.im += exchange
                refractory[j] = max(0.0, refractory[j] - dt)
                if (old < boundary && x[j] >= boundary && v[j] > 0 && refractory[j] <= 0.0) {
                    val strength = (v[j] * sqrt(mass) * .011 * sh.contact).coerceIn(0.0, .16)
                    if (options.contactsEnabled) {
                        pulseTarget[j] += strength
                        if (options.recordDiagnostics && frame >= recordFrom) contacts += ContactEvent(time.toFloat(), j, strength.toFloat(), sealed[j])
                    }
                    refractory[j] = .025 + .015 * weight
                }
                // Seal hysteresis is separate from the lower tapping boundary. Pressure bends
                // a seal; elastic return reopens it as extraction/pressure relax, without chatter.
                val closing = .97 - .12 * sh.seal + .015 * j
                if (!sealed[j] && x[j] > closing) sealed[j] = true
                if (sealed[j] && x[j] < closing - .15) sealed[j] = false
                sealMix[j] += ((if (sealed[j]) .95 - .16 * leak else 0.0) - sealMix[j]) * (1 - exp(-dt / .012))
                area[j] = (.16 + .84 * smooth((x[j] / .65).coerceIn(0.0, 1.0))) * (1 - sealMix[j])
                // .018 is a documented imperfect-rim leak, in addition to reservoir bypass.
                flows[j] = (.18 - .035 * spiral) * (area[j] + .018 + .04 * leak) * sqrt(local[j])
                if (!x[j].isFinite() || !v[j].isFinite() || !local[j].isFinite()) { x[j] = 0.0; v[j] = 0.0; local[j] = 0.0; flows[j] = 0.0; sealed[j] = false }
            }
            if (options.recordDiagnostics && frame >= recordFrom && frame % (controlFrames * 10) == 0) {
                snapshots += StateSnapshot(time.toFloat(), (if (options.independentReservoirs) isolated[0] else suction).toFloat(), local.map { it.toFloat() }.toFloatArray(), flows.map { it.toFloat() }.toFloatArray(), area.map { it.toFloat() }.toFloatArray(), x.map { it.toFloat() }.toFloatArray(), v.map { it.toFloat() }.toFloatArray(), sealed.copyOf(), pistonWork, energy())
            }
        }

        fun step(): Float {
            if (frame % 8192 == 0 && (cancelled() || Thread.currentThread().isInterrupted)) throw CancellationException("UNDERTOW render no longer wanted")
            if (frame % controlFrames == 0) control()
            val texture = if (held) periodicNoise[frame % periodicNoise.size].toDouble() else noise.next().toDouble()
            noiseLow += (texture - noiseLow) * .055
            ceramicSample = 0.0
            airflowSample = 0.0
            shellSample = 0.0
            for (j in 0 until active) {
                // Finite ~1 ms contact force, integrated into modes at the oversampled clock.
                contactPulse[j] += (pulseTarget[j] - contactPulse[j]) * .025
                pulseTarget[j] *= .975
                val impulse = contactPulse[j] * .010
                for (r in 0..1) {
                    val mode = rim[2 * j + r]
                    mode.im += impulse * (if (r == 0) 1.0 else .32 * (1 - .45 * weight))
                    mode.step()
                    ceramicSample += mode.re * (if (j == 0) 1.0 else .32 / (j + 1))
                }
                val t = tone[j]
                // Smooth slow-clock flow before applying nonlinear acoustic feedback.
                audibleFlow[j] += (flows[j] - audibleFlow[j]) * .008
                val flow = audibleFlow[j]
                // Flow-fed inward reed feedback: gain rises only with pressure-driven flow.
                // Root is strongest; upper apertures alter harmonics, not unrelated pitches.
                val feedback = 850 * flow * (.8 + .65 * draw) * (1 - .12 * sealMix[j])
                val quietSeed = if (held && frame > INTERNAL_RATE) 0.0 else noiseLow * flow * .000045
                t.im += quietSeed + impulse * .09
                t.step(feedback)
                airflowSample += t.re * sh.breath * (if (j == 0) 1.0 else .24 / sqrt((j + 1).toDouble()))
                airflowSample += noiseLow * flow * (.025 + .04 * leak) / (j + 1)
                // Reciprocal, norm-contracting exchanges connect every chamber to the shell.
                val b = body[j % BODY]
                val amount = (.20 + .65 * spiral) / INTERNAL_RATE
                val exchange = if (options.contactsEnabled) (rim[j * 2].im - b.im) * amount else 0.0
                rim[j * 2].im -= exchange
                b.im += exchange
                // The same flow resonator is loaded by the shell, not a post-output echo.
                val loading = (t.im - b.im) * amount * .25
                t.im -= loading
                b.im += loading
            }
            for (j in body.indices) {
                body[j].step()
                shellSample += body[j].re * (1.0 + 1.5 * spiral) * sh.body / (j + 1)
            }
            frame++
            return ((ceramicSample + airflowSample + shellSample) * TRIM).toFloat()
        }

        fun energy(): Double {
            var e = .5 * compliance * suction * suction
            for (j in 0 until active) e += .00005 * (.5 * mass * v[j] * v[j] + .5 * stiffness * x[j] * x[j]) + .01 * local[j] * local[j]
            for (mode in tone) e += mode.energy()
            for (mode in rim) e += mode.energy()
            for (mode in body) e += mode.energy()
            return e
        }

        fun stateGroups(): List<DoubleArray> = listOf(
            doubleArrayOf(suction) + isolated + local,
            x + v + area + flows + audibleFlow + sealMix + refractory + sealed.map { if (it) 1.0 else 0.0 }.toDoubleArray(),
            (tone + rim + body).flatMap { listOf(it.re, it.im) }.toDoubleArray() + contactPulse + pulseTarget,
            doubleArrayOf(noiseLow),
        )
    }

    private fun smooth(x: Double): Double = x * x * (3 - 2 * x)

    private fun stateError(a: List<DoubleArray>, b: List<DoubleArray>): Double = a.indices.maxOf { group ->
        var difference = 0.0
        var norm = 0.0
        for (i in a[group].indices) {
            val delta = a[group][i] - b[group][i]
            difference += delta * delta
            norm += .5 * (a[group][i] * a[group][i] + b[group][i] * b[group][i])
        }
        sqrt(difference / max(if (group == 2) 1e-12 else .0001, norm))
    }

    internal fun probe(
        voice: UndertowVoice, macros: Map<String, Float> = emptyMap(), energy: Float = 1f,
        options: ProbeOptions = ProbeOptions(), cancelled: () -> Boolean = { false },
    ): Probe {
        val m = settled(voice, macros)
        val held = isLoop(m.getValue("HOLD")) && options.driveStopSeconds == null
        // Align the slow clock, periodic roughness and integer pitch periods to one cycle.
        val baseFrames = (Dsp.RATE * 1.6).roundToInt()
        val cycleFrames = if (held) (baseFrames / (CONTROL / Dsp.OVERSAMPLE)) * (CONTROL / Dsp.OVERSAMPLE) else 0
        val simulation = Simulation(voice, m, finite(energy, 1f).toDouble(), options, cycleFrames, cancelled)
        var convergence = 0.0
        if (held) {
            var previous: List<DoubleArray>? = null
            for (cycle in 0 until 12) {
                repeat(cycleFrames * Dsp.OVERSAMPLE) { simulation.step() }
                val state = simulation.stateGroups()
                convergence = previous?.let { stateError(it, state) } ?: Double.POSITIVE_INFINITY
                previous = state
                if (cycle >= 3 && convergence < .0005) break
            }
            // Preroll diagnostics are discarded; returned times describe the captured material.
            simulation.snapshots.clear()
            simulation.contacts.clear()
        }
        val seconds = (options.durationSeconds?.takeIf { it.isFinite() } ?: (simulation.stop + 1.8 + .6 * m.getValue("SPIRAL")).toFloat()).coerceIn(.1f, MAX_SECONDS)
        val frames = if (held) 2 * cycleFrames else (seconds * Dsp.RATE).roundToInt()
        val padding = if (held) 256 else 0
        val startFrame = simulation.frame
        simulation.recordFrom = startFrame
        val count = (frames + padding) * Dsp.OVERSAMPLE
        val taps = Array(if (options.recordDiagnostics) 4 else 1) { FloatArray(count) }
        var middleState: List<DoubleArray>? = null
        var endState: List<DoubleArray>? = null
        for (i in 0 until count) {
            taps[0][i] = simulation.step()
            if (taps.size > 1) {
                taps[1][i] = (simulation.ceramicSample * TRIM).toFloat()
                taps[2][i] = (simulation.airflowSample * TRIM).toFloat()
                taps[3][i] = (simulation.shellSample * TRIM).toFloat()
            }
            if (held && i + 1 == cycleFrames * Dsp.OVERSAMPLE) middleState = simulation.stateGroups()
            if (held && i + 1 == 2 * cycleFrames * Dsp.OVERSAMPLE) endState = simulation.stateGroups()
        }
        if (held) convergence = middleState?.let { stateError(it, checkNotNull(endState)) } ?: convergence
        val decimated = taps.map { Dsp.decimate(it, Dsp.RATE) }
        val raw = if (held) decimated[0].copyOfRange(cycleFrames, 2 * cycleFrames) else decimated[0]
        val previous = if (held) decimated[0].copyOfRange(0, cycleFrames) else FloatArray(0)
        val peak = raw.maxOfOrNull { abs(it) } ?: 0f
        val seam = if (held && peak > 1e-12f) Keys.seamError(previous + raw, previous.size) else 0.0
        if (!held) Dsp.fadeTail(raw)
        if (options.normalize) {
            Dsp.levelTo(raw, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            val normalizedPeak = raw.maxOfOrNull { abs(it) } ?: 0f
            val gain = if (peak > 1e-12f) normalizedPeak / peak else 1f
            for (i in previous.indices) previous[i] *= gain
        }
        val timeOffset = startFrame.toFloat() / INTERNAL_RATE + if (held) cycleFrames.toFloat() / Dsp.RATE else 0f
        val endTime = timeOffset + raw.size.toFloat() / Dsp.RATE
        fun stem(i: Int): FloatArray = if (decimated.size <= i) FloatArray(0) else if (held) decimated[i].copyOfRange(cycleFrames, 2 * cycleFrames) else decimated[i]
        return Probe(raw, stem(1), stem(2), stem(3),
            simulation.snapshots.filter { it.timeSeconds >= timeOffset && it.timeSeconds < endTime }.map { it.copy(timeSeconds = it.timeSeconds - timeOffset) },
            simulation.contacts.filter { it.timeSeconds >= timeOffset && it.timeSeconds < endTime }.map { it.copy(timeSeconds = it.timeSeconds - timeOffset) },
            peak, simulation.energy(), previous, seam, convergence)
    }
}
