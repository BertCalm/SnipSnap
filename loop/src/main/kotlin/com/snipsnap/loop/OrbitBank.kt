package com.snipsnap.loop

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip

/**
 * The audio a set of rings needs, decoded, resampled and fitted ahead of time.
 *
 * Preparing is off-thread work by definition — it reads files, resamples,
 * and for a snip ring slices at the transients — so it happens here, once,
 * and the engine only ever reads from the result. A bank is immutable; an
 * edit prepares a new one (reusing what the previous bank already had) and
 * the engine swaps at a block boundary, the same "swap atomically, never
 * mid-buffer" rule the loop grid's [Residency] lives by.
 *
 * Every buffer is stereo at the set's sample rate, so the engine never
 * branches on channel count or converts in the hot path.
 */
class OrbitBank private constructor(
    val sampleRate: Int,
    private val pads: Map<PadKey, Snip>,
    private val groups: Map<PadKey, Int>,
    private val loops: Map<LoopKey, FittedLoop>,
) {

    private data class PadKey(val kit: String, val slot: Int)

    /** A snip fitted to one period — a different tempo or ring size is a different buffer. */
    private data class LoopKey(val sampleFile: String, val periodFrames: Long)

    /** A pad's stereo audio, or null when the kit or slot is missing (the hit is skipped, not thrown). */
    fun pad(kit: String, slot: Int): Snip? = pads[PadKey(kit, slot)]

    /**
     * A pad's choke group, 0 for none — read fresh on every [prepare] even
     * when the audio is reused, so retuning a kit's choke reaches the next
     * block rather than waiting for the sample to be evicted.
     */
    fun muteGroup(kit: String, slot: Int): Int = groups[PadKey(kit, slot)] ?: 0

    /** The snip on [orbit], fitted to its period in [set], or null when the file is missing. */
    fun loop(set: OrbitSet, orbit: Orbit): Snip? = fitted(set, orbit)?.snip

    /** What the fit did to the snip on [orbit] — as-is, trimmed, padded or sliced — or null when the file is missing. */
    fun fit(set: OrbitSet, orbit: Orbit): FitReport? = fitted(set, orbit)?.report

    /**
     * The snip on [orbit] as [buckets] peaks round the ring — the loudest
     * sample in each equal slice of its period, both channels — so the
     * ring can wear its own waveform. Null when the file is missing.
     */
    fun peaks(set: OrbitSet, orbit: Orbit, buckets: Int): FloatArray? = loop(set, orbit)?.let { peaksOf(it, buckets) }

    private fun fitted(set: OrbitSet, orbit: Orbit): FittedLoop? {
        val content = orbit.content as? SnipOrbit ?: return null
        return loops[LoopKey(content.sampleFile, OrbitClock.periodFrames(set, orbit))]
    }

    /** How many distinct buffers are held — for tests and the memory readout. */
    val residentCount: Int get() = pads.size + loops.size

    companion object {

        /**
         * Prepare every buffer [set] needs from [source], reusing any that
         * [previous] already holds at the same rate. A missing file is a
         * missing entry, never an exception: one deleted snip should not
         * silence the whole set.
         */
        fun prepare(set: OrbitSet, source: SampleSource, previous: OrbitBank? = null): OrbitBank {
            val reuse = previous?.takeIf { it.sampleRate == set.sampleRate }
            val pads = HashMap<PadKey, Snip>()
            val groups = HashMap<PadKey, Int>()
            val loops = HashMap<LoopKey, FittedLoop>()

            for (orbit in set.orbits) {
                when (val content = orbit.content) {
                    is PatternOrbit -> for (hit in content.hits) {
                        val key = PadKey(content.kit, hit.slot)
                        if (key in pads) continue
                        val kept = reuse?.pads?.get(key)
                        val snip = kept ?: source.pad(content.kit, hit.slot)?.let { stereoAt(it, set.sampleRate) }
                        if (snip != null) {
                            pads[key] = snip
                            val group = source.muteGroup(content.kit, hit.slot)
                            if (group != 0) groups[key] = group
                        }
                    }
                    is SnipOrbit -> {
                        val period = OrbitClock.periodFrames(set, orbit)
                        val key = LoopKey(content.sampleFile, period)
                        if (key in loops) continue
                        val kept = reuse?.loops?.get(key)
                        val fitted = kept ?: source.loop(content.sampleFile)?.let {
                            BlockBaker.fitLoopReported(stereoAt(it, set.sampleRate), period.toInt())
                        }
                        if (fitted != null) loops[key] = fitted
                    }
                }
            }
            return OrbitBank(set.sampleRate, pads, groups, loops)
        }

        /** [buckets] peaks over [snip]: the loudest absolute sample in each equal run of frames, any channel. */
        fun peaksOf(snip: Snip, buckets: Int): FloatArray {
            require(buckets > 0) { "buckets must be positive: $buckets" }
            val out = FloatArray(buckets)
            val frames = snip.frameCount
            if (frames == 0) return out
            val channels = snip.channels
            for (b in 0 until buckets) {
                val from = (b.toLong() * frames / buckets).toInt()
                val until = ((b + 1).toLong() * frames / buckets).toInt().coerceAtLeast(from + 1).coerceAtMost(frames)
                var peak = 0f
                for (i in from * channels until until * channels) {
                    val a = kotlin.math.abs(snip.samples[i])
                    if (a > peak) peak = a
                }
                out[b] = peak
            }
            return out
        }

        private fun stereoAt(snip: Snip, rate: Int): Snip =
            BlockBaker.toStereo(Resampler.resample(snip, rate))
    }
}
