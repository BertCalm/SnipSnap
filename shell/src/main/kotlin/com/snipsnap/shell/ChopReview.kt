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
) {

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

    /** Put the classifier's call back on one chip. */
    fun clearOverride(index: Int) {
        rows[index].override = null
    }

    /**
     * Where each slice would land — [AutoPlace] over the *effective*
     * classes, whole banks so nothing is dropped. Index i = pad i+1.
     */
    fun placementPreview(): List<Row?> {
        val padCount = (((rows.size + 15) / 16) * 16).coerceAtMost(128)
        return AutoPlace.arrange(rows, padCount) { it.effectiveClass }
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
        val arranged = placed.map { row ->
            row?.let { ArrangedPad(it.slice.snip, it.effectiveClass) }
        }
        val choke = placed.any { it != null && AutoPlace.muteGroupFor(it.effectiveClass) != 0 }
        return SendResult(arranged, rows.size, choke)
    }

    /** RE-CHOP: fresh detection, fresh labels, overrides gone. */
    fun rechop(newMode: ChopMode = mode): ChopReviewModel = chop(source, newMode)

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
        val padCount = (((ordered.size + 15) / 16) * 16).coerceAtMost(128)
        return ordered.take(padCount) + List(padCount - ordered.size.coerceAtMost(padCount)) { null }
    }

    /** SEND TO GRID, melodic layout. */
    fun sendToGridMelodic(): SendResult {
        val placed = melodicPreview()
        val arranged = placed.map { row ->
            row?.let { ArrangedPad(it.slice.snip, it.effectiveClass) }
        }
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

        fun chop(source: Snip, mode: ChopMode = ChopMode.ByHits()): ChopReviewModel {
            val slices = when (mode) {
                is ChopMode.ByHits ->
                    Chopper.byTransients(source, maxSlices = mode.maxSlices, cleanup = Chopper.SLICE_CLEANUP)
                is ChopMode.Grid ->
                    Chopper.intoEqualParts(source, mode.parts, cleanup = Chopper.SLICE_CLEANUP)
            }
            return ChopReviewModel(source, mode, slices)
        }
    }
}
