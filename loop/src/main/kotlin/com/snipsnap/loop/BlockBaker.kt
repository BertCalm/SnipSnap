package com.snipsnap.loop

import com.snipsnap.audio.AutoPlace
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
     * [block] on its own, as if nothing played before or after it: a
     * pattern's tails fade out at the interval's end instead of carrying.
     * The grid bakes through the [Neighbourhood] overload; this one is for
     * a block heard alone.
     *
     * On the already-fits path, the returned [Snip] aliases the [SampleSource]'s
     * own `FloatArray` with no copy — a caller must not mutate it in place.
     */
    fun bake(
        block: Block,
        session: Session,
        source: SampleSource,
        /** Asked by a drone's render while it runs; see [SampleSource.drone]. Other blocks never ask. */
        cancelled: () -> Boolean = { false },
    ): Snip = bake(Neighbourhood(block), session, source, cancelled)

    /** One interval of a track, from its [Neighbourhood] ([Arrangement.neighbourhoodAt]). */
    fun bake(
        at: Neighbourhood,
        session: Session,
        source: SampleSource,
        cancelled: () -> Boolean = { false },
    ): Snip = when (val block = at.block) {
        is LoopBlock -> bakeLoop(block, session, source)
        is PatternBlock -> bakePattern(at.before, block, at.after, session, source)
        is SilenceBlock -> silence(session)
        is DroneBlock -> bakeDrone(block, session, source, cancelled)
    }

    /**
     * One slice of a drone's whole render. No fade at either end: the
     * slices are contiguous pieces of one loop that closes on whole cycles,
     * and a fade would *make* the seam it exists to hide. A render that is
     * missing or the wrong length is silence, not a throw, for the reason a
     * missing file is.
     *
     * Sliced before it is made stereo: a drone can be tens of seconds, and
     * widening the whole of it to cut out one interval would allocate the
     * entire drone again, twice over, for every slice.
     */
    private fun bakeDrone(block: DroneBlock, session: Session, source: SampleSource, cancelled: () -> Boolean): Snip {
        val target = session.intervalFrames
        val whole = source.drone(block.recipe, block.rootMidi, block.of.toLong() * target, session.sampleRate, cancelled)
            ?: return silence(session)
        if (whole.sampleRate != session.sampleRate || whole.frameCount.toLong() != block.of.toLong() * target ||
            whole.channels !in 1..2
        ) {
            return silence(session)
        }
        val ch = whole.channels
        val from = block.slice * target * ch
        val piece = Snip(whole.samples.copyOfRange(from, from + target * ch), ch, session.sampleRate)
        return toStereo(piece)
    }

    private fun bakeLoop(block: LoopBlock, session: Session, source: SampleSource): Snip {
        val raw = source.loop(block.sampleFile) ?: return silence(session)
        val atRate = Resampler.resample(raw, session.sampleRate)
        val stereo = toStereo(atRate)
        val target = session.intervalFrames
        if (stereo.frameCount == 0) return silence(session)

        return fitLoop(stereo, target)
    }

    /**
     * Fit a stereo loop to exactly [targetFrames]: trim or pad when it is
     * within [FIT_TOLERANCE] of the target, slice at the hits and re-place
     * them on the new grid when it is not. The one fit rule for a loop on
     * the grid ([bakeLoop]) and a snip on a ring ([OrbitBank]), so the two
     * can never disagree about what a wrapped loop sounds like.
     */
    internal fun fitLoop(stereo: Snip, targetFrames: Int): Snip = fitLoopReported(stereo, targetFrames).snip

    /** [fitLoop], and the [FitReport] of what it did — the ring panel's line about a snip. */
    internal fun fitLoopReported(stereo: Snip, targetFrames: Int): FittedLoop {
        require(stereo.channels == 2) { "fitLoop wants stereo, got ${stereo.channels} channel(s)" }
        require(targetFrames > 0) { "targetFrames must be positive: $targetFrames" }
        val source = stereo.frameCount
        if (source == 0) {
            return FittedLoop(Snip(FloatArray(targetFrames * 2), 2, stereo.sampleRate), FitReport(LoopFit.PADDED, 0, targetFrames))
        }
        val drift = abs(source - targetFrames).toDouble() / targetFrames
        if (drift > FIT_TOLERANCE) {
            // Sustained material has no onsets to cut on. Trimming is a worse
            // fit but an honest one; silence would be a bug — and the report
            // says what actually happened, not what was tried.
            retrigger(stereo, targetFrames)?.let { return it }
        }
        val kind = when {
            source == targetFrames -> LoopFit.AS_IS
            source > targetFrames -> LoopFit.TRIMMED
            else -> LoopFit.PADDED
        }
        return FittedLoop(conform(stereo, targetFrames), FitReport(kind, source, targetFrames))
    }

    /**
     * Render a pattern to one interval.
     *
     * Groove.kt does something like this for a generated pattern, but it mixes
     * mono and picks its own hits. This takes authored steps, keeps stereo, and
     * honours velocity and micro-offsets — a pattern block has to reproduce
     * exactly what the user wrote, every cycle.
     *
     * The interval is rendered from three patterns laid on one timeline:
     * [before] one interval early, [block] at zero, [after] one interval
     * late. Only this interval's frames are written, but every hit takes
     * part, which is what makes the boundary disappear
     * (docs/plans/2026-10-30-song-making.md, phase 3):
     *
     * - A hit late in [before] rings on into this interval, so the bar line
     *   no longer cuts it. Before this, every hit that ran past the
     *   interval's end was cut off there, and on a loop that was a click
     *   every cycle (an open hat on the last 16th: a 0.27 step).
     * - A hit [after] nudges early on its downbeat starts at this interval's
     *   end, where it was written, instead of losing its head.
     * - A kit's choke reaches across the boundary: a closed hat on [block]'s
     *   downbeat stops [before]'s open hat, within one kit, as `KitPreview`
     *   and `OrbitEngine` already do.
     *
     * Neighbouring intervals compute every shared voice the same way, so a
     * voice split across a boundary joins without a seam. A voice carries
     * one interval past its own: anything still sounding then fades out over
     * [TAIL_FADE_MS], the way a voice with no next pattern to carry it fades
     * at its own interval's end.
     */
    private fun bakePattern(
        before: PatternBlock?,
        block: PatternBlock,
        after: PatternBlock?,
        session: Session,
        source: SampleSource,
    ): Snip {
        val target = session.intervalFrames
        val stepFrames = target.toDouble() / session.stepsPerInterval
        val tailFade = (TAIL_FADE_MS * session.sampleRate / 1000f).roundToInt().coerceAtLeast(1)
        val pads = HashMap<Pair<String, Int>, Snip?>()
        val groups = HashMap<String, Map<Int, Int>>()

        class Voice(val kit: String, val group: Int, val samples: FloatArray, val start: Int, val gain: Float) {
            var end = start + samples.size / 2
            var fade = 0
            var choked = false
        }

        val voices = ArrayList<Voice>()
        // [carried]: whether the next interval bakes this pattern as its
        // `before`, so its voices may sound one interval past its own end.
        fun place(pattern: PatternBlock, origin: Int, carried: Boolean) {
            val cap = origin + target + if (carried) target else 0
            for (step in pattern.steps) {
                if (step.step >= session.stepsPerInterval) continue
                val start = origin + (step.step * stepFrames).roundToInt() + step.microOffset
                if (start - origin >= target) continue
                val pad = pads.getOrPut(pattern.kit to step.slot) {
                    source.pad(pattern.kit, step.slot)?.let { toStereo(Resampler.resample(it, session.sampleRate)) }
                } ?: continue
                val group = groups.getOrPut(pattern.kit) { source.muteGroups(pattern.kit) }[step.slot] ?: 0
                val voice = Voice(pattern.kit, group, pad.samples, start, step.velocity)
                if (voice.end > cap) {
                    voice.end = cap
                    voice.fade = minOf(tailFade, cap - start)
                }
                voices += voice
            }
        }
        before?.let { place(it, -target, carried = true) }
        place(block, 0, carried = after != null)
        after?.let { place(it, target, carried = true) }

        // The kit rule, in the order the hits land: a new voice in a group
        // chokes everything of the same kit still ringing in it, over
        // [AutoPlace.CHOKE_FADE] or whatever is left, and a voice already
        // fading out of a choke keeps the ramp it is on. KitPreview's rule.
        voices.sortBy { it.start }
        for ((i, v) in voices.withIndex()) {
            if (v.group == 0) continue
            for (j in 0 until i) {
                val u = voices[j]
                if (u.kit != v.kit || u.group != v.group || u.choked || u.end <= v.start) continue
                val len = minOf(AutoPlace.CHOKE_FADE, u.end - v.start)
                u.end = v.start + len
                u.fade = len
                u.choked = true
            }
        }

        val out = FloatArray(target * 2)
        for (v in voices) {
            val from = maxOf(0, v.start)
            val to = minOf(target, v.end)
            val fadeFrom = v.end - v.fade
            for (f in from until to) {
                val i = (f - v.start) * 2
                val g = if (f >= fadeFrom && v.fade > 0) v.gain * ((v.end - f).toFloat() / v.fade) else v.gain
                out[f * 2] += v.samples[i] * g
                out[f * 2 + 1] += v.samples[i + 1] * g
            }
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
    private fun retrigger(snip: Snip, targetFrames: Int): FittedLoop? {
        val slices = Chopper.byTransients(
            snip,
            maxSlices = 32,
            cleanup = Chopper.SLICE_CLEANUP,
        )
        // Sustained material has no onsets to cut on: nothing to slice, so
        // the caller conforms instead (and reports that, not this).
        if (slices.isEmpty()) return null

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
        val faded = Cleanup.applyFades(Snip(out, 2, snip.sampleRate), fadeInMs = 0f, fadeOutMs = TAIL_FADE_MS)
        return FittedLoop(faded, FitReport(LoopFit.SLICED, snip.frameCount, targetFrames, slices = placed.size))
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
