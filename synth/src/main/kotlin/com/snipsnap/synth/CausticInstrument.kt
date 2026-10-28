package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class CausticVoice { ALLOY, HOLLOW_MALLET, RUBBED_RING, TRAVELLING_CATCHES }

data class CausticInstrumentDiagnostics(
    val rawPeak: Double, val rawRms: Double, val rawDc: Double,
    val internalPeak: Double, val requestedHz: Double, val durationSeconds: Double,
    val geometryCacheHit: Boolean, val prepareNanos: Long, val audioNanos: Long,
    val finishNanos: Long, val renderNanos: Long, val modeFrequencies: DoubleArray,
    val modelVersion: Int, val oversample: Int,
)

/** Diagnostic captures only. [internalRaw] precedes filtering at 4x (or diagnostic 16x).
 * [raw] uses the original listening renderer's 8 Hz DC block and 8 ms fade;
 * [prelevel] uses the native house output before loudness makeup. */
data class CausticInstrumentProbe(
    val samples: FloatArray, val prelevel: FloatArray, val raw: FloatArray,
    val internalRaw: FloatArray, val sampleRate: Int,
    val diagnostics: CausticInstrumentDiagnostics,
)

/**
 * Finite instruments sharing coherent circular source-to-pickup paths.
 * ALLOY retains its original model-1 calculation; the other voices change contact and body.
 * BODY changes excitation pressure, attack and decay between the heard Alloy and Fuller-body
 * endpoints. LENGTH shortens source decay and modal release, rather than cutting an unchanged tail.
 * MIDI and velocity are explicit recipe fields. HOLD is reserved and positive values are refused.
 *
 * The bank is linear and uncoupled. A delayed exponential/carrier has an exact discrete forced
 * response plus a free modal response, so causal arrivals can be folded into 48 shared forcing
 * poles and 24 free poles. Every pole decays; no feedback limiter or output normalization conceals
 * unstable state. This avoids evaluating the complete modal drive and diagnostics at every sample.
 * Geometry alone is cached in a bounded LRU; note, velocity and BODY never share mutable state.
 */
object CausticInstrument {
    const val MODEL_VERSION = 1
    const val RATE = Dsp.RATE
    const val DEFAULT_MIDI = 60
    const val MIDI_MIN = 36
    const val MIDI_MAX = 84
    private const val RAYS = 256
    private const val APERTURE = .05
    private const val DELAY_CYCLES = .65
    private const val MODES = 24
    private const val FORCED = 48
    private const val POLES = FORCED + MODES
    private const val CACHE_LIMIT = 32
    private const val DIRECT_ROOT = .035
    private const val TAU = 2.0 * PI

    fun supportsModel(model: Int) = model == MODEL_VERSION

    fun macrosFor(voice: CausticVoice, model: Int = MODEL_VERSION): List<MacroSpec> {
        require(supportsModel(model)) { "Unsupported CAUSTIC instrument model $model" }
        return when (voice) {
            CausticVoice.ALLOY -> listOf(
                MacroSpec("SOURCE", .5f, .5f), MacroSpec("FOCUS", .19f, .19f),
                MacroSpec("BODY", 1f, 1f), MacroSpec("LENGTH", 1f, 1f), MacroSpec("HOLD", 0f, 0f),
            )
            CausticVoice.HOLLOW_MALLET, CausticVoice.RUBBED_RING -> listOf(
                MacroSpec("SOURCE", .5f, .5f), MacroSpec("FOCUS", .19f, .19f),
                MacroSpec("BODY", .5f, .5f), MacroSpec("LENGTH", 1f, 1f), MacroSpec("HOLD", 0f, 0f),
            )
            CausticVoice.TRAVELLING_CATCHES -> listOf(
                MacroSpec("SOURCE", .5f, .5f), MacroSpec("FOCUS", .19f, .19f),
                MacroSpec("BODY", 0f, 0f), MacroSpec("LENGTH", 1f, 1f), MacroSpec("HOLD", 0f, 0f),
            )
        }
    }

