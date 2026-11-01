package com.snipsnap.shell

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.SongBus
import com.snipsnap.kit.AnswerStore
import com.snipsnap.kit.BeatTape
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.GrooveVariations
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPreview
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

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
 * Every section's clip is derived fresh from the stored base groove so
 * the plan never depends on what happened to be saved — **with one
 * exception, and it is the point of the exception**: the user's own
 * programs (`GrooveEdit`'s hand-edited forks) play as themselves. The
 * first takes the variation section; any others follow it, one section
 * each, in slot order:
 *
 * ```
 * intro → theme → variation (yours 1) → yours 2 → yours 3 → the turn →
 * reprise → outro
 * ```
 *
 * Until they did, a pattern someone had step-edited by hand could not
 * appear in a song at all; SONG ▸ composed only from what the app had
 * generated, which made the one screen where the user writes something
 * a dead end. Filling several patterns and playing them in a row is the
 * other half of what a wall of pattern buttons is for, and this is
 * where that happens.
 *
 * Read here rather than passed in, from the same [kitDir] the base
 * comes from, so the phone and the CLI cannot disagree about whether a
 * song includes your own work.
 *
 * Seeded and deterministic: the same (kit, seed) always plans the same
 * song. Honest refusals and skips: no groove refuses outright; a kit
 * with nothing to roll on skips the turn; no snare to whisper on means
 * the variation falls back to tight.
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

    fun arrange(
        kit: Kit,
        kitDir: File,
        seed: Int = 0,
        /** Null means straight — `standard`'s own default. Previously not accepted at all, which is why ARRANGE silently ignored the SWING control. */
        swingPercent: Int? = null,
        /** The feel axis, −1f..1f. 0 means untouched. */
        feel: Float = 0f,
        /** The rolled template [feel] scales. Null alongside a non-zero [feel] is a no-op, not an error: a caller with no template has no feel to apply. */
        feelTemplate: GrooveFeel.Template? = null,
    ): Arrangement {
        val stored = GrooveStore.load(kitDir)
        // `firstOrNull { !isProgE }`, not a bare `firstOrNull()`. The two
        // said the same thing only by luck: `GrooveEdit.save` appends the
        // user program last, so the first stored clip happened to be the
        // captured base. But `GrooveEdit.fork` already picks the base with
        // the predicate, and two rules for "which clip is the base" is the
        // shape of defect this repo keeps finding. Now there is one, and a
        // sidecar that ever put the user program first would arrange the
        // captured take rather than deriving swing and half from somebody's
        // hand-edit.
        val base = stored.firstOrNull { !GrooveEdit.isProgE(it) }
            ?: throw IllegalArgumentException(
                "no groove to arrange - chop with --groove, or import a .mid",
            )
        // The user's own programs, in slot order. Not derived and never
        // regenerated: whatever they last left in the step editor. The
        // note-less ones are dropped rather than played —
        // `GrooveEdit.startEmpty` forks an empty program whenever GROOVE
        // opens on a kit that has recorded nothing, so that is the common
        // state, and a silent section is not a section.
        val yours = GrooveEdit.loadAll(kitDir).filter { it.notes.isNotEmpty() }
        // A stored groove with no notes is a real state, not a corrupt
        // one: GrooveEdit.startEmpty writes exactly this when GROOVE opens
        // on a kit that has recorded nothing yet. ORBIT used to be able to
        // write one too, until OrbitClip learned to refuse every no-note
        // shape, so a groove.json from before that can still carry one.
        // There is no song to plan out of silence either way, so it
        // refuses by name here - above the median arithmetic below
        // (`velocities[velocities.size / 2]`), which has no element to
        // index and would otherwise raise a bare IndexOutOfBoundsException
        // instead of saying what is wrong.
        require(base.notes.isNotEmpty()) {
            "the groove \"${base.name}\" has no notes yet - record a take on GROOVE, or chop with --groove"
        }
        val felt = if (feel != 0f && feelTemplate != null) GrooveFeel.applyFeel(base, feel, feelTemplate) else base
        // Deliberately uniform, unlike GrooveScreen's exportMidi: this is
        // NOT mirroring the A-E selector (that's what GrooveProgram.compute
        // is for), it's picking clips for song SECTIONS by name - captured /
        // tight / half / sparse are a song-structure choice, and "tight"
        // here is the section named "variation", not a stand-in for PROG B.
        // So every clip below is derived from felt, not base: std's four
        // slots via GrooveVariations.standard(felt, swingPercent), and
        // ghosted/fill further down take felt too - all six arrangement
        // sections carry the feel. ARRANGE differs from the screen's
        // PROG B feel-exemption on purpose.
        val std = GrooveVariations.standard(felt, swingPercent)
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
            GrooveVariations.ghosted(felt, kit, seed = seed + 1)
        } catch (e: IllegalArgumentException) {
            null
        }
        val variation = yours.firstOrNull() ?: if (preferGhosts && ghosted != null) ghosted else tight
        val variationReason = when {
            // Ahead of the measured rules below, and the only thing that
            // outranks them: the variation section is where the song says
            // "here is the same beat, different" — which is exactly what a
            // hand-edit is. A measurement about ghost notes is a good way to
            // invent a variation and a poor reason to ignore one.
            //
            // It is handed over as stored, not `felt`, while every other
            // section carries the feel. Same exemption `GrooveProgram` makes
            // for it on screen, for the same reason: these steps are already
            // where somebody put them, and leaning on them would move hits
            // that were placed by hand.
            yours.isNotEmpty() -> "yours — the program you stepped in, exactly as you left it"
            preferGhosts && ghosted != null ->
                "ghosted — dynamic range %.1fx ≥ %.0fx threshold".format(java.util.Locale.ROOT, dynamicRange, VARIATION_GHOST_DYNAMIC_RANGE)
            preferGhosts ->
                "tight — dynamic range %.1fx ≥ %.0fx threshold, but no snare or clap to whisper on"
                    .format(java.util.Locale.ROOT, dynamicRange, VARIATION_GHOST_DYNAMIC_RANGE)
            else ->
                "tight — dynamic range %.1fx < %.0fx threshold".format(java.util.Locale.ROOT, dynamicRange, VARIATION_GHOST_DYNAMIC_RANGE)
        }
        val turn = try {
            GrooveVariations.fill(felt, kit, seed = seed + 2)
        } catch (e: IllegalArgumentException) {
            null
        }

        fun repeats(clip: Mpc3Clip, target: Int) = maxOf(1, target / clip.bars)
        val sections = buildList {
            add(Section("intro", sparse, repeats(sparse, INTRO_BARS), "sparse — only the hits at or above the base groove's median velocity"))
            add(Section("theme", captured, repeats(captured, BODY_BARS), "captured — the break exactly as played"))
            add(Section("variation", variation, repeats(variation, BODY_BARS), variationReason))
            // Your other programs, each its own section, in slot order and
            // straight after the first. This is the chaining half of what a
            // wall of pattern buttons is for: filling several and playing
            // them in a row. They sit together rather than being scattered
            // through the structure because they are the part of the song
            // somebody wrote, and a song reads better when that part is
            // continuous than when the app interleaves itself into it.
            for ((i, extra) in yours.drop(1).withIndex()) {
                add(
                    Section(
                        "yours ${i + 2}", extra, repeats(extra, BODY_BARS),
                        "yours — your program ${i + 2}, exactly as you left it",
                    ),
                )
            }
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
     * How far from its own note the bed's sample is asked to play: two
     * octaves either way. The Answer's widest leap is the octave, so no
     * line the app writes comes near it. The bound is for a hand-edited
     * `answer.json`, whose note four octaves down would ask for a buffer
     * sixteen times the sample's length; that note is left out instead.
     */
    internal const val BED_REACH_SEMITONES = 24

    /**
     * The song as one WAV: every section rendered through [KitPreview]
     * and laid on its own downbeat, a pull-up over the last beat before
     * the turn, a tape stop ending the outro — SIDE A's own transitions —
     * and the Answer's bass riding under the body sections when the kit
     * has one (its root re-detected from the stored note, the OneNote
     * way; an undetectable root skips the bed honestly).
     *
     * A section starts on the frame of its own first pulse, counted from
     * the top of the song, so the bar count is the timing. Each section's
     * ring-out (that's the room) is added under the next section's
     * opening rather than played before it: laid end to end, every
     * ring-out pushed the next downbeat 0.6 s late, 0.92 of a beat at the
     * default tempo, and the song stumbled at every boundary
     * (docs/plans/2026-10-30-song-making.md, phase 2).
     *
     * The drums of each stretch the pull-up does not interrupt render as
     * one take ([KitPreview.renderNotes]), not section by section, so the
     * kit's chokes reach across a boundary: an open hat on the last off-beat
     * of the theme stops at the variation's closed hat on one, as it would
     * on the hardware, instead of ringing over it. The tape stop runs on the
     * whole song at the outro's end, because a tape carries everything on
     * it: a bass note still ringing from the reprise slows and dies with the
     * drums rather than playing on past the ending.
     */
    fun mixdown(kit: Kit, kitDir: File, plan: Arrangement): Mix {
        val bpm = KitPreview.playedBpm(kit.tempoBpm)
        val framesPerPulse = framesPerPulse(bpm)
        fun frameAt(pulse: Long) = (pulse * framesPerPulse).roundToInt()
        val bed = answerBed(kitDir)

        val clips = plan.sections.map { tiled(it.clip, it.repeats) }
        // Counted in pulses and turned into a frame once per boundary, so
        // rounding never accumulates across the song.
        val startPulses = clips.runningFold(0L) { at, clip -> at + clip.lengthPulses }
        val starts = plan.sections.indices.map { frameAt(startPulses[it]) }

        val turn = plan.sections.indexOfFirst { it.name == "the turn" }
        val stretches = if (turn > 0) listOf(0 until turn, turn until plan.sections.size) else listOf(plan.sections.indices)

        var songEnd = 0
        val pieces = stretches.map { range ->
            val first = range.first
            val notes = range.flatMap { i ->
                val offset = startPulses[i] - startPulses[first]
                clips[i].notes.map { it.copy(timePulses = it.timePulses + offset) }
            }
            val drums = KitPreview.renderNotes(kit, kitDir, notes, startPulses[range.last + 1] - startPulses[first], bpm)
            // The last stretch's drums, ring-out included, are where the song ends.
            songEnd = starts[first] + drums.frameCount
            var audio = drums
            if (bed != null) {
                for (i in range) {
                    if (plan.sections[i].name in BED_SECTIONS) {
                        audio = addAt(audio, renderBed(bed, clips[i].lengthPulses, framesPerPulse), starts[i] - starts[first])
                    }
                }
            }
            if (range.last + 1 == turn) {
                val lastBeat = frameAt(startPulses[turn] - Mpc3Clip.PULSES_PER_BEAT) - starts[first]
                audio = pullUpOver(audio, cut = lastBeat, end = starts[turn] - starts[first])
            }
            starts[first] to audio
        }

        val frames = pieces.maxOf { (at, audio) -> at + audio.frameCount }
        val out = FloatArray(frames * 2)
        for ((at, audio) in pieces) {
            for (j in audio.samples.indices) out[at * 2 + j] += audio.samples[j]
        }
        val song = BeatTape.tapeStop(Snip(out.copyOf(songEnd * 2), 2, KitPreview.RATE))
        SongBus.ceiling(song.samples)
        return Mix(song, starts)
    }

    /** [KitPreview]'s own frame arithmetic, so a section boundary and a note inside it agree. */
    private fun framesPerPulse(bpm: Float): Double = 60.0 / bpm * KitPreview.RATE / Mpc3Clip.PULSES_PER_BEAT

    /**
     * [clip] played [repeats] times end to end. Each repeat starts one
     * clip-length later, in the clip's own meter: a three-four bar is
     * three beats, and tiling it by a four-four bar put every repeat a
     * beat per bar late.
     */
    internal fun tiled(clip: Mpc3Clip, repeats: Int): Mpc3Clip {
        if (repeats == 1) return clip
        val length = clip.lengthPulses
        return clip.copy(
            bars = clip.bars * repeats,
            notes = (0 until repeats).flatMap { r ->
                clip.notes.map { it.copy(timePulses = it.timePulses + r * length) }
            },
        )
    }

    /**
     * [seg] with its last beat, from [cut] to [end], replaced by the
     * spinback built from what played before it: the record pulled back
     * over the final beat, so the turn drops on its own downbeat (D1 in
     * the plan). Nothing of [seg] sounds past [end]. A pulled-up record has
     * stopped, so its ring-out does not play under the turn.
     */
    internal fun pullUpOver(seg: Snip, cut: Int, end: Int): Snip {
        require(seg.channels == 2) { "the mixdown is stereo, got ${seg.channels} channel(s)" }
        require(cut in 0..end) { "the pull-up starts inside the section: $cut of $end" }
        val from = minOf(cut, seg.frameCount)
        val head = Snip(seg.samples.copyOf(from * 2), 2, seg.sampleRate)
        val spin = BeatTape.pullUp(head, (end - from).toFloat() / seg.sampleRate)
        val out = FloatArray(end * 2)
        head.samples.copyInto(out)
        spin.samples.copyInto(out, from * 2, 0, minOf(spin.samples.size, (end - from) * 2))
        return Snip(out, 2, seg.sampleRate)
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

    /**
     * The bass under one section of [sectionPulses]: the Answer's clip
     * laid end to end across the section ([bedNotes]), each note repitched
     * from the sample's root to the note it names. Its last notes ring out
     * past the section's end, as a played note does.
     */
    private fun renderBed(bed: Bed, sectionPulses: Long, framesPerPulse: Double): Snip {
        val cache = HashMap<Int, Snip?>()
        val voiced = bedNotes(bed.clip, sectionPulses).mapNotNull { note ->
            val v = if (note.note in cache) cache[note.note] else repitched(bed.sample, note.note - bed.rootMidi).also { cache[note.note] = it }
            v?.let { note to it }
        }
        val sectionFrames = (sectionPulses * framesPerPulse).roundToInt()
        val total = maxOf(sectionFrames, voiced.maxOfOrNull { (n, v) -> (n.timePulses * framesPerPulse).roundToInt() + v.frameCount } ?: 0)
        val out = FloatArray(total * 2)
        for ((note, v) in voiced) {
            val start = (note.timePulses * framesPerPulse).roundToInt()
            val gain = BED_GAIN * (0.35f + 0.65f * note.velocity)
            for (f in 0 until v.frameCount) {
                val s = mono(v, f) * gain
                out[(start + f) * 2] += s * 0.7071f
                out[(start + f) * 2 + 1] += s * 0.7071f
            }
        }
        return Snip(out, 2, KitPreview.RATE)
    }

    /**
     * The bed [clip]'s notes laid end to end from the section's first
     * pulse, and none at or past [sectionPulses]. Cut to the section, not
     * tiled by the section's repeat count: that count belongs to the drum
     * clip, so a bass line of a different length used to run past the
     * section or stop short of its end.
     */
    internal fun bedNotes(clip: Mpc3Clip, sectionPulses: Long): List<Mpc3Note> {
        val loop = clip.lengthPulses
        val laps = ((sectionPulses + loop - 1) / loop).toInt()
        return (0 until laps)
            .flatMap { r -> clip.notes.map { it.copy(timePulses = it.timePulses + r * loop) } }
            .filter { it.timePulses < sectionPulses }
    }

    /**
     * [sample] moved by [semitones] (D4 in the plan): resampled to a
     * virtual rate and read back at its own, `TempoFit.repitch`'s
     * arithmetic without that function's factor-of-two bound. The bound is
     * right for a tempo fit and wrong for a bass line, whose octave is a
     * speed of exactly 2. The old window (0.51..1.99) played every octave
     * note at the sample's root, an octave low, and the Answer writes an
     * octave about one note in eight (one in three in a scale without a
     * fifth). Null past [BED_REACH_SEMITONES].
     */
    internal fun repitched(sample: Snip, semitones: Int): Snip? {
        if (semitones == 0) return sample
        if (abs(semitones) > BED_REACH_SEMITONES) return null
        val speed = 2.0.pow(semitones / 12.0)
        val moved = Resampler.resample(sample, (sample.sampleRate / speed).roundToInt())
        return Snip(moved.samples, moved.channels, sample.sampleRate)
    }

    private fun mono(snip: Snip, frame: Int): Float =
        if (snip.channels == 1) {
            snip.samples[frame]
        } else {
            var sum = 0f
            for (ch in 0 until snip.channels) sum += snip.samples[frame * snip.channels + ch]
            sum / snip.channels
        }

    /** Stereo [b] added into stereo [a] from frame [at], the longer reach setting the length. */
    private fun addAt(a: Snip, b: Snip, at: Int): Snip {
        val frames = maxOf(a.frameCount, at + b.frameCount)
        val out = FloatArray(frames * 2)
        a.samples.copyInto(out)
        for (i in b.samples.indices) out[at * 2 + i] += b.samples[i]
        return Snip(out, 2, KitPreview.RATE)
    }
}
