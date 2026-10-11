package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class CorollaVoice { TONGUE, BLOSSOM, CHOIR, CHATTER, ORBIT, HUSK }

/**
 * One imaginary mechanical flower. Three mass-normalised flexural modes per petal, six
 * reciprocal neighbor springs, four chamber modes, and one slow, energy-driven hinge.
 * All constants are musical model choices, not measurements of a physical machine.
 *
 * q is displacement, v is velocity, x = omega*q. Free motion is an exact damped rotation
 * of (x,v). Forces are kicks on the 4x clock. The positive spring potentials and bounded
 * gradients keep the equilibrium stable. A conservative energy correction after each
 * passive step removes split-integrator and moving-geometry energy gains; it is internal
 * to the object, before pickup, decimation or loudness. Static magnets never supply power.
 * See docs/superpowers/specs/2026-10-05-corolla-engine-design.md for the render contract.
 */
object Corolla {
    const val RATE = Dsp.RATE
    const val ROOT_MIDI = 48
    const val TUNE_SEMITONES = 24
    const val LOOP_THRESHOLD = 0.99f
    const val HOLD_LOOP = LOOP_THRESHOLD
    internal const val MAX_FINITE_SECONDS = 14f
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val PETALS = 6
    private const val MODES = 3
    private const val BODY = PETALS * MODES
    private const val COUNT = BODY + 4
    private const val LN1000 = 6.907755278982137
    private const val STEP = 1.0 / INTERNAL_RATE
    private data class Shape(val pull: Float, val bloom: Float, val field: Float,
        val contact: Float, val chamber: Float, val t60: Double,
        val ratios: DoubleArray = doubleArrayOf(1.0, 2.7, 5.4),
        val excitation: DoubleArray = doubleArrayOf(1.0, .8, .45),
        val radiation: DoubleArray = doubleArrayOf(1.0, .7, .4),
        val decay: DoubleArray = doubleArrayOf(1.0, .8, .55),
        val neighborWeight: Double = 1.0, val coupling: Double = .14,
        val bodyWeight: Double = 1.0, val bodyLink: Double = 1.0,
        val fieldDepth: Double = .6, val fieldSpread: Double = .4,
        val fieldRates: DoubleArray = doubleArrayOf(.15, .3, 2.0, 10.0, 40.0),
        val fieldPassages: Boolean = true, val initialFieldSpread: Double = 0.0,
        val bloomRadiation: Double = .6,
        val contactElastic: Double = .18, val contactLoss: Double = .035,
        val contactGapScale: Double = 1.0,
        val contactProjection: DoubleArray = doubleArrayOf(1.0, .5, .3))

