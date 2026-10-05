package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class ThawVoice { BRITTLE, RUNNER, MELT, CHANNEL, FROST, SHEET }

/**
 * Tuned ice plates contacted by powered copper runners. These are normalized musical units,
 * not measured properties of ice. Contact work heats a bounded latent layer, water travels
 * through four delayed channels, and cooling can spend a finite stored phase-stress budget.
 * No oscillator supplies the note: the same modal coordinates carry engagement and friction.
 *
 * Acoustic coordinates are (omega*q, velocity), so changing loading preserves their norm.
 * Each reciprocal velocity exchange is a contraction; after contact and finite stress release
 * stop, the network is passive even while the thermal coefficients continue to change.
 * HOLD delivers settled, balanced contact. The host stores one loop buffer, so the initial
 * frozen engagement is deliberately absent from HOLD exports.
 */
object Thaw {
    const val TUNE_SEMITONES = 24
    const val ROOT_MIDI = 48
    private const val PLATES = 4
    private const val MODES = 4
    private const val BOX_MODES = 3
    private const val SIZE = PLATES * MODES + BOX_MODES
    private const val CTRL = 176
    private const val LATENT = 0.70
    private const val TRANSITION = 0.35
    private const val MAX_ENTHALPY = 1.35
    private const val RATE = Dsp.RATE * Dsp.OVERSAMPLE

    fun rootMidi(voice: ThawVoice): Int = ROOT_MIDI
    fun midiFor(voice: ThawVoice, tune: Float): Int = rootMidi(voice) +
        Math.round(finite(tune, .5f) * TUNE_SEMITONES)
    fun frequencyFor(voice: ThawVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= .999f

    private data class Shape(
        val controls: FloatArray, val gesture: Double, val loss: Double, val texture: Double,
        val upper: Double, val constraint: Double, val body: Double,
    )

    private fun shape(voice: ThawVoice): Shape = when (voice) {
        ThawVoice.BRITTLE -> Shape(floatArrayOf(.40f, .20f, .70f, .25f, .35f), .65, .75, .55, 1.15, .75, .12)
        ThawVoice.RUNNER -> Shape(floatArrayOf(.60f, .50f, .40f, .30f, .50f), 1.45, 1.05, .80, 1.00, .75, .14)
        ThawVoice.MELT -> Shape(floatArrayOf(.40f, .75f, .25f, .45f, .45f), 1.75, 1.15, .35, .75, .65, .13)
        ThawVoice.CHANNEL -> Shape(floatArrayOf(.45f, .55f, .45f, .80f, .55f), 1.65, 1.25, .50, .90, 1.00, .15)
        ThawVoice.FROST -> Shape(floatArrayOf(.50f, .55f, .85f, .70f, .40f), 1.10, .90, .65, 1.05, 1.70, .12)
        ThawVoice.SHEET -> Shape(floatArrayOf(.55f, .45f, .35f, .55f, .85f), 1.90, 1.30, .40, .60, .90, .22)
    }

    fun macrosFor(voice: ThawVoice): List<MacroSpec> {
        val d = shape(voice).controls
        return listOf(
            MacroSpec("TUNE", .5f, neutral = .5f),
            MacroSpec("CONTACT", d[0], neutral = .40f),
            MacroSpec("HEAT", d[1], neutral = .40f),
            MacroSpec("FREEZE", d[2], neutral = .40f),
            MacroSpec("CHANNELS", d[3], neutral = .30f),
            MacroSpec("THICKNESS", d[4], neutral = .50f),
            MacroSpec("HOLD", 0f, neutral = 0f),
        )
    }

    fun defaults(voice: ThawVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    private fun finite(v: Float, fallback: Float): Float = if (v.isFinite()) v.coerceIn(0f, 1f) else fallback
    internal fun settled(macros: Map<String, Float>, voice: ThawVoice): Map<String, Float> =
        defaults(voice).mapValues { (k, v) -> finite(macros[k] ?: v, v) }

    /** Finite powered travel plus a genuinely passive tail, within the host's six-second budget. */
    internal fun renderFrames(voice: ThawVoice, macros: Map<String, Float>): Int {
        val m = settled(macros, voice)
        val seconds = gestureSeconds(voice, m) + 1.55 + .65 * m.getValue("CHANNELS") +
            .55 * m.getValue("THICKNESS")
        return (seconds.coerceIn(2.0, 6.0) * Dsp.RATE).toInt()
    }

    private fun gestureSeconds(voice: ThawVoice, m: Map<String, Float>): Double =
        (shape(voice).gesture + 1.50 * min(.998f, m.getValue("HOLD"))).coerceAtMost(3.6)

    fun drumClassFor(voice: ThawVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(macros, voice).getValue("HOLD")) || renderFrames(voice, macros) > Dsp.RATE * 1.5)
            DrumClass.LOOP else DrumClass.TONAL

    fun scramble(voice: ThawVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val d = defaults(voice)
        val seed = if (near == null) d else d + near.macros.filterKeys { it in d }
        return Dsp.scrambleNear(seed, finite(temperature, .35f), random).toMutableMap().also {
            it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f)
        }
    }

