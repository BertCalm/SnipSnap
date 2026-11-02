package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Measured raw state/routing evidence. These are not listening-acceptance claims. */
data class CausticSustainDiagnostics(
    val fittedHz: Double, val plannedPitchCents: Double, val loopFrames: Int,
    val prefixCycles: Int, val stateResidual: Double, val continuationError: Double,
    val rawPeak: Double, val sourceMin: Double, val sourceMax: Double,
    val focusMin: Double, val focusMax: Double, val collectionMin: Double,
    val collectionMax: Double, val maxDelayR: Double, val geometrySnapshots: Int,
    val firL1Bound: Double, val prepareNanos: Long, val renderNanos: Long,
    val periodicStateResidual: Double = 0.0, val rawSeamError: Double = 0.0,
)

/**
 * Version-2 excitation/routing, isolated from the frozen finite model-1 arithmetic.
 *
 * Motion uses a partition of unity at source-emission times. Each window goes
 * through the actual circle's signed fractional-delay modal reception kernel;
 * the resulting forces then drive fixed damped modes. Interpolating emission
 * routes is a sampled moving-path model, not a physical Doppler simulation and
 * not a crossfade of already-ringing outputs. No surviving path is renormalized.
 *
 * Held sources and routing repeat. The affine one-period modal map supplies an
 * exact periodic reference state, while the delivered trace starts from ZERO
 * with causal FIR history and an actual attack. Genuine further cycles settle
 * and continue that state. No audio cycle is copied to manufacture seam proof.
 * Filtering/leveling/cropping belongs to the caller, over this complete trace.
 */
