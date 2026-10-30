package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class NacreVoice { PEARL }

/**
 * The preferred original duet -> returning paths -> rest. The frozen [NacreModel1] instrument
 * stays at its heard CURRENT .20, BLOOM .35 and VENUE .15. TOUCH, DUET, note, velocity, seed and
 * source ablations remain actual core inputs. No exciter, tuning, body or direct decay changes.
 *
 * VENUE blends close coral answers with later seaweed windows. CURRENT changes coral reflection
 * emphasis and seaweed's nonnegative outlet apertures. Zero means STILL RETURNS: the original
 * instrument's subtle internal water remains at .20. BLOOM scales its existing linear sympathetic
 * branch and return amount without extending decay. A static sympathetic scale is equivalent to
 * scaling that branch's excitation because it has no return into the played strings.
 *
 * Source sound retains its original 4x conditioning. New 44.1-kHz returns use fixed integer
 * delays, fixed low-passes and smooth apertures under 4 Hz. Nonnegative path weights sum to one
 * and VENUE's output blend is convex. There is no venue feedback, moving delay, added oscillator,
 * brighter exciter or held loop. Bounded feed-forward paths cannot create feedback instability.
 *
 * One heard reference fixes return and output gains. Output is not normalized per patch: the
 * reference gain preserves velocity and control authority, followed only by a scalar .95 peak
 * guard. This is an explicit exception to per-patch loudness matching. Earlier quiet variants
 * disappeared under the foreground; changing excitation and sustain made the tone abrasive.
 * These paths reveal space in time while preserving the preferred instrument. Four private,
 * completed core buffers bound memory; returned arrays never expose cache-owned mutable data.
 */
object Nacre {
    const val MODEL_VERSION = 3
    const val DEFAULT_MIDI = 48
    const val MIN_MIDI = 28
    const val MAX_MIDI = 72
    const val DEFAULT_SEED = 7L
    const val DURATION_SECONDS = 6f
    const val REFERENCE_RETURN_GAIN = .8930654093975182
    const val REFERENCE_OUTPUT_GAIN = 3.2143452167510986
    private const val CORE_CURRENT = .20f
    private const val CORE_BLOOM = .35f
    private const val CORE_VENUE = .15f
    private const val CACHE_CAPACITY = 4
    private const val RATE = Dsp.RATE

    fun macrosFor(voice: NacreVoice): List<MacroSpec> = when (voice) {
        NacreVoice.PEARL -> listOf(MacroSpec("TOUCH", .30f, .30f), MacroSpec("DUET", .15f, .15f),
            MacroSpec("CURRENT", CORE_CURRENT, CORE_CURRENT), MacroSpec("VENUE", .15f, .15f),
            MacroSpec("BLOOM", CORE_BLOOM, CORE_BLOOM), MacroSpec("HOLD", 0f))
    }
    fun defaults(voice: NacreVoice = NacreVoice.PEARL): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    private fun settled(voice: NacreVoice, macros: Map<String, Float>): Map<String, Float> {
        val defaults = defaults(voice)
        require(macros.keys.all { it in defaults }) { "NACRE contains unknown macros: ${macros.keys - defaults.keys}" }
        val m = defaults.mapValues { (name, default) -> (macros[name] ?: default).also {
            require(it.isFinite() && it in 0f..1f) { "NACRE $name must be finite and in 0..1, got $it" }
        } }
        require(m.getValue("HOLD") == 0f) { "NACRE's paired-strum instrument is finite; HOLD must be 0. Held gestures are not implemented." }
        return m
    }
    @Suppress("UNUSED_PARAMETER")
    fun drumClassFor(voice: NacreVoice, macros: Map<String, Float> = emptyMap()): DrumClass = DrumClass.TONAL

    internal data class Options(val muteBassPlucks: Boolean = false, val muteUpperPlucks: Boolean = false,
        val disableCrossHarp: Boolean = false, val postCurrentOnly: Boolean = false,
        val bypassVenue: Boolean = false, val lockStrumTiming: Boolean = false)
    internal data class Event(val performer: String, val stringId: Int, val timeSeconds: Double,
        val frequencyHz: Double, val strength: Double, val direction: String)
    internal data class Probe(val snip: Snip, val raw: FloatArray, val stems: Map<String, FloatArray>,
        val events: List<Event>, val diagnostics: Map<String, Double>)

