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
 * [MutateSheet.read], [OutsideSheet.read], the pad's shape fields), and
 * [UNTOUCHED] when nothing does. A summary must never wrap: it is one
 * line of the 8 px pixel face across the sheet (about 6.4 px a glyph,
 * so [SUMMARY_CHARS] of them cross a 390 sheet beside the chevron), and
 * the phone ellipsizes anything longer.
 */
object PadSheetBoxes {

    enum class Box(val legend: String) {
        TREATMENT("TREATMENT"),
        SHAPE("SHAPE"),
        MUTATE("MUTATE"),
        OUTSIDE("OUTSIDE"),
        MAKE("MAKE"),
    }

    /** Box order, top to bottom, as the sheet draws them. */
    val ORDER: List<Box> = Box.values().toList()

    /** What a strip reads when the pad carries nothing from that bench. */
    const val UNTOUCHED = "UNTOUCHED"

    /** The label budget for one strip: about this many 8 px pixel characters cross a 390 sheet. */
    const val SUMMARY_CHARS = 44

    fun boxFor(name: String): Box? = Box.values().firstOrNull { it.name == name }

    /** Tapping a strip: the tapped box opens, or closes when it was the open one. */
    fun toggle(open: Box?, tapped: Box): Box? = if (open == tapped) null else tapped

    /** "CRUSH · 35%", or [UNTOUCHED]. */
    fun treatment(applied: PadSheet.Applied?): String = applied?.let {
        "${(it.segment ?: it.treatment.name).uppercase(Locale.ROOT)} · ${(it.amount * 100).roundToInt()}%"
    } ?: UNTOUCHED

    /** "ATK 12 MS · CUT 1.2K" - only the fields the pad sets; the rest are the format's defaults and say nothing. */
    fun shape(pad: KitPad): String {
        val parts = listOfNotNull(
            pad.attack?.let { "ATK %.0f MS".format(Locale.ROOT, PadShape.attackSeconds(it) * 1000f) },
            pad.decay?.let { "DEC ${(it * 100).roundToInt()}%" },
            pad.cutoff?.let { "CUT ${cutoffLabel(it)}" },
            pad.resonance?.let { "RES %.0f DB".format(Locale.ROOT, PadShape.resonanceDb(it)) },
        )
        return if (parts.isEmpty()) UNTOUCHED else parts.joinToString(" · ")
    }

    /** "1.2K" above a kilohertz, "400 HZ" below. */
    fun cutoffLabel(cutoff: Float): String {
        val hz = PadShape.cutoffHz(cutoff)
        return if (hz >= 1000f) "%.1fK".format(Locale.ROOT, hz / 1000f) else "${hz.roundToInt()} HZ"
    }

    /** "ROOM × FUNK ROOM", "DRIFT × SOUL A03", or [UNTOUCHED]. */
    fun mutate(applied: MutateSheet.Applied?): String = applied?.let {
        "${it.word} × ${it.parents.joinToString(" + ") { p -> parentName(p) }}"
    } ?: UNTOUCHED

    /** A parent's label as the strip names it: a room by its name, a pad as "KIT A03". */
    fun parentName(label: String): String =
        (if (label.startsWith(Rooms.LABEL_PREFIX)) label.removePrefix(Rooms.LABEL_PREFIX) else label.replace(':', ' '))
            .uppercase(Locale.ROOT)

    /** The trip's stage while one is out; else the card's status line; else [UNTOUCHED]. */
    fun outside(applied: OutsideSheet.Applied?, stage: String?): String =
        stage ?: applied?.let { OutsideSheet.statusLine(it) } ?: UNTOUCHED

    /** What MAKE holds, or what DE-SAMPLE already made of the pad. */
    fun make(pad: KitPad): String =
        pad.source["desampled"]?.let { "A PATCH NOW, ${it.uppercase(Locale.ROOT)} AWAY" } ?: "PAD FROM ANYTHING · DE-SAMPLE · INSTRUMENT"

    /** Every strip at once, in [ORDER]. */
    fun summaries(pad: KitPad, outsideStage: String?): Map<Box, String> = linkedMapOf(
        Box.TREATMENT to treatment(PadSheet.read(pad.recipe)),
        Box.SHAPE to shape(pad),
        Box.MUTATE to mutate(MutateSheet.read(pad.recipe)),
        Box.OUTSIDE to outside(OutsideSheet.read(pad.recipe), outsideStage),
        Box.MAKE to make(pad),
    )
}
