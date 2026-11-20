package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

/**
 * Candidate LIBRATION voices awaiting a listening verdict. Each reads something in the solved
 * trajectories that the kept cloud never uses:
 *
 * - CHIME reads *when* each body crosses its equilibrium's axis and strikes a bar at that moment.
 * - STRUM reads *when* each body gets clear of its equilibrium (the kept voices only fade a body
 *   that has left) and plucks a string as it goes, so the chord is rolled by the swarm.
 * - ROTOR turns the rotating frame back into the fixed one, so every body circles past a listener:
 *   a slow rotary tremolo and pitch bend, with each pipe's turn pushed and pulled by its libration.
 * - HOLLOW is the spare: the kept cloud mapping with odd-only partial tables, resting on L3.
 *
 * All four are finite only. They share the kept macros and trajectory cache and are rendered at the
 * output rate from band-limited sinusoids, so no partial is ever written above 12 kHz.
 */
internal object LibrationCandidates {
    private const val TOP_HZ = 12_000.0

    /** REGION, WANDER, NUDGE, SWARM, RESONANCE. */
    fun defaults(voice: LibrationVoice): FloatArray = when (voice) {
        LibrationVoice.CHIME -> floatArrayOf(0f, .60f, .30f, .70f, .55f)
        LibrationVoice.STRUM -> floatArrayOf(.5f, .45f, .60f, .80f, .50f)
        LibrationVoice.ROTOR -> floatArrayOf(0f, .50f, .15f, .60f, .35f)
        LibrationVoice.HOLLOW -> floatArrayOf(.75f, .45f, .20f, .70f, .40f)
        else -> error("$voice is a kept LIBRATION voice")
    }

    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Libration render cancelled")
    }
    /** Keeps the top of the keyboard from turning glassy: a partial fades out between 5 and 12 kHz. */
    private fun air(hz: Double): Double = if (hz >= TOP_HZ) 0.0 else 1 / (1 + (hz / 5200).pow(4))

    /** One decaying sinusoid starting at phase zero with a short rounded onset: no step, no click. */
    private fun ring(out: DoubleArray, start: Int, hz: Double, gain: Double, t60: Double, attack: Double) {
        if (gain == 0.0 || hz >= TOP_HZ || start >= out.size) return
        val w = 2 * PI * hz / Dsp.RATE
        val c = cos(w); val s = sin(w)
        val decay = exp(-6.907755278982137 / (t60 * Dsp.RATE))
        val rise = exp(-1 / (attack * Dsp.RATE))
        var re = gain; var im = 0.0; var closed = 1.0
        val end = min(out.size, start + (t60 * 1.7 * Dsp.RATE).toInt())
        for (i in start until end) {
            out[i] += im * (1 - closed)
            val r = (re * c - im * s) * decay
            im = (re * s + im * c) * decay
            re = r
            closed *= rise
        }
    }

    private class Lane(val particle: Libration.Particle, val rate: Int) {
        val last = particle.path.report.validSamples - 1
        fun at(q: Int, seconds: Double): Double {
            val position = (seconds * rate).coerceIn(0.0, (particle.descriptors.size - 1).toDouble())
            val i = min(position.toInt(), particle.descriptors.size - 2)
            val f = position - i
            return particle.descriptors[i][q] + f * (particle.descriptors[i + 1][q] - particle.descriptors[i][q])
        }
    }

    fun probe(voice: LibrationVoice, m: Map<String, Float>, midi: Int, velocity: Float,
        options: Libration.Options, population: Libration.Population, began: Long): Libration.Probe {
        val hz = Keys.midiHz(midi).toDouble()
        val v = velocity.toDouble()
        val resonance = m.getValue("RESONANCE").toDouble()
        val lanes = population.particles.map { Lane(it, population.rate) }
        val raw = if (v == 0.0) DoubleArray(((options.seconds + 1.0) * Dsp.RATE).roundToInt()) else when (voice) {
            LibrationVoice.CHIME -> chime(lanes, hz, v, resonance, options.seconds)
            LibrationVoice.STRUM -> strum(lanes, hz, v, resonance, options.seconds)
            LibrationVoice.ROTOR -> rotor(lanes, hz, v, resonance, options.seconds)
            LibrationVoice.HOLLOW -> hollow(lanes, hz, v, resonance, options.seconds)
            else -> error("$voice is a kept LIBRATION voice")
        }
        cancelled()
        var peak = 0.0; var squares = 0.0
        val output = FloatArray(raw.size) { i ->
            check(raw[i].isFinite()) { "LIBRATION non-finite audio" }
            peak = max(peak, abs(raw[i])); squares += raw[i] * raw[i]
            raw[i].toFloat()
        }
        // The last half second always closes smoothly, whatever is still ringing.
        Dsp.fadeTailCosine(output, .5f)
        val normalized = output.copyOf()
        Dsp.levelTo(normalized, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * v.pow(.7).toFloat(), .94f)
        val particles = population.particles
        val diagnostics = Libration.Diagnostics(peak, sqrt(squares / raw.size),
            particles.maxOf { it.path.report.jacobiDrift }, particles.count { it.path.report.termination != "horizon" },
            particles.size, particles.sumOf { it.weight }, particles.maxOf { p -> p.descriptors.maxOf { abs(it[0]) } } * .3,
            (System.nanoTime() - began) / 1e6, particles.sumOf { it.path.report.work })
        return Libration.Probe(Snip(output, 1, Dsp.RATE), Snip(normalized, 1, Dsp.RATE), population, diagnostics)
    }

    // ---- CHIME -----------------------------------------------------------------------------

    private val chimeBars = intArrayOf(1, 2, 3, 1, 4, 2, 6, 1, 3, 2, 4, 1, 6, 3, 2, 8)

    /** Times at which a body crosses the axis through its equilibrium, with the speed it had there. */
    internal fun crossings(particle: Libration.Particle, rate: Int, until: Double): List<Pair<Double, Double>> {
        val d = particle.descriptors
        val events = ArrayList<Pair<Double, Double>>()
        var lastTime = -1.0
        for (i in 1 until min(d.size, particle.path.report.validSamples)) {
            val a = d[i - 1][1]; val b = d[i][1]
            if (a == 0.0 || a * b > 0) continue
            val time = (i - 1 + a / (a - b)) / rate
            if (time < .06 || time >= until) continue
            // A body that hovers on the axis strikes once, not as a rattle.
            if (time - lastTime < .35) continue
            lastTime = time
            events += time to d[i][2] * d[i][4]
            if (events.size == 8) break
        }
        return events
    }

    private fun chime(lanes: List<Lane>, hz: Double, v: Double, resonance: Double, seconds: Double): DoubleArray {
        val tail = 1.6 + 4.0 * resonance
        val out = DoubleArray(((seconds + tail) * Dsp.RATE).roundToInt())
        val t60 = .8 + 4.4 * resonance
        val mallet = .0065 - .0045 * v
        // A harder mallet lifts the bar's upper modes: tone, not only level.
        val upper = .25 + 1.35 * v
        val norm = 1 / sqrt(lanes.sumOf { it.particle.weight * it.particle.weight }.coerceAtLeast(1e-12))
        fun strike(lane: Lane, seconds: Double, strength: Double, first: Boolean) {
            cancelled()
            val p = lane.particle
            val bar = chimeBars[p.id % chimeBars.size]
            // The body's own offset when struck leaves each bar a few cents from its neighbours.
            val f = hz * bar * 2.0.pow(.3 * lane.at(0, seconds) / 1200)
            val gain = strength * p.weight * norm / bar.toDouble().pow(if (first) .9 else .6)
            val start = (seconds * Dsp.RATE).roundToInt()
            val length = t60 / bar.toDouble().pow(.35)
            ring(out, start, f, gain * air(f), length, mallet)
            ring(out, start, f * 2.756, gain * .34 * upper * air(f * 2.756), length * .42, mallet)
            ring(out, start, f * 5.404, gain * .15 * upper * upper * air(f * 5.404), length * .2, mallet)
        }
        // The played note: every bar at once, then the root bar on its own so the pitch is plain.
        for (lane in lanes) strike(lane, 0.0, .5 * v, true)
        ring(out, 0, hz, .42 * v, t60, mallet)
        ring(out, 0, hz * 2.756, .10 * v * upper, t60 * .42, mallet)
        // Afterwards each body rings its bar again whenever it crosses its axis.
        for (lane in lanes) for ((time, speed) in crossings(lane.particle, lane.rate, seconds))
            strike(lane, time, v * (.22 + .34 * speed), false)
        for (i in out.indices) out[i] *= .4
        return out
    }

    // ---- STRUM -----------------------------------------------------------------------------

    private val strumStrings = intArrayOf(2, 3, 4, 1, 6, 2, 8, 3, 4, 2, 6, 1, 3, 8, 4, 2)

    /**
     * When each body gets clear of its equilibrium (a fiftieth of the family's neighbourhood), counted
     * from the first one to go. On a saddle NUDGE's kick decides it: a strong kick sends the bodies
     * off almost together, a weak one lets them go one by one over a third of a second. On a triangle
     * they swing out and back, so the same reading spreads over seconds. Null: never.
     */
    internal fun leavings(particles: List<Libration.Particle>, rate: Int, until: Double): List<Double?> {
        val times = particles.map { p ->
            val e = LibrationTrajectory.equilibrium(p.family)
            val clear = .02 * LibrationTrajectory.templates.getValue(p.family).neighborhood
            val states = p.path.states
            var found: Double? = if (LibrationTrajectory.distance(states[0], e) >= clear) 0.0 else null
            if (found == null) for (i in 1 until p.path.report.validSamples) {
                val b = LibrationTrajectory.distance(states[i], e)
                if (b < clear) continue
                val a = LibrationTrajectory.distance(states[i - 1], e)
                found = (i - 1 + (if (b > a) ((clear - a) / (b - a)).coerceIn(0.0, 1.0) else 1.0)) / rate
                break
            }
            found
        }
        val first = times.filterNotNull().minOrNull() ?: return times
        return times.map { t -> t?.let { it - first }?.takeIf { it < until } }
    }

    private fun strum(lanes: List<Lane>, hz: Double, v: Double, resonance: Double, seconds: Double): DoubleArray {
        val tail = 1.6 + 4.0 * resonance
        val out = DoubleArray(((seconds + tail) * Dsp.RATE).roundToInt())
        val t60 = 1.1 + 4.6 * resonance
        // A harder pluck sits nearer the bridge and keeps more of its upper harmonics.
        val position = .24 - .13 * v
        val slope = 1.75 - .65 * v
        val finger = .0065 - .0040 * v
        val norm = 1 / sqrt(lanes.sumOf { it.particle.weight * it.particle.weight }.coerceAtLeast(1e-12))
        fun pluck(f: Double, start: Double, gain: Double) {
            cancelled()
            val first = (start * Dsp.RATE).roundToInt()
            var n = 1
            while (n <= 18 && n * f < TOP_HZ) {
                val a = sin(n * PI * position) / n.toDouble().pow(slope)
                ring(out, first, n * f, gain * a * air(n * f), t60 / (1 + .32 * (n - 1)) / (f / hz).pow(.3), finger)
                n++
            }
        }
        pluck(hz, 0.0, .85 * v)
        val leaving = leavings(lanes.map { it.particle }, lanes.first().rate, seconds - .02)
        for ((index, lane) in lanes.withIndex()) {
            val p = lane.particle
            val string = strumStrings[p.id % strumStrings.size]
            val f = hz * string * 2.0.pow(.25 * lane.at(0, 0.0) / 1200)
            val left = leaving[index]
            // A body that never leaves is plucked with the note; one that leaves is plucked as it goes.
            val gain = p.weight * norm * v / string.toDouble().pow(.55)
            if (left == null) pluck(f, 0.0, .30 * gain)
            else pluck(f, left, gain * (.50 + .35 * lane.at(2, left)))
        }
        for (i in out.indices) out[i] *= .35
        return out
    }

    // ---- ROTOR -----------------------------------------------------------------------------

    private val rotorPipes = intArrayOf(1, 2, 3, 1, 4, 2, 6, 1, 3, 8, 2, 4, 1, 6, 2, 3)
    private const val LISTENER = 3.0

    private fun rotor(lanes: List<Lane>, hz: Double, v: Double, resonance: Double, seconds: Double): DoubleArray {
        val release = min(.65, seconds * .325)
        val fade = .35 + 1.9 * resonance
        val out = DoubleArray(((seconds + fade + .1) * Dsp.RATE).roundToInt())
        val opening = .075 - .060 * v
        val tilt = 1.15 - .65 * v
        val norm = .5 * v / sqrt(lanes.sumOf { it.particle.weight * it.particle.weight }.coerceAtLeast(1e-12))
        for (lane in lanes) {
            cancelled()
            val p = lane.particle
            val pipe = rotorPipes[p.id % rotorPipes.size]
            val f0 = hz * pipe
            if (f0 >= TOP_HZ) continue
            val clock = LibrationTrajectory.templates.getValue(p.family).clock
            val e = LibrationTrajectory.equilibrium(p.family)
            val rest = atan2(e.y, e.x)
            // The tall pipes sit in the horn and swing wide; the low ones barely move, as in a rotary cabinet.
            val depth = .40 + .60 * min(1.0, (pipe - 1) / 5.0)
            val bend = 5.0 * depth * min(1.0, clock / 18.0)
            val gain = p.weight * norm * air(f0) / pipe.toDouble().pow(tilt)
            var phase = Random(Dsp.seedFor("LIBRATION_ROTOR", p.family.name, p.id)).nextDouble()
            for (i in out.indices) {
                val t = i.toDouble() / Dsp.RATE
                val held = min(t, seconds)
                // Frame rotation, undone: the body's bearing from a listener who does not turn with it.
                // Its libration (the kept x reading) pushes it ahead of and behind the turning frame.
                val state = p.path.states[min((held * lane.rate).toInt(), lane.last)]
                val bearing = clock * t + rest + 1.4 * lane.at(1, held) + .5 * lane.at(5, held)
                val radius = hypot(state.x, state.y).coerceIn(.5, 1.5)
                val distance = sqrt(radius * radius + LISTENER * LISTENER - 2 * radius * LISTENER * cos(bearing))
                val near = 1 - depth * (1 - (LISTENER - radius) / distance)
                val supply = smooth(t / opening) * (if (t < seconds - release) 1.0
                    else (1 - smooth((t - seconds + release) / (release + fade))).pow(2))
                val bright = lane.at(2, held)
                val tone = sin(2 * PI * phase) + (.05 + .16 * bright * v) * sin(4 * PI * phase) * air(2 * f0)
                out[i] += gain * near * supply * lane.at(4, held).coerceAtLeast(.15) * tone
                phase += f0 * 2.0.pow(bend * sin(bearing) / 1200) / Dsp.RATE
                if (phase >= 1) phase -= 1.0
            }
        }
        // Touch: a hard key adds a short second-harmonic ping, as an organ's percussion stop does.
        ring(out, 0, hz * 2, .16 * max(0.0, v - .45) * air(hz * 2), .45, .002)
        ring(out, 0, hz * 3, .08 * max(0.0, v - .45) * air(hz * 3), .30, .002)
        return out
    }

    // ---- HOLLOW (spare) --------------------------------------------------------------------

    private val hollowPipes = intArrayOf(1, 3, 1, 5, 3, 1, 7, 3, 5, 1, 3, 9, 1, 5, 3, 7)

    private fun hollow(lanes: List<Lane>, hz: Double, v: Double, resonance: Double, seconds: Double): DoubleArray {
        val release = min(.65, seconds * .325)
        val fade = .35 + 1.9 * resonance
        val out = DoubleArray(((seconds + fade + .1) * Dsp.RATE).roundToInt())
        val touch = v - .8
        val norm = .34 / sqrt(lanes.sumOf { it.particle.weight * it.particle.weight }.coerceAtLeast(1e-12))
        fun supply(t: Double) = smooth(t / (.060 - .040 * v)) * (if (t < seconds - release) 1.0
            else (1 - smooth((t - seconds + release) / (release + fade))).pow(2))
        for (lane in lanes) {
            cancelled()
            val p = lane.particle
            val pipe = hollowPipes[p.id % hollowPipes.size]
            if (hz * pipe >= TOP_HZ) continue
            var phase = Random(Dsp.seedFor("LIBRATION_HOLLOW", p.family.name, p.id)).nextDouble()
            var breath = Random(Dsp.seedFor("LIBRATION_HOLLOW_BREATH", p.family.name, p.id)).nextDouble()
            var envelope = 0.0
            val gain = p.weight * norm / pipe.toDouble().pow(.75)
            val a3 = air(3 * hz * pipe); val a5 = air(5 * hz * pipe)
            val coefficient = 1 - exp(-1 / (Dsp.RATE * (.030 - .012 * v)))
            for (i in out.indices) {
                val t = i.toDouble() / Dsp.RATE
                val held = min(t, seconds)
                val bright = lane.at(2, held)
                val upper = (.25 + 1.2 * bright) * (1 + .9 * touch)
                // A slower, rounder breath than the kept cloud's pulses, at the same trajectory-set rate.
                val pulse = .5 + .5 * sin(2 * PI * breath)
                envelope += coefficient * (supply(t) * lane.at(4, held) * (.45 + .55 * pulse) - envelope)
                val x = 2 * PI * phase
                out[i] += gain * air(hz * pipe) * envelope *
                    (sin(x) + .33 * upper * a3 * sin(3 * x) + .16 * upper * a5 * sin(5 * x))
                phase += hz * pipe * 2.0.pow(lane.at(0, held) / 1200) / Dsp.RATE
                if (phase >= 1) phase -= 1.0
                breath += .45 * lane.at(3, held) / Dsp.RATE
                if (breath >= 1) breath -= 1.0
            }
        }
        var phase = 0.0
        val third = .30 * (1 + .9 * touch) * air(3 * hz); val fifth = .13 * (1 + 1.6 * touch) * air(5 * hz)
        for (i in out.indices) {
            val x = 2 * PI * phase
            out[i] += .20 * supply(i.toDouble() / Dsp.RATE) * (sin(x) + third * sin(3 * x) + fifth * sin(5 * x))
            phase += hz / Dsp.RATE
            if (phase >= 1) phase -= 1.0
        }
        for (i in out.indices) out[i] *= v
        return out
    }
}
