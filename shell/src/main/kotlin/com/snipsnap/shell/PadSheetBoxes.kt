package com.snipsnap.shell

import com.snipsnap.kit.KitPad
import com.snipsnap.kit.PadShape
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The PAD SHEET's workshop, folded (Pad Sheet v2, Direction A): every
 * card below the everyday controls is a group box with a pixel legend,
 * closed by default, its one-line strip reading what the pad already
 * carries - so a closed sheet fits one screen and tells the pad's story
 * at a glance. One box open at a time; the open one is remembered per
 * kit, not per pad, so the next pad opens on the same bench.
 *
 * The summaries come from what the recipes already say ([PadSheet.read],
 * [PadSheet.readPedals], [MutateSheet.read], [OutsideSheet.read], the pad's shape fields), and
 * what the box holds ([TREATMENT_HOLDS], [SHAPE_HOLDS], [OUTSIDE_HOLDS], [MUTATE_OFFERS]) when nothing
 * does: a closed line says what is inside or its current value, never "UNTOUCHED" (clarity spec 7.3).
 * Whether a pad carries anything from a bench is [Line.carried], never a test on the words. A summary must never wrap: it is one
 * line of the 8 px pixel face across the sheet (about 6.4 px a glyph,
 * so [SUMMARY_CHARS] of them cross a 390 sheet beside the chevron), and
 * the phone ellipsizes anything longer.
 */
object PadSheetBoxes {

    enum class Box(val legend: String) {
        TREATMENT("TREATMENT"),
        SHAPE("SHAPE"),
        OUTSIDE("OUTSIDE"),
        MUTATE("MUTATE"),
        MAKE("MAKE"),
    }

    /**
     * Box order, top to bottom, as the pad editor draws them: TREATMENT, SHAPE and OUTSIDE on the EFFECTS
     * screen (OUTSIDE plays the pad out through a speaker or pedal and records it back over the pad's sound,
     * so it is an effect), MUTATE on the VARIATIONS screen, then MAKE on the editor itself.
     */
    val ORDER: List<Box> = Box.values().toList()

    /** Which box sits on which of the editor's pages. */
    fun page(box: Box): PadSheetPage = when (box) {
        Box.TREATMENT, Box.SHAPE, Box.OUTSIDE -> PadSheetPage.EFFECTS
        Box.MUTATE -> PadSheetPage.VARIATIONS
        Box.MAKE -> PadSheetPage.EDITOR
    }

    /** What the TREATMENT strip reads when the pad carries no treatment: the rows' first words. */
    const val TREATMENT_HOLDS = "CRUSH, TAPE, DIRT, PEDALS"

    /** What the SHAPE strip reads when the pad sets no shape field: the four knobs. */
    const val SHAPE_HOLDS = "ATTACK, DECAY, CUTOFF, RESONANCE"

    /** What the OUTSIDE strip reads when the pad has made no trip: the card's two moves. */
    const val OUTSIDE_HOLDS = "REAMP, ROOM"

    /** One closed line and whether the pad carries anything from that bench ([text] is for the eye only). */
    data class Line(val text: String, val carried: Boolean)

    /** The label budget for one strip: about this many 8 px pixel characters cross a 390 sheet. */
    const val SUMMARY_CHARS = 44

    fun boxFor(name: String): Box? = Box.values().firstOrNull { it.name == name }

    /** Tapping a strip: the tapped box opens, or closes when it was the open one. */
    fun toggle(open: Box?, tapped: Box): Box? = if (open == tapped) null else tapped

    /** "CRUSH · 35%", or [TREATMENT_HOLDS]. */
    fun treatment(applied: PadSheet.Applied?): String = appliedTreatment(applied) ?: TREATMENT_HOLDS

    private fun appliedTreatment(applied: PadSheet.Applied?): String? = applied?.let {
        "${(it.segment ?: it.treatment.name).uppercase(Locale.ROOT)} · ${(it.amount * 100).roundToInt()}%"
    }

    /**
     * The pedals a pad plays ([PedalSlate.audible]: a pick at AMT 0, or one a later pick silences, is
     * not named), in the order they went on: one reads as a chip does ("DELAY · 70%"), several as
     * "WAH 70% · DRIVE 100%", or the words alone when the AMTs would not fit the strip, or the count
     * when even the words would not.
     */
    fun pedals(picks: List<com.snipsnap.synth.PedalBoard.Pick>): String {
        picks.singleOrNull()?.let { return "${it.entry.word} · ${(it.amount * 100).roundToInt()}%" }
        val full = picks.joinToString(" · ") { "${it.entry.word} ${(it.amount * 100).roundToInt()}%" }
        if (full.length <= SUMMARY_CHARS) return full
        val words = picks.joinToString(" · ") { it.entry.word }
        return if (words.length <= SUMMARY_CHARS) words else "${picks.size} PEDALS"
    }

    /** "ATK 12 MS · CUT 1.2K" - only the fields the pad sets; the rest are the format's defaults and say nothing. */
    fun shape(pad: KitPad): String = appliedShape(pad) ?: SHAPE_HOLDS

    private fun appliedShape(pad: KitPad): String? {
        val parts = listOfNotNull(
            pad.attack?.let { "ATK %.0f MS".format(Locale.ROOT, PadShape.attackSeconds(it) * 1000f) },
            pad.decay?.let { "DEC ${(it * 100).roundToInt()}%" },
            pad.cutoff?.let { "CUT ${cutoffLabel(it)}" },
            pad.resonance?.let { "RES %.0f DB".format(Locale.ROOT, PadShape.resonanceDb(it)) },
        )
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /** "1.2K" above a kilohertz, "400 HZ" below. */
    fun cutoffLabel(cutoff: Float): String {
        val hz = PadShape.cutoffHz(cutoff)
        return if (hz >= 1000f) "%.1fK".format(Locale.ROOT, hz / 1000f) else "${hz.roundToInt()} HZ"
    }

    /** "ROOM × FUNK ROOM", "DRIFT × SOUL A03", or [MUTATE_OFFERS]. */
    fun mutate(applied: MutateSheet.Applied?): String = appliedMutate(applied) ?: MUTATE_OFFERS

    private fun appliedMutate(applied: MutateSheet.Applied?): String? = applied?.let {
        "${it.word} × ${it.parents.joinToString(" + ") { p -> parentName(p) }}"
    }

    /** A parent's label as the strip names it: a room by its name, a pad as "KIT A03". */
    fun parentName(label: String): String =
        (if (label.startsWith(Rooms.LABEL_PREFIX)) label.removePrefix(Rooms.LABEL_PREFIX) else label.replace(':', ' '))
            .uppercase(Locale.ROOT)

    /** The trip's stage while one is out; else the card's status line; else [OUTSIDE_HOLDS]. */
    fun outside(applied: OutsideSheet.Applied?, stage: String?): String =
        appliedOutside(applied, stage) ?: OUTSIDE_HOLDS

    private fun appliedOutside(applied: OutsideSheet.Applied?, stage: String?): String? =
        stage ?: applied?.let { OutsideSheet.statusLine(it) }

    /** What MAKE holds, or what DE-SAMPLE already made of the pad. */
    fun make(pad: KitPad): String =
        pad.source["desampled"]?.let { "A PATCH NOW, ${it.uppercase(Locale.ROOT)} AWAY" }
            ?: if (ConfluenceWorkshop.isGathering(pad)) "GATHERING · MAKE VARIATION"
            else "PAD FROM ANYTHING · DE-SAMPLE · INSTRUMENT"

    /**
     * Which benches this pad actually carries something from (J36).
     *
     * KIT's grid showed nothing to tell a treated pad from a raw one,
     * though every summary above already knows — sixteen treated pads
     * could only be told apart by holding each in turn. This is the one
     * predicate the grid asks, derived from the same strips the sheet
     * draws, so the two can never disagree about what "treated" means.
     *
     * **[Box.MAKE] is not a bench in this sense and is deliberately
     * excluded.** Its strip is never empty of words: with nothing applied it
     * still reads "PAD FROM ANYTHING · DE-SAMPLE · INSTRUMENT", because
     * it advertises what the box can do rather than reporting what the
     * pad carries. A plain "any strip has words" test would
     * therefore call every pad in every kit treated — which is the same
     * answer as marking none of them, arrived at more expensively.
     * DE-SAMPLE is the one thing MAKE leaves on a pad, so that, and only
     * that, counts.
     */
    fun touched(pad: KitPad, outsideStage: String? = null): Set<Box> {
        val strips = lines(pad, outsideStage)
        val benches = ORDER.filter { it != Box.MAKE && strips.getValue(it).carried }
        val desampled = if (pad.source["desampled"] != null) listOf(Box.MAKE) else emptyList()
        return (benches + desampled).toSet()
    }

    /** Whether anything has been done to [pad] at all — the grid's marker. */
    fun isTouched(pad: KitPad, outsideStage: String? = null): Boolean = touched(pad, outsideStage).isNotEmpty()

    /**
     * The closed line of the editor's `EFFECTS ▸` door: what the pad has applied, from the three
     * benches that sit on that screen (what the TREATMENT strip says, then SHAPE, then OUTSIDE), as
     * "CRUSH 35% · ATK 12 MS". [EFFECTS_HOLDS] (what the screen holds) when none has anything; the count when the words would
     * not fit the line ([SUMMARY_CHARS]).
     */
    fun effectsLine(pad: KitPad, outsideStage: String? = null): String {
        val strips = lines(pad, outsideStage)
        val parts = ORDER.filter { page(it) == PadSheetPage.EFFECTS }
            .map { strips.getValue(it) }
            .filter { it.carried }
            .map { AMOUNT_JOINT.replace(it.text, "$1 $2") }
        if (parts.isEmpty()) return EFFECTS_HOLDS
        val full = parts.joinToString(" · ")
        return if (full.length <= SUMMARY_CHARS) full else "${parts.size} APPLIED"
    }

    /**
     * The closed line of the editor's `VARIATIONS ▸` door: what is inside. MUTATE is what the screen holds,
     * so it reads what MUTATE has done to the pad ("MUTATE · ROOM × FUNK ROOM"), or what it can do.
     */
    fun variationsLine(pad: KitPad): String {
        val applied = appliedMutate(MutateSheet.read(pad.recipe)) ?: return MUTATE_OFFERS
        val full = "MUTATE · $applied"
        return if (full.length <= SUMMARY_CHARS) full else "MUTATE · APPLIED"
    }

    /** What the EFFECTS door reads when the pad carries no treatment, shape or trip: the three benches on the screen. */
    const val EFFECTS_HOLDS = "TREATMENT, SHAPE, OUTSIDE"

    /** What the VARIATIONS door reads when the pad carries no mutate: what the screen holds. */
    const val MUTATE_OFFERS = "MIX WITH ANOTHER SOUND"

    /** "CRUSH · 35%" read as the door says it, "CRUSH 35%": a name, the joint, a percentage that ends the strip. */
    private val AMOUNT_JOINT = Regex("""^(.*) · (\d+%)$""")

    /** Every strip's words at once, in [ORDER]. */
    fun summaries(pad: KitPad, outsideStage: String?): Map<Box, String> =
        lines(pad, outsideStage).mapValues { it.value.text }

    /** Every strip at once, in [ORDER], with whether the pad carries anything from that bench. */
    fun lines(pad: KitPad, outsideStage: String?): Map<Box, Line> {
        val treatment = AquariumPadSheet.read(pad.recipe)?.let { "AQUARIUM" }
            ?: BoardSheet.read(pad.recipe)?.let { "${it.type.name} · ${(it.amount * 100).roundToInt()}%" }
            ?: pad.recipe?.takeIf(PrintedPads::has)?.let { "${PrintedPads.label(it)} · CHECK RECIPE" }
            ?: PadSheet.readPedals(pad.recipe)?.let { PedalSlate.audible(it.picks) }?.takeIf { it.isNotEmpty() }?.let(::pedals)
            ?: appliedTreatment(PadSheet.read(pad.recipe))
        fun line(applied: String?, holds: String) = Line(applied ?: holds, applied != null)
        return linkedMapOf(
            Box.TREATMENT to line(treatment, TREATMENT_HOLDS),
            Box.SHAPE to line(appliedShape(pad), SHAPE_HOLDS),
            Box.MUTATE to line(appliedMutate(MutateSheet.read(pad.recipe)), MUTATE_OFFERS),
            Box.OUTSIDE to line(appliedOutside(OutsideSheet.read(pad.recipe), outsideStage), OUTSIDE_HOLDS),
            Box.MAKE to Line(make(pad), false),
        )
    }
}
