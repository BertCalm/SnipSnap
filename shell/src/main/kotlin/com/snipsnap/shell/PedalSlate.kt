package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.PedalBoard
import com.snipsnap.synth.PedalSide
import com.snipsnap.synth.Pedals
import com.snipsnap.synth.Treatments

/**
 * Row seven's slate, as data (docs/superpowers/specs/2026-10-28-pedal-slate-design.md).
 *
 * The slate is every pedal that passed its gate ([slate]). Row seven draws five of them, the
 * user's [Row], the same on every pad of every kit, then MORE, which lists the whole library
 * ([library]). A pad carries a board of up to [PedalBoard.MAX_PICKS] pedals: a tap on an unlit
 * pedal puts it on at 70, a tap on a lit one takes it off, and AMT moves the marked one. MORE
 * lights, with a count, when the pad carries pedals the row does not show.
 *
 * One law holds everywhere: **a lit pedal is a pedal you hear.** A pick at AMT 0, or one whose
 * every section a later pick on its side overrides (WAH under TOUCH: both set the wah), sounds
 * like nothing, so the slate never keeps or lights one ([audible]).
 *
 * Everything the screens decide is decided here, under test, because the app has no unit-test
 * source set: which five chips draw and which light ([card]), what MORE lists ([more]), what a
 * gesture does to the board ([change]), the door that renders it ([land]) and the line that says
 * so ([toast]). The row itself is kept in the app's preferences as [write]'s text under
 * [PREFS_KEY], the [MidiLearnStore] pattern, and [read] never throws.
 */
object PedalSlate {

    /** The row's width: five pedals, so the row and MORE fill the card's six places. */
    const val ROW_SIZE = 5

    const val VERSION = 1

    /** Where the app keeps [write]'s text, in the preferences every app-wide setting lives in. */
    const val PREFS_KEY = "pedals.row"

    /**
     * The owner's default five (2026-10-28), as saved keys: DRIVE, SUSTAIN, WAH, FORGE, DELAY.
     * Keys, not words, because a chip word can be renamed (COMP became SUSTAIN) while its key stays.
     */
    val DEFAULT_ROW: List<String> = listOf("driven", "compressed", "wahed", "forged", "delayed")

    /** Every pedal the row can draw, in library order: the gated entries that are not boards (a chip word is a character). */
    fun slate(): List<Pedals.Entry> = Pedals.gated().filter { !it.isBoard }

    /** Every entry MORE can put on a pad, in library order: the slate and any gated board. */
    fun library(): List<Pedals.Entry> = Pedals.gated()

    private fun onSlate(key: String): Boolean = slate().any { it.key == key }

    /** How many pedals a row holds: [ROW_SIZE], or the whole slate if it is smaller. */
    val rowSize: Int get() = minOf(ROW_SIZE, slate().size)

    /**
     * The app-wide row: [rowSize] distinct slate pedals, in the user's order. Positions matter:
     * PUT ON ROW replaces one place and leaves the others where they are.
     */
    data class Row(val keys: List<String>) {
        init {
            require(keys.size == rowSize) { "the row holds $rowSize pedals, got ${keys.size}" }
            require(keys.distinct().size == keys.size) { "a pedal is on the row twice: $keys" }
            for (key in keys) require(onSlate(key)) {
                "'$key' is not on the slate - it holds: ${slate().joinToString(", ") { it.key }}"
            }
        }

        /** The chip words, in row order. */
        val words: List<String> get() = keys.map { Pedals.byKey(it).word }
    }

    /** The row before the user changes it: [DEFAULT_ROW], repaired against the library this build carries. */
    val DEFAULT: Row get() = repair(DEFAULT_ROW)

    /**
     * Any list of keys made into a legal [Row], keeping every good key in its place. A key that is
     * missing, unknown, ungated, a board or already seen leaves a hole. Places past [rowSize] are cut,
     * and their good keys fill the holes first; then the default five, then the slate in library
     * order, each skipping what the row already holds. Idempotent, and total.
     */
    fun repair(keys: List<String?>): Row {
        val seen = mutableSetOf<String>()
        val good = keys.map { key -> key?.takeIf { onSlate(it) && seen.add(it) } }
        val places = good.take(rowSize).toMutableList()
        while (places.size < rowSize) places.add(null)
        val kept = places.filterNotNull().toSet()
        val fill = (good.drop(rowSize).filterNotNull() + DEFAULT_ROW + slate().map { it.key })
            .filter { onSlate(it) && it !in kept }.distinct().iterator()
        for (i in places.indices) if (places[i] == null) places[i] = fill.next()
        return Row(places.map { it!! })
    }

