package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

/**
 * GRIND: a stone dragged across a rough bed by a spring, like pulling it with
 * a rubber band. The mechanics are a literal, normalised stick-slip law:
 *
 * - The spring's far end moves at a steady speed (SLOPE, scaled by velocity).
 * - While the stone sticks, the spring stretches until its pull beats the
 *   bed's grip at that spot. The grip varies smoothly along the bed, and
 *   seeded notches (GROOVE sets how many and how deep) grip much harder.
 * - While it slips, kinetic friction (FRICTION) opposes it. Each slip is an
 *   exact solution of a harmonic oscillator about a moving equilibrium, so
 *   no stepping error enters the timing.
 * - A slip that reaches a notch slowly is caught there; a fast one loses part
 *   of its energy to a tick and runs on. The bed is a ratchet: steep faces
 *   take any backward pull, so the stone never slides back.
 *
 * Because the grip is irregular, slips bunch up and stall instead of settling
 * into a steady squeak (the regular stick-slip of ARCO's bow). The work
 * ledger closes: driver work in = elastic and kinetic energy + friction + catches.
 * HOLD's top step drags the spring around a circular bed whose lap takes
 * exactly two seconds. One deep notch per lap catches every stone that
 * reaches it, so each lap restarts from the same state and repeats exactly.
 * The audio is a one-way readout: catches strike the stone's modes, and every
 * slip is also a short band-limited noise burst, as long as the slip itself.
 * That burst also reaches the output directly, through a two-pole lowpass
 * whose cutoff FRICTION sets (2.5-5.5 kHz), so the scrape has grit without hiss.
 * Constants are design shapes, not measured stone or bed data.
 */
internal object CairnGrind {
    const val PERIOD = 2.0
    private const val MAX_EVENTS = 512
    private const val MAX_SEGMENTS = 200_000
    private const val CELL = .012
    private const val TICK_SHARE = .3
    /** The directly heard scrape: two-pole lowpass at 2.5-5.5 kHz (FRICTION) and its level (round 2; the owner chose G9's .008). */
    private const val AIR_BASE_HZ = 2500.0
    private const val AIR_SPAN_HZ = 3000.0
    internal const val DIRECT_GAIN = .008
    private const val LAPS = 12
    const val CATCH = 0
    const val SLIP = 1
    const val TICK = 2

    private class Bed(val position: DoubleArray, val extra: DoubleArray, val capture: DoubleArray,
                      val rough: DoubleArray, val cell: Double, val lap: Double) {
        fun notch(k: Int): Double = if (lap > 0) position[k % position.size] + (k / position.size) * lap
            else if (k < position.size) position[k] else Double.POSITIVE_INFINITY
        fun extraAt(k: Int) = extra[k % extra.size]
        fun captureAt(k: Int) = capture[k % capture.size]
        fun grip(x: Double): Double {
            val u = x / cell
            val i = floor(u).toInt()
            val f = (u - i).let { it * it * (3 - 2 * it) }
            fun at(j: Int) = if (lap > 0) rough[j.mod(rough.size)] else rough[j.coerceIn(0, rough.size - 1)]
            return at(i) + (at(i + 1) - at(i)) * f
        }
    }

    private fun bed(groove: Double, speed: Double, drag: Double, powered: Boolean): Bed {
        val random = Random(Dsp.seedFor("CAIRN", 6, "GRIND", "bed"))
        val spacing = .05 + .08 * (1 - groove)
        fun depth() = 1600 * (.25 + .75 * groove) * (.3 + .7 * random.nextDouble())
        fun capture() = (5 + 7 * groove) * (.5 + random.nextDouble())
        if (powered) {
            val lap = speed * PERIOD
            val n = max(3, (lap / spacing).roundToInt())
            val gaps = DoubleArray(n) { .35 + 1.3 * random.nextDouble() }
            val total = gaps.sum()
            var sum = 0.0
            val position = DoubleArray(n) { j -> (lap * sum / total).also { sum += gaps[j] } }
            // Notch 0 is the lap's one deep catch: it holds every stone that reaches
            // it. Each lap therefore restarts from the same caught state, so
            // the motion repeats exactly once per lap instead of drifting.
            val extra = DoubleArray(n) { if (it == 0) 1600 * (.25 + .75 * groove) else depth() }
            val capture = DoubleArray(n) { if (it == 0) Double.POSITIVE_INFINITY else capture() }
            val cells = max(8, (lap / CELL).roundToInt())
            return Bed(position, extra, capture, DoubleArray(cells) { .45 + 1.1 * random.nextDouble() }, lap / cells, lap)
        }
        val travel = speed * drag + .5
        val position = ArrayList<Double>(); val extra = ArrayList<Double>(); val capture = ArrayList<Double>()
        var p = 0.0
        while (p <= travel) {
            position += p; extra += depth(); capture += capture()
            p += spacing * (.35 + 1.3 * random.nextDouble())
        }
        val cells = ceil(travel / CELL).toInt() + 2
        return Bed(position.toDoubleArray(), extra.toDoubleArray(), capture.toDoubleArray(),
            DoubleArray(cells) { .45 + 1.1 * random.nextDouble() }, CELL, 0.0)
    }

