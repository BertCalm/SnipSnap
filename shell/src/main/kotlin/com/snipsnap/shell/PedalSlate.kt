package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.PedalBoard
import com.snipsnap.synth.PedalSide
import com.snipsnap.synth.Pedals

/**
 * Row seven's slate, as data (docs/superpowers/specs/2026-10-28-pedal-slate-design.md).
 *
 * The slate is every pedal that passed its gate ([slate]). Row seven draws five of them, the
 * user's [Row], the same on every pad of every kit, then MORE, which lists the whole slate.
 * A pad carries a board of up to [PedalBoard.MAX_PICKS] pedals: a tap on an unlit pedal puts
 * it on, a tap on a lit one takes it off, and AMT moves the marked one. MORE lights, with a
 * count, when the pad carries pedals the row does not show.
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

    /** Every pedal the sheet can put on a pad, in library order: the gated entries that are not boards. */
    fun slate(): List<Pedals.Entry> = Pedals.gated().filter { !it.isBoard }

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
     * missing, unknown, ungated, a board or already seen leaves a hole; the list is cut or padded to
     * [rowSize]; the holes fill in place order from [DEFAULT_ROW], then from the slate in library
     * order, skipping what the row already holds. Idempotent, and total.
     */
    fun repair(keys: List<String?>): Row {
        val seen = mutableSetOf<String>()
        val places: MutableList<String?> = keys.take(rowSize).map { key ->
            key?.takeIf { onSlate(it) && seen.add(it) }
        }.toMutableList()
        while (places.size < rowSize) places.add(null)
        val fill = (DEFAULT_ROW + slate().map { it.key }).filter { onSlate(it) }.distinct()
            .filter { it !in seen }.iterator()
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
     * The pedals a pad's [recipe] carries: its `pedals` board ([PadSheet.readPedals]), or an old
     * single-chip recipe whose name is a pedal key read as a one-pick board on the pedal's own side
     * ([PedalBoard.fromCharacter], the library design's rule). Null for every other recipe: an era,
     * any other character, a keyed, SMEAR or DUST is not pedals.
     */
    fun boardOf(recipe: JsonValue.Obj?): PedalBoard? {
        PadSheet.readPedals(recipe)?.let { return it }
        val applied = PadSheet.read(recipe) ?: return null
        val chip = applied.treatment as? PadSheet.Treatment.Character ?: return null
        if (Pedals.entries.none { it.key == chip.name && !it.isBoard }) return null
        return try {
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
     *   pedals, and the screen's AMT keeps its old target.
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
     * - [on]: the pad carries it, and [amount] and [side] say how.
     * - [onRow]: row seven draws it.
     * - [onSlate]: false only for a pick the slate does not hold (a board or an ungated pedal from
     *   the CLI or an old kit). Such a line can only be taken off.
     * - [marked]: AMT moves it.
     * - [sides]: where it may sit; two means it has a before/after-the-amp toggle.
     */
    data class Listed(
        val word: String,
        val key: String,
        val on: Boolean,
        val onRow: Boolean,
        val onSlate: Boolean,
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

    /** MORE's list: the whole slate in library order, then any pick on the pad the slate does not hold. */
    fun more(row: Row, recipe: JsonValue.Obj?, lastTapped: String?): List<Listed> {
        val board = boardOf(recipe)
        val mark = marked(board, lastTapped)
        val slateKeys = slate().map { it.key }
        val offSlate = board?.picks?.map { it.entry }?.filter { it.key !in slateKeys }.orEmpty()
        return (slate() + offSlate).map { e ->
            val pick = board?.picks?.firstOrNull { it.key == e.key }
            Listed(
                word = e.word,
                key = e.key,
                on = pick != null,
                onRow = e.key in row.keys,
                onSlate = e.key in slateKeys,
                marked = e.key == mark,
                amount = pick?.amount,
                side = pick?.side,
                sides = e.sides,
            )
        }
    }

    // ---- gestures ----

    /** What the user did to the pad's pedals. */
    sealed interface Gesture {
        val key: String

        /** A tap on a pedal, on the row or in MORE: on if it is off, off if it is on. [amount] is the AMT on screen. */
        data class Tap(override val key: String, val amount: Float) : Gesture

        /** AMT on the marked pick. 0 takes it off: a lit chip always sounds. */
        data class Amount(override val key: String, val amount: Float) : Gesture

        /** The before/after-the-amp toggle on a movable pick. */
        data class Flip(override val key: String) : Gesture
    }

    /** What a gesture does to a board, before any audio is touched. */
    sealed interface Change {
        /** The pad should carry [board]. */
        data class Render(val board: PedalBoard) : Change

        /** The last pick came off: the pad should be itself again, as NONE leaves it. */
        object Clear : Change

        /** A pedal past [PedalBoard.MAX_PICKS]: nothing changes. */
        object Full : Change
    }

    /**
     * The pure rule. A tap on a pedal the board lacks puts it on at the end, on its own side, at
     * [Gesture.Tap.amount], or at [PadSheet.DEFAULT_AMOUNT] when that reads 0 so a tap is always
     * heard; on a board already holding [PedalBoard.MAX_PICKS] it is [Change.Full]. A tap on a
     * pedal the board holds takes it off. AMT replaces the pick's amount in place, and 0 takes it
     * off. Taking the last pick off is [Change.Clear]. Refuses (in words) a pedal off the slate put
     * on, AMT or a flip on a pick the board does not hold, and a flip on a pedal that cannot move.
     */
    fun change(board: PedalBoard?, gesture: Gesture): Change {
        val picks = board?.picks.orEmpty()
        val at = picks.indexOfFirst { it.key == gesture.key }
        fun without(): Change = picks.filterIndexed { i, _ -> i != at }
            .let { if (it.isEmpty()) Change.Clear else Change.Render(PedalBoard(it)) }
        return when (gesture) {
            is Gesture.Tap -> when {
                at >= 0 -> without()
                else -> {
                    require(onSlate(gesture.key)) { "'${gesture.key}' is not on the slate" }
                    require(gesture.amount.isFinite()) { "AMT is a number, got ${gesture.amount}" }
                    if (picks.size >= PedalBoard.MAX_PICKS) return Change.Full
                    val amount = if (gesture.amount <= 0f) PadSheet.DEFAULT_AMOUNT else gesture.amount.coerceAtMost(1f)
                    Change.Render(PedalBoard(picks + PedalBoard.Pick(gesture.key, amount)))
                }
            }
            is Gesture.Amount -> {
                require(at >= 0) { "'${gesture.key}' is not on this pad" }
                require(gesture.amount.isFinite()) { "AMT is a number, got ${gesture.amount}" }
                if (gesture.amount <= 0f) return without()
                val pick = picks[at]
                Change.Render(PedalBoard(picks.toMutableList().also { it[at] = pick.copy(amount = gesture.amount.coerceAtMost(1f)) }))
            }
            is Gesture.Flip -> {
                require(at >= 0) { "'${gesture.key}' is not on this pad" }
                val pick = picks[at]
                val other = pick.entry.sides.firstOrNull { it != pick.side }
                    ?: throw IllegalArgumentException("${pick.entry.word} cannot move - it sits ${pick.side.id}")
                Change.Render(PedalBoard(picks.toMutableList().also { it[at] = pick.copy(side = other) }))
            }
        }
    }

    // ---- the door ----

    /** What [land] did. */
    sealed interface Landed {
        /** The pad now carries [board]. [stacked]: the pad carried another treatment the bin could not restore, and the board went on top of it. */
        data class Applied(val board: PedalBoard, val stacked: Boolean) : Landed

        /** The last pedal came off: every file came back out of the bin and the pad is itself again. */
        object Cleared : Landed

        /** A pedal past the board's limit; nothing was touched. */
        object Full : Landed

        /** A printed board pad (WIDE, RUSH, AQUARIUM), named by [label]; nothing was touched. */
        data class Printed(val label: String) : Landed

        /** Ghost layers postdate the treatment, so the bin cannot restore the pad whole; nothing was touched. */
        object GhostsPostdate : Landed

        /**
         * The pad carries pedals and the bin holds no earlier take of it: the board re-renders whole,
         * so changing it would play every pedal already in the audio twice. Nothing was touched.
         */
        object NotBinned : Landed
    }

    /**
     * The door the sheet calls for every pedal gesture, inside the screen's fresh-kit block. It
     * reads the pad as it is now (the screen's copy can be stale by the time the work runs), applies
     * [change], restores through the bin when it can ([KitBuilderModel.unEraPad], as a chip tap
     * does, so pedals never stack), then renders through [KitBuilderModel.pedalPad] or, on
     * [Change.Clear], leaves the pad restored. A pad with another treatment and no original in the
     * bin takes the board on top and says so ([Landed.Applied.stacked]), as a chip does; a pad whose
     * own pedals the bin cannot restore refuses ([Landed.NotBinned]). Throws for a slot with no pad
     * and for the refusals [change] makes in words.
     */
    fun land(model: KitBuilderModel, slot: Int, gesture: Gesture): Landed {
        val pad = model.kit.pad(slot) ?: throw IllegalArgumentException("no pad on slot $slot")
        if (PrintedPads.has(pad.recipe)) return Landed.Printed(PrintedPads.label(pad.recipe))
        val before = boardOf(pad.recipe)
        val change = change(before, gesture)
        if (change is Change.Full) return Landed.Full
        var stacked = false
        when (PadSheet.unTreatState(pad, model.binContents().map { it.originalName }.toSet())) {
            PadSheet.UnTreat.READY -> model.unEraPad(slot)
            PadSheet.UnTreat.GHOSTS_POSTDATE -> return Landed.GhostsPostdate
            PadSheet.UnTreat.NOT_BINNED -> if (before != null) return Landed.NotBinned else stacked = true
            PadSheet.UnTreat.NOTHING -> Unit
        }
        return when (change) {
            is Change.Render -> {
                model.pedalPad(slot, change.board)
                Landed.Applied(change.board, stacked)
            }
            Change.Clear -> Landed.Cleared
            Change.Full -> Landed.Full
        }
    }

    /**
     * The line that says what [land] did with [gesture] on the pad named [pad]: a pedal on (or on
     * top of an older treatment), off, its AMT, its side, the pad itself again, or why nothing
     * changed.
     */
    fun toast(gesture: Gesture, landed: Landed, pad: String): String {
        val word = Pedals.entries.firstOrNull { it.key == gesture.key }?.word ?: gesture.key.uppercase()
        return when (landed) {
            is Landed.Applied -> {
                val pick = landed.board.picks.firstOrNull { it.key == gesture.key }
                when {
                    landed.stacked -> Copy.treatedStacked(word, pad)
                    pick == null -> Copy.pedalOff(word, pad)
                    gesture is Gesture.Flip -> Copy.pedalSide(word, pick.side == PedalSide.PRE, pad)
                    else -> Copy.treated(word, pad)
                }
            }
            Landed.Cleared -> Copy.unTreated(pad)
            Landed.Full -> Copy.PEDALS_FULL
            is Landed.Printed -> Copy.pedalsPrinted(landed.label)
            Landed.GhostsPostdate -> Copy.RETREAT_REFUSED
            Landed.NotBinned -> Copy.PEDALS_NOT_BINNED
        }
    }
}
