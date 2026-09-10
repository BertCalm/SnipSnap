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
    private val loops: Map<LoopKey, Snip>,
) {

    private data class PadKey(val kit: String, val slot: Int)

    /** A snip fitted to one period — a different tempo or ring size is a different buffer. */
    private data class LoopKey(val sampleFile: String, val periodFrames: Long)

    /** A pad's stereo audio, or null when the kit or slot is missing (the hit is skipped, not thrown). */
    fun pad(kit: String, slot: Int): Snip? = pads[PadKey(kit, slot)]

    /** The snip on [orbit], fitted to its period in [set], or null when the file is missing. */
    fun loop(set: OrbitSet, orbit: Orbit): Snip? {
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
            val loops = HashMap<LoopKey, Snip>()

            for (orbit in set.orbits) {
                when (val content = orbit.content) {
                    is PatternOrbit -> for (hit in content.hits) {
                        val key = PadKey(content.kit, hit.slot)
                        if (key in pads) continue
                        val kept = reuse?.pads?.get(key)
                        val snip = kept ?: source.pad(content.kit, hit.slot)?.let { stereoAt(it, set.sampleRate) }
                        if (snip != null) pads[key] = snip
                    }
                    is SnipOrbit -> {
                        val period = OrbitClock.periodFrames(set, orbit)
                        val key = LoopKey(content.sampleFile, period)
                        if (key in loops) continue
                        val kept = reuse?.loops?.get(key)
                        val snip = kept ?: source.loop(content.sampleFile)?.let {
                            BlockBaker.fitLoop(stereoAt(it, set.sampleRate), period.toInt())
                        }
                        if (snip != null) loops[key] = snip
                    }
                }
            }
            return OrbitBank(set.sampleRate, pads, loops)
        }

        private fun stereoAt(snip: Snip, rate: Int): Snip =
            BlockBaker.toStereo(Resampler.resample(snip, rate))
    }
}
