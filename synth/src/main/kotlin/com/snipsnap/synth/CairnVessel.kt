package com.snipsnap.synth

import kotlin.math.*
import kotlin.random.Random

/**
 * VESSEL: a stone dropped into a hollow conical bowl. It rolls around the
 * wall, loses energy to rolling resistance (FRICTION), and spirals inward. On
 * a cone a circular orbit's angular speed is sqrt(g·tan(slope)/r), so as the
 * circles narrow they also speed up: the rhythm comes from the motion itself.
 *
 * The law is an averaged, literal energy law, not a 2-D simulation: the stone
 * is assumed to stay on a nearly circular orbit, with energy 1.7·m·g·tanA·r
 * (a rolling sphere's 7/5 kinetic factor plus height). Constant rolling
 * resistance then makes sqrt(r) fall linearly in time, so radius, speed and
 * orbit angle all have closed forms. Bumps on the wall (GROOVE) knock once per
 * pass and take a small share of the energy. At the flat bottom the stone
 * rolls to rest. SLOPE is the wall's steepness, WEIGHT the stone's size.
 *
 * The audio is a one-way readout into a bowl: paired wall modes (the slow
 * beating of a real bowl's split modes) on Rayleigh's free-rim ratios, plus
 * one low air resonance an octave below the note, whose strength and ring
 * BODY sets. Rolling drives the bank with noise, low-passed by the rolling
 * speed and swelling once per orbit. HOLD's top step keeps the stone on a
 * steady orbit with a whole number of turns in two seconds, so the loop
 * is a true repeat. Constants are design shapes, not measured bowl data.
 */
internal object CairnVessel {
    const val PERIOD = 2.0
    private const val CONTROL_RATE = 2000
    private const val G = 9.81
    private const val INERTIA = 1.7
    private const val RIM = .12
    private const val DROP_AT = .018
    private const val MAX_EVENTS = 512
    const val DROP = 0
    const val KNOCK = 1

    private class Rim(val angle: DoubleArray, val share: DoubleArray)

    private fun rim(groove: Double): Rim {
        val random = Random(Dsp.seedFor("CAIRN", 7, "VESSEL", "rim"))
        val count = 1 + (4 * groove).roundToInt()
        val angle = DoubleArray(count) { 2 * PI * (it + .7 * random.nextDouble()) / count }
        val share = DoubleArray(count) { (.015 + .06 * groove) * (.6 + .8 * random.nextDouble()) }
        return Rim(angle, share)
    }

    fun bottomRadius(weight: Double) = .012 + .010 * weight

    private fun silent(powered: Boolean) = Cairn.Motion(emptyList(), listOf(Cairn.State(0.0, 0.0, 0.0,
        listOf(0.0, 0.0, 0.0, 0.0, 0.0))), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        if (powered) PERIOD else 0.0, if (powered) 1 else 0, 0.0,
        orbit = Cairn.Orbit(CONTROL_RATE, listOf(0.0, 0.0), listOf(0.0, 0.0), 0.0))