    private fun shape(voice: CorollaVoice) = when (voice) {
        CorollaVoice.TONGUE -> Shape(.45f, .25f, .10f, .10f, .30f, 2.8,
            ratios = doubleArrayOf(1.0, 2.73, 5.43), excitation = doubleArrayOf(1.0, 1.0, .4),
            radiation = doubleArrayOf(1.0, 1.0, .65), decay = doubleArrayOf(1.0, .65, .35),
            coupling = .08, bodyWeight = .7, fieldDepth = .3, fieldSpread = .15,
            contactGapScale = 1.1)
        CorollaVoice.BLOSSOM -> Shape(.60f, .70f, .25f, .20f, .55f, 3.8,
            ratios = doubleArrayOf(1.0, 2.15, 3.85), excitation = doubleArrayOf(1.0, 1.15, .8),
            radiation = doubleArrayOf(2.0, .9, .65), decay = doubleArrayOf(1.0, .95, .8),
            neighborWeight = 1.4, coupling = .30, bodyWeight = 2.0, bodyLink = 2.0,
            fieldDepth = .7, fieldSpread = .4, bloomRadiation = 1.8,
            contactElastic = .4)
        CorollaVoice.CHOIR -> Shape(.30f, .55f, .55f, .10f, .65f, 4.8,
            ratios = doubleArrayOf(1.0, 2.006, 4.012), excitation = doubleArrayOf(1.0, 1.2, .7),
            radiation = doubleArrayOf(1.0, .9, .65), decay = doubleArrayOf(1.0, 1.1, .9),
            neighborWeight = 1.5, bodyWeight = 1.4, fieldDepth = .8, fieldSpread = .8,
            contactGapScale = 1.1)
        CorollaVoice.CHATTER -> Shape(.60f, .35f, .45f, .75f, .40f, 3.2,
            ratios = doubleArrayOf(1.0, 3.13, 7.47), excitation = doubleArrayOf(1.0, 1.6, 1.0),
            radiation = doubleArrayOf(1.0, 1.1, .8), decay = doubleArrayOf(1.0, .9, .65),
            neighborWeight = 1.2, fieldDepth = 1.1, fieldSpread = .5,
            contactElastic = .6, contactLoss = .028, contactGapScale = .35,
            contactProjection = doubleArrayOf(.18, .85, .65))
        CorollaVoice.ORBIT -> Shape(.35f, .60f, .80f, .10f, .40f, 4.0,
            // Align upper modes with responding petals so slow circulation does not
            // acquire rapid beats from neighboring inharmonic partials.
            ratios = doubleArrayOf(1.0, 3.0, 6.0), excitation = doubleArrayOf(1.0, .95, .55),
            radiation = doubleArrayOf(1.0, .85, .6), decay = doubleArrayOf(1.0, .85, .7),
            neighborWeight = 1.7, fieldDepth = 1.15, fieldSpread = 1.0,
            fieldRates = doubleArrayOf(.08, .20, .50, 1.0, 2.0),
            fieldPassages = false, initialFieldSpread = .30,
            contactElastic = .25, contactGapScale = .7)
        CorollaVoice.HUSK -> Shape(.40f, .20f, .30f, .30f, .85f, 3.5,
            ratios = doubleArrayOf(1.0, 1.48, 3.12), excitation = doubleArrayOf(1.0, 1.3, .28),
            radiation = doubleArrayOf(.8, 1.2, .18), decay = doubleArrayOf(1.0, .95, .4),
            bodyWeight = 5.0, bodyLink = 5.0, fieldDepth = .7, fieldSpread = .3,
            bloomRadiation = .3, contactElastic = .6, contactGapScale = 1.1)
    }

    fun macrosFor(voice: CorollaVoice): List<MacroSpec> {
        val s = shape(voice)
        return listOf(MacroSpec("TUNE", .5f, .5f), MacroSpec("PULL", s.pull, .4f),
            MacroSpec("BLOOM", s.bloom, .4f), MacroSpec("FIELD", s.field, 0f),
            MacroSpec("CONTACT", s.contact, 0f), MacroSpec("CHAMBER", s.chamber, .4f),
            MacroSpec("HOLD", 0f, 0f))
    }

