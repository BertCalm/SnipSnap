package com.snipsnap.shell

import com.snipsnap.kit.Kit
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.xpm.PadNoteMap
import java.util.Locale

/**
 * The Chart — a groove as a monospace drum chart, the kind a drummer
 * reads off a music stand: one row per pad the clip actually plays, one
 * column per 16th, a glyph per hit sized by how hard it lands. Plain
 * text, so it pastes into a message, a README, a liner note, a DAW's
 * notepad.
 *
 * It draws exactly what is stored. A hit that isn't on the 16th grid is
 * drawn in the cell nearest to it as `>` (late) or `<` (early) and named
 * in a footnote with its bar, step and pulse offset — the chart never
 * tidies a groove onto the grid the way an export or a step-fork does.
 * The one number it derives, the swing line, is measured from the even
 * 16ths' own median lean and stated as an approximation.
 *
 * Pure text rendering over [Mpc3Clip] values; deterministic, so the
 * same clip and kit always chart the same.
 */
object Chart {

    /** A hit at or above this velocity draws as `X`. */
    const val LOUD = 0.75f

    /** A hit at or above this (and under [LOUD]) draws as `x`; softer draws as `o`. */
    const val MID = 0.4f

    /** Bars per system (one line of the grid); longer clips wrap, labels repeated. */
    const val BARS_PER_SYSTEM = 4

    /** Longest pad name the label column carries before an ellipsis. */
    const val NAME_WIDTH = 16

    /** One column of the grid is one 16th — the step editor's own cell. */
    val CELL_PULSES: Long = Mpc3Clip.PULSES_PER_16TH

    /**
     * The groove writer's chromatic map: pad slot N plays note 35+N
     * (`GrooveEdit.noteFor`, `KitPreview`, `GrooveVariations` all count
     * from the same 35). A row is a slot, so a note reads back as one.
     */
    const val NOTE_OFFSET = 35

    private const val STEPS_PER_BAR = 16
    private const val EMPTY = '.'

    /** One off-grid hit, in the footnotes: where it was drawn, and how far off it really is. */
    data class OffGrid(
        val index: Int,
        val slot: Int,
        /** 1-based bar and step of the cell the mark sits in. */
        val bar: Int,
        val step: Int,
        /** Pulses after (+) or before (−) that cell's grid line. */
        val offsetPulses: Long,
        val velocity: Float,
        /** The nearest cell was past the loop's end, so the mark sits at the top. */
        val acrossLoop: Boolean,
    )

    /** What one rendering says, for a toast to quote without re-parsing the text. */
    data class Summary(val notes: Int, val offGrid: Int, val pads: Int)

    fun summary(clip: Mpc3Clip): Summary = Summary(
        notes = clip.notes.size,
        offGrid = clip.notes.count { it.timePulses % CELL_PULSES != 0L },
        pads = clip.notes.map { it.note }.distinct().size,
    )

    /**
     * One clip as a chart. [program] is the door's own label for what was
     * on screen ("PROG A"); [bpmIsDefault] makes the header say so when
     * the tempo is a stand-in rather than the kit's.
     */
    fun render(
        clip: Mpc3Clip,
        kit: Kit,
        bpm: Float,
        bpmIsDefault: Boolean,
        program: String? = null,
    ): String = buildString {
        val title = listOfNotNull(
            clip.name.uppercase(),
            program?.uppercase(),
            tempoWord(bpm, bpmIsDefault),
            bars(clip.bars),
            notes(clip.notes.size),
        ).joinToString(" · ")
        appendLine(title)
        appendLine("=".repeat(title.length))
        appendLine()
        append(grid(clip, kit))
    }

