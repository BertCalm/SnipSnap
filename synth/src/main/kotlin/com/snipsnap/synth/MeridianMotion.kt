package com.snipsnap.synth

import java.util.Collections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.pow

/** Musical, maintained motion, not an Earth-rate pendulum solver.
 * No acoustic state enters this geometry. Pitch, velocity and MEMORY therefore
 * share the same prepared contact history. The twelve fixed ribbons have stable
 * IDs; rotating trajectory and floor together changes no relative contact. */
internal object MeridianMotion {
    const val SURFACES = 12
    const val CONTROL_RATE = 1000
    const val INITIAL_ORIENTATION = .12
    const val MAX_PRECESSION = .45
    private const val TAU = 2 * PI

    data class Point(val x: Double, val y: Double, val vx: Double, val vy: Double)
    data class Entry(val seconds: Double, val strength: Double)
    data class Prepared(val plan: Plan, val cacheHit: Boolean, val prepareNanos: Long)

    /** Weights stay private and both event-list levels are unmodifiable, so a
     * diagnostic caller cannot mutate a later render through the shared cache. */
    class Plan internal constructor(
        val periodSeconds: Double,
        val precessionRate: Double,
        val amplitude: Double,
        val activeSeconds: Double,
        val initialOrientation: Double,
        val floorRotation: Double,
        private val weights: FloatArray,
        val entries: List<List<Entry>>,
        val maxWeightSum: Double,
    ) {
        val frameCount: Int get() = weights.size / SURFACES

        fun weight(frame: Int, surface: Int): Double {
            require(surface in 0 until SURFACES)
            return if (frame in 0 until frameCount) weights[frame * SURFACES + surface].toDouble() else 0.0
        }

        fun weightsAt(seconds: Double): DoubleArray {
            require(seconds.isFinite() && seconds >= 0.0)
            val at = seconds * CONTROL_RATE
            val frame = at.toInt()
            val fraction = at - frame
            return DoubleArray(SURFACES) { j ->
                weight(frame, j) * (1 - fraction) + weight(frame + 1, j) * fraction
            }
        }

        fun pointAt(seconds: Double): Point {
            require(seconds.isFinite())
            val w = TAU / periodSeconds
            val s = amplitude * cos(w * seconds)
            val ds = -amplitude * w * sin(w * seconds)
            val psi = initialOrientation - precessionRate * seconds
            val c = cos(psi)
            val sn = sin(psi)
            return Point(s * c, s * sn,
                ds * c + s * precessionRate * sn,
                ds * sn - s * precessionRate * c)
        }
    }

    private data class Key(val latitude: Double, val length: Double, val reach: Double,
        val active: Double, val initial: Double, val floor: Double, val fixed: Boolean, val pMax: Double)