internal object CausticSustain {
    data class HeldRaw(val internal: FloatArray, val loopStartFrame: Int,
        val loopFrames: Int, val playedEndFrame: Int, val frequencies: DoubleArray,
        val diagnostics: CausticSustainDiagnostics)
    data class PeriodProbe(val beforeRe: Double, val beforeIm: Double,
        val afterRe: Double, val afterIm: Double, val first: DoubleArray, val second: DoubleArray)
    data class MetalForceProbe(val analytic: Array<FloatArray>, val fft: Array<FloatArray>,
        val analyticOnset: Array<FloatArray>, val fftOnset: Array<FloatArray>)
    private data class BodyMode(val index: Int, val frequency: Double, val t60: Double,
        val gain: Double, val spatial: DoubleArray)
    private data class SourceGroup(val source: DoubleArray, val modes: IntArray,
        val sourcePosition: Double, val radiation: Boolean = false, val trajectoryFrames: Int = 0)
    private data class ExpTerm(val from: Int, val until: Int, val origin: Int,
        val lambda: Double, val amplitude: Double, val phase: Double = 0.0)
    private data class ForcePole(val mode: Int, val lambda: Double, val omega: Double)
    private data class SparseKernel(val offsets: IntArray, val weights: DoubleArray)
    private data class ForcePacket(val at: Int, val pole: Int, val kernel: SparseKernel,
        val fr: Double, val fi: Double, val sr: Double, val si: Double)
    private data class EmissionWindow(val center: Int, val left: Int, val right: Int, val geometry: Int)
    private data class MetalFields(val steady: Array<FloatArray>, val onset: Array<FloatArray>)
    private data class GeometryKey(val source: Double, val focus: Double, val motion: Double, val steps: Int)
    private data class GeometryTable(val points: List<CausticGeometry.Prepared>,
        val sources: DoubleArray, val focuses: DoubleArray)
    private data class Kernel(val samples: DoubleArray)
    private class BasisKey(val geometry: CausticGeometry.Prepared, val hz: Double, val rate: Int) {
        override fun equals(other: Any?) = other is BasisKey && geometry === other.geometry && hz == other.hz && rate == other.rate
        override fun hashCode() = 31 * (31 * System.identityHashCode(geometry) + hz.hashCode()) + rate
    }
    private data class ReceptionBasis(val length: Int, val pickups: Array<SparseKernel>, val bytes: Long)
    private data class State(var re: Double = 0.0, var im: Double = 0.0)
    private data class CycleReference(val before: State, val peak: Double, val closure: Double)
    private data class Evolved(val raw: FloatArray, val stateError: Double)
    private class Evidence {
        private val included = HashSet<GeometryTable>()
        var sourceMin = Double.POSITIVE_INFINITY; var sourceMax = Double.NEGATIVE_INFINITY
        var focusMin = Double.POSITIVE_INFINITY; var focusMax = Double.NEGATIVE_INFINITY
        var collectionMin = Double.POSITIVE_INFINITY; var collectionMax = Double.NEGATIVE_INFINITY
        var delay = 0.0; var fir = 0.0; var snapshots = 0
        fun include(table: GeometryTable) {
            if (!included.add(table)) return
            snapshots += table.points.size
            for (i in table.points.indices) {
                sourceMin = min(sourceMin, table.sources[i]); sourceMax = max(sourceMax, table.sources[i])
                focusMin = min(focusMin, table.focuses[i]); focusMax = max(focusMax, table.focuses[i])
                val g = table.points[i]
                collectionMin = min(collectionMin, g.densities.sum()); collectionMax = max(collectionMax, g.densities.sum())
                delay = max(delay, g.paths.maxOfOrNull { it.delayR } ?: 0.0)
            }
        }
    }
    private const val TAU = 2.0 * PI
    private const val DELAY_CYCLES = .65
    private const val MOTION_STEPS = 32
    private const val CACHE_LIMIT = 6
    private const val RECEPTION_CACHE_BYTES = 8L * 1024 * 1024
    // Fixed held-drive headroom, shared by every position; no per-position makeup.
    private const val HELD_FORCE_GAIN = .25
    private val geometryCache = object : LinkedHashMap<GeometryKey, GeometryTable>(CACHE_LIMIT, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GeometryKey, GeometryTable>?) = size > CACHE_LIMIT
    }
    private val receptionCache = LinkedHashMap<BasisKey, ReceptionBasis>(32, .75f, true)
    private var receptionBytes = 0L
    private fun stop(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("CAUSTIC sustain render no longer wanted")
    }
    private fun value(m: Map<String, Float>, key: String, default: Double): Double =
        m[key]?.takeIf { it.isFinite() }?.toDouble() ?: default
    private fun length(m: Map<String, Float>) = value(m, "LENGTH", 1.0).coerceAtLeast(0.0)
    private fun motion(m: Map<String, Float>) = value(m, "MOTION", 0.0).coerceIn(0.0, 1.0)
    private fun body(voice: CausticVoice, m: Map<String, Float>) = value(m, "BODY", when (voice) {
        CausticVoice.ALLOY -> 1.0; CausticVoice.TRAVELLING_CATCHES -> 0.0; else -> .5
    }).coerceIn(0.0, 1.0)
    private fun nominalHz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun periods(midi: Int, length: Double) = max(4, (nominalHz(midi) * (.65 + .35 * length.coerceIn(0.0, 1.0)) / 4).roundToInt() * 4)

    fun loopFrameCount(midi: Int, length: Float = 1f): Int {
        require(midi in 36..84)
        val l = length.takeIf { it.isFinite() }?.toDouble() ?: 1.0
        return (periods(midi, l) * Dsp.RATE / nominalHz(midi)).roundToInt()
    }
    internal fun geometryCacheSize(): Int = synchronized(geometryCache) { geometryCache.size }
    internal fun receptionCacheBytes(): Long = synchronized(receptionCache) { receptionBytes }
    internal fun clearGeometryCache() {
        synchronized(geometryCache) { geometryCache.clear() }
        synchronized(receptionCache) { receptionCache.clear(); receptionBytes = 0 }
    }

    private fun table(source: Double, focus: Double, amount: Double, cancelled: () -> Boolean): GeometryTable {
        val steps = if (amount == 0.0) 1 else MOTION_STEPS
        val key = GeometryKey(source, focus, amount, steps)
        synchronized(geometryCache) { geometryCache[key]?.let { return it } }
        val sources = DoubleArray(steps); val focuses = DoubleArray(steps)
        val points = List(steps) { i ->
            stop(cancelled)
            val phase = TAU * i / steps
            sources[i] = (source + .24 * amount * sin(phase)).coerceIn(0.0, 1.0)
            focuses[i] = (focus + .18 * amount * sin(phase)).coerceIn(0.0, 1.0)
            CausticGeometry.prepare(sources[i], focuses[i])
        }
        val result = GeometryTable(points, sources, focuses)
        synchronized(geometryCache) { geometryCache[key] = result }
        return result
    }

    private fun alloyProfile(m: Map<String, Float>, b: Double): Caustic.RichProfile {
        val scale = (.5 + 7.5 * length(m)) / 8.0
        fun blend(a: Double, z: Double) = when (b) { 0.0 -> a; 1.0 -> z; else -> exp(ln(a) + (ln(z) - ln(a)) * b) }
        return Caustic.RichProfile(Caustic.Character.ALLOY, blend(.64, 1.5) * scale,
            blend(.006, .035), .006, .0005, blend(70.0, 210.0), 1500.0, .68)
    }
    private fun bodyModes(voice: CausticVoice, m: Map<String, Float>, hz: Double): List<BodyMode> {
        val b = body(voice, m); val scale = (.5 + 7.5 * length(m)) / 8.0
        if (voice == CausticVoice.ALLOY || voice == CausticVoice.TRAVELLING_CATCHES) {
            val p = alloyProfile(m, b)
            return List(24) { i -> BodyMode(i, hz * p.frequency(i), p.t60(i) * scale, p.gain(i) * p.output,
                DoubleArray(9) { pickup -> p.band(pickup, i % 12 + 1) * p.participation(i, pickup) }) }
        }
        val modes = if (voice == CausticVoice.HOLLOW_MALLET) CausticCharacters.hollowModes else CausticCharacters.rubbedModes
        val damping = 2.0.pow((2 * b - 1) * if (voice == CausticVoice.HOLLOW_MALLET) .30 else .20)
        val fixed = if (voice == CausticVoice.HOLLOW_MALLET) .45 else .16
        return modes.mapIndexed { i, mode ->
            val decay = mode.t60 * scale * damping
            val t60 = if (voice == CausticVoice.HOLLOW_MALLET) max(decay, 12.0 / hz * mode.t60 / .46) else decay
            BodyMode(i, mode.ratio * hz, t60, mode.gain * fixed, DoubleArray(9) { pickup ->
                val y = (pickup - 4) / 4.0
                val participation = if (i == 0) .92 + .08 * cos(PI * y)
                else if (voice == CausticVoice.HOLLOW_MALLET)
                    .28 + .72 * cos(PI * (.30 + .16 * (i % 4)) * (y + .12 * sin(i.toDouble())))
                else .22 + .78 * cos(PI * (.35 + .19 * (i % 4)) * (y + .20 * cos(i.toDouble())))
                participation / (1.0 + (mode.ratio * (.035 + .085 * pickup / 8.0)).pow(1.6))
            })
        }
    }

    private fun shiftedSource(base: Double, contact: Int): Double {
        val positions = floatArrayOf(0f, .25f, .6f, 1f)
        // Preserve the original finite contact-location Float law.
        return (positions[contact] + (base.toFloat() - .5f) * .7f).coerceIn(0f, 1f).toDouble()
    }
    private fun finiteGroups(voice: CausticVoice, m: Map<String, Float>, midi: Int, velocity: Double,
        hz: Double, frames: Int, rate: Int, cancelled: () -> Boolean): Sequence<SourceGroup> = sequence {
        val source = value(m, "SOURCE", .5).coerceIn(0.0, 1.0)
        val b = body(voice, m)
        if (voice == CausticVoice.HOLLOW_MALLET || voice == CausticVoice.RUBBED_RING) {
            val scale = (.5 + 7.5 * length(m)) / 8.0
            val pair = if (voice == CausticVoice.HOLLOW_MALLET)
                CausticCharacters.hollowSource(hz, midi, velocity, 2 * b - 1, scale, rate)
            else CausticCharacters.rubbedSource(hz, midi, velocity, 2 * b - 1, scale, rate)
            val force = if (voice == CausticVoice.HOLLOW_MALLET) 1000.0 else 120.0
            yield(SourceGroup(DoubleArray(pair.first.size) { pair.first[it] * force },
                IntArray(if (voice == CausticVoice.HOLLOW_MALLET) 6 else 7) { it }, source,
                trajectoryFrames = pair.first.size))
            pair.second?.let { radiation -> yield(SourceGroup(radiation, intArrayOf(0), source, true, pair.first.size)) }
        } else {
            val p = alloyProfile(m, b)
            val contacts = if (voice == CausticVoice.TRAVELLING_CATCHES) 4 else 1
            val times = doubleArrayOf(0.0, .12, .28, .48)
            val gains = doubleArrayOf(.75 / 2.4, .65 / 2.4, .55 / 2.4, .45 / 2.4)
            val scale = (.5 + 7.5 * length(m)) / 8.0
            val lastLaunch = if (contacts == 1) 0 else ((times[3] * scale * hz).roundToInt() / hz * rate).roundToInt()
            val trajectory = lastLaunch + ceil(3 * p.decay * rate).toInt()
            for (contact in 0 until contacts) for (h in 1..12) {
                stop(cancelled)
                val at = if (contacts == 1) 0 else ((times[contact] * scale * hz).roundToInt() / hz * rate).roundToInt()
                val gain = if (contacts == 1) 1.0 else gains[contact]
                val tone = p.force * p.source(h, hz, velocity, false)
                val edge = p.edgeForce * p.source(h, hz, velocity, true)
                val wave = DoubleArray(frames) { n ->
                    if ((n and 8191) == 0) stop(cancelled)
                    if (n < at) 0.0 else {
                        val t = (n - at).toDouble() / rate
                        gain * sin(TAU * h * hz * t) * (tone * (exp(-p.slow * t) - exp(-p.fast * t)) +
                            edge * (exp(-p.edgeSlow * t) - exp(-p.edgeFast * t)))
                    }
                }
                yield(SourceGroup(wave, intArrayOf(h - 1, h + 11), if (contacts == 1) source else shiftedSource(source, contact),
                    trajectoryFrames = trajectory))
            }
        }
    }

    /** A cyclic, spectrally colored deterministic texture: no filter reset at the wrap. */
    private fun periodicNoise(voice: CausticVoice, midi: Int, count: Int, period: Double,
        cutoff: Double, cancelled: () -> Boolean): DoubleArray {
        var size = 1
        while (size < count) size *= 2
        val generator = Dsp.Noise(Dsp.seedFor("CAUSTIC_SUSTAIN", voice.name, midi, 2))
        val re = FloatArray(size) { generator.next() }; val im = FloatArray(size)
        stop(cancelled); CausticFft.forward(re, im)
        for (i in re.indices) {
            val frequency = min(i, size - i).toDouble() / period
            val gain = 1.0 / sqrt(1.0 + (frequency / cutoff).pow(4))
            re[i] = (re[i] * gain).toFloat(); im[i] = (im[i] * gain).toFloat()
        }
        CausticFft.inverse(re, im); stop(cancelled)
        return DoubleArray(count) { n ->
            val position = n.toDouble() * size / count
            val i = position.toInt(); val f = position - i
            re[i] * (1 - f) + re[(i + 1) % size] * f
        }
    }
    private fun heldGroups(voice: CausticVoice, m: Map<String, Float>, midi: Int, velocity: Double,
        hz: Double, count: Int, rate: Int, cancelled: () -> Boolean): Sequence<SourceGroup> = sequence {
        val b = body(voice, m); val deviation = 2 * b - 1
        val source = value(m, "SOURCE", .5).coerceIn(0.0, 1.0)
        val period = count.toDouble() / rate; val scale = (.5 + 7.5 * length(m)) / 8.0
        if (voice == CausticVoice.HOLLOW_MALLET || voice == CausticVoice.RUBBED_RING) {
            val harmonic = if (voice == CausticVoice.HOLLOW_MALLET) CausticCharacters.hollowHarmonics else CausticCharacters.rubbedHarmonics
            val weights = if (voice == CausticVoice.HOLLOW_MALLET) CausticCharacters.hollowWeights else CausticCharacters.rubbedWeights
            val grain = periodicNoise(voice, midi, count, period, if (voice == CausticVoice.HOLLOW_MALLET) 2400.0 else 5000.0, cancelled)
            val rough = if (voice == CausticVoice.RUBBED_RING) periodicNoise(voice, midi, count, period, 45.0, cancelled) else DoubleArray(0)
            val width = (.025 - .01 * velocity) * scale * 2.0.pow(-.65 * deviation)
            val force = if (voice == CausticVoice.HOLLOW_MALLET) 1000.0 else 120.0
            val wave = DoubleArray(count) { n ->
                if ((n and 8191) == 0) stop(cancelled)
                val t = n.toDouble() / rate
                val tone = CausticCharacters.harmonic(t, hz, harmonic, weights, (if (voice == CausticVoice.HOLLOW_MALLET) .35 else .30) * deviation)
                val x = if (voice == CausticVoice.HOLLOW_MALLET) {
                    val phase = t % (period / 4)
                    val contact = if (phase < width) .5 - .5 * cos(TAU * phase / width) else 0.0
                    contact * (tone + .055 * 2.0.pow(.4 * deviation) * grain[n]) * velocity.pow(.9)
                } else (tone * (1 + .65 * 2.0.pow(.8 * deviation) * rough[n] * 6) + .26 * 2.0.pow(.6 * deviation) * grain[n]) * velocity.pow(.85)
                HELD_FORCE_GAIN * force * x
            }
            yield(SourceGroup(wave, IntArray(if (voice == CausticVoice.HOLLOW_MALLET) 6 else 7) { it }, source))
            if (voice == CausticVoice.RUBBED_RING)
                yield(SourceGroup(DoubleArray(count) { grain[it] * velocity * HELD_FORCE_GAIN }, intArrayOf(0), source, true))
        } else {
            val p = alloyProfile(m, b)
            val contacts = if (voice == CausticVoice.TRAVELLING_CATCHES) 4 else 1
            val gains = doubleArrayOf(.75 / 2.4, .65 / 2.4, .55 / 2.4, .45 / 2.4)
            for (contact in 0 until contacts) for (h in 1..12) {
                stop(cancelled)
                val wave = DoubleArray(count) { n ->
                    if ((n and 8191) == 0) stop(cancelled)
                    val t = n.toDouble() / rate
                    val local = (t - contact * period / 4 + period) % period
                    val pulse = exp(-local / (.08 * 2.0.pow(.8 * b))) - exp(-local / .002)
                    val amplitude = if (contacts == 4) 1500.0 * pulse * gains[contact] * h.toDouble().pow(-.25 * b)
                    else p.force + 1500.0 * (.05 + .30 * (1 - b)) * (exp(-(t % (period / 4)) / .006) - exp(-(t % (period / 4)) / .0005))
                    HELD_FORCE_GAIN * amplitude * p.source(h, hz, velocity, false) * sin(TAU * h * hz * t)
                }
                yield(SourceGroup(wave, intArrayOf(h - 1, h + 11), if (contacts == 1) source else shiftedSource(source, contact)))
            }
        }
    }

    /** Fractional delays and their Lagrange coefficients are independent of the
     * receiving mode. Keep one sparse basis per pickup, then project each mode.
     * The key includes every dependency; velocity/BODY do not alter this basis. */
    private fun receptionBasis(geometry: CausticGeometry.Prepared, hz: Double, rate: Int): ReceptionBasis {
        val key = BasisKey(geometry, hz, rate)
        synchronized(receptionCache) { receptionCache[key]?.let { return it } }
        val count = ceil(4 * DELAY_CYCLES / hz * rate).toInt() + 4
        val pickups = Array(9) { DoubleArray(count) }
        for (path in geometry.paths) {
            val delay = path.delayR * DELAY_CYCLES / hz * rate
            val integer = floor(delay).toInt(); val fraction = delay - integer
            for (tap in 0..3) {
                var coefficient = 1.0
                for (j in 0..3) if (tap != j) coefficient *= (fraction - j) / (tap - j)
                pickups[path.pickup][integer + tap] += path.weight * coefficient
            }
        }
        val perPickup = Array(9) { sparse(pickups[it]) }
        // Include a conservative allowance for the retained geometry/key objects.
        val bytes = perPickup.sumOf { it.offsets.size * 12L + 64 } + geometry.paths.size * 96L + 1024
        val result = ReceptionBasis(count, perPickup, bytes)
        synchronized(receptionCache) {
            receptionCache[key]?.let { return it }
            while (receptionCache.isNotEmpty() && (receptionBytes + bytes > RECEPTION_CACHE_BYTES || receptionCache.size >= 256)) {
                val oldest = receptionCache.entries.iterator(); val entry = oldest.next()
                receptionBytes -= entry.value.bytes; oldest.remove()
            }
            if (bytes <= RECEPTION_CACHE_BYTES) { receptionCache[key] = result; receptionBytes += bytes }
        }
        return result
    }
    private fun kernel(geometry: CausticGeometry.Prepared, spatial: DoubleArray, hz: Double, rate: Int): Kernel {
        val basis = receptionBasis(geometry, hz, rate); val taps = DoubleArray(basis.length)
        for (pickup in 0..8) {
            val k = basis.pickups[pickup]; val scale = spatial[pickup]
            for (i in k.offsets.indices) taps[k.offsets[i]] += k.weights[i] * scale
        }
        return Kernel(taps)
    }

    private fun emissionWindows(count: Int, span: Int, steps: Int): List<EmissionWindow> {
        if (steps == 1) return listOf(EmissionWindow(0, 0, count, 0))
        val centers = IntArray(steps + 1 + if (span < count) 1 else 0) { i ->
            if (i <= steps) (i.toLong() * span / steps).toInt() else count
        }
        return centers.indices.map { i -> EmissionWindow(centers[i],
            if (i == 0) -centers[1] else centers[i - 1],
            if (i == centers.lastIndex) centers[i] + centers[i] - centers[i - 1] else centers[i + 1],
            min(i, steps) % steps) }
    }

    private fun sparse(samples: DoubleArray): SparseKernel {
        val indices = samples.indices.filter { samples[it] != 0.0 }.toIntArray()
        return SparseKernel(indices, DoubleArray(indices.size) { samples[indices[it]] })
    }

    /** Exact source terms for the existing metal pressure laws; no FFT approximation.
     * Contacts share poles and differ only in local segment/event coefficients. */
    private fun metalTerms(voice: CausticVoice, p: Caustic.RichProfile, b: Double,
        h: Int, contact: Int, held: Boolean, count: Int, hz: Double, rate: Int,
        velocity: Double, scale: Double): List<ExpTerm> {
        val omega = TAU * h * hz / rate
        val catches = voice == CausticVoice.TRAVELLING_CATCHES
        val gain = if (catches) doubleArrayOf(.75 / 2.4, .65 / 2.4, .55 / 2.4, .45 / 2.4)[contact] else 1.0
        if (!held) {
            val times = doubleArrayOf(0.0, .12, .28, .48)
            val at = if (catches) ((times[contact] * scale * hz).roundToInt() / hz * rate).roundToInt() else 0
            val tone = gain * p.force * p.source(h, hz, velocity, false)
            val edge = gain * p.edgeForce * p.source(h, hz, velocity, true)
            return listOf(ExpTerm(at, count, at, p.slow, tone, -omega * at),
                ExpTerm(at, count, at, p.fast, -tone, -omega * at),
                ExpTerm(at, count, at, p.edgeSlow, edge, -omega * at),
                ExpTerm(at, count, at, p.edgeFast, -edge, -omega * at))
        }
        val amplitude = HELD_FORCE_GAIN * p.source(h, hz, velocity, false)
        if (!catches) {
            val terms = ArrayList<ExpTerm>()
            terms.add(ExpTerm(0, count, 0, 0.0, amplitude * p.force))
            val edge = amplitude * 1500.0 * (.05 + .30 * (1 - b))
            for (q in 0..3) {
                val at = q * count / 4; val end = (q + 1) * count / 4
                terms.add(ExpTerm(at, end, at, 1 / .006, edge))
                terms.add(ExpTerm(at, end, at, 1 / .0005, -edge))
            }
            return terms
        }
        val at = contact * count / 4
        val amount = amplitude * 1500 * gain * h.toDouble().pow(-.25 * b)
        val result = ArrayList<ExpTerm>()
        for ((lambda, sign) in listOf(1 / (.08 * 2.0.pow(.8 * b)) to 1.0, 1 / .002 to -1.0)) {
            if (at > 0) result.add(ExpTerm(0, at, at - count, lambda, amount * sign))
            result.add(ExpTerm(at, count, at, lambda, amount * sign))
        }
        return result
    }

    /** Starts/stops of (F+k*S)q^k. Each packet schedules its actual FIR taps
     * into a bounded delay ring, rather than expanding a note-sized event matrix. */
    private fun addForceSegment(packets: MutableList<ForcePacket>, poles: MutableList<ForcePole>,
        mode: Int, kernel: SparseKernel, term: ExpTerm, left: Int, right: Int,
        windowAtLeft: Double, slope: Double, omega: Double, rate: Int, factor: Double = 1.0) {
        if (right <= left || term.amplitude == 0.0 || kernel.offsets.isEmpty()) return
        val key = ForcePole(mode, term.lambda, omega)
        var pole = poles.indexOf(key)
        if (pole < 0) { pole = poles.size; poles.add(key) }
        val amplitude = term.amplitude * factor * exp(-term.lambda * (left - term.origin) / rate)
        val phase = omega * left + term.phase
        val ar = amplitude * cos(phase); val ai = amplitude * sin(phase)
        val fr = ar * windowAtLeft; val fi = ai * windowAtLeft
        val sr = ar * slope; val si = ai * slope
        packets.add(ForcePacket(left, pole, kernel, fr, fi, sr, si))
        val length = right - left
        val radius = exp(-term.lambda * length / rate)
        val cr = radius * cos(omega * length); val ci = radius * sin(omega * length)
        val er = fr + length * sr; val ei = fi + length * si
        packets.add(ForcePacket(right, pole, kernel, -(er * cr - ei * ci), -(er * ci + ei * cr),
            -(sr * cr - si * ci), -(sr * ci + si * cr)))
    }

    private fun renderForcePackets(packets: List<ForcePacket>, poles: List<ForcePole>,
        count: Int, delay: Int, rate: Int, cancelled: () -> Boolean): Array<FloatArray> {
        val result = Array(2) { FloatArray(count) }
        if (packets.isEmpty()) return result
        val ordered = packets.sortedBy { it.at }; val ringSize = delay + 1
        val events = Array(poles.size * 4) { DoubleArray(ringSize) }
        val fr = DoubleArray(poles.size); val fi = DoubleArray(poles.size)
        val sr = DoubleArray(poles.size); val si = DoubleArray(poles.size)
        val cr = DoubleArray(poles.size) { exp(-poles[it].lambda / rate) * cos(poles[it].omega) }
        val ci = DoubleArray(poles.size) { exp(-poles[it].lambda / rate) * sin(poles[it].omega) }
        var next = 0; var index = 0
        for (n in 0 until count) {
            if ((n and 8191) == 0) stop(cancelled)
            while (next < ordered.size && ordered[next].at == n) {
                val p = ordered[next++]; val slot = p.pole * 4
                for (j in p.kernel.offsets.indices) {
                    var at = index + p.kernel.offsets[j]
                    if (at >= ringSize) at -= ringSize
                    val weight = p.kernel.weights[j]
                    events[slot][at] += p.fr * weight; events[slot + 1][at] += p.fi * weight
                    events[slot + 2][at] += p.sr * weight; events[slot + 3][at] += p.si * weight
                }
            }
            var a = 0.0; var b = 0.0
            for (i in poles.indices) {
                val slot = i * 4
                fr[i] += events[slot][index]; fi[i] += events[slot + 1][index]
                sr[i] += events[slot + 2][index]; si[i] += events[slot + 3][index]
                for (k in 0..3) events[slot + k][index] = 0.0
                if (poles[i].mode == 0) a += fi[i] else b += fi[i]
                val r = fr[i] + sr[i]; val im = fi[i] + si[i]; val s = sr[i]
                fr[i] = r * cr[i] - im * ci[i]; fi[i] = r * ci[i] + im * cr[i]
                sr[i] = s * cr[i] - si[i] * ci[i]; si[i] = s * ci[i] + si[i] * cr[i]
            }
            result[0][n] = a.toFloat(); result[1][n] = b.toFloat()
            index++
            if (index == ringSize) index = 0
        }
        return result
    }

    private fun metalFields(voice: CausticVoice, m: Map<String, Float>, midi: Int,
        velocity: Double, hz: Double, count: Int, rate: Int, h: Int, held: Boolean,
        onsetSeconds: Double, modes: List<BodyMode>, evidence: Evidence,
        cancelled: () -> Boolean): MetalFields {
        val b = body(voice, m); val p = alloyProfile(m, b)
        val contacts = if (voice == CausticVoice.TRAVELLING_CATCHES) 4 else 1
        val source = value(m, "SOURCE", .5).coerceIn(0.0, 1.0)
        val focus = value(m, "FOCUS", .19).coerceIn(0.0, 1.0)
        val scale = (.5 + 7.5 * length(m)) / 8.0
        val lastLaunch = if (contacts == 1) 0 else ((.48 * scale * hz).roundToInt() / hz * rate).roundToInt()
        val span = if (held) count else lastLaunch + ceil(3 * p.decay * rate).toInt()
        val packets = ArrayList<ForcePacket>(); val poles = ArrayList<ForcePole>()
        val deficit = ArrayList<ForcePacket>(); val deficitPoles = ArrayList<ForcePole>()
        val maxDelay = ceil(4 * DELAY_CYCLES / hz * rate).toInt() + 4
        val onsetEnd = min(count, ceil(onsetSeconds * rate).toInt())
        val omega = TAU * h * hz / rate
        for (contact in 0 until contacts) {
            stop(cancelled)
            val position = if (contacts == 1) source else shiftedSource(source, contact)
            val table = table(position, focus, motion(m), cancelled); evidence.include(table)
            val terms = metalTerms(voice, p, b, h, contact, held, count, hz, rate, velocity, scale)
            val indices = intArrayOf(h - 1, h + 11)
            val baseline = if (held) null else CausticGeometry.prepare(position, focus)
            val original = indices.map { index -> baseline?.let { kernel(it, modes[index].spatial, hz, rate).samples } }
            for (window in emissionWindows(count, span, table.points.size)) {
                stop(cancelled)
                val geometry = table.points[window.geometry]
                for (localMode in 0..1) {
                    val dense = kernel(geometry, modes[indices[localMode]].spatial, hz, rate).samples
                    if (!held) for (i in dense.indices) dense[i] -= original[localMode]!![i]
                    evidence.fir = max(evidence.fir, dense.sumOf { abs(it) })
                    val k = sparse(dense)
                    val pieces = if (table.points.size == 1) listOf(Triple(0, count, 0.0)) else
                        listOf(Triple(window.left, window.center, 1.0 / (window.center - window.left)),
                            Triple(window.center, window.right, -1.0 / (window.right - window.center)))
                    for ((wl, wr, slope) in pieces) for (term in terms) {
                        val left = max(0, max(wl, term.from)); val right = min(count, min(wr, term.until))
                        val weight = if (table.points.size == 1) 1.0 else if (slope > 0) (left - wl) * slope else (left - wr) * slope
                        addForceSegment(packets, poles, localMode, k, term, left, right, weight, slope, omega, rate)
                        val end = min(right, onsetEnd)
                        if (held && onsetEnd > left && end > left) {
                            val sideband = PI / (onsetSeconds * rate)
                            addForceSegment(deficit, deficitPoles, localMode, k, term, left, end, weight, slope, omega, rate, .5)
                            addForceSegment(deficit, deficitPoles, localMode, k, term, left, end, weight, slope, omega + sideband, rate, .25)
                            addForceSegment(deficit, deficitPoles, localMode, k, term, left, end, weight, slope, omega - sideband, rate, .25)
                        }
                    }
                }
            }
        }
        val causal = renderForcePackets(packets, poles, count + if (held) maxDelay else 0, maxDelay, rate, cancelled)
        if (!held) return MetalFields(causal, emptyArray())
        val correction = if (onsetEnd > 0) renderForcePackets(deficit, deficitPoles, onsetEnd + maxDelay, maxDelay, rate, cancelled) else emptyArray()
        val steady = Array(2) { causal[it].copyOf(count) }; val onset = Array(2) { causal[it].copyOf(count) }
        for (j in 0..1) {
            for (n in count until causal[j].size) steady[j][n - count] += causal[j][n]
            if (correction.isNotEmpty()) for (n in 0 until min(count, correction[j].size)) onset[j][n] -= correction[j][n]
        }
        return MetalFields(steady, onset)
    }

    /** Emission windows sum to one, including the first/last half windows. */
    private fun route(group: SourceGroup, modes: List<BodyMode>, m: Map<String, Float>, hz: Double,
        rate: Int, count: Int, periodic: Boolean, onset: Double, delta: Boolean,
        evidence: Evidence, cancelled: () -> Boolean, retainTail: Boolean = false): Array<FloatArray> {
        val amount = motion(m); val focus = value(m, "FOCUS", .19).coerceIn(0.0, 1.0)
        val table = table(group.sourcePosition, focus, amount, cancelled)
        evidence.include(table)
        val steps = table.points.size
        // Finite motion follows the actual contact/pressure/energetic drive,
        // rather than spending a full8s orbit after a17ms mallet has stopped.
        // This clock also stays fixed when a diagnostic captures only a prefix.
        val span = if (delta && group.trajectoryFrames > 0) group.trajectoryFrames else count
        val centers = if (steps == 1) intArrayOf(0) else
            IntArray(steps + 1 + if (span < count) 1 else 0) { i ->
                if (i <= steps) (i.toLong() * span / steps).toInt() else count
            }
        val maxDelay = ceil(4 * DELAY_CYCLES / hz * rate).toInt() + 4
        val result = Array(group.modes.size) { FloatArray(count + if (periodic || retainTail) maxDelay else 0) }
        val static = if (delta) CausticGeometry.prepare(group.sourcePosition, focus) else null
        val baseline = group.modes.map { index -> static?.let { kernel(it, modes[index].spatial, hz, rate).samples } }
        // A constant route needs no emission interpolation; ordinary convolution
        // is the identical operator with all partition weights collected together.
        for (node in centers.indices) {
            stop(cancelled)
            val center = centers[node]
            val leftCenter = if (steps == 1) 0 else if (node == 0) -centers[1] else centers[node - 1]
            val rightCenter = if (steps == 1) count else if (node == centers.lastIndex)
                center + center - centers[node - 1] else centers[node + 1]
            val left = max(0, leftCenter)
            val right = min(min(count, group.source.size), rightCenter)
            if (right <= left) continue
            val windowed = DoubleArray(right - left) { j ->
                val n = left + j
                val weight = if (steps == 1) 1.0 else if (n < center)
                    (n - leftCenter).toDouble() / (center - leftCenter)
                else (rightCenter - n).toDouble() / (rightCenter - center)
                val attack = if (onset > 0 && n < onset * rate) .5 - .5 * cos(PI * n / (onset * rate)) else 1.0
                group.source[n] * weight * attack
            }
            val geometry = table.points[min(node, steps) % steps]
            val kernels = group.modes.mapIndexed { j, index ->
                val k = kernel(geometry, modes[index].spatial, hz, rate).samples
                if (delta) for (i in k.indices) k[i] -= baseline[j]!![i]
                evidence.fir = max(evidence.fir, k.sumOf { abs(it) })
                k
            }
            convolveAdd(windowed, left, kernels, result, cancelled)
        }
        if (periodic) for (buffer in result) {
            for (i in count until buffer.size) buffer[i - count] += buffer[i]
        }
        return if (periodic) Array(result.size) { result[it].copyOf(count) } else result
    }

    /** One complex kernel K1+iK2 produces two real receptions per inverse FFT.
     * This is convolution before the body, not a crossfade of modal outputs. */
    private fun convolveAdd(source: DoubleArray, offset: Int, kernels: List<DoubleArray>,
        result: Array<FloatArray>, cancelled: () -> Boolean) {
        val length = kernels.first().size
        var size = 64
        while (size < min(source.size, 8192) + length - 1) size *= 2
        val step = size - length + 1
        val pairs = (kernels.size + 1) / 2
        val kr = List(pairs) { p -> FloatArray(size) { if (it < kernels[p * 2].size) kernels[p * 2][it].toFloat() else 0f } }
        val ki = List(pairs) { p -> FloatArray(size) { if (p * 2 + 1 < kernels.size && it < kernels[p * 2 + 1].size) kernels[p * 2 + 1][it].toFloat() else 0f } }
        for (pair in 0 until pairs) { stop(cancelled); CausticFft.forward(kr[pair], ki[pair]) }
        val sr = FloatArray(size); val si = FloatArray(size); val pr = FloatArray(size); val pi = FloatArray(size)
        var start = 0
        while (start < source.size && offset + start < result[0].size) {
            stop(cancelled); sr.fill(0f); si.fill(0f)
            val count = min(step, source.size - start)
            for (i in 0 until count) sr[i] = source[start + i].toFloat()
            CausticFft.forward(sr, si)
            for (pair in 0 until pairs) {
                for (i in sr.indices) { pr[i] = sr[i] * kr[pair][i] - si[i] * ki[pair][i]; pi[i] = sr[i] * ki[pair][i] + si[i] * kr[pair][i] }
                CausticFft.inverse(pr, pi)
                val channel = pair * 2
                val end = min(count + length - 1, result[channel].size - offset - start)
                for (i in 0 until end) result[channel][offset + start + i] += pr[i]
                if (channel + 1 < result.size)
                    for (i in 0 until end) result[channel + 1][offset + start + i] += pi[i]
            }
            start += count
        }
    }

    internal fun convolutionProbe(source: DoubleArray, kernels: List<DoubleArray>): Pair<Array<FloatArray>, Array<FloatArray>> {
        require(source.isNotEmpty() && kernels.isNotEmpty() && kernels.all { it.size == kernels[0].size })
        val outputSize = source.size + kernels[0].size - 1
        val paired = Array(kernels.size) { FloatArray(outputSize) }
        convolveAdd(source, 0, kernels, paired) { false }
        // Deliberately retain the old scalar-channel house FFT reference here.
        // It shares neither the paired complex layout nor planned FFT code.
        val reference = Array(kernels.size) { FloatArray(outputSize) }
        val length = kernels[0].size; var size = 64
        while (size < min(source.size, 8192) + length - 1) size *= 2
        val step = size - length + 1
        for (channel in kernels.indices) {
            val kr = FloatArray(size) { if (it < length) kernels[channel][it].toFloat() else 0f }
            val ki = FloatArray(size); Fft.forward(kr, ki)
            var start = 0
            while (start < source.size) {
                val sr = FloatArray(size); val si = FloatArray(size)
                val count = min(step, source.size - start)
                for (i in 0 until count) sr[i] = source[start + i].toFloat()
                Fft.forward(sr, si)
                val pr = FloatArray(size) { sr[it] * kr[it] - si[it] * ki[it] }
                val pi = FloatArray(size) { sr[it] * ki[it] + si[it] * kr[it] }
                Fft.inverse(pr, pi)
                for (i in 0 until count + length - 1) reference[channel][start + i] += pr[i]
                start += count
            }
        }
        return paired to reference
    }

    private fun rotation(mode: BodyMode, rate: Int): Pair<Double, Double> {
        val radius = exp(-ln(1000.0) / (mode.t60 * rate))
        check(radius in 0.0..1.0 && radius < 1.0)
        return radius * cos(TAU * mode.frequency / rate) to radius * sin(TAU * mode.frequency / rate)
    }
    private fun advance(s: State, real: Double, imag: Double, force: Double, rate: Int) {
        val r = s.re
        s.re = r * real - s.im * imag
        s.im = r * imag + s.im * real - force / rate
    }
    private fun reference(drive: FloatArray, mode: BodyMode, rate: Int, cancelled: () -> Boolean): CycleReference {
        val (ar, ai) = rotation(mode, rate); val zero = State()
        for (i in drive.indices) { if ((i and 8191) == 0) stop(cancelled); advance(zero, ar, ai, drive[i].toDouble(), rate) }
        val radiusN = exp(-ln(1000.0) * drive.size / (mode.t60 * rate))
        val phaseN = TAU * mode.frequency * drive.size / rate
        val dr = 1 - radiusN * cos(phaseN); val di = -radiusN * sin(phaseN)
        val norm = dr * dr + di * di
        check(norm > 1e-16)
        val before = State((zero.re * dr + zero.im * di) / norm, (zero.im * dr - zero.re * di) / norm)
        val state = before.copy(); var peak = hypot(state.re, state.im)
        for (i in drive.indices) {
            if ((i and 8191) == 0) stop(cancelled)
            advance(state, ar, ai, drive[i].toDouble(), rate); peak = max(peak, hypot(state.re, state.im))
        }
        val closure = hypot(state.re - before.re, state.im - before.im) / peak.coerceAtLeast(1e-20)
        return CycleReference(before, peak, closure)
    }
    fun periodicProbe(drive: DoubleArray, frequency: Double, t60: Double, rate: Int): PeriodProbe {
        require(drive.isNotEmpty() && drive.all { it.isFinite() } && frequency > 0 && t60 > 0 && rate > 0)
        val floats = FloatArray(drive.size) { drive[it].toFloat() }
        val mode = BodyMode(0, frequency, t60, 1.0, DoubleArray(9))
        val ref = reference(floats, mode, rate) { false }; val state = ref.before.copy()
        val (ar, ai) = rotation(mode, rate)
        fun cycle() = DoubleArray(drive.size) { i -> advance(state, ar, ai, floats[i].toDouble(), rate); -state.im }
        val first = cycle(); val second = cycle()
        return PeriodProbe(ref.before.re, ref.before.im, state.re, state.im, first, second)
    }

    /** Independent implementation check: the retained full source/FloatFFT path
     * does not use packets, forcing-pole recurrences or analytic segment values. */
    internal fun metalForceProbe(voice: CausticVoice, macros: Map<String, Float>, midi: Int,
        velocity: Double, held: Boolean, harmonic: Int = 3): MetalForceProbe {
        require(voice == CausticVoice.ALLOY || voice == CausticVoice.TRAVELLING_CATCHES)
        val rate = Dsp.RATE * 4
        val frames = if (held) loopFrameCount(midi, length(macros).toFloat()) else (Dsp.RATE * .6).roundToInt()
        val count = frames * 4
        val hz = if (held) periods(midi, length(macros)) * Dsp.RATE.toDouble() / frames else nominalHz(midi)
        val modes = bodyModes(voice, macros, hz); val rise = if (held) .025 else 0.0
        val fast = metalFields(voice, macros, midi, velocity, hz, count, rate, harmonic, held, rise, modes, Evidence()) { false }
        val reference = Array(2) { FloatArray(count) }; val referenceOnset = Array(2) { FloatArray(count) }
        val groups = if (held) heldGroups(voice, macros, midi, velocity, hz, count, rate) { false }
            else finiteGroups(voice, macros, midi, velocity, hz, count, rate) { false }
        for (group in groups) {
            if (group.modes.first() != harmonic - 1) continue
            val routed = route(group, modes, macros, hz, rate, count, held, 0.0, !held, Evidence(), { false })
            val onset = if (held) route(group, modes, macros, hz, rate, count, false, rise, false, Evidence(), { false }) else emptyArray()
            for (j in 0..1) for (n in 0 until count) {
                reference[j][n] += routed[j][n]
                if (held) referenceOnset[j][n] += onset[j][n]
            }
        }
        return MetalForceProbe(fast.steady, reference, fast.onset, referenceOnset)
    }
    private fun ringFinite(out: FloatArray, drive: FloatArray, mode: BodyMode, rate: Int, cancelled: () -> Boolean) {
        val (ar, ai) = rotation(mode, rate); val inverseRate = 1.0 / rate
        var real = 0.0; var imag = 0.0
        for (i in out.indices) {
            if ((i and 8191) == 0) stop(cancelled)
            val force = if (i < drive.size) drive[i].toDouble() else 0.0
            val next = real * ar - imag * ai
            imag = real * ai + imag * ar - force * inverseRate; real = next
            out[i] = (out[i] - mode.gain * imag).toFloat()
        }
    }
    private fun error(a: FloatArray, first: Int, second: Int, count: Int): Double {
        var diff = 0.0; var energy = 0.0
        for (i in 0 until count) { val d = a[first + i] - a[second + i].toDouble(); diff += d * d; energy += a[first + i].toDouble() * a[first + i] }
        return diff / energy.coerceAtLeast(1e-30)
    }
    private fun evolveHeld(forces: Array<FloatArray>, onset: Array<FloatArray>, radiation: FloatArray,
        onsetRadiation: FloatArray, modes: List<BodyMode>, refs: List<CycleReference>, count: Int,
        prefix: Int, rate: Int, cancelled: () -> Boolean): Evolved {
        val out = FloatArray((prefix + 3) * count + 128 * (rate / Dsp.RATE))
        var stateError = 0.0
        for (m in modes.indices) {
            stop(cancelled)
            val mode = modes[m]; val (ar, ai) = rotation(mode, rate); val inverseRate = 1.0 / rate
            var real = 0.0; var imag = 0.0; var boundaryRe = 0.0; var boundaryIm = 0.0; var phase = 0
            for (n in out.indices) {
                if ((n and 8191) == 0) stop(cancelled)
                val force = if (n < count) onset[m][phase] else forces[m][phase]
                val next = real * ar - imag * ai
                imag = real * ai + imag * ar - force * inverseRate; real = next
                out[n] = (out[n] - mode.gain * imag).toFloat()
                if (n == prefix * count - 1) { boundaryRe = real; boundaryIm = imag }
                if (n == (prefix + 1) * count - 1)
                    stateError = max(stateError, hypot(real - boundaryRe, imag - boundaryIm) / refs[m].peak.coerceAtLeast(1e-20))
                phase++
                if (phase == count) phase = 0
            }
        }
        var phase = 0
        for (n in out.indices) {
            val x = if (n < count) onsetRadiation[phase] else radiation[phase]
            out[n] += .25f * x
            check(out[n].isFinite() && abs(out[n]) < 8f) { "CAUSTIC held raw bound exceeded at $n: ${out[n]}" }
            phase++
            if (phase == count) phase = 0
        }
        return Evolved(out, stateError)
    }

    /** Predict real transient contraction from the actual first causal cycle,
     * avoiding repeated complete trace renders while retaining the same proof. */
    private fun settledPrefix(onset: Array<FloatArray>, modes: List<BodyMode>, refs: List<CycleReference>,
        count: Int, rate: Int, cancelled: () -> Boolean): Int {
        var prefix = 1
        for (m in modes.indices) {
            val state = State(); val (ar, ai) = rotation(modes[m], rate)
            for (i in onset[m].indices) {
                if ((i and 8191) == 0) stop(cancelled)
                advance(state, ar, ai, onset[m][i].toDouble(), rate)
            }
            val logRadius = -ln(1000.0) * count / (modes[m].t60 * rate)
            val r = exp(logRadius); val phase = TAU * modes[m].frequency * count / rate
            val displacement = hypot(state.re - refs[m].before.re, state.im - refs[m].before.im)
            val initialChange = displacement * hypot(r * cos(phase) - 1, r * sin(phase))
            val target = refs[m].peak.coerceAtLeast(1e-20) * 5e-6
            if (initialChange > target)
                prefix = max(prefix, 1 + ceil(ln(target / initialChange) / logRadius).toInt())
        }
        check(prefix <= 24) { "CAUSTIC held transient needs excessive settling: $prefix cycles" }
        return prefix
    }
    private fun diagnostics(hz: Double, midi: Int, frames: Int, prefix: Int, state: Double,
        continuation: Double, peak: Double, evidence: Evidence, prepare: Long, render: Long,
        periodic: Double = 0.0, seam: Double = 0.0) = CausticSustainDiagnostics(hz,
        1200 * ln(hz / nominalHz(midi)) / ln(2.0), frames, prefix, state, continuation, peak,
        evidence.sourceMin, evidence.sourceMax, evidence.focusMin, evidence.focusMax,
        evidence.collectionMin, evidence.collectionMax, evidence.delay, evidence.snapshots,
        evidence.fir, prepare, render, periodic, seam)

    fun held(voice: CausticVoice, macros: Map<String, Float>, midi: Int, velocity: Double,
        oversample: Int = 4, cancelled: () -> Boolean = { false }): HeldRaw {
        stop(cancelled); val started = System.nanoTime()
        require(midi in 36..84 && velocity.isFinite() && velocity in 0.0..1.0)
        require(oversample == 4 || oversample == 16)
        val frames = loopFrameCount(midi, length(macros).toFloat()); val count = frames * oversample
        val rate = Dsp.RATE * oversample; val hz = periods(midi, length(macros)) * Dsp.RATE.toDouble() / frames
        val modes = bodyModes(voice, macros, hz); val evidence = Evidence()
        if (velocity == 0.0) {
            evidence.include(table(value(macros, "SOURCE", .5).coerceIn(0.0, 1.0),
                value(macros, "FOCUS", .19).coerceIn(0.0, 1.0), motion(macros), cancelled))
            val elapsed = System.nanoTime() - started
            val diag = diagnostics(hz, midi, frames, 1, 0.0, 0.0, 0.0, evidence, elapsed, 0)
            return HeldRaw(FloatArray(4 * count + 128 * oversample), frames, frames, 2 * frames,
                DoubleArray(modes.size) { modes[it].frequency }, diag)
        }
        val forces = Array(modes.size) { FloatArray(count) }; val onset = Array(modes.size) { FloatArray(count) }
        val radiation = FloatArray(count); val onsetRadiation = FloatArray(count)
        val rise = when (voice) { CausticVoice.RUBBED_RING -> .34 * 2.0.pow(-.7 * (2 * body(voice, macros) - 1)); CausticVoice.HOLLOW_MALLET -> 0.0; else -> .025 }
        if (voice == CausticVoice.ALLOY || voice == CausticVoice.TRAVELLING_CATCHES) {
            for (h in 1..12) {
                val fields = metalFields(voice, macros, midi, velocity, hz, count, rate, h, true, rise, modes, evidence, cancelled)
                forces[h - 1] = fields.steady[0]; forces[h + 11] = fields.steady[1]
                onset[h - 1] = fields.onset[0]; onset[h + 11] = fields.onset[1]
            }
        } else for (group in heldGroups(voice, macros, midi, velocity, hz, count, rate, cancelled)) {
            val causal = route(group, modes, macros, hz, rate, count, false, 0.0, false, evidence, cancelled, true)
            val steady = Array(group.modes.size) { causal[it].copyOf(count) }
            val attack = Array(group.modes.size) { causal[it].copyOf(count) }
            if (rise > 0) {
                val end = min(group.source.size, ceil(rise * rate).toInt())
                val deficit = group.copy(source = DoubleArray(end) { n ->
                    group.source[n] * (.5 + .5 * cos(PI * n / (rise * rate)))
                })
                val correction = route(deficit, modes, macros, hz, rate, count, false, 0.0, false, evidence, cancelled)
                for (j in attack.indices) for (n in 0 until count) attack[j][n] -= correction[j][n]
            }
            for (j in group.modes.indices) {
                for (n in count until causal[j].size) steady[j][n - count] += causal[j][n]
                for (n in 0 until count) {
                    if (group.radiation) { radiation[n] += steady[j][n]; onsetRadiation[n] += attack[j][n] }
                    else { forces[group.modes[j]][n] += steady[j][n]; onset[group.modes[j]][n] += attack[j][n] }
                }
            }
        }
        val refs = modes.mapIndexed { i, mode -> reference(forces[i], mode, rate, cancelled) }
        val prepared = System.nanoTime() - started
        var prefix = settledPrefix(onset, modes, refs, count, rate, cancelled)
        var evolved: Evolved; var seam: Double; var continuation: Double
        while (true) {
            evolved = evolveHeld(forces, onset, radiation, onsetRadiation, modes, refs, count, prefix, rate, cancelled)
            seam = error(evolved.raw, (prefix - 1) * count, prefix * count, count)
            continuation = error(evolved.raw, prefix * count, (prefix + 1) * count, count)
            if (evolved.stateError < 1e-5 && seam < 1e-7 && continuation < 1e-7) break
            check(prefix < 24) { "CAUSTIC held state did not settle: $seam/$continuation/${evolved.stateError}" }
            prefix++
        }
        val peak = evolved.raw.maxOfOrNull { abs(it).toDouble() } ?: 0.0
        val diag = diagnostics(hz, midi, frames, prefix, evolved.stateError, continuation, peak,
            evidence, prepared, System.nanoTime() - started - prepared, refs.maxOfOrNull { it.closure } ?: 0.0, seam)
        return HeldRaw(evolved.raw, prefix * frames, frames, (prefix + 1) * frames,
            DoubleArray(modes.size) { modes[it].frequency }, diag)
    }

    /** Add only the changed reception's response, preserving the exact old finite baseline. */
    fun applyMotion(voice: CausticVoice, macros: Map<String, Float>, midi: Int, velocity: Double,
        legacyInternal: FloatArray, oversample: Int = 4, cancelled: () -> Boolean = { false }): CausticSustainDiagnostics {
        stop(cancelled); val started = System.nanoTime()
        require(midi in 36..84 && velocity.isFinite() && velocity in 0.0..1.0)
        require(oversample == 4 || oversample == 16)
        val hz = nominalHz(midi); val rate = Dsp.RATE * oversample
        val modes = bodyModes(voice, macros, hz); val evidence = Evidence()
        if (motion(macros) > 0 && velocity > 0) {
            if (voice == CausticVoice.ALLOY || voice == CausticVoice.TRAVELLING_CATCHES) {
                for (h in 1..12) {
                    val fields = metalFields(voice, macros, midi, velocity, hz, legacyInternal.size, rate,
                        h, false, 0.0, modes, evidence, cancelled)
                    ringFinite(legacyInternal, fields.steady[0], modes[h - 1], rate, cancelled)
                    ringFinite(legacyInternal, fields.steady[1], modes[h + 11], rate, cancelled)
                }
            } else for (group in finiteGroups(voice, macros, midi, velocity, hz, legacyInternal.size, rate, cancelled)) {
                val forces = route(group, modes, macros, hz, rate, legacyInternal.size, false, 0.0, true, evidence, cancelled)
                for (j in group.modes.indices) {
                    if (group.radiation) for (n in legacyInternal.indices) legacyInternal[n] += .25f * forces[j][n]
                    else ringFinite(legacyInternal, forces[j], modes[group.modes[j]], rate, cancelled)
                }
            }
        } else evidence.include(table(value(macros, "SOURCE", .5).coerceIn(0.0, 1.0),
            value(macros, "FOCUS", .19).coerceIn(0.0, 1.0), 0.0, cancelled))
        for (x in legacyInternal) check(x.isFinite() && abs(x) < 8f) { "CAUSTIC moving finite raw bound exceeded: $x" }
        return diagnostics(hz, midi, 0, 0, 0.0, 0.0, legacyInternal.maxOfOrNull { abs(it).toDouble() } ?: 0.0,
            evidence, System.nanoTime() - started, 0)
    }
}