    fun render(voice: NacreVoice = NacreVoice.PEARL, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = 1f, seed: Long = DEFAULT_SEED): Snip =
        play(voice, macros, midi, velocity, seed, Options(), false).snip
    internal fun inspect(voice: NacreVoice = NacreVoice.PEARL, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = 1f, seed: Long = DEFAULT_SEED,
        options: Options = Options()): Probe = play(voice, macros, midi, velocity, seed, options, true)

    private data class CoreKey(val voice: NacreVoice, val touch: Float, val duet: Float, val midi: Int,
        val velocity: Float, val seed: Long, val muteBass: Boolean, val muteUpper: Boolean,
        val disableCross: Boolean, val postCurrentOnly: Boolean)
    private data class Core(val raw: FloatArray, val oldVenue: FloatArray, val bass: FloatArray,
        val upper: FloatArray, val sympathetic: FloatArray, val source: FloatArray,
        val events: List<Event>, val diagnostics: Map<String, Double>) {
        val bytes: Long get() = 4L * (raw.size + oldVenue.size + bass.size + upper.size + sympathetic.size + source.size)
    }
    private val cores = NacreCoreCache<CoreKey, Core>(CACHE_CAPACITY) { it.bytes }
    internal data class CoreCacheStats(val entries: Int, val bytes: Long, val hits: Long, val builds: Long, val capacity: Int)
    internal fun coreCacheStats(): CoreCacheStats = cores.snapshot().let {
        CoreCacheStats(it.entries, it.bytes, it.hits, it.builds, it.capacity)
    }
    internal fun clearCoreCache() = cores.clear()

