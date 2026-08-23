package com.snipsnap.loop

import com.snipsnap.audio.Chopper
import com.snipsnap.audio.Cleanup
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
     * Click guard applied to the tail of a baked interval buffer whenever audio
     * was cut mid-waveform (a squeezed [retrigger], or a [conform] trim).
     *
     * These buffers loop every interval, so a non-zero final sample is a click
     * on every cycle, not a one-off. 3 ms matches [Chopper.SLICE_CLEANUP]'s own
     * `fadeOutMs` — the same "cut mid-waveform" situation a slice boundary is
     * already reasoned about in this codebase, so this reuses that constant
     * rather than inventing a new one. It is 3x the 1 ms head fade on purpose:
     * the head fade is kept short to preserve attack punch on the downbeat,
     * but the tail here has no punch to protect — it exists purely to mask a
     * truncation, so there is no reason to keep it as short as the head.
     */
    const val TAIL_FADE_MS = 3f

    /**
     * On the already-fits path, the returned [Snip] aliases the [SampleSource]'s
     * own `FloatArray` with no copy — a caller must not mutate it in place.
     */
    fun bake(block: Block, session: Session, source: SampleSource): Snip = when (block) {
        is LoopBlock -> bakeLoop(block, session, source)
        is PatternBlock -> bakePattern(block, session, source)
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
     * Render a pattern to one interval.
     *
     * Groove.kt does something like this for a generated pattern, but it mixes
     * mono and picks its own hits. This takes authored steps, keeps stereo, and
     * honours velocity and micro-offsets — a pattern block has to reproduce
     * exactly what the user wrote, every cycle.
     */
    private fun bakePattern(block: PatternBlock, session: Session, source: SampleSource): Snip {
        val target = session.intervalFrames
        val out = FloatArray(target * 2)
        val stepFrames = target.toDouble() / session.stepsPerInterval

        for (step in block.steps) {
            if (step.step >= session.stepsPerInterval) continue
            val pad = source.pad(block.kit, step.slot) ?: continue
            val stereo = toStereo(Resampler.resample(pad, session.sampleRate))

            val at = (step.step * stepFrames).roundToInt() + step.microOffset
            if (at >= target) continue
            val from = if (at < 0) -at else 0
            val base = (at + from) * 2
            val room = (target - at - from) * 2
            val n = minOf(stereo.samples.size - from * 2, room)
            if (n <= 0) continue

            for (i in 0 until n) out[base + i] += stereo.samples[from * 2 + i] * step.velocity
        }
        return Snip(out, 2, session.sampleRate)
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
        // Slice positions scale but slice lengths don't: for a squeezed fit
        // (scale < 1) the last slice's placed length is guaranteed to overhang
        // targetFrames, so `room` above hard-truncates it mid-waveform — the
        // buffer's last sample lands on live audio, not silence. On a stretched
        // fit there is usually no overhang and this fades trailing silence,
        // which is a no-op. Always applying it is what makes it safe either way.
        return Cleanup.applyFades(Snip(out, 2, snip.sampleRate), fadeInMs = 0f, fadeOutMs = TAIL_FADE_MS)
    }

    /** Trim or zero-pad to exactly [targetFrames]. */
    private fun conform(snip: Snip, targetFrames: Int): Snip {
        if (snip.frameCount == targetFrames) return snip
        val out = FloatArray(targetFrames * snip.channels)
        System.arraycopy(snip.samples, 0, out, 0, minOf(snip.samples.size, out.size))
        val conformed = Snip(out, snip.channels, snip.sampleRate)
        // Zero-padding (source shorter than target) needs no fade: the padding
        // is already silence. Trimming (source longer than target) cuts the
        // source mid-waveform, which is the same click hazard as retrigger's
        // overhang, on the same looping buffer.
        return if (snip.frameCount > targetFrames) {
            Cleanup.applyFades(conformed, fadeInMs = 0f, fadeOutMs = TAIL_FADE_MS)
        } else {
            conformed
        }
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
