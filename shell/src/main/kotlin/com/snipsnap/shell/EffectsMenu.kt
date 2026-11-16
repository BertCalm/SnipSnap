package com.snipsnap.shell

import com.snipsnap.kit.KitPad
import com.snipsnap.synth.BoardType
import kotlin.math.roundToInt

/**
 * "Change the sound", as data: every effect a pad's EFFECTS page offers, sorted by what you want
 * rather than by which family makes it, each with a plain name and one line saying what it does
 * (direction B of the November UX pass, docs/UX_PASS_2026_11.md).
 *
 * The owner's decisions (2026-11-15): the rename list in full, ENDLESS for DRONE, CUT TAIL,
 * DREAMY SPACE and OLD RECORDING after the Luthier check, nine groups, and a START HERE set at the
 * top for drums. The grouping and every line were checked against the DSP by Luthier at
 * `fec4631ef` (computed from the code, not heard).
 *
 * A language and order layer only. Every [Target] is the id the existing doors already take
 * (a segment, a pedal word, a board, an outside move), so a row in the list does exactly what its
 * chip does, and the names come from [PadSheet.displayLabel], [PadSheet.boardLabel] and
 * [PadSheet.moveLabel], one place for each word.
 */
object EffectsMenu {

    /** What a row in the list reaches: the same door its chip, pedal line, board or section reaches. */
    sealed interface Target {
        /** A segment on rows one to six ([PadSheet.ALL_SEGMENTS] minus the slate). */
        data class Segment(val id: String) : Target

        /** A slate pedal, by its word ([PadSheet.PEDAL_SEGMENTS]); its saved key is [com.snipsnap.synth.Pedals.byWord]'s. */
        data class Pedal(val word: String) : Target

        /** A board, staged on the board card until KEEP. */
        data class Board(val type: BoardType) : Target

        /** An outside move: out of the phone and back in. */
        data class Outside(val move: OutsideSheet.Move) : Target

        /** The per-pad Aquarium, a page of its own inside the box. */
        data object Chain : Target

        /** The SHAPE box: the MPC's own pad knobs. */
        data object Shape : Target
    }

    /** One row: what it reaches and the line under its name (sentence case, no full stop). */
    data class Entry(val target: Target, val line: String) {
        /** The name drawn on the row, in CAPS. */
        val name: String get() = nameFor(target)
    }

    /** A closed section of the list: its heading, and its entries in the order a drum pad wants them. */
    data class Group(val heading: String, val entries: List<Entry>) {
        /** The section's closed line: what is inside, so a closed section still says what it holds. */
        val summary: String get() = entries.joinToString(" · ") { it.name }
    }

    /** The name [target] draws. */
    fun nameFor(target: Target): String = when (target) {
        is Target.Segment -> PadSheet.displayLabel(target.id)
        is Target.Pedal -> PadSheet.displayLabel(target.word)
        is Target.Board -> PadSheet.boardLabel(target.type)
        is Target.Outside -> PadSheet.moveLabel(target.move)
        Target.Chain -> PadSheet.CHAIN_LABEL
        Target.Shape -> "SHAPE"
    }

    private fun seg(id: String, line: String) = Entry(Target.Segment(id), line)
    private fun pedal(word: String, line: String) = Entry(Target.Pedal(word), line)
    private fun board(type: BoardType, line: String) = Entry(Target.Board(type), line)
    private fun outside(move: OutsideSheet.Move, line: String) = Entry(Target.Outside(move), line)

    /** The heading over the drum starters at the top of the list. */
    const val START_HERE_HEADING = "START HERE"

    /** The closed section under the list that keeps today's chip rows, pedal row, MORE and COPY/PASTE: nothing deleted. */
    const val BUTTONS_HEADING = "ALL AS BUTTONS"

    /** That section's closed line: what is inside. */
    const val BUTTONS_HOLDS = "THE CHIP ROWS, PEDALS, MORE, COPY AND PASTE"

