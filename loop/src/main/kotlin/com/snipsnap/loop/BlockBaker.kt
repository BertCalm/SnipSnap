package com.snipsnap.loop

import com.snipsnap.audio.Chopper
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns any block into exactly one interval of audio.
 *
 * This is the whole reason one engine can play both kinds of block. After
 * baking, a loop file and a pattern are indistinguishable: a stereo buffer of
 * [Session.intervalFrames] frames at the session's rate. The audio thread never
 * learns there were two kinds.
 *
 * Baking is off-thread work by definition — it decodes, resamples, allocates
 * and slices. Nothing here belongs in a callback.
 */
object BlockBaker {

    /** How far off the interval a loop can be before it gets sliced instead of trimmed. */
    const val FIT_TOLERANCE = 0.02

    /**
     * On the already-fits path, the returned [Snip] aliases the [SampleSource]'s
     * own `FloatArray` with no copy — a caller must not mutate it in place.
     */
    fun bake(block: Block, session: Session, source: SampleSource): Snip = when (block) {
        is LoopBlock -> bakeLoop(block, session, source)
        is PatternBlock -> silence(session)
    }

    private fun bakeLoop(block: LoopBlock, session: Session, source: SampleSource): Snip {
        val raw = source.loop(block.sampleFile) ?: return silence(session)
        val atRate = Resampler.resample(raw, session.sampleRate)
        val stereo = toStereo(atRate)
        val target = session.intervalFrames
        if (stereo.frameCount == 0) return silence(session)

        val drift = abs(stereo.frameCount - target).toDouble() / target
        return if (drift <= FIT_TOLERANCE) conform(stereo, target) else retrigger(stereo, target)
    }

    /**
     * Fit by slicing at transients and re-placing the slices on the new grid.
     *
     * The slices themselves are untouched — same pitch, same length, same decay.
     * Only their positions scale. Stretched out, the gaps between hits grow;
     * squeezed, hits overlap and sum. That is what Recycle did, what an MPC's
     * chop-and-program does, and what SnipSnap's own Chopper was already built
     * for.
     */
    private fun retrigger(snip: Snip, targetFrames: Int): Snip {
        val slices = Chopper.byTransients(
            snip,
            maxSlices = 32,
            cleanup = Chopper.SLICE_CLEANUP,
        )
        // Sustained material has no onsets to cut on. Trimming is a worse fit
        // but an honest one; silence would be a bug.
        if (slices.isEmpty()) return conform(snip, targetFrames)

        // Onset detection cannot fire on an attack inside the first analysis window —
        // the rectified energy derivative has no earlier window to rise from. A loop
        // that starts on the downbeat therefore yields its first onset well after
        // frame 0, and the head would be dropped. Recycle/REX always begin with a
        // slice at the loop start; do the same.
        val head = slices.first().sourceFrame
        val placed = if (head > 0) {
            listOf(Chopper.slice(snip, 0, head, onset = null, cleanup = Chopper.SLICE_CLEANUP)) + slices
        } else {
            slices
        }

        val out = FloatArray(targetFrames * 2)
        val scale = targetFrames.toDouble() / snip.frameCount
        for (slice in placed) {
            val at = (slice.sourceFrame * scale).roundToInt()
            if (at >= targetFrames) continue
            val room = (targetFrames - at) * 2
            val n = minOf(slice.snip.samples.size, room)
            val base = at * 2
            for (i in 0 until n) out[base + i] += slice.snip.samples[i]
        }
        return Snip(out, 2, snip.sampleRate)
    }

    /** Trim or zero-pad to exactly [targetFrames]. */
    private fun conform(snip: Snip, targetFrames: Int): Snip {
        if (snip.frameCount == targetFrames) return snip
        val out = FloatArray(targetFrames * snip.channels)
        System.arraycopy(snip.samples, 0, out, 0, minOf(snip.samples.size, out.size))
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /** Everything downstream is stereo, so the mixer never branches on channel count. */
    internal fun toStereo(snip: Snip): Snip {
        if (snip.channels == 2) return snip
        val out = FloatArray(snip.frameCount * 2)
        for (f in 0 until snip.frameCount) {
            val v = snip.samples[f]
            out[f * 2] = v
            out[f * 2 + 1] = v
        }
        return Snip(out, 2, snip.sampleRate)
    }

    internal fun silence(session: Session): Snip =
        Snip(FloatArray(session.intervalFrames * 2), 2, session.sampleRate)
}
