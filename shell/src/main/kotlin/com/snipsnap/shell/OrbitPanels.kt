package com.snipsnap.shell

import com.snipsnap.loop.OrbitSection
import com.snipsnap.loop.OrbitSet
import com.snipsnap.loop.OrbitSpan
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The words ORBIT's folds and pickers say (clarity spec 7.3, "Removed patterns").
 *
 * A fold's closed line says what is inside it or its current value, never
 * "UNTOUCHED"; a picker's rows are a name and, where there is a fact to give, one
 * line under it. The logic is here, away from the composables, so a test can hold
 * it: the screen only draws what these return.
 */
object OrbitPanels {

    /** THE SET's closed line: the bar, the swing, and the project's tempo it plays at. */
    fun setSummary(lapSteps: Int, swing: Int, bpm: Float): String =
        "${OrbitSet.meterLabel(lapSteps)} · ${swingWord(swing)} · ${bpm.roundToInt()} BPM"

    private fun swingWord(swing: Int): String = if (swing == OrbitSet.STRAIGHT_SWING) "STRAIGHT" else "SWING $swing"

    /** SECTIONS' closed line: the plan's length and each section's bars, or that there is no plan. */
    fun sectionsSummary(sections: List<OrbitSection>): String {
        if (sections.isEmpty()) return "NO ARRANGEMENT · EVERY RING TURNS FOREVER"
        val total = sections.sumOf { it.bars }
        return Copy.countOf(total, "BAR", "BARS") + " · " + sections.joinToString(" · ") { "${it.name} ${it.bars}" }
    }

    /** ARP's closed line: the shape, how many octaves it climbs (a chord does not), and the gate. */
    fun arpSummary(shapeLabel: String, chord: Boolean, octaves: Int, gate: Float): String =
        listOfNotNull(shapeLabel, if (chord) null else "$octaves OCT", "GATE ${(gate * 100).roundToInt()}%").joinToString(" · ")

    /** ONE TURN OUT's closed line: how long one turn is. */
    fun outSummary(turn: String): String = "ONE TURN IS $turn"

    /** ONE TURN OUT's second closed line: the ways out that are open to this set now. */
    fun outAlso(canBounce: Boolean, canClip: Boolean): String =
        listOfNotNull(
            if (canBounce) Copy.ORBIT_BOUNCE_BUTTON else null,
            if (canClip) "CLIP TO KIT" else null,
            "GROOVE TO RINGS",
        ).joinToString(" · ")

    /** The step counts the picker offers a ring, as rows. */
    fun stepsName(steps: Int): String = Copy.countOf(steps, "STEP", "STEPS")

    /**
     * What a step count comes to, under its row: a multiple of four is that many
     * beats (sixteen is the bar), an odd count is the kind that drifts furthest
     * against its neighbours, and any other even count has nothing to add.
     */
    fun stepsNote(steps: Int): String? = when {
        steps == 16 -> "A BAR · 4 BEATS"
        steps == 4 -> "ONE BEAT"
        steps % 4 == 0 -> "${steps / 4} BEATS"
        steps % 2 == 1 -> "ODD · DRIFTS FURTHEST"
        else -> null
    }

    /** The picker's one line of help under the rows. */
    const val STEPS_HELP = "16 IS A BAR · 20 IS FIVE BEATS · 12 IS THREE · ODD NUMBERS DRIFT FURTHEST"

    /** What a span means, under its row. */
    fun spanNote(span: OrbitSpan): String =
        if (span.laps == null) "AS LONG AS ITS STEPS" else "ONE TURN IS ${span.label} WHATEVER THE STEPS"

    const val SPAN_HELP = "FREE IS AS LONG AS ITS STEPS. A SPAN IS ½, 1, 2 OR 4 BARS WHATEVER THE STEPS: 3 STEPS ACROSS 2 BARS IS THREE HITS IN EIGHT BEATS."

    /** The spread counts a ring of [steps] offers: one to twelve, never more than its steps. */
    fun spreadCounts(steps: Int): IntRange = 1..minOf(steps, 12)

    fun spreadName(hits: Int): String = Copy.countOf(hits, "HIT", "HITS")

    /** The two spreads that have a name, on a ring of eight. */
    fun spreadNote(hits: Int, steps: Int): String? = when {
        steps == 8 && hits == 3 -> "THE TRESILLO"
        steps == 8 && hits == 5 -> "THE CINQUILLO"
        else -> null
    }

    const val SPREAD_HELP = "AS EVEN AS THE STEPS ALLOW. ON THE RING'S FIRST PAD."

    fun spreadTitle(steps: Int): String = "SPREAD HOW MANY HITS ROUND ${Copy.countOf(steps, "STEP", "STEPS")}?"

    /** A tempo line in seconds for a printing length row, to one place. */
    internal fun seconds(s: Float): String = "%.1f".format(Locale.ROOT, s)
}