    internal class Probe(
        val record: Boolean = false,
        val thermal: Boolean = true,
        val channelTransfer: Boolean = true,
        val drive: Boolean = true,
        val durationSeconds: Float? = null,
        val velocity: Float = 1f,
        val stopSeconds: Float? = null,
        val friction: Boolean = true,
    )

    internal class Trace(val rate: Int, val frames: Int) {
        val runnerVelocity = FloatArray(frames)
        val contactForce = FloatArray(frames)
        val contactWork = FloatArray(frames)
        val temperature = Array(PLATES) { FloatArray(frames) }
        val liquid = Array(PLATES) { FloatArray(frames) }
        val channel = Array(PLATES) { FloatArray(frames) }
        val stress = Array(PLATES) { FloatArray(frames) }
        val events = FloatArray(frames)
        val energy = FloatArray(frames)
        /** Liquid in contact layers, transport queues and channels. */
        val liquidTotal = FloatArray(frames)
        /** Cumulative explicit melting minus freezing/drainage, equal to liquidTotal. */
        val liquidBalance = FloatArray(frames)
    }

    internal class Played(
        val raw: FloatArray, val direct: FloatArray?, val neighbors: FloatArray?,
        val enclosure: FloatArray?, val trace: Trace?, val finalState: DoubleArray,
        val maxEnergy: Double, val recoveredStates: Int,
    )

    /** Safeguarded implicit Stribeck contact. Its root is unique under the checked compliance bound. */
    private class Contact {
        var slip = 0.0
        fun force(speed: Double, surface: Double, pressure: Double, compliance: Double, a: Double): Double {
            val rhs = speed - surface
            if (pressure <= 0.0) { slip = rhs; return 0.0 }
            val r = sqrt(2 * a)
            val c = pressure * compliance
            check(c * r * 2 / Math.E < .9) { "THAW contact exceeded its implicit stability bound" }
            var lo = rhs - c
            var hi = rhs + c
            var x = slip.coerceIn(lo, hi)
            for (j in 0 until 12) {
                val ee = exp(-a * x * x + .5)
                val phi = r * x * ee
                val g = x + c * phi - rhs
                if (abs(g) < 1e-12) break
                if (g > 0) hi = x else lo = x
                val next = x - g / (1 + c * r * ee * (1 - 2 * a * x * x))
                x = if (next > lo && next < hi) next else .5 * (lo + hi)
            }
            slip = x
            return pressure * r * x * exp(-a * x * x + .5)
        }
    }

