package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.kit.AnswerStore
import com.snipsnap.kit.BeatTape
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.GrooveVariations
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPreview
import com.snipsnap.mpc3.Mpc3Clip
import java.io.File

/**
 * The Arranger (KK1): songs, not loops. The kit already owns every
 * ingredient — the captured groove, its tight/half/sparse variations,
 * the fill, the ghost grammar — and this lays them into a structure a
 * beat tape would recognize:
 *
 * ```
 * intro (sparse) → theme (captured) → variation (ghosted or tight) →
 * the turn (fill) → reprise (captured) → outro (half)
 * ```
 *
 * Every section's clip is one of the kit's own variations, derived
 * fresh from the stored base groove so the plan never depends on what
 * happened to be saved. Seeded and deterministic: the same (kit, seed)
 * always plans the same song. Honest refusals and skips: no groove
 * refuses outright; a kit with nothing to roll on skips the turn; no
 * snare to whisper on means the variation falls back to tight.
 */
object Arranger {

    /**
     * One section: a variation clip played [repeats] times through, plus
     * [reason] — the rule that picked this clip, stated in words, so a
     * screen can show its work instead of asking the player to trust it.
     */
    data class Section(val name: String, val clip: Mpc3Clip, val repeats: Int, val reason: String) {
        init {
            require(repeats >= 1) { "a section plays at least once" }
        }

        val bars: Int get() = clip.bars * repeats
    }

    data class Arrangement(val name: String, val seed: Int, val sections: List<Section>) {
        val totalBars: Int get() = sections.sumOf { it.bars }
    }

    /** Section target lengths in bars — the classic beat-tape proportions. */
    private const val INTRO_BARS = 2
    private const val BODY_BARS = 4
    private const val OUTRO_BARS = 2

    /**
     * How much louder the base groove's loudest hit has to be than its
     * median-kept one (the same median [GrooveVariations.sparse] already
     * computes) before the variation section prefers the ghosted grammar
     * over a plain tight re-quantize. A groove this dynamic has room for a
     * whisper between its hits; a flatter one would just bury the ghosts.
     */
    private const val VARIATION_GHOST_DYNAMIC_RANGE = 2f

    fun arrange(kit: Kit, kitDir: File, seed: Int = 0): Arrangement {
        val base = GrooveStore.load(kitDir).firstOrNull()
            ?: throw IllegalArgumentException(
                "no groove to arrange - chop with --groove, or import a .mid",
            )
        val std = GrooveVariations.standard(base)
        val captured = std[0]
        val tight = std[1]
        val half = std[2]
        val sparse = std[3]

        // A measured number, not a coin flip: the base groove's own
        // dynamic range decides whether the variation has room for a
        // whisper between its hits.
        val velocities = base.notes.map { it.velocity }.sorted()
        val median = velocities[velocities.size / 2]
        val loudest = velocities.last()
        val dynamicRange = if (median > 0f) loudest / median else 0f
        val preferGhosts = dynamicRange >= VARIATION_GHOST_DYNAMIC_RANGE
        val ghosted = try {
            GrooveVariations.ghosted(base, kit, seed = seed + 1)
        } catch (e: IllegalArgumentException) {
            null
        }
        val variation = if (preferGhosts && ghosted != null) ghosted else tight
        val variationReason = when {
            preferGhosts && ghosted != null ->
                "ghosted — dynamic range %.1fx ≥ %.0fx threshold".format(java.util.Locale.ROOT, dynamicRange, VARIATION_GHOST_DYNAMIC_RANGE)
            preferGhosts ->
                "tight — dynamic range %.1fx ≥ %.0fx threshold, but no snare or clap to whisper on"
                    .format(java.util.Locale.ROOT, dynamicRange, VARIATION_GHOST_DYNAMIC_RANGE)
            else ->
                "tight — dynamic range %.1fx < %.0fx threshold".format(java.util.Locale.ROOT, dynamicRange, VARIATION_GHOST_DYNAMIC_RANGE)
        }
        val turn = try {
            GrooveVariations.fill(base, kit, seed = seed + 2)
        } catch (e: IllegalArgumentException) {
            null
        }

        fun repeats(clip: Mpc3Clip, target: Int) = maxOf(1, target / clip.bars)
        val sections = buildList {
            add(Section("intro", sparse, repeats(sparse, INTRO_BARS), "sparse — only the hits at or above the base groove's median velocity"))
            add(Section("theme", captured, repeats(captured, BODY_BARS), "captured — the break exactly as played"))
            add(Section("variation", variation, repeats(variation, BODY_BARS), variationReason))
            turn?.let { add(Section("the turn", it, repeats(it, BODY_BARS), "fill — the last bar of every four rolls into the turn")) }
            add(Section("reprise", captured, repeats(captured, BODY_BARS), "captured — the theme repeats"))
            add(Section("outro", half, repeats(half, OUTRO_BARS), "half — the same feel, stretched to half time"))
        }
        return Arrangement("${kit.name} Song", seed, sections)
    }

    // ---- the mixdown (KK3) ------------------------------------------------

    /** The stitched song plus where each section starts, in frames. */
    data class Mix(val snip: Snip, val sectionStarts: List<Int>)

    /** The bed sits under the body sections only — never the intro or the turn. */
    private val BED_SECTIONS = setOf("theme", "variation", "reprise")

    /** The Answer plays under the song at bed level. */
    private const val BED_GAIN = 0.8f