    /** The nine groups, top to bottom; then the two pages of their own ([PAGES]). */
    val GROUPS: List<Group> = listOf(
        Group(
            "HIT HARDER",
            listOf(
                seg("PUNCH", "Lets the hit snap through and adds a little grit"),
                pedal("FORGE", "Adds a thump and clips it loud and solid"),
                seg("SPIKE", "Sharpens the start of the hit"),
                pedal("SUSTAIN", "Evens the level so it rings out longer"),
                pedal("HUSH", "Cuts the tail off once it gets quiet"),
            ),
        ),
        Group(
            "DIRTY OR OLD",
            listOf(
                seg("CRUSH", "The grainy sound of an old 12-bit sampler"),
                seg("TAPE", "Soft, warm and a little wobbly, like cassette"),
                seg("DIRT", "The warm grit of an old drum machine"),
                pedal("DRIVE", "Overdrives it like a cranked guitar amp"),
                seg("VINYL", "Crackle, rumble and hiss under it"),
                seg(PadSheet.DUST, "Adds the hiss and room of a real recording"),
                seg("DUB", "Like a copy of a copy of a tape"),
            ),
        ),
        Group(
            "ECHO AND SPACE",
            listOf(
                pedal("ROOM", "Puts it in a drum room"),
                seg("SLAP", "One quick bounce right after the hit"),
                seg("WASH", "A big, washy reverb"),
                pedal("DELAY", "A trail of echoes that fade and darken"),
                pedal("REWIND", "The hit, then a backwards copy that swells up after it"),
                board(BoardType.VELOUR, "Chorus, echoes and grains in a room, through an amp"),
                board(BoardType.VELOUR_BLOOM, "Bigger room, more scattered grains, more amp"),
                outside(OutsideSheet.Move.ROOM, "Measures your room with a sweep and puts it in there"),
            ),
        ),
        Group(
            "MOVEMENT",
            listOf(
                pedal("FLANGE", "A jet-plane whoosh"),
                seg("PHASE", "A slow, swirling sweep"),
                board(BoardType.WIDE, "Tape warmth, chorus and a little reverb, in stereo"),
                seg("ENSEMBLE", "Thickens it into a slow, swirling chorus"),
                seg("SECTION", "Sounds like six players at once"),
                pedal("TREM", "The volume pulses"),
                pedal("VIBRATO", "The pitch wavers"),
                pedal("ROTARY", "A spinning organ speaker that speeds up"),
            ),
        ),
        Group(
            "FILTER AND WAH",
            listOf(
                seg("CONTOUR", "A synth filter that closes down after the hit"),
                pedal("TOUCH", "A wah that opens on the hit and closes as it fades"),
                pedal("WAH", "A wah-wah that sweeps on its own"),
                pedal("ROCK", "One wah up and back, then it rests"),
            ),
        ),
        Group(
            "IN TIME WITH THE BEAT",
            listOf(
                seg("ONBEAT", "Repeats that land on the beat, darker each time"),
                seg("WOBBLE", "A filter that opens and closes in time"),
                seg("ROLL", "Repeats the start of it in time, like a roll"),
                seg("GATE", "Chops it into a rhythm"),
                seg("PUMP", "Ducks on every beat and swells back up"),
            ),
        ),
        Group(
            "PITCH AND REVERSE",
            listOf(
                seg("FLIP", "Plays it backwards"),
                pedal("SUB", "Adds a gritty copy an octave below"),
                seg("PITCH", "Plays it slower and lower"),
                pedal("DIVE", "The pitch drops an octave and stays down"),
                seg("STOP", "Slows to a stop, like a turntable"),
                seg("START", "Spins up into the sound"),
                seg(PadSheet.TUNE, "Pulls a note into the kit's key (not for drums)"),
            ),
        ),
        Group(
            "SOFTER OR LONGER",
            listOf(
                seg("SKIM", "Takes the click off, keeps the thump"),
                seg("SWELL", "A reverse swell rises into the hit"),
                seg(PadSheet.TAIL, "Removes the hit, keeps the ring"),
                seg("GHOST", "Only the breath of it is left"),
                seg(PadSheet.SMEAR, "Slows and blurs the tone, keeps the attack"),
                seg("ETERNAL", "Keeps the hit and stretches its tail out for ages"),
                pedal("FADE IN", "Rises in from silence"),
            ),
        ),
        Group(
            "STRANGE",
            listOf(
                seg("RING", "Metallic, like a bell or a radio"),
                seg("BODY", "Gives it a ringing body in the kit's key"),
                board(BoardType.OBLITERATE, "An octave halo, fuzz and chopped fragments"),
                board(BoardType.RUSH, "Swells in, distorts, and echoes back a fifth higher"),
                outside(OutsideSheet.Move.REAMP, "Plays it out loud or through a pedal, records it back"),
            ),
        ),
    )

