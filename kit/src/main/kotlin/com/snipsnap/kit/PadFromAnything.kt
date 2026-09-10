package com.snipsnap.kit

import com.snipsnap.audio.Pghi
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Stretch
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * PAD FROM ANYTHING — one hit becomes a pad that holds while you hold it
 * and plays in every note.
 *
 * The chain is three things the shop already had, in order: stretch far
 * (the clear stretch for a pitched source, so its note stays a line; the
 * paulstretch wash for an unpitched one, so a drum becomes weather), cut
 * a seamless loop out of the wash, and hand it to the keygroup writers
 * with loop points and a slow release. The sample is the wash's first
 * [HEAD_SEC] + [LOOP_SEC]: the head is the arrival, the body loops back to
 * the head's end forever, and the seam is baked as a crossfade over the
 * body's last [FADE_SEC] into the material just before the loop start —
 * on a stationary wash that seam is inaudible by construction.
 *
 * Two knobs. DEPTH is how far to stretch (×[DEPTH_MIN]..×[DEPTH_MAX]);
 * it gives where a minute would be outrun or the wash would fall short
 * of a loop, and the depth actually used is reported. BLOOM is how long
 * the arrival ramps in (0..[BLOOM_MAX_SEC]), so a tap swells like a pad.
 *
 * A pitched source is rooted where it sounds; an unpitched one is
 * accepted as a drone at [DRONE_ROOT] rather than refused — a pad does
 * not need a note to be played as one. Deterministic per seed.
 */
object PadFromAnything {

    const val DEPTH_MIN = 8f
    const val DEPTH_MAX = 100f
    const val DEPTH_DEFAULT = 40f

    /** The arrival: what plays before the loop, and the room the seam crossfades from. */
    const val HEAD_SEC = 1f

    /** The seamless body. */
    const val LOOP_SEC = 4f

    /** The crossfade baked at the wrap. */
    const val FADE_SEC = 0.75f

    /** BLOOM 1 ramps the arrival in over this long. */
    const val BLOOM_MAX_SEC = 1f

    /** The longest wash grown, whatever the depth asks. */
    const val MAX_WASH_SEC = 60f

    /** A source under this has nothing to stretch into five seconds. */
    const val MIN_SOURCE_SEC = 0.05f

    /** A source over this cannot slow even ×2 inside the minute. */
    const val MAX_SOURCE_SEC = 30f

    /** Where an unpitched source sits: C3, the pad player's home row. */
    const val DRONE_ROOT = 48

    /** A pad lets go slowly. */
    const val RELEASE = 0.6f

    /** The pad's ceiling. */
    const val PEAK = 0.9f

    data class Spec(
        val depth: Float = DEPTH_DEFAULT,
        /** 0..1 onto 0..[BLOOM_MAX_SEC]. */
        val bloom: Float = 0.3f,
        val seed: Long = 7L,
    ) {
        init {
            require(depth in DEPTH_MIN..DEPTH_MAX) { "depth wants $DEPTH_MIN..$DEPTH_MAX, got $depth" }
            require(bloom in 0f..1f) { "bloom wants 0..1, got $bloom" }
        }
    }

    data class Result(
        val program: KeygroupProgram,
        /** What gets written: the head, then the body with its seam baked. */
        val sample: Snip,
        val loopStartFrame: Long,
        val rootMidi: Int,
        val rootName: String,
        /** True when the source had a confident pitch and the pad is rooted there; false for a drone. */
        val pitched: Boolean,
        val detectedHz: Float?,
        /** The clear stretch (pitched) or the wash (unpitched). */
        val clear: Boolean,
        /** The stretch factor actually used after the minute and the loop had their say. */
        val depthUsed: Float,
        val sampleStem: String,
    )

    /** The depth that fits: never a wash past the minute, never one too short for the head and the loop. */
    fun depthFor(sourceSeconds: Float, asked: Float): Float {
        val need = HEAD_SEC + LOOP_SEC
        var d = asked.coerceAtMost(MAX_WASH_SEC / sourceSeconds)
        d = d.coerceAtLeast(need / sourceSeconds)
        return d.coerceIn(Stretch.MIN_FACTOR, DEPTH_MAX)
    }