    fun defaults(voice: CorollaVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(macros: Map<String, Float>, voice: CorollaVoice): Map<String, Float> =
        defaults(voice).toMutableMap().also { m ->
            for ((k, v) in macros) if (k in m) m[k] = if (v.isFinite()) v.coerceIn(0f, 1f) else m.getValue(k)
        }
    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: CorollaVoice) = ROOT_MIDI
    fun midiFor(voice: CorollaVoice, tune: Float) = rootMidi(voice) + (tune.coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(voice: CorollaVoice, tune: Float) = Keys.midiHz(midiFor(voice, tune))
    fun isLoop(hold: Float) = hold >= LOOP_THRESHOLD
    /** Pitched engine metadata preserves IN KEY routing; the generic duration classifier
     * independently labels long recordings LOOP. Only settled HOLD material loops here. */
    fun drumClassFor(voice: CorollaVoice, macros: Map<String, Float> = emptyMap()) =
        if (isLoop(settled(macros, voice).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL
    fun scramble(voice: CorollaVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(defaults(voice) + (near?.macros?.filterKeys { it in defaults(voice) } ?: emptyMap()), temperature, random)
            .toMutableMap().also { it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f) }

    /** Rate anchors are interpolated in log frequency; FIELD zero supplies exactly no drive. */
    internal fun coreHz(field: Float, voice: CorollaVoice = CorollaVoice.TONGUE): Double {
        if (field <= 0f) return 0.0
        val a = shape(voice).fieldRates
        val x = field.coerceIn(0f, 1f) * 4
        val j = x.toInt().coerceAtMost(3)
        return a[j] * (a[j + 1] / a[j]).pow(x - j.toDouble())
    }

    internal class Probe(val record: Boolean = false, val coupling: Boolean = true,
        val chamber: Boolean = true, val pull: Boolean = true, val powered: Boolean = true,
        val opening: Boolean = true)
    internal class Taps(n: Int) {
        val direct = FloatArray(n)
        val neighbors = FloatArray(n)
        val chamber = FloatArray(n)
        val contact = FloatArray(n)
        val opening = FloatArray(n)
        val energy = FloatArray(n)
        val poweredInput = FloatArray(n)
        val contactEnergy = FloatArray(n)
        val passiveCorrection = FloatArray(n)
        fun trimmed(n: Int) = Taps(n).also { result ->
            for ((source, destination) in listOf(direct to result.direct, neighbors to result.neighbors,
                chamber to result.chamber, contact to result.contact, opening to result.opening,
                energy to result.energy, poweredInput to result.poweredInput,
                contactEnergy to result.contactEnergy, passiveCorrection to result.passiveCorrection))
                source.copyInto(destination, endIndex = n)
        }
    }
    internal class Played(val raw: FloatArray, val taps: Taps?, val loopStart: Int = -1)

    private data class LoopPlan(val frames: Int, val core: Double, val hz: Double)
    private fun loopPlan(hz: Double, field: Float, voice: CorollaVoice): LoopPlan {
        val core = coreHz(field, voice)
        // At least one slow core orbit, at most four seconds. The nearest compatible nonzero
        // rate is used for very slow fields. Root moves less than 0.1 cent by the frame snap.
        val seconds = if (core > 0) (ceil(core * 1.8).coerceAtLeast(1.0) / core).coerceIn(1.8, 4.0) else 2.0
        val cycles = (seconds * hz).roundToInt().coerceAtLeast(1)
        val frames = (cycles * RATE / hz).roundToInt()
        val duration = frames / RATE.toDouble()
        return LoopPlan(frames, if (core > 0) (core * duration).roundToInt().coerceAtLeast(1) / duration else 0.0,
            cycles / duration)
    }

    /** Full internal object, including the attack. HOLD's public buffer is cut from its settled tail. */
    internal fun play(voice: CorollaVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        probe: Probe = Probe(), seconds: Float? = null): Played {
        val m = settled(macros, voice)
        val s = shape(voice)
        val pull = m.getValue("PULL").toDouble()
        val bloom = m.getValue("BLOOM").toDouble()
        val field = m.getValue("FIELD").toDouble()
        val contact = m.getValue("CONTACT").toDouble()
        val chamber = m.getValue("CHAMBER").toDouble()
        val hold = m.getValue("HOLD").toDouble()
        val held = isLoop(hold.toFloat())
        val strength = if (velocity.isFinite()) velocity.coerceIn(0f, 1f).toDouble() else 1.0
        val requestedHz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        val plan = loopPlan(requestedHz, field.toFloat(), voice)
        val hz = if (held) plan.hz else requestedHz
        val w0 = 2 * PI * hz
        val resting = .08 + .66 * bloom
        val driveEnd = .35 + 1.3 * field + 1.3 * hold
        val naturalTail = seconds == null && !held
        val periodN = plan.frames * Dsp.OVERSAMPLE
        val prerollN = (max(2.0, s.t60 * .65) * INTERNAL_RATE).toInt() / periodN * periodN + periodN
        val n = if (seconds != null) (seconds.coerceIn(.01f, 30f) * INTERNAL_RATE).toInt()
            else if (held) prerollN + 3 * periodN else (MAX_FINITE_SECONDS * INTERNAL_RATE).toInt()
        val out = FloatArray(n)
        val taps = if (probe.record) Taps(n) else null
        var renderedN = n
        var peakEnergy = 0.0
        var peakPickup = 0.0
        var quietSamples = 0
        val quietWindow = (.15 * INTERNAL_RATE).roundToInt()

        val target = DoubleArray(COUNT)
        val loss = DoubleArray(COUNT)
        val pickup = DoubleArray(COUNT)
        val random = Random(Dsp.seedFor("COROLLA", voice.name, midiFor(voice, m.getValue("TUNE"))))
        for (p in 0 until PETALS) for (j in 0 until MODES) {
            val i = p * MODES + j
            val variation = if (p == 0 && j == 0) 1.0 else 1.0 + (random.nextDouble() - .5) * .002
            val upper = s.ratios[j]
            target[i] = w0 * (p + 1) * upper * variation
            loss[i] = s.t60 * (.80 + .4 * chamber) * s.decay[j] / (1 + .10 * p)
            // Modes near the final supported bandwidth are attenuated, never folded.
            val bandwidth = ((19_000 - target[i] / (2 * PI)) / 3_000).coerceIn(0.0, 1.0)
            pickup[i] = bandwidth * (if (p == 0) 1.0 else s.neighborWeight / sqrt(p + 1.0)) * s.radiation[j]
        }
        val bodyRatios = doubleArrayOf(1.0, 1.57, 2.31, 3.62)
        for (j in 0 until 4) {
            target[BODY + j] = 2 * PI * (620.0 * (145.0 / 620).pow(chamber)) * bodyRatios[j]
            loss[BODY + j] = .20 + .8 * chamber
            pickup[BODY + j] = if (probe.chamber) s.bodyWeight * (.08 + .65 * chamber) / sqrt(j + 1.0) else 0.0
        }
        val edgeI = IntArray(10) { if (it < 6) it * MODES else 0 }
        val edgeJ = IntArray(10) { if (it < 6) ((it + 1) % PETALS) * MODES else BODY + it - 6 }
        val spring = DoubleArray(10)
        val unloaded = target.copyOf()
        fun setSprings(b: Double) {
            for (e in spring.indices) spring[e] = if (e < 6) {
                if (probe.coupling) w0 * w0 * (.025 + s.coupling * (1 - b).pow(2)) else 0.0
            } else if (probe.chamber) min(unloaded[0], unloaded[edgeJ[e]]).pow(2) *
                s.bodyLink * (.012 + .05 * chamber) / (e - 5) else 0.0
            // Preserve positive unloaded stiffness, so the tuning eigensolve describes
            // the actual network even in a folded, strongly loaded chamber.
            val load = DoubleArray(COUNT)
            for (e in spring.indices) { load[edgeI[e]] += spring[e]; load[edgeJ[e]] += spring[e] }
            var bound = 1.0
            for (i in load.indices) if (load[i] > 0)
                bound = min(bound, .40 * unloaded[i] * unloaded[i] / load[i])
            if (bound < 1.0) for (e in spring.indices) spring[e] *= bound
        }
        setSprings(resting)
        // Preload subtraction leaves each diagonal stiffness at its requested value. The
        // reciprocal off-diagonals still shift the eigenmode, so compensate the full network.
        val matrix = Array(COUNT) { i -> DoubleArray(COUNT) { j -> if (i == j) target[i] * target[i] else 0.0 } }
        for (e in spring.indices) {
            matrix[edgeI[e]][edgeJ[e]] -= spring[e]
            matrix[edgeJ[e]][edgeI[e]] -= spring[e]
        }
        val (eigen, vectors) = Modes.symmetricEigen(matrix)
        val anchor = eigen.indices.maxBy { abs(vectors[0][it]) }
        val tuning = w0 / sqrt(eigen[anchor])
        for (i in target.indices) target[i] *= tuning
        for (e in spring.indices) spring[e] *= tuning * tuning
        val x = DoubleArray(COUNT)
        val v = DoubleArray(COUNT)
        val q = DoubleArray(COUNT)
        val omega = DoubleArray(COUNT)
        val cr = DoubleArray(COUNT)
        val sr = DoubleArray(COUNT)
        val force = DoubleArray(COUNT)
        val loadingLoss = DoubleArray(COUNT)
        val contactProjection = s.contactProjection
        var b = resting
        var bv = 0.0
        var sensed = 0.0
        var phase = 0.0
        var lastEnergy = 0.0
        var contactActivity = 0.0
        val phaseStep = 2 * PI * (if (held) plan.core else coreHz(field.toFloat(), voice)) * STEP
        val pullN = ((.0025 - .0020 * pull) * INTERNAL_RATE).roundToInt().coerceAtLeast(16)
        val amplitude = strength * (.16 + .48 * pull)
        val upperPull = doubleArrayOf(1.0, s.excitation[1] * (.3 + .9 * pull),
            s.excitation[2] * (.2 + .8 * pull * pull))
        // Total powered work per second, in these mass-normalised coordinates. Each force
        // proposal is shrunk to this budget before touching the object. No output limiter.
        val powerBudget = if (!probe.powered) 0.0 else (.025 + .45 * field) * (if (held) 1.0 else field) * strength
        fun coefficients() {
            setSprings(b)
            for (e in spring.indices) spring[e] *= tuning * tuning
            val preload = DoubleArray(COUNT)
            loadingLoss.fill(0.0)
            for (e in spring.indices) {
                preload[edgeI[e]] += spring[e]; preload[edgeJ[e]] += spring[e]
                if (spring[e] > 0) {
                    loadingLoss[edgeI[e]] += .00018 * w0
                    loadingLoss[edgeJ[e]] += .00018 * w0
                }
            }
            for (i in omega.indices) {
                omega[i] = sqrt(max(target[i] * target[i] * .55, target[i] * target[i] - preload[i]))
                val apertureLoss = if (i >= BODY) 1 + 2.5 * b else 1 + .15 * b
                val r = exp(-LN1000 * STEP * apertureLoss / loss[i])
                cr[i] = r * cos(omega[i] * STEP)
                sr[i] = r * sin(omega[i] * STEP)
            }
        }
        coefficients()
        fun contactGap() = (.37 - .34 * sqrt(contact) + .16 * b) * s.contactGapScale
        // Integral of z^2 / (softness + z). Elastic contact work is stored here and
        // returned on separation, rather than mistaken for new passive input.
        fun contactPotential(penetration: Double): Double {
            if (penetration <= 0.0) return 0.0
            val softness = .03
            return contact * s.contactElastic * max(0.0,
                .5 * penetration * penetration - softness * penetration +
                    softness * softness * ln1p(penetration / softness))
        }
        fun contactStored(scale: Double = 1.0): Double {
            if (contact <= 0.0) return 0.0
            val gap = contactGap()
            var stored = 0.0
            for (p in 0 until PETALS) {
                val other = (p + 1) % PETALS
                var delta = 0.0
                for (j in 0 until MODES) {
                    val i = p * MODES + j
                    val k = other * MODES + j
                    delta += contactProjection[j] * (x[i] / omega[i] - x[k] / omega[k]) * w0
                }
                stored += contactPotential(abs(delta * scale) - gap)
            }
            return stored
        }
        fun energy(scale: Double = 1.0): Double {
            var energy = 0.0
            for (i in x.indices) energy += .5 * (x[i] * x[i] + v[i] * v[i]) * scale * scale
            for (e in spring.indices) {
                val d = (x[edgeI[e]] / omega[edgeI[e]] - x[edgeJ[e]] / omega[edgeJ[e]]) * scale
                val maxTravel = 4.0 / w0
                val a = abs(d / maxTravel)
                // Exact potential of the bounded tanh link; evaluating it stably also
                // keeps the diagnostic meaningful outside the usual small-motion range.
                val logCosh = if (a < 1e-4) .5 * a * a else if (a < 20.0) ln(cosh(a)) else a - ln(2.0)
                energy += spring[e] * maxTravel * maxTravel * logCosh
            }
            return energy + contactStored(scale)
        }
        fun passiveKick(fraction: Double) {
            for (i in x.indices) {
                q[i] = x[i] / omega[i]
                force[i] = 0.0
            }
            for (e in spring.indices) {
                val i = edgeI[e]; val j = edgeJ[e]
                // Opposite reactions occur once per edge. FIELD zero still has these
                // passive structural/magnetic links and answering petals.
                val delta = q[j] - q[i]
                val maxTravel = 4.0 / w0
                val f = spring[e] * maxTravel * tanh(delta / maxTravel)
                val damping = .00018 * w0 * (v[j] - v[i])
                force[i] += f + (if (spring[e] > 0) damping else 0.0)
                force[j] -= f + (if (spring[e] > 0) damping else 0.0)
            }
            if (contact > 0) for (p in 0 until PETALS) {
                val other = (p + 1) % PETALS
                var delta = 0.0; var relative = 0.0
                for (j in 0 until MODES) {
                    delta += contactProjection[j] * (q[p * MODES + j] - q[other * MODES + j]) * w0
                    relative += contactProjection[j] * (v[p * MODES + j] - v[other * MODES + j])
                }
                val penetration = abs(delta) - contactGap()
                if (penetration > 0) {
                    val sign = if (delta > 0) 1.0 else -1.0
                    val smooth = penetration * penetration / (.03 + penetration)
                    // The first term is the gradient of contactPotential; the second
                    // dissipates only during closing and stores no fictitious energy.
                    val magnitude = w0 * contact * (s.contactElastic * smooth +
                        s.contactLoss * max(0.0, sign * relative))
                    val f = sign * magnitude
                    for (j in 0 until MODES) {
                        force[p * MODES + j] -= f * contactProjection[j]
                        force[other * MODES + j] += f * contactProjection[j]
                    }
                    contactActivity += fraction * magnitude / w0
                }
            }
            for (i in v.indices) v[i] += force[i] * STEP * fraction
        }
        for (sample in 0 until n) {
            if (sample % 64 == 0) {
                if (probe.opening) {
                    val dt = 64 * STEP
                    sensed += (lastEnergy - sensed) * (1 - exp(-dt / .035))
                    val acceleration = (20 + 100 * bloom) * sensed - 26 * (b - resting) - 9 * bv
                    bv += dt * acceleration
                    val next = b + dt * bv
                    b = next.coerceIn(0.0, 1.0)
                    if (next != b) bv *= .0
                }
                // Changing the hinge changes stiffness, not physical displacement.
                // Any positive geometry work is removed by the passive guard below.
                for (i in x.indices) q[i] = x[i] / omega[i]
                coefficients()
                for (i in x.indices) x[i] = q[i] * omega[i]
            }
            contactActivity = 0.0
            passiveKick(.5)
            for (i in x.indices) {
                val old = x[i]
                x[i] = cr[i] * old + sr[i] * v[i]
                v[i] = cr[i] * v[i] - sr[i] * old
            }
            passiveKick(.5)
            var e = energy()
            var correction = 0.0
            // Symmetric elastic kicks preserve normal contact exchange. This guard still
            // forbids passive geometry/integration gains, measured with all stored potentials.
            if (!e.isFinite()) {
                x.fill(0.0); v.fill(0.0); e = 0.0
            } else if (sample >= pullN && e > lastEnergy && e > 0) {
                val before = e
                var g = sqrt(lastEnergy / e)
                // Contact and tanh potentials are nonquadratic. A radial bound avoids
                // pretending that their stored energy necessarily scales by g squared.
                if (energy(g) > lastEnergy) {
                    var lo = 0.0; var hi = g
                    repeat(24) {
                        val mid = (lo + hi) * .5
                        if (energy(mid) > lastEnergy) hi = mid else lo = mid
                    }
                    g = lo
                }
                for (i in x.indices) { x[i] *= g; v[i] *= g }
                e = energy()
                correction = max(0.0, before - e)
            }
            if (probe.pull && sample < pullN) {
                val t = (sample + 1.0) / pullN
                val ramp = .5 - .5 * cos(PI * t)
                for (j in 0 until MODES) {
                    x[j] = amplitude * upperPull[j] * ramp
                    v[j] = amplitude * upperPull[j] * PI * sin(PI * t) / (2 * pullN * STEP * omega[j])
                }
                e = energy()
            }
            var work = 0.0
            val envelope = if (held) 1.0 else if (sample * STEP <= driveEnd) 1.0
                else (.5 + .5 * cos(PI * ((sample * STEP - driveEnd) / .25).coerceIn(0.0, 1.0)))
            if (powerBudget > 0 && envelope > 0 && sample >= pullN) {
                // Powered velocity feedback is a force inside the modes: it offsets their
                // losses, with a rotating spatial preference and amplitude-dependent gain.
                // HOLD at FIELD0 uses a gentle feedback maintenance drive on this same bank.
                val available = powerBudget * envelope * STEP
                var rootLinear = 0.0; var rootQuadratic = 0.0
                var otherLinear = 0.0; var otherQuadratic = 0.0
                for (p in 0 until PETALS) {
                    val facing = .5 + .5 * cos(phase - p * 2 * PI / PETALS)
                    val spatial = (.65 + s.fieldDepth * (facing - .5)).coerceAtLeast(.04)
                    for (j in 0 until MODES) {
                        val i = p * MODES + j
                        val own = .5 * (x[i] * x[i] + v[i] * v[i])
                        val targetEnergy = (.02 + .10 * field) * (if (p == 0) 1.0 else s.fieldSpread) *
                            (if (j == 0) 1.0 else .35 * s.excitation[j]) / (1 + p * .35 + j * .65)
                        // The anchored mode also loses energy through the mounted network.
                        // Its separate work allocation prevents isolated upper modes from
                        // consuming the source while the loaded fundamental dies away.
                        val passiveRate = 2 * LN1000 * (1 + .15 * b) / loss[i] + loadingLoss[i]
                        val driveRate = passiveRate * (1.3 + 1.2 * field) / .65 + if (i == 0) 32.0 else 0.0
                        val gain = driveRate * spatial / (1 + own / targetEnergy.coerceAtLeast(.0001))
                        force[i] = v[i] * gain * STEP
                        // A finite, smooth field-induced release starts the feedback network
                        // even with the performer's pull disabled. It drives the actual root
                        // modes and spends the same power budget; it is not an output layer.
                        val releaseTime = (sample - pullN) * STEP
                        if (p == 0 && releaseTime < .008 && field > 0)
                            force[i] += w0 * .04 * field * upperPull[j] * sin(PI * releaseTime / .008) * STEP
                        // ORBIT wakes its answering modes once, then the smooth rotating
                        // feedback sustains their free pitches without a recurring pulse comb.
                        if (p > 0 && releaseTime < .008 && field > 0 && s.initialFieldSpread > 0)
                            force[i] += target[i] * .04 * field * s.initialFieldSpread * s.excitation[j] *
                                sin(PI * releaseTime / .008) * STEP
                        // The motor can pull and release whichever petal it faces. This is a
                        // smooth acoustic-clock force inside the bank, not gain on its output.
                        // It seeds responding modes that velocity feedback cannot wake from zero.
                        if (s.fieldPassages && field > 0 && p > 0) {
                            val sweep = ((phase - p * 2 * PI / PETALS) % (2 * PI) + 2 * PI) % (2 * PI)
                            val coreRate = phaseStep / (2 * PI * STEP)
                            val pulseSeconds = .0008 + .0014 * (1 - pull)
                            val passageSeconds = if (coreRate > 0) sweep / (2 * PI * coreRate) else Double.POSITIVE_INFINITY
                            val magneticRelease = if (passageSeconds < pulseSeconds)
                                sin(PI * passageSeconds / pulseSeconds) else 0.0
                            // Repeated pulses excite responding petals. The anchored root
                            // keeps its free pitch: a periodic impulse comb can entrain it to
                            // a core harmonic. Its initial release and feedback suffice.
                            force[i] += target[i] * .10 * field * s.fieldSpread * s.excitation[j] *
                                magneticRelease * STEP
                        }
                        if (i == 0) {
                            rootLinear += v[i] * force[i]
                            rootQuadratic += .5 * force[i] * force[i]
                        } else {
                            otherLinear += v[i] * force[i]
                            otherQuadratic += .5 * force[i] * force[i]
                        }
                    }
                }
                // Solve the actual kick work a*g + b*g², including release impulses.
                // Each group stays within its share; their sum never exceeds source power.
                fun workScale(a: Double, q: Double, budget: Double): Double =
                    if (a + q <= budget) 1.0 else if (q > 0)
                        ((sqrt(a * a + 4 * q * budget) - a) / (2 * q)).coerceIn(0.0, 1.0)
                    else (budget / a).coerceIn(0.0, 1.0)
                val rootGain = workScale(rootLinear, rootQuadratic, available * .55)
                val otherGain = workScale(otherLinear, otherQuadratic, available * .45)
                for (i in 0 until BODY) {
                    val dv = force[i] * if (i == 0) rootGain else otherGain
                    work += v[i] * dv + .5 * dv * dv
                    v[i] += dv
                }
                e = energy()
            }
            lastEnergy = e
            var direct = 0.0; var responding = 0.0; var cavity = 0.0
            for (i in 0 until BODY) {
                val aperture = if (i % MODES == 0) 1.0 else .35 + s.bloomRadiation * b
                val value = pickup[i] * aperture * v[i]
                if (i < MODES) direct += value else responding += value
            }
            for (i in BODY until COUNT) cavity += pickup[i] * v[i] * (1 - .65 * b)
            val radiation = .72 + .40 * b
            out[sample] = (.65 * (radiation * (direct + responding) + cavity)).toFloat()
            taps?.let {
                it.direct[sample] = direct.toFloat(); it.neighbors[sample] = responding.toFloat()
                it.chamber[sample] = cavity.toFloat(); it.contact[sample] = contactActivity.toFloat()
                it.opening[sample] = b.toFloat(); it.energy[sample] = e.toFloat()
                it.poweredInput[sample] = work.toFloat()
                it.contactEnergy[sample] = contactStored().toFloat()
                it.passiveCorrection[sample] = correction.toFloat()
            }
            phase += phaseStep
            if (phase > 2 * PI) phase -= 2 * PI
            if (naturalTail) {
                peakEnergy = max(peakEnergy, e)
                peakPickup = max(peakPickup, abs(out[sample].toDouble()))
                // Let the same unpowered object resolve. Stored energy prevents a
                // cancellation trough from ending a note; quiet pickup and a sustained
                // quiet window make the final click-prevention fade inaudible.
                val quiet = sample * STEP >= driveEnd + .25 &&
                    e <= peakEnergy * 1e-8 && abs(out[sample].toDouble()) <= peakPickup * 1e-4
                quietSamples = if (quiet) quietSamples + 1 else 0
                if (quietSamples >= quietWindow && (sample + 1) * STEP >= 2.2 &&
                    (sample + 1) % Dsp.OVERSAMPLE == 0) {
                    renderedN = sample + 1
                    break
                }
            }
        }
        return Played(if (renderedN == n) out else out.copyOf(renderedN),
            if (renderedN == n) taps else taps?.trimmed(renderedN),
            if (held && seconds == null) n - periodN else -1)
    }

    internal fun finish(raw: FloatArray, normalize: Boolean = true, fade: Boolean = true): FloatArray {
        val filtered = raw.copyOf()
        Tide.bandLimit(filtered, INTERNAL_RATE)
        val out = Dsp.decimate(filtered, RATE)
        val mean = if (out.isEmpty()) 0f else (out.sumOf { it.toDouble() } / out.size).toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) { val x = out[i] - mean; out[i] = x - hp.lp(x, 20f) }
        if (normalize) Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        if (fade) Dsp.fadeTail(out)
        return out
    }

    /** Preroll and one loop, for the host's existing 256-frame seam metric. */
    internal fun loopBuffer(voice: CorollaVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        normalize: Boolean = true): Pair<FloatArray, Int> {
        val played = play(voice, macros + ("HOLD" to 1f), velocity)
        val finished = finish(played.raw, normalize = false, fade = false)
        val period = (played.raw.size - played.loopStart) / Dsp.OVERSAMPLE
        val start = played.loopStart / Dsp.OVERSAMPLE
        // Pick the least-changing of two successive candidates before a wrap blend. Every
        // subsystem has run through the bounded preroll; core phase alone is not the seam.
        fun difference(end: Int): Double {
            var d = 0.0
            for (i in end - period until end) { val e = finished[i] - finished[i - period]; d += e * e }
            return d
        }
        val end = if (difference(start) < difference(start + period)) start else start + period
        val buf = finished.copyOfRange(end - 2 * period, end)
        wrapCrossfade(buf, period)
        if (normalize) Dsp.levelTo(buf, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        // A zero-velocity note is exactly silent; the relative seam metric is undefined (0/0).
        if (buf.any { it != 0f }) Keys.requireSeam("COROLLA $voice", buf, period)
        return buf to period
    }

    /** Settled sustain only: the current Snip API does not carry an attack-plus-loop marker. */
    fun renderLoop(voice: CorollaVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        normalize: Boolean = true): FloatArray {
        val (buffer, start) = loopBuffer(voice, macros, velocity, normalize)
        return buffer.copyOfRange(start, buffer.size)
    }

    // Tremor's proven, unity-sum smoothstep wrap: no equal-power boost on correlated tones.
    private fun wrapCrossfade(buf: FloatArray, start: Int) {
        val fade = 1024
        val seam = 256
        val need = fade + seam
        for (i in 0 until need) {
            val w = if (i >= fade) 1.0 else (i / fade.toDouble()).let { it * it * (3 - 2 * it) }
            val src = start - need + i
            val dst = buf.size - need + i
            buf[dst] = ((1 - w) * buf[dst] + w * buf[src]).toFloat()
        }
    }

    fun render(voice: CorollaVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        val m = settled(macros, voice)
        return Snip(if (isLoop(m.getValue("HOLD"))) renderLoop(voice, m, velocity)
            else finish(play(voice, m, velocity).raw), channels = 1, sampleRate = RATE)
    }
}
