package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Bounded source energy for OHM's two answering ports, before their shared bridge.
 *
 * [fill]'s observed sample is the dry exciter bus, never a resonator or the audio output.
 * Grains copy already written audio at native playback speed. Freeze captures all 250 ms
 * of populated history, removes its DC and joins that actual buffer with a unity-sum
 * crossfade. This is a captured-buffer freeze, not a spectral freeze or a held scalar.
 * Source gain follows a companion's round-trip loss; no normalization or powered feedback
 * can turn a quiet captured stroke into a full-scale continuous driver.
 *
 * A held note supplies one complete dry source cycle to [prepareLoop]. Its seeded grain
 * schedule and FM phases then close over that cycle, independently of physical settling.
 * The body still has to demonstrate convergence through genuinely subsequent cycles.
 */
internal class OhmTextures(
    private val options: OhmOptions,
    midi: Int,
    seed: Int,
    private val rate: Int,
    private val loopFrames: Int? = null,
) {
    companion object {
        const val HISTORY_SECONDS = .25f
        private const val ELDER_GAIN = .035f
        private const val MEMORY_GAIN = .5f
        private const val MAX_GRAINS = 4
        private const val LOG_1000 = 6.907755278982137
    }

    init {
        require(midi in 48..72) { "OHM texture MIDI must be 48..72" }
        require(rate in Dsp.RATE..384_000) { "OHM texture rate must be 44100..384000" }
        require(loopFrames == null || loopFrames >= 256) { "OHM texture cycle is too short" }
    }

    private val rootHz = Keys.midiHz(midi).toDouble()
    internal val elderCarrierHz = rootHz * 2
    // The Carson envelope is an energy estimate, not a finite-sideband proof. The
    // measured 4x/8x source comparison remains necessary even below this limit.
    private val sourceBandHz = minOf(Dsp.RATE * .40, rate * .40)
    internal val effectiveFmIndex = minOf(options.fmIndex.toDouble(),
        max(0.0, (sourceBandHz / elderCarrierHz - 1) / options.fmRatio - 1))
    internal val fmBandEstimateHz = elderCarrierHz * (1 + (effectiveFmIndex + 1) * options.fmRatio)
    private val loss = (1 - exp(-LOG_1000 / (2 * rootHz * .9))).toFloat()
    /** Absolute per-port bound for a normalized source, including DC subtraction. */
    internal val excitationCeiling = max(ELDER_GAIN, MEMORY_GAIN * 2) * loss
    internal val historyFrames = (HISTORY_SECONDS * rate).roundToInt()
    private val grainFrames = (options.grainSeconds * rate).roundToInt().coerceAtLeast(3)
    private val hop = ((grainFrames - 1) / 2).coerceAtLeast(1)
    private val window = FloatArray(grainFrames) { i ->
        (.5 - .5 * cos(2 * PI * i / (grainFrames - 1))).toFloat()
    }
    private val memoryEnabled = options.grains > 0f || options.freeze > 0f
    private val history = FloatArray(if (memoryEnabled && loopFrames == null) historyFrames else 0)
    private class Grain(size: Int) {
        val samples = FloatArray(size)
        var age = size
    }
    private val grains = if (options.grains > 0f && loopFrames == null)
        Array(MAX_GRAINS) { Grain(grainFrames) } else emptyArray()
    private val random = Dsp.Noise(Dsp.seedFor("OHM", 3, midi, seed, "audio-grains"))
    private var nextGrain = grainFrames
    private var frozen: FloatArray? = null
    private var carrierPhase = 0.0
    private var modulatorPhase = 0.0
    private var expectedFrame = 0
    private var lastActivity = Float.NaN
    private var memoryParticipation = 0f
    private var elderParticipation = 0f
    private var periodicElder: FloatArray? = null
    private var periodicMemory: FloatArray? = null

    internal var maxOutput = 0f
        private set
    internal var maxActiveGrains = 0
        private set
    internal var grainCount = 0
        private set
    internal var lastGrainSourceFromFrame = -1
        private set
    internal var lastGrainSourceUntilFrame = -1
        private set
    internal var frozenAtFrame: Int? = null
        private set
    internal var frozenSnapshotFrames = 0
        private set

    /**
     * Build periodic sources once, from one complete normalized dry source cycle.
     * This explicitly prepares reusable material; it does not pretend to capture
     * future audio during a finite note. No resonator output is accepted here.
     */
    fun prepareLoop(source: FloatArray) {
        checkCancelled(0)
        val size = requireNotNull(loopFrames) { "only held textures accept a source cycle" }
        require(source.size == size) { "OHM texture source cycle length differs" }
        for (i in source.indices) {
            checkCancelled(i)
            require(source[i].isFinite() && abs(source[i]) <= 1f) { "OHM texture source must be finite and normalized" }
        }
        check(expectedFrame == 0) { "prepare OHM texture material before playback" }
        val elder = FloatArray(size)
        if (options.elder > 0f) {
            val carrierCycles = (elderCarrierHz * size / rate).roundToInt().coerceAtLeast(1)
            val modulatorCycles = (elderCarrierHz * options.fmRatio * size / rate).roundToInt().coerceAtLeast(1)
            for (i in elder.indices) {
                checkCancelled(i)
                elder[i] = (sin(2 * PI * carrierCycles * i / size + effectiveFmIndex *
                    sin(2 * PI * modulatorCycles * i / size)) * ELDER_GAIN * loss * options.elder).toFloat()
            }
        }
        val memory = FloatArray(size)
        if (memoryEnabled) {
            // All samples come from the supplied written cycle. A short cycle may
            // repeat inside this history; it is still actual source audio.
            // Renewed picks and captures begin at phase zero; a tail-only capture
            // would miss their actual stroke and retain only the quiet forcing.
            val snapshot = FloatArray(historyFrames) { i -> checkCancelled(i); source[i % size] }
            removeMean(snapshot)
            frozenSnapshotFrames = snapshot.size
            if (options.grains > 0f) {
                val overlap = FloatArray(size)
                val count = max(2, (size.toDouble() / hop).roundToInt())
                for (event in 0 until count) {
                    checkCancelled(event * grainFrames)
                    val onset = (event.toDouble() * size / count).roundToInt()
                    val offset = randomOffset(snapshot.size - grainFrames)
                    for (age in window.indices) {
                        checkCancelled(age)
                        val frame = (onset + age) % size
                        memory[frame] += snapshot[offset + age] * window[age]
                        overlap[frame] += window[age]
                    }
                }
                for (i in memory.indices) {
                    checkCancelled(i)
                    memory[i] = memory[i] / max(1f, overlap[i]) * options.grains * (1 - options.freeze)
                }
                grainCount = count
            }
            if (options.freeze > 0f) {
                val captured = joinedSnapshot(snapshot)
                val replay = FloatArray(size) { i -> checkCancelled(i); captured[i % captured.size] }
                // Keep native playback speed: fitting an arbitrary integer count
                // of buffer traversals to the host cycle would transpose it.
                // Close only this source's read schedule, before the body runs.
                val fade = minOf((.025f * rate).roundToInt(), size / 4, captured.size / 4).coerceAtLeast(2)
                for (i in 0 until fade) {
                    val w = smooth(i.toFloat() / (fade - 1))
                    val preceding = captured[Math.floorMod(i - fade, captured.size)]
                    replay[size - fade + i] = (1 - w) * replay[size - fade + i] + w * preceding
                }
                for (i in memory.indices) {
                    checkCancelled(i)
                    memory[i] += options.freeze * replay[i]
                }
                frozenAtFrame = 0
            }
            for (i in memory.indices) {
                checkCancelled(i)
                memory[i] *= MEMORY_GAIN * loss
            }
        }
        periodicElder = elder
        periodicMemory = memory
    }

    /** Overwrite output[0..1]; the caller supplies normalized dry source only. */
    fun fill(frame: Int, activity: Float, agreement: Float, observed: Float, output: FloatArray) {
        require(output.size >= 2) { "OHM textures need two source ports" }
        require(frame == expectedFrame) { "OHM textures must advance one source frame at a time" }
        require(activity.isFinite() && activity in 0f..1f && agreement.isFinite() && agreement in 0f..1f)
        require(observed.isFinite() && abs(observed) <= 1f) { "OHM observed input must be a normalized dry source" }
        expectedFrame++
        output[0] = 0f
        output[1] = 0f
        if (activity != lastActivity) {
            memoryParticipation = activity.pow(.85f)
            val opening = smooth((activity - .25f) / .75f)
            elderParticipation = memoryParticipation * opening
            lastActivity = activity
        }
        if (loopFrames != null) {
            val elder = checkNotNull(periodicElder) { "prepare held OHM texture sources first" }
            val memory = checkNotNull(periodicMemory)
            output[0] = elder[frame % loopFrames] * elderParticipation
            output[1] = memory[frame % loopFrames] * memoryParticipation
        } else {
            if (options.elder > 0f) {
                val attack = smooth(frame.toFloat() / (.02f * rate))
                output[0] = (sin(2 * PI * carrierPhase + effectiveFmIndex * sin(2 * PI * modulatorPhase)) *
                    ELDER_GAIN * loss * options.elder * elderParticipation * attack).toFloat()
                carrierPhase = (carrierPhase + elderCarrierHz / rate) % 1.0
                modulatorPhase = (modulatorPhase + elderCarrierHz * options.fmRatio / rate) % 1.0
            }
            if (memoryEnabled) {
                if (options.freeze > 0f && frozen == null && frame >= historyFrames) {
                    val snapshot = FloatArray(historyFrames) { i -> history[(frame - historyFrames + i) % historyFrames] }
                    removeMean(snapshot)
                    frozen = joinedSnapshot(snapshot)
                    frozenAtFrame = frame
                    frozenSnapshotFrames = snapshot.size
                }
                if (options.grains > 0f && frame >= nextGrain) {
                    startGrain(frame)
                    nextGrain += hop
                }
                var wet = 0f
                var overlap = 0f
                var active = 0
                for (grain in grains) if (grain.age < grainFrames) {
                    wet += grain.samples[grain.age] * window[grain.age]
                    overlap += window[grain.age]
                    grain.age++
                    active++
                }
                maxActiveGrains = max(maxActiveGrains, active)
                wet = wet / max(1f, overlap) * options.grains * (1 - options.freeze)
                frozen?.let { capture ->
                    val age = frame - requireNotNull(frozenAtFrame)
                    wet += options.freeze * smooth(age.toFloat() / (.025f * rate)) * capture[age % capture.size]
                }
                output[1] = wet * MEMORY_GAIN * loss * memoryParticipation
                // Write only after every read and after the possible snapshot.
                history[frame % historyFrames] = observed
            }
        }
        maxOutput = max(maxOutput, max(abs(output[0]), abs(output[1])))
    }

    private fun startGrain(frame: Int) {
        val available = minOf(frame, historyFrames)
        if (available < grainFrames) return
        val grain = grains.firstOrNull { it.age >= grainFrames }
            ?: error("OHM bounded grain schedule exhausted its slots")
        val from = frame - available + randomOffset(available - grainFrames)
        for (i in grain.samples.indices) grain.samples[i] = history[(from + i) % historyFrames]
        removeMean(grain.samples)
        grain.age = 0
        grainCount++
        lastGrainSourceFromFrame = from
        lastGrainSourceUntilFrame = from + grainFrames
    }

    private fun randomOffset(span: Int): Int =
        (((random.next() + 1) * .5f) * span).roundToInt().coerceIn(0, span)

    private fun joinedSnapshot(snapshot: FloatArray): FloatArray {
        val cycles = (rootHz * HISTORY_SECONDS * .9).roundToInt().coerceAtLeast(1)
        val length = (cycles * rate / rootHz).roundToInt().coerceIn(snapshot.size / 2, snapshot.size - 2)
        val fade = snapshot.size - length
        return FloatArray(length) { i ->
            checkCancelled(i)
            if (i >= fade) snapshot[i] else {
                val w = smooth(i.toFloat() / (fade - 1))
                (1 - w) * snapshot[length + i] + w * snapshot[i]
            }
        }.also(::removeMean)
    }

    private fun removeMean(samples: FloatArray) {
        var sum = 0.0
        for (i in samples.indices) { checkCancelled(i); sum += samples[i] }
        val mean = sum / samples.size
        for (i in samples.indices) { checkCancelled(i); samples[i] = (samples[i] - mean).toFloat() }
    }

    private fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

    private fun checkCancelled(frame: Int) {
        if (frame % 4096 == 0 && Thread.currentThread().isInterrupted) throw InterruptedException("OHM texture preparation cancelled")
    }
}