    /**
     * The song as one WAV: every section rendered through [KitPreview]
     * (its ring-out included — that's the room), a pull-up spinning into
     * the turn, a tape stop ending the outro — SIDE A's own transitions —
     * and the Answer's bass riding under the body sections when the kit
     * has one (its root re-detected from the stored note, the OneNote
     * way; an undetectable root skips the bed honestly).
     */
    fun mixdown(kit: Kit, kitDir: File, plan: Arrangement): Mix {
        val bpm = (kit.tempoBpm ?: KitPreview.DEFAULT_BPM)
            .coerceIn(KitPreview.MIN_BPM, KitPreview.MAX_BPM)
        val bed = answerBed(kitDir)

        val segments = plan.sections.mapIndexed { i, s ->
            val tiledClip = tiled(s.clip, s.repeats)
            var seg = KitPreview.render(kit, kitDir, clip = tiledClip, tempoBpm = bpm)
            if (bed != null && s.name in BED_SECTIONS) {
                seg = sum(seg, renderBed(bed, s.repeats, bpm))
            }
            when {
                plan.sections.getOrNull(i + 1)?.name == "the turn" ->
                    concatFrames(seg, BeatTape.pullUp(seg))
                i == plan.sections.lastIndex -> BeatTape.tapeStop(seg)
                else -> seg
            }
        }

        val starts = ArrayList<Int>(segments.size)
        var at = 0
        for (seg in segments) {
            starts += at
            at += seg.frameCount
        }
        val out = FloatArray(at * 2)
        segments.forEachIndexed { i, seg ->
            seg.samples.copyInto(out, starts[i] * 2)
        }
        var peak = 0f
        for (v in out) {
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        if (peak > 0.95f) {
            val k = 0.95f / peak
            for (i in out.indices) out[i] *= k
        }
        return Mix(Snip(out, 2, KitPreview.RATE), starts)
    }

    private fun tiled(clip: Mpc3Clip, repeats: Int): Mpc3Clip {
        if (repeats == 1) return clip
        val barPulses = clip.bars.toLong() * Mpc3Clip.PULSES_PER_BAR
        return clip.copy(
            bars = clip.bars * repeats,
            notes = (0 until repeats).flatMap { r ->
                clip.notes.map { it.copy(timePulses = it.timePulses + r * barPulses) }
            },
        )
    }

    private class Bed(val sample: Snip, val rootMidi: Int, val clip: Mpc3Clip)

    private fun answerBed(kitDir: File): Bed? {
        val answer = AnswerStore.load(kitDir) ?: return null
        val file = File(kitDir, answer.sampleFile).takeIf { it.isFile } ?: return null
        val sample = try {
            com.snipsnap.audio.WavReader.read(file)
        } catch (e: Exception) {
            return null
        }
        val pitch = com.snipsnap.audio.Pitch.detect(sample)?.takeIf { it.confidence >= 0.5f } ?: return null
        val rootMidi = Math.round(69.0 + 12.0 * Math.log(pitch.hz / 440.0) / Math.log(2.0)).toInt()
        return Bed(sample, rootMidi, answer.clip)
    }

    /** The bass clip tiled across the section, each note repitched from the root. */
    private fun renderBed(bed: Bed, repeats: Int, bpm: Float): Snip {
        val framesPerPulse = 60.0 / bpm * KitPreview.RATE / 960.0
        val tiledClip = tiled(bed.clip, repeats)
        val total = (tiledClip.bars * Mpc3Clip.PULSES_PER_BAR * framesPerPulse).toInt() + bed.sample.frameCount
        val out = FloatArray(total * 2)
        val cache = HashMap<Int, Snip>()
        for (note in tiledClip.notes) {
            val voiced = cache.getOrPut(note.note) {
                val speed = Math.pow(2.0, (note.note - bed.rootMidi) / 12.0).toFloat()
                if (speed in 0.51f..1.99f && Math.abs(speed - 1f) > 1e-4f) {
                    com.snipsnap.audio.TempoFit.repitch(bed.sample, 1000f, 1000f * speed)
                } else {
                    bed.sample
                }
            }
            val start = (note.timePulses * framesPerPulse).toInt()
            val gain = BED_GAIN * (0.35f + 0.65f * note.velocity)
            for (f in 0 until voiced.frameCount) {
                val at = start + f
                if (at >= total) break
                val s = mono(voiced, f) * gain
                out[at * 2] += s * 0.7071f
                out[at * 2 + 1] += s * 0.7071f
            }
        }
        return Snip(out, 2, KitPreview.RATE)
    }

    private fun mono(snip: Snip, frame: Int): Float =
        if (snip.channels == 1) {
            snip.samples[frame]
        } else {
            var sum = 0f
            for (ch in 0 until snip.channels) sum += snip.samples[frame * snip.channels + ch]
            sum / snip.channels
        }

    /** Sum two stereo snips, the longer one setting the length. */
    private fun sum(a: Snip, b: Snip): Snip {
        val frames = maxOf(a.frameCount, b.frameCount)
        val out = FloatArray(frames * 2)
        a.samples.copyInto(out)
        for (i in b.samples.indices) out[i] += b.samples[i]
        return Snip(out, 2, KitPreview.RATE)
    }

    /** [b] appended after [a] — the pull-up's spinback tail. */
    private fun concatFrames(a: Snip, b: Snip): Snip {
        val out = FloatArray((a.frameCount + b.frameCount) * 2)
        a.samples.copyInto(out)
        b.samples.copyInto(out, a.frameCount * 2)
        return Snip(out, 2, KitPreview.RATE)
    }
}
