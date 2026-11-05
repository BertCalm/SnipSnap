package com.snipsnap.synth

import kotlin.math.*

/**
 * TUMBLE's separate contact law: one upward launch, gravity, viscous flight
 * loss and unilateral impacts on a fixed bed. Rebounds lose height, so the
 * actual flight times contract. There is no accelerating phrase scheduler.
 * All quantities are normalized design shapes, not measured stone data.
 */
internal object CairnTumble {
    fun motion(weight: Double, slope: Double, groove: Double, friction: Double,
               body: Double, velocity: Double, powered: Boolean, isolated: Boolean): Cairn.Motion {
        val mass = .55 + 1.3 * weight
        val gravity = 10.0 + 8.0 * slope
        val drag = (.08 + 1.1 * friction) / mass
        val launch = (1.05 + .35 * slope) / sqrt(mass) * velocity
        // A more open groove lets small rebounds escape. The mount returns
        // some motion; removing it reduces restitution, never launches a reply.
        val restitution = .69 + .10 * groove + (if (isolated) 0.0 else .06 * body) - .10 * friction
        val captureSpeed = .028 + .09 * (1.0 - groove) + .02 * friction
        val duration = if (powered) 2.0 else 3.5
        val dt = 1.0 / 2000
        var height = 0.0
        var speed = if (powered) 0.0 else launch
        var resting = speed == 0.0
        fun energy() = .5 * mass * speed * speed + mass * gravity * height
        // Closed-form free flight, including drag. expm1 keeps tiny flights
        // accurate near the bed. Collisions split a step at their actual root.
        fun flight(t: Double): Pair<Double, Double> {
            val decay = exp(-drag * t)
            val integral = -expm1(-drag * t) / drag
            return (height + speed * integral - gravity * (t - integral) / drag) to
                (speed * decay - gravity * integral)
        }
        val nodes = doubleArrayOf(.1127016653792583, .5, .8872983346207417)
        val quadratureWeights = doubleArrayOf(5.0 / 18, 8.0 / 18, 5.0 / 18)
        fun dragWork(t: Double): Double {
            // Independent positive quadrature of m*drag*v^2, rather than
            // assigning the energy-budget residual to a damping ledger.
            return mass * drag * t * nodes.indices.sumOf { i ->
                val v = flight(t * nodes[i]).second
                quadratureWeights[i] * v * v
            }
        }
        var preceding = emptyList<Cairn.Event>()
        repeat(if (powered) 8 else 1) { cycle ->
            val beforeHeight = height; val beforeSpeed = speed
            val initial = energy()
            if (powered) { speed += launch; resting = speed == 0.0 }
            val supplied = if (powered) energy() - initial else 0.0
            var withdrawn = 0.0
            var dissipated = 0.0
            var captured = 0.0
            var previousEnergy = energy()
            var largestIncrease = 0.0
            val events = mutableListOf<Cairn.Event>()
            val states = mutableListOf(Cairn.State(0.0, previousEnergy, 0.0, listOf(height, 0.0, 0.0, 0.0, 0.0)))
            repeat((duration / dt).roundToInt()) { step ->
                var remaining = dt
                var elapsed = 0.0
                var splits = 0
                while (!resting && remaining > 1e-12) {
                    check(++splits <= 4) { "CAIRN TUMBLE collision step cap exceeded" }
                    val next = flight(remaining)
                    if (next.first >= 0.0) {
                        dissipated += dragWork(remaining)
                        height = next.first; speed = next.second
                        remaining = 0.0
                    } else {
                        var lo = 0.0; var hi = remaining
                        repeat(40) {
                            val mid = (lo + hi) * .5
                            if (flight(mid).first >= 0.0) lo = mid else hi = mid
                        }
                        val hit = (lo + hi) * .5
                        dissipated += dragWork(hit)
                        val incoming = abs(flight(hit).second)
                        val rebound = incoming * restitution
                        // The last impact sticks below the escape threshold.
                        // This is an inelastic contact, not a loop-boundary reset.
                        val sticks = rebound < captureSpeed
                        val work = .5 * mass * (incoming * incoming - rebound * rebound)
                        // A sub-threshold rebound is caught by the soft bed.
                        // Its remaining kinetic energy becomes bed loss, not
                        // an artificially louder final acoustic contact.
                        val captureWork = if (sticks) .5 * mass * rebound * rebound else 0.0
                        dissipated += captureWork; captured += captureWork
                        height = 0.0; speed = if (sticks) 0.0 else rebound; resting = sticks
                        withdrawn += work
                        events += Cairn.Event(step * dt + elapsed + hit, 0, events.size, work, incoming, rebound)
                        check(events.size <= 256) { "CAIRN TUMBLE contact cap exceeded" }
                        remaining -= hit; elapsed += hit
                    }
                }
                val e = energy()
                largestIncrease = max(largestIncrease, e - previousEnergy)
                previousEnergy = e
                if ((step + 1) % 20 == 0) states += Cairn.State((step + 1) * dt, e, withdrawn,
                    listOf(height, 0.0, 0.0, 0.0, 0.0))
            }
            check(abs(initial + supplied - energy() - withdrawn - dissipated) < 1e-8) {
                "CAIRN TUMBLE work ledger does not close"
            }
            val residual = max(abs(height - beforeHeight), abs(speed - beforeSpeed))
            val repeated = events.size == preceding.size && events.zip(preceding).all { (a, b) ->
                abs(a.seconds - b.seconds) < 1e-7 && abs(a.work - b.work) < 1e-8
            }
            if (!powered || (cycle >= 2 && residual < 1e-8 && repeated)) return Cairn.Motion(
                events.toList(), states.toList(), initial, energy(), withdrawn, dissipated,
                largestIncrease, captured, supplied, if (powered) duration else 0.0,
                if (powered) cycle + 1 else 0, if (powered) residual else 0.0,
                if (powered) preceding else emptyList())
            preceding = events.toList()
        }
        error("CAIRN TUMBLE HOLD did not settle within eight continuing cycles")
    }
}
