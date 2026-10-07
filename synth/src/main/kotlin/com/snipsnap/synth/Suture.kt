package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class SutureVoice { BLOOM, THREAD, CLOSE, MURMUR, STRAIN, SHELL }

/**
 * A reduced bronze vessel, in musical rather than measured physical units.
 * Four plates, four elastic links, their wooden mounts and a four-mode cavity
 * share acoustic coordinates (omega*q, velocity). Exact damped rotations and
 * reciprocal bridges preserve or decrease the complete acoustic energy norm.
 * Retuning those normalized coordinates does not create a stiffness impulse.
 *
 * Two finite-force opening coordinates run near 2 kHz. Acoustic energy can
 * increase their resistance to closing, but that resistance is a dashpot: it
 * can only oppose closing travel. Friction receives explicit carriage work;
 * its equal/opposite acoustic reaction and dissipated work are accounted for.
 * Seam compliance rotates a relative coordinate and then contracts velocity.
 * There is no audio limiter or recovery reset inside the coupled network.
 *
 * HOLD supplies periodic mechanical travel to this same system. The host has
 * a loop-only buffer, so HOLD exports the settled powered cycle and omits the
 * initial note-on opening. Full acoustic and mechanical states are prerolled
 * without resetting them at the loop boundary.
 */
object Suture {
    const val MODEL_VERSION = 1
    const val ROOT_MIDI = 48
    const val TUNE_SEMITONES = 24
    const val MIDI_MIN = ROOT_MIDI
    const val MIDI_MAX = ROOT_MIDI + TUNE_SEMITONES
    const val DEFAULT_MIDI = 60
    private const val PLATES = 4
    private const val MODES = 4
    private const val CORDS = 4
    private const val CORD_MODES = 3
    private const val WOOD = PLATES * MODES + CORDS * CORD_MODES
    private const val CAVITY = WOOD + CORDS
    private const val SIZE = CAVITY + 4
    private const val RATE = Dsp.RATE * Dsp.OVERSAMPLE
    // The material/contact path converges at this 2 kHz reference against 4 and
    // 8 kHz mechanical probes, independently of the audio oversampling check.
    internal const val CONTROL_STRIDE = 88
    private const val CTRL = CONTROL_STRIDE

    private class Shape(
        val defaults: FloatArray, val decay: Double, val upper: Double,
        val mass: Double, val elasticity: Double, val roughness: Double,
        val body: Double, val ratios: DoubleArray,
    )