    // At the largest diagnostic gate one plan is about 0.6 MB; eight plans keep
    // a knob sweep bounded. No audio arrays or pitch-dependent coefficients live here.
    private val cache = object : LinkedHashMap<Key, Plan>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Plan>): Boolean = size > 8
    }

    @Synchronized
    fun prepare(latitude: Double, length: Double, reach: Double, activeSeconds: Double,
        initialOrientation: Double = INITIAL_ORIENTATION, floorRotation: Double = 0.0,
        fixedAlignment: Boolean = false, pMax: Double = MAX_PRECESSION): Prepared {
        for ((label, value) in listOf("latitude" to latitude, "length" to length, "reach" to reach))
            require(value.isFinite() && value in 0.0..1.0) { "$label must be in 0..1: $value" }
        require(activeSeconds.isFinite() && activeSeconds in .05..12.0)
        require(initialOrientation.isFinite() && floorRotation.isFinite())
        require(pMax.isFinite() && pMax in 0.0..1.0)
        if (Thread.currentThread().isInterrupted)
            throw java.util.concurrent.CancellationException("MERIDIAN geometry no longer wanted")
        val key = Key(latitude, length, reach, activeSeconds, initialOrientation, floorRotation, fixedAlignment, pMax)
        cache[key]?.let { return Prepared(it, true, 0L) }
        val began = System.nanoTime()
        val period = .15 * 10.0.pow(length)
        val omega = TAU / period
        val precession = if (fixedAlignment) 0.0 else pMax * sin(PI * (latitude - .5))
        val amplitude = .35 + .65 * reach
        val frames = ceil(activeSeconds * CONTROL_RATE).toInt() + 2
        val weights = FloatArray(frames * SURFACES)
        val directionsX = DoubleArray(SURFACES) { cos(TAU * it / SURFACES + floorRotation) }
        val directionsY = DoubleArray(SURFACES) { sin(TAU * it / SURFACES + floorRotation) }
        val widthCos = cos((27 + 10 * reach) * PI / 180)
        var maxWeight = 0.0
        val row = DoubleArray(SURFACES)
        for (i in 0 until frames) {
            if (i and 1023 == 0 && Thread.currentThread().isInterrupted)
                throw java.util.concurrent.CancellationException("MERIDIAN geometry no longer wanted")
            val t = i.toDouble() / CONTROL_RATE
            val s = amplitude * cos(omega * t)
            val psi = initialOrientation - precession * t
            // The direction flips at the center, where radial pressure is zero.
            // There is no atan2 and no division by the vanishing bob radius.
            val sign = if (s >= 0) 1.0 else -1.0
            val x = sign * cos(psi)
            val y = sign * sin(psi)
            val radial = smooth((abs(s) - .2) / .6)
            // Starting inside a ribbon is allowed. Pressure rises through a short
            // onset; a catch still requires an actual rising entry and approach.
            val gate = smooth(t / .008) * smooth((activeSeconds - t) / .035)
            var total = 0.0
            for (j in 0 until SURFACES) {
                val dot = x * directionsX[j] + y * directionsY[j]
                row[j] = radial * smooth((dot - widthCos) / (1 - widthCos)) * gate
                total += row[j]
            }
            val bound = maxOf(1.0, total)
            var storedSum = 0.0
            for (j in 0 until SURFACES) {
                val w = (row[j] / bound).toFloat()
                weights[i * SURFACES + j] = w
                storedSum += w
            }
            maxWeight = maxOf(maxWeight, storedSum)
        }
        fun engagementSlope(t: Double, surface: Int): Double {
            val s = amplitude * cos(omega * t)
            val ds = -amplitude * omega * sin(omega * t)
            val sign = if (s >= 0.0) 1.0 else -1.0
            val radialArgument = (abs(s) - .2) / .6
            val radial = smooth(radialArgument)
            val radialSlope = smoothDerivative(radialArgument) * sign * ds / .6
            val psi = initialOrientation - precession * t
            var sum = 0.0
            var sumSlope = 0.0
            var own = 0.0
            var ownSlope = 0.0
            for (j in 0 until SURFACES) {
                val relative = psi - (TAU * j / SURFACES + floorRotation)
                val angularArgument = (sign * cos(relative) - widthCos) / (1 - widthCos)
                val angular = smooth(angularArgument)
                val angularSlope = smoothDerivative(angularArgument) *
                    sign * precession * sin(relative) / (1 - widthCos)
                val value = radial * angular
                val slope = radialSlope * angular + radial * angularSlope
                sum += value
                sumSlope += slope
                if (j == surface) {
                    own = value
                    ownSlope = slope
                }
            }
            // Use physical engagement, excluding the note-on/off gate. A fast
            // tangential pass can barely graze a ribbon; its Euclidean speed
            // alone must not turn that threshold crossing into a full strike.
            // The quotient derivative retains the same overlap energy bound.
            return if (sum <= 1.0) ownSlope else (ownSlope * sum - own * sumSlope) / (sum * sum)
        }
        val events = List(SURFACES) { j ->
            val surface = mutableListOf<Entry>()
            var armed = true
            var last = -1.0
            for (i in 1 until frames) {
                val before = weights[(i - 1) * SURFACES + j].toDouble()
                val now = weights[i * SURFACES + j].toDouble()
                if (now < .025) armed = true
                if (armed && before < .065 && now >= .065) {
                    val fraction = (.065 - before) / (now - before)
                    val t = (i - 1 + fraction) / CONTROL_RATE
                    if (t - last >= .025 && t < activeSeconds) {
                        val s = amplitude * cos(omega * t)
                        val radialSpeed = -amplitude * omega * sin(omega * t)
                        val speed = hypot(radialSpeed, s * precession)
                        // 20 /s is a proposed full-catch engagement slope. The
                        // smooth positive approach factor softens a slow entry,
                        // even when the bob is moving quickly along the ribbon.
                        val approach = smooth(maxOf(0.0, engagementSlope(t, j)) / 20.0)
                        var visitPeak = now
                        var lookahead = i + 1
                        while (lookahead < frames) {
                            val weight = weights[lookahead * SURFACES + j].toDouble()
                            if (weight < .025) break
                            visitPeak = maxOf(visitPeak, weight)
                            lookahead++
                        }
                        // The maintained trajectory is prepared offline. Its
                        // connected visit's maximum engagement is a designed
                        // ribbon-catch hardness, not a causal collision solver.
                        // It makes a newly detected shallow visit enter at zero
                        // strength even at a normalization kink or between
                        // control samples. Deep visits keep their full catch.
                        val penetration = smooth((visitPeak - .065) / .10)
                        val strength = .28 * smooth((speed - .08) / 7.92) * (.45 + .55 * reach) * approach * penetration
                        if (strength > 0.0) surface += Entry(t, strength)
                        armed = false
                        last = t
                    }
                }
            }
            surface.toList()
        }
        val immutableEvents = Collections.unmodifiableList(events.map { Collections.unmodifiableList(it) })
        val plan = Plan(period, precession, amplitude, activeSeconds, initialOrientation,
            floorRotation, weights, immutableEvents, maxWeight)
        cache[key] = plan
        return Prepared(plan, false, System.nanoTime() - began)
    }

    internal fun clearCache() = synchronized(this) { cache.clear() }

    internal fun smooth(value: Double): Double {
        val x = value.coerceIn(0.0, 1.0)
        return x * x * (3 - 2 * x)
    }

    private fun smoothDerivative(value: Double): Double =
        if (value <= 0.0 || value >= 1.0) 0.0 else 6 * value * (1 - value)
}
