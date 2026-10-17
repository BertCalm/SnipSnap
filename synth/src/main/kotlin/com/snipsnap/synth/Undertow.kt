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
        val tap: Double = .47, val onset: Double = .024, val capacity: Double = 1.0,
        val rimDrive: Double = .14, val rimLoss: Double = 15.0, val rimUpper: Double = 2.0,
        val reedLoss: Double = 9.0,
        val partials: DoubleArray = doubleArrayOf(1.0, .17, .14, .12),
        val turbulence: Double = 1.0, val roughness: Double = 0.0,
        val mounting: Double = 1.0, val shellLoss: Double = 8.0,
        val shellProjection: DoubleArray = doubleArrayOf(1.0, .5, .33),
        val flex: Double = 0.0, val radiation: Double = 0.0,
        val relief: Double = 0.0, val sealLag: Double = .012,
    )

    private fun shape(voice: UndertowVoice) = when (voice) {
        // These are different inlet/material designs, not six gain settings on one reed.
        UndertowVoice.KNOCK -> Shape(floatArrayOf(.45f, .30f, .60f, .35f, .55f), .25, 1.25, .52, .35, .10, .70,
            tap = .18, rimDrive = .22, rimLoss = 7.0, rimUpper = 3.0, reedLoss = 28.0,
            partials = doubleArrayOf(1.0, .12, .34, .06), mounting = 3.0)
        UndertowVoice.BREATH -> Shape(floatArrayOf(.55f, .40f, .35f, .55f, .40f), 1.30, .45, 1.10, .25, .10, .60,
            onset = .065, rimDrive = .045, rimLoss = 32.0,
            partials = doubleArrayOf(1.0, .035, .018, .01), turbulence = 24.0, shellLoss = 14.0)
        UndertowVoice.FLUTTER -> Shape(floatArrayOf(.60f, .80f, .40f, .50f, .35f), 1.30, .75, .90, 1.50, -1.8, .75,
            capacity = .72, rimDrive = .09, partials = doubleArrayOf(1.0, .38, .22, .12),
            turbulence = 6.0, mounting = 3.0, flex = 1.0, radiation = .90)
        UndertowVoice.SEAL -> Shape(floatArrayOf(.65f, .55f, .60f, .60f, .20f), 1.40, .90, .80, .85, 4.3, .90,
            rimDrive = .14, partials = doubleArrayOf(1.0, .08, .50, .22), mounting = 5.0,
            radiation = .85, relief = .95, sealLag = .065)
        UndertowVoice.HOLLOW -> Shape(floatArrayOf(.50f, .45f, .65f, .85f, .35f), .75, .70, .55, .40, .20, 1.40,
            rimDrive = .12, rimLoss = 12.0, reedLoss = 13.0,
            partials = doubleArrayOf(1.0, .25, .06, .03), mounting = 30.0, shellLoss = .8,
            shellProjection = doubleArrayOf(1.0, 1.1, .09))
        UndertowVoice.SURGE -> Shape(floatArrayOf(.85f, .65f, .70f, .65f, .30f), 1.40, 1.05, 1.00, 1.20, .55, 1.00,
            tap = .32, onset = .055, rimDrive = .10,
            partials = doubleArrayOf(1.0, .62, .46, .34), turbulence = 10.0, roughness = .75,
            mounting = 5.0, shellLoss = 6.0)
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
        val controlFrames = controlFramesFor(options.controlRateHz)
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
        val area = DoubleArray(CHAMBERS) { if (it < active) .16 else 0.0 }
        val flows = DoubleArray(CHAMBERS)
        val audibleFlow = DoubleArray(CHAMBERS)
        val sealed = BooleanArray(CHAMBERS)
        val sealMix = DoubleArray(CHAMBERS)
        val refractory = DoubleArray(CHAMBERS)
        val contactPulse = DoubleArray(CHAMBERS)
        val pulseTarget = DoubleArray(CHAMBERS)
        val tone = Array(CHAMBERS) { Mode(hz * (it + 1), (if (held) minOf(9.0, sh.reedLoss) else sh.reedLoss) + 2.0 * it + 5.0 * leak) }
        val rim = Array(CHAMBERS * 2) { j -> Mode(hz * (j / 2 + 1) * (if (j % 2 == 0) 1.0 else sh.rimUpper), sh.rimLoss + 10.0 * (1 - weight) + 4 * (j % 2)) }
        val body = Array(BODY) { j -> Mode(hz * (1 + j) * (if (held) 1.0 else 1 + .10 * (1 - spiral) * j), sh.shellLoss + 4 * (1 - spiral) + 4 * j) }
        // A pressure-powered leather flexure changes the opening itself. It is never
        // mixed into the audio as an independent tremolo oscillator or noise source.
        val flexure = Array(CHAMBERS) { j -> Mode((12 + 14 * flap) * (1 + .11 * j), 8.0) }
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
        val dcInput = DoubleArray(3)
        val dcOutput = DoubleArray(3)
        val dcPole = exp(-2 * PI * 8 / INTERNAL_RATE)
        val snapshots = ArrayList<StateSnapshot>()
        val contacts = ArrayList<ContactEvent>()
        var recordFrom = 0

        private fun drive(time: Double): Double {
            if (eventEnergy <= 0.0) return 0.0
            if (options.driveStopSeconds != null && time >= stop) return 0.0
            val rise = smooth((time / (sh.onset * (.65 + .7 * weight))).coerceIn(0.0, 1.0))
            val release = if (held) 1.0 else smooth(((stop - time) / .16).coerceIn(0.0, 1.0))
            val swelling = if (voice == UndertowVoice.SURGE && !held) .62 + .38 * smooth((time / (stop * .7)).coerceIn(0.0, 1.0)) else 1.0
            val capacity = (.30 + .75 * draw) * (.30 + .70 * eventEnergy) * sh.capacity * swelling
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
                val boundary = sh.tap + .065 * j + .04 * weight
                val penetration = max(0.0, x[j] - boundary)
                val contactWindow = exp(-penetration * penetration / .0025)
                // The ceramic passes a compliant rim before reaching the distant seal seat.
                // This short reaction dissipates inward work at the audible tapping boundary.
                val rimReaction = if (options.contactsEnabled && x[j] > boundary)
                    (6500 * penetration + 20 * max(0.0, v[j])) * contactWindow else 0.0
                // The distant compliant travel stop keeps seal-seat motion bounded.
                val collision = 9000 * max(0.0, x[j] - (1.04 - .03 * j)) + 65 * max(0.0, v[j]) * smooth(((x[j] - .95) / .08).coerceIn(0.0, 1.0))
                // The seated flap nearly equalizes its pressure load even though a rim
                // leak still admits air. Normalize seat travel independently of that
                // acoustic leak, so stronger draws cannot hold it shut until release.
                val seated = if (!held && sh.relief > 0.0)
                    (sealMix[j] / (.95 - .16 * leak)).coerceIn(0.0, 1.0) else 0.0
                val pressureLoad = local[j] * (1 - sh.relief * seated)
                val force = 1450 * pressureLoad - k * x[j] - rimReaction - collision
                val trial = v[j] + dt * force / mass
                // Implicit loss avoids explicit cubic-damping instability at high speed.
                // The exponential gain is powered aerodynamic work, absent without pressure.
                val poweredGain = exp(minOf(.3, dt * max(0.0, flutter - damping) / mass))
                v[j] = (trial * poweredGain / (1 + dt * (max(0.0, damping - flutter) + 1.8 * trial * trial) / mass)).coerceIn(-18.0, 18.0)
                x[j] = (x[j] + dt * v[j]).coerceIn(-.12, 1.16)
                // A reciprocal velocity exchange loads mechanics and lets shell vibration disturb a catch.
                val b = body[j % BODY]
                val mechanicalVelocity = v[j] * .008 * sqrt(mass)
                if (!held) {
                    // HOLLOW's mounting relaxes with the piston, isolating the stored
                    // body ring from the damped flaps after its powered draw ends.
                    val engagement = if (voice == UndertowVoice.HOLLOW) .035 + .965 * minOf(1.0, extraction / .15) else 1.0
                    val exchange = (mechanicalVelocity - b.im) * (.008 + .018 * spiral) * flap * controlFrames / CONTROL * engagement
                    v[j] -= exchange / (.008 * sqrt(mass))
                    b.im += exchange
                }
                refractory[j] = max(0.0, refractory[j] - dt)
                if (old < boundary && x[j] >= boundary && v[j] > 0 && refractory[j] <= 0.0) {
                    val strength = (v[j] * sqrt(mass) * .011 * sh.contact).coerceIn(0.0, .06)
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
                if (sealed[j] && x[j] < closing - (if (sh.relief > 0 && !held) .25 else .15)) sealed[j] = false
                sealMix[j] += ((if (sealed[j]) .95 - .16 * leak else 0.0) - sealMix[j]) * (1 - exp(-dt / sh.sealLag))
                val flexOpening = if (held) 1.0 else 1 - sh.flex * .85 * smooth(((flexure[j].re + .24) / .48).coerceIn(0.0, 1.0))
                area[j] = (.16 + .84 * smooth((x[j] / .65).coerceIn(0.0, 1.0))) * (1 - sealMix[j]) * flexOpening
                // .018 is a documented imperfect-rim leak, in addition to reservoir bypass.
                flows[j] = (.18 - .035 * spiral) * (area[j] + .018 + .04 * leak) * sqrt(local[j])
                if (!x[j].isFinite() || !v[j].isFinite() || !local[j].isFinite()) { x[j] = 0.0; v[j] = 0.0; local[j] = 0.0; flows[j] = 0.0; sealed[j] = false }
            }
            if (options.independentReservoirs) {
                pistonWork += dt * (0 until active).sumOf { extraction / CHAMBERS * isolated[it] }
            } else {
                pistonWork += dt * extraction * suction
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
                val impulse = contactPulse[j] * sh.rimDrive
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
                // Moving leather also shutters radiation at the same opening. The
                // resonator continues to ring; flow and radiation both follow its valve.
                airflowSample += t.re * sh.breath * sh.partials[j] * (1 - sh.radiation + sh.radiation * area[j])
                airflowSample += (noiseLow + sh.roughness * .32 * (texture - noiseLow)) * flow * (.025 + .04 * leak) * sh.turbulence / (j + 1)
                if (sh.flex > 0 && !held) {
                    val f = flexure[j]
                    f.im += noiseLow * flow * .000015 + impulse * .005
                    f.step(480 * flow * (.4 + flap))
                }
                // Reciprocal, norm-contracting exchanges connect every chamber to the shell.
                val b = body[j % BODY]
                val engagement = if (voice == UndertowVoice.HOLLOW && !held) .035 + .965 * minOf(1.0, extraction / .15) else 1.0
                // Settled HOLD uses harmonic mounts and isotropic exchange, without
                // cross-register or slow-clock acoustic reaction that drifts free phase.
                // One-shots retain detuned modes and the full mechanical reaction.
                val amount = if (held && j >= BODY) 0.0 else (.20 + .65 * spiral) * sh.mounting * engagement / INTERNAL_RATE
                val exchange = if (options.contactsEnabled) (rim[j * 2].im - b.im) * amount else 0.0
                rim[j * 2].im -= exchange
                b.im += exchange
                if (held && options.contactsEnabled) {
                    val displacement = (rim[j * 2].re - b.re) * amount
                    rim[j * 2].re -= displacement
                    b.re += displacement
                }
                // The same flow resonator is loaded by the shell, not a post-output echo.
                val loading = (t.im - b.im) * amount * .25
                t.im -= loading
                b.im += loading
                if (held) {
                    val displacement = (t.re - b.re) * amount * .25
                    t.re -= displacement
                    b.re += displacement
                }
            }
            for (j in body.indices) {
                body[j].step()
                shellSample += body[j].re * (1.0 + 1.5 * spiral) * sh.body * sh.shellProjection[j]
            }
            // Flow-derived texture may acquire a small finite-record bias. A linear 8 Hz
            // blocker on each pickup preserves the sum of raw stems and the pitched root.
            // Its memory is part of HOLD convergence, and never enters acoustic feedback.
            ceramicSample = removeDc(0, ceramicSample)
            airflowSample = removeDc(1, airflowSample)
            shellSample = removeDc(2, shellSample)
            frame++
            return ((ceramicSample + airflowSample + shellSample) * TRIM).toFloat()
        }

        private fun removeDc(pickup: Int, input: Double): Double {
            val output = input - dcInput[pickup] + dcPole * dcOutput[pickup]
            dcInput[pickup] = input
            dcOutput[pickup] = output
            return output
        }

        fun energy(): Double {
            var e = if (options.independentReservoirs) {
                isolated.sumOf { .5 * (compliance / CHAMBERS) * it * it }
            } else {
                .5 * compliance * suction * suction
            }
            for (j in 0 until active) e += .00005 * (.5 * mass * v[j] * v[j] + .5 * stiffness * x[j] * x[j]) + .01 * local[j] * local[j]
            for (mode in tone) e += mode.energy()
            for (mode in rim) e += mode.energy()
            for (mode in body) e += mode.energy()
            for (mode in flexure) e += mode.energy()
            return e
        }

        fun stateGroups(): List<DoubleArray> = listOf(
            doubleArrayOf(suction) + isolated + local,
            x + v + area + flows + audibleFlow + sealMix + refractory + sealed.map { if (it) 1.0 else 0.0 }.toDoubleArray(),
            (tone + rim + body + flexure).flatMap { listOf(it.re, it.im) }.toDoubleArray() + contactPulse + pulseTarget,
            doubleArrayOf(noiseLow),
            dcInput + dcOutput,
        )
    }

    private fun controlFramesFor(controlRateHz: Int): Int =
        (INTERNAL_RATE.toDouble() / controlRateHz.coerceIn(500, 4000)).roundToInt()

    private fun greatestCommonDivisor(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val remainder = x % y
            x = y
            y = remainder
        }
        return x
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
        sqrt(difference / max(if (group == 2 || group == 4) 1e-12 else .0001, norm))
    }

    internal fun probe(
        voice: UndertowVoice, macros: Map<String, Float> = emptyMap(), energy: Float = 1f,
        options: ProbeOptions = ProbeOptions(), cancelled: () -> Boolean = { false },
    ): Probe {
        val m = settled(voice, macros)
        val held = isLoop(m.getValue("HOLD")) && options.driveStopSeconds == null
        // Align the slow clock, periodic roughness and integer pitch periods to one cycle.
        val baseFrames = (Dsp.RATE * 1.6).roundToInt()
        val controlFrames = controlFramesFor(options.controlRateHz)
        val cycleQuantum = controlFrames / greatestCommonDivisor(controlFrames, Dsp.OVERSAMPLE)
        val cycleFrames = if (held) (baseFrames.toDouble() / cycleQuantum).roundToInt() * cycleQuantum else 0
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