    fun motion(weight: Double, slope: Double, groove: Double, friction: Double, velocity: Double,
               powered: Boolean): Cairn.Motion {
        if (velocity <= 0.0) return silent(powered)
        val mass = .55 + 1.3 * weight
        val tanA = .35 + 1.3 * slope
        val gT = G * tanA
        val resistance = .05 + .15 * friction
        val bottom = bottomRadius(weight)
        val rim = rim(groove)
        fun orbitEnergy(u: Double) = INERTIA * mass * gT * u * u
        if (powered) return held(mass, gT, resistance, bottom, rim, velocity)
        // d sqrt(r)/dt = -kappa: constant rolling resistance on a circular orbit.
        val kappa = resistance / (2 * INERTIA) * sqrt(G / tanA)
        val root = sqrt(gT)
        val ub = sqrt(bottom)
        val drop = .25 * .5 * mass * (.8 * velocity).pow(2)
        var u = sqrt(bottom + (RIM - bottom) * (.25 + .75 * velocity))
        var theta = 0.0
        var t = DROP_AT
        val initial = orbitEnergy(u) + drop
        var rolled = 0.0; var knocked = 0.0
        val events = mutableListOf(Cairn.Event(DROP_AT, DROP, -1, drop, .8 * velocity, 0.0))
        // Segments of the closed-form spiral: start time, sqrt(r), unwrapped angle.
        val segments = mutableListOf(doubleArrayOf(t, u, theta))
        while (u > ub) {
            check(events.size <= MAX_EVENTS) { "CAIRN VESSEL knock cap exceeded" }
            var target = Double.POSITIVE_INFINITY; var which = -1
            for (j in rim.angle.indices) {
                var a = rim.angle[j] + 2 * PI * floor((theta - rim.angle[j]) / (2 * PI))
                while (a <= theta + 1e-12) a += 2 * PI
                if (a < target) { target = a; which = j }
            }
            // d theta/dt = root/u with u falling linearly, so theta = theta_k + root/kappa ln(u_k/u).
            val uc = u * exp(-kappa * (target - theta) / root)
            val end = max(uc, ub)
            val dt = (u - end) / kappa
            rolled += resistance * mass * G * root * (u + end) / 2 * dt
            theta += root / kappa * ln(u / end)
            t += dt; u = end
            if (uc <= ub) break
            // A knock never takes the stone below the bottom's own orbit energy.
            val work = min(rim.share[which] * .5 * mass * gT * u * u, orbitEnergy(u) - orbitEnergy(ub))
            knocked += work
            events += Cairn.Event(t, KNOCK, which, work, root * u, 0.0)
            u = max(ub, sqrt((orbitEnergy(u) - work) / (INERTIA * mass * gT)))
            segments += doubleArrayOf(t, u, theta)
        }
        val wallEnd = t
        // The flat bottom: rolling resistance brings the stone to rest, still circling.
        val speedAtBottom = root * ub
        val deceleration = resistance * G / 1.4
        val settle = speedAtBottom / deceleration
        val settleLoss = .7 * mass * speedAtBottom * speedAtBottom
        val end = wallEnd + settle
        // At rest the stone keeps the bottom's height energy.
        val resting = mass * gT * bottom
        check(abs(initial - rolled - knocked - settleLoss - drop - resting) <= 1e-9 * initial) {
            "CAIRN VESSEL work ledger does not close"
        }
        val samples = (end * CONTROL_RATE).roundToInt() + 2
        val speed = ArrayList<Double>(samples); val angle = ArrayList<Double>(samples)
        val states = mutableListOf<Cairn.State>()
        var segment = 0
        for (i in 0 until samples) {
            val time = i.toDouble() / CONTROL_RATE
            var v: Double; var a: Double
            if (time < DROP_AT) { v = 0.0; a = 0.0 }
            else if (time < wallEnd) {
                while (segment + 1 < segments.size && segments[segment + 1][0] <= time) segment++
                val (start, uk, thetaK) = segments[segment].let { Triple(it[0], it[1], it[2]) }
                val now = uk - kappa * (time - start)
                v = root * now; a = thetaK + root / kappa * ln(uk / now)
            } else {
                val tau = min(time - wallEnd, settle)
                v = max(0.0, speedAtBottom - deceleration * tau)
                a = theta + (speedAtBottom * tau - .5 * deceleration * tau * tau) / bottom
            }
            speed += v; angle += a
            if (i % 20 == 0) {
                val r = if (v > 0 && time < wallEnd) (v / root).pow(2) else bottom
                states += Cairn.State(time, if (time < wallEnd) INERTIA * mass * v * v else .7 * mass * v * v,
                    0.0, listOf(r, if (r > 0) v / r else 0.0, a, v, 0.0))
            }
        }
        return Cairn.Motion(events.toList(), states, initial, resting, knocked + drop, rolled + settleLoss, 0.0,
            settleLoss, orbit = Cairn.Orbit(CONTROL_RATE, speed, angle, end))
    }

    private fun held(mass: Double, gT: Double, resistance: Double, bottom: Double, rim: Rim,
                     velocity: Double): Cairn.Motion {
        val natural = bottom + (RIM - bottom) * (.25 + .75 * velocity) * .55
        var turns = max(1, (sqrt(gT / natural) * PERIOD / (2 * PI)).roundToInt())
        fun radius(n: Int) = gT / (2 * PI * n / PERIOD).pow(2)
        while (radius(turns) < 1.5 * bottom && turns > 1) turns--
        while (radius(turns) > RIM) turns++
        val r = radius(turns)
        val omega = 2 * PI * turns / PERIOD
        val v = sqrt(gT * r)
        val events = ArrayList<Cairn.Event>()
        for (q in 0 until turns) for (j in rim.angle.indices) {
            val work = rim.share[j] * .5 * mass * v * v
            events += Cairn.Event((2 * PI * q + rim.angle[j]) / omega, KNOCK, j, work, v, v)
        }
        events.sortBy { it.seconds }
        val knocked = events.sumOf { it.work }
        val rolled = resistance * mass * G * v * PERIOD
        val energy = INERTIA * mass * gT * r
        val samples = (PERIOD * CONTROL_RATE).roundToInt() + 1
        // A small drive tops up exactly what rolling and the knocks remove.
        return Cairn.Motion(events.toList(), listOf(Cairn.State(0.0, energy, 0.0, listOf(r, omega, 0.0, v, 0.0))),
            energy, energy, knocked, rolled, 0.0, 0.0, knocked + rolled, PERIOD, 1, 0.0, events.toList(),
            orbit = Cairn.Orbit(CONTROL_RATE, List(samples) { v }, List(samples) { omega * it / CONTROL_RATE }, PERIOD))
    }

    private fun wallRing(m: Map<String, Float>) = (.9 + 1.4 * m.getValue("BODY")) * (1.0 + .6 * m.getValue("HOLD"))
    private fun airRing(m: Map<String, Float>) = (.35 + 1.5 * m.getValue("BODY")) * (1.0 + .6 * m.getValue("HOLD"))

    fun finiteFrames(motion: Cairn.Motion, m: Map<String, Float>): Int {
        val end = motion.orbit?.endSeconds ?: 0.0
        return ceil(max(.12, end + wallRing(m)) * Cairn.RATE).toInt()
    }