    /**
     * A whole arrangement: each section's clip charted once under its
     * name, bar count, repeat count and the rule that picked it — the
     * song's plan as a stack of charts rather than a stack of claims.
     */
    fun render(
        arrangement: Arranger.Arrangement,
        kit: Kit,
        bpm: Float,
        bpmIsDefault: Boolean,
    ): String = buildString {
        val title = listOf(
            arrangement.name.uppercase(),
            "SEED ${arrangement.seed}",
            tempoWord(bpm, bpmIsDefault),
            bars(arrangement.totalBars),
            "${arrangement.sections.size} SECTIONS",
        ).joinToString(" · ")
        appendLine(title)
        appendLine("=".repeat(title.length))
        for (section in arrangement.sections) {
            appendLine()
            val head = "${section.name.uppercase()} · ${bars(section.bars)} " +
                "(${section.clip.bars} × ${section.repeats}) · ${section.clip.name.uppercase()}"
            appendLine(head)
            appendLine("-".repeat(head.length))
            appendLine(section.reason.uppercase())
            appendLine()
            append(grid(section.clip, kit))
        }
    }

    // ---- the grid ----

    private class Cell(var glyph: Char = EMPTY, var loudest: Float = -1f, var hits: Int = 0)

    private fun grid(clip: Mpc3Clip, kit: Kit): String = buildString {
        val steps = clip.bars * STEPS_PER_BAR
        val slots = clip.notes.map { slotOf(it) }.distinct().sorted()
        if (slots.isEmpty()) {
            appendLine("NO HITS. AN EMPTY CHART IS STILL A CHART.")
            return@buildString
        }
        val cells = slots.associateWith { Array(steps) { Cell() } }
        val offGrid = mutableListOf<OffGrid>()
        var shared = 0

        for (n in clip.notes.sortedWith(compareBy({ it.timePulses }, { it.note }))) {
            val nearest = (n.timePulses + CELL_PULSES / 2) / CELL_PULSES
            val offset = n.timePulses - nearest * CELL_PULSES
            val across = nearest >= steps
            val drawnAt = (nearest % steps).toInt()
            val cell = cells.getValue(slotOf(n))[drawnAt]
            val onGrid = offset == 0L
            if (!onGrid) {
                offGrid += OffGrid(
                    index = offGrid.size + 1,
                    slot = slotOf(n),
                    bar = drawnAt / STEPS_PER_BAR + 1,
                    step = drawnAt % STEPS_PER_BAR + 1,
                    offsetPulses = offset,
                    velocity = n.velocity,
                    acrossLoop = across,
                )
            }
            cell.hits += 1
            if (cell.hits > 1) shared += 1
            // The loudest hit owns the cell; an off-grid mark carries no
            // velocity of its own, so an on-grid hit of equal weight wins
            // the glyph (the footnote already names the off-grid one).
            if (n.velocity > cell.loudest || (n.velocity == cell.loudest && onGrid)) {
                cell.loudest = n.velocity
                cell.glyph = if (onGrid) velocityGlyph(n.velocity) else if (offset > 0) '>' else '<'
            }
        }

        val labels = slots.associateWith { rowLabel(it, kit) }
        val labelWidth = labels.values.maxOf { it.length }
        val systems = (clip.bars + BARS_PER_SYSTEM - 1) / BARS_PER_SYSTEM
        for (system in 0 until systems) {
            val firstBar = system * BARS_PER_SYSTEM
            val lastBar = minOf(clip.bars, firstBar + BARS_PER_SYSTEM) - 1
            if (system > 0) appendLine()
            // Bar numbers, then the beat ruler, then a row per pad.
            append(" ".repeat(labelWidth))
            for (bar in firstBar..lastBar) append(" " + "BAR ${bar + 1}".padEnd(STEPS_PER_BAR))
            appendLine()
            append(" ".repeat(labelWidth))
            for (bar in firstBar..lastBar) append("|1...2...3...4...")
            appendLine("|")
            for (slot in slots) {
                append(labels.getValue(slot).padEnd(labelWidth))
                val row = cells.getValue(slot)
                for (bar in firstBar..lastBar) {
                    append('|')
                    for (step in 0 until STEPS_PER_BAR) append(row[bar * STEPS_PER_BAR + step].glyph)
                }
                appendLine("|")
            }
        }

        appendLine()
        appendLine("X ≥ .75   x ≥ .40   o SOFTER   > LATE   < EARLY   ONE COLUMN = ONE 16TH ($CELL_PULSES PULSES)")
        appendLine(swingLine(clip))
        if (offGrid.isNotEmpty()) {
            appendLine()
            appendLine("OFF THE GRID — DRAWN IN THE NEAREST CELL, NOT MOVED THERE:")
            for (o in offGrid) appendLine(footnote(o))
        }
        if (shared > 0) {
            appendLine()
            appendLine(sharedLine(shared))
        }
    }

