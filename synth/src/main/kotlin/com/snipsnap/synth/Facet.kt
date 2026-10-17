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

/** First finite, dry mirrorball prototype. Constants are audition hypotheses. */
enum class FacetVoice { BLOOM, SHIMMER }

/** Stable beam IDs; elevations, colours, widths and intensity are normalized. */
data class FacetBeam(
    val id: Int,
    val intensity: Float = 1f,
    val elevation: Float = .5f,
    val color: Float = .5f,
    val width: Float = .5f,
) {
    init {
        require(id in 0..11) { "Facet beam ID must be in 0..11" }
        require(listOf(intensity, elevation, color, width).all { it.isFinite() && it in 0f..1f }) {
            "Facet beam intensity, elevation, color and width must be finite values in 0..1"
        }
    }
}

data class FacetOptions(
    val seconds: Float? = null,
    val resonance: Boolean = true,
    val commonGainRouting: Boolean = false,
    val oversample: Int = 4,
    val sourceMotion: Boolean = true,
) {
    init {
        require(seconds == null || seconds.isFinite() && seconds in .5f..24f) {
            "Facet diagnostic duration must be between .5 and 24 seconds"
        }
        require(oversample in listOf(1, 2, 4, 8)) { "Facet oversample must be 1, 2, 4 or 8" }
    }
}

data class FacetTrace(val seconds: Float, val phase: Double, val routeEnergy: List<Float>)

data class FacetDiagnostics(
    val preparationMillis: Double,
    val synthesisMillis: Double,
    val rawPeak: Float,
    val rawRms: Float,
    val rawDc: Float,
    val statePeak: Float,
    val rotorHz: Float,
    val startPhase: Double,
    val endPhase: Double,
    val effectiveFacets: Float,
    val routeDiversity: Float,
    val routeMotion: Float,
    val oversample: Int,
)

/** Raw is mono at 44.1 kHz after decimation/DC removal and before shared leveling. */
data class FacetRender(
    val snip: Snip,
    val raw: FloatArray,
    val diagnostics: FacetDiagnostics,
    val trace: List<FacetTrace>,
)

object Facet {
    const val MODEL_VERSION = 2
    const val LEGACY_MODEL_VERSION = 1
    const val DEFAULT_MIDI = 48
    private const val TABLE_SIZE = 2048
    private const val PARTIALS = 32
    private const val SOURCE_GROUPS = 3
    private const val ROUTES = PARTIALS * SOURCE_GROUPS
    private const val GEOMETRY_VERSION = 1
    private const val DETUNE_CENTS = 2.5
    private const val DETUNE_HZ_CEILING = .9
    private val harmonics = IntArray(PARTIALS) { it + 1 }
    private val levels = intArrayOf(8, 16, 32, 64, 128)

    fun supportsModel(modelVersion: Int): Boolean = modelVersion == LEGACY_MODEL_VERSION || modelVersion == MODEL_VERSION

    fun macrosFor(voice: FacetVoice): List<MacroSpec> {
        val bloom = voice == FacetVoice.BLOOM
        return listOf(
            MacroSpec("ILLUMINATE", if (bloom) .45f else .60f, .45f),
            MacroSpec("ELEVATION", if (bloom) .30f else .65f, .50f),
            MacroSpec("CHROMA", if (bloom) .20f else .65f, .45f),
            MacroSpec("REVOLVE", if (bloom) .15f else .65f, .25f),
            MacroSpec("FACETS", if (bloom) .25f else .80f, .50f),
            MacroSpec("HOLD", 0f, 0f),
        )
    }