    fun dragSeconds(slope: Double) = 1.0 + .6 * (1 - slope)

    private fun silent(powered: Boolean) = Cairn.Motion(emptyList(), listOf(Cairn.State(0.0, 0.0, 0.0,
        listOf(0.0, 0.0, 0.0, 0.0, 0.0))), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        if (powered) PERIOD else 0.0, if (powered) 1 else 0, 0.0)

    private fun sameLap(a: List<Cairn.Event>, b: List<Cairn.Event>) = a.size == b.size && a.zip(b).all { (x, y) ->
        x.stone == y.stone && x.groove == y.groove && abs(x.seconds - y.seconds) < 1e-7 &&
            abs(x.work - y.work) <= 1e-8 * max(1.0, abs(x.work)) && abs(x.slipSeconds - y.slipSeconds) < 1e-7
    }

    fun motion(weight: Double, slope: Double, groove: Double, friction: Double, velocity: Double,
               powered: Boolean): Cairn.Motion {
        val mass = .55 + 1.3 * weight
        val w = 2 * PI * (52 - 24 * weight)
        val w2 = w * w
        val speed = (.35 + .9 * slope) * velocity
        if (speed <= 0.0) return silent(powered)
        // Per-unit-mass grip levels: kinetic, the smooth bed's static excess, notches on top.
        val kinetic = 260 * (.5 + friction)
        val smooth = 900 + 700 * (1 - groove)
        val drag = dragSeconds(slope)
        val bed = bed(groove, speed, drag, powered)

        var t = 0.0; var x = 0.0; var v = 0.0
        var stuck = true
        var threshold = kinetic + smooth * bed.grip(0.0) + bed.extraAt(0)
        var next = 1
        var slipStart = 0.0; var slipX = 0.0
        var supplied = 0.0; var frictionWork = 0.0; var caught = 0.0
        val events = ArrayList<Cairn.Event>()
        val states = mutableListOf(Cairn.State(0.0, 0.0, 0.0, listOf(0.0, 0.0, 0.0, 0.0, 0.0)))
        fun driver(time: Double) = speed * if (powered) time else min(time, drag)
        fun energy() = .5 * mass * v * v + .5 * mass * w2 * (driver(t) - x).pow(2)
        // Held runs: the state and running totals at every lap boundary.
        val boundaryEnergy = ArrayList<Double>(); val boundaryState = ArrayList<DoubleArray>()
        val boundaryTotals = ArrayList<DoubleArray>()
        var boundary = PERIOD
        var largestIncrease = 0.0
        var segments = 0
        fun mark() {
            boundaryEnergy += energy()
            boundaryState += doubleArrayOf(driver(t) - x, v, if (stuck) 1.0 else 0.0, threshold,
                (next % bed.position.size).toDouble())
            boundaryTotals += doubleArrayOf(supplied, frictionWork + caught)
        }
        if (powered) mark()
        while (true) {
            check(++segments <= MAX_SEGMENTS) { "CAIRN GRIND segment cap exceeded" }
            check(events.size <= MAX_EVENTS * (if (powered) LAPS else 1)) { "CAIRN GRIND event cap exceeded" }
            if (powered && t >= boundary - 1e-12) {
                t = boundary; mark(); boundary += PERIOD
                if (boundaryEnergy.size > LAPS) break
            }
            val before = energy()
            if (stuck) {
                val moving = powered || t < drag
                val stretch = driver(t) - x
                if (!moving) { if (w2 * stretch <= threshold) break }
                val release = if (w2 * stretch > threshold) t else (x + threshold / w2) / speed
                if (!powered && release > drag) break
                val until = if (powered) min(release, boundary) else release
                val dt = until - t
                supplied += mass * w2 * speed * (stretch * dt + speed * dt * dt / 2)
                t = until
                if (until == release) { stuck = false; v = 0.0; slipStart = t; slipX = x }
                largestIncrease = max(largestIncrease, energy() - before)
                continue
            }
            val moving = powered || t < drag
            val sp = if (moving) speed else 0.0
            val t0 = t; val d0 = driver(t0)
            val y0 = x - (d0 - kinetic / w2); val y1 = v - sp
            val r = hypot(y1, y0 * w)
            val phase = atan2(y0 * w, y1)
            var stop = Double.POSITIVE_INFINITY
            if (r > sp + 1e-12) {
                var c = (acos(-sp / r) - phase).mod(2 * PI)
                if (c < 1e-12) c += 2 * PI
                stop = c / w
            }
            var limit = stop
            if (!powered && moving) limit = min(limit, drag - t0)
            if (powered) limit = min(limit, boundary - t0)
            check(limit.isFinite()) { "CAIRN GRIND slip has no end" }
            fun position(tau: Double) = d0 + sp * tau - kinetic / w2 + y0 * cos(w * tau) + y1 / w * sin(w * tau)
            fun speedAt(tau: Double) = sp - y0 * w * sin(w * tau) + y1 * cos(w * tau)
            fun advance(tau: Double) {
                // Closed-form driver work and friction work over this exact segment.
                val area = y0 * sin(w * tau) / w + y1 / w2 * (1 - cos(w * tau))
                supplied += mass * sp * (kinetic * tau - w2 * area)
                frictionWork += mass * kinetic * (position(tau) - x)
            }
            val notch = bed.notch(next)
            if (position(limit) >= notch) {
                var lo = 0.0; var hi = limit
                repeat(60) { val mid = (lo + hi) * .5; if (position(mid) >= notch) hi = mid else lo = mid }
                advance(hi)
                val arriving = speedAt(hi)
                x = notch; t = t0 + hi
                if (arriving < bed.captureAt(next)) {
                    val work = .5 * mass * arriving * arriving
                    caught += work
                    events += Cairn.Event(t, CATCH, next % bed.position.size, work, arriving, 0.0)
                    events += Cairn.Event(slipStart, SLIP, -1, mass * kinetic * (x - slipX), 0.0, 0.0, t - slipStart)
                    v = 0.0; stuck = true
                    threshold = kinetic + smooth * bed.grip(x) + bed.extraAt(next)
                } else {
                    val work = TICK_SHARE * .5 * mass * arriving * arriving
                    caught += work
                    v = arriving * sqrt(1 - TICK_SHARE)
                    events += Cairn.Event(t, TICK, next % bed.position.size, work, arriving, v)
                }
                next++
            } else {
                advance(limit)
                x = position(limit); t = t0 + limit
                if (limit == stop) {
                    events += Cairn.Event(slipStart, SLIP, -1, mass * kinetic * (x - slipX), 0.0, 0.0, t - slipStart)
                    v = 0.0; stuck = true
                    threshold = kinetic + smooth * bed.grip(x)
                } else v = speedAt(limit)
            }
            largestIncrease = max(largestIncrease, energy() - before)
            if (!powered) states += Cairn.State(t, energy(), frictionWork + caught,
                listOf(x, v, driver(t) - x, 0.0, 0.0))
        }
        val finalEnergy = energy()
        check(abs(supplied - finalEnergy - frictionWork - caught) <= 1e-9 * max(1.0, supplied)) {
            "CAIRN GRIND work ledger does not close"
        }
        val ordered = events.sortedWith(compareBy<Cairn.Event> { it.seconds }.thenBy { it.stone })
            .filter { it.stone != SLIP || it.work > 1e-12 }
        if (!powered) return Cairn.Motion(ordered, states.toList(), 0.0, finalEnergy, frictionWork + caught,
            0.0, largestIncrease, 0.0, supplied)
        fun lap(k: Int) = ordered.filter { floor(it.seconds / PERIOD + 1e-12).toInt() == k }
            .map { it.copy(seconds = it.seconds - k * PERIOD) }
        for (k in 2 until LAPS - 1) {
            val current = lap(k)
            val residual = (0 until 5).maxOf { abs(boundaryState[k][it] - boundaryState[k + 1][it]) }
            if (sameLap(current, lap(k - 1)) && sameLap(current, lap(k + 1)) && residual < 1e-7) {
                val supply = boundaryTotals[k + 1][0] - boundaryTotals[k][0]
                val lost = boundaryTotals[k + 1][1] - boundaryTotals[k][1]
                return Cairn.Motion(current, listOf(Cairn.State(0.0, boundaryEnergy[k], 0.0,
                    boundaryState[k].toList())), boundaryEnergy[k], boundaryEnergy[k + 1], lost, 0.0,
                    largestIncrease, 0.0, supply, PERIOD, k + 1, residual, lap(k - 1))
            }
        }
        error("CAIRN GRIND HOLD did not lock to its bed within $LAPS laps")
    }