    private fun shape(voice: SutureVoice): Shape = when (voice) {
        SutureVoice.BLOOM -> Shape(floatArrayOf(.75f, .30f, .30f, .15f, .55f), 1.25, .85, 1.20, .80, .75, .85,
            doubleArrayOf(1.0, 2.60, 4.50, 6.80))
        SutureVoice.THREAD -> Shape(floatArrayOf(.50f, .60f, .75f, .20f, .40f), .94, .60, .90, 1.20, 1.25, .70,
            doubleArrayOf(1.0, 2.52, 4.38, 6.64))
        SutureVoice.CLOSE -> Shape(floatArrayOf(.60f, .65f, .40f, .30f, .65f), 1.03, .70, .85, .95, .85, 1.10,
            doubleArrayOf(1.0, 2.57, 4.42, 6.72))
        SutureVoice.MURMUR -> Shape(floatArrayOf(.35f, .50f, .45f, .70f, .65f), 1.14, .43, 1.00, .90, .95, 1.15,
            doubleArrayOf(1.0, 2.61, 4.53, 6.79))
        SutureVoice.STRAIN -> Shape(floatArrayOf(.70f, .75f, .85f, .50f, .50f), .97, .90, 1.25, 1.40, 1.60, .80,
            doubleArrayOf(1.0, 2.63, 4.57, 6.86))
        SutureVoice.SHELL -> Shape(floatArrayOf(.45f, .40f, .35f, .25f, .85f), 1.30, .40, 1.12, .75, .60, 1.35,
            doubleArrayOf(1.0, 2.56, 4.43, 6.67))
    }

    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: SutureVoice): Int = ROOT_MIDI
    fun midiFor(voice: SutureVoice, tune: Float): Int = rootMidi(voice) + Math.round(finite(tune, .5f) * TUNE_SEMITONES)
    fun frequencyFor(voice: SutureVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= .999f

    fun macrosFor(voice: SutureVoice): List<MacroSpec> {
        val d = shape(voice).defaults
        return listOf(
            MacroSpec("TUNE", .5f, neutral = .5f),
            MacroSpec("GAP", d[0], neutral = .40f),
            MacroSpec("STITCH", d[1], neutral = .40f),
            MacroSpec("CORD", d[2], neutral = .35f),
            MacroSpec("SEAM", d[3], neutral = 0f),
            MacroSpec("CAVITY", d[4], neutral = .40f),
            MacroSpec("HOLD", 0f, neutral = 0f),
        )
    }

    fun defaults(voice: SutureVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    private fun finite(value: Float, fallback: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
    internal fun settled(macros: Map<String, Float>, voice: SutureVoice): Map<String, Float> =
        defaults(voice).mapValues { (name, value) -> finite(macros[name] ?: value, value) }

    fun scramble(voice: SutureVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val d = defaults(voice)
        val seed = if (near == null) d else d + near.macros.filterKeys { it in d }
        return Dsp.scrambleNear(seed, finite(temperature, .35f), random).toMutableMap().also {
            it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f)
        }
    }

    internal fun renderFrames(voice: SutureVoice, macros: Map<String, Float>): Int {
        val m = settled(macros, voice)
        return ((2.90 + 1.05 * m.getValue("GAP") + .85 * m.getValue("CAVITY") +
            .65 * m.getValue("HOLD").coerceAtMost(.998f)) * Dsp.RATE).toInt().coerceAtMost(6 * Dsp.RATE)
    }

    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: SutureVoice,
                     @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap()): DrumClass = DrumClass.LOOP

    internal class Probe(
        val record: Boolean = false,
        val drive: Boolean = true,
        val gesture: Boolean = true,
        val cordMotion: Boolean = true,
        val seam: Boolean = true,
        val coupling: Boolean = true,
        val resistance: Boolean = true,
        val durationSeconds: Float? = null,
        val velocity: Float = 1f,
        val stopSeconds: Float? = null,
        val initialEnergy: Double = 0.0,
        val controlStride: Int = CTRL,
        val oversample: Int = Dsp.OVERSAMPLE,
    )

    internal class Trace(val rate: Int, val frames: Int) {
        val gap = Array(2) { FloatArray(frames) }
        val gapVelocity = Array(2) { FloatArray(frames) }
        val tension = Array(CORDS) { FloatArray(frames) }
        val travelSpeed = Array(CORDS) { FloatArray(frames) }
        val slipEvents = FloatArray(frames)
        val frictionWork = FloatArray(frames)
        val seamActivity = FloatArray(frames)
        val seamLoss = FloatArray(frames)
        val resistance = FloatArray(frames)
        val actuatorForce = FloatArray(frames)
        val actuatorWork = FloatArray(frames)
        val poweredAcousticWork = FloatArray(frames)
        /** Acoustic work debited from finite note-on opening energy. */
        val gestureAcousticWork = FloatArray(frames)
        /** Acoustic work debited from the stitcher's delivered mechanical work. */
        val actuatorAcousticWork = FloatArray(frames)
        val storedMechanicalWork = FloatArray(frames)
        /** Measured carriage work still being delivered across this control interval. */
        val pendingMechanicalWork = FloatArray(frames)
        val energy = FloatArray(frames)
        val passiveLoss = FloatArray(frames)
    }

    internal class Played(
        val raw: FloatArray, val plates: FloatArray?, val cords: FloatArray?,
        val eyelets: FloatArray?, val cavity: FloatArray?, val seam: FloatArray?,
        val trace: Trace?, val finalState: DoubleArray, val maxEnergy: Double,
        val recoveredStates: Int, val closureSeconds: Double?,
        val internalSampleRate: Int = RATE,
    )

    private class Engine(val voice: SutureVoice, val m: Map<String, Float>, val probe: Probe, val held: Boolean = false,
                         val periodFrames: Int = 0) {
        val configuration = shape(voice)
        val gapMacro = m.getValue("GAP").toDouble()
        val stitch = m.getValue("STITCH").toDouble()
        val cord = m.getValue("CORD").toDouble()
        val seamMacro = if (probe.seam) m.getValue("SEAM").toDouble() else 0.0
        val cavityMacro = m.getValue("CAVITY").toDouble()
        val velocity = finite(probe.velocity, 1f).toDouble()
        val heldTravelScale = .35 + .65 * velocity
        val heldForceScale = .45 + .55 * velocity
        // Start settled loops late in the opening stroke, where the driven bronze
        // carries a clear tonal center. The same phase moves carriage and take-up;
        // choosing this full-cycle cut creates no attack or boundary event.
        val heldPhaseOffset = if (held) 3 * PI / 4 else 0.0
        val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val internalRate = Dsp.RATE * probe.oversample
        val dt = 1.0 / internalRate
        val workStoreDecay = exp(-.8 * dt)
        val stride = probe.controlStride.coerceIn(22, 352) * probe.oversample / Dsp.OVERSAMPLE
        val controlDt = stride * dt
        val reactionSmoothing = 1 - exp(-50 * controlDt)
        val opening = .10 + .70 * gapMacro
        val spreadSeconds = .10 + .16 * gapMacro
        val driveHorizon = probe.stopSeconds?.takeIf { it.isFinite() }?.toDouble()?.coerceIn(0.0, 20.0)
            ?: (1.1 + 1.4 * gapMacro + .8 * (1 - stitch) + 1.2 * m.getValue("HOLD").coerceAtMost(.998f))
        val re = DoubleArray(SIZE)
        val im = DoubleArray(SIZE)
        val cr = DoubleArray(SIZE)
        val sr = DoubleArray(SIZE)
        val targetCr = DoubleArray(SIZE)
        val targetSr = DoubleArray(SIZE)
        val pickup = DoubleArray(SIZE)
        val gaps = DoubleArray(2)
        val speeds = DoubleArray(2)
        val strain = DoubleArray(CORDS)
        val travelSpeed = DoubleArray(CORDS)
        val surfacePosition = DoubleArray(CORDS)
        val takeUp = DoubleArray(CORDS)
        val acousticGap = DoubleArray(2)
        val acousticStrain = DoubleArray(CORDS)
        val acousticPosition = DoubleArray(CORDS)
        val acousticTravel = DoubleArray(CORDS)
        val phase = DoubleArray(CORDS)
        val previousSlip = DoubleArray(CORDS)
        val carriageActive = BooleanArray(2)
        val frictionReaction = DoubleArray(CORDS)
        val frictionImpulse = DoubleArray(CORDS)
        val exchange = DoubleArray(CORDS)
        val bodyExchange = DoubleArray(4)
        val plateBridgeC = DoubleArray(MODES)
        val plateBridgeS = DoubleArray(MODES)
        val cavityBridgeC = DoubleArray(4)
        val cavityBridgeS = DoubleArray(4)
        val cordBridgeC = DoubleArray(CORDS)
        val cordBridgeS = DoubleArray(CORDS)
        var sample = 0L
        var plates = 0.0
        var cords = 0.0
        var eyelets = 0.0
        var cavity = 0.0
        var seam = 0.0
        var poweredWork = 0.0
        var gestureAcousticWork = 0.0
        var actuatorAcousticWork = 0.0
        var storedGestureWork = 0.0
        var storedActuatorWork = 0.0
        var pendingGestureWork = 0.0
        var pendingActuatorWork = 0.0
        var passiveLoss = 0.0
        var actuatorWork = 0.0
        var gestureMechanicalWork = 0.0
        var frictionWork = 0.0
        var seamLoss = 0.0
        var seamActivity = 0.0
        var slipEvents = 0
        var resistance = 0.0
        var actuatorForce = 0.0
        var closureSeconds: Double? = null
        var maxEnergy = 0.0
        var gestureBudget = if (probe.gesture) .035 * (.35 + .65 * gapMacro) * velocity * velocity else 0.0
        val gestureWeights = DoubleArray(PLATES * MODES)

        init {
            require(probe.oversample == 4 || probe.oversample == 8) { "SUTURE diagnostic oversample must be 4 or 8" }
            val random = Random(Dsp.seedFor("SUTURE", voice, midiFor(voice, m.getValue("TUNE")), "mounts"))
            for (p in 0 until PLATES) for (j in 0 until MODES) {
                val k = p * MODES + j
                val upper = if (j == 0) 1.0 else configuration.upper * .60.pow(j - 1)
                pickup[k] = upper * if (p == 0) 1.0 else (if (j == 0) .48 else .18) + .06 * random.nextDouble()
                gestureWeights[k] = (if (p == 0) 1.0 else if (j == 0) .50 else .26) *
                    (if (j == 0) 1.0 else upper * .85) * (if ((p + j) % 3 == 2) -1 else 1)
            }
            val norm = sqrt(gestureWeights.sumOf { it * it })
            for (k in gestureWeights.indices) gestureWeights[k] /= norm
            for (c in 0 until CORDS) {
                phase[c] = random.nextDouble() * 2 * PI
                strain[c] = .10 + .26 * cord
                acousticStrain[c] = strain[c]
                surfacePosition[c] = .035 * strain[c]
                acousticPosition[c] = surfacePosition[c]
                for (j in 0 until CORD_MODES) pickup[PLATES * MODES + c * CORD_MODES + j] =
                    (.08 + .29 * cord) * .55.pow(j)
                pickup[WOOD + c] = .18 + .35 * cord
            }
            for (j in 0 until 4) pickup[CAVITY + j] = (.08 + .25 * cavityMacro) * .66.pow(j)
            val initial = if (probe.initialEnergy.isFinite()) probe.initialEnergy.coerceIn(0.0, .25) else 0.0
            im[0] = sqrt(2 * initial)
            maxEnergy = initial
            updateCoefficients(true)
        }

        fun energy(): Double {
            var result = 0.0
            for (k in 0 until SIZE) result += .5 * (re[k] * re[k] + im[k] * im[k])
            return result
        }

        private fun mechanicalStep() {
            val time = sample * dt
            resistance = 0.0
            actuatorForce = 0.0
            // Project completed contact impulses onto the linkage's 20 ms force
            // envelope. The interval mean alone still passes audio vibration into
            // slow gap motion; this positive envelope remains a resisting load.
            for (c in 0 until CORDS) {
                frictionReaction[c] +=
                    (frictionImpulse[c] / controlDt - frictionReaction[c]) * reactionSmoothing
                frictionImpulse[c] = 0.0
            }
            val ring = energy()
            val mass = .075 * configuration.mass
            for (g in 0..1) {
                val active = probe.drive && velocity > 0 && (held || time < driveHorizon) &&
                    (held || time >= spreadSeconds && (closureSeconds == null || gaps[g] > .002))
                carriageActive[g] = active
                val phaseTime = if (held) (sample % periodFrames) * 2 * PI / periodFrames + heldPhaseOffset else 0.0
                val target = if (held) heldTravelScale * opening * (.50 + .43 * sin(phaseTime - PI / 2)) *
                    (if (g == 0) 1.0 else .92) else 0.0
                val targetSpeed = if (held) heldTravelScale * opening * .43 * cos(phaseTime - PI / 2) *
                    2 * PI * internalRate / periodFrames * (if (g == 0) 1.0 else .92) else -(.16 + 1.7 * stitch)
                val requested = if (held) (10 + 36 * stitch) * (target - gaps[g]) +
                    (2.5 + 8 * stitch) * (targetSpeed - speeds[g]) else
                    (1.0 + 6 * stitch) * (targetSpeed - speeds[g])
                val forceLimit = (.7 + 6 * stitch) * if (held) heldForceScale else 1.0
                val driveForce = if (active) requested.coerceIn(-forceLimit, forceLimit) else 0.0
                val spreadTarget = opening * (if (g == 0) 1.0 else .92)
                val spread = if (!held && probe.gesture && velocity > 0 && time < spreadSeconds)
                    (700 * (spreadTarget - gaps[g]) - 5 * speeds[g]).coerceIn(-20.0, 20.0) else 0.0
                // Ringing changes a bounded closing dashpot. It never pushes the gaps open.
                val resistanceCoefficient = if (probe.resistance && speeds[g] < 0) min(24.0, 650 * ring) else 0.0
                val baseDamping = .55 + .35 * (1 - stitch)
                val roughCatch = if (probe.cordMotion) min(5.0, .25 +
                    16 * (frictionReaction[g] + frictionReaction[g + 2])) else 0.0
                val elasticReaction = if (probe.cordMotion) (.15 + .45 * cord) *
                    max(0.0, strain[g] + strain[g + 2] - 2 * (.10 + .26 * cord)) else 0.0
                val spring = .70 * gaps[g] + .18 * (gaps[g] - gaps[1 - g]) + elasticReaction
                // Backward-Euler dashpots remain passive for any diagnostic control stride.
                val newSpeed = ((speeds[g] + controlDt * (spread + driveForce - spring) / mass) /
                    (1 + controlDt * (baseDamping + roughCatch + resistanceCoefficient) / mass)).coerceIn(-6.0, 6.0)
                val nextGap = gaps[g] + controlDt * newSpeed
                speeds[g] = if (nextGap < 0 || nextGap > 1) 0.0 else newSpeed
                gaps[g] = nextGap.coerceIn(0.0, 1.0)
                val stitchWork = max(0.0, driveForce * speeds[g] * controlDt)
                val openingWork = max(0.0, spread * speeds[g] * controlDt)
                actuatorWork += stitchWork
                gestureMechanicalWork += openingWork
                // Finite delivered mechanical work, in this reduced model's energy
                // units. Unused work can relax into the linkage; it is never an
                // unlimited audio source or a passive unity-feedback sustain.
                // Deliver this interval's measured work at audio rate. Posting the
                // complete allowance at a control tick can turn a starved contact
                // into a clocked impulse train even when its carriage travels smoothly.
                pendingActuatorWork += stitchWork
                pendingGestureWork += openingWork
                resistance += abs(resistanceCoefficient * speeds[g])
                actuatorForce += abs(driveForce)
            }
            if (!held && time > spreadSeconds && closureSeconds == null && gaps.all { it < .003 }) closureSeconds = time
            for (c in 0 until CORDS) {
                val g = c % 2
                val preload = .10 + .26 * cord
                val targetStrain = preload + configuration.elasticity * (.25 + .45 * cord) * gaps[g]
                strain[c] += (targetStrain.coerceIn(0.0, 1.2) - strain[c]) * (1 - exp(-controlDt * (25 + 45 * stitch)))
                if (carriageActive[g] && probe.cordMotion) {
                    val takeUpTarget = if (held) heldTravelScale * .012 * (1 + 3 * stitch) * sin(2 * PI * (sample % periodFrames) / periodFrames + heldPhaseOffset) else
                        -.012 * (1 + 3 * stitch) * (1 - exp(-4 * max(0.0, time - spreadSeconds)))
                    takeUp[c] += (takeUpTarget - takeUp[c]) * (1 - exp(-30 * controlDt))
                }
                // Surface coordinates are geometry, not a free-running texture phase. A
                // returning powered cycle revisits the same seeded grain in each eyelet.
                val nextPosition = gaps[g] * (.24 + .24 * cord) + .035 * strain[c] + takeUp[c]
                travelSpeed[c] = if (probe.cordMotion) (nextPosition - surfacePosition[c]) / controlDt else 0.0
                surfacePosition[c] = nextPosition
            }
            updateCoefficients(false)
        }

        private fun coefficient(k: Int, frequency: Double, t60: Double, initial: Boolean) {
            val angle = 2 * PI * frequency.coerceIn(20.0, 17_000.0) * dt
            val r = exp(-6.90775527898 * dt / max(.018, t60))
            targetCr[k] = r * cos(angle)
            targetSr[k] = r * sin(angle)
            if (initial) { cr[k] = targetCr[k]; sr[k] = targetSr[k] }
        }

        private fun updateCoefficients(initial: Boolean) {
            val aperture = .5 * (gaps[0] + gaps[1])
            for (p in 0 until PLATES) for (j in 0 until MODES) {
                val k = p * MODES + j
                // Root loading: at most 5.2 cents at ordinary geometry, 8.6 at full
                // opening. Answering plates carry stronger mounting movement.
                val detune = if (j == 0) 1 + .004 * gaps[p % 2] else 1 + .018 * gaps[p % 2] * j
                val plateRatio = if (p == 0) 1.0 else (p + 1).toDouble()
                val radiation = 1 + (.7 + .8 * cavityMacro) * aperture
                val t60 = if (p == 0 && j == 0)
                    (3.60 + 2.0 * cavityMacro) * (.85 + .20 * configuration.decay) / (1 + .80 * aperture)
                    else if (j == 0) configuration.decay * (1.60 + 1.10 * cavityMacro) / radiation
                    else configuration.decay * (.82 + .55 * cavityMacro) * .75.pow(j) / radiation
                coefficient(k, hz * plateRatio * configuration.ratios[j] * detune, t60, initial)
            }
            for (c in 0 until CORDS) for (j in 0 until CORD_MODES) {
                val k = PLATES * MODES + c * CORD_MODES + j
                val movement = .10 * max(0.0, strain[c] - (.10 + .26 * cord))
                // Balance the root-bearing reference tension for both finite and
                // held motion. Upper cord modes retain their full elastic stretch.
                val stretch = 1 + if (j == 0) min(.003, movement) else movement
                coefficient(k, hz * (j + 1) * (if (c % 2 == 0) 1.0 else 2.0) * stretch,
                    (.40 + 1.00 * cord) * .73.pow(j), initial)
            }
            for (c in 0 until CORDS) coefficient(WOOD + c,
                (730 + c * 470.0) * (1 + .45 * cord), .028 + .045 * (1 - cord), initial)
            val bodyScale = 1.30 - .65 * cavityMacro
            for (j in 0 until 4) {
                val bodyHz = hz * doubleArrayOf(1.0, 2.05, 3.25, 5.10)[j] * bodyScale * (1 + .16 * aperture)
                coefficient(CAVITY + j, bodyHz, (.16 + .80 * cavityMacro) / (1 + 4 * aperture + .30 * j), initial)
                val rate = (1.0 + 16 * cavityMacro * configuration.body) * (1 - .72 * aperture)
                // A detuned cavity can load upper bending modes strongly without
                // extinguishing the requested root before the stitchers arrive.
                bodyExchange[j] = .5 * (1 - exp(-2 * rate * (if (j == 0) .08 else .18) * dt))
                val bridgeRate = (.5 + 20 * cavityMacro) * (1 - .65 * aperture)
                // A subordinate root footprint bounds the avoided crossing when
                // cavity and bronze nearly coincide. Scaling with the reference
                // pitch preserves that bound through the host's two octaves;
                // upper body modes keep their stronger reciprocal connections.
                val rootFootprint = 2.5 * hz / Keys.midiHz(ROOT_MIDI)
                val angle = (if (j == 0) min(bridgeRate, rootFootprint) else bridgeRate) * dt
                cavityBridgeC[j] = cos(angle)
                cavityBridgeS[j] = sin(angle)
            }
            for (j in 0 until MODES) {
                val angle = (2 + 15 * (1 - aperture)) * (if (j == 0) .18 else .65) * dt
                plateBridgeC[j] = cos(angle)
                plateBridgeS[j] = sin(angle)
            }
            for (c in 0 until CORDS) {
                exchange[c] = .5 * (1 - exp(-2 * (1.0 + 12 * cord) * (.35 + strain[c]) * dt))
                // A small reciprocal elastic bridge transmits powered cord work
                // into bronze without extinguishing its root against a dashpot.
                // The primary bridge's worst-case unloaded split is <2.5 cents
                // at C3; responding plates may carry a larger answering spread.
                val angle = (if (c == 0) .25 + .9 * cord else 1 + 3 * cord) * dt
                cordBridgeC[c] = cos(angle)
                cordBridgeS[c] = sin(angle)
            }
        }

        private fun bridge(i: Int, j: Int, c: Double, s: Double) {
            val qi = re[i]
            val vj = im[j]
            re[i] = c * qi - s * vj
            im[j] = s * qi + c * vj
            val qj = re[j]
            val vi = im[i]
            re[j] = c * qj - s * vi
            im[i] = s * qj + c * vi
        }

        private fun exchange(i: Int, j: Int, amount: Double) {
            val d = im[j] - im[i]
            val impulse = amount * d
            im[i] += impulse
            im[j] -= impulse
            passiveLoss += amount * (1 - amount) * d * d
        }

        private fun release() {
            if (gestureBudget <= 0 || !probe.gesture || held) return
            val t = sample * dt
            val releaseSeconds = .00035
            if (t > releaseSeconds) return
            // A finite, smooth note-on force spends a single explicit acoustic budget.
            val requested = .45 * velocity * sin(PI * (t / releaseSeconds).coerceIn(0.0, 1.0)) * dt / releaseSeconds
            var dot = 0.0
            for (k in gestureWeights.indices) dot += im[k] * gestureWeights[k]
            val allowed = -dot + sqrt(max(0.0, dot * dot + 2 * gestureBudget))
            val impulse = min(requested, allowed)
            val work = impulse * dot + .5 * impulse * impulse
            for (k in gestureWeights.indices) im[k] += impulse * gestureWeights[k]
            if (work >= 0) { gestureBudget -= work; poweredWork += work; gestureAcousticWork += work } else passiveLoss -= work
        }

        private fun friction() {
            for (c in 0 until CORDS) {
                val k = PLATES * MODES + c * CORD_MODES
                val wood = WOOD + c
                val b0 = 1.0
                val b1 = .42
                val b2 = .23
                // Balanced HOLD keeps woody catches subordinate to the tuned
                // links; one-shots retain the broader wooden opening response.
                val bw = if (held) -.20 else -.55
                val contactVelocity = im[k] * b0 + im[k + 1] * b1 + im[k + 2] * b2 + im[wood] * bw
                val travel = acousticTravel[c]
                val materialScale = hz / Keys.midiHz(DEFAULT_MIDI)
                val fineGrain = if (held) 11_696.0 else 28_000.0
                val grain = sin(acousticPosition[c] * (1800 + 2100 * cord) * materialScale + phase[c]) +
                    .32 * sin(acousticPosition[c] * fineGrain * materialScale + 1.7 * phase[c])
                // The driven surface follows actual travel. It is exactly zero when
                // stopped; seeded grain modulates positive contact, never audio noise.
                val closureEngagement = if (held) 1.0 else ((sample * dt - spreadSeconds) / .025).coerceIn(0.0, 1.0)
                val openingTransfer = .05 + 3.45 * closureEngagement * closureEngagement * (3 - 2 * closureEngagement)
                val surface = travel * (.13 + .35 * cord) * (if (held) 2.4 else openingTransfer) * (1 + .56 * cord * grain)
                val slip = surface - contactVelocity
                val normal = .30 + acousticStrain[c]
                val stribeck = .35 + .65 * exp(-(slip / .035).pow(2))
                val rate = (35 + 420 * cord * configuration.roughness) * normal * stribeck *
                    (1 + .24 * cord * grain).coerceAtLeast(.30)
                val norm = b0 * b0 + b1 * b1 + b2 * b2 + bw * bw
                val alpha = rate * dt / (1 + rate * dt * norm)
                var impulse = alpha * slip
                val requestedWork = impulse * surface
                if (requestedWork > 0) {
                    val available = storedGestureWork + storedActuatorWork
                    impulse *= min(1.0, available / requestedWork)
                }
                frictionImpulse[c] += abs(impulse)
                val change = impulse * contactVelocity + .5 * impulse * impulse * norm
                val input = impulse * surface
                val loss = max(0.0, input - change)
                if (input >= 0) {
                    val fromActuator = min(storedActuatorWork, input)
                    val fromGesture = input - fromActuator
                    storedActuatorWork = max(0.0, storedActuatorWork - fromActuator)
                    storedGestureWork = max(0.0, storedGestureWork - fromGesture)
                    actuatorAcousticWork += fromActuator
                    gestureAcousticWork += fromGesture
                    // Backwards-compatible total supplied acoustic work; the two
                    // named source ledgers distinguish powered closure from the
                    // finite stored opening/release contribution.
                    poweredWork += input
                } else passiveLoss -= input
                passiveLoss += loss
                frictionWork += max(0.0, input)
                im[k] += impulse * b0
                im[k + 1] += impulse * b1
                im[k + 2] += impulse * b2
                im[wood] += impulse * bw
                // Count entry into the kinetic part of this same Stribeck law.
                // One-way travel can release a catch without reversing direction;
                // a velocity-zero-crossing counter misses that physical transition.
                if (abs(travel) > .002 && abs(slip) >= .035 && abs(previousSlip[c]) < .035) slipEvents++
                previousSlip[c] = slip
            }
        }

        private fun seamContact() {
            seam = 0.0
            seamActivity = 0.0
            if (seamMacro <= 0.0 || !probe.coupling) return
            for (g in 0..1) {
                val i = g * MODES
                val j = (g + 2) * MODES
                val clearance = .012 + .105 * seamMacro
                val edge = .42 * (re[i] - re[j])
                val penetration = max(0.0, clearance - acousticGap[g] + edge)
                val contact = penetration / (.012 + penetration)
                if (contact == 0.0) continue
                seamActivity += contact
                val q = (re[i] - re[j]) / sqrt(2.0)
                val v = (im[i] - im[j]) / sqrt(2.0)
                val angle = .04 * (12 + 140 * seamMacro) * contact * dt
                val cs = cos(angle)
                val ss = sin(angle)
                val nq = cs * q - ss * v
                val nv = ss * q + cs * v
                re[i] += (nq - q) / sqrt(2.0)
                re[j] -= (nq - q) / sqrt(2.0)
                im[i] += (nv - v) / sqrt(2.0)
                im[j] -= (nv - v) / sqrt(2.0)
                val rough = 1 + .30 * seamMacro * sin(75 * penetration + phase[g])
                val amount = .5 * (1 - exp(-2 * (8 + 155 * seamMacro) * contact * rough * dt))
                val before = passiveLoss
                val oldDifference = im[j] - im[i]
                // The requested-root edge has a small footprint; the answering
                // 2x/4x plate pair carries stronger seam loss and audible chatter.
                val fundamentalProjection = if (g == 0) (if (held) .015 else .10) else .55
                exchange(i, j, fundamentalProjection * amount)
                seam += -.22 * fundamentalProjection * amount * oldDifference * internalRate / 1000
                // The same edge damps upper bending coordinates. Their switching
                // load makes the fine contact buzz without an independent source.
                for (mode in 1 until MODES) exchange(i + mode, j + mode, amount * (.55 + .12 * mode))
                seamLoss += passiveLoss - before
            }
        }

        fun step(): Float {
            if (sample % stride == 0L) mechanicalStep()
            val remaining = stride - (sample % stride).toInt()
            val gestureDelivery = pendingGestureWork / remaining
            val actuatorDelivery = pendingActuatorWork / remaining
            pendingGestureWork = max(0.0, pendingGestureWork - gestureDelivery)
            pendingActuatorWork = max(0.0, pendingActuatorWork - actuatorDelivery)
            // The finite inventory relaxes continuously, including when there is
            // no contact. Delivery never exceeds already measured carriage work.
            storedGestureWork = min(2.0, storedGestureWork * workStoreDecay + gestureDelivery)
            storedActuatorWork = min(2.0, storedActuatorWork * workStoreDecay + actuatorDelivery)
            // Advance between actual interval endpoints. A one-pole position
            // follower has a discontinuous derivative at every control update;
            // that derivative would spuriously drive friction at the clock rate.
            for (g in 0..1) acousticGap[g] += (gaps[g] - acousticGap[g]) / remaining
            for (c in 0 until CORDS) {
                acousticStrain[c] += (strain[c] - acousticStrain[c]) / remaining
                val oldPosition = acousticPosition[c]
                acousticPosition[c] += (surfacePosition[c] - acousticPosition[c]) / remaining
                acousticTravel[c] = if (probe.cordMotion) (acousticPosition[c] - oldPosition) / dt else 0.0
            }
            release()
            for (k in 0 until SIZE) {
                cr[k] += (targetCr[k] - cr[k]) / stride
                sr[k] += (targetSr[k] - sr[k]) / stride
                val q = re[k]
                val v = im[k]
                val nq = cr[k] * q - sr[k] * v
                val nv = sr[k] * q + cr[k] * v
                passiveLoss += max(0.0, .5 * (q * q + v * v - nq * nq - nv * nv))
                re[k] = nq
                im[k] = nv
            }
            if (probe.coupling) {
                for (p in 0 until PLATES) for (j in 0 until MODES) {
                    val i = p * MODES + j
                    val k = ((p + 1) % PLATES) * MODES + j
                    bridge(i, k, plateBridgeC[j], plateBridgeS[j])
                }
                for (c in 0 until CORDS) {
                    val k = PLATES * MODES + c * CORD_MODES
                    bridge(c * MODES, k, cordBridgeC[c], cordBridgeS[c])
                    for (j in 0 until CORD_MODES) exchange(c * MODES + j, k + j,
                        exchange[c] * (if (c == 0 && j == 0) (if (held) 2.25 else .14) else if (j == 0) 1.0 else .12))
                    exchange(k, WOOD + c, exchange[c] * (if (held) .08 else .45))
                }
                for (j in 0 until 4) {
                    bridge(j, CAVITY + j, cavityBridgeC[j], cavityBridgeS[j])
                    exchange(j, CAVITY + j, bodyExchange[j])
                }
            }
            friction()
            seamContact()
            plates = 0.0; cords = 0.0; eyelets = 0.0; cavity = 0.0
            val aperture = .5 * (acousticGap[0] + acousticGap[1])
            // The balanced MURMUR loop uses a quiet edge pickup around its real,
            // mechanically driven primary plate. Its high field still carries
            // contact and chirps, without obscuring the requested tonal center.
            val heldField = if (held && voice == SutureVoice.MURMUR) .24 else 1.0
            for (k in 0 until PLATES * MODES) {
                // Aperture changes the radiation of this same moving bronze,
                // in addition to its modal loading and cavity reaction. Upper
                // bending modes radiate freely while open and soften on closure.
                val radiation = if (k == 0) (if (held) .90 + .10 * aperture else .40 + 2.0 * aperture)
                    else heldField * (.60 + 1.30 * aperture)
                plates += radiation * pickup[k] * im[k]
            }
            for (k in PLATES * MODES until WOOD) cords += pickup[k] * im[k]
            for (k in WOOD until CAVITY) eyelets += pickup[k] * im[k]
            for (k in CAVITY until SIZE) cavity += pickup[k] * im[k]
            cords *= heldField
            eyelets *= heldField
            cavity *= heldField
            seam *= heldField
            val e = energy()
            check(e.isFinite() && e < 2.0) { "SUTURE acoustic energy exceeded its mechanical work bound: $e" }
            maxEnergy = max(maxEnergy, e)
            sample++
            return (plates + cords + eyelets + cavity + seam).toFloat()
        }

        fun state(): DoubleArray = re + im + gaps + speeds + strain + previousSlip + frictionReaction + frictionImpulse + cr + sr + targetCr + targetSr +
            travelSpeed + surfacePosition + takeUp + acousticGap + acousticStrain + acousticPosition + acousticTravel +
            doubleArrayOf(storedGestureWork, storedActuatorWork, pendingGestureWork, pendingActuatorWork)

        fun groups(): List<DoubleArray> = listOf(re + im, gaps + speeds + acousticGap,
            strain + previousSlip + frictionReaction + frictionImpulse + travelSpeed + surfacePosition + takeUp + acousticStrain + acousticPosition + acousticTravel,
            cr + sr + targetCr + targetSr,
            doubleArrayOf(storedGestureWork, storedActuatorWork, pendingGestureWork, pendingActuatorWork))

        fun record(trace: Trace, frame: Int) {
            for (g in 0..1) { trace.gap[g][frame] = gaps[g].toFloat(); trace.gapVelocity[g][frame] = speeds[g].toFloat() }
            for (c in 0 until CORDS) {
                trace.tension[c][frame] = strain[c].toFloat()
                trace.travelSpeed[c][frame] = travelSpeed[c].toFloat()
            }
            trace.slipEvents[frame] = slipEvents.toFloat()
            slipEvents = 0
            trace.frictionWork[frame] = frictionWork.toFloat()
            trace.seamActivity[frame] = seamActivity.toFloat()
            trace.seamLoss[frame] = seamLoss.toFloat()
            trace.resistance[frame] = resistance.toFloat()
            trace.actuatorForce[frame] = actuatorForce.toFloat()
            trace.actuatorWork[frame] = actuatorWork.toFloat()
            trace.poweredAcousticWork[frame] = poweredWork.toFloat()
            trace.gestureAcousticWork[frame] = gestureAcousticWork.toFloat()
            trace.actuatorAcousticWork[frame] = actuatorAcousticWork.toFloat()
            trace.storedMechanicalWork[frame] =
                (storedGestureWork + storedActuatorWork + pendingGestureWork + pendingActuatorWork).toFloat()
            trace.pendingMechanicalWork[frame] = (pendingGestureWork + pendingActuatorWork).toFloat()
            trace.energy[frame] = energy().toFloat()
            trace.passiveLoss[frame] = passiveLoss.toFloat()
        }
    }

    internal fun play(voice: SutureVoice, macros: Map<String, Float>, probe: Probe = Probe()): Played {
        val m = settled(macros, voice)
        val engine = Engine(voice, m, probe)
        val seconds = probe.durationSeconds?.let { if (it.isFinite()) it.coerceIn(.02f, 20f) else 6f }
        val frames = seconds?.let { (it * engine.internalRate).toInt() } ?: renderFrames(voice, m) * probe.oversample
        val raw = FloatArray(frames)
        val branches = if (probe.record) Array(5) { FloatArray(frames) } else null
        val trace = if (probe.record) Trace(engine.internalRate / engine.stride, (frames + engine.stride - 1) / engine.stride) else null
        for (i in raw.indices) {
            if (i % 8192 == 0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("SUTURE render no longer wanted")
            raw[i] = engine.step()
            branches?.let {
                it[0][i] = engine.plates.toFloat(); it[1][i] = engine.cords.toFloat()
                it[2][i] = engine.eyelets.toFloat(); it[3][i] = engine.cavity.toFloat(); it[4][i] = engine.seam.toFloat()
            }
            if (i % engine.stride == 0) trace?.let { engine.record(it, i / engine.stride) }
        }
        return Played(raw, branches?.get(0), branches?.get(1), branches?.get(2), branches?.get(3), branches?.get(4),
            trace, engine.state(), engine.maxEnergy, 0, engine.closureSeconds, engine.internalRate)
    }

    /** Shared band-limited 4x path and melodic loudness targeting; no feedback concealment. */
    internal fun finish(raw: FloatArray, normalize: Boolean = true, loop: Boolean = false,
                        internalSampleRate: Int = RATE): FloatArray {
        require(internalSampleRate == RATE || internalSampleRate == RATE * 2)
        val fourTimes = if (internalSampleRate == RATE) raw else
            Resampler.resample(Snip(raw, channels = 1, sampleRate = internalSampleRate), RATE).samples
        val out = Dsp.decimate(fourTimes, Dsp.RATE)
        var mean = 0.0
        for (x in out) mean += x
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
        val samples: FloatArray, val seam: Double, val stateError: Double,
        val cycles: Int, val converged: Boolean, val rawRms: Double,
        val groupErrors: Map<String, Double>,
        val actuatorWorkPerCycle: Double = 0.0,
        val acousticWorkPerCycle: Double = 0.0,
        val maxEnergy: Double = 0.0,
        /** Actual captured cycle taps after decimation, before loudness matching. */
        val eyelets: FloatArray? = null,
        val cords: FloatArray? = null,
    )

    private fun groupErrors(a: List<DoubleArray>, b: List<DoubleArray>): Map<String, Double> {
        val names = listOf("acoustic", "gaps", "cords", "coefficients", "workStores")
        return a.indices.associate { group ->
            var difference = 0.0
            var norm = 0.0
            for (i in a[group].indices) {
                difference += (a[group][i] - b[group][i]).pow(2)
                norm += .5 * (a[group][i].pow(2) + b[group][i].pow(2))
            }
            names[group] to sqrt(difference / max(if (group == 0) 1e-12 else 1e-6, norm))
        }
    }

    /** Periodic actuator work, bounded full-state preroll, then shared loop seam measurement. */
    internal fun renderLoopMeasured(voice: SutureVoice, macros: Map<String, Float>, velocity: Float = 1f,
                                    normalize: Boolean = true, controlStride: Int = CTRL,
                                    recordBranches: Boolean = false): LoopRender {
        require(controlStride == CTRL || controlStride == CTRL / 2 || controlStride == CTRL / 4) {
            "SUTURE loop diagnostic control stride must be 88, 44 or 22"
        }
        val m = settled(macros, voice)
        val loopFrames = (((1.45 + .85 * m.getValue("GAP")) * Dsp.RATE / (CTRL / Dsp.OVERSAMPLE)).toInt() *
            (CTRL / Dsp.OVERSAMPLE)).coerceAtLeast(Dsp.RATE)
        val period = loopFrames * Dsp.OVERSAMPLE
        if (finite(velocity, 1f) == 0f) return LoopRender(FloatArray(loopFrames), 0.0, 0.0, 0, true, 0.0,
            emptyMap(), 0.0, 0.0, 0.0, if (recordBranches) FloatArray(loopFrames) else null,
            if (recordBranches) FloatArray(loopFrames) else null)
        val engine = Engine(voice, m, Probe(velocity = velocity, controlStride = controlStride), held = true, periodFrames = period)
        var previousState = engine.groups()
        var previousRaw = FloatArray(0)
        var previousEyelets = FloatArray(0)
        var previousCords = FloatArray(0)
        var best = FloatArray(0)
        var bestEyelets: FloatArray? = null
        var bestCords: FloatArray? = null
        var seam = Double.POSITIVE_INFINITY
        var errors = emptyMap<String, Double>()
        var stateError = Double.POSITIVE_INFINITY
        var cycles = 0
        var actuatorWorkPerCycle = 0.0
        var acousticWorkPerCycle = 0.0
        for (cycle in 1..12) {
            val previousActuatorWork = engine.actuatorWork
            val previousAcousticWork = engine.poweredWork
            val raw = FloatArray(period)
            val eyelets = if (recordBranches) FloatArray(period) else null
            val cords = if (recordBranches) FloatArray(period) else null
            for (i in raw.indices) {
                if (i % 8192 == 0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("SUTURE loop no longer wanted")
                raw[i] = engine.step()
                eyelets?.set(i, engine.eyelets.toFloat())
                cords?.set(i, engine.cords.toFloat())
            }
            val state = engine.groups()
            actuatorWorkPerCycle = engine.actuatorWork - previousActuatorWork
            acousticWorkPerCycle = engine.poweredWork - previousAcousticWork
            errors = groupErrors(previousState, state)
            stateError = errors.values.maxOrNull() ?: 0.0
            if (previousRaw.size >= 4096) {
                // The sinc resampler reads either side of each output frame. Both
                // sides of the actual cut need periodic guards, including its end.
                val combined = previousRaw.copyOfRange(previousRaw.size - 4096, previousRaw.size) + raw + raw.copyOfRange(0, 4096)
                val finished = finish(combined, normalize = false, loop = true)
                val guarded = finished.copyOfRange(0, 1024 + loopFrames)
                seam = Keys.seamError(guarded, 1024)
                best = guarded.copyOfRange(1024, guarded.size)
            } else best = finish(raw, normalize = false, loop = true)
            fun branchCut(current: FloatArray, previous: FloatArray): FloatArray {
                if (previous.size < 4096) return finish(current, normalize = false, loop = true)
                val guarded = previous.copyOfRange(previous.size - 4096, previous.size) + current + current.copyOfRange(0, 4096)
                return finish(guarded, normalize = false, loop = true).copyOfRange(1024, 1024 + loopFrames)
            }
            if (eyelets != null && cords != null) {
                bestEyelets = branchCut(eyelets, previousEyelets)
                bestCords = branchCut(cords, previousCords)
                previousEyelets = eyelets
                previousCords = cords
            }
            previousRaw = raw
            previousState = state
            cycles = cycle
            if (cycle >= 3 && stateError < 8e-4 && seam < 8e-4) break
        }
        val rms = sqrt(best.sumOf { it.toDouble() * it } / max(1, best.size))
        // Smooth, low-motion settings may form a valid but very quiet cycle.
        // Audibility is separate from physical convergence; shared level matching
        // already leaves sub-threshold signals unamplified.
        val converged = stateError < 1e-3 && seam < 1e-3
        if (normalize) Dsp.levelTo(best, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        return LoopRender(best, seam, stateError, cycles, converged, rms, errors,
            actuatorWorkPerCycle, acousticWorkPerCycle, engine.maxEnergy, bestEyelets, bestCords)
    }

    fun render(voice: SutureVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        val m = settled(macros, voice)
        val out = if (isLoop(m.getValue("HOLD"))) {
            if (finite(velocity, 1f) == 0f) FloatArray(Dsp.RATE * 2) else {
                val loop = renderLoopMeasured(voice, m, velocity)
                check(loop.converged) { "SUTURE $voice powered loop did not converge: state=${loop.stateError}, seam=${loop.seam}" }
                loop.samples
            }
        } else finish(play(voice, m, Probe(velocity = velocity)).raw)
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }
}