    fun defaults(voice: FacetVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    fun settled(macros: Map<String, Float>, voice: FacetVoice): Map<String, Float> {
        val result = defaults(voice).toMutableMap()
        require(macros.keys.all { it in result }) { "Unknown Facet macro; expected ${result.keys.joinToString()}" }
        require(macros.values.all { it.isFinite() && it in 0f..1f }) { "Facet macros must be finite values in 0..1" }
        for ((key, value) in macros) result[key] = value
        require(result.getValue("HOLD") < .99f) {
            "Facet is a finite prototype: seamless HOLD is deferred; use HOLD below .99"
        }
        return result.toMap()
    }

    fun render(
        voice: FacetVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
        seed: Int = 17,
        initialAlignment: Float = 0f,
        beams: List<FacetBeam> = emptyList(),
        beamMode: String = "MACRO",
        options: FacetOptions = FacetOptions(),
        modelVersion: Int = MODEL_VERSION,
    ): Snip = probe(voice, macros, midi, velocity, seed, initialAlignment, beams, beamMode, options, modelVersion).snip

    fun probe(
        voice: FacetVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
        seed: Int = 17,
        initialAlignment: Float = 0f,
        beams: List<FacetBeam> = emptyList(),
        beamMode: String = "MACRO",
        options: FacetOptions = FacetOptions(),
        modelVersion: Int = MODEL_VERSION,
    ): FacetRender {
        require(supportsModel(modelVersion)) { "Unsupported Facet model version: $modelVersion" }
        if (modelVersion == LEGACY_MODEL_VERSION) return FacetModel1.probe(
            voice, macros, midi, velocity, seed, initialAlignment, beams, beamMode, options,
        )
        val preparationStart = System.nanoTime()
        require(midi in 0..127) { "Facet MIDI note must be in 0..127" }
        require(velocity.isFinite() && velocity in 0f..1f) { "Facet velocity must be in 0..1" }
        require(initialAlignment.isFinite() && initialAlignment in 0f..1f) {
            "Facet initialAlignment must be finite turns in 0..1"
        }
        require(beamMode == "MACRO" || beamMode == "OVERRIDE") { "Facet beamMode must be MACRO or OVERRIDE" }
        require(beams.map { it.id }.distinct().size == beams.size) { "Facet beam IDs must be unique" }
        require(beamMode == "OVERRIDE" || beams.isEmpty()) { "Facet beam overrides require beamMode OVERRIDE" }
        val m = settled(macros, voice)
        val illumination = m.getValue("ILLUMINATE")
        val elevation = m.getValue("ELEVATION")
        val chroma = m.getValue("CHROMA")
        val density = m.getValue("FACETS")
        val rotor = rotationHz(m.getValue("REVOLVE"), voice)
        val activeBeams = generatedBeams(illumination, elevation, chroma, voice).toMutableList()
        for (beam in beams) activeBeams[beam.id] = beam
        val illuminated = activeBeams.sumOf { it.intensity.toDouble() } / 12.0
        val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val densityPosition = density.toDouble() * 4.0
        val lower = floor(densityPosition).toInt().coerceAtMost(3)
        val blend = (densityPosition - lower).coerceIn(0.0, 1.0)
        // Source revisions must not rearrange the surface used by a saved patch.
        val renderSeed = Dsp.seedFor("FACET", GEOMETRY_VERSION, voice, seed)
        val phases = DoubleArray(PARTIALS) { p ->
            Random(Dsp.seedFor(renderSeed, "source", harmonics[p])).nextDouble() * 2.0 * PI
        }
        val beamPhases = Array(12) { b -> DoubleArray(PARTIALS) { p ->
            (Random(Dsp.seedFor(renderSeed, "beam", b, harmonics[p])).nextDouble() - .5) * .8
        } }
        // The angular response is a finite trigonometric polynomial: max degree 28.
        // Its fastest sideband is therefore 28*rotorHz, not an unbounded pulse edge.
        val modulationBandwidth = 28.0 * rotor
        // The guard is identical for clean/living comparisons; only frequencies move.
        val sourceCeiling = 16_000.0 - modulationBandwidth - DETUNE_HZ_CEILING
        val partialGuard = DoubleArray(PARTIALS) { p ->
            val ratio = frequency * harmonics[p] / sourceCeiling
            when { ratio >= 1.0 -> 0.0; ratio <= .82 -> 1.0; else -> .5 + .5 * cos(PI * (ratio - .82) / .18) }
        }
        val lowTable = prepare(levels[lower], lower, activeBeams, beamPhases, illumination, velocity, partialGuard, renderSeed, harmonics)
        val highTable = if (blend == 0.0) lowTable else
            prepare(levels[lower + 1], lower + 1, activeBeams, beamPhases, illumination, velocity, partialGuard, renderSeed, harmonics)
        val table = Array(ROUTES * 2) { c ->
            FloatArray(TABLE_SIZE) { i -> (lowTable[c][i] * (1.0 - blend) + highTable[c][i] * blend).toFloat() }
        }
        if (options.commonGainRouting) useCommonGain(table)
        val preparationMillis = (System.nanoTime() - preparationStart) / 1e6
        val synthesisStart = System.nanoTime()
        val seconds = options.seconds ?: (3.0f + m.getValue("HOLD") * 5.0f)
        val rate = Dsp.RATE * options.oversample
        val frames = (seconds * Dsp.RATE).toInt()
        val internal = FloatArray(frames * options.oversample)
        val oscillatorCount = if (options.sourceMotion) ROUTES else PARTIALS
        val oscillatorSin = DoubleArray(oscillatorCount) { sin(phases[it % PARTIALS]) }
        val oscillatorCos = DoubleArray(oscillatorCount) { cos(phases[it % PARTIALS]) }
        val oscillatorHz = DoubleArray(oscillatorCount) { i ->
            val p = i % PARTIALS
            val nominal = frequency * harmonics[p]
            val group = i / PARTIALS
            val offset = if (p == 0 || !options.sourceMotion) 0.0 else
                min(DETUNE_HZ_CEILING, nominal * (2.0.pow(DETUNE_CENTS / 1200.0) - 1.0)) * (group - 1)
            nominal + offset
        }
        val stepSin = DoubleArray(oscillatorCount) { sin(2.0 * PI * oscillatorHz[it] / rate) }
        val stepCos = DoubleArray(oscillatorCount) { cos(2.0 * PI * oscillatorHz[it] / rate) }
        // Stable, root-related modes now cover the upper material as well as the body.
        // The fundamental is supported without being reinforced by most of the bank.
        val modeSource = intArrayOf(0, 1, 2, 3, 5, 7, 9, 11, 15, 19, 23, 31)
        val modeReal = DoubleArray(modeSource.size)
        val modeImag = DoubleArray(modeSource.size)
        val poleReal = DoubleArray(modeSource.size)
        val poleImag = DoubleArray(modeSource.size)
        val injection = DoubleArray(modeSource.size)
        for (j in modeSource.indices) {
            val p = modeSource[j]
            val tail = (if (voice == FacetVoice.BLOOM) 1.7 else 1.25) *
                (1.1 - .55 * p / (PARTIALS - 1.0)) * (1.15 - .3 * chroma)
            val radius = exp(-ln(1000.0) / (tail * rate))
            val w = 2.0 * PI * frequency * harmonics[p] / rate
            poleReal[j] = radius * cos(w)
            poleImag[j] = radius * sin(w)
            injection[j] = 2.0 * (1.0 - radius)
        }
        val startPhase = if (initialAlignment == 1f) 0.0 else initialAlignment.toDouble()
        var phase = startPhase
        val phaseStep = rotor.toDouble() / rate
        val trace = ArrayList<FacetTrace>()
        val traceEvery = max(1, rate / 50)
        val route = FloatArray(ROUTES * 2)
        lookupInto(table, startPhase * TABLE_SIZE, route)
        val partial = DoubleArray(PARTIALS)
        var statePeak = 0.0
        val tailSeconds = min(seconds * .36, 1.1)
        val releaseStart = seconds - tailSeconds
        val releaseSeconds = min(tailSeconds * .6, .4)
        val attack = min(seconds * .18, if (voice == FacetVoice.BLOOM) .32 else .075)
        for (n in internal.indices) {
            val t = n.toDouble() / rate
            val a = smooth((t / attack).coerceIn(0.0, 1.0))
            val release = 1.0 - smooth(((t - releaseStart) / releaseSeconds).coerceIn(0.0, 1.0))
            val envelope = a * release * velocity
            if (rotor != 0f) lookupInto(table, phase * TABLE_SIZE, route)
            var reflected = 0.0
            for (p in 0 until PARTIALS) {
                var excitation = 0.0
                for (g in 0 until SOURCE_GROUPS) {
                    val source = if (options.sourceMotion) g * PARTIALS + p else p
                    val c = (g * PARTIALS + p) * 2
                    // Keep the illuminated carrier present while a quieter part of
                    // each beam beats around it. Detuning the entire beam made the
                    // upper spectrum periodically cancel and exposed the root again.
                    val center = if (options.sourceMotion) PARTIALS + p else p
                    val sourceSin = if (options.sourceMotion) .70 * oscillatorSin[center] + .30 * oscillatorSin[source] else oscillatorSin[p]
                    val sourceCos = if (options.sourceMotion) .70 * oscillatorCos[center] + .30 * oscillatorCos[source] else oscillatorCos[p]
                    excitation += sourceSin * route[c] + sourceCos * route[c + 1]
                }
                partial[p] = excitation * envelope
                reflected += partial[p]
            }
            var resonance = 0.0
            if (options.resonance) for (j in modeSource.indices) {
                val re = modeReal[j]
                val im = modeImag[j]
                modeReal[j] = poleReal[j] * re - poleImag[j] * im + injection[j] * partial[modeSource[j]]
                modeImag[j] = poleImag[j] * re + poleReal[j] * im
                statePeak = max(statePeak, max(abs(modeReal[j]), abs(modeImag[j])))
                resonance += modeReal[j] * (if (j == 0) .2 else .52 + .2 * modeSource[j] / (PARTIALS - 1.0))
            }
            val root = oscillatorSin[0] * .04 * illuminated * envelope
            internal[n] = (.52 * reflected + .68 * resonance + root).toFloat()
            if (n % traceEvery == 0) trace.add(FacetTrace(t.toFloat(), phase,
                List(PARTIALS) { p ->
                    var energy = 0.0
                    for (g in 0 until SOURCE_GROUPS) {
                        val c = (g * PARTIALS + p) * 2
                        energy += sqrt((route[c] * route[c] + route[c + 1] * route[c + 1]).toDouble())
                    }
                    energy.toFloat()
                }))
            for (i in oscillatorSin.indices) {
                val oldSin = oscillatorSin[i]
                oscillatorSin[i] = oldSin * stepCos[i] + oscillatorCos[i] * stepSin[i]
                oscillatorCos[i] = oscillatorCos[i] * stepCos[i] - oldSin * stepSin[i]
            }
            if (rotor != 0f) {
                phase += phaseStep
                if (phase >= 1.0) phase -= 1.0
            }
        }
        var raw = internal
        var currentRate = rate
        while (currentRate > Dsp.RATE) {
            raw = Resampler.resample(Snip(raw, 1, currentRate), currentRate / 2).samples
            currentRate /= 2
        }
        // Remove the finite render's mean and remaining subaudio energy before measuring.
        val mean = raw.sumOf { it.toDouble() } / raw.size.coerceAtLeast(1)
        val dc = Dsp.OnePole(Dsp.RATE)
        for (i in raw.indices) {
            val x = (raw[i] - mean).toFloat()
            raw[i] = x - dc.lp(x, 8f)
        }
        Dsp.fadeTail(raw, 15f)
        val leveled = raw.copyOf()
        Dsp.levelTo(leveled, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        var peak = 0f
        var sum = 0.0
        var squares = 0.0
        for (v in raw) { peak = max(peak, abs(v)); sum += v; squares += v * v.toDouble() }
        val (diversity, motion) = routingMeasures(trace)
        val diagnostics = FacetDiagnostics(
            preparationMillis, (System.nanoTime() - synthesisStart) / 1e6, peak,
            sqrt(squares / raw.size.coerceAtLeast(1)).toFloat(), (sum / raw.size.coerceAtLeast(1)).toFloat(),
            statePeak.toFloat(), rotor, startPhase, phase,
            (levels[lower] * (1.0 - blend) + levels[lower + 1] * blend).toFloat(),
            diversity, motion, options.oversample,
        )
        return FacetRender(Snip(leveled, 1, Dsp.RATE), raw, diagnostics, trace.toList())
    }

    private fun generatedBeams(illumination: Float, elevation: Float, chroma: Float, voice: FacetVoice): List<FacetBeam> =
        List(12) { b ->
            val angle = 2.0 * PI * b / 12.0
            val rank = ((b * 5) % 12) / 11.0
            val participation = .035 + .965 * smooth(((illumination * 1.35 - rank * .75) / .65).coerceIn(0.0, 1.0))
            FacetBeam(
                b, (if (b == 0) max(.3, participation) else participation).toFloat(),
                (elevation + .14 * sin(angle + .4)).toFloat().coerceIn(0f, 1f),
                (chroma + (.08 + .19 * chroma) * sin(angle * 2.0 + .8)).toFloat().coerceIn(0f, 1f),
                if (voice == FacetVoice.BLOOM) .65f else .38f,
            )
        }

    private data class Tile(
        val z0: Double, val z1: Double, val a0: Double, val a1: Double,
        val material: Double, val normalOffset: Double, val id: Int,
    )

    /** Alternating azimuth/equal-area-z subdivision gives exact area conservation. */
    private fun layout(depth: Int, seed: Int): List<Tile> {
        var tiles = (0 until 8).map { i ->
            Tile(if (i < 4) -1.0 else 0.0, if (i < 4) 0.0 else 1.0,
                (i % 4) * PI / 2.0, (i % 4 + 1) * PI / 2.0,
                variation(seed, i), .85 * variation(seed, i + 199), i + 1)
        }
        repeat(depth) { d ->
            tiles = tiles.flatMap { tile ->
                val delta = variation(seed, tile.id * 71 + d * 19) * .24 / sqrt(d + 1.0)
                val normalDelta = variation(seed, tile.id * 97 + d * 31 + 311) * .28 / sqrt(d + 1.0)
                if (d % 2 == 0) {
                    val mid = (tile.a0 + tile.a1) * .5
                    listOf(tile.copy(a1 = mid, material = tile.material + delta,
                        normalOffset = tile.normalOffset + normalDelta, id = tile.id * 2),
                        tile.copy(a0 = mid, material = tile.material - delta,
                            normalOffset = tile.normalOffset - normalDelta, id = tile.id * 2 + 1))
                } else {
                    val mid = (tile.z0 + tile.z1) * .5
                    listOf(tile.copy(z1 = mid, material = tile.material + delta,
                        normalOffset = tile.normalOffset + normalDelta, id = tile.id * 2),
                        tile.copy(z0 = mid, material = tile.material - delta,
                            normalOffset = tile.normalOffset - normalDelta, id = tile.id * 2 + 1))
                }
            }
        }
        return tiles
    }

    private fun variation(seed: Int, id: Int): Double = Random(Dsp.seedFor(seed, "tile", id)).nextDouble() * 2.0 - 1.0

    private fun prepare(
        count: Int, depth: Int, beams: List<FacetBeam>, phases: Array<DoubleArray>,
        illumination: Float, velocity: Float, guard: DoubleArray, seed: Int, harmonics: IntArray,
    ): Array<FloatArray> {
        val tiles = layout(depth, seed)
        check(tiles.size == count)
        val result = Array(ROUTES * 2) { FloatArray(TABLE_SIZE) }
        val exponent = 1 + depth * 3
        for (beam in beams) {
            if (beam.intensity == 0f) continue
            val azimuth = 2.0 * PI * beam.id / 12.0
            val kernel = DoubleArray(TABLE_SIZE) { i ->
                val theta = 2.0 * PI * i / TABLE_SIZE
                val incidence = .5 + .5 * cos(theta - azimuth)
                val reception = .5 + .5 * cos(2.0 * theta - azimuth - .7)
                incidence * incidence * reception.pow(exponent)
            }
            val mean = kernel.average()
            for (i in kernel.indices) kernel[i] = .07 + .93 * kernel[i] / mean
            val coverage = DoubleArray(tiles.size) { j ->
                val z = (tiles[j].z0 + tiles[j].z1) * .5
                val width = .25 + .68 * beam.width
                exp(-.5 * ((z - (beam.elevation * 2.0 - 1.0)) / width).pow(2.0)) / count
            }
            // Static coverage normalization preserves beam budget near either pole;
            // the instantaneous angular sum is deliberately never normalized.
            val coverageTotal = coverage.sum()
            for (j in tiles.indices) {
                val tile = tiles[j]
                val latitude = ((tile.z0 + tile.z1) * .25 + .5)
                val azimuth = (tile.a0 + tile.a1) * .5
                // Surface regions have distinct spectral materials. Latitude alone made
                // a uniform ring's copies cancel their spectral motion when summed.
                val azimuthColor = .5 + .5 * sin(azimuth + tile.material * 1.1)
                val color = (.30 * beam.color + .22 * latitude + .46 * azimuthColor + .16 * tile.material).coerceIn(0.0, 1.0)
                val spectrum = DoubleArray(PARTIALS) { p ->
                    val h = harmonics[p].toDouble()
                    val band = ln(h) / ln(harmonics.last().toDouble())
                    // A dense illuminated spectrum, rather than eight isolated sines.
                    // The palette tilts the source but leaves upper energy at its warm end.
                    val warmth = (if (p == 0) .65 else 1.0) / h.pow(.82 - .30 * beam.color)
                    val hollow = if (harmonics[p] % 2 == 0) 1.0 - .72 * sin(PI * beam.color) else 1.0
                    val illuminationBrightness = 1.0 + (illumination * .8 + velocity * .3) * band
                    val region = .14 + 1.65 * exp(-.5 * ((band - color) / .21).pow(2.0)) +
                        .55 * exp(-.5 * ((band - (1.0 - color)) / .18).pow(2.0))
                    warmth * hollow * illuminationBrightness * region
                }
                val budget = spectrum.sum()
                val scale = beam.intensity / 12.0 * coverage[j] / coverageTotal
                // Hierarchical normal deviations make irregular receivers rather than
                // uniformly shifted copies. Phase shifts preserve polynomial bandwidth
                // and each tile's angular mean, so the reference-energy budget survives.
                val offset = ((azimuth + tile.normalOffset) / (2.0 * PI) * TABLE_SIZE).toInt()
                for (p in 0 until PARTIALS) {
                    val amplitude = scale * spectrum[p] / budget * guard[p]
                    val c = amplitude * cos(phases[beam.id][p])
                    val s = amplitude * sin(phases[beam.id][p])
                    // Grouping happens while beam energy is reflected, so changing
                    // alignment changes which independently beating beams are heard.
                    val channel = ((beam.id % SOURCE_GROUPS) * PARTIALS + p) * 2
                    val real = result[channel]
                    val imag = result[channel + 1]
                    for (i in 0 until TABLE_SIZE) {
                        val weight = kernel[(i + offset) and (TABLE_SIZE - 1)]
                        real[i] += (weight * c).toFloat()
                        imag[i] += (weight * s).toFloat()
                    }
                }
            }
        }
        return result
    }

    /** Destructive mechanism control: retain one envelope and fixed spectral balance. */
    private fun useCommonGain(table: Array<FloatArray>) {
        val means = DoubleArray(ROUTES) { p ->
            (0 until TABLE_SIZE).sumOf { i -> sqrt((table[p * 2][i] * table[p * 2][i] + table[p * 2 + 1][i] * table[p * 2 + 1][i]).toDouble()) } / TABLE_SIZE
        }
        val phaseReal = DoubleArray(ROUTES)
        val phaseImag = DoubleArray(ROUTES)
        for (p in 0 until ROUTES) {
            val real = table[p * 2].average()
            val imag = table[p * 2 + 1].average()
            val magnitude = sqrt(real * real + imag * imag)
            phaseReal[p] = if (magnitude > 1e-15) real / magnitude else 1.0
            phaseImag[p] = if (magnitude > 1e-15) imag / magnitude else 0.0
        }
        val sum = means.sum()
        if (sum == 0.0) return
        for (i in 0 until TABLE_SIZE) {
            var gain = 0.0
            for (p in 0 until ROUTES) gain += sqrt((table[p * 2][i] * table[p * 2][i] + table[p * 2 + 1][i] * table[p * 2 + 1][i]).toDouble())
            for (p in 0 until ROUTES) {
                table[p * 2][i] = (gain * means[p] / sum * phaseReal[p]).toFloat()
                table[p * 2 + 1][i] = (gain * means[p] / sum * phaseImag[p]).toFloat()
            }
        }
    }

    /** Cubic periodic lookup; the interpolation basis is shared by all source groups. */
    private fun lookupInto(table: Array<FloatArray>, position: Double, output: FloatArray) {
        val i = position.toInt()
        val x = position - i
        val x2 = x * x
        val x3 = x2 * x
        val wa = -.5 * x + x2 - .5 * x3
        val wb = 1.0 - 2.5 * x2 + 1.5 * x3
        val wc = .5 * x + 2.0 * x2 - 1.5 * x3
        val wd = -.5 * x2 + .5 * x3
        val ia = (i - 1) and (TABLE_SIZE - 1)
        val ib = i and (TABLE_SIZE - 1)
        val ic = (i + 1) and (TABLE_SIZE - 1)
        val id = (i + 2) and (TABLE_SIZE - 1)
        for (c in output.indices) {
            val channel = table[c]
            output[c] = (wa * channel[ia] + wb * channel[ib] + wc * channel[ic] + wd * channel[id]).toFloat()
        }
    }

    private fun rotationHz(value: Float, voice: FacetVoice): Float {
        if (value == 0f) return 0f
        val positions = floatArrayOf(0f, .1f, .25f, .5f, .7f, .85f, 1f)
        val rates = floatArrayOf(0f, .025f, .1f, .5f, 2f, 8f, if (voice == FacetVoice.BLOOM) 24f else 40f)
        val j = (0 until positions.lastIndex).firstOrNull { value <= positions[it + 1] } ?: positions.lastIndex - 1
        val x = (value - positions[j]) / (positions[j + 1] - positions[j])
        return if (j == 0) rates[1] * x else (rates[j] * (rates[j + 1] / rates[j]).toDouble().pow(x.toDouble())).toFloat()
    }

    private fun smooth(value: Double): Double = value * value * (3.0 - 2.0 * value)

    private fun routingMeasures(trace: List<FacetTrace>): Pair<Float, Float> {
        if (trace.isEmpty()) return 0f to 0f
        var diversity = 0.0
        var motion = 0.0
        var previous: List<Float>? = null
        for (point in trace) {
            val sum = point.routeEnergy.sum().toDouble()
            if (sum <= 1e-12) continue
            val normalized = point.routeEnergy.map { (it / sum).toFloat() }
            val last = previous
            if (last != null) for (p in 0 until PARTIALS) diversity += abs(normalized[p] - last[p])
            previous = normalized
        }
        for (j in 1 until trace.size) for (p in 0 until PARTIALS)
            motion += abs(trace[j].routeEnergy[p] - trace[j - 1].routeEnergy[p])
        return (diversity / max(1, trace.size - 1)).toFloat() to (motion / max(1, trace.size - 1)).toFloat()
    }
}