    /** The row as text for the preferences: versioned JSON of keys. */
    fun write(row: Row): String = Json.write(
        JsonValue.Obj(
            linkedMapOf(
                "version" to JsonValue.Num(VERSION.toDouble()),
                "row" to JsonValue.Arr(row.keys.map { JsonValue.Str(it) }),
            ),
        ),
    )

    /**
     * The row [write] saved, read forgivingly: no text, text that is not this format or another
     * version reads as [DEFAULT]; a key this build cannot use is repaired in place ([repair]).
     * Never throws. Never writes back: a pedal a build does not carry is forgotten only when the
     * user next changes the row.
     */
    fun read(text: String?): Row {
        if (text.isNullOrBlank()) return DEFAULT
        val keys = try {
            val root = Json.parse(text).obj()
            if ((root["version"] as? JsonValue.Num)?.value != VERSION.toDouble()) return DEFAULT
            val items = (root["row"] as? JsonValue.Arr)?.items ?: return DEFAULT
            items.map { (it as? JsonValue.Str)?.value }
        } catch (_: JsonException) {
            return DEFAULT
        } catch (_: IllegalArgumentException) {
            return DEFAULT
        }
        return repair(keys)
    }

    /**
     * PUT ON ROW: [incoming] takes [outgoing]'s place and every other place stays. Refuses a pedal
     * already on the row (it has no PUT ON ROW), a chip not on the row, and a pedal off the slate.
     */
    fun putOnRow(row: Row, incoming: String, outgoing: String): Row {
        require(onSlate(incoming)) { "'$incoming' is not on the slate" }
        require(incoming !in row.keys) { "'$incoming' is already on the row" }
        val at = row.keys.indexOf(outgoing)
        require(at >= 0) { "'$outgoing' is not on the row: ${row.keys}" }
        return Row(row.keys.toMutableList().also { it[at] = incoming })
    }

    // ---- what a pad carries ----

    /**
     * The picks of [picks] a listener hears, in their order: AMT above 0, and the last pick on its
     * side to set at least one of its sections. [PedalBoard.chainFor] folds a side in pick order
     * and a later pick replaces a section whole, so WAH then TOUCH on one side plays TOUCH alone,
     * and DRIVE then RIG plays RIG's amp; dropping the silent pick changes no sample.
     */
    fun audible(picks: List<PedalBoard.Pick>): List<PedalBoard.Pick> {
        val live = picks.filter { it.amount > 0f }
        return live.filter { pick ->
            pick.entry.sections.any { section ->
                live.lastOrNull { it.side == pick.side && it.entry.chain.section(section) != null } === pick
            }
        }
    }

