package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.security.MessageDigest

/**
 * One completed pre-stage and one completed whole-board render, never working DSP state.
 * Content hashes identify owned input snapshots, not mutable Snip identities. Each result is
 * an owned copy. Clock/pattern edits reuse the pre-stage; identical HEAR/KEEP calls reuse both.
 * Cold core renders still compute the approved 8x process and are not claimed to take tens of ms.
 * Work and copies happen outside the cache locks. Interruption is checked at call/stage boundaries.
 */
internal object ObliterateBoardCache {
    const val MAX_PRE_SAMPLES = (ObliterateShimmer.MAX_INPUT_SECONDS * ObliterateShimmer.RATE + ObliterateShimmer.TAIL_FRAMES) * 2
    const val MAX_OUTPUT_SAMPLES = MAX_PRE_SAMPLES + ObliterateCore.TAIL_FRAMES * 2
    private data class InputKey(val digest: List<Byte>, val channels: Int, val rate: Int)
    private data class PreKey(val input: InputKey, val settings: ObliterateShimmer.Settings, val model: String = ObliterateShimmer.MODEL)
    private data class FullKey(val input: InputKey, val settings: Obliterate.Settings,
        val boardModel: String = ObliterateBoard.MODEL, val coreModel: String = ObliterateCore.MODEL,
        val shimmerModel: String = ObliterateShimmer.MODEL)
    private val pre = Completed<PreKey>(MAX_PRE_SAMPLES)
    private val full = Completed<FullKey>(MAX_OUTPUT_SAMPLES)
    internal data class Stats(val preHits: Long, val preMisses: Long, val fullHits: Long, val fullMisses: Long, val retainedSamples: Int)

    fun render(input: Snip, settings: Obliterate.Settings): Snip {
        checkCancellation()
        ObliterateBoard.requireInput(input)
        val source = input.copy(samples = input.samples.copyOf())
        require(source.samples.all { it.isFinite() && it in -1f..1f }) { "OBLITERATE needs finite PCM in -1..1" }
        val key = InputKey(digest(source.samples), source.channels, source.sampleRate)
        checkCancellation()
        return full.render(FullKey(key, settings)) {
            val halo = pre.render(PreKey(key, settings.shimmer)) {
                ObliterateShimmer.render(source, settings.shimmer).audio
            }
            checkCancellation()
            val processed = ObliterateCore.render(halo, settings.core).audio
            val engagement = ObliterateBoard.engagement(settings.core.amount)
            if (engagement == 1.0) processed else {
                // The approved core filters even a near-zero non-bypass input. Fade the
                // whole result, rather than only its halo, to approach exact dry smoothly.
                val samples = FloatArray(processed.samples.size) { i ->
                    val frame = i / 2
                    val dry = if (frame < source.frameCount) source.samples[frame * source.channels + minOf(i % 2, source.channels - 1)].toDouble() else 0.0
                    ((1.0 - engagement) * dry + engagement * processed.samples[i].toDouble()).toFloat()
                }
                Snip(samples, processed.channels, processed.sampleRate)
            }
        }
    }

    internal fun clear() { pre.clear(); full.clear() }
    internal fun stats(): Stats {
        val p = pre.stats(); val f = full.stats()
        return Stats(p.first, p.second, f.first, f.second, pre.retained() + full.retained())
    }
    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("OBLITERATE board render interrupted")
    }
    private fun digest(samples: FloatArray): List<Byte> {
        val hash = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(8192)
        var used = 0
        for (x in samples) {
            val bits = x.toRawBits()
            for (shift in 0..3) block[used++] = (bits ushr (shift * 8)).toByte()
            if (used == block.size) { hash.update(block); used = 0; checkCancellation() }
        }
        if (used > 0) hash.update(block, 0, used)
        return hash.digest().toList()
    }
    internal class Completed<K>(private val limit: Int) {
        private data class Entry<K>(val key: K, val audio: Snip)
        private val lock = Any()
        private var entry: Entry<K>? = null
        private var generation = 0L
        private var hits = 0L
        private var misses = 0L
        fun render(key: K, renderer: () -> Snip): Snip {
            val (found, epoch) = synchronized(lock) {
                checkCancellation()
                val found = entry?.takeIf { it.key == key }
                if (found == null) misses++ else hits++
                found to generation
            }
            found?.let { return owned(it.audio) }
            val rendered = renderer()
            checkCancellation()
            check(rendered.samples.size <= limit) { "OBLITERATE cache exceeds its finite budget" }
            val privateAudio = owned(rendered)
            val result = owned(privateAudio)
            synchronized(lock) {
                checkCancellation()
                if (epoch == generation) entry = Entry(key, privateAudio)
            }
            // The return copy is ready before publication. An observed interruption never
            // publishes a candidate and then throws while copying its caller's result.
            return result
        }
        private fun owned(snip: Snip): Snip {
            checkCancellation()
            val copy = snip.copy(samples = snip.samples.copyOf())
            checkCancellation()
            return copy
        }
        fun clear() = synchronized(lock) { entry = null; generation++; hits = 0; misses = 0 }
        fun stats(): Pair<Long, Long> = synchronized(lock) { hits to misses }
        fun retained(): Int = synchronized(lock) { entry?.audio?.samples?.size ?: 0 }
    }
}