    private fun stopIfInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("NACRE render cancelled")
    }
    private fun core(key: CoreKey): Pair<Core, Boolean> = cores.getOrBuild(key) {
        val macros = NacreModel1.defaults(key.voice) + mapOf("TOUCH" to key.touch, "DUET" to key.duet,
            "CURRENT" to CORE_CURRENT, "BLOOM" to CORE_BLOOM, "VENUE" to CORE_VENUE, "HOLD" to 0f)
        val p = NacreModel1.inspect(key.voice, macros, key.midi, key.velocity, key.seed,
            NacreModel1.Options(key.muteBass, key.muteUpper, key.disableCross, key.postCurrentOnly, false))
        stopIfInterrupted()
        val bass = p.stems.getValue("bass"); val upper = p.stems.getValue("upper")
        Core(p.raw, p.stems.getValue("venue"), bass, upper, p.stems.getValue("sympathetic"),
            FloatArray(p.raw.size) { bass[it] + upper[it] }, p.events.map {
                Event(it.performer, it.stringId, it.timeSeconds, it.frequencyHz, it.strength, it.direction)
            }, LinkedHashMap(p.diagnostics))
    }

    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    private fun returnAmount(bloom: Float): Double = when {
        bloom == CORE_BLOOM -> 1.0
        bloom < CORE_BLOOM -> .15 + .85 * smooth(bloom.toDouble() / CORE_BLOOM)
        else -> 1.0 + .50 * smooth((bloom.toDouble() - CORE_BLOOM) / (1.0 - CORE_BLOOM))
    }
    private fun sympatheticAmount(bloom: Float): Double = when {
        bloom == CORE_BLOOM -> 1.0
        bloom < CORE_BLOOM -> .35 + .65 * smooth(bloom.toDouble() / CORE_BLOOM)
        else -> 1.0 + .35 * smooth((bloom.toDouble() - CORE_BLOOM) / (1.0 - CORE_BLOOM))
    }
    private fun apertureRate(current: Float): Double = when {
        current == 0f -> 0.0
        current == CORE_CURRENT -> 2.0
        else -> minOf(4.0, .5 + 1.5 * sqrt(current.toDouble() / CORE_CURRENT))
    }
    private fun apertureDepth(current: Float): Double = .90 * smooth(current.toDouble() / CORE_CURRENT)

    private fun returns(source: FloatArray, current: Float, seaweed: Boolean): FloatArray {
        val delay = if (seaweed) doubleArrayOf(.22, .41, .64) else doubleArrayOf(.10, .21, .34)
        val cuts = if (seaweed) doubleArrayOf(1200.0, 900.0, 650.0) else doubleArrayOf(1600.0, 1200.0, 900.0)
        val weights = doubleArrayOf(.52, .31, .17)
        if (!seaweed && current != CORE_CURRENT) {
            // More flow favors a later close answer, with no moving delay. The heard anchor's
            // exact coefficients are kept; all weights remain positive and still sum to one.
            val flow = current.toDouble() - CORE_CURRENT
            weights[0] -= .20 * flow; weights[1] += .05 * flow; weights[2] += .15 * flow
        }
        val depth = if (seaweed) apertureDepth(current) else 0.0
        val rate = if (seaweed) apertureRate(current) else 0.0
        val peaks = doubleArrayOf(.40, .65, .85)
        val out = DoubleArray(source.size)
        for (path in 0..2) {
            val frames = (delay[path] * RATE).roundToInt()
            val a = 1.0 - exp(-2.0 * PI * cuts[path] / RATE)
            val phase = -2.0 * PI * 2.0 * peaks[path]
            var low = 0.0
            for (i in out.indices) {
                if (i % 32768 == 0) stopIfInterrupted()
                val input = if (i >= frames) source[i - frames].toDouble() else 0.0
                low += a * (input - low)
                // At zero freeze at the mean .55. At .20 keep the reference arithmetic exactly.
                val aperture = if (!seaweed) 1.0 else if (current == 0f) .55 else {
                    val wave = cos(2.0 * PI * rate * i / RATE + phase)
                    if (current == CORE_CURRENT) 1.0 - depth + depth * (.5 + .5 * wave)
                    else .55 + .5 * depth * wave
                }
                out[i] += weights[path] * low * aperture
            }
        }
        return FloatArray(out.size) { out[it].toFloat() }
    }

    private fun play(voice: NacreVoice, macros: Map<String, Float>, midi: Int, velocity: Float,
        seed: Long, options: Options, record: Boolean): Probe {
        require(midi in MIN_MIDI..MAX_MIDI) { "NACRE supports MIDI $MIN_MIDI..$MAX_MIDI, got $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "NACRE velocity must be finite and in 0..1, got $velocity" }
        val m = settled(voice, macros)
        require(!options.lockStrumTiming || m.getValue("TOUCH") == .30f) {
            "NACRE's original core cannot lock strum timing at a different TOUCH; use TOUCH .30 for timing-locked return comparisons"
        }
        stopIfInterrupted()
        val (c, hit) = core(CoreKey(voice, m.getValue("TOUCH"), m.getValue("DUET"), midi, velocity, seed,
            options.muteBassPlucks, options.muteUpperPlucks, options.disableCrossHarp, options.postCurrentOnly))
        val venue = m.getValue("VENUE").toDouble()
        val current = m.getValue("CURRENT"); val bloom = m.getValue("BLOOM")
        val gain = REFERENCE_RETURN_GAIN * returnAmount(bloom)
        val symGain = sympatheticAmount(bloom)
        val coral = if (!options.bypassVenue && venue < 1.0) returns(c.source, current, false) else null
        val weed = if (!options.bypassVenue && venue > 0.0) returns(c.source, current, true) else null
        val wet = FloatArray(c.raw.size) { i ->
            val blend = when {
                options.bypassVenue -> 0.0
                venue == 0.0 -> coral!![i].toDouble()
                venue == 1.0 -> weed!![i].toDouble()
                else -> (1.0 - venue) * coral!![i] + venue * weed!![i]
            }
            (gain * blend).toFloat()
        }
        val sympathetic = if (symGain == 1.0) c.sympathetic.copyOf() else
            FloatArray(c.sympathetic.size) { (c.sympathetic[it] * symGain).toFloat() }
        val raw = FloatArray(c.raw.size) { i ->
            val foreground = c.raw[i].toDouble() - c.oldVenue[i].toDouble()
            val delta = if (symGain == 1.0) 0.0 else sympathetic[i].toDouble() - c.sympathetic[i]
            // Keep the original bus residual; re-summing independently conditioned dry stems
            // would insert another Float rounding and lose the heard reference sample identity.
            (if (symGain == 1.0) foreground + wet[i] else foreground + delta + wet[i]).toFloat()
        }
        val native = FloatArray(raw.size) { (raw[it] * REFERENCE_OUTPUT_GAIN).toFloat() }
        val preGuardPeak = native.maxOf { abs(it) }
        val peakSafetyGain = if (preGuardPeak > .95f) (.95f / preGuardPeak).toDouble() else 1.0
        Dsp.limitPeak(native, .95f)
        stopIfInterrupted()
        val totalEnergy = raw.sumOf { it.toDouble() * it }
        var energy = 0.0; var energy99 = 0
        for (i in raw.indices) {
            energy += raw[i].toDouble() * raw[i]
            if (energy >= .99 * totalEnergy) { energy99 = i; break }
        }
        val stats = coreCacheStats()
        val diagnostics = LinkedHashMap(c.diagnostics).also {
            it["sourceModelVersion"] = 1.0; it["modelVersion"] = MODEL_VERSION.toDouble()
            it["legacyCurrentRateHz"] = c.diagnostics.getValue("currentRateHz")
            it["coreCurrentMacro"] = CORE_CURRENT.toDouble(); it["coreBloomMacro"] = CORE_BLOOM.toDouble()
            it["coreVenueMacro"] = CORE_VENUE.toDouble(); it["currentControlsReturnsOnly"] = 1.0
            it["currentRateHz"] = apertureRate(current); it["currentDepth"] = apertureDepth(current)
            it["sympatheticGain"] = symGain; it["returnGain"] = gain
            it["referenceOutputGain"] = REFERENCE_OUTPUT_GAIN; it["venueBlendWeightSum"] = 1.0
            it["peakSafetyGain"] = peakSafetyGain; it["effectiveOutputGain"] = REFERENCE_OUTPUT_GAIN * peakSafetyGain
            it["venueFeedbackGain"] = 0.0; it["rawPeak"] = raw.maxOf { v -> abs(v).toDouble() }
            it["energy99Seconds"] = if (totalEnergy == 0.0) 0.0 else energy99.toDouble() / RATE
            it["coreCacheHit"] = if (hit) 1.0 else 0.0
            it["coreCacheEntries"] = stats.entries.toDouble(); it["coreCacheBytes"] = stats.bytes.toDouble()
        }
        val stems = if (!record) emptyMap() else linkedMapOf("bass" to c.bass.copyOf(), "upper" to c.upper.copyOf(),
            "sympathetic" to sympathetic, "venue" to wet)
        return Probe(Snip(native, channels = 1, sampleRate = RATE), raw, stems, c.events.toList(), diagnostics)
    }
}