    fun defaults(voice: CausticVoice, model: Int = MODEL_VERSION): Map<String, Float> =
        macrosFor(voice, model).associate { it.name to it.default }

    fun settled(voice: CausticVoice, macros: Map<String, Float>, model: Int = MODEL_VERSION): Map<String, Float> =
        defaults(voice, model).toMutableMap().also { values ->
            for ((name, value) in macros) if (name in values && value.isFinite())
                values[name] = value.coerceIn(0f, 1f)
        }

    private fun finiteMacros(voice: CausticVoice, macros: Map<String, Float>, model: Int): Map<String, Float> =
        settled(voice, macros, model).also {
            require(it.getValue("HOLD") == 0f) { "CAUSTIC HOLD is reserved; this model renders finite notes only" }
        }

    fun durationSeconds(voice: CausticVoice, macros: Map<String, Float> = emptyMap(), model: Int = MODEL_VERSION): Double =
        .5 + 7.5 * finiteMacros(voice, macros, model).getValue("LENGTH")

    fun drumClassFor(voice: CausticVoice, macros: Map<String, Float> = emptyMap(), model: Int = MODEL_VERSION): DrumClass {
        val frames = (durationSeconds(voice, macros, model) * RATE).roundToInt()
        return if (frames > RATE * 1.5) DrumClass.LOOP else DrumClass.TONAL
    }

    fun scramble(voice: CausticVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = settled(voice, near?.macros ?: emptyMap())
        return Dsp.scrambleNear(base, temperature.takeIf { it.isFinite() } ?: .35f, random).toMutableMap().also {
            it["HOLD"] = 0f
        }
    }