    private class Engine(
        val voice: ThawVoice, val m: Map<String, Float>, val probe: Probe, val held: Boolean = false,
    ) {
        val configuration = shape(voice)
        val contact = m.getValue("CONTACT").toDouble()
        val heat = m.getValue("HEAT").toDouble()
        val freeze = m.getValue("FREEZE").toDouble()
        val channels = m.getValue("CHANNELS").toDouble()
        val thick = m.getValue("THICKNESS").toDouble()
        val velocity = finite(probe.velocity, 1f).toDouble()
        val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val dt = 1.0 / RATE
        var controlDt = CTRL * dt
        val endDrive = probe.stopSeconds?.toDouble() ?: gestureSeconds(voice, m)
        val mass = .65 + .90 * thick
        val capacity = .08 + .52 * channels
        val re = DoubleArray(SIZE)
        val im = DoubleArray(SIZE)
        val crossingAcoustic = DoubleArray(SIZE * 2)
        val cr = DoubleArray(SIZE)
        val sr = DoubleArray(SIZE)
        val targetCr = DoubleArray(SIZE)
        val targetSr = DoubleArray(SIZE)
        val ratios = doubleArrayOf(1.0, 2.3, 4.0, 6.1)
        val roots = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val b = Array(2) { DoubleArray(SIZE) }
        val pickup = DoubleArray(SIZE)
        val friction = Array(2) { Contact() }
        val h = DoubleArray(PLATES)
        val water = DoubleArray(PLATES)
        val channel = DoubleArray(PLATES)
        val stress = DoubleArray(PLATES)
        val refractory = DoubleArray(PLATES)
        val pulses = DoubleArray(PLATES)
        val transit = Array(PLATES) { DoubleArray(64) }
        val transitTotal = DoubleArray(PLATES)
        var transitIndex = 0
        val lagH = DoubleArray(PLATES)
        val loss = DoubleArray(SIZE)
        val a = DoubleArray(2) { 5000.0 }
        val pressure = DoubleArray(2)
        val rough = DoubleArray(2)
        val acousticExchange = DoubleArray(PLATES * MODES)
        val bridgeCr = DoubleArray(PLATES) { 1.0 }
        val bridgeSr = DoubleArray(PLATES)
        var sharedBridgeCr = 1.0
        var sharedBridgeSr = 0.0
        val enclosureExchange = .5 * (1 - exp(-2 * (5 + 6 * thick) * configuration.body * dt))
        var sharedExchange = 0.0
        val compliance = DoubleArray(2)
        // Common microscopic surface for a voice/note: macro comparisons change mechanics,
        // rather than obtaining an unrelated random realization on every knob movement.
        val noise = Dsp.Noise(Dsp.seedFor("THAW", voice, midiFor(voice, m.getValue("TUNE")), "surface"))
        val roughLow = Dsp.OnePole(RATE)
        var texture = 0.0
        var sample = 0L
        var lastControlSample = 0L
        var quarter = 0
        var work = DoubleArray(PLATES)
        var workCount = 0
        var lastWork = 0.0
        var lastForce = 0.0
        var lastSpeed = 0.0
        var lastEvents = 0
        var liquidBalance = 0.0
        var maxEnergy = 0.0
        var recovered = 0
        var rootCrossings = 0L
        var direct = 0.0
        var neighbors = 0.0
        var enclosure = 0.0

        init {
            for (runner in 0..1) for (j in 0 until MODES) {
                val k = runner * MODES + j
                b[runner][k] = when (j) {
                    0 -> 1.0
                    1 -> .33 * configuration.upper * (1 - .5 * thick)
                    2 -> -.20 * configuration.upper * (1 - .6 * thick)
                    else -> .10 * configuration.upper * (1 - .6 * thick)
                }
            }
            for (p in 0 until PLATES) for (j in 0 until MODES) {
                val k = p * MODES + j
                val upper = if (j == 0) 1.0 else configuration.upper * .65.pow(j) * (1 - .55 * thick)
                pickup[k] = upper * if (p == 0) 1.0 else .45 + .45 * channels
            }
            for (runner in 0..1) compliance[runner] = b[runner].sumOf { it * it } * dt
            updateCoefficients(true)
        }

        private fun liquid(enthalpy: Double): Double = ((enthalpy - TRANSITION) / LATENT).coerceIn(0.0, 1.0)
        private fun driveEnvelope(time: Double, runner: Int): Double {
            if (!probe.drive || velocity <= 0.0) return 0.0
            val delay = if (runner == 0) 0.0 else .12 + .10 * thick
            val tt = time - delay
            if (tt < 0) return 0.0
            val travel = if (probe.stopSeconds != null) time else tt
            if (!held && travel >= endDrive + .06) return 0.0
            val rise = min(1.0, tt / (.020 + .045 * thick))
            val release = if (!held && travel > endDrive) (1 - (travel - endDrive) / .06).coerceIn(0.0, 1.0) else 1.0
            return rise * release * if (runner == 0) 1.0 else .28
        }

        private fun thermalStep() {
            lastEvents = 0
            val time = sample * dt
            for (p in 0 until PLATES) lagH[p] = h[p]
            for (p in 0 until PLATES) {
                val packet = transit[p][transitIndex]
                transit[p][transitIndex] = 0.0
                transitTotal[p] -= packet
                channel[p] += packet
                val coldLoss = (.22 + 2.6 * freeze) * channel[p] * controlDt
                val drainage = .06 * channel[p] * controlDt
                val removed = min(channel[p], coldLoss + drainage)
                channel[p] -= removed
                liquidBalance -= removed
                if (probe.thermal) stress[p] += coldLoss * configuration.constraint * (.35 + channels)
            }
            for (p in 0 until PLATES) {
                val oldWater = liquid(h[p])
                val powered = if (p < 2) driveEnvelope(time, p) else 0.0
                // Positive F*slip is dissipated contact work; the heater is explicitly carriage-powered.
                val dissipated = if (workCount > 0) work[p] / workCount else 0.0
                val source = (.35 + 2.8 * heat) * dissipated / .065 +
                    powered * (.06 + 1.40 * heat) * (.40 + .70 * contact) * velocity
                val heldTarget = TRANSITION + LATENT * (.30 + .20 * heat - .08 * freeze)
                // HOLD's powered refrigerated enclosure balances the thin film in the capture
                // region. One-shots retain unrestricted hot, slippery trajectories.
                val refrigeration = if (held && powered > 0.0) 20 * max(0.0, h[p] - heldTarget) else 0.0
                val cooling = (.22 + 2.6 * freeze) * h[p] + refrigeration
                val transfer = .12 * channels * (lagH[(p + 3) % PLATES] + lagH[(p + 1) % PLATES] - 2 * lagH[p])
                if (probe.thermal) h[p] = (h[p] + controlDt * (source - cooling + transfer) / mass).coerceIn(0.0, MAX_ENTHALPY)
                val newWater = liquid(h[p])
                liquidBalance += newWater - oldWater
                if (newWater < oldWater && probe.thermal) {
                    stress[p] += (oldWater - newWater) * configuration.constraint * (.25 + 1.6 * channels)
                }
                water[p] = newWater
                refractory[p] = max(0.0, refractory[p] - controlDt)
                stress[p] = stress[p].coerceIn(0.0, .45)
                val threshold = .055 + .035 * thick
                if (stress[p] >= threshold && refractory[p] == 0.0) {
                    // The norm of this velocity impulse is limited by the stored stress energy.
                    pulses[p] += .0025 * sqrt(stress[p]) * (.3 + .7 * freeze)
                    stress[p] *= .20
                    refractory[p] = .060
                    lastEvents++
                }
                // Constraint can relax silently as well; neither dry coldness nor idle noise creates stress.
                val relaxedSupport = if (held) 15 * driveEnvelope(time, 0) else 0.0
                stress[p] *= exp(-(.45 + relaxedSupport) * controlDt)
            }
            if (probe.channelTransfer && probe.thermal && channels > 0.0) {
                for (p in 0 until PLATES) {
                    val next = (p + 1) % PLATES
                    val room = max(0.0, capacity - channel[p] - transitTotal[p])
                    val desired = min(room, (.35 + 2.4 * channels) * controlDt * (water[p] + water[next]))
                    val sum = water[p] + water[next]
                    if (sum <= 0.0 || desired <= 0.0) continue
                    val amount = min(desired, sum)
                    val fromP = amount * water[p] / sum
                    val fromNext = amount - fromP
                    // A departing warm-liquid packet also carries the zone's sensible surplus.
                    // Bring the remaining thin film to the latent boundary before removing mass:
                    // otherwise saturated liquid(h) would silently refill transferred water.
                    if (fromP > 0.0) h[p] = min(h[p], TRANSITION + LATENT) - LATENT * fromP
                    if (fromNext > 0.0) h[next] = min(h[next], TRANSITION + LATENT) - LATENT * fromNext
                    water[p] = max(0.0, water[p] - fromP)
                    water[next] = max(0.0, water[next] - fromNext)
                    transit[p][transitIndex] += amount
                    transitTotal[p] += amount
                }
            }
            transitIndex = (transitIndex + 1) % transit[0].size
            lastWork = if (workCount > 0) work.sum() / workCount else 0.0
            work.fill(0.0)
            workCount = 0
            updateCoefficients(false)
        }

        private fun updateCoefficients(initial: Boolean) {
            for (p in 0 until PLATES) {
                val wetChannel = .5 * (channel[p] + channel[(p + 3) % PLATES]) / capacity
                val wet = water[p]
                for (j in 0 until MODES) {
                    val k = p * MODES + j
                    // The root has only a few cents of transient load; upper plate spacing carries thickness.
                    val spacing = if (j == 0) 1.0 else 1 + (.045 - .085 * thick) * j
                    val detune = if (j == 0) 1 - .0011 * wet - .0010 * channels * wetChannel else 1 - .018 * wet - .025 * channels * wetChannel
                    val frequency = (hz * roots[p] * ratios[j] * spacing * detune).coerceAtMost(17_000.0)
                    val t60 = configuration.loss * (1.25 + .65 * thick) * .63.pow(j) /
                        (1 + .90 * wet + .90 * channels * wetChannel + .18 * contact + .22 * freeze)
                    loss[k] = 6.90775527898 / t60
                    val radius = exp(-loss[k] * dt)
                    val angle = 2 * PI * frequency * dt
                    targetCr[k] = radius * cos(angle)
                    targetSr[k] = radius * sin(angle)
                    if (initial) { cr[k] = targetCr[k]; sr[k] = targetSr[k] }
                }
            }
            for (j in 0 until BOX_MODES) {
                val k = PLATES * MODES + j
                val frequency = doubleArrayOf(185.0, 410.0, 790.0)[j] * (1.15 - .35 * thick)
                val radius = exp(-6.90775527898 * dt / (.15 + .10 * thick + .06 * j))
                targetCr[k] = radius * cos(2 * PI * frequency * dt)
                targetSr[k] = radius * sin(2 * PI * frequency * dt)
                if (initial) { cr[k] = targetCr[k]; sr[k] = targetSr[k] }
            }
            for (runner in 0..1) {
                val wet = water[runner]
                val warming = (h[runner] / TRANSITION).coerceIn(0.0, 1.0)
                val excess = ((wet - .68) / .32).coerceIn(0.0, 1.0)
                a[runner] = 5000.0 - 1600.0 * wet - 1100.0 * warming
                val driver = .03 * (.60 + .40 * velocity)
                val slope = sqrt(2 * a[runner]) * exp(-a[runner] * driver * driver + .5) *
                    (1 - 2 * a[runner] * driver * driver)
                val couplingLoss = couplingRate(runner)
                val grow = 1 / (.045 + .055 * thick)
                // Passive loss compensation is independent of gesture energy. Velocity changes
                // growth, carriage speed and engagement; a quiet runner must still capture pitch.
                pressure[runner] = (2 * loss[runner * MODES] + 2 * couplingLoss +
                    grow * (.30 + 1.50 * contact) * (.65 + .35 * velocity)) / abs(slope) *
                    (1 - .78 * excess)
                rough[runner] = configuration.texture * (.05 + .45 * contact) *
                    (1 - .92 * wet) * (1 - .30 * warming) * (.80 + .20 * freeze)
            }
            for (p in 0 until PLATES) for (j in 0 until MODES) {
                acousticExchange[p * MODES + j] = .5 * (1 - exp(-2 * couplingRate(p) * (1 + .22 * j) * dt))
            }
            for (p in 0 until PLATES) {
                val wet = (channel[p] / capacity).coerceIn(0.0, 1.0)
                val angle = (1 + 13 * channels) * (1 - .85 * wet) * dt
                bridgeCr[p] = cos(angle)
                bridgeSr[p] = sin(angle)
            }
            sharedExchange = .5 * (1 - exp(-2 * couplingRate(3) * 1.4 * dt))
            val sharedAngle = (2 + 21 * channels) * (1 - .85 * channel[3] / capacity) * dt
            sharedBridgeCr = cos(sharedAngle)
            sharedBridgeSr = sin(sharedAngle)
        }

        private fun couplingRate(p: Int): Double {
            val wet = .5 * (channel[p] + channel[(p + 3) % PLATES]) / capacity
            return (2.0 + 34.0 * channels) * (1 + .65 * wet)
        }

        private fun exchange(i: Int, j: Int, amount: Double) {
            // Equal and opposite exchange leaves common velocity unchanged and contracts its difference.
            val t = amount * (im[j] - im[i])
            im[i] += t
            im[j] -= t
        }

        private fun bridge(i: Int, j: Int, c: Double, s: Double) {
            // A reciprocal reactive bridge is two exact cross-coordinate rotations. Their
            // skew-symmetric generator adds off-diagonal reactance, and preserves the complete
            // modal energy norm for every bridge strength. Frozen bridges are stiffer; liquid
            // weakens this term while strengthening the separate dissipative exchange.
            val qi = re[i]
            val vj = im[j]
            re[i] = c * qi - s * vj
            im[j] = s * qi + c * vj
            val qj = re[j]
            val vi = im[i]
            re[j] = c * qj - s * vi
            im[i] = s * qj + c * vi
        }

        private fun surface(runner: Int): Double {
            var v = 0.0
            for (j in 0 until MODES) v += b[runner][runner * MODES + j] * im[runner * MODES + j]
            return v
        }

        fun energy(): Double {
            var sum = 0.0
            for (k in 0 until SIZE) sum += .5 * (re[k] * re[k] + im[k] * im[k])
            return sum
        }

        fun step(): Float {
            if ((!held && sample % CTRL == 0L) || sample == 0L) thermalStep()
            val oldRoot = re[0]
            // The closest output frame can be several oversamples beyond a crossing. Retain
            // the two real states around the crossing so cycle comparison can use a common
            // fractional acoustic phase rather than measuring that sample-grid residue.
            val nearCrossing = held && oldRoot < 0.0 && im[0] < 0.0 && oldRoot > 2 * sr[0] * im[0]
            val beforeRe = if (nearCrossing) re.copyOf() else null
            val beforeIm = if (nearCrossing) im.copyOf() else null
            for (k in 0 until SIZE) {
                cr[k] += (targetCr[k] - cr[k]) / CTRL
                sr[k] += (targetSr[k] - sr[k]) / CTRL
                val q = re[k]
                val v = im[k]
                re[k] = cr[k] * q - sr[k] * v
                im[k] = sr[k] * q + cr[k] * v
                if (!re[k].isFinite() || !im[k].isFinite()) {
                    re[k] = 0.0; im[k] = 0.0; recovered++
                }
            }
            for (p in 0 until PLATES) for (j in 0 until MODES) {
                val next = (p + 1) % PLATES
                exchange(p * MODES + j, next * MODES + j, acousticExchange[p * MODES + j])
                bridge(p * MODES + j, next * MODES + j, bridgeCr[p], bridgeSr[p])
            }
            // A shared fourth partial/fourth plate path makes delayed channel answers audible.
            exchange(2, 12, sharedExchange)
            bridge(2, 12, sharedBridgeCr, sharedBridgeSr)
            for (j in 0 until BOX_MODES) exchange(j, PLATES * MODES + j, enclosureExchange)

            val time = sample * dt
            // Seeded microscopic texture modulates contact, never an independently mixed noise layer.
            texture = if (held) 0.0 else roughLow.lp(noise.next(), (900 + 2200 * contact).toFloat()).toDouble()
            lastForce = 0.0
            lastSpeed = 0.0
            for (runner in 0..1) {
                val env = driveEnvelope(time, runner)
                val speed = .03 * (.60 + .40 * velocity) * min(1.0, max(0.0, (time - if (runner == 0) 0.0 else .12 + .10 * thick) / .040)) * env
                val p = if (probe.friction) pressure[runner] * env * (1 + rough[runner] * texture).coerceAtLeast(.25) else 0.0
                val force = friction[runner].force(speed, surface(runner), p,
                    compliance[runner], a[runner])
                var impulse = 0.0
                val delay = if (runner == 0) 0.0 else .12 + .10 * thick
                val tt = time - delay
                val strikeTime = .0007 + .0032 * (1 - contact) + .0010 * thick
                if (probe.drive && tt >= 0.0 && tt < strikeTime) {
                    impulse = (.015 + .040 * contact) * velocity / mass *
                        PI / (2 * strikeTime) * sin(PI * tt / strikeTime) * if (runner == 0) 1.0 else .18
                }
                for (j in 0 until MODES) im[runner * MODES + j] += b[runner][runner * MODES + j] * dt * (force + impulse)
                work[runner] += max(0.0, force * friction[runner].slip)
                lastForce += force
                lastSpeed = max(lastSpeed, speed)
            }
            for (p in 0 until PLATES) if (pulses[p] > 0.0) {
                for (j in 0 until MODES) im[p * MODES + j] += pulses[p] * .5.pow(j)
                im[PLATES * MODES] += pulses[p] * .20
                pulses[p] = 0.0
            }
            workCount++
            if (oldRoot < 0.0 && re[0] >= 0.0 && im[0] < 0.0) {
                rootCrossings++
                if (held) {
                    val fraction = (-oldRoot / (re[0] - oldRoot)).coerceIn(0.0, 1.0)
                    for (k in 0 until SIZE) {
                        crossingAcoustic[k] = if (beforeRe == null) re[k] else beforeRe[k] + fraction * (re[k] - beforeRe[k])
                        crossingAcoustic[SIZE + k] = if (beforeIm == null) im[k] else beforeIm[k] + fraction * (im[k] - beforeIm[k])
                    }
                }
            }
            if (held) {
                // A balanced held instrument is autonomous. Its slow clock follows the plate's
                // four phase quadrants, rather than introducing an unrelated 1 kHz period that
                // cannot return at a note-cycle boundary. Elapsed time remains the actual dt.
                val nextQuarter = if (re[0] >= 0.0) { if (im[0] >= 0.0) 0 else 3 }
                    else { if (im[0] >= 0.0) 1 else 2 }
                if (nextQuarter != quarter) {
                    controlDt = max(dt, (sample + 1 - lastControlSample) * dt)
                    thermalStep()
                    lastControlSample = sample + 1
                    quarter = nextQuarter
                }
            }
            direct = 0.0; neighbors = 0.0; enclosure = 0.0
            for (k in 0 until PLATES * MODES) {
                if (k < MODES) direct += pickup[k] * im[k] else neighbors += pickup[k] * im[k]
            }
            for (j in 0 until BOX_MODES) enclosure += im[PLATES * MODES + j] * configuration.body * .60.pow(j)
            sample++
            if (sample % CTRL == 0L) maxEnergy = max(maxEnergy, energy())
            return (direct + neighbors + enclosure).toFloat()
        }

        fun state(): DoubleArray {
            val out = ArrayList<Double>()
            for (arr in listOf(re, im, h, water, channel, stress, refractory, pulses, cr, sr, targetCr, targetSr, work)) for (x in arr) out.add(x)
            for (r in friction) out.add(r.slip)
            out.add(texture)
            for (p in 0 until PLATES) for (i in transit[p].indices) out.add(transit[p][(transitIndex + i) % transit[p].size])
            for (x in transitTotal) out.add(x)
            out.add(workCount * dt)
            out.add(if (held) (sample - lastControlSample) * dt else (sample % CTRL) * dt)
            out.add(quarter.toDouble())
            return out.toDoubleArray()
        }

        fun comparisonGroups(): List<DoubleArray> = listOf(
            if (held) crossingAcoustic.copyOf() else re + im,
            h.copyOf(),
            water.copyOf(),
            channel.copyOf(),
            stress.copyOf() + refractory + pulses,
            DoubleArray(2) { friction[it].slip } + doubleArrayOf(texture),
            cr + sr + targetCr + targetSr,
            DoubleArray(PLATES * 64) { k -> transit[k / 64][(transitIndex + k % 64) % 64] } + transitTotal,
            work + doubleArrayOf(workCount * dt, (sample - lastControlSample) * dt, quarter.toDouble()),
        )

        /** Retain a candidate cut without retaining or resetting the subsequently discarded future. */
        fun checkpoint(): () -> Unit {
            val arrays = listOf(re, im, crossingAcoustic, cr, sr, targetCr, targetSr, h, water, channel, stress,
                refractory, pulses, transitTotal, lagH, loss, a, pressure, rough, acousticExchange,
                bridgeCr, bridgeSr, work) + transit.toList()
            val copies = arrays.map { it.copyOf() }
            val slips = DoubleArray(2) { friction[it].slip }
            val scalar = doubleArrayOf(controlDt, texture, lastWork, lastForce, lastSpeed,
                liquidBalance, maxEnergy, sharedExchange, sharedBridgeCr, sharedBridgeSr,
                direct, neighbors, enclosure)
            val times = longArrayOf(sample, lastControlSample, rootCrossings)
            val counters = intArrayOf(quarter, workCount, transitIndex, lastEvents, recovered)
            return {
                for (i in arrays.indices) copies[i].copyInto(arrays[i])
                for (i in 0..1) friction[i].slip = slips[i]
                controlDt = scalar[0]; texture = scalar[1]; lastWork = scalar[2]
                lastForce = scalar[3]; lastSpeed = scalar[4]; liquidBalance = scalar[5]
                maxEnergy = scalar[6]; sharedExchange = scalar[7]
                sharedBridgeCr = scalar[8]; sharedBridgeSr = scalar[9]
                direct = scalar[10]; neighbors = scalar[11]; enclosure = scalar[12]
                sample = times[0]; lastControlSample = times[1]; rootCrossings = times[2]
                quarter = counters[0]; workCount = counters[1]; transitIndex = counters[2]
                lastEvents = counters[3]; recovered = counters[4]
            }
        }

        fun record(trace: Trace, frame: Int) {
            trace.runnerVelocity[frame] = lastSpeed.toFloat()
            trace.contactForce[frame] = lastForce.toFloat()
            trace.contactWork[frame] = lastWork.toFloat()
            trace.events[frame] = lastEvents.toFloat()
            trace.energy[frame] = energy().toFloat()
            var total = 0.0
            for (p in 0 until PLATES) {
                trace.temperature[p][frame] = when {
                    h[p] < TRANSITION -> (h[p] / TRANSITION - 1).toFloat()
                    h[p] <= TRANSITION + LATENT -> 0f
                    else -> ((h[p] - TRANSITION - LATENT) / .30).toFloat()
                }
                trace.liquid[p][frame] = water[p].toFloat()
                trace.channel[p][frame] = channel[p].toFloat()
                trace.stress[p][frame] = stress[p].toFloat()
                total += water[p] + channel[p] + transitTotal[p]
            }
            trace.liquidTotal[frame] = total.toFloat()
            trace.liquidBalance[frame] = liquidBalance.toFloat()
        }
    }