    private fun ringSeconds(m: Map<String, Float>) =
        (.30 + .55 * m.getValue("BODY")) * (1.0 + .6 * m.getValue("HOLD"))

    fun finiteFrames(motion: Cairn.Motion, m: Map<String, Float>): Int {
        val last = motion.events.maxOfOrNull { it.seconds + it.slipSeconds } ?: 0.0
        return ceil(max(.12, last + ringSeconds(m)) * Cairn.RATE).toInt()
    }

    fun inspect(m: Map<String, Float>, hz: Double, velocity: Float, startAtContact: Boolean,
                options: Cairn.Options, motion: Cairn.Motion, cacheHit: Boolean, mechanicsMs: Double): Cairn.Render {
        val started = System.nanoTime()
        val rate = Cairn.RATE * options.oversample
        val held = Cairn.isLoop(m)
        val period = (PERIOD * Cairn.RATE).roundToInt()
        val periodInternal = period * options.oversample
        val ring = ringSeconds(m)
        val body = m.getValue("BODY").toDouble()
        val frictionMacro = m.getValue("FRICTION").toDouble()
        // A rougher, less harmonic stone than KNOCK's: the same family, new ratios.
        val ratios = doubleArrayOf(1.0, 2.62, 5.07, 2.0, 3.0, 4.0, 1.0, 1.41)
        val bank = Modes.Bank(8, rate)
        for (i in ratios.indices) bank.tune(i, min(12_000.0, hz * ratios[i]), ring * when (i) {
            0 -> 1.0; 1 -> .40; 2 -> .20; in 3..5 -> .55; else -> .45
        })
        fun audible(weights: DoubleArray) = bank.participation(DoubleArray(8) { i ->
            if (hz * ratios[i] >= 12_000) 0.0 else weights[i]
        })
        // Catches strike the stone squarely; slips rub its upper partials; ticks clip them.
        val catchContact = audible(doubleArrayOf(1.0, .45, .22, .25, .20, .15, .12 + .23 * body, .08))
        val slipContact = audible(doubleArrayOf(.35, .25, .30, .55, .45, .40, .10, .20))
        val tickContact = audible(doubleArrayOf(.20, .50, .60, .30, .30, .30, .05, .40))
        val pickup = audible(doubleArrayOf(1.0, .60, .30, 1.0, .90, .80, .32, .16))
        val forceFrames = if (held) periodInternal else finiteFrames(motion, m) * options.oversample
        val forces = Array(3) { FloatArray(forceFrames) }
        val direct = FloatArray(forceFrames)
        fun put(target: FloatArray, frame: Int, value: Double) {
            val i = if (held) frame.mod(periodInternal) else frame
            if (i in target.indices) target[i] += value.toFloat()
        }
        val width = (.00030 + .0010 * m.getValue("WEIGHT")) * (1.35 - .65 * velocity) * .5
        val pulseFrames = max(8, (width * rate).roundToInt())
        val kernel = DoubleArray(pulseFrames) { .5 - .5 * cos(2 * PI * (it + .5) / pulseFrames) }
        val area = kernel.sum() / rate
        val bright = (1800 + 7000 * frictionMacro).toFloat()
        val air = (AIR_BASE_HZ + AIR_SPAN_HZ * frictionMacro).toFloat()
        for ((index, event) in motion.events.withIndex()) {
            val first = (event.seconds * rate).roundToInt()
            if (event.stone != SLIP) {
                val impulse = .12 * sqrt(event.work)
                for (j in kernel.indices) put(forces[event.stone], first + j, impulse * kernel[j] / area)
                continue
            }
            // The scrape: band-limited noise for the length of the slip itself,
            // at a level set by the slip's own friction power.
            val seconds = max(.002, event.slipSeconds)
            val n = max(8, (seconds * rate).roundToInt())
            val amplitude = 12.0 * sqrt(event.work / seconds)
            val noise = Dsp.Noise(Dsp.seedFor("CAIRN", 6, "GRIND", "scrape", index))
            val low = Dsp.OnePole(rate); val floor = Dsp.OnePole(rate)
            val airA = Dsp.OnePole(rate); val airB = Dsp.OnePole(rate)
            for (j in 0 until n) {
                val shaped = low.lp(noise.next(), bright)
                val band = shaped - floor.lp(shaped, 500f)
                val envelope = sin(PI * (j + .5) / n).pow(2)
                val value = amplitude * envelope * band
                put(forces[SLIP], first + j, value)
                // The scrape heard directly, not through the stone. Round 1's
                // one-pole at the force path's cutoff left 13% of the energy above
                // 4 kHz, which the owner heard as an overpowering, static-y top.
                // A two-pole lowpass at its own, lower cutoff keeps the 2-4 kHz grit
                // and takes the hiss off.
                val heard = airB.lp(airA.lp(value.toFloat(), air), air)
                put(direct, first + j, DIRECT_GAIN * heard)
            }
        }
        val contacts = arrayOf(catchContact, slipContact, tickContact)
        fun drive(i: Int) { for (c in 0..2) { val f = forces[c][i]; if (f != 0f) bank.drive(contacts[c], f.toDouble()) } }
        if (held) {
            for (i in 0 until periodInternal) { drive(i); bank.step() }
            bank.closeUncoupledCycle(periodInternal)
        }
        val prefix = if (held) period else 0
        val frames = if (held) 2 * period + 1024 else forceFrames / options.oversample
        val raw = FloatArray(frames * options.oversample)
        var maxEnergy = 0.0; var tailIncrease = 0.0; var previousEnergy = 0.0
        val lastForce = if (held) Int.MAX_VALUE
            else (motion.events.maxOfOrNull { it.seconds + it.slipSeconds } ?: 0.0).times(rate).roundToInt() + pulseFrames + 4
        for (i in raw.indices) {
            val f = if (held) i % periodInternal else i
            if (f < forceFrames) drive(f)
            bank.step()
            raw[i] = (bank.velocityAlong(pickup) + if (f < forceFrames) direct[f] else 0f).toFloat()
            if (i % 32 == 0) {
                val e = bank.energy(); maxEnergy = max(maxEnergy, e)
                if (i > lastForce + 32) tailIncrease = max(tailIncrease, e - previousEnergy)
                previousEnergy = e
            }
        }
        return CairnStone.finish(raw, rate, options, held, prefix, period, velocity, startAtContact,
            motion, cacheHit, mechanicsMs, started, hz, "GRIND")
    }
}