    /** The two that are pages of their own, after the groups. */
    val PAGES: List<Entry> = listOf(
        Entry(Target.Chain, "Six effects in one, with ready-made starting points"),
        Entry(Target.Shape, "How it starts and fades, and how bright it is"),
    )

    /** Every row, groups then pages, each once. */
    val ALL: List<Entry> get() = GROUPS.flatMap { it.entries } + PAGES

    /** The entry for [target], or an error naming it. */
    fun entryFor(target: Target): Entry = ALL.firstOrNull { it.target == target }
        ?: throw IllegalArgumentException("no row for $target in the effects list")

    /**
     * What [pad] carries now, by row: "ON · 70%", or "ON" for the rows with no amount (the chain, an
     * outside trip, SHAPE). A row absent from the map is off. Read from the same recipes the box strips
     * read ([PadSheetBoxes.lines]), so the list and the closed line never disagree.
     */
    fun onNow(pad: KitPad): Map<Target, String> {
        val out = LinkedHashMap<Target, String>()
        fun on(amount: Float) = "ON · ${(amount * 100).roundToInt()}%"
        val recipe = pad.recipe
        if (AquariumPadSheet.read(recipe) != null) out[Target.Chain] = "ON"
        BoardSheet.read(recipe)?.let { out[Target.Board(it.type)] = on(it.amount) }
        val smear = PadSheet.readSmear(recipe)
        val dust = PadSheet.readDust(recipe)
        smear?.let { out[Target.Segment(PadSheet.SMEAR)] = on(it) }
        dust?.let { out[Target.Segment(PadSheet.DUST)] = on(it.amount) }
        PadSheet.readPedals(recipe)?.let { board ->
            for (pick in PedalSlate.audible(board.picks)) {
                val word = pick.entry.word
                if (word in PadSheet.PEDAL_SEGMENTS) out[Target.Pedal(word)] = on(pick.amount)
            }
        }
        if (smear == null && dust == null) {
            PadSheet.read(recipe)?.let { applied ->
                val segment = applied.segment ?: return@let
                val target = if (segment in PadSheet.PEDAL_SEGMENTS) Target.Pedal(segment) else Target.Segment(segment)
                out.getOrPut(target) { on(applied.amount) }
            }
        }
        OutsideSheet.read(recipe)?.let { trip ->
            OutsideSheet.Move.entries.firstOrNull { it.name == trip.move }?.let { out[Target.Outside(it)] = "ON" }
        }
        if (pad.attack != null || pad.decay != null || pad.cutoff != null || pad.resonance != null) out[Target.Shape] = "ON"
        return out
    }

    /**
     * START HERE, for a drum pad: none of these refuses a short hit or needs the kit's tempo or key
     * (Luthier, 2026-11-15). The same entries as in their groups, not copies, so a line changes once.
     */
    val START_HERE: List<Entry> = listOf(
        Target.Segment("PUNCH"),
        Target.Pedal("FORGE"),
        Target.Segment("SPIKE"),
        Target.Pedal("ROOM"),
        Target.Segment("SLAP"),
        Target.Segment("CRUSH"),
        Target.Pedal("SUB"),
        Target.Segment("FLIP"),
    ).map(::entryFor)
}