    private data class GeometryKey(val source: Double, val focus: Double, val rays: Int, val aperture: Double)
    private val geometries = object : LinkedHashMap<GeometryKey, CausticGeometry.Prepared>(CACHE_LIMIT, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GeometryKey, CausticGeometry.Prepared>?) = size > CACHE_LIMIT
    }
    internal fun clearGeometryCache() = synchronized(geometries) { geometries.clear() }
    internal fun geometryCacheSize(): Int = synchronized(geometries) { geometries.size }

    private fun geometry(source: Double, focus: Double): Pair<CausticGeometry.Prepared, Boolean> = synchronized(geometries) {
        val key = GeometryKey(source, focus, RAYS, APERTURE)
        geometries[key]?.let { return@synchronized it to true }
        CausticGeometry.prepare(source, focus, RAYS, APERTURE).also { geometries[key] = it } to false
    }

    private fun interpolate(a: Double, b: Double, t: Double): Double = when (t) {
        0.0 -> a
        1.0 -> b
        else -> exp(ln(a) + (ln(b) - ln(a)) * t)
    }

    private fun profile(body: Double, length: Double): Caustic.RichProfile {
        val scale = (.5 + 7.5 * length) / 8.0
        return Caustic.RichProfile(Caustic.Character.ALLOY,
            interpolate(.64, 1.5, body) * scale, interpolate(.006, .035, body),
            .006, .0005, interpolate(70.0, 210.0, body), 1_500.0, .68)
    }

    private data class Arrival(val frame: Int, val re: DoubleArray, val im: DoubleArray)
    private data class Plan(
        val arrivals: List<Arrival>, val rotateRe: DoubleArray, val rotateIm: DoubleArray,
        val expires: IntArray, val initialIm: DoubleArray, val frequencies: DoubleArray,
        val output: Double, val cacheHit: Boolean, val prepareNanos: Long,
    )

    /** Complex division is only used during preparation, never in the sample loop. */
    private fun quotient(ar: Double, ai: Double, br: Double, bi: Double): Pair<Double, Double> {
        val d = br * br + bi * bi
        check(d > 1e-30 && d.isFinite()) { "CAUSTIC degenerate discrete response" }
        return (ar * br + ai * bi) / d to (ai * br - ar * bi) / d
    }

    private fun prepare(m: Map<String, Float>, midi: Int, velocity: Double, oversample: Int): Plan {
        val started = System.nanoTime()
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val rate = RATE * oversample
        val body = m.getValue("BODY").toDouble()
        val length = m.getValue("LENGTH").toDouble()
        val lengthScale = (.5 + 7.5 * length) / 8.0
        val p = profile(body, length)
        val (geometry, cacheHit) = geometry(m.getValue("SOURCE").toDouble(), m.getValue("FOCUS").toDouble())
        val rates = doubleArrayOf(p.slow, p.fast, p.edgeSlow, p.edgeFast)
        val rotationRe = DoubleArray(POLES)
        val rotationIm = DoubleArray(POLES)
        val frequencies = DoubleArray(MODES) { hz * p.frequency(it) }
        val initialIm = DoubleArray(POLES)
        for (j in 0..3) for (h in 0..11) {
            val index = j * 12 + h
            val r = exp(-rates[j] / rate)
            val angle = TAU * hz * (h + 1) / rate
            rotationRe[index] = r * cos(angle)
            rotationIm[index] = r * sin(angle)
        }
        for (mode in 0 until MODES) {
            val r = exp(-ln(1000.0) / (p.t60(mode) * lengthScale * rate))
            val angle = TAU * frequencies[mode] / rate
            rotationRe[FORCED + mode] = r * cos(angle)
            rotationIm[FORCED + mode] = r * sin(angle)
        }
        for (i in rotationRe.indices) check(rotationRe[i] * rotationRe[i] + rotationIm[i] * rotationIm[i] < 1.0)
        initialIm[0] = -DIRECT_ROOT * velocity
        initialIm[12] = DIRECT_ROOT * velocity

        val dpr = DoubleArray(MODES * 4); val dpi = DoubleArray(MODES * 4)
        val dmr = DoubleArray(MODES * 4); val dmi = DoubleArray(MODES * 4)
        val apr = DoubleArray(MODES * 4); val api = DoubleArray(MODES * 4)
        val amr = DoubleArray(MODES * 4); val ami = DoubleArray(MODES * 4)
        for (mode in 0 until MODES) for (j in 0..3) {
            val at = mode * 4 + j
            val q = j * 12 + mode % 12
            val ar = rotationRe[FORCED + mode]; val ai = rotationIm[FORCED + mode]
            val qr = rotationRe[q]; val qi = rotationIm[q]
            // P = (-i*T/2)*q/(q-a); R uses the negative-frequency source pole.
            val plus = quotient(qr, qi, qr - ar, qi - ai)
            val minus = quotient(qr, -qi, qr - ar, -qi - ai)
            dpr[at] = plus.second / (2.0 * rate); dpi[at] = -plus.first / (2.0 * rate)
            dmr[at] = minus.second / (2.0 * rate); dmi[at] = -minus.first / (2.0 * rate)
            val ap = quotient(ar, ai, qr, qi); val am = quotient(ar, ai, qr, -qi)
            apr[at] = ap.first; api[at] = ap.second; amr[at] = am.first; ami[at] = am.second
        }
        val amplitude = Array(4) { j -> DoubleArray(12) { h ->
            val edge = j >= 2
            (if (edge) p.edgeForce else p.force) * p.source(h + 1, hz, velocity, edge) *
                (if ((j and 1) == 0) 1.0 else -1.0)
        } }
        val spatial = Array(MODES) { mode -> DoubleArray(9) { pickup ->
            p.gain(mode) * p.band(pickup, mode % 12 + 1) * p.participation(mode, pickup)
        } }
        val schedule = TreeMap<Int, Pair<DoubleArray, DoubleArray>>()
        val kr = DoubleArray(48); val ki = DoubleArray(48)
        for (path in geometry.paths) {
            val delay = path.delayR * DELAY_CYCLES / hz
            val frame = ceil(delay * rate).toInt()
            val elapsed = (frame.toDouble() / rate - delay).coerceAtLeast(0.0)
            val arrays = schedule.getOrPut(frame) { DoubleArray(POLES) to DoubleArray(POLES) }
            for (j in 0..3) for (h in 0..11) {
                val at = j * 12 + h
                val a = amplitude[j][h] * exp(-rates[j] * elapsed)
                val phase = TAU * hz * (h + 1) * elapsed
                kr[at] = a * sin(phase)
                ki[at] = -a * cos(phase)
            }
            for (mode in 0 until MODES) {
                val weight = path.weight * spatial[mode][path.pickup]
                for (j in 0..3) {
                    val at = mode * 4 + j
                    val q = j * 12 + mode % 12
                    val real = kr[q] * weight; val imag = ki[q] * weight
                    val pr = dpr[at] * real - dpi[at] * imag
                    val pi = dpr[at] * imag + dpi[at] * real
                    val rr = dmr[at] * real + dmi[at] * imag
                    val ri = -dmr[at] * imag + dmi[at] * real
                    val sr = -(apr[at] * pr - api[at] * pi + amr[at] * rr - ami[at] * ri)
                    val si = -(apr[at] * pi + api[at] * pr + amr[at] * ri + ami[at] * rr)
                    arrays.first[q] -= pi + ri
                    arrays.second[q] += pr - rr
                    arrays.first[FORCED + mode] -= si
                    arrays.second[FORCED + mode] += sr
                }
            }
        }
        val last = schedule.lastKeyOrNull() ?: 0
        // Forced attack poles below exp(-45) contribute below float precision, even after cancellation.
        // Free body poles are retained for the complete capture. No audible tail is noise-gated.
        val expires = IntArray(POLES) { i -> if (i < FORCED)
            last + ceil(45.0 * rate / rates[i / 12]).toInt() else Int.MAX_VALUE }
        return Plan(schedule.map { Arrival(it.key, it.value.first, it.value.second) }, rotationRe, rotationIm,
            expires, initialIm, frequencies, p.output, cacheHit, System.nanoTime() - started)
    }

    private fun <K, V> TreeMap<K, V>.lastKeyOrNull(): K? = if (isEmpty()) null else lastKey()

    /** Four spatial contacts excite one linear body before a single output stage.
     * Their absolute force amplitudes sum to one. This bounds the contact-force budget;
     * overlapping coherent contacts do not imply an acoustic-energy conservation claim.
     * Whole-period launch times retain the root heard in the published listening study. */
    private fun prepareTravelling(m: Map<String, Float>, midi: Int, velocity: Double, oversample: Int): Plan {
        val started = System.nanoTime()
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val rate = RATE * oversample
        val scale = (.5 + 7.5 * m.getValue("LENGTH")) / 8.0
        val sources = floatArrayOf(0f, .25f, .6f, 1f)
        val times = doubleArrayOf(0.0, .12, .28, .48)
        val amplitudes = doubleArrayOf(.75, .65, .55, .45)
        val budget = amplitudes.sumOf { abs(it) }
        val schedule = TreeMap<Int, Pair<DoubleArray, DoubleArray>>()
        var first: Plan? = null
        var allCached = true
        val expires = IntArray(POLES)
        for (contact in sources.indices) {
            stopIfUnwanted()
            val source = (sources[contact] + (m.getValue("SOURCE") - .5f) * .7f).coerceIn(0f, 1f)
            val plan = prepare(m + ("SOURCE" to source), midi, velocity, oversample)
            if (first == null) first = plan
            allCached = allCached && plan.cacheHit
            val frame = ((times[contact] * scale * hz).roundToInt() / hz * rate).roundToInt()
            val gain = amplitudes[contact] / budget
            val initial = schedule.getOrPut(frame) { DoubleArray(POLES) to DoubleArray(POLES) }
            for (i in 0 until POLES) initial.second[i] += plan.initialIm[i] * gain
            for (event in plan.arrivals) {
                val at = schedule.getOrPut(frame + event.frame) { DoubleArray(POLES) to DoubleArray(POLES) }
                for (i in 0 until POLES) {
                    at.first[i] += event.re[i] * gain
                    at.second[i] += event.im[i] * gain
                }
            }
            for (i in 0 until POLES) expires[i] = maxOf(expires[i],
                if (plan.expires[i] == Int.MAX_VALUE) Int.MAX_VALUE else frame + plan.expires[i])
        }
        val body = checkNotNull(first)
        return Plan(schedule.map { Arrival(it.key, it.value.first, it.value.second) },
            body.rotateRe, body.rotateIm, expires, DoubleArray(POLES), body.frequencies,
            body.output, allCached, System.nanoTime() - started)
    }

    private data class Audio(val internal: FloatArray, val peak: Double, val nanos: Long)
    private fun evolve(plan: Plan, frames: Int, oversample: Int): Audio {
        val started = System.nanoTime()
        val raw = FloatArray(frames * oversample)
        val re = DoubleArray(POLES)
        val im = plan.initialIm.copyOf()
        val cr = plan.rotateRe.copyOf(); val ci = plan.rotateIm.copyOf()
        val expires = plan.expires.copyOf()
        var count = POLES
        var next = 0
        val lastArrival = plan.arrivals.lastOrNull()?.frame ?: 0
        var nextExpiry = expires.minOrNull() ?: Int.MAX_VALUE
        var peak = 0.0
        for (n in raw.indices) {
            if ((n and 8191) == 0) stopIfUnwanted()
            while (next < plan.arrivals.size && plan.arrivals[next].frame == n) {
                val event = plan.arrivals[next++]
                for (i in 0 until POLES) { re[i] += event.re[i]; im[i] += event.im[i] }
            }
            // Expiry is rare. Prune at its next scheduled frame, preserving the same swap order,
            // instead of loading an expiry and branching for every live pole at every sample.
            if (n > lastArrival && n >= nextExpiry) {
                var i = 0
                while (i < count) {
                    if (n >= expires[i]) {
                        --count
                        re[i] = re[count]; im[i] = im[count]; cr[i] = cr[count]; ci[i] = ci[count]; expires[i] = expires[count]
                    } else ++i
                }
                nextExpiry = Int.MAX_VALUE
                for (i in 0 until count) nextExpiry = minOf(nextExpiry, expires[i])
            }
            var sum = 0.0
            var i = 0
            while (i < count) {
                val real = re[i]; val imag = im[i]
                sum += real
                re[i] = real * cr[i] - imag * ci[i]
                im[i] = real * ci[i] + imag * cr[i]
                ++i
            }
            val value = sum * plan.output
            check(value.isFinite() && abs(value) < 8) { "CAUSTIC native raw state exceeded its bound at $n: $value" }
            raw[n] = value.toFloat()
            peak = maxOf(peak, abs(value))
        }
        stopIfUnwanted()
        return Audio(raw, peak, System.nanoTime() - started)
    }

    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("CAUSTIC render no longer wanted")
    }

    private fun decimate(raw: FloatArray, oversample: Int): FloatArray {
        Tide.bandLimit(raw, RATE * oversample)
        stopIfUnwanted()
        return if (oversample == 4) Dsp.decimate(raw, RATE) else Dsp.decimate(Dsp.decimate(raw, RATE * 4), RATE)
    }

    private fun condition(samples: FloatArray, legacy: Boolean): FloatArray {
        val out = samples.copyOf()
        val mean = if (legacy) 0f else (out.sumOf { it.toDouble() } / out.size.coerceAtLeast(1)).toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in out.indices) { val value = out[i] - mean; out[i] = value - hp.lp(value, if (legacy) 8f else 20f) }
        Dsp.fadeTail(out, ms = if (legacy) 8f else 4f, rate = RATE)
        return out
    }

    fun render(voice: CausticVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = .75f, model: Int = MODEL_VERSION): Snip =
        Snip(synthesize(voice, macros, midi, velocity, model, null, 4, true, false).samples, 1, RATE)

    /** Optional [seconds] captures a prefix without changing LENGTH or its source/modal release.
     * Diagnostic 16x uses the same source and modes with finer discrete rotation/kick steps. */
    fun probe(voice: CausticVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = .75f, normalize: Boolean = true, model: Int = MODEL_VERSION,
        seconds: Double? = null, oversample: Int = 4): CausticInstrumentProbe =
        synthesize(voice, macros, midi, velocity, model, seconds, oversample, normalize, true)

    private fun synthesize(voice: CausticVoice, macros: Map<String, Float>, midi: Int, velocity: Float,
        model: Int, seconds: Double?, oversample: Int, normalize: Boolean, record: Boolean): CausticInstrumentProbe {
        stopIfUnwanted()
        val started = System.nanoTime()
        require(midi in MIDI_MIN..MIDI_MAX) { "CAUSTIC MIDI must be $MIDI_MIN..$MIDI_MAX" }
        require(oversample == 4 || oversample == 16) { "CAUSTIC diagnostic oversample must be 4 or 16" }
        val m = finiteMacros(voice, macros, model)
        val duration = .5 + 7.5 * m.getValue("LENGTH")
        require(seconds == null || seconds.isFinite() && seconds in .005..duration) { "CAUSTIC diagnostic duration outside note" }
        val frames = ((seconds ?: duration) * RATE).roundToInt()
        val vel = (velocity.takeIf { it.isFinite() } ?: .75f).coerceIn(0f, 1f).toDouble()
        val audio: Audio
        val frequencies: DoubleArray
        val cacheHit: Boolean
        val prepareNanos: Long
        when (voice) {
            CausticVoice.ALLOY, CausticVoice.TRAVELLING_CATCHES -> {
                val plan = if (voice == CausticVoice.ALLOY) prepare(m, midi, vel, oversample)
                    else prepareTravelling(m, midi, vel, oversample)
                audio = evolve(plan, frames, oversample)
                frequencies = plan.frequencies
                cacheHit = plan.cacheHit
                prepareNanos = plan.prepareNanos
            }
            CausticVoice.HOLLOW_MALLET, CausticVoice.RUBBED_RING -> {
                val preparationStarted = System.nanoTime()
                val (prepared, cached) = geometry(m.getValue("SOURCE").toDouble(), m.getValue("FOCUS").toDouble())
                cacheHit = cached
                prepareNanos = System.nanoTime() - preparationStarted
                val audioStarted = System.nanoTime()
                val rendered = CausticCharacters.render(voice, m, midi, vel, frames, oversample, prepared)
                var peak = 0.0
                for (value in rendered.internal) {
                    check(value.isFinite() && abs(value) < 8f) { "CAUSTIC character raw state exceeded its bound: $value" }
                    peak = maxOf(peak, abs(value.toDouble()))
                }
                audio = Audio(rendered.internal, peak, System.nanoTime() - audioStarted)
                frequencies = rendered.frequencies
            }
        }
        val internalRaw = if (record) audio.internal.copyOf() else FloatArray(0)
        val finishStarted = System.nanoTime()
        val decimated = decimate(audio.internal, oversample)
        stopIfUnwanted()
        val raw = if (record) condition(decimated, true) else FloatArray(0)
        val prelevel = condition(decimated, false)
        val samples = prelevel.copyOf()
        if (normalize && vel > 0.0) Dsp.levelTo(samples, RATE, Dsp.MELODIC_LOUDNESS_TARGET * vel.toFloat().pow(.4f))
        stopIfUnwanted()
        val finishNanos = System.nanoTime() - finishStarted
        var peak = 0.0; var sum = 0.0; var square = 0.0
        if (record) for (x in prelevel) { peak = maxOf(peak, abs(x.toDouble())); sum += x; square += x.toDouble() * x }
        val n = prelevel.size.coerceAtLeast(1)
        val diagnostics = CausticInstrumentDiagnostics(peak, sqrt(square / n), sum / n, audio.peak,
            440.0 * 2.0.pow((midi - 69) / 12.0), duration, cacheHit, prepareNanos, audio.nanos,
            finishNanos, System.nanoTime() - started, frequencies.copyOf(), model, oversample)
        return CausticInstrumentProbe(samples, prelevel, raw, internalRaw, RATE, diagnostics)
    }
}