    // ---- words ----

    fun slotOf(note: Mpc3Note): Int = note.note - NOTE_OFFSET

    /** "A02 SNARE", the pad's own label and name; an off-pad note says what it is. */
    fun rowLabel(slot: Int, kit: Kit): String {
        if (slot !in 1..PadNoteMap.PAD_COUNT) return "NOTE ${slot + NOTE_OFFSET} (NO PAD)"
        val name = kit.pad(slot)?.displayName?.uppercase() ?: "(EMPTY)"
        val shown = if (name.length > NAME_WIDTH) name.take(NAME_WIDTH - 1) + "…" else name
        return "${PadNoteMap.labelForPad(slot)} $shown"
    }

    fun velocityGlyph(velocity: Float): Char = when {
        velocity >= LOUD -> 'X'
        velocity >= MID -> 'x'
        else -> 'o'
    }

    /** "OFF-GRID 1: A02 BAR 2 STEP 15, +31 PULSES LATE (x)". */
    fun footnote(o: OffGrid): String {
        val label = if (o.slot in 1..PadNoteMap.PAD_COUNT) PadNoteMap.labelForPad(o.slot) else "NOTE ${o.slot + NOTE_OFFSET}"
        val pulses = pulses(kotlin.math.abs(o.offsetPulses))
        val direction = if (o.offsetPulses > 0) "LATE" else "EARLY"
        val sign = if (o.offsetPulses > 0) "+" else "−"
        val across = if (o.acrossLoop) ", ACROSS THE LOOP'S END" else ""
        return "OFF-GRID ${o.index}: $label BAR ${o.bar} STEP ${o.step}, $sign$pulses $direction$across (${velocityGlyph(o.velocity)})"
    }

    /**
     * The swing line, measured: the median lean of the even 16ths (the
     * "ands") in pulses, and the panel percent that lean corresponds to —
     * the inverse of `GrooveVariations.swing`'s push, so a clip swung at
     * 62 reads back "≈ SWING 62%". A negative lean is early, which no
     * swing setting produces, and says so instead of inventing a percent.
     */
    fun swingLine(clip: Mpc3Clip): String {
        val leans = clip.notes.mapNotNull { n ->
            val nearest = (n.timePulses + CELL_PULSES / 2) / CELL_PULSES
            if (nearest % 2 == 1L) n.timePulses - nearest * CELL_PULSES else null
        }
        if (leans.isEmpty()) return "NO HITS ON THE EVEN 16THS. NOTHING TO CALL SWING."
        val lean = leans.sorted()[leans.size / 2]
        return when {
            lean == 0L -> "EVEN 16THS ON THE GRID. STRAIGHT."
            lean < 0L -> "EVEN 16THS −${pulses(-lean)} EARLY. NOT A SWING — NO PANEL SETTING LANDS EARLY."
            else -> {
                val percent = 50 + (lean * 50 + CELL_PULSES / 2) / CELL_PULSES
                "EVEN 16THS +${pulses(lean)}, ≈ SWING $percent%"
            }
        }
    }

    private fun sharedLine(shared: Int): String =
        "$shared ${if (shared == 1) "HIT SHARES" else "HITS SHARE"} A CELL WITH ANOTHER. THE LOUDER ONE IS DRAWN; THE FOOTNOTES STILL NAME EVERY OFF-GRID HIT."

    private fun tempoWord(bpm: Float, isDefault: Boolean): String {
        val n = String.format(Locale.ROOT, "%.0f", bpm)
        return if (isDefault) "$n BPM (STAND-IN, NO TEMPO SET)" else "$n BPM"
    }

    private fun bars(n: Int) = "$n ${if (n == 1) "BAR" else "BARS"}"
    private fun notes(n: Int) = "$n ${if (n == 1) "NOTE" else "NOTES"}"
    private fun pulses(n: Long) = "$n ${if (n == 1L) "PULSE" else "PULSES"}"
}
