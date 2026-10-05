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
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val PETALS = 6
    private const val MODES = 3
    private const val BODY = PETALS * MODES
    private const val COUNT = BODY + 4
    private const val LN1000 = 6.907755278982137
    private const val STEP = 1.0 / INTERNAL_RATE
    private val UPPER_RATIOS = doubleArrayOf(1.0, 2.7, 5.4)

    private data class Shape(val pull: Float, val bloom: Float, val field: Float,
        val contact: Float, val chamber: Float, val t60: Double, val metal: Double)

    private fun shape(voice: CorollaVoice) = when (voice) {
        CorollaVoice.TONGUE -> Shape(.45f, .25f, .10f, .10f, .30f, 2.8, .90)
        CorollaVoice.BLOSSOM -> Shape(.60f, .70f, .25f, .20f, .55f, 3.8, 1.00)
        CorollaVoice.CHOIR -> Shape(.30f, .55f, .55f, .10f, .65f, 4.8, .80)
        CorollaVoice.CHATTER -> Shape(.60f, .35f, .45f, .75f, .40f, 3.2, 1.10)
        CorollaVoice.ORBIT -> Shape(.35f, .60f, .80f, .35f, .40f, 4.0, 1.05)
        CorollaVoice.HUSK -> Shape(.40f, .20f, .30f, .30f, .85f, 3.5, .65)
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
    internal fun coreHz(field: Float): Double {
        if (field <= 0f) return 0.0
        val a = doubleArrayOf(.15, .3, 2.0, 10.0, 40.0)
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
    }
    internal class Played(val raw: FloatArray, val taps: Taps?, val loopStart: Int = -1)

    private data class LoopPlan(val frames: Int, val core: Double, val hz: Double)
    private fun loopPlan(hz: Double, field: Float): LoopPlan {
        val core = coreHz(field)
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
        val plan = loopPlan(requestedHz, field.toFloat())
        val hz = if (held) plan.hz else requestedHz
        val w0 = 2 * PI * hz
        val resting = .08 + .66 * bloom
        val driveEnd = .35 + 1.3 * field + 1.3 * hold
        val naturalSeconds = (driveEnd + .80 * s.t60).coerceIn(2.2, 6.0)
        val periodN = plan.frames * Dsp.OVERSAMPLE
        val prerollN = (max(2.0, s.t60 * .65) * INTERNAL_RATE).toInt() / periodN * periodN + periodN
        val n = if (seconds != null) (seconds.coerceIn(.01f, 30f) * INTERNAL_RATE).toInt()
            else if (held) prerollN + 3 * periodN else (naturalSeconds * INTERNAL_RATE).toInt()
        val out = FloatArray(n)
        val taps = if (probe.record) Taps(n) else null

        val target = DoubleArray(COUNT)
        val loss = DoubleArray(COUNT)
        val pickup = DoubleArray(COUNT)
        val random = Random(Dsp.seedFor("COROLLA", voice.name, midiFor(voice, m.getValue("TUNE"))))
        for (p in 0 until PETALS) for (j in 0 until MODES) {
            val i = p * MODES + j
            val variation = if (p == 0 && j == 0) 1.0 else 1.0 + (random.nextDouble() - .5) * .002
            val upper = if (j == 0) 1.0 else UPPER_RATIOS[j] * (1 + (s.metal - 1) * .015)
            target[i] = w0 * (p + 1) * upper * variation
            loss[i] = s.t60 * (.80 + .4 * chamber) / (1 + .65 * j + .10 * p)
            // Modes near the final supported bandwidth are attenuated, never folded.
            val bandwidth = ((19_000 - target[i] / (2 * PI)) / 3_000).coerceIn(0.0, 1.0)
            pickup[i] = bandwidth * (if (p == 0) 1.0 else .95 / sqrt(p + 1.0)) *
                (if (j == 0) 1.0 else .35 * s.metal / j)
        }
        val bodyRatios = doubleArrayOf(1.0, 1.57, 2.31, 3.62)
        for (j in 0 until 4) {
            target[BODY + j] = 2 * PI * (620.0 * (145.0 / 620).pow(chamber)) * bodyRatios[j]
            loss[BODY + j] = .20 + .8 * chamber
            pickup[BODY + j] = if (probe.chamber) (.08 + .65 * chamber) / (j + 1) else 0.0
        }
        val edgeI = IntArray(10) { if (it < 6) it * MODES else 0 }
        val edgeJ = IntArray(10) { if (it < 6) ((it + 1) % PETALS) * MODES else BODY + it - 6 }
        val spring = DoubleArray(10)
        val unloaded = target.copyOf()
        fun setSprings(b: Double) {
            for (e in spring.indices) spring[e] = if (e < 6) {
                if (probe.coupling) w0 * w0 * (.025 + .14 * (1 - b).pow(2)) else 0.0
            } else if (probe.chamber) min(unloaded[0], unloaded[edgeJ[e]]).pow(2) * (.006 + .028 * chamber) / (e - 5) else 0.0
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
        val contactProjection = doubleArrayOf(1.0, .34, .12)
        var b = resting
        var bv = 0.0
        var sensed = 0.0
        var phase = 0.0
        var lastEnergy = 0.0
        var contactActivity = 0.0
        val phaseStep = 2 * PI * (if (held) plan.core else coreHz(field.toFloat())) * STEP
        val pullN = ((.0025 - .0020 * pull) * INTERNAL_RATE).roundToInt().coerceAtLeast(16)
        val amplitude = strength * (.16 + .48 * pull)
        val upperPull = doubleArrayOf(1.0, (.05 + .40 * pull * pull) * s.metal, (.018 + .23 * pull.pow(3)) * s.metal)
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
                    loadingLoss[edgeI[e]] += .0008 * w0
                    loadingLoss[edgeJ[e]] += .0008 * w0
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
        fun energy(): Double {
            var energy = 0.0
            for (i in x.indices) energy += .5 * (x[i] * x[i] + v[i] * v[i])
            for (e in spring.indices) {
                val d = x[edgeI[e]] / omega[edgeI[e]] - x[edgeJ[e]] / omega[edgeJ[e]]
                energy += .5 * spring[e] * d * d
            }
            return energy
        }
        for (sample in 0 until n) {
            if (sample % 64 == 0) {
                if (probe.opening) {
                    val dt = 64 * STEP
                    sensed += (lastEnergy - sensed) * (1 - exp(-dt / .035))
                    val acceleration = (12 + 40 * bloom) * sensed - 26 * (b - resting) - 9 * bv
                    bv += dt * acceleration
                    val next = b + dt * bv
                    b = next.coerceIn(0.0, 1.0)
                    if (next != b) bv *= .0
                }
                coefficients()
            }
            for (i in x.indices) {
                val old = x[i]
                x[i] = cr[i] * old + sr[i] * v[i]
                v[i] = cr[i] * v[i] - sr[i] * old
                q[i] = x[i] / omega[i]
                force[i] = 0.0
            }
            for (e in spring.indices) {
                val i = edgeI[e]; val j = edgeJ[e]
                // Bounded gradient: the supported states lie in its linear region. Opposite
                // reactions are applied once. Small structural and magnetic transfer share
                // this passive link, so FIELD zero still has answering petals.
                val delta = q[j] - q[i]
                val maxTravel = 4.0 / w0
                val f = spring[e] * maxTravel * tanh(delta / maxTravel)
                val damping = .0008 * w0 * (v[j] - v[i])
                force[i] += f + (if (spring[e] > 0) damping else 0.0)
                force[j] -= f + (if (spring[e] > 0) damping else 0.0)
            }
            contactActivity = 0.0
            if (contact > 0) for (p in 0 until PETALS) {
                val other = (p + 1) % PETALS
                var delta = 0.0; var relative = 0.0
                for (j in 0 until MODES) {
                    delta += contactProjection[j] * (q[p * MODES + j] - q[other * MODES + j]) * w0
                    relative += contactProjection[j] * (v[p * MODES + j] - v[other * MODES + j])
                }
                val gap = .37 - .34 * sqrt(contact) + .16 * b
                val penetration = abs(delta) - gap
                if (penetration > 0) {
                    // C1 compliant, non-adhesive spring plus closing-only loss. The elastic
                    // energy is subject to the same passive bound; contact cannot add energy.
                    val sign = if (delta > 0) 1.0 else -1.0
                    val smooth = penetration * penetration / (.03 + penetration)
                    val magnitude = w0 * contact * (.18 * smooth + .035 * max(0.0, sign * relative))
                    val f = sign * magnitude
                    for (j in 0 until MODES) {
                        force[p * MODES + j] -= f * contactProjection[j]
                        force[other * MODES + j] += f * contactProjection[j]
                    }
                    contactActivity += magnitude / w0
                }
            }
            for (i in v.indices) v[i] += force[i] * STEP
            var e = energy()
            // Bounded energy correction is a documented reduced passive formulation: moving
            // hinge geometry and the nonlinear split may dissipate energy but never replenish
            // it. It also enforces finite state at the source, independently of output gain.
            if (!e.isFinite()) {
                x.fill(0.0); v.fill(0.0); e = 0.0
            } else if (sample >= pullN && e > lastEnergy && e > 0) {
                val g = sqrt(lastEnergy / e)
                for (i in x.indices) { x[i] *= g; v[i] *= g }
                e = lastEnergy
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
                var proposed = 0.0
                for (p in 0 until PETALS) {
                    val facing = .5 + .5 * cos(phase - p * 2 * PI / PETALS)
                    val spatial = if (p == 0) .80 + .20 * facing else .20 + .80 * facing
                    for (j in 0 until MODES) {
                        val i = p * MODES + j
                        val own = .5 * (x[i] * x[i] + v[i] * v[i])
                        val targetEnergy = (.015 + .09 * field) * spatial / (1 + p * .50 + j * 3.0)
                        // Fundamental modes also supply the mounting and neighbor losses.
                        // Upper modes receive less maintenance, preserving the requested root
                        // instead of letting the easiest isolated upper mode win the budget.
                        val driveRate = if (j == 0) LN1000 / loss[i] * 2.8 + loadingLoss[i] + 3 + field * 8
                            else LN1000 / loss[i] * 1.65 + field * 4
                        val gain = driveRate * spatial / (1 + own / targetEnergy)
                        force[i] = v[i] * gain * STEP
                        // A finite, smooth field-induced release starts the feedback network
                        // even with the performer's pull disabled. It drives the actual root
                        // modes and spends the same power budget; it is not an output layer.
                        val releaseTime = (sample - pullN) * STEP
                        if (p == 0 && releaseTime < .008 && field > 0)
                            force[i] += w0 * .04 * field * upperPull[j] * sin(PI * releaseTime / .008) * STEP
                        proposed += v[i] * force[i] + .5 * force[i] * force[i]
                    }
                }
                val g = if (proposed > available) available / proposed else 1.0
                for (i in 0 until BODY) {
                    val dv = force[i] * g
                    work += v[i] * dv + .5 * dv * dv
                    v[i] += dv
                }
                e = energy()
            }
            lastEnergy = e
            var direct = 0.0; var responding = 0.0; var cavity = 0.0
            for (i in 0 until BODY) {
                val value = pickup[i] * v[i]
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
            }
            phase += phaseStep
            if (phase > 2 * PI) phase -= 2 * PI
        }
        return Played(out, taps, if (held && seconds == null) n - periodN else -1)
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
