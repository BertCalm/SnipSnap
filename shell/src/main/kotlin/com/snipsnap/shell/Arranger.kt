package com.snipsnap.shell

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.SongBus
import com.snipsnap.kit.AnswerStore
import com.snipsnap.kit.BeatTape
import com.snipsnap.kit.StepChance
import com.snipsnap.kit.ClipRef
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.GrooveVariations
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.PlanSection
import com.snipsnap.kit.SectionNames
import com.snipsnap.kit.SongPlan
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
     *
     * [ref] is the clip's name in a kept song ([ClipRef]), [locked] whether
     * REROLL leaves it alone, and [seed] the draw its ghosts and fill came
     * from; [Arranger.toPlan] turns a run of these into a [SongPlan] and
     * [Arranger.resolve] turns one back.
     */
    data class Section(
        val name: String,
        val clip: Mpc3Clip,
        val repeats: Int,
        val reason: String,
        val ref: String = "",
        val locked: Boolean = false,
        val seed: Int = 0,
        /** The Answer's bass plays under it ([PlanSection.bass]). */
        val bass: Boolean = SectionNames.bassUnder(name),
        /** A pull-up plays over the beat before it ([PlanSection.pullUp]). */
        val pullUp: Boolean = SectionNames.pullsUpInto(name),
        /** The section's own last bar keeps only the kick, or the bed ([PlanSection.drop]). */
        val drop: Boolean = false,
        /** The section's own last bar sweeps down through a low-pass ([PlanSection.close]). */
        val close: Boolean = false,
        /** A reversed crash or open hat ends on the next section's downbeat ([PlanSection.reverseCrash]). */
        val reverseCrash: Boolean = false,
        /** The lanes left out of it ([PlanSection.mutes]); the [clip] still holds their hits, so un-muting needs no new layout. */
        val mutes: Set<String> = emptySet(),
    ) {
        init {
            require(repeats >= 1) { "a section plays at least once" }
        }

        val bars: Int get() = clip.bars * repeats
    }

    data class Arrangement(val name: String, val seed: Int, val sections: List<Section>) {
        val totalBars: Int get() = sections.sumOf { it.bars }
    }

    /** The sections every draft has whatever the kit holds: intro, theme, variation, the turn, reprise, outro. */
    private const val FIXED_SECTIONS = 6

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
        val palette = Palette.load(kit, kitDir, swingPercent, feel, feelTemplate)
        return Arrangement("${kit.name} Song", seed, palette.draft(seed))
    }

    // ---- a kept song (SongPlan) -------------------------------------------

    /** A plan resolved against the kit as it is now, and the references that could not be. */
    data class Resolved(
        val arrangement: Arrangement,
        /** The clip names the kept plan asked for that no longer exist, in plan order, each once (`yours:3`, `fill`). */
        val refused: List<String>,
    )

    /**
     * The song's sections as a [SongPlan]: each section's clip by name,
     * its repeats, its lock and the seed it was drawn with. The first plan
     * a kit gets is `toPlan(arrange(..., seed = 0))`, so the first open is
     * the arranger's own draft and everything after it is the person's.
     */
    fun toPlan(arrangement: Arrangement): SongPlan = SongPlan(
        seed = arrangement.seed,
        sections = arrangement.sections.map {
            PlanSection(
                name = it.name,
                clip = it.ref,
                repeats = it.repeats,
                locked = it.locked,
                seed = it.seed,
                bass = it.bass,
                pullUp = it.pullUp,
                drop = it.drop,
                close = it.close,
                reverseCrash = it.reverseCrash,
                mutes = it.mutes,
            )
        },
    )

    /**
     * [plan] laid out again from the kit as it is now. Every clip is
     * derived afresh from its NAME, so a beat edited since still flows into
     * the song while the order, repeats and locks the person chose stay.
     *
     * A name that does not resolve (a program deleted, a fill the kit can
     * no longer roll) is refused: that section is planned again, as the
     * arranger's own draft section of the same name when it has one and as
     * the captured groove when it does not, and the name comes back in
     * [Resolved.refused] for the screen to say. Repeats are cut to what a
     * 64-bar sequence can hold ([maxRepeats]). Throws as [arrange] does
     * when the kit has no groove to plan from.
     */
    fun resolve(
        kit: Kit,
        kitDir: File,
        plan: SongPlan,
        swingPercent: Int? = null,
        feel: Float = 0f,
        feelTemplate: GrooveFeel.Template? = null,
    ): Resolved {
        val palette = Palette.load(kit, kitDir, swingPercent, feel, feelTemplate)
        val refused = mutableListOf<String>()
        val sections = plan.sections.map { s ->
            val clip = palette.clip(s.clip, s.seed)
            if (clip != null) {
                Section(
                    name = s.name,
                    clip = clip,
                    repeats = minOf(s.repeats, maxRepeats(clip)),
                    reason = palette.reason(s.clip),
                    ref = s.clip,
                    locked = s.locked,
                    seed = s.seed,
                    bass = s.bass,
                    pullUp = s.pullUp,
                    drop = s.drop,
                    close = s.close,
                    reverseCrash = s.reverseCrash,
                    mutes = s.mutes,
                )
            } else {
                if (s.clip !in refused) refused += s.clip
                // A gone program of yours is not replaced by a different one of
                // yours: the draft's own program sections are kept out of the
                // search, so a pattern made since cannot slip into the song under
                // the section name the old one had.
                val draft = palette.draft(s.seed).filter { ClipRef.yoursId(s.clip) == null || ClipRef.yoursId(it.ref) == null }
                val again = draft.firstOrNull { it.name == s.name } ?: draft.first { it.ref == ClipRef.CAPTURED }
                again.copy(
                    name = s.name,
                    repeats = minOf(s.repeats, maxRepeats(again.clip)),
                    locked = s.locked,
                    seed = s.seed,
                    bass = s.bass,
                    pullUp = s.pullUp,
                    drop = s.drop,
                    close = s.close,
                    reverseCrash = s.reverseCrash,
                    mutes = s.mutes,
                )
            }
        }
        return Resolved(Arrangement("${kit.name} Song", plan.seed, sections), refused)
    }

    /** A rerolled plan, and how many sections it redrew (a new clip, or a new take of a seeded one). */
    data class Rerolled(val plan: SongPlan, val redrawn: Int)

    /** Clips that stand in for one another on a reroll: the full beat, and the light one. A fill stays a fill. */
    private val FULL_FAMILY = listOf(ClipRef.CAPTURED, ClipRef.TIGHT, ClipRef.GHOSTED)
    private val LIGHT_FAMILY = listOf(ClipRef.SPARSE, ClipRef.HALF)

    /**
     * REROLL: every unlocked section redrawn with [newSeed], the song's
     * order kept. A section on one of the derived clips draws again from
     * its family — the full beat (captured, tight, ghosted) or the light
     * one (sparse, half) — among what the kit can make now, so an intro
     * stays light and a body section stays full. It avoids repeating the
     * section before it when its family has another choice, and keeps
     * the section's length in bars as near as the new clip allows.
     *
     * Left as they are: a locked section; a section playing one of your
     * own programs (the arranger never swaps out what somebody wrote); a
     * fill, which stays the fill and gets a new take from the seed. The
     * draw is keyed on [newSeed] and the section's place, so the same
     * reroll of the same plan is the same song.
     */
    fun rerolled(
        kit: Kit,
        kitDir: File,
        plan: SongPlan,
        newSeed: Int,
        swingPercent: Int? = null,
        feel: Float = 0f,
        feelTemplate: GrooveFeel.Template? = null,
    ): Rerolled {
        val palette = Palette.load(kit, kitDir, swingPercent, feel, feelTemplate)
        var redrawn = 0
        val out = ArrayList<PlanSection>(plan.sections.size)
        for ((i, s) in plan.sections.withIndex()) {
            if (s.locked) {
                out += s
                continue
            }
            val family = when (s.clip) {
                in FULL_FAMILY -> FULL_FAMILY
                in LIGHT_FAMILY -> LIGHT_FAMILY
                else -> null
            }
            if (family == null) {
                // Yours keeps its clip; a fill (or a name this build does not know) keeps its name and takes the new seed.
                if (s.clip == ClipRef.FILL || s.clip == ClipRef.GHOSTED) redrawn++
                out += s.copy(seed = newSeed)
                continue
            }
            val choices = family.filter { palette.clip(it, newSeed) != null }
            if (choices.isEmpty()) {
                out += s.copy(seed = newSeed)
                continue
            }
            val previous = out.lastOrNull()?.clip
            val pool = choices.filter { it != previous }.ifEmpty { choices }
            val pick = pool[kotlin.random.Random(newSeed * 31 + i).nextInt(pool.size)]
            val oldBars = (palette.clip(s.clip, s.seed)?.bars ?: 1) * s.repeats
            val newBars = palette.clip(pick, newSeed)!!.bars
            val repeats = Math.round(oldBars.toFloat() / newBars).coerceIn(1, maxRepeats(newBars))
            if (pick != s.clip || pick == ClipRef.GHOSTED) redrawn++
            out += s.copy(clip = pick, repeats = repeats, seed = newSeed)
        }
        return Rerolled(plan.copy(seed = newSeed, sections = out), redrawn)
    }

    /** A clip name the kit can play, and how many bars one play of it is. */
    data class Pick(val ref: String, val bars: Int)

    /**
     * Every clip the kit can play right now, in the order a picker lists
     * them: the derived ones it can make, then each of the person's
     * programs that has notes. The same test [resolve] applies, so a name
     * offered here always resolves.
     */
    fun available(
        kit: Kit,
        kitDir: File,
        swingPercent: Int? = null,
        feel: Float = 0f,
        feelTemplate: GrooveFeel.Template? = null,
    ): List<Pick> {
        val palette = Palette.load(kit, kitDir, swingPercent, feel, feelTemplate)
        return ClipRef.DERIVED.mapNotNull { ref -> palette.clip(ref, 0)?.let { Pick(ref, it.bars) } } +
            palette.yours.map { (id, clip) -> Pick(ClipRef.yours(id), clip.bars) }
    }

    /** How many times [clip] may repeat: [SongPlan.MAX_REPEATS], and no more than fit in the 64 bars a sequence holds. */
    fun maxRepeats(clip: Mpc3Clip): Int = maxRepeats(clip.bars)

    fun maxRepeats(clipBars: Int): Int = minOf(SongPlan.MAX_REPEATS, MAX_CLIP_BARS / clipBars).coerceAtLeast(1)

    /** The most bars one MPC sequence (and so one section, repeats included) holds: `Mpc3Clip`'s own bound. */
    private const val MAX_CLIP_BARS = 64

    /**
     * Everything a section's clip can be, derived once from the kit's base
     * groove: the four standard variations, the user's programs, and the
     * two seeded ones ([ghosted], [fill]) on request.
     */
    private class Palette(
        val kit: Kit,
        val felt: Mpc3Clip,
        val captured: Mpc3Clip,
        val tight: Mpc3Clip,
        val half: Mpc3Clip,
        val sparse: Mpc3Clip,
        /** The user's programs with notes, in slot order, each with its own number. */
        val yours: List<Pair<Int, Mpc3Clip>>,
        val dynamicRange: Float,
        /** The names the person gave their programs, by program number; a program without one is "yours N". */
        val names: Map<Int, String> = emptyMap(),
    ) {
        val preferGhosts: Boolean get() = dynamicRange >= VARIATION_GHOST_DYNAMIC_RANGE

        fun ghosted(seed: Int): Mpc3Clip? = try {
            GrooveVariations.ghosted(felt, kit, seed = seed + 1)
        } catch (e: IllegalArgumentException) {
            null
        }

        fun fill(seed: Int): Mpc3Clip? = try {
            GrooveVariations.fill(felt, kit, seed = seed + 2)
        } catch (e: IllegalArgumentException) {
            null
        }

        /** The clip a name stands for, drawn with [seed]; null when the kit cannot make it now. */
        fun clip(ref: String, seed: Int): Mpc3Clip? = when (ref) {
            ClipRef.CAPTURED -> captured
            ClipRef.TIGHT -> tight
            ClipRef.HALF -> half
            ClipRef.SPARSE -> sparse
            ClipRef.GHOSTED -> ghosted(seed)
            ClipRef.FILL -> fill(seed)
            else -> ClipRef.yoursId(ref)?.let { id -> yours.firstOrNull { it.first == id }?.second }
        }

        /** The rule behind a kept section, in words: what the name means, not how the draft came to pick it. */
        fun reason(ref: String): String = when {
            ref == ClipRef.CAPTURED -> "captured — the break exactly as played"
            ref == ClipRef.TIGHT -> "tight — the break re-quantized"
            ref == ClipRef.HALF -> "half — the same feel, stretched to half time"
            ref == ClipRef.SPARSE -> "sparse — only the hits at or above the base groove's median velocity"
            ref == ClipRef.GHOSTED -> "ghosted — quiet hits added between the break's own"
            ref == ClipRef.FILL -> "fill — the last bar of every four rolls into the turn"
            ClipRef.yoursId(ref) != null -> ClipRef.yoursId(ref)!!.let { id ->
                names[id]?.let { "yours — your pattern $it, exactly as you left it" }
                    ?: "yours — your program ${id + 1}, exactly as you left it"
            }
            else -> ref
        }

        /** The arranger's own song for [seed]: intro, theme, variation, your other programs, the turn, reprise, outro. */
        fun draft(seed: Int): List<Section> {
            val ghosted = ghosted(seed)
            val variation = yours.firstOrNull()?.second ?: if (preferGhosts && ghosted != null) ghosted else tight
            val variationRef = when {
                yours.isNotEmpty() -> ClipRef.yours(yours.first().first)
                preferGhosts && ghosted != null -> ClipRef.GHOSTED
                else -> ClipRef.TIGHT
            }
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
            val turn = fill(seed)

            fun repeats(clip: Mpc3Clip, target: Int) = maxOf(1, target / clip.bars)
            fun section(name: String, ref: String, clip: Mpc3Clip, target: Int, reason: String) =
                Section(name, clip, repeats(clip, target), reason, ref, locked = false, seed = seed)
            return buildList {
                add(section("intro", ClipRef.SPARSE, sparse, INTRO_BARS, reason(ClipRef.SPARSE)))
                add(section("theme", ClipRef.CAPTURED, captured, BODY_BARS, "captured — the break exactly as played"))
                add(section("variation", variationRef, variation, BODY_BARS, variationReason))
                // Your other programs, each its own section, in slot order and
                // straight after the first. This is the chaining half of what a
                // wall of pattern buttons is for: filling several and playing
                // them in a row. They sit together rather than being scattered
                // through the structure because they are the part of the song
                // somebody wrote, and a song reads better when that part is
                // continuous than when the app interleaves itself into it.
                // At most the sections a song has room for once the six that are
                // always there are placed: past that the rest are still one SWAP away.
                for ((i, extra) in yours.drop(1).take(SongPlan.MAX_SECTIONS - FIXED_SECTIONS).withIndex()) {
                    add(
                        section(
                            names[extra.first]?.lowercase(java.util.Locale.ROOT) ?: "yours ${i + 2}",
                            ClipRef.yours(extra.first), extra.second, BODY_BARS,
                            names[extra.first]?.let { "yours — your pattern $it, exactly as you left it" }
                                ?: "yours — your program ${i + 2}, exactly as you left it",
                        ),
                    )
                }
                turn?.let { add(section(TURN, ClipRef.FILL, it, BODY_BARS, reason(ClipRef.FILL))) }
                add(section("reprise", ClipRef.CAPTURED, captured, BODY_BARS, "captured — the theme repeats"))
                add(section("outro", ClipRef.HALF, half, OUTRO_BARS, reason(ClipRef.HALF)))
            }
        }

        companion object {
            fun load(kit: Kit, kitDir: File, swingPercent: Int?, feel: Float, feelTemplate: GrooveFeel.Template?): Palette {
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
                    .map { (GrooveEdit.progIndexOf(it) ?: 0) to it }
                // A stored groove with no notes is a real state, not a corrupt
                // one: GrooveEdit.startEmpty writes exactly this when GROOVE
                // opens on a kit that has recorded nothing yet. ORBIT used to be able to
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
                // ghosted/fill take felt too - all six arrangement
                // sections carry the feel. ARRANGE differs from the screen's
                // PROG B feel-exemption on purpose.
                val std = GrooveVariations.standard(felt, swingPercent)

                // A measured number, not a coin flip: the base groove's own
                // dynamic range decides whether the variation has room for a
                // whisper between its hits.
                val velocities = base.notes.map { it.velocity }.sorted()
                val median = velocities[velocities.size / 2]
                val loudest = velocities.last()
                val dynamicRange = if (median > 0f) loudest / median else 0f
                return Palette(kit, felt, std[0], std[1], std[2], std[3], yours, dynamicRange, GrooveEdit.namesById(kitDir))
            }
        }
    }

    // ---- the mixdown (KK3) ------------------------------------------------

    /**
     * The stitched song plus where each section starts, in frames, and
     * [passes]: every time through every section's clip, in song order, on
     * the same grid. Live play ([SongCursor]) moves between passes.
     */
    data class Mix(
        val snip: Snip,
        val sectionStarts: List<Int>,
        val passes: List<Pass> = emptyList(),
        val drops: List<Drop> = emptyList(),
        val transitions: List<Transition> = emptyList(),
    )

    /**
     * What a section's CLOSE or REVERSE CRASH did: [kind] is "CLOSE" or
     * "REVERSE CRASH", [applied] whether the bar was changed, and the
     * [reason] says why not when it was left as it was (no crash or open hat
     * in the kit, no next section, another transition on the boundary), or
     * what was used when it was.
     */
    data class Transition(val section: Int, val kind: String, val applied: Boolean, val reason: String)

    /**
     * What a section's DROP did: [kept] is "kick" or "bed" when its last
     * bar was re-rendered from only that, and null when the bar was left
     * as it was, with the [reason] either way, in words.
     */
    data class Drop(val section: Int, val kept: String?, val reason: String)

    /**
     * The pad slots [clip] plays, in order: the lanes a section can mute
     * ([PlanSection.padLane] makes each one's key).
     */
    fun lanes(clip: Mpc3Clip): List<Int> = clip.notes.map { Mpc3Note.slotFor(it.note) }.distinct().sorted()

    /**
     * [clip] without the hits of the pad lanes in [mutes]: a muted lane is
     * not played, so it neither sounds nor chokes anything. Its length and
     * bar are the clip's own. With no pad lane muted it is [clip] itself.
     */
    internal fun withoutMuted(clip: Mpc3Clip, mutes: Set<String>): Mpc3Clip =
        if (mutes.none { it.startsWith("pad:") }) clip
        else clip.copy(notes = clip.notes.filter { PlanSection.padLane(Mpc3Note.slotFor(it.note)) !in mutes })

    /** What a dropped bar keeps: the kick pad's hits, or the bed's notes. */
    internal enum class DropKeeps { KICK, BED }

    /** The ramp that ends a take cut short, frames: about 5 ms, so a cut tail does not click. */
    private const val CUT_FADE = 220

    /**
     * One time through [section]'s clip: frames [start] until [end] of the
     * mix, on the grid. A section played three times is three passes.
     * [barLines] are the frames of the bar lines strictly inside the pass,
     * each rounded once from its own pulse like every other boundary (a
     * one-bar pass has none); live play's NEXT BAR cuts there.
     */
    data class Pass(val section: Int, val start: Int, val end: Int, val barLines: List<Int> = emptyList())

    /**
     * What a played run of passes makes as a song: [plan], and how many
     * passes did not fit ([left]) once the song reached
     * [SongPlan.MAX_SECTIONS] sections.
     */
    data class Played(val plan: SongPlan, val left: Int)

    /**
     * KEEP THIS ORDER: the song [played] makes, where [played] names the
     * section of every pass heard, in order, and [clipBars] is each
     * section's clip length. Each run of one section becomes that section
     * (its clip, lock, seed, BASS and PULL-UP) played that many times. A run
     * longer than one sequence holds ([maxRepeats]) is split across copies.
     * Null when nothing was played.
     */
    fun playedOrder(kept: SongPlan, clipBars: List<Int>, played: List<Int>): Played? {
        if (played.isEmpty()) return null
        val runs = mutableListOf<Pair<Int, Int>>()
        for (s in played) {
            val last = runs.lastOrNull()
            if (last != null && last.first == s) runs[runs.lastIndex] = s to last.second + 1 else runs += s to 1
        }
        val out = mutableListOf<PlanSection>()
        var left = 0
        for ((s, count) in runs) {
            val max = maxRepeats(clipBars[s])
            var rest = count
            while (rest > 0) {
                val take = minOf(rest, max)
                if (out.size < SongPlan.MAX_SECTIONS) out += kept.sections[s].copy(repeats = take) else left += take
                rest -= take
            }
        }
        return Played(SongPlan(kept.seed, out), left)
    }

    /** The section a pull-up leads into, in the arranger's own draft. */
    private const val TURN = SectionNames.THE_TURN

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

        // Rolled per repeat here (the WAV is what the song sounds like); the .xpj under WRITE keeps the chances for the MPC to roll.
        val unmuted = plan.sections.map { tiled(it.clip, it.repeats) }
        // A muted lane's hits are left out before anything else reads the clip: they are not played, so they cannot choke or ring.
        val clips = unmuted.mapIndexed { i, c -> withoutMuted(c, plan.sections[i].mutes) }
        // A stretch whose every hit rolled silent is a stretch of silence, not a kit that cannot play the clip.
        val chancy = plan.sections.any { StepChance.hasChance(it.clip) }
        // Counted in pulses and turned into a frame once per boundary, so
        // rounding never accumulates across the song.
        val startPulses = clips.runningFold(0L) { at, clip -> at + clip.lengthPulses }
        val starts = plan.sections.indices.map { frameAt(startPulses[it]) }

        // Every section marked [Section.pullUp] (past the first, which has
        // nothing before it to pull up) is preceded by a pull-up, so a song
        // with the turn played twice pulls up twice. The mark is the
        // section's own, kept through a move, a copy or a new name.
        val turns = plan.sections.indices.filter { it > 0 && plan.sections[it].pullUp }
        val cuts = listOf(0) + turns + listOf(plan.sections.size)
        val stretches = cuts.zipWithNext { from, to -> from until to }

        // DROP: a section's own last bar re-rendered from the kick's hits, or
        // the bed's notes, alone. The boundary into a pull-up is the pull-up's.
        val dropReports = mutableListOf<Drop>()
        val dropAt = HashMap<Int, DropBar>()
        for (i in plan.sections.indices) {
            if (!plan.sections[i].drop) continue
            if (i + 1 in turns) {
                dropReports += Drop(i, null, "the pull-up into the next section takes this boundary, so the last bar was left as it is")
                continue
            }
            val bedHere = bed?.takeIf { plan.sections[i].bass && PlanSection.BED_LANE !in plan.sections[i].mutes }
            val d = dropBarOf(kit, clips[i], startPulses[i + 1], bedHere)
            if (d == null) {
                // The kick is in the bar but muted: DROP keeps only the kick, and there is none to keep, so the bar is silent.
                val silenced = dropBarOf(kit, unmuted[i], startPulses[i + 1], null)
                if (silenced != null) {
                    dropAt[i] = DropBar(DropKeeps.KICK, silenced.startPulse, silenced.endPulse, emptyList(), emptyList())
                    dropReports += Drop(i, "kick", "the last bar keeps only the kick, which is muted in this section, so the bar is silent")
                    continue
                }
                val why = if (kit.pads.none { it.drumClass == com.snipsnap.audio.DrumClass.KICK }) "no pad in this kit is marked as a kick" else "the last bar has no kick hit"
                dropReports += Drop(i, null, "$why and there is no bed under the section, so the last bar was left as it is")
            } else {
                dropAt[i] = d
                dropReports += Drop(i, if (d.keeps == DropKeeps.BED) "bed" else "kick", "the last bar keeps only the ${if (d.keeps == DropKeeps.BED) "bed" else "kick"}")
            }
        }

        val transitionReports = mutableListOf<Transition>()
        // A stretch whose every hit is muted is a stretch of silence, as one whose hits all rolled out is.
        fun anyMuted(range: IntRange) = range.any { plan.sections[it].mutes.isNotEmpty() }
        val pieces = stretches.map { range ->
            val first = range.first
            val notes = range.flatMap { i ->
                val offset = startPulses[i] - startPulses[first]
                clips[i].notes.map { it.copy(timePulses = it.timePulses + offset) }
            }
            val stretchPulses = startPulses[range.last + 1] - startPulses[first]
            val drops = range.mapNotNull { dropAt[it] }
            var audio = if (drops.isEmpty()) {
                KitPreview.renderNotes(kit, kitDir, notes, stretchPulses, bpm, allowSilence = chancy || anyMuted(range))
            } else {
                // Each stretch of drums between drops is its own take, in this stretch's own frames, cut where
                // the next dropped bar begins: a tail never rings into it, and none comes back after it.
                // The dropped bar is its own take; the notes after it are rendered as they would have been.
                var mixed = Snip(FloatArray(0), 2, KitPreview.RATE)
                var from = 0L
                for (d in drops) {
                    val a = d.startPulse - startPulses[first]
                    val cutFrame = frameAt(d.startPulse) - starts[first]
                    val before = notes.filter { it.timePulses in from until a }
                    if (before.isNotEmpty()) {
                        mixed = addAt(mixed, cutAt(KitPreview.renderNotes(kit, kitDir, before, a, bpm, allowSilence = chancy), cutFrame), 0)
                    }
                    val barFrames = frameAt(d.endPulse) - frameAt(d.startPulse)
                    mixed = addAt(mixed, dropBar(kit, kitDir, d.keeps, d.drums, d.bedHits, bed, d.endPulse - d.startPulse, barFrames, bpm), cutFrame)
                    from = d.endPulse - startPulses[first]
                }
                val after = notes.filter { it.timePulses >= from }
                if (after.isNotEmpty()) mixed = addAt(mixed, KitPreview.renderNotes(kit, kitDir, after, stretchPulses, bpm, allowSilence = chancy), 0)
                mixed
            }
            if (bed != null) {
                for (i in range) {
                    if (!plan.sections[i].bass || PlanSection.BED_LANE in plan.sections[i].mutes) continue
                    val at = starts[i] - starts[first]
                    // A dropped section's bed plays up to its last bar, which the drop took over.
                    val d = dropAt[i]
                    val layer: Snip? = if (d == null) {
                        renderBed(bed, clips[i].lengthPulses, framesPerPulse)
                    } else {
                        val relStart = d.startPulse - startPulses[i]
                        val before = bedNotes(bed.clip, clips[i].lengthPulses).filter { it.timePulses < relStart }
                        if (before.isEmpty()) null else renderBed(bed, clips[i].lengthPulses, framesPerPulse, before)
                    }
                    if (layer == null) continue
                    // ...and no bed's tail rings into a bar that was dropped.
                    val cut = drops.map { frameAt(it.startPulse) - starts[first] }.filter { it > at }.minOrNull()
                    audio = addAt(audio, if (cut == null) layer else cutAt(layer, cut - at), at)
                }
            }
            // CLOSE and REVERSE CRASH: each section's own last bar, in this stretch's frames.
            for (i in range) {
                val s = plan.sections[i]
                if (!s.close && !s.reverseCrash) continue
                val barEnd = frameAt(startPulses[i + 1])
                val bar = BoundaryBar(
                    index = i,
                    from = frameAt(startPulses[i + 1] - clips[i].pulsesPerBar) - starts[first],
                    end = barEnd - starts[first],
                    pulses = clips[i].pulsesPerBar.toLong(),
                    last = i == plan.sections.lastIndex,
                    pullUpNext = i + 1 in turns,
                )
                audio = boundaryTransitions(kit, kitDir, audio, s, bar, bpm, transitionReports)
            }
            val next = range.last + 1
            if (next in turns) {
                val lastBeat = frameAt(startPulses[next] - Mpc3Clip.PULSES_PER_BEAT) - starts[first]
                audio = pullUpOver(audio, cut = lastBeat, end = starts[next] - starts[first])
            }
            starts[first] to audio
        }

        val frames = pieces.maxOf { (at, audio) -> at + audio.frameCount }
        val out = FloatArray(frames * 2)
        for ((at, audio) in pieces) {
            for (j in audio.samples.indices) out[at * 2 + j] += audio.samples[j]
        }
        // The song ends where the outro would end on its own: its bars and
        // its own ring-out, measured by rendering it alone (only its length
        // is used; the audio is the stretch's, chokes and all). The stretch's
        // own length is no guide: a long pad struck earlier in it rings on
        // inside the take, and would hold the tape stop back.
        val last = plan.sections.lastIndex
        val songEnd = starts[last] + KitPreview.renderNotes(kit, kitDir, unmuted[last].notes, unmuted[last].lengthPulses, bpm, allowSilence = chancy).frameCount
        val song = BeatTape.tapeStop(Snip(out.copyOf(songEnd * 2), 2, KitPreview.RATE))
        SongBus.ceiling(song.samples)
        val passes = plan.sections.indices.flatMap { i ->
            val clip = plan.sections[i].clip
            val one = clip.lengthPulses
            (0 until plan.sections[i].repeats).map { r ->
                val at = startPulses[i] + r * one
                // The clip's own bar (a three-four bar is three beats), counted in pulses and rounded once.
                val lines = (1 until clip.bars).map { b -> frameAt(at + b * clip.pulsesPerBar) }
                Pass(i, frameAt(at), frameAt(at + one), lines)
            }
        }
        return Mix(song, starts, passes, dropReports, transitionReports)
    }

    /**
     * Where one section's last bar is, in the frames of the stretch being
     * mixed: [from] up to [end] (the next section's downbeat), [pulses]
     * long. [last] is the song's last section, [pullUpNext] a pull-up into
     * the next one.
     */
    private class BoundaryBar(val index: Int, val from: Int, val end: Int, val pulses: Long, val last: Boolean, val pullUpNext: Boolean)

    /**
     * [section]'s CLOSE and REVERSE CRASH applied to [audio] over [bar], and
     * what each did added to [reports]. One transition holds a boundary: a
     * DROP on the section takes it before either (and was reported by its
     * own list), CLOSE before REVERSE CRASH, and the next section's pull-up
     * before all of them, so the rest are left off and say so.
     */
    private fun boundaryTransitions(kit: Kit, kitDir: File, audio: Snip, section: Section, bar: BoundaryBar, bpm: Float, reports: MutableList<Transition>): Snip {
        var out = audio
        var held = section.drop
        for (kind in listOfNotNull(CLOSE.takeIf { section.close }, REVERSE_CRASH.takeIf { section.reverseCrash })) {
            fun skip(why: String) { reports += Transition(bar.index, kind, false, "$why, so the last bar was left as it is") }
            when {
                held -> skip(if (section.drop) "DROP is on this section and takes the boundary" else "another transition on this section takes the boundary")
                bar.pullUpNext -> skip("the pull-up into the next section takes this boundary")
                kind == REVERSE_CRASH && bar.last -> skip("the last section has no next downbeat to land on")
                kind == CLOSE -> {
                    out = closeBar(out, bar.from, bar.end)
                    reports += Transition(bar.index, kind, true, "the last bar sweeps down from ${CLOSE_OPEN_HZ.roundToInt()} Hz to ${CLOSE_SHAPE_HZ.roundToInt()} Hz")
                    held = true
                }
                else -> {
                    val pad = crashPad(kit)
                    if (pad == null) {
                        skip("this kit has no crash or open-hat pad")
                    } else {
                        val hit = KitPreview.renderNotes(kit, kitDir, listOf(Mpc3Note(Mpc3Note.noteFor(pad.slot), 0, REVERSE_CRASH_VELOCITY)), bar.pulses, bpm)
                        out = reverseInto(out, hit, bar.end, bar.end - bar.from)
                        reports += Transition(bar.index, kind, true, "pad ${pad.slot} (${pad.displayName}) reversed to end on the next downbeat")
                        held = true
                    }
                }
            }
        }
        return out
    }

    /**
     * The pad REVERSE CRASH reverses: the lowest-numbered pad whose name says
     * "crash" (a crash is tagged as an open hat in this app, so its class
     * cannot tell it from a ride or a hat), else the lowest-numbered pad of
     * class [com.snipsnap.audio.DrumClass.HAT_OPEN]; null when the kit has
     * neither.
     */
    internal fun crashPad(kit: Kit): com.snipsnap.kit.KitPad? =
        kit.pads.filter { it.drumClass != com.snipsnap.audio.DrumClass.HAT_CLOSED && it.displayName.contains("crash", ignoreCase = true) }.minByOrNull { it.slot }
            ?: kit.pads.filter { it.drumClass == com.snipsnap.audio.DrumClass.HAT_OPEN }.minByOrNull { it.slot }

    /**
     * CLOSE: [seg] with frames [from] until [to] (clamped to its length)
     * run through a low-pass (`Dsp.TptSvf` by way of
     * [com.snipsnap.synth.PadFilter.sweep], since `Dsp` is internal to
     * :synth) whose cutoff falls exponentially from [CLOSE_OPEN_HZ] to [CLOSE_SHAPE_HZ] across
     * them, resonance [CLOSE_Q]. Every other frame is [seg]'s own, bit for
     * bit, and the length is unchanged. The filter is run over the
     * [CLOSE_WARMUP] frames before [from] at the open cutoff first, so
     * its state is the music's and the bar does not start with a click.
     */
    internal fun closeBar(seg: Snip, from: Int, to: Int): Snip {
        require(seg.channels == 2) { "the mixdown is stereo, got ${seg.channels} channel(s)" }
        val open = minOf(CLOSE_OPEN_HZ, seg.sampleRate * 0.45f)
        return com.snipsnap.synth.PadFilter.sweep(seg, from, to, open, CLOSE_SHAPE_HZ, 1f / CLOSE_Q, CLOSE_WARMUP)
    }

    /**
     * REVERSE CRASH: the first [maxFrames] frames of [hit] (a crash struck
     * once, with its ring-out), played backwards so that its last sample is
     * the frame before [end] and the hit's own attack lands on [end], the
     * next downbeat. Added into [seg]; nothing is added from [end] on, so
     * the song is no longer and the downbeat is where it was. A short ramp
     * up at the start keeps the cut end of the ring-out from clicking.
     */
    internal fun reverseInto(seg: Snip, hit: Snip, end: Int, maxFrames: Int): Snip {
        val len = minOf(hit.frameCount, maxFrames, end)
        if (len <= 0) return seg
        val rev = FloatArray(len * 2)
        val ramp = minOf(CUT_FADE, len)
        for (n in 0 until len) {
            val g = if (n < ramp) n.toFloat() / ramp else 1f
            val src = (len - 1 - n) * 2
            rev[n * 2] = hit.samples[src] * g
            rev[n * 2 + 1] = hit.samples[src + 1] * g
        }
        return addAt(seg, Snip(rev, 2, seg.sampleRate), end - len)
    }

    private const val CLOSE = "CLOSE"
    private const val REVERSE_CRASH = "REVERSE CRASH"

    /**
     * CLOSE's shape, in Hz: the cutoff the last bar starts at (wide open) and the one it ends at. These,
     * [CLOSE_Q] and the exponential path between them are shape choices, set by reasoning and not yet by ear:
     * they await the owner's listen on a phone.
     */
    private const val CLOSE_OPEN_HZ = 18_000f
    private const val CLOSE_SHAPE_HZ = 300f

    /** CLOSE's resonance: mild, so the sweep darkens without whistling. A shape choice awaiting the owner's ear. */
    private const val CLOSE_Q = 0.7f

    /** Frames run through the filter before CLOSE's bar, so it starts with the music's state: about 46 ms. */
    private const val CLOSE_WARMUP = 2048

    /** The velocity the reversed crash is struck at. A shape choice awaiting the owner's ear. */
    private const val REVERSE_CRASH_VELOCITY = 0.9f

    /** [KitPreview]'s own frame arithmetic, so a section boundary and a note inside it agree. */
    private fun framesPerPulse(bpm: Float): Double = 60.0 / bpm * KitPreview.RATE / Mpc3Clip.PULSES_PER_BEAT

    /**
     * [clip] played [repeats] times end to end. Each repeat starts one
     * clip-length later, in the clip's own meter: a three-four bar is
     * three beats, and tiling it by a four-four bar put every repeat a
     * beat per bar late.
     *
     * With [roll] each repeat rolls its own step chances ([StepChance], pass
     * = the repeat, keyed on [clip]'s own name), so a half-likely step can
     * sound in the first lap and not the second. Do it here, before the
     * caller renames the clip. [roll] false keeps every hit and its chance,
     * for an MPC file that rolls them itself.
     */
    internal fun tiled(clip: Mpc3Clip, repeats: Int, roll: Boolean = true): Mpc3Clip {
        val length = clip.lengthPulses
        fun lap(r: Int) = if (roll) StepChance.resolve(clip, r.toLong()) else clip
        if (repeats == 1) return lap(0)
        return clip.copy(
            bars = clip.bars * repeats,
            notes = (0 until repeats).flatMap { r ->
                lap(r).notes.map { it.copy(timePulses = it.timePulses + r * length) }
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

    internal class Bed(val sample: Snip, val rootMidi: Int, val clip: Mpc3Clip)

    /** One section's dropped bar: where it is in the song, what it keeps, and the events that bar holds (relative to its own start). */
    private class DropBar(
        val keeps: DropKeeps,
        val startPulse: Long,
        val endPulse: Long,
        val drums: List<Mpc3Note>,
        val bedHits: List<Mpc3Note>,
    )

    /**
     * The last bar of [clip] (a section, already tiled), which ends at
     * song pulse [endPulse], as a [DropBar]: the bed when [bed] is given
     * (the section has BASS on and the kit has a bed; the bar may then be
     * silent, which is what keeping only the bed of a bar with no bass
     * note in it means), else the kick when a kick pad is hit in the bar,
     * else null (nothing to keep, so the bar is left alone).
     */
    private fun dropBarOf(kit: Kit, clip: Mpc3Clip, endPulse: Long, bed: Bed?): DropBar? {
        val bar = clip.pulsesPerBar.toLong()
        val rel = clip.lengthPulses - bar
        fun inBar(n: Mpc3Note) = n.timePulses >= rel
        val drums = clip.notes.filter(::inBar).map { it.copy(timePulses = it.timePulses - rel) }
        val hits = bed?.let { b -> bedNotes(b.clip, clip.lengthPulses).filter(::inBar).map { it.copy(timePulses = it.timePulses - rel) } }.orEmpty()
        val keeps = when {
            bed != null -> DropKeeps.BED
            drums.any { isKick(kit, it) } -> DropKeeps.KICK
            else -> return null
        }
        return DropBar(keeps, endPulse - bar, endPulse, drums, hits)
    }

    private fun isKick(kit: Kit, note: Mpc3Note): Boolean =
        kit.pad(Mpc3Note.slotFor(note.note))?.drumClass == com.snipsnap.audio.DrumClass.KICK

    /**
     * DROP: the audio of exactly one bar, [frames] long, with only the
     * kick pad's hits ([DropKeeps.KICK], from [drums]) or only the bed's
     * notes ([DropKeeps.BED], from [bedHits]) in it. Both lists are the
     * bar's own events, their times counted from the bar's start; nothing
     * from before the bar can sound in it, because it is rendered from
     * these alone, not gated out of a mix. Deterministic: the same events
     * are the same audio. A note ringing past the bar's end stops with a
     * short fade at it (the bar never makes the section longer).
     */
    internal fun dropBar(
        kit: Kit,
        kitDir: File,
        keeps: DropKeeps,
        drums: List<Mpc3Note>,
        bedHits: List<Mpc3Note>,
        bed: Bed?,
        barPulses: Long,
        frames: Int,
        bpm: Float,
    ): Snip {
        val take = when (keeps) {
            DropKeeps.KICK -> {
                val kicks = drums.filter { isKick(kit, it) }
                if (kicks.isEmpty()) Snip(FloatArray(0), 2, KitPreview.RATE)
                else KitPreview.renderNotes(kit, kitDir, kicks, barPulses, bpm)
            }
            DropKeeps.BED -> {
                require(bed != null) { "a bed drop needs a bed" }
                renderBed(bed, barPulses, framesPerPulse(bpm), bedHits)
            }
        }
        val out = FloatArray(frames * 2)
        take.samples.copyInto(out, 0, 0, minOf(take.samples.size, out.size))
        return cutAt(Snip(out, 2, KitPreview.RATE), frames)
    }

    /** [snip] stopped at [frame] with a short ramp down into it: nothing past it, no click at it. */
    private fun cutAt(snip: Snip, frame: Int): Snip {
        val n = frame.coerceIn(0, snip.frameCount)
        val out = snip.samples.copyOf(n * 2)
        val ramp = minOf(CUT_FADE, n)
        for (k in 0 until ramp) {
            val g = (ramp - k).toFloat() / ramp
            val f = n - ramp + k
            out[f * 2] *= g
            out[f * 2 + 1] *= g
        }
        return Snip(out, 2, snip.sampleRate)
    }

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
    private fun renderBed(bed: Bed, sectionPulses: Long, framesPerPulse: Double, notes: List<Mpc3Note> = bedNotes(bed.clip, sectionPulses)): Snip {
        val cache = HashMap<Int, Snip?>()
        val voiced = notes.mapNotNull { note ->
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
            .flatMap { r -> StepChance.resolve(clip, r.toLong()).notes.map { it.copy(timePulses = it.timePulses + r * loop) } }
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
