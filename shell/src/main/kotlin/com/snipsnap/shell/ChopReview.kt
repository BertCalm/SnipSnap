package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Chopper
import com.snipsnap.audio.Classification
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Slice
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.xpm.PadNoteMap

/**
 * The CHOP SHOP screen: slices with the classifier's labels on chips,
 * every one of them tap-to-cycle overridable — placement is a starting
 * point, not a verdict, and this model is where that promise is kept.
 *
 * A chip under the confidence threshold renders dashed with NOT SURE
 * (the UI treatment); overriding one marks it "YOU ✓". RE-CHOP re-runs
 * detection and clears overrides — new slices, new opinions.
 */
class ChopReviewModel private constructor(
    val source: Snip,
    val mode: ChopMode,
    slices: List<Slice>,
    /** Where [source] sits in a tape on the SNIPS shelf, when it came off one; null = nothing to go back to. */
    val tape: TapeRef? = null,
) {

    /**
     * The tape [source] was cut from: [file] is the snip's bare filename
     * on the SNIPS shelf and [offsetFrames] where [source] starts in it
     * (TAPE's KEEP range, or 0 for a whole file), at the file's own rate.
     * Every slice sent to the grid gets `Retrim`'s three keys from this,
     * so RE-TRIM can open TAPE on the pad's own cut later.
     */
    data class TapeRef(val file: String, val offsetFrames: Int) {
        init {
            require(offsetFrames >= 0) { "a tape offset can't be negative: $offsetFrames" }
        }

        companion object {
            /**
             * A reference for [file] when it lives on the SNIPS shelf
             * (`SnipStore.DIR`) — the only place `Retrim.of` looks — else
             * null: TAPE also scrubs a kit's longest sample as a fallback,
             * and a pad tagged with that would only ever resolve to "gone".
             * A negative [offsetFrames] (a KEEP range clamped to the head)
             * reads as 0, the same clamp the slice itself gets.
             */
            fun ofSnip(file: java.io.File, offsetFrames: Int): TapeRef? =
                if (file.parentFile?.name == SnipStore.DIR) TapeRef(file.name, offsetFrames.coerceAtLeast(0)) else null
        }
    }

    sealed interface ChopMode {
        /** Follow the hits — right for breaks. */
        data class ByHits(val maxSlices: Int = 16) : ChopMode

        /** Divide evenly — right when the bars matter more than the attacks. */
        data class Grid(val parts: Int) : ChopMode
    }

    inner class Row internal constructor(
        /** 1-based display number, capture order. */
        val n: Int,
        val slice: Slice,
        val classification: Classification,
    ) {
        /** Tap-to-cycle override; null = the classifier's call stands. */
        var override: DrumClass? = null
            internal set

        val effectiveClass: DrumClass get() = override ?: classification.drumClass

        /** True once the human corrected the machine — the "YOU ✓" marker. */
        val overridden: Boolean get() = override != null && override != classification.drumClass

        /** Dashed-chip treatment: a guess, and honest about it. */
        val unsure: Boolean
            get() = !overridden && classification.confidence < NOT_SURE_BELOW
    }

    val rows: List<Row> = slices.mapIndexed { i, s ->
        Row(i + 1, s, Classifier.classify(s.snip))
    }

    val sliceCount: Int get() = rows.size

    /** Tap the chip: the label advances through the cycle and wraps. */
    fun cycleLabel(index: Int) {
        val row = rows[index]
        val at = CHIP_CYCLE.indexOf(row.effectiveClass)
        row.override = CHIP_CYCLE[(at + 1) % CHIP_CYCLE.size]
    }

    /**
     * Say outright what a slice is, in one move.
     *
     * The September UAT's finding 6, the worst number in the report: with
     * [cycleLabel] the only way to correct a chip, and the cycle running one
     * direction through ten classes with no back step, relabelling a
     * 16-slice kit could cost **144 taps** — and overshooting your target by
     * one meant going round again.
     *
     * Picking the class the classifier already chose is agreement, not
     * correction: [Row.overridden] compares against the classification
     * rather than merely testing for a set override, so the "YOU ✓" marker
     * stays honest either way.
     */
    fun setLabel(index: Int, dc: DrumClass) {
        rows[index].override = dc
    }

    /** Put the classifier's call back on one chip. */
    fun clearOverride(index: Int) {
        rows[index].override = null
    }

    /**
     * Where each slice would land — [AutoPlace] over the *effective*
     * classes, whole banks so nothing is dropped. Index i = pad i+1.
     */
    fun placementPreview(): List<Row?> {
        return AutoPlace.arrange(rows, bankAlignedPadCount(rows.size)) { it.effectiveClass }
    }

    /**
     * The AUTO-PLACE readout line: where the core classes landed, plus
     * "+ CHOKE" when the hats got their mute group.
     */
    fun placementSummary(): String {
        val placed = placementPreview()
        val parts = mutableListOf<String>()
        for (dc in listOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN)) {
            val at = placed.indexOfFirst { it?.effectiveClass == dc }
            if (at >= 0) parts += "${chipName(dc)}→${PadNoteMap.labelForPad(at + 1)}"
        }
        if (parts.isEmpty()) return "AUTO-PLACE: FILL IN ORDER"
        val choke = placed.any {
            it != null && AutoPlace.muteGroupFor(it.effectiveClass) != 0
        }
        return "AUTO-PLACE: " + parts.joinToString(" ") + if (choke) " + CHOKE" else ""
    }

    /** What SEND TO GRID hands the kit builder. */
    data class SendResult(
        /** Index i = pad i+1; ready for KitAssembler. */
        val arranged: List<ArrangedPad?>,
        val sliceCount: Int,
        /** True when a mute group got set — the toast mentions it. */
        val chokeSet: Boolean,
    )

    fun sendToGrid(): SendResult {
        val placed = placementPreview()
        val arranged = placed.map { row -> row?.let(::arrangedPad) }
        val choke = placed.any { it != null && AutoPlace.muteGroupFor(it.effectiveClass) != 0 }
        return SendResult(arranged, rows.size, choke)
    }

    /**
     * One slice as the kit builder takes it. `sourceFrame`/`lengthFrames`
     * are within [source] (the CLI's own keys); the `Retrim` keys, when a
     * [tape] is known, are the same cut made absolute in the file —
     * [TapeRef.offsetFrames] added — so two slices of one KEEP land on
     * different frames of the same tape.
     */
    private fun arrangedPad(row: Row): ArrangedPad {
        val start = row.slice.sourceFrame
        val length = row.slice.snip.frameCount
        val cut = tape?.let { Retrim.tag(it.file, it.offsetFrames + start, it.offsetFrames + start + length) } ?: emptyMap()
        return ArrangedPad(
            row.slice.snip, row.effectiveClass,
            source = mapOf(
                "origin" to "chop",
                "sourceFrame" to start.toString(),
                "lengthFrames" to length.toString(),
            ) + cut,
        )
    }

    /** RE-CHOP: fresh detection, fresh labels, overrides gone; the tape reference rides along. */
    fun rechop(newMode: ChopMode = mode): ChopReviewModel = chop(source, newMode, tape)

    /**
     * The teach-the-machine harvest: every overridden chip as a labeled
     * example — the measurements plus the human's word, never the audio.
     * The app appends these to the teach log only behind a consent switch.
     */
    fun labeledOverrides(): List<TeachLog.Example> =
        rows.filter { it.overridden }.map {
            TeachLog.Example(
                label = it.effectiveClass,
                features = it.classification.features,
                machineSaid = it.classification.drumClass,
            )
        }

    // ---------- melodic placement ----------

    /** Per-row detected pitch (confident only), cached — melodic placement reads it. */
    private val pitchByRow: Map<Row, com.snipsnap.audio.PitchEstimate> by lazy {
        rows.mapNotNull { row ->
            com.snipsnap.audio.Pitch.detect(row.slice.snip)
                ?.takeIf { it.confidence >= MELODIC_PITCH_CONFIDENCE }
                ?.let { row to it }
        }.toMap()
    }

    /** The row's confident pitch, or null — the UI shows it on melodic chips. */
    fun pitchOf(index: Int): com.snipsnap.audio.PitchEstimate? = pitchByRow[rows[index]]

    /**
     * MELODIC placement: pitched slices sorted **low → high** onto the
     * pads (root at A01, ascending — the SCALE-layout spirit), unpitched
     * slices appended after in capture order. A phrase becomes something
     * you can perform.
     */
    fun melodicPreview(): List<Row?> {
        val pitched = rows.filter { it in pitchByRow }.sortedBy { pitchByRow.getValue(it).hz }
        val unpitched = rows.filter { it !in pitchByRow }
        val ordered = pitched + unpitched
        val padCount = bankAlignedPadCount(ordered.size)
        return ordered.take(padCount) + List(padCount - ordered.size.coerceAtMost(padCount)) { null }
    }

    /** SEND TO GRID, melodic layout. */
    fun sendToGridMelodic(): SendResult {
        val placed = melodicPreview()
        val arranged = placed.map { row -> row?.let(::arrangedPad) }
        val choke = placed.any { it != null && AutoPlace.muteGroupFor(it.effectiveClass) != 0 }
        return SendResult(arranged, rows.size, choke)
    }

    /** The source's tempo, when it confidently has one. */
    val tempo: com.snipsnap.audio.TempoEstimate? by lazy {
        com.snipsnap.audio.Tempo.estimate(source)?.takeIf { it.confidence >= 0.3f }
    }

    /**
     * The capture's own rhythm as an embeddable clip — [CapturedGroove]
     * over the current placement, velocities from the hits' own dynamics.
     * Null when the source has no confident tempo; the UI greys the
     * GROOVE toggle rather than guessing one.
     */
    fun grooveClip(
        name: String,
        quantizeTo: Long? = null,
    ): com.snipsnap.mpc3.Mpc3Clip? {
        val t = tempo ?: return null
        val placed = placementPreview()
        val maxPeak = placed.filterNotNull()
            .maxOfOrNull { it.slice.snip.peak() }?.coerceAtLeast(1e-6f) ?: return null
        val hits = placed.mapIndexedNotNull { i, row ->
            row?.let {
                com.snipsnap.kit.CapturedGroove.Hit(
                    padSlot = i + 1,
                    sourceFrame = it.slice.sourceFrame.toLong(),
                    lengthFrames = it.slice.snip.frameCount.toLong().coerceAtLeast(1),
                    velocity = (it.slice.snip.peak() / maxPeak).coerceIn(0.05f, 1f),
                )
            }
        }
        return com.snipsnap.kit.CapturedGroove.clip(name, hits, t.bpm, source.sampleRate, quantizeTo)
    }

    companion object {
        /**
         * Whole banks of 16, rounded up, so nothing is dropped — but never
         * zero. A chop that found no slices still describes an empty
         * 16-pad grid: `AutoPlace.arrange` refuses `padCount == 0`, and
         * "no hits detected" is not the same thing as "no grid to show."
         */
        internal fun bankAlignedPadCount(rowCount: Int): Int =
            (((rowCount + 15) / 16).coerceAtLeast(1) * 16).coerceAtMost(128)

        /** Below this the chip goes dashed — same threshold as the CLI's `?`. */
        const val NOT_SURE_BELOW = 0.5f

        /** Below this a slice counts as unpitched for melodic placement. */
        const val MELODIC_PITCH_CONFIDENCE = 0.5f

        /** Chip tap-cycle order — core hits first, escape hatches last. */
        val CHIP_CYCLE: List<DrumClass> = listOf(
            DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
            DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM,
            DrumClass.PERC, DrumClass.TONAL, DrumClass.LOOP, DrumClass.UNKNOWN,
        )

        /** What the chip says: class names in NOT-SURE-able form. */
        fun chipName(dc: DrumClass): String = when (dc) {
            DrumClass.KICK -> "KICK"
            DrumClass.SNARE -> "SNARE"
            DrumClass.CLAP -> "CLAP"
            DrumClass.HAT_CLOSED -> "HAT CL"
            DrumClass.HAT_OPEN -> "HAT OP"
            DrumClass.TOM -> "TOM"
            DrumClass.PERC -> "PERC"
            DrumClass.TONAL -> "TONAL"
            DrumClass.LOOP -> "LOOP"
            DrumClass.UNKNOWN -> "NOT SURE"
        }

        /** Slice [source] by [mode]; [tape] names where it came from so the slices can find their way back. */
        fun chop(source: Snip, mode: ChopMode = ChopMode.ByHits(), tape: TapeRef? = null): ChopReviewModel {
            val slices = when (mode) {
                is ChopMode.ByHits ->
                    Chopper.byTransients(source, maxSlices = mode.maxSlices, cleanup = Chopper.SLICE_CLEANUP)
                is ChopMode.Grid ->
                    Chopper.intoEqualParts(source, mode.parts, cleanup = Chopper.SLICE_CLEANUP)
            }
            return ChopReviewModel(source, mode, slices, tape)
        }
    }
}