    fun inspect(m: Map<String, Float>, hz: Double, velocity: Float, startAtContact: Boolean,
                options: Cairn.Options, motion: Cairn.Motion, cacheHit: Boolean, mechanicsMs: Double): Cairn.Render {
        val started = System.nanoTime()
        val orbit = requireNotNull(motion.orbit)
        val rate = Cairn.RATE * options.oversample
        val held = Cairn.isLoop(m)
        val period = (PERIOD * Cairn.RATE).roundToInt()
        val periodInternal = period * options.oversample
        val body = m.getValue("BODY").toDouble()
        // Wall modes in near-degenerate pairs (Rayleigh's free-rim bowl ratios),
        // then the air resonance an octave below the note.
        val ratios = doubleArrayOf(1.0, 1.0035, 2.83, 2.838, 5.42, 5.433, 8.77, .5)
        val wall = wallRing(m)
        val bank = Modes.Bank(8, rate)
        for (i in ratios.indices) bank.tune(i, min(12_000.0, hz * ratios[i]), when (i) {
            0, 1 -> wall; 2, 3 -> .55 * wall; 4, 5 -> .30 * wall; 6 -> .18 * wall; else -> airRing(m)
        })
        fun audible(weights: DoubleArray) = bank.participation(DoubleArray(8) { i ->
            if (hz * ratios[i] >= 12_000) 0.0 else weights[i]
        })
        val knockContact = audible(doubleArrayOf(1.0, .8, .55, .5, .3, .28, .15, .2 + .3 * body))
        val rollContact = audible(doubleArrayOf(.55, .45, .35, .3, .15, .12, .05, .25 + .9 * body))
        val pickup = audible(doubleArrayOf(1.0, 1.0, .7, .7, .4, .4, .25, .6 + .8 * body))
        val forceFrames = if (held) periodInternal else finiteFrames(motion, m) * options.oversample
        val knocks = FloatArray(forceFrames)
        val rolling = FloatArray(forceFrames)
        val width = (.0004 + .0012 * m.getValue("WEIGHT")) * (1.35 - .65 * velocity)
        val pulseFrames = max(8, (width * rate).roundToInt())
        val kernel = DoubleArray(pulseFrames) { .5 - .5 * cos(2 * PI * (it + .5) / pulseFrames) }
        val area = kernel.sum() / rate
        for (event in motion.events) {
            val first = (event.seconds * rate).roundToInt()
            val impulse = .12 * sqrt(event.work)
            for (j in kernel.indices) {
                val i = if (held) (first + j).mod(periodInternal) else first + j
                if (i in knocks.indices) knocks[i] += (impulse * kernel[j] / area).toFloat()
            }
        }
        // Rolling: seeded noise, darker when slow, swelling once per orbit.
        val reference = sqrt(G * (.35 + 1.3 * m.getValue("SLOPE")) * RIM)
        val rollFrames = if (held) periodInternal else min(forceFrames, (orbit.endSeconds * rate).roundToInt() + 1)
        val noise = Dsp.Noise(Dsp.seedFor("CAIRN", 7, "VESSEL", "roll"))
        val source = FloatArray(rollFrames) { noise.next() }
        val low = Dsp.OnePole(rate); val lower = Dsp.OnePole(rate)
        repeat(if (held) 2 else 1) { pass ->
            for (i in 0 until rollFrames) {
                val x = i.toDouble() * orbit.rate / rate
                val k = x.toInt().coerceAtMost(orbit.speed.size - 2)
                val mix = (x - k).coerceIn(0.0, 1.0)
                val v = orbit.speed[k] + mix * (orbit.speed[k + 1] - orbit.speed[k])
                val a = orbit.angle[k] + mix * (orbit.angle[k + 1] - orbit.angle[k])
                val cutoff = (250 + 2200 * min(1.0, v / reference)).toFloat()
                val shaped = lower.lp(low.lp(source[i], cutoff), cutoff)
                if (pass == (if (held) 1 else 0)) rolling[i] = (240.0 * v.pow(1.5) * (1 + .35 * cos(a)) * shaped).toFloat()
            }
        }
        fun drive(i: Int) {
            if (knocks[i] != 0f) bank.drive(knockContact, knocks[i].toDouble())
            if (rolling[i] != 0f) bank.drive(rollContact, rolling[i].toDouble())
        }
        if (held) {
            for (i in 0 until periodInternal) { drive(i); bank.step() }
            bank.closeUncoupledCycle(periodInternal)
        }
        val prefix = if (held) period else 0
        val frames = if (held) 2 * period + 1024 else forceFrames / options.oversample
        val raw = FloatArray(frames * options.oversample)
        for (i in raw.indices) {
            val f = if (held) i % periodInternal else i
            if (f < forceFrames) drive(f)
            bank.step()
            raw[i] = (bank.velocityAlong(pickup) + if (f < forceFrames) .0002 * rolling[f] else 0.0).toFloat()
        }
        return CairnStone.finish(raw, rate, options, held, prefix, period, velocity, startAtContact,
            motion, cacheHit, mechanicsMs, started, hz, "VESSEL")
    }
}