    internal fun play(voice: ThawVoice, macros: Map<String, Float>, probe: Probe = Probe()): Played {
        val m = settled(macros, voice)
        val engine = Engine(voice, m, probe)
        val frames = probe.durationSeconds?.let { (finiteDuration(it) * RATE).toInt() }
            ?: renderFrames(voice, m) * Dsp.OVERSAMPLE
        val raw = FloatArray(frames)
        val direct = if (probe.record) FloatArray(frames) else null
        val neighbors = if (probe.record) FloatArray(frames) else null
        val enclosure = if (probe.record) FloatArray(frames) else null
        val trace = if (probe.record) Trace(RATE / CTRL, (frames + CTRL - 1) / CTRL) else null
        for (i in 0 until frames) {
            if (i % 8192 == 0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("THAW render no longer wanted")
            raw[i] = engine.step()
            direct?.set(i, engine.direct.toFloat())
            neighbors?.set(i, engine.neighbors.toFloat())
            enclosure?.set(i, engine.enclosure.toFloat())
            if (i % CTRL == 0) trace?.let { engine.record(it, i / CTRL) }
        }
        return Played(raw, direct, neighbors, enclosure, trace, engine.state(), engine.maxEnergy, engine.recovered)
    }

    private fun finiteDuration(seconds: Float): Float = if (seconds.isFinite()) seconds.coerceIn(.02f, 20f) else 6f

    internal fun finish(raw: FloatArray, normalize: Boolean = true, loop: Boolean = false): FloatArray {
        val filters = Array(4) { Dsp.OnePole(RATE) }
        val band = FloatArray(raw.size)
        for (i in raw.indices) {
            var x = raw[i]
            for (f in filters) x = f.lp(x, 12_000f)
            band[i] = x
        }
        val out = Dsp.decimate(band, Dsp.RATE)
        var mean = 0.0
        for (v in out) mean += v
        val dc = (mean / max(1, out.size)).toFloat()
        val hp = Dsp.OnePole(Dsp.RATE)
        for (i in out.indices) {
            val x = out[i] - dc
            out[i] = if (loop) x else x - hp.lp(x, 12f)
        }
        if (normalize) Dsp.levelTo(out, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        if (!loop) Dsp.fadeTail(out)
        return out
    }

    internal class LoopRender(
        val samples: FloatArray, val seam: Double, val stateError: Double, val cycles: Int,
        val converged: Boolean, val groupErrors: Map<String, Double>, val rawRms: Double,
    )

    private val STATE_GROUPS = listOf("acoustic", "enthalpy", "liquid", "channels", "stress", "contact", "coefficients", "transit", "clock")

    private fun stateError(a: List<DoubleArray>, b: List<DoubleArray>): Double {
        return stateErrors(a, b).values.maxOrNull() ?: 0.0
    }

    private fun stateErrors(a: List<DoubleArray>, b: List<DoubleArray>): Map<String, Double> {
        val errors = linkedMapOf<String, Double>()
        for (g in a.indices) {
            var err = 0.0
            var norm = 0.0
            for (i in a[g].indices) {
                err += (a[g][i] - b[g][i]).pow(2)
                norm += .5 * (a[g][i] * a[g][i] + b[g][i] * b[g][i])
            }
            // Modal energy is checked relative to its own norm, independently of the much
            // larger near-unity coefficient states. Small normalized material quantities use
            // a .01-unit floor so a dry layer's roundoff is not treated as infinite error.
            val floor = if (g == 0) 1e-14 else 1e-4
            errors[STATE_GROUPS[g]] = sqrt(err / max(floor, norm))
        }
        return errors
    }

    /** Bounded full-state preroll, then a cycle cut at the dominant plate's own phase crossing. */
    internal fun renderLoopMeasured(
        voice: ThawVoice, macros: Map<String, Float>, velocity: Float = 1f, normalize: Boolean = true,
    ): LoopRender {
        val m = settled(macros, voice)
        val engine = Engine(voice, m, Probe(velocity = velocity), held = true)
        val periods = max(16, (engine.hz * 1.8).toInt())
        var previousState: List<DoubleArray>? = null
        var previousRaw = FloatArray(0)
        var best = FloatArray(0)
        var err = Double.POSITIVE_INFINITY
        var seam = Double.POSITIVE_INFINITY
        var cycles = 0
        var groupErrors = emptyMap<String, Double>()
        for (cycle in 1..10) {
            val start = engine.rootCrossings
            val data = FloatArray((2.8 * RATE).toInt())
            var length = 0
            var checkedCrossing = start
            var candidates = 0
            var bestLength = 0
            var bestError = Double.POSITIVE_INFINITY
            var restoreBest: (() -> Unit)? = null
            while (length < data.size) {
                if (length % 8192 == 0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("THAW loop no longer wanted")
                data[length++] = engine.step()
                if (engine.rootCrossings - start >= periods && length % Dsp.OVERSAMPLE == 0 &&
                    engine.rootCrossings != checkedCrossing) {
                    checkedCrossing = engine.rootCrossings
                    candidates++
                    val candidate = engine.comparisonGroups()
                    val previous = previousState
                    if (previous == null) break
                    val error = stateError(previous, candidate)
                    if (error < bestError) {
                        bestError = error
                        bestLength = length
                        restoreBest = engine.checkpoint()
                    }
                    if (error < .0008 || candidates >= 64) break
                }
            }
            if (bestLength > 0 && bestLength < length) {
                restoreBest?.invoke()
                length = bestLength
            }
            val raw = data.copyOf(length)
            val state = engine.comparisonGroups()
            if (previousState != null) {
                groupErrors = stateErrors(previousState, state)
                err = groupErrors.values.maxOrNull() ?: 0.0
            }
            if (previousRaw.size >= 1024) {
                val combined = previousRaw.takeLast(4096).toFloatArray() + raw
                val finished = finish(combined, normalize = false, loop = true)
                val prefix = 4096 / Dsp.OVERSAMPLE
                seam = Keys.seamError(finished, prefix)
                best = finished.copyOfRange(prefix, finished.size)
            } else best = finish(raw, normalize = false, loop = true)
            previousState = state
            previousRaw = raw
            cycles = cycle
            if (cycle >= 4 && err < 1e-3 && seam < 1e-3) break
        }
        val rawRms = sqrt(best.sumOf { it.toDouble() * it } / max(1, best.size))
        val converged = err < 1e-3 && seam < 1e-3 && rawRms > 1e-5
        if (normalize) Dsp.levelTo(best, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        return LoopRender(best, seam, err, cycles, converged, groupErrors, rawRms)
    }

    fun render(voice: ThawVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        val m = settled(macros, voice)
        val out = if (isLoop(m.getValue("HOLD"))) {
            val loop = renderLoopMeasured(voice, m, velocity)
            check(loop.converged) { "THAW $voice held material did not converge: state=${loop.stateError}, seam=${loop.seam}" }
            loop.samples
        } else finish(play(voice, m, Probe(velocity = velocity)).raw)
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }
}