/** Small completed-value LRU with one cancellable builder, independent of the DSP's arrays. */
internal class NacreCoreCache<K : Any, V : Any>(private val capacity: Int, private val bytesOf: (V) -> Long) {
    init { require(capacity > 0) }
    private val values = LinkedHashMap<K, V>(capacity + 1, .75f, true)
    private val buildLock = ReentrantLock()
    private var generation = 0L
    private var hits = 0L
    private var builds = 0L
    internal data class Snapshot(val entries: Int, val bytes: Long, val hits: Long, val builds: Long, val capacity: Int)

    fun snapshot(): Snapshot = synchronized(values) {
        Snapshot(values.size, values.values.sumOf(bytesOf), hits, builds, capacity)
    }
    fun clear() = synchronized(values) { values.clear(); generation++; hits = 0; builds = 0 }

    fun getOrBuild(key: K, build: () -> V): Pair<V, Boolean> {
        active()
        val requestedGeneration = synchronized(values) {
            values[key]?.let { hits++; return it to true }
            generation
        }
        try { buildLock.lockInterruptibly() } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("NACRE core preparation cancelled")
        }
        try {
            // Another request may have completed this key while we waited. Warm hits, snapshots
            // and clear never acquire buildLock, so an unrelated cold note cannot hold them up.
            synchronized(values) { values[key]?.let { hits++; return it to true } }
            active()
            val completed = build()
            active()
            synchronized(values) {
                active()
                // Clearing during a build or queue wait invalidates that request's publication.
                if (generation == requestedGeneration) {
                    values[key] = completed; builds++
                    if (values.size > capacity) values.remove(values.keys.first())
                }
            }
            return completed to false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("NACRE core preparation cancelled")
        } finally { buildLock.unlock() }
    }

    private fun active() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("NACRE core preparation cancelled")
    }
}
