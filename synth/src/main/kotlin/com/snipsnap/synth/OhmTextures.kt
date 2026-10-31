package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bounded source energy for OHM's two answering ports, before their shared bridge.
 *
 * [fill]'s observed sample is the dry exciter bus, never a resonator or the audio output.
 * Grains copy already written audio at native playback speed. Freeze captures all 250 ms
 * of populated history, removes its DC and joins that actual buffer with a unity-sum
 * crossfade. This is a captured-buffer freeze, not a spectral freeze or a held scalar.
 * Source gain follows a companion's round-trip loss. Revision 4 first rings an independent,
 * passive source mode from that dry drive: a force impulse is not itself a populated
 * audio history. Grains then rearrange short pieces of that ringing start while keeping
 * the note's source phase; the seed selects different actual source cycles. Freeze
 * repeats a captured stretch. Fixed source calibration keeps this preparation separate
 * from output loudness. Captured-source banks have a bounded, monotone source-unit
 * compressor; it cannot turn a vanishing input into a fixed-level signal. No coupled body
 * sample enters that source mode or its memory.
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
    private val revision: Int = 3,
    private val rootDecaySeconds: Float = 4.41f,
) {
    companion object {
        const val HISTORY_SECONDS = .25f
        private const val ELDER_GAIN = .035f
        private const val MEMORY_GAIN = .5f
        private const val SOURCE_STATE_CEILING = 2f
        private const val CAPTURED_BANK_MAX_GAIN = 32.0
        private const val CAPTURED_BANK_RMS = .20
        private const val CAPTURED_BANK_PEAK = .80
        private const val MAX_GRAINS = 4
        private const val LOG_1000 = 6.907755278982137

        /** Center the actual Hann-weighted grain while keeping its zero-valued ends. */
        internal fun centerWindowedGrain(samples: FloatArray, window: FloatArray) {
            require(samples.size == window.size)
            val mean = windowedMean(samples, window, 0)
            for (i in samples.indices) samples[i] = (samples[i] - mean).toFloat()
        }

        private fun windowedMean(samples: FloatArray, window: FloatArray, offset: Int): Double {
            require(offset >= 0 && offset + window.size <= samples.size)
            var weighted = 0.0
            var weight = 0.0
            for (i in window.indices) {
                if (i % 4096 == 0 && Thread.currentThread().isInterrupted)
                    throw InterruptedException("OHM grain preparation cancelled")
                weighted += samples[offset + i].toDouble() * window[i]
                weight += window[i]
            }
            return if (weight > 0) weighted / weight else 0.0
        }
    }

    init {
        require(midi in 48..72) { "OHM texture MIDI must be 48..72" }
        require(rate in Dsp.RATE..384_000) { "OHM texture rate must be 44100..384000" }
        require(loopFrames == null || loopFrames >= 256) { "OHM texture cycle is too short" }
        require(revision in 3..4) { "unsupported OHM texture revision $revision" }
        require(rootDecaySeconds.isFinite() && rootDecaySeconds > 0f)
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
    internal val historyFrames = (HISTORY_SECONDS * rate).roundToInt()
    private val grainFrames = (options.grainSeconds * rate).roundToInt().coerceAtLeast(3)
    private val hop = ((grainFrames - 1) / 2).coerceAtLeast(1)
    private val window = FloatArray(grainFrames) { i ->
        (.5 - .5 * cos(2 * PI * i / (grainFrames - 1))).toFloat()
    }
    private val memoryEnabled = options.grains > 0f || options.freeze > 0f
    // These are fixed conversions from three different dry-drive units, not a
    // per-note normalization. In particular a bow's small starting strike is
    // heard against its continuously driven friction junction.
    private val grainGain = when (options.exciter) {
        OhmExciter.PLUCK -> 192f
        OhmExciter.BOW -> 768f
        OhmExciter.CAPTURED -> 768f
    }
    private val freezeGain = when (options.exciter) {
        OhmExciter.PLUCK -> 160f
        OhmExciter.BOW -> 800f
        OhmExciter.CAPTURED -> 384f
    }
    /** Absolute per-port bound including mean subtraction, or explicit source refusal. */
    internal val excitationCeiling = if (revision == 4)
        max(ELDER_GAIN, max(grainGain, freezeGain) * 2 * SOURCE_STATE_CEILING) * loss
        else max(ELDER_GAIN, MEMORY_GAIN * 2) * loss
    private val sourceDriveGain = when (options.exciter) {
        OhmExciter.PLUCK -> 1.2f
        OhmExciter.BOW -> 8f
        OhmExciter.CAPTURED -> (.12 * minOf(1.0, 261.625565 / rootHz) *
            minOf(1.0, .25 * Dsp.RATE / (options.capture?.frameCount ?: Dsp.RATE / 4))).toFloat()
    }
    private val sourceParticipation = doubleArrayOf(2 * rootHz)
    private val sourceInstrument = if (revision == 4 && memoryEnabled) {
        // One exact Double-precision rotation has only the requested mode.
        // A string would also carry DC/upper modes that the receiving comb can
        // disproportionately amplify. No springs or coupled-body inputs exist.
        Modes.Bank(1, rate).also { it.tune(0, rootHz, .65) }
    } else null
    // A foreign capture can force the source at its own recording pitch during
    // contact. Capture the freely ringing response only after that contact ends.
    private val bankStart = if (revision == 4 && options.exciter == OhmExciter.CAPTURED)
        ((options.capture?.frameCount ?: 0).toDouble() * rate / Dsp.RATE + .015 * rate).roundToInt() else 0
    private var grainBankFromFrame = 0
    private var memoryEnvelope = 1f
    private val memoryDecay = exp(-LOG_1000 / (rate * rootDecaySeconds.toDouble())).toFloat()
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
    private var frozenCursor = 0
    private var grainBank: FloatArray? = null
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
    internal var maxSourceState = 0f
        private set
    internal var bankConditioningGain = 1f
        private set
    internal var bankRmsBefore = 0f
        private set
    internal var bankRmsAfter = 0f
        private set
    internal var maxPreparedPeak = 0f
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
            val sourceAudio = if (sourceInstrument == null) source else FloatArray(max(size, bankStart + historyFrames)) { i ->
                checkCancelled(i)
                ringingSource(if (i < source.size) source[i] else 0f)
            }
            // All samples come from the supplied written cycle. A short cycle may
            // repeat inside this history; it is still actual source audio.
            // Renewed picks and captures begin at phase zero; a tail-only capture
            // would miss their actual stroke and retain only the quiet forcing.
            val snapshot = FloatArray(historyFrames) { i ->
                checkCancelled(i)
                sourceAudio[if (revision == 4) bankStart + i else i % size]
            }
            removeMean(snapshot)
            conditionBank(snapshot)
            frozenSnapshotFrames = snapshot.size
            if (options.grains > 0f) {
                val overlap = FloatArray(size)
                val count = max(2, (size.toDouble() / hop).roundToInt())
                for (event in 0 until count) {
                    checkCancelled(event * grainFrames)
                    val onset = (event.toDouble() * size / count).roundToInt()
                    val offset = sourceOffset(snapshot.size - grainFrames, onset,
                        if (revision == 4) bankStart else 0) ?: continue
                    val centered = if (revision == 4) windowedMean(snapshot, window, offset) else 0.0
                    for (age in window.indices) {
                        checkCancelled(age)
                        val frame = (onset + age) % size
                        val sample = if (revision == 4) (snapshot[offset + age] - centered).toFloat()
                            else snapshot[offset + age]
                        memory[frame] += sample * window[age]
                        overlap[frame] += window[age]
                    }
                }
                for (i in memory.indices) {
                    checkCancelled(i)
                    memory[i] = memory[i] / max(1f, overlap[i]) * options.grains * (1 - options.freeze) *
                        (if (revision == 4) grainGain else 1f)
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
                    memory[i] += options.freeze * replay[i] * (if (revision == 4) freezeGain else 1f)
                }
                frozenAtFrame = 0
            }
            for (i in memory.indices) {
                checkCancelled(i)
                memory[i] *= (if (revision == 4) 1f else MEMORY_GAIN) * loss
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
                if ((options.freeze > 0f || revision == 4) && frozen == null && frame >= bankStart + historyFrames) {
                    val snapshot = FloatArray(historyFrames) { i -> history[(frame - historyFrames + i) % historyFrames] }
                    removeMean(snapshot)
                    conditionBank(snapshot)
                    frozen = joinedSnapshot(snapshot)
                    // Continue the captured source clock. L contains whole root
                    // periods; starting at N mod L retains the phase at capture,
                    // whereas cursor zero adds a pitch-dependent 250 ms delay.
                    if (revision == 4) frozenCursor = snapshot.size % requireNotNull(frozen).size
                    if (revision == 4) grainBank = snapshot
                    grainBankFromFrame = frame - historyFrames
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
                wet = wet / max(1f, overlap) * options.grains * (1 - options.freeze) *
                    (if (revision == 4) grainGain else 1f)
                frozen?.let { capture ->
                    val age = frame - requireNotNull(frozenAtFrame)
                    wet += options.freeze * smooth(age.toFloat() / (.025f * rate)) * capture[(age + frozenCursor) % capture.size] *
                        (if (revision == 4) freezeGain else 1f)
                }
                output[1] = wet * (if (revision == 4) 1f else MEMORY_GAIN) * loss * memoryParticipation
                if (revision == 4 && options.exciter != OhmExciter.BOW) {
                    // Free primaries decay, so finite memory must not replace
                    // their root after it has faded. A bow remains powered until
                    // the caller releases contact; HOLD never takes this path.
                    output[1] *= memoryEnvelope
                    memoryEnvelope *= memoryDecay
                }
                // Write only after every read and after the possible snapshot.
                history[frame % historyFrames] = ringingSource(observed)
            }
        }
        maxOutput = max(maxOutput, max(abs(output[0]), abs(output[1])))
    }

    private fun startGrain(frame: Int) {
        if (revision == 4 && options.exciter == OhmExciter.CAPTURED && grainBank == null) return
        val available = minOf(frame, historyFrames)
        if (available < grainFrames) return
        val grain = grains.firstOrNull { it.age >= grainFrames }
            ?: error("OHM bounded grain schedule exhausted its slots")
        val bank = grainBank
        val sourceStart = if (bank == null) frame - available else grainBankFromFrame
        val span = (bank?.size ?: available) - grainFrames
        // Wait for a phase-compatible written slice rather than reading future
        // audio when the first grain has only just filled its history.
        val offset = sourceOffset(span, frame, sourceStart) ?: return
        val from = sourceStart + offset
        for (i in grain.samples.indices) grain.samples[i] = bank?.get(from - grainBankFromFrame + i)
            ?: history[(from + i) % historyFrames]
        // A slice's plain mean is not its mean after Hann weighting. A short
        // 2.6-cycle grain can otherwise inject a DC/low-mode impulse into the
        // companion even though the unwindowed slice sums to zero.
        if (revision == 4) centerWindowedGrain(grain.samples, window) else removeMean(grain.samples)
        grain.age = 0
        grainCount++
        lastGrainSourceFromFrame = from
        lastGrainSourceUntilFrame = from + grainFrames
    }

    private fun randomOffset(span: Int): Int =
        (((random.next() + 1) * .5f) * span).roundToInt().coerceIn(0, span)

    private fun sourceOffset(span: Int, onsetFrame: Int, sourceStart: Int): Int? {
        val wanted = randomOffset(span)
        if (revision != 4) return wanted
        val period = rate / rootHz
        val elapsed = (onsetFrame - sourceStart).toDouble()
        val phase = ((elapsed % period) + period) % period
        val firstCycle = ceil(-phase / period).toInt()
        val lastCycle = floor((span - phase) / period).toInt()
        if (lastCycle < firstCycle) return null
        val cycle = ((wanted - phase) / period).roundToInt().coerceIn(firstCycle, lastCycle)
        // Playback itself stays native-speed. Only its start is chosen, within
        // half a source frame of the running note phase, from real written audio.
        return (phase + cycle * period).roundToInt().coerceIn(0, span)
    }

    private fun joinedSnapshot(snapshot: FloatArray): FloatArray {
        // A long fade over the head erases a pluck's only populated milliseconds.
        // The ringing-source revision instead keeps all but about one root
        // period, with at least a 1 ms join; playback remains at native speed.
        val cycles = if (revision == 4) floor(rootHz * (HISTORY_SECONDS - .001)).toInt().coerceAtLeast(1)
            else (rootHz * HISTORY_SECONDS * .9).roundToInt().coerceAtLeast(1)
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

    private fun ringingSource(drive: Float): Float {
        val instrument = sourceInstrument ?: return drive
        instrument.step()
        instrument.drive(sourceParticipation, (drive * sourceDriveGain).toDouble())
        val value = instrument.velocity(0).toFloat()
        // Refuse a source outside the stated budget; never clip its feedback or
        // normalize a quiet input into that budget. The physical core separately
        // checks all three of its own states, before any output-level processing.
        check(value.isFinite() && abs(value) <= SOURCE_STATE_CEILING) { "OHM ringing source exceeded its fixed energy budget" }
        maxSourceState = max(maxSourceState, abs(value))
        return value
    }

    private fun conditionBank(samples: FloatArray) {
        if (revision != 4 || options.exciter != OhmExciter.CAPTURED) return
        var energy = 0.0
        var peak = 0.0
        for (i in samples.indices) {
            checkCancelled(i)
            val x = samples[i].toDouble()
            energy += x * x
            peak = max(peak, abs(x))
        }
        val rms = sqrt(energy / samples.size)
        // RMS_out = 32*rms / sqrt(1+(32*rms/.2)^2): increasing from zero
        // with finite slope, approaching a fixed source budget. This does not
        // normalize every recording to the same level or invent quiet energy.
        val relative = CAPTURED_BANK_MAX_GAIN * rms / CAPTURED_BANK_RMS
        val gain = minOf(CAPTURED_BANK_MAX_GAIN / sqrt(1 + relative * relative),
            if (peak > 0) CAPTURED_BANK_PEAK / peak else CAPTURED_BANK_MAX_GAIN)
        for (i in samples.indices) {
            checkCancelled(i)
            samples[i] = (samples[i] * gain).toFloat()
        }
        bankConditioningGain = gain.toFloat()
        bankRmsBefore = rms.toFloat()
        bankRmsAfter = (rms * gain).toFloat()
        maxPreparedPeak = (peak * gain).toFloat()
        check(maxPreparedPeak <= CAPTURED_BANK_PEAK + 1e-6) { "OHM prepared source exceeded its peak budget" }
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
