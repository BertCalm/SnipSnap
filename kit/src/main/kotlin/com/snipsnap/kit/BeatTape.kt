package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.mpc3.Mpc3Clip
import java.io.File
import kotlin.math.floor

/**
 * SIDE A — the output stops being kits and becomes a finished artifact.
 * N kits play in sequence, each running its own patterns for a few bars
 * (rotating through its stored grooves), chained with the two transitions
 * every beat tape knows: the **tape stop** (a repitch ramp to zero — we
 * own that math) and the **pull-up** (the spinback: the last moments
 * rewound fast, pitch rising as they go). One continuous render, sample-
 * accurate track starts, deterministic end to end.
 */
object BeatTape {

    const val RATE = KitPreview.RATE
    const val DEFAULT_BARS = 8

    /** The repitch ramp to zero, seconds of the outgoing tail. */
    const val STOP_SEC = 0.8f

    /** The spinback's length, seconds appended between tracks. */
    const val SPIN_SEC = 0.6f

    data class Track(
        val name: String,
        /** Where this kit starts in the tape, exact to the frame. */
        val startFrame: Int,
        /** This kit's segment length in frames, transition included. */
        val lengthFrames: Int,
        val bars: Int,
        val bpm: Float,
        /** "tape stop", "pull-up", or null on the closing track. */
        val transition: String?,
    )

    data class Tape(val audio: Snip, val tracks: List<Track>)

    fun render(kitDirs: List<File>, barsPerKit: Int = DEFAULT_BARS): Tape {
        require(kitDirs.isNotEmpty()) { "a beat tape needs at least one kit" }
        require(barsPerKit in 1..64) { "bars per kit is 1..64, got $barsPerKit" }

        val segments = mutableListOf<Snip>()
        val tracks = mutableListOf<Track>()
        var cursor = 0
        kitDirs.forEachIndexed { index, dir ->
            val kit = KitStore.load(dir)
            val clips = GrooveStore.load(dir).ifEmpty { listOf(KitPreview.defaultPattern(kit)) }
            val arranged = arrange(clips, barsPerKit, "${kit.name} Side A")
            var segment = KitPreview.render(kit, dir, clip = arranged)
            val transition = when {
                index == kitDirs.lastIndex -> null // the last track rings out
                index % 2 == 0 -> "tape stop"
                else -> "pull-up"
            }
            segment = when (transition) {
                "tape stop" -> tapeStop(segment, STOP_SEC)
                "pull-up" -> append(segment, pullUp(segment, SPIN_SEC))
                else -> segment
            }
            segments += segment
            tracks += Track(
                name = kit.name,
                startFrame = cursor,
                lengthFrames = segment.frameCount,
                bars = arranged.bars,
                bpm = (kit.tempoBpm ?: KitPreview.DEFAULT_BPM).coerceIn(KitPreview.MIN_BPM, KitPreview.MAX_BPM),
                transition = transition,
            )
            cursor += segment.frameCount
        }

        val out = FloatArray(cursor * 2)
        for ((i, seg) in segments.withIndex()) {
            System.arraycopy(seg.samples, 0, out, tracks[i].startFrame * 2, seg.samples.size)
        }
        return Tape(Snip(out, 2, RATE), tracks)
    }

    /**
     * Pattern rotation: the kit's clips laid bar after bar until [bars]
     * are filled — variation flips exactly where the hardware's sequence
     * switcher would put them.
     */
    internal fun arrange(clips: List<Mpc3Clip>, bars: Int, name: String): Mpc3Clip {
        require(clips.isNotEmpty()) { "no clips to arrange" }
        val notes = mutableListOf<com.snipsnap.mpc3.Mpc3Note>()
        var bar = 0
        var i = 0
        while (bar < bars) {
            val c = clips[i % clips.size]
            val take = minOf(c.bars, bars - bar)
            val offset = bar * Mpc3Clip.PULSES_PER_BAR
            notes += c.notes
                .filter { it.timePulses < take * Mpc3Clip.PULSES_PER_BAR }
                .map { it.copy(timePulses = it.timePulses + offset) }
            bar += take
            i++
        }
        return Mpc3Clip(name, bars, notes)
    }

    /**
     * The tape stop: the last [seconds] re-read at a speed ramping 1 → 0
     * (eased — the reel drags before it dies), gain riding the speed down
     * to silence. In place, length unchanged: the next track starts where
     * this one *would* have ended.
     */
    internal fun tapeStop(snip: Snip, seconds: Float = STOP_SEC): Snip {
        val n = minOf((seconds * snip.sampleRate).toInt(), snip.frameCount)
        if (n < 8) return snip
        val start = snip.frameCount - n
        val out = snip.samples.copyOf()
        var pos = start.toDouble()
        for (i in 0 until n) {
            val t = i / n.toDouble()
            val speed = (1.0 - t) * (1.0 - t)
            val gain = (1.0 - t).toFloat()
            readFrame(snip, pos, out, (start + i) * snip.channels, gain)
            pos += speed
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /**
     * The pull-up: [seconds] of spinback built from the segment's own
     * tail, read backwards at a speed ramping 1 → 3 with the gain falling
     * away — the rewind you hear before the next beat drops.
     */
    internal fun pullUp(snip: Snip, seconds: Float = SPIN_SEC): Snip {
        val n = (seconds * snip.sampleRate).toInt()
        if (n < 8 || snip.frameCount < 8) return Snip(FloatArray(0), snip.channels, snip.sampleRate)
        val out = FloatArray(n * snip.channels)
        var pos = (snip.frameCount - 1).toDouble()
        for (i in 0 until n) {
            val t = i / n.toDouble()
            val speed = 1.0 + 2.0 * t
            val gain = (0.8 * (1.0 - 0.6 * t)).toFloat()
            if (pos <= 0.0) break
            readFrame(snip, pos, out, i * snip.channels, gain)
            pos -= speed
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    private fun readFrame(src: Snip, pos: Double, out: FloatArray, outIndex: Int, gain: Float) {
        val i0 = floor(pos).toInt().coerceIn(0, src.frameCount - 1)
        val i1 = (i0 + 1).coerceAtMost(src.frameCount - 1)
        val frac = (pos - i0).toFloat()
        for (c in 0 until src.channels) {
            val a = src.samples[i0 * src.channels + c]
            val b = src.samples[i1 * src.channels + c]
            out[outIndex + c] = (a + (b - a) * frac) * gain
        }
    }

    private fun append(a: Snip, b: Snip): Snip {
        if (b.frameCount == 0) return a
        require(a.channels == b.channels && a.sampleRate == b.sampleRate)
        return Snip(a.samples + b.samples, a.channels, a.sampleRate)
    }
}
