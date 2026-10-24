package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Model 3's causal, feed-forward middle junction. The first two sections act before exchange;
 * the last two act on the destination lane afterwards. Only marginal lanes 1..3 exchange.
 *
 * Each static half uses delayed taps with cumulative sqrt-retention factors. Fractional-delay
 * interpolation is evaluated once per arrival, rather than as serial section interpolators;
 * the actual exchanged midpoint signal supplies the destination half's history.
 * Splits use actual delayed audio with sqrt(R/T/A). Each exchange is orthogonal even
 * though its angle follows previous activity. Delays and fixed route filters are contractive,
 * and readout vectors have norm <=1. Thus nonlinear control does not create audio energy.
 * The activity follower is a musical memory, not a claim about thermal response of real glass.
 */
internal class FilamentJunction(
    private val plan: Filament.Plan,
    private val voice: FilamentVoice,
    macros: Map<String, Float>,
    hz: Double,
    private val options: Filament.Options,
    private val rate: Int,
    private val captureDiagnostics: Boolean = true,
) {
    private class Ring(size: Int) {
        private val data = DoubleArray(size)
        private var position = 0
        fun write(x: Double) { data[position] = x }
        fun read(delay: Double): Double {
            var location = position - delay
            if (location < 0.0) location += data.size
            val index = floor(location).toInt()
            val fraction = location - index
            return data[index] + fraction * (data[(index + 1) % data.size] - data[index])
        }
        fun advance() { position = (position + 1) % data.size }
        fun appendState(destination: DoubleArray, offset: Int): Int {
            for (i in data.indices) destination[offset + i] = data[(position + i) % data.size]
            return offset + data.size
        }
        val size get() = data.size
    }

    private class RouteFilter(spec: Filament.TapSpec, rate: Int, private val captureDiagnostics: Boolean) {
        private val lowAlpha = if (spec.lowHz.isInfinite()) 1.0 else 1.0 - exp(-2.0 * PI * spec.lowHz / rate)
        private val highAlpha = if (spec.highHz > 0.0) 1.0 - exp(-2.0 * PI * spec.highHz / rate) else 0.0
        private val highMix = spec.highMix
        private val dispersion = if (spec.dispersionHz > 0.0) Filament.Allpass(spec.dispersionHz, rate, captureDiagnostics) else null
        private val dispersionMix = spec.dispersionMix
        private var low = 0.0
        private var high = 0.0
        var maxState = 0.0; private set
        fun next(x: Double): Double {
            low += lowAlpha * (x - low)
            high += highAlpha * (low - high)
            if (captureDiagnostics) maxState = max(maxState, max(abs(low), abs(high)))
            val filtered = low - highMix * high
            val allpass = dispersion ?: return filtered
            val result = (1.0 - dispersionMix) * filtered + dispersionMix * allpass.next(filtered)
            if (captureDiagnostics) maxState = max(maxState, allpass.maxState)
            return result
        }
        fun state(destination: MutableList<Double>) {
            destination += low; destination += high
            dispersion?.state(destination)
        }
    }

    private inner class Route(val branch: Filament.BranchTrace) {
        val sites = plan.sites.filter { it.strandId == branch.strandId && it.pathId == branch.pathId }.sortedBy { it.sectionId }
        val retained = DoubleArray(4) { sqrt(sites[it].retainedFraction) }
        val escape = DoubleArray(4) { sqrt(sites[it].escapedFraction) }
        val absorb = DoubleArray(4) { sqrt(sites[it].absorbedFraction) }
        val launch = sqrt(branch.launch)
        val midpoint = branch.endpointSeconds * .5 * rate
        val delays = DoubleArray(4) { branch.endpointSeconds * (it + 1.0) / 4.0 * rate }
        val ring = Ring(ceil(plan.maxDelay * rate).toInt() + 4)
        val spillFilters = Array(4) { j -> RouteFilter(plan.leakage[branch.strandId * 16 + branch.pathId * 4 + j], rate, captureDiagnostics) }
        val endpointFilter = RouteFilter(plan.endpoints[branch.strandId * 4 + branch.pathId], rate, captureDiagnostics)
        var throughWeight = if (voice == FilamentVoice.PULSE) sqrt(branch.launch) else
            if (plan.retained > 0.0) sqrt(branch.retained / plan.retained) else 0.0
        val spillWeights = DoubleArray(4) { j ->
            if (plan.escaped <= 0.0) 0.0 else if (voice == FilamentVoice.PULSE)
                sqrt(branch.launch) * (if (j < 2) .25 else 1.0)
            else sqrt(sites[j].escapedPower / plan.escaped)
        }
        var activityLow = 0.0
        var memory = if (options.freezeMemory && branch.pathId != 0) .5 else 0.0
        val active get() = branch.launch > 0.0
    }

    private val source = Ring(ceil(plan.maxDelay * rate).toInt() + 4)
    private val routes = plan.branches.map { Route(it) }.toTypedArray()
    // These arrays keep the fixed route order without allocating sample-rate iterators.
    private val active = routes.filter { it.active }.toTypedArray()
    init {
        if (voice == FilamentVoice.PULSE) {
            // Apertures follow admitted lanes, not their no-exchange retained-power estimates:
            // a destination may now carry energy that arrived from a different lane. Observe
            // late answers rather than spending readout on already-emitted leakage. These are
            // fixed unit-length vectors over COMPLETE collectors, not branch makeup gains.
            val retainedNorm = sqrt(routes.sumOf { it.throughWeight * it.throughWeight })
            val escapedNorm = sqrt(routes.sumOf { route -> route.spillWeights.sumOf { it * it } })
            for (route in routes) {
                if (retainedNorm > 0.0) route.throughWeight /= retainedNorm
                if (escapedNorm > 0.0) for (j in route.spillWeights.indices) route.spillWeights[j] /= escapedNorm
            }
        }
    }
    private val junction = DoubleArray(routes.size)
    private val before = DoubleArray(routes.size)
    private val strandPower = DoubleArray(4) { s -> plan.branches.filter { it.strandId == s }.sumOf { it.launch } }
    private val bundle = macros.getValue("BUNDLE").toDouble()
    private val inject = macros.getValue("INJECT").toDouble()
    private val activityAlpha = 1.0 - exp(-2.0 * PI * hz * 1.5 / rate)
    private val attackAlpha = 1.0 - exp(-1.0 / (.025 * rate))
    private val releaseSeconds = when (voice) { FilamentVoice.THREAD -> .40; FilamentVoice.PULSE -> .14; FilamentVoice.CURL -> .65 }
    private val releaseAlpha = 1.0 - exp(-1.0 / (releaseSeconds * rate))
    private val withinT = tan((when (voice) { FilamentVoice.THREAD -> .40; FilamentVoice.PULSE -> 1.05; FilamentVoice.CURL -> .58 }) * .5)
    private val acrossT = tan(.50 * .5)
    private val throughNorm = sqrt(routes.sumOf { it.throughWeight * it.throughWeight })
    private val spillNorm = sqrt(routes.sumOf { r -> r.spillWeights.sumOf { it * it } })
    private val launchNorm = plan.branches.sumOf { it.launch }
    var through = 0.0; private set
    var escaped = 0.0; private set
    var maxState = 0.0; private set

    private var frameCount = 0L
    private var energyScope = "complete finite propagation"
    private var sourceEnergy = 0.0
    private var retainedEnergy = 0.0
    private var escapedEnergy = 0.0
    private var absorbedEnergy = 0.0
    private var junctionInputEnergy = 0.0
    private var junctionOutputEnergy = 0.0
    private var maxJunctionError = 0.0
    private var maxSplitError = 0.0
    private var minimumMemory = Double.POSITIVE_INFINITY
    private var maximumMemory = 0.0
    private var sumMemory = 0.0
    private var memoryCount = 0L
    private var maxT = 0.0
    private val routeChanges = DoubleArray(routes.size)

    /** Integrals for HOLD describe its actual returned cycle, not the discarded preroll. */
    fun beginWindow(scope: String) {
        if (!captureDiagnostics) return
        energyScope = scope
        frameCount = 0L
        sourceEnergy = 0.0; retainedEnergy = 0.0; escapedEnergy = 0.0; absorbedEnergy = 0.0
        junctionInputEnergy = 0.0; junctionOutputEnergy = 0.0
        minimumMemory = Double.POSITIVE_INFINITY; maximumMemory = 0.0; sumMemory = 0.0; memoryCount = 0L
        maxT = 0.0
        routeChanges.fill(0.0)
    }

    private fun split(route: Route, section: Int, input: Double): Double {
        val r = input * route.retained[section]
        if (captureDiagnostics) {
            val t = input * route.escape[section]
            val a = input * route.absorb[section]
            maxSplitError = max(maxSplitError, abs(input * input - r * r - t * t - a * a))
            absorbedEnergy += a * a
        }
        return r
    }

    /** Rational half-angle form is smooth and exactly orthogonal without audio-rate trig. */
    private fun rotate(a: Int, b: Int, t: Double) {
        if (t == 0.0) return
        val denominator = 1.0 + t * t
        val cosine = (1.0 - t * t) / denominator
        val sine = 2.0 * t / denominator
        val x = junction[a]
        val y = junction[b]
        val u = cosine * x + sine * y
        val v = -sine * x + cosine * y
        if (captureDiagnostics) maxJunctionError = max(maxJunctionError, abs(x * x + y * y - u * u - v * v))
        junction[a] = u; junction[b] = v
        if (captureDiagnostics) maxT = max(maxT, abs(t))
    }

    fun next(input: Double) {
        if (captureDiagnostics) {
            frameCount++
            sourceEnergy += input * input
        }
        source.write(input)
        through = 0.0; escaped = 0.0
        for (route in active) {
            val id = route.branch.strandId * 4 + route.branch.pathId
            if (captureDiagnostics) {
                val first = source.read(route.delays[0]) * route.launch
                split(route, 0, first)
            }
            val second = source.read(route.delays[1]) * route.launch * route.retained[0]
            val middle = split(route, 1, second)
            junction[id] = middle
            if (captureDiagnostics) before[id] = middle
            if (route.branch.pathId != 0) {
                route.activityLow += activityAlpha * (middle - route.activityLow)
                val upper = middle - route.activityLow
                val power = upper * upper
                val target = power / (power + .000070)
                if (!options.freezeMemory) {
                    val alpha = if (target > route.memory) attackAlpha else releaseAlpha
                    route.memory += alpha * (target - route.memory)
                }
                if (captureDiagnostics) {
                    minimumMemory = min(minimumMemory, route.memory)
                    maximumMemory = max(maximumMemory, route.memory)
                    sumMemory += route.memory
                    memoryCount++
                }
            }
            for (j in 0..1) {
                val delay = if (options.genericSpill) plan.maxDelay * rate else route.delays[j]
                val prefix = if (j == 0) route.launch else route.launch * route.retained[0]
                val escapedInput = source.read(delay) * prefix * route.escape[j]
                val leaf = route.spillFilters[j].next(escapedInput)
                if (captureDiagnostics) escapedEnergy += leaf * leaf
                escaped += route.spillWeights[j] * leaf
            }
        }
        if (!options.disableExchange) {
            for (s in 0 until 4) if (strandPower[s] > 0.0) {
                val base = s * 4
                val m12 = sqrt((routes[base + 1].memory + routes[base + 2].memory) * .5)
                val m13 = sqrt((routes[base + 1].memory + routes[base + 3].memory) * .5)
                val strength = withinT * (.35 + .65 * inject)
                if (voice == FilamentVoice.PULSE) {
                    // The clearest marginal route feeds the longest, most bend-sensitive
                    // destination first. Its delayed answer should differ from its donor.
                    rotate(base + 1, base + 3, -strength * m13)
                    rotate(base + 1, base + 2, -strength * .50 * m12)
                } else {
                    val m23 = sqrt((routes[base + 2].memory + routes[base + 3].memory) * .5)
                    rotate(base + 1, base + 2, strength * m12)
                    rotate(base + 2, base + 3, -strength * .85 * m23)
                }
            }
            for (s in 0 until 3) if (strandPower[s] > 0.0 && strandPower[s + 1] > 0.0) {
                val participation = 2.0 * sqrt(strandPower[s] * strandPower[s + 1]) / (strandPower[s] + strandPower[s + 1])
                for (p in 1..3) {
                    val a = s * 4 + p
                    val b = (s + 1) * 4 + p
                    val memory = sqrt((routes[a].memory + routes[b].memory) * .5)
                    val direction = if ((s + p) % 2 == 0) 1.0 else -1.0
                    rotate(a, b, direction * acrossT * bundle * participation * memory)
                }
            }
        }
        var incoming = 0.0
        var outgoing = 0.0
        for (route in active) {
            val id = route.branch.strandId * 4 + route.branch.pathId
            val y = junction[id]
            if (captureDiagnostics) {
                val x = before[id]
                incoming += x * x; outgoing += y * y
                routeChanges[id] += y * y - x * x
            }
            route.ring.write(y)
        }
        if (captureDiagnostics) {
            junctionInputEnergy += incoming
            junctionOutputEnergy += outgoing
            maxJunctionError = max(maxJunctionError, abs(incoming - outgoing))
        }
        for (route in active) {
            val third = route.ring.read(route.delays[2] - route.midpoint)
            if (captureDiagnostics) split(route, 2, third)
            val fourth = route.ring.read(route.delays[3] - route.midpoint) * route.retained[2]
            val endpoint = split(route, 3, fourth)
            val retainedLeaf = route.endpointFilter.next(endpoint)
            if (captureDiagnostics) retainedEnergy += retainedLeaf * retainedLeaf
            through += route.throughWeight * retainedLeaf
            for (j in 2..3) {
                val delay = (if (options.genericSpill) plan.maxDelay * rate else route.delays[j]) - route.midpoint
                val prefix = if (j == 2) 1.0 else route.retained[2]
                val escapedInput = route.ring.read(delay) * prefix * route.escape[j]
                val leaf = route.spillFilters[j].next(escapedInput)
                if (captureDiagnostics) escapedEnergy += leaf * leaf
                escaped += route.spillWeights[j] * leaf
            }
            if (captureDiagnostics) maxState = max(maxState,
                max(abs(junction[route.branch.strandId * 4 + route.branch.pathId]), max(abs(route.activityLow), route.memory)))
            route.ring.advance()
        }
        source.advance()
    }

    fun state(): DoubleArray {
        val filters = ArrayList<Double>()
        for (route in active) {
            filters += route.activityLow; filters += route.memory
            route.endpointFilter.state(filters)
            maxState = max(maxState, route.endpointFilter.maxState)
            for (filter in route.spillFilters) { filter.state(filters); maxState = max(maxState, filter.maxState) }
        }
        val snapshot = DoubleArray(filters.size + source.size + active.sumOf { it.ring.size })
        for (i in filters.indices) snapshot[i] = filters[i]
        var offset = source.appendState(snapshot, filters.size)
        for (route in active) offset = route.ring.appendState(snapshot, offset)
        return snapshot
    }

    fun diagnostics(): Filament.ExchangeDiagnostics {
        val scale = 1.0 / rate
        val launched = sourceEnergy * launchNorm
        return Filament.ExchangeDiagnostics(!options.disableExchange, options.freezeMemory, rate / Dsp.RATE,
            sourceEnergy * scale, launched * scale, retainedEnergy * scale, escapedEnergy * scale,
            absorbedEnergy * scale, (launched - retainedEnergy - escapedEnergy - absorbedEnergy) * scale,
            junctionInputEnergy * scale, junctionOutputEnergy * scale, maxJunctionError, maxSplitError,
            throughNorm, spillNorm, maximumMemory, if (memoryCount == 0L) 0.0 else sumMemory / memoryCount,
            2.0 * atan(maxT), routeChanges.map { it * scale },
            if (minimumMemory.isFinite()) maximumMemory - minimumMemory else 0.0, energyScope, frameCount)
    }
}
