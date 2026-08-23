package com.snipsnap.loop

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip

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
        return conform(stereo, session.intervalFrames)
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