    fun build(name: String, source: Snip, spec: Spec = Spec()): Result {
        require(Names.isMpcSafe(name)) { "instrument name isn't MPC-safe: '$name'" }
        require(source.frameCount > 0) { "empty snip" }
        val seconds = source.durationSeconds
        require(seconds >= MIN_SOURCE_SEC) {
            "%.0f ms is too short to stretch into a pad - it wants at least %.0f ms".format(java.util.Locale.ROOT, seconds * 1000f, MIN_SOURCE_SEC * 1000f)
        }
        require(seconds <= MAX_SOURCE_SEC) {
            "%.1f s is too long to slow inside a minute - a pad wants a sound under %.0f s".format(java.util.Locale.ROOT, seconds, MAX_SOURCE_SEC)
        }
        val rate = source.sampleRate

        val est = Pitch.detect(source)
        val pitched = est != null && est.confidence >= OneNote.MIN_CONFIDENCE
        val depth = depthFor(seconds, spec.depth)
        val wash = if (pitched) Pghi.stretch(source, depth, spec.seed) else Stretch.stretch(source, depth, spec.seed)

        val head = (HEAD_SEC * rate).toInt()
        val total = minOf(wash.frameCount, head + (LOOP_SEC * rate).toInt())
        val fade = (FADE_SEC * rate).toInt()
        require(total - head > 2 * fade) { "the wash came up short of a loop (${total - head} frames)" }
        val ch = wash.channels
        val out = wash.samples.copyOfRange(0, total * ch)

        // BLOOM: the arrival ramps in, equal-power, inside the head.
        val bloom = (spec.bloom * BLOOM_MAX_SEC * rate).toInt().coerceAtMost(head)
        for (i in 0 until bloom) {
            val g = sin(PI / 2.0 * (i + 1) / bloom).toFloat()
            for (c in 0 until ch) out[i * ch + c] *= g
        }

        // The seam: the body's last FADE_SEC blends into the wash just before
        // the loop start, so the wrap lands exactly where it continues from.
        for (i in 0 until fade) {
            val t = (i + 1).toFloat() / fade
            for (c in 0 until ch) {
                val at = (total - fade + i) * ch + c
                val from = (head - fade + i) * ch + c
                out[at] = out[at] * (1f - t) + wash.samples[from] * t
            }
        }

        // The pad's ceiling.
        var peak = 0f
        for (v in out) { val a = if (v < 0) -v else v; if (a > peak) peak = a }
        if (peak > 0f) { val k = PEAK / peak; for (i in out.indices) out[i] *= k }
        val sample = Snip(out, ch, rate)

        val rootMidi = if (pitched) Scales.hzToMidi(est!!.hz).roundToInt().coerceIn(0, 127) else DRONE_ROOT
        val rootName = Scales.nameOf(rootMidi)
        val stem = Names.sanitizeStem("${name.filter { !it.isWhitespace() }}_Pad_$rootName")
        val program = KeygroupProgram(
            name,
            listOf(
                Keygroup(
                    lowNote = 0,
                    highNote = 127,
                    rootNote = rootMidi,
                    layers = listOf(
                        VelocityLayer(stem, sample.frameCount.toLong(), velStart = 0, velEnd = 127, loopStartFrame = head.toLong()),
                    ),
                ),
            ),
            volumeRelease = RELEASE,
        )
        return Result(
            program, sample, head.toLong(), rootMidi, rootName, pitched, est?.hz?.takeIf { pitched },
            clear = pitched, depthUsed = depth, sampleStem = stem,
        )
    }

    /** Build and write the pad in the dual-generation layout the instruments ship in. */
    fun export(name: String, source: Snip, destRoot: File, spec: Spec = Spec(), overwrite: Boolean = false): Result {
        val result = build(name, source, spec)
        OneNote.writePackage(name, result.program, mapOf(result.sampleStem to result.sample), destRoot, overwrite)
        return result
    }
}