    /**
     * The pedals a pad's [recipe] carries, as the slate reads them: the [audible] picks of its
     * `pedals` board ([PadSheet.readPedals]), or an old single-chip recipe whose name is a pedal key
     * read as a one-pick board on the pedal's own side ([PedalBoard.fromCharacter], the library
     * design's rule). The old chip counts only while its stored chain is the pedal's chain today at
     * its AMT: a chip made before its character changed (SUSTAIN was SQUASH until 2026-10-24) would
     * re-render as something else, so it reads as an older treatment instead. Null for every other
     * recipe: an era, any other character, a keyed, SMEAR or DUST is not pedals.
     */
    fun boardOf(recipe: JsonValue.Obj?): PedalBoard? {
        PadSheet.readPedals(recipe)?.let { board ->
            return audible(board.picks).takeIf { it.isNotEmpty() }?.let(::PedalBoard)
        }
        val applied = PadSheet.read(recipe) ?: return null
        val chip = applied.treatment as? PadSheet.Treatment.Character ?: return null
        if (Pedals.entries.none { it.key == chip.name && !it.isBoard } || applied.amount <= 0f) return null
        val stored: FxChain = try {
            PadRecipe.fromJsonValue(recipe!!).fx ?: return null
        } catch (_: Exception) {
            return null
        }
        return try {
            if (stored != Treatments.chain(chip.name, applied.amount)) return null
            PedalBoard.fromCharacter(chip.name, applied.amount)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * The pick AMT moves: [lastTapped] while it is on the board (the screen remembers the last pedal
     * tapped, on the row or in MORE), otherwise the newest pick (picks are saved in the order they
     * went on). Null with no board.
     */
    fun marked(board: PedalBoard?, lastTapped: String?): String? {
        if (board == null) return null
        return lastTapped?.takeIf { key -> board.picks.any { it.key == key } } ?: board.picks.last().key
    }

    // ---- the card ----

    /** One of row seven's pedal chips: [lit] when the pad carries it, [marked] when AMT moves it. */
    data class Chip(val word: String, val key: String, val lit: Boolean, val marked: Boolean)

    /**
     * Row seven for one pad.
     *
     * - [chips]: the row's five, in row order.
     * - [moreCount]: the pedals on the pad that the row does not show, slate or not. MORE's label is
     *   [Copy.pedalMore] of it, and [moreLit] says MORE should light.
     * - [moreMarked]: the marked pedal sits behind MORE, so MORE carries the mark.
     * - [marked], [amount]: the pick AMT moves and its AMT. Both are null when the pad carries no
     *   pedals, and the screen's AMT keeps its old target. [Copy.pedalAmt] of the marked word is the
     *   stepper's label, so it says which pedal it moves.
     */
    data class Card(
        val chips: List<Chip>,
        val moreCount: Int,
        val moreMarked: Boolean,
        val marked: String?,
        val amount: Float?,
    ) {
        val moreLit: Boolean get() = moreCount > 0
    }

    fun card(row: Row, recipe: JsonValue.Obj?, lastTapped: String?): Card {
        val board = boardOf(recipe)
        val on = board?.picks?.map { it.key }?.toSet().orEmpty()
        val mark = marked(board, lastTapped)
        return Card(
            chips = row.keys.map { Chip(Pedals.byKey(it).word, it, it in on, it == mark) },
            moreCount = (on - row.keys.toSet()).size,
            moreMarked = mark != null && mark !in row.keys,
            marked = mark,
            amount = board?.picks?.firstOrNull { it.key == mark }?.amount,
        )
    }

    // ---- MORE ----

    /**
     * One line of MORE's list.
     *
     * - [on]: the pad carries it, and [amount] and [side] say how. ON/OFF and AMT draw on every line
     *   the pad carries.
     * - [onRow]: row seven draws it.
     * - [onSlate]: a pedal the row can draw (not a board).
     * - [inLibrary]: MORE can put it on. False only for a pick the library does not offer (an ungated
     *   pedal or board from the CLI or an old kit), whose line can be moved and taken off but, once
     *   off, is gone from MORE.
     * - [marked]: AMT moves it.
     * - [sides]: where it may sit; two means it has a before/after-the-amp toggle.
     */
    data class Listed(
        val word: String,
        val key: String,
        val on: Boolean,
        val onRow: Boolean,
        val onSlate: Boolean,
        val inLibrary: Boolean,
        val marked: Boolean,
        val amount: Float?,
        val side: PedalSide?,
        val sides: List<PedalSide>,
    ) {
        /** PUT ON ROW draws on a slate pedal the row does not show. */
        val canPutOnRow: Boolean get() = onSlate && !onRow

        /** The before/after-the-amp toggle draws on a movable pedal the pad carries. */
        val canFlip: Boolean get() = on && sides.size > 1
    }

    /** MORE's list: the whole library in library order, then any pick on the pad the library does not offer. */
    fun more(row: Row, recipe: JsonValue.Obj?, lastTapped: String?): List<Listed> {
        val board = boardOf(recipe)
        val mark = marked(board, lastTapped)
        val libraryKeys = library().map { it.key }
        val offLibrary = board?.picks?.map { it.entry }?.filter { it.key !in libraryKeys }.orEmpty()
        return (library() + offLibrary).map { e ->
            val pick = board?.picks?.firstOrNull { it.key == e.key }
            Listed(
                word = e.word,
                key = e.key,
                on = pick != null,
                onRow = e.key in row.keys,
                onSlate = onSlate(e.key),
                inLibrary = e.key in libraryKeys,
                marked = e.key == mark,
                amount = pick?.amount,
                side = pick?.side,
                sides = e.sides,
            )
        }
    }

    // ---- the board as a chain (docs/superpowers/specs/2026-10-30-pad-pedalboard-card-design.md) ----

    /** One pedal on the drawn chain: a pick the pad carries, where the sound reaches it. */
    data class Link(
        val word: String,
        val key: String,
        val amount: Float,
        val side: PedalSide,
        val marked: Boolean,
        val sides: List<PedalSide>,
    ) {
        /** It may move to the other side of the amp ([Gesture.Flip]). */
        val canFlip: Boolean get() = sides.size > 1
    }

    /** One pass of the board: [side]'s pedals in the order the sound meets them. */
    data class Pass(val side: PedalSide, val links: List<Link>)

    /**
     * The pad's pedals in the order the sound passes through them: the PRE pass, the AMP pass, then
     * [aroundTheAmp]'s pad amp when there is one, then the POST pass ([PedalBoard.processAround]). All
     * three passes are always listed, empty or not, so the card can draw an empty side as a place.
     */
    data class Chain(val passes: List<Pass>, val aroundTheAmp: Boolean) {
        val links: List<Link> get() = passes.flatMap { it.links }
        val isEmpty: Boolean get() = links.isEmpty()
    }

    /**
     * Where a pick sits inside its pass. [PedalBoard.chainFor] folds a side into one chain, and a
     * chain runs its sections in the rack's fixed order ([FxChain.SECTION_NAMES]), so within a side
     * the sound meets the pedals in rack order, whatever order they were tapped in. A pedal's place is
     * its first section ([Pedals.Entry.sections] is in rack order); a tie keeps tap order.
     */
    private fun rackPlace(pick: PedalBoard.Pick): Int =
        pick.entry.sections.firstOrNull()?.let { FxChain.SECTION_NAMES.indexOf(it) } ?: Int.MAX_VALUE

    /**
     * [recipe]'s [audible] pedals ([boardOf]) as the chain the card draws, with [lastTapped]'s mark
     * ([marked]). [aroundTheAmp] says whether the pad's own amp sits between the AMP and POST passes:
     * [KitBuilderModel.pedalsAroundTheAmp] answers it for a pad on disk. An empty chain for a pad
     * with no pedals.
     */
    fun chain(recipe: JsonValue.Obj?, lastTapped: String?, aroundTheAmp: Boolean): Chain {
        val board = boardOf(recipe)
        val mark = marked(board, lastTapped)
        val picks = board?.picks.orEmpty()
        val passes = listOf(PedalSide.PRE, PedalSide.AMP, PedalSide.POST).map { side ->
            Pass(
                side,
                picks.withIndex()
                    .filter { it.value.side == side }
                    .sortedWith(compareBy({ rackPlace(it.value) }, { it.index }))
                    .map { (_, p) -> Link(p.entry.word, p.key, p.amount, p.side, p.key == mark, p.entry.sides) },
            )
        }
        return Chain(passes, aroundTheAmp)
    }

    /**
     * Whether [recipe]'s pedals were rendered in front of the pad's own amp, read from the recipe
     * alone: [KitBuilderModel.pedalPad] keeps the patch beside the board only when it rendered the
     * board around the patch's landing chain, and that chain plays only when it is not a bypass.
     * Null when the recipe carries no board: where the next pedal goes then depends on whether the
     * file is still the patch's render, which only [KitBuilderModel.pedalsAroundTheAmp] can check.
     */
    fun aroundTheAmp(recipe: JsonValue.Obj?): Boolean? {
        if (PadSheet.readPedals(recipe) == null) return null
        val parsed = try {
            PadRecipe.fromJsonValue(recipe!!)
        } catch (_: Exception) {
            return false
        }
        return parsed.patch != null && parsed.fx?.isBypass == false
    }

    // ---- gestures ----

    /**
     * What the user did to the pad's pedals. A tap on a chip sends [On] or [Off] by what the card
     * showed, not a toggle, so a second tap that reaches the door before the first has landed does
     * nothing instead of undoing it.
     */
    sealed interface Gesture {
        val key: String

        /** An unlit pedal tapped, on the row or in MORE: on at [PadSheet.DEFAULT_AMOUNT], on its own side. */
        data class On(override val key: String) : Gesture

        /** A lit pedal tapped: off. */
        data class Off(override val key: String) : Gesture

        /** AMT on the marked pick. 0 takes it off. */
        data class Amount(override val key: String, val amount: Float) : Gesture

        /** The before/after-the-amp toggle on a movable pick, to the side the card offered ([to]), so a late second tap does nothing. */
        data class Flip(override val key: String, val to: PedalSide) : Gesture
    }

    /** What a gesture does to a board, before any audio is touched. */
    sealed interface Change {
        /** The pad should carry [board]. [replaced]: picks this gesture silenced, so they came off (TOUCH replacing WAH). */
        data class Render(val board: PedalBoard, val replaced: List<String> = emptyList()) : Change

        /** The last pick came off: the pad should be itself again, as NONE leaves it. */
        object Clear : Change

        /** A pedal past [PedalBoard.MAX_PICKS]: nothing changes. */
        object Full : Change

        /** The board already is what the gesture asks (a pedal already on, or already off). */
        object Same : Change
    }

    /**
     * The pure rule.
     *
     * - [Gesture.On] puts the pedal on after the others at [PadSheet.DEFAULT_AMOUNT], on its own side.
     *   It goes on at 70, not at the AMT on screen, because the AMT on screen is the marked pedal's:
     *   copying it would be arbitrary, and the habit "set AMT, then tap" would move the old pedal.
     * - [Gesture.Off] takes the pedal off.
     * - [Gesture.Amount] replaces the pick's AMT in place; 0 takes it off.
     * - [Gesture.Flip] moves the pick to [Gesture.Flip.to] and to the end of the order, so it wins any
     *   section it shares there.
     *
     * After each, the board keeps only its [audible] picks: a pedal that would be silenced comes off
     * and is named in [Change.Render.replaced] (TOUCH on a pad with WAH on the same side replaces
     * WAH: one wah section per side). More than [PedalBoard.MAX_PICKS] is [Change.Full]; the last pick
     * off is [Change.Clear]. A gesture the board already satisfies is [Change.Same], and so is AMT or a
     * flip that arrives for a pick no longer on the pad (a late commit from a screen that had not
     * caught up). Refuses (in words) putting on what the library does not offer, an AMT that is not a
     * number, and a side the pedal cannot take.
     */
    fun change(board: PedalBoard?, gesture: Gesture): Change {
        val picks = board?.picks.orEmpty()
        val at = picks.indexOfFirst { it.key == gesture.key }
        fun settle(next: List<PedalBoard.Pick>): Change {
            val kept = audible(next)
            if (kept.isEmpty()) return Change.Clear
            if (kept.size > PedalBoard.MAX_PICKS) return Change.Full
            val replaced = picks.map { it.key }.filter { key -> key != gesture.key && kept.none { it.key == key } }
            return Change.Render(PedalBoard(kept), replaced)
        }
        return when (gesture) {
            is Gesture.On -> {
                if (at >= 0) return Change.Same
                require(library().any { it.key == gesture.key }) { "'${gesture.key}' is not in the library MORE offers" }
                settle(picks + PedalBoard.Pick(gesture.key, PadSheet.DEFAULT_AMOUNT))
            }
            is Gesture.Off -> if (at < 0) Change.Same else settle(picks.filterIndexed { i, _ -> i != at })
            is Gesture.Amount -> {
                require(gesture.amount.isFinite()) { "AMT is a number, got ${gesture.amount}" }
                if (at < 0) return Change.Same
                if (gesture.amount <= 0f) return settle(picks.filterIndexed { i, _ -> i != at })
                val moved = picks[at].copy(amount = gesture.amount.coerceAtMost(1f))
                settle(picks.toMutableList().also { it[at] = moved })
            }
            is Gesture.Flip -> {
                val entry = Pedals.byKey(gesture.key)
                require(gesture.to in entry.sides) {
                    "${entry.word} cannot sit on the ${gesture.to.id} side - it sits ${entry.sides.joinToString(" or ") { it.id }}"
                }
                if (at < 0 || picks[at].side == gesture.to) return Change.Same
                settle(picks.filterIndexed { i, _ -> i != at } + picks[at].copy(side = gesture.to))
            }
        }
    }

    // ---- the door ----

    /** What [land] did. */
    sealed interface Landed {
        /**
         * The pad now carries [board]. [replaced]: pedals this gesture silenced, so they came off.
         * [stacked]: the pad carried another treatment the bin could not restore, and the board went
         * on top of it.
         */
        data class Applied(val board: PedalBoard, val stacked: Boolean, val replaced: List<String> = emptyList()) : Landed

        /** The last pedal came off: every file came back out of the bin and the pad is itself again. */
        object Cleared : Landed

        /** The pad already was what the gesture asked; nothing was touched. */
        object Unchanged : Landed

        /** The pad's sample is not the one the screen showed (it changed underneath); nothing was touched. */
        object Gone : Landed

        /** A pedal past the board's limit; nothing was touched. */
        object Full : Landed

        /** A printed board pad (WIDE, RUSH, AQUARIUM), named by [label]; nothing was touched. */
        data class Printed(val label: String) : Landed

        /** Ghost layers postdate the treatment, so the bin cannot restore the pad whole; nothing was touched. */
        object GhostsPostdate : Landed

        /**
         * The pad carries pedals and the bin holds no earlier take of it (the bin keeps a take 30 days
         * after it went in, then lets it go): the board re-renders whole from the original, so
         * changing it would play every pedal already in the audio twice. Nothing was touched.
         */
        object NotBinned : Landed
    }

    /**
     * The door the sheet calls for every pedal gesture, inside the screen's fresh-kit block. It
     * reads the pad as it is now (the screen's copy can be stale by the time the work runs) and does
     * nothing when [sampleFile], the file the screen showed, is no longer the pad's. It applies
     * [change], restores through the bin when it can ([KitBuilderModel.unEraPad], as a chip tap
     * does, so pedals never stack), then renders through [KitBuilderModel.pedalPad] or, on
     * [Change.Clear], leaves the pad restored. A pad with another treatment and no original in the
     * bin takes the board on top and says so ([Landed.Applied.stacked]), as a chip does; a pad whose
     * own pedals the bin cannot restore refuses ([Landed.NotBinned]). Every refusal comes before any
     * file moves, and a pad that cannot change at all says so before a full board does, so the full
     * board's advice (tap one off) is never refused on the next tap. Throws for a slot with no pad
     * and for the refusals [change] makes in words.
     */
    fun land(model: KitBuilderModel, slot: Int, gesture: Gesture, sampleFile: String? = null): Landed {
        val pad = model.kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        if (sampleFile != null && pad.sampleFile != sampleFile) return Landed.Gone
        if (PrintedPads.has(pad.recipe)) return Landed.Printed(PrintedPads.label(pad.recipe))
        val before = boardOf(pad.recipe)
        val change = change(before, gesture)
        if (change == Change.Same) return Landed.Unchanged
        val state = PadSheet.unTreatState(pad, model.binContents().map { it.originalName }.toSet())
        if (state == PadSheet.UnTreat.GHOSTS_POSTDATE) return Landed.GhostsPostdate
        if (state == PadSheet.UnTreat.NOT_BINNED && before != null) return Landed.NotBinned
        if (change == Change.Full) return Landed.Full
        val stacked = state == PadSheet.UnTreat.NOT_BINNED
        if (state == PadSheet.UnTreat.READY) model.unEraPad(slot)
        return when (change) {
            is Change.Render -> {
                model.pedalPad(slot, change.board)
                Landed.Applied(change.board, stacked, change.replaced)
            }
            else -> Landed.Cleared
        }
    }

    private fun wordOf(key: String): String = Pedals.entries.firstOrNull { it.key == key }?.word ?: key.uppercase()

    /**
     * The line that says what [land] did with [gesture] on the pad named [pad]: a pedal on (in place
     * of the one it silenced, or on top of an older treatment), off and which pedal AMT moves now,
     * its AMT, its side, the pad itself again, or why nothing changed. Null when nothing happened
     * and there is nothing to say ([Landed.Unchanged]).
     */
    fun toast(gesture: Gesture, landed: Landed, pad: String): String? {
        val word = wordOf(gesture.key)
        return when (landed) {
            is Landed.Applied -> {
                val pick = landed.board.picks.firstOrNull { it.key == gesture.key }
                val replaced = landed.replaced.map(::wordOf)
                when {
                    landed.stacked -> Copy.treatedStacked(word, pad)
                    pick == null -> Copy.pedalOff(word, pad, wordOf(landed.board.picks.last().key))
                    gesture is Gesture.Flip -> Copy.pedalSide(word, pick.side == PedalSide.PRE, pad, replaced)
                    replaced.isNotEmpty() -> Copy.pedalReplaced(word, replaced, pad)
                    else -> Copy.treated(word, pad)
                }
            }
            Landed.Cleared -> Copy.unTreated(pad)
            Landed.Unchanged -> null
            Landed.Gone -> Copy.BIN_ITEM_GONE
            Landed.Full -> Copy.PEDALS_FULL
            is Landed.Printed -> Copy.pedalsPrinted(landed.label)
            Landed.GhostsPostdate -> Copy.RETREAT_REFUSED
            Landed.NotBinned -> Copy.PEDALS_NOT_BINNED
        }
    }
}
