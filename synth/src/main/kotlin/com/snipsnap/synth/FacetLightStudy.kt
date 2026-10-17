package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class FacetLightDirection { GLASS_GLINTS, AIRY_WASH }

/** A finite listening study, separate from the saved FACET source models. */
object FacetLightStudy {
    const val STUDY_VERSION = 1
    private const val SIZE = 2048
    private const val GROUPS = 8
    private const val MODES = 16
    private val counts = intArrayOf(8, 16, 32, 64, 128)
    private val glassRatios = doubleArrayOf(2.0, 3.0, 4.02, 5.03, 5.98, 7.97, 10.03, 12.02, 15.97, 18.03, 20.04, 24.03, 27.97, 31.98, 40.03, 47.95)
    private val airRatios = doubleArrayOf(1.0, 2.0, 3.01, 4.01, 5.98, 8.02, 10.01, 12.03, 16.02, 20.01, 24.02, 28.04, 32.02, 40.01, 48.03, 56.01)

    fun probe(
        voice: FacetVoice,
        direction: FacetLightDirection,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = 48,
        velocity: Float = .8f,
        seed: Int = 17,
        initialAlignment: Float = 0f,
        options: FacetOptions = FacetOptions(seconds = 6f),
    ): FacetRender {
        val begin = System.nanoTime()
        require(midi in 0..127 && velocity.isFinite() && velocity in 0f..1f) { "Invalid light-study note or velocity" }
        require(initialAlignment.isFinite() && initialAlignment in 0f..1f) { "Light-study alignment must be turns in 0..1" }
        require(options.resonance && !options.commonGainRouting && options.sourceMotion) {
            "Light study supports seconds and oversample only; resonance/commonGainRouting/sourceMotion bypasses are unsupported"
        }
        val m = Facet.settled(macros, voice)
        val density = m.getValue("FACETS").toDouble()
        val illumination = m.getValue("ILLUMINATE").toDouble()
        val chroma = m.getValue("CHROMA").toDouble()
        val elevation = m.getValue("ELEVATION").toDouble()
        val rotor = rotation(m.getValue("REVOLVE"), voice)
        val geometrySeed = Dsp.seedFor("FACET", 1, voice, seed)
        val position = density * 4.0
        val lower = floor(position).toInt().coerceAtMost(3)
        val blend = (position - lower).coerceIn(0.0, 1.0)
        val low = prepare(lower, illumination, elevation, chroma, geometrySeed)
        val high = if (blend == 0.0) low else prepare(lower + 1, illumination, elevation, chroma, geometrySeed)
        val table = Array(GROUPS) { g -> FloatArray(SIZE) { i ->
            (low.routes[g][i] * (1.0 - blend) + high.routes[g][i] * blend).toFloat()
        } }
        val interpolationBudget = 1.0 / sqrt((1.0 - blend).pow(2) + blend.pow(2))
        val events = (low.events.map { it.copy(amplitude = it.amplitude * (1.0 - blend) * interpolationBudget) } +
            if (blend == 0.0) emptyList() else high.events.map { it.copy(amplitude = it.amplitude * blend * interpolationBudget) })
            .filter { it.amplitude > 0.0 }.sortedBy { it.phase }
        val seconds = options.seconds ?: 6f
        val rate = Dsp.RATE * options.oversample
        val frames = (seconds * Dsp.RATE).toInt()
        val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val airy = direction == FacetLightDirection.AIRY_WASH
        val ratios = if (airy) airRatios else glassRatios
        val poleReal = DoubleArray(MODES)
        val poleImag = DoubleArray(MODES)
        val injection = DoubleArray(MODES)
        val modeLevel = DoubleArray(MODES)
        for (j in 0 until MODES) {
            val band = j / (MODES - 1.0)
            val t60 = if (airy) (1.9 - .85 * band) * (1.15 - .35 * density) else
                (.60 - .27 * band) * (1.25 - .65 * density)
            val decayRate = ln(1000.0) / t60
            val radius = exp(-decayRate / rate)
            val hz = frequency * ratios[j]
            poleReal[j] = radius * cos(2.0 * PI * hz / rate)
            poleImag[j] = radius * sin(2.0 * PI * hz / rate)
            // Fixed-clock noise has fixed physical PSD, independent of oversampling.
            injection[j] = sqrt(4.0 * decayRate * Dsp.RATE) / rate
            modeLevel[j] = if (hz >= 13_000.0) 0.0 else
                (.45 + .55 * band) / sqrt(MODES.toDouble()) * min(1.0, (13_000.0 - hz) / 2000.0)
        }
        val modeReal = DoubleArray(MODES)
        val modeImag = DoubleArray(MODES)
        val slow = DoubleArray(MODES)
        val fast = DoubleArray(MODES)
        val attackSeconds = if (airy) .035 - .014 * density else .018 - .010 * density
        val burstSeconds = if (airy) .12 - .045 * density else .050 - .025 * density
        val fastPole = exp(-1.0 / (attackSeconds * rate))
        val slowPole = exp(-1.0 / (burstSeconds * rate))
        val packetBudget = (if (airy) .40 else .85) * sqrt(.25 / (.18 + rotor)) * sqrt(.05 / burstSeconds)
        val noise = FixedNoise(MODES + 1, Dsp.seedFor("FACET-LIGHT", STUDY_VERSION, voice, seed))
        val airFilter = Dsp.Biquad().apply { bandpass(if (airy) 4100f else 4800f, if (airy) .75f else 1.15f, rate) }
        val veilFilter = Dsp.Biquad().apply { bandpass(if (airy) 4100f else 4800f, if (airy) .75f else 1.15f, rate) }
        val route = FloatArray(GROUPS)
        val startPhase = if (initialAlignment == 1f) 0.0 else initialAlignment.toDouble()
        lookup(table, startPhase * SIZE, route)
        var eventIndex = events.indexOfFirst { it.phase > startPhase }
        var eventTurn = 0
        if (eventIndex < 0) { eventIndex = 0; eventTurn = 1 }
        val trace = ArrayList<FacetTrace>()
        val internal = FloatArray(frames * options.oversample)
        val noiseValues = DoubleArray(MODES + 1)
        val preparations = (System.nanoTime() - begin) / 1e6
        val synthesisBegin = System.nanoTime()
        var statePeak = 0.0
        var phase = startPhase
        val tail = min(seconds * .36, 1.1)
        val releaseStart = seconds - tail
        val releaseSeconds = min(.4, tail * .65)
        val attack = min(seconds * .16, if (airy) .45 else .20)
        for (n in internal.indices) {
            val t = n.toDouble() / rate
            val absolutePhase = startPhase + rotor * t
            phase = if (rotor == 0f) startPhase else absolutePhase - floor(absolutePhase)
            if (rotor != 0f) lookup(table, phase * SIZE, route)
            if (rotor != 0f && events.isNotEmpty()) {
                while (eventTurn + events[eventIndex].phase <= absolutePhase) {
                    val event = events[eventIndex]
                    // Only this surface material's two or three modes receive a glint.
                    val first = event.group * 2
                    for (offset in 0..2) {
                        val j = (first + offset) % MODES
                        val amount = event.amplitude * event.polarity * packetBudget * (if (offset == 2) .38 else 1.0)
                        slow[j] += amount
                        fast[j] += amount
                    }
                    eventIndex++
                    if (eventIndex == events.size) { eventIndex = 0; eventTurn++ }
                }
            }
            noise.at(n, options.oversample, noiseValues)
            val envelope = smooth((t / attack).coerceIn(0.0, 1.0)) *
                (1.0 - smooth(((t - releaseStart) / releaseSeconds).coerceIn(0.0, 1.0))) * velocity
            var modal = 0.0
            for (j in 0 until MODES) {
                slow[j] *= slowPole
                fast[j] *= fastPole
                // Stable independent excitation polarities prevent overlapping packets
                // from adding as one coherent envelope as facet count increases.
                val packet = slow[j] - fast[j]
                // A stationary ball still illuminates a fixed, noise-excited sculpture.
                val stationary = (if (airy) .030 else .017) * sqrt(max(0.0, route[j / 2].toDouble()) * GROUPS)
                val excitation = (packet + stationary) * noiseValues[j] * envelope
                val re = modeReal[j]
                val im = modeImag[j]
                modeReal[j] = poleReal[j] * re - poleImag[j] * im + injection[j] * excitation
                modeImag[j] = poleImag[j] * re + poleReal[j] * im
                modal += modeReal[j] * modeLevel[j]
                statePeak = max(statePeak, max(abs(modeReal[j]), abs(modeImag[j])))
            }
            val routedIllumination = route.sum().toDouble().coerceAtLeast(0.0)
            val filteredAir = veilFilter.process(airFilter.process(noiseValues[MODES].toFloat())).toDouble()
            val veil = filteredAir * (if (airy) .070 else .012) * sqrt(routedIllumination) * envelope
            val root = sin(2.0 * PI * frequency * t + .4) * .004 * (.25 + .75 * illumination) * envelope
            // A gentle cap belongs to the finite study envelope, not a signal limiter.
            val cap = 1.0 - smooth(((t - (seconds - min(.32, tail * .6))) / min(.32, tail * .6)).coerceIn(0.0, 1.0))
            internal[n] = ((modal + veil + root) * cap).toFloat()
            if (n % max(1, rate / 50) == 0) trace.add(FacetTrace(t.toFloat(), phase, route.toList()))
        }
        var raw = internal
        var currentRate = rate
        Tide.bandLimit(raw, currentRate)
        while (currentRate > Dsp.RATE) {
            raw = Resampler.resample(Snip(raw, 1, currentRate), currentRate / 2).samples
            currentRate /= 2
        }
        val dc = Dsp.OnePole(Dsp.RATE)
        val mean = raw.sumOf { it.toDouble() } / max(1, raw.size)
        for (i in raw.indices) { val x = (raw[i] - mean).toFloat(); raw[i] = x - dc.lp(x, 8f) }
        Dsp.fadeTail(raw, 12f)
        val output = raw.copyOf()
        Dsp.levelTo(output, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        var peak = 0f; var sum = 0.0; var squares = 0.0
        for (v in raw) { peak = max(peak, abs(v)); sum += v; squares += v * v.toDouble() }
        val (diversity, motion) = measures(trace)
        val end = if (rotor == 0f) startPhase else (startPhase + rotor * frames.toDouble() / Dsp.RATE).let { it - floor(it) }
        return FacetRender(Snip(output, 1, Dsp.RATE), raw, FacetDiagnostics(
            preparations, (System.nanoTime() - synthesisBegin) / 1e6, peak,
            sqrt(squares / max(1, raw.size)).toFloat(), (sum / max(1, raw.size)).toFloat(), statePeak.toFloat(),
            rotor, startPhase, end, (counts[lower] * (1.0 - blend) + counts[lower + 1] * blend).toFloat(),
            diversity, motion, options.oversample,
        ), trace.toList())
    }

    private data class Tile(val z0: Double, val z1: Double, val a0: Double, val a1: Double, val material: Double, val normal: Double, val id: Int)
    private data class Event(val phase: Double, val group: Int, val amplitude: Double, val polarity: Double)
    private data class Geometry(val routes: Array<FloatArray>, val events: List<Event>)

    private fun prepare(depth: Int, illuminate: Double, elevation: Double, chroma: Double, seed: Int): Geometry {
        fun variation(id: Int) = Random(Dsp.seedFor(seed, "tile", id)).nextDouble() * 2.0 - 1.0
        var tiles = (0 until 8).map { i -> Tile(if (i < 4) -1.0 else 0.0, if (i < 4) 0.0 else 1.0,
            (i % 4) * PI / 2, (i % 4 + 1) * PI / 2, variation(i), .85 * variation(i + 199), i + 1) }
        repeat(depth) { d -> tiles = tiles.flatMap { tile ->
            val material = variation(tile.id * 71 + d * 19) * .24 / sqrt(d + 1.0)
            val normal = variation(tile.id * 97 + d * 31 + 311) * .28 / sqrt(d + 1.0)
            val a = tile.copy(material = tile.material + material, normal = tile.normal + normal, id = tile.id * 2)
            val b = tile.copy(material = tile.material - material, normal = tile.normal - normal, id = tile.id * 2 + 1)
            if (d % 2 == 0) { val mid = (tile.a0 + tile.a1) * .5; listOf(a.copy(a1 = mid), b.copy(a0 = mid)) }
            else { val mid = (tile.z0 + tile.z1) * .5; listOf(a.copy(z1 = mid), b.copy(z0 = mid)) }
        } }
        val routes = Array(GROUPS) { FloatArray(SIZE) }
        val events = ArrayList<Event>()
        val intensity = DoubleArray(12) { b ->
            val rank = ((b * 5) % 12) / 11.0
            val value = .035 + .965 * smooth(((illuminate * 1.35 - rank * .75) / .65).coerceIn(0.0, 1.0))
            if (b == 0) max(.3, value) else value
        }
        val kernels = Array(12) { b -> DoubleArray(SIZE) { i ->
            val x = 2 * PI * i / SIZE
            val incidence = .5 + .5 * cos(x - 2 * PI * b / 12)
            incidence * incidence * (.5 + .5 * cos(2 * x - 2 * PI * b / 12 - .7)).pow(1 + depth * 3)
        }.also { kernel -> val mean = kernel.average(); for (i in kernel.indices) kernel[i] = .05 + .95 * kernel[i] / mean } }
        for (tile in tiles) {
            val latitude = (tile.z0 + tile.z1) * .25 + .5
            val azimuth = (tile.a0 + tile.a1) * .5
            val color = (.30 * chroma + .22 * latitude + .46 * (.5 + .5 * sin(azimuth + tile.material * 1.1)) + .16 * tile.material).coerceIn(0.0, 1.0)
            val groupPosition = color * (GROUPS - 1)
            val group = floor(groupPosition).toInt().coerceAtMost(GROUPS - 2)
            val groupMix = groupPosition - group
            val beamWeights = DoubleArray(12) { b ->
                val target = (elevation + .14 * sin(2 * PI * b / 12 + .4)).coerceIn(0.0, 1.0) * 2 - 1
                intensity[b] * exp(-.5 * (((tile.z0 + tile.z1) * .5 - target) / .55).pow(2))
            }
            val selected = (0 until 12).sortedByDescending { beamWeights[it] }.take(2)
            val illumination = beamWeights.sum() / 12
            val selectedSum = selected.sumOf { beamWeights[it] }
            val area = 1.0 / tiles.size
            for (b in selected) {
                val beam = beamWeights[b] / selectedSum * illumination
                val offset = ((azimuth + tile.normal) / (2 * PI) * SIZE).toInt()
                for (i in 0 until SIZE) {
                    val weight = area * beam * kernels[b][(i + offset) and (SIZE - 1)]
                    routes[group][i] += (weight * (1 - groupMix)).toFloat()
                    routes[group + 1][i] += (weight * groupMix).toFloat()
                }
                val phases = DoubleArray(2) { half ->
                    val angle = .5 * (2 * PI * b / 12 + .7) - azimuth - tile.normal + PI * half
                    val turns = angle / (2 * PI); turns - floor(turns)
                }
                val incidence = DoubleArray(2) { half ->
                    val angle = phases[half] * 2 * PI + azimuth + tile.normal
                    (.5 + .5 * cos(angle - 2 * PI * b / 12)).pow(2)
                }
                val incidenceSum = incidence.sum().coerceAtLeast(1e-9)
                for (half in 0..1) events.add(Event(phases[half], if (groupMix > .5) group + 1 else group,
                    sqrt(area * beam * incidence[half] / incidenceSum),
                    if (Random(Dsp.seedFor(seed, "packet", tile.id, b, half)).nextBoolean()) 1.0 else -1.0))
            }
        }
        return Geometry(routes, events)
    }

    /** Noise samples have one fixed clock for every requested internal rate. */
    private class FixedNoise(count: Int, seed: Int) {
        private val sources = Array(count) { Dsp.Noise(Dsp.seedFor(seed, it)) }
        private val points = Array(count) { j -> DoubleArray(4) { sources[j].next().toDouble() } }
        fun at(sample: Int, oversample: Int, output: DoubleArray) {
            if (sample > 0 && sample % oversample == 0) for (j in sources.indices) {
                val p = points[j]; p[0] = p[1]; p[1] = p[2]; p[2] = p[3]; p[3] = sources[j].next().toDouble()
            }
            val x = (sample % oversample).toDouble() / oversample
            for (j in sources.indices) { val p = points[j]
                output[j] = p[1] + .5 * x * (p[2] - p[0] + x * (2*p[0] - 5*p[1] + 4*p[2] - p[3] + x * (3*(p[1] - p[2]) + p[3] - p[0])))
            }
        }
    }

    private fun lookup(table: Array<FloatArray>, position: Double, output: FloatArray) {
        val i = position.toInt(); val x = position - i
        for (g in output.indices) { val p = table[g]
            val a = p[(i - 1) and (SIZE - 1)].toDouble(); val b = p[i and (SIZE - 1)].toDouble()
            val c = p[(i + 1) and (SIZE - 1)].toDouble(); val d = p[(i + 2) and (SIZE - 1)].toDouble()
            output[g] = (b + .5*x*(c-a+x*(2*a-5*b+4*c-d+x*(3*(b-c)+d-a)))).toFloat()
        }
    }

    private fun rotation(value: Float, voice: FacetVoice): Float {
        if (value == 0f) return 0f
        val positions = floatArrayOf(0f, .1f, .25f, .5f, .7f, .85f, 1f)
        val rates = floatArrayOf(0f, .025f, .1f, .5f, 2f, 8f, if (voice == FacetVoice.BLOOM) 24f else 40f)
        val j = (0 until positions.lastIndex).firstOrNull { value <= positions[it + 1] } ?: positions.lastIndex - 1
        val x = (value - positions[j]) / (positions[j + 1] - positions[j])
        return if (j == 0) rates[1]*x else (rates[j]*(rates[j+1]/rates[j]).toDouble().pow(x.toDouble())).toFloat()
    }

    private fun smooth(x: Double) = x*x*(3-2*x)

    private fun measures(trace: List<FacetTrace>): Pair<Float, Float> {
        var diversity = 0.0; var motion = 0.0
        for (i in 1 until trace.size) {
            val a = trace[i-1].routeEnergy; val b = trace[i].routeEnergy
            val sa = a.sum().coerceAtLeast(1e-12f); val sb = b.sum().coerceAtLeast(1e-12f)
            for (g in 0 until GROUPS) { diversity += abs(a[g]/sa-b[g]/sb); motion += abs(a[g]-b[g]) }
        }
        return (diversity/max(1,trace.size-1)).toFloat() to (motion/max(1,trace.size-1)).toFloat()
    }
}
