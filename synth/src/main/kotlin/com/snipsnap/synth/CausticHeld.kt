package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Genuine neighbouring cycles are retained for inspection before any export crop. */
data class CausticHeldProbe(
    val note: KeyNote,
    val loop: Snip,
    val preceding: FloatArray,
    val following: FloatArray,
    /** Full 4x/16x pre-finish history, not the output-rate legacy diagnostic channel. */
    val raw: FloatArray,
    val seamError: Double,
    val continuationError: Double,
    val diagnostics: CausticSustainDiagnostics,
    val prelevelLoop: FloatArray,
    val legacyLoop: FloatArray,
    val internalLoop: FloatArray,
    val modeFrequencies: DoubleArray,
    val finishNanos: Long,
    val renderNanos: Long,
    val loopPhaseOffsetFrames: Int,
    val internalSampleRate: Int,
)

/** Finish continuous history once; a sample crop must never manufacture the seam proof. */
internal object CausticHeld {
    const val MAX_CONTINUATION_ERROR = 1e-3

    fun probe(voice: CausticVoice, macros: Map<String, Float>, midi: Int, velocity: Float,
        model: Int, oversample: Int, normalize: Boolean, cancelled: () -> Boolean,
        record: Boolean = true): CausticHeldProbe {
        val started = System.nanoTime()
        fun checkWanted() {
            if (Thread.currentThread().isInterrupted || cancelled())
                throw CancellationException("CAUSTIC held render no longer wanted")
        }
        checkWanted()
        require(model == CausticInstrument.MODEL_VERSION && CausticInstrument.isLoop(voice, macros, model)) {
            "CAUSTIC sustained rendering needs model 2 and HOLD 1"
        }
        require(midi in CausticInstrument.MIDI_MIN..CausticInstrument.MIDI_MAX) { "CAUSTIC MIDI outside 36..84" }
        require(oversample == 4 || oversample == 16) { "CAUSTIC oversample must be 4 or 16" }
        val m = CausticInstrument.settled(voice, macros, model)
        val effort = (velocity.takeIf { it.isFinite() } ?: .75f).coerceIn(0f, 1f)
        val trace = CausticSustain.held(voice, m, midi, effort.toDouble(), oversample, cancelled)
        val finishStarted = System.nanoTime()
        checkWanted()
        val originalStart = trace.loopStartFrame
        val size = trace.loopFrames
        val originalEnd = trace.playedEndFrame
        require(originalStart >= size && originalEnd == originalStart + size &&
            trace.internal.size >= (originalEnd + size + 64) * oversample) {
            "CAUSTIC held trace lacks genuine previous and following periods"
        }
        check(trace.diagnostics.stateResidual.isFinite() && trace.diagnostics.stateResidual < 1e-4) {
            "CAUSTIC modal state did not close: ${trace.diagnostics.stateResidual}"
        }
        check(trace.diagnostics.continuationError.isFinite() && trace.diagnostics.continuationError < 1e-4) {
            "CAUSTIC raw continuation did not close: ${trace.diagnostics.continuationError}"
        }
        // The helper returns a fresh, single-owner trace. Ordinary playback needs
        // neither a second raw copy nor the legacy comparison DC blocker.
        val raw = if (record) trace.internal.copyOf() else FloatArray(0)
        val filtered = trace.internal
        Tide.bandLimit(filtered, Dsp.RATE * oversample)
        checkWanted()
        val decimated = if (oversample == 4) Dsp.decimate(filtered, Dsp.RATE)
            else Dsp.decimate(Dsp.decimate(filtered, Dsp.RATE * 4), Dsp.RATE)
        // Both DC blockers see the complete onset and settling history. No tail fade is
        // applied to a held cycle; key release belongs to the sampler's envelope.
        fun condition(cutoff: Float, subtractMean: Boolean): FloatArray {
            val out = decimated.copyOf()
            val mean = if (subtractMean) (out.sumOf { it.toDouble() } / out.size.coerceAtLeast(1)).toFloat() else 0f
            val hp = Dsp.FixedOnePole(cutoff, Dsp.RATE)
            for (i in out.indices) {
                if ((i and 8191) == 0) checkWanted()
                val x = out[i] - mean
                out[i] = x - hp.lp(x)
            }
            return out
        }
        val legacy = if (record) condition(8f, false) else FloatArray(0)
        val prelevel = condition(20f, true)
        val samples = prelevel.copyOf()
        if (normalize && effort > 0f)
            Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * effort.pow(.4f))
        checkWanted()
        val ceiling = if (normalize) .991f else 8f
        check(samples.all { it.isFinite() && abs(it) <= ceiling }) { "CAUSTIC held output exceeded its bound" }
        // Choose the pad's initial phase from genuine continuation, rather than rotate
        // a copied loop. Keygroups keep that same phase and the real history before it.
        // Centered decimation also needs genuine signal beyond the last compared period.
        val lastOffset = minOf(size - 1, samples.size - originalEnd - size - 64)
        require(lastOffset >= 0) { "CAUSTIC held trace lacks decimator guard history" }
        val offset = (0..lastOffset).minByOrNull { abs(samples[originalStart + it]) } ?: 0
        val start = originalStart + offset
        val end = originalEnd + offset
        val previous = samples.copyOfRange(start - size, start)
        val loop = samples.copyOfRange(start, end)
        val following = samples.copyOfRange(end, end + size)
        val continuation = maxOf(relativeDifference(previous, loop), relativeDifference(loop, following))
        check(continuation < MAX_CONTINUATION_ERROR) {
            "CAUSTIC conditioned continuation does not close: $continuation"
        }
        val note = KeyNote(Snip(samples.copyOf(end), 1, Dsp.RATE), start.toLong())
        val seam = if (effort == 0f) 0.0 else Keys.seamError(note.snip.samples, start)
        check(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR) { "CAUSTIC held seam does not close: $seam" }
        val prelevelLoop = prelevel.copyOfRange(start, end)
        val legacyLoop = if (record) legacy.copyOfRange(start, end) else FloatArray(0)
        val internalLoop = if (record) raw.copyOfRange(start * oversample, end * oversample) else FloatArray(0)
        val frequencies = trace.frequencies.copyOf()
        val finished = System.nanoTime()
        return CausticHeldProbe(note, Snip(loop, 1, Dsp.RATE), previous, following, raw, seam, continuation,
            trace.diagnostics, prelevelLoop, legacyLoop, internalLoop, frequencies,
            finished - finishStarted, finished - started, offset, Dsp.RATE * oversample)
    }

    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        var error = 0.0
        var power = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i].toDouble()
            error += d * d
            power += a[i].toDouble() * a[i]
        }
        return sqrt(error / power.coerceAtLeast(1e-30))
    }
}