/** The output stage the CAIRN voices with their own readout share. */
internal object CairnStone {
    fun finish(raw: FloatArray, rate: Int, options: Cairn.Options, held: Boolean, prefix: Int, period: Int,
               velocity: Float, startAtContact: Boolean, motion: Cairn.Motion, cacheHit: Boolean,
               mechanicsMs: Double, started: Long, hz: Double, label: String): Cairn.Render {
        val rawPeak = raw.maxOf { abs(it.toDouble()) }
        val rawRms = sqrt(raw.sumOf { it.toDouble() * it } / raw.size)
        val filtered = raw.copyOf(); Tide.bandLimit(filtered, rate)
        val full = if (options.oversample == Dsp.OVERSAMPLE) Dsp.decimate(filtered, Cairn.RATE)
            else Resampler.resample(Snip(filtered, 1, rate), Cairn.RATE).samples
        val mean = full.average().toFloat(); val hp = Dsp.OnePole(Cairn.RATE)
        for (i in full.indices) { val x = full[i] - mean; full[i] = x - hp.lp(x, 20f) }
        Dsp.levelTo(full, Cairn.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        if (!held) Dsp.fadeTail(full, rate = Cairn.RATE)
        val firstContact = ((motion.events.firstOrNull()?.seconds ?: 0.0) * Cairn.RATE).roundToInt()
        val cue = if (held) prefix else if (startAtContact) (firstContact - (.002 * Cairn.RATE).roundToInt()).coerceAtLeast(0) else 0
        val kept = if (held) full.copyOf(prefix + period) else full
        val seam = if (held && velocity > 0) Cairn.silentBoundarySeam(kept, prefix) else null
        if (seam != null) require(seam < Keys.MAX_SEAM_ERROR) { "CAIRN $label: the loop does not close (seam $seam)" }
        val samples = if (cue == 0) kept else kept.copyOfRange(cue, kept.size)
        return Cairn.Render(raw, kept, samples, Cairn.Diagnostics(motion, rawPeak, rawRms, 0.0,
            0.0, firstContact, cue, cacheHit, mechanicsMs, (System.nanoTime() - started) / 1e6,
            hz, rate, if (held) prefix else 0, seam))
    }
}
