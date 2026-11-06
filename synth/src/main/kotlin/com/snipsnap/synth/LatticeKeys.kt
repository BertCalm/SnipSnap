package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Continuous onset and sustain, with the real preceding cycle retained for recurrence evidence.
 * State snapshots contain source carriers and collector histories. The complete conditioned
 * waveform is independently compared over a whole cycle, including its exported PCM precision.
 * PCM maximum differences cover both native note gain and the host's velocity-unscaled layer.
 */
data class LatticeKeyProbe(
    val note: KeyNote,
    val previousCycle: FloatArray,
    val loopStateStart: DoubleArray,
    val loopStateEnd: DoubleArray,
    val seamError: Double,
    val cycleError: Double,
    val pcm16SeamError: Double,
    val pcm16MaxCycleDifference: Int,
    val pcm24MaxCycleDifference: Int,
    val stateError: Double,
    val requestedHz: Double,
    val fittedHz: Double,
    val loopFrames: Int,
    val transitionEndSeconds: Double,
    val sourceGain: Double,
    val directSustainPeak: Double,
    val renderMillis: Double,
) {
    val prefixFrames: Int get() = note.loopStartFrame.toInt()
    val prefixSeconds: Double get() = prefixFrames.toDouble() / note.snip.sampleRate
    val pitchErrorCents: Double get() = 1200.0 * ln(fittedHz / requestedHz) / ln(2.0)
}

/** TRACE as a held keyboard instrument, independently versioned from its frozen finite model.
 *
 * The accepted pluck and SPEED trajectory start one source/collector network from rest.
 * Held model 2 keeps the original source curve through 35 ms, then continues pressure smoothly
 * to a .80 floor by .08 reference seconds. A fixed reversed-polarity 2x collector pickup
 * keeps the moving direct/halo readouts from canceling. Held model 1 retains its original
 * .30-to-.45-second decay continuation, .20 floor and original collector polarity. Once the
 * direct passage closes, the actual signed rejects alone sustain the collector. No unrelated attack and loop are joined, and no
 * fade, crossfade, normalization or endpoint repair conceals an unsettled cycle.
 *
 * TUNE is centred so [note]'s MIDI is absolute. The saved finite HOLD is ignored: a keygroup
 * host repeats the measured loop while held and applies its program envelope on note-off.
 * This sampled release fades the complete sound, rather than simulating collector release.
 * [note] retains native velocity timbre and gain; [layer] removes only velocity^.72 so the
 * instrument host applies note velocity once. Ordinary model-2 rendering stays unchanged.
 */
object LatticeKeys {
    const val MODEL_VERSION = 2
    const val RENDER_VERSION = MODEL_VERSION
    const val MIN_MIDI = 48
    const val MAX_MIDI = 72
    const val MAX_SAMPLE_SECONDS = 12.1
    const val SOURCE_FLOOR = .80
    /** Fixed authored pickup sensitivity, applied before the held model's branch mix. */
    const val COLLECTOR_PICKUP = -2.0
    internal const val TRANSITION_START = .035
    internal const val TRANSITION_END = .08
    private const val LEGACY_SOURCE_FLOOR = .20
    private const val LEGACY_TRANSITION_START = .30
    private const val LEGACY_TRANSITION_END = .45
    internal const val MAX_CYCLES = 6
    internal const val STATE_TOLERANCE = 1e-7
    internal const val CYCLE_TOLERANCE = 1e-10

    fun supports(patch: LatticePatch): Boolean =
        patch.model == Lattice.MODEL_VERSION && patch.voice == LatticeVoice.TRACE

    fun note(patch: LatticePatch, midi: Int = patch.midi,
        cancelled: () -> Boolean = { false }, heldModel: Int = MODEL_VERSION): KeyNote =
        probe(patch, midi, cancelled, heldModel).note

    fun layer(patch: LatticePatch, midi: Int = patch.midi,
        cancelled: () -> Boolean = { false }, heldModel: Int = MODEL_VERSION): KeyNote {
        val note = note(patch, midi, cancelled, heldModel)
        if (patch.velocity > 0f) {
            val nativeGain = patch.velocity.toDouble().pow(.72).toFloat()
            for (i in note.snip.samples.indices) note.snip.samples[i] /= nativeGain
        }
        return note
    }

    fun probe(patch: LatticePatch, midi: Int = patch.midi,
        cancelled: () -> Boolean = { false }, heldModel: Int = MODEL_VERSION): LatticeKeyProbe {
        require(supportsHeldModel(heldModel)) { "unsupported LATTICE held model $heldModel" }
        require(supports(patch)) { "LATTICE held keys support TRACE model ${Lattice.MODEL_VERSION} only" }
        require(midi in MIN_MIDI..MAX_MIDI) { "LATTICE keys require absolute MIDI $MIN_MIDI..$MAX_MIDI: $midi" }
        return Lattice.traceKeyProbe(patch, midi, cancelled, heldModel)
    }

    fun supportsHeldModel(heldModel: Int): Boolean = heldModel == 1 || heldModel == MODEL_VERSION

    internal fun sourceTransitionEnd(heldModel: Int): Double = when (heldModel) {
        1 -> LEGACY_TRANSITION_END
        MODEL_VERSION -> TRANSITION_END
        else -> throw IllegalArgumentException("unsupported LATTICE held model $heldModel")
    }

    internal fun collectorPickup(heldModel: Int): Double = when (heldModel) {
        1 -> 1.0
        MODEL_VERSION -> COLLECTOR_PICKUP
        else -> throw IllegalArgumentException("unsupported LATTICE held model $heldModel")
    }

    /** Cubic Hermite pressure continuation. Value and slope meet the original pluck at the
     * start and meet a flat pressure floor at the end. Model 2's initial tangent is 2.86 times
     * its secant, below the monotone bound of 3, so the source never dips below its floor.
     * Only this held source changes: the accepted finite B never calls this method. */
    internal fun sourceEffort(referenceTime: Double, velocity: Float, heldModel: Int = MODEL_VERSION): Double {
        val attack = .025 - .013 * velocity
        val envelope = if (referenceTime < attack) .5 - .5 * cos(PI * referenceTime / attack) else 1.0
        val startTime: Double
        val endTime: Double
        val floor: Double
        when (heldModel) {
            1 -> { startTime = LEGACY_TRANSITION_START; endTime = LEGACY_TRANSITION_END; floor = LEGACY_SOURCE_FLOOR }
            MODEL_VERSION -> { startTime = TRANSITION_START; endTime = TRANSITION_END; floor = SOURCE_FLOOR }
            else -> throw IllegalArgumentException("unsupported LATTICE held model $heldModel")
        }
        val articulation = when {
            referenceTime <= startTime -> exp(-referenceTime / .23)
            referenceTime >= endTime -> floor
            else -> {
                val duration = endTime - startTime
                val u = (referenceTime - startTime) / duration
                val u2 = u * u
                val u3 = u2 * u
                val start = exp(-startTime / .23)
                (2 * u3 - 3 * u2 + 1) * start + (u3 - 2 * u2 + u) * duration * (-start / .23) +
                    (-2 * u3 + 3 * u2) * floor
            }
        }
        return envelope * articulation
    }
}
