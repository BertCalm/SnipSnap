package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Chopper
import com.snipsnap.audio.Classification
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Slice
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Transients
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
 *
 * The CUT bench (`docs/CHOP_CONTROLS.md`): how many hits, how hard the
 * detector listens ([Ear]), where each cut lands against the attack
 * ([Cut]), or a grid instead — every change a fresh chop through
 * [rechop], with the chips the user already corrected carried across
 * by [carryingOverrides]. And two local moves, [merged] and [split],
 * for when fourteen of sixteen cuts are right: the global count is the
 * wrong tool for the other two.
 */
class ChopReviewModel private constructor(
    val source: Snip,
    val mode: ChopMode,
    slices: List<Slice>,
    /** Where [source] sits in a tape on the SNIPS shelf, when it came off one; null = nothing to go back to. */
    val tape: TapeRef? = null,
    /** True once [merged] or [split] moved a cut by hand: the slices are no longer exactly what [mode] would cut. */
    val edited: Boolean = false,
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
             * A reference for [file] when it lives in [snipsDir] — the
             * app's actual SNIPS shelf, the only place `Retrim.of` looks —
             * else null: TAPE also scrubs a kit's longest sample as a
             * fallback, and a pad tagged with that would only ever resolve
             * to "gone" (or, worse, to a same-named snip). The directory
             * itself is compared, not its name. A negative [offsetFrames]
             * (a KEEP range clamped to the head) reads as 0, the same clamp
             * the slice itself gets.
             */
            fun ofSnip(file: java.io.File, offsetFrames: Int, snipsDir: java.io.File): TapeRef? =
                if (file.parentFile?.absoluteFile == snipsDir.absoluteFile) TapeRef(file.name, offsetFrames.coerceAtLeast(0)) else null
        }
    }

    sealed interface ChopMode {
        /**
         * Follow the hits — right for breaks. [maxSlices] keeps the
         * strongest that many; [ear] is how hard the detector listens and
         * [cut] where each cut lands against the attack.
         */
        data class ByHits(val maxSlices: Int = 16, val ear: Ear = Ear.NORMAL, val cut: Cut = Cut.ON) : ChopMode {
            init {
                require(maxSlices in 1..MAX_HITS) { "hits is 1..$MAX_HITS, got $maxSlices" }
            }

            /** The detector's settings for this ear and cut. */
            fun config(): Transients.Config = ear.config(cut)
        }

        /** Divide evenly — right when the bars matter more than the attacks. */
        data class Grid(val parts: Int) : ChopMode {
            init {
                require(parts in 1..MAX_HITS) { "parts is 1..$MAX_HITS, got $parts" }
            }
        }
    }

    /**
     * How hard the detector listens. NORMAL is the detector's own
     * defaults; FINE lowers the bar a hit has to clear and lets hits sit
     * closer, so ghost notes and fast hats come through; COARSE raises
     * it and keeps them apart, so only the hits that carry the beat do.
     * The three move the same two thresholds and the gap together —
     * one control, not three, because the ear is one thing.
     */
    enum class Ear {
        COARSE, NORMAL, FINE;

        /** The detector's settings at this ear, cutting per [cut]. */
        fun config(cut: Cut = Cut.ON): Transients.Config = when (this) {
            COARSE -> Transients.Config(thresholdFactor = 2.6f, thresholdFloorDb = 7f, minSliceMs = 80f, backoffFrames = cut.backoffFrames)
            NORMAL -> Transients.Config(backoffFrames = cut.backoffFrames)
            FINE -> Transients.Config(thresholdFactor = 1.2f, thresholdFloorDb = 1.5f, minSliceMs = 15f, backoffFrames = cut.backoffFrames)
        }
    }

    /**
     * Where a cut lands against the attack it found. The detector lags
     * the true attack by up to a hop and backs every cut off by a fixed
     * amount to make up for it (ON, the default); EARLY backs off more,
     * for when a kick still lost its click; LATE not at all, for when
     * the previous slice's tail was bleeding into this one's front.
     */
    enum class Cut(val backoffFrames: Int) {
        EARLY(384), ON(128), LATE(0)
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

    /** Where each slice starts in [source]: the cut markers the screen draws live. */
    fun cutFrames(): List<Int> = rows.map { it.slice.sourceFrame }

    /**
     * What the header says after the count: the mode, then only what is
     * off its default (BY HITS · FINE · CUT EARLY), then EDITED once a
     * cut was moved by hand — so a chop is reproducible from its words.
     */
    fun modeLabel(): String {
        val parts = mutableListOf<String>()
        when (mode) {
            is ChopMode.ByHits -> {
                parts += "BY HITS"
                if (mode.ear != Ear.NORMAL) parts += mode.ear.name
                if (mode.cut != Cut.ON) parts += "CUT ${mode.cut.name}"
            }
            is ChopMode.Grid -> parts += "GRID ×${mode.parts}"
        }
        if (edited) parts += "EDITED"
        return parts.joinToString(" · ")
    }

    /**
     * How many hits [source] wants at this ear — the knee in the sorted
     * loudness curve where the real hits end and the detector's table
     * scraps begin (`Chopper.autoSliceCount`), bounded to the stepper's
     * range. The AUTO button's answer.
     */
    fun autoCount(): Int {
        val config = (mode as? ChopMode.ByHits)?.config() ?: Transients.Config()
        return Chopper.autoSliceCount(source, config).coerceIn(1, MAX_HITS)
    }

    /**
     * This model's overrides carried onto [fresh], chip by chip, wherever
     * a fresh slice starts within [CARRY_TOLERANCE_FRAMES] of a slice the
     * user had corrected — so nudging CUT, or one step of HITS, does not
     * throw away ten relabelled chips. A slice that moved further than
     * that is a different slice and takes the classifier's word. Returns
     * [fresh] itself, mutated.
     */
    fun carryingOverrides(fresh: ChopReviewModel): ChopReviewModel {
        val corrected = rows.filter { it.override != null }
        if (corrected.isEmpty()) return fresh
        for (row in fresh.rows) {
            val near = corrected.minByOrNull { kotlin.math.abs(it.slice.sourceFrame - row.slice.sourceFrame) } ?: continue
            if (kotlin.math.abs(near.slice.sourceFrame - row.slice.sourceFrame) <= CARRY_TOLERANCE_FRAMES) row.override = near.override
        }
        return fresh
    }

    /**
     * MERGE: slice [index] and the one after it as one slice — the cut
     * between them gone, the joined slice cut from [source] again so its
     * cleanup is one slice's, not two fades in the middle. The joined
     * slice keeps the first's chip; every other row keeps its own. Null
     * when [index] is the last slice: nothing after it to merge with.
     */
    fun merged(index: Int): ChopReviewModel? {
        if (index !in 0 until rows.size - 1) return null
        val a = rows[index].slice
        val b = rows[index + 1].slice
        val joined = Chopper.slice(source, a.sourceFrame, b.sourceFrame + b.snip.frameCount, a.onset, Chopper.SLICE_CLEANUP)
        val slices = rows.map { it.slice }.toMutableList()
        slices[index] = joined
        slices.removeAt(index + 1)
        val overrides = rows.map { it.override }.toMutableList().also { it.removeAt(index + 1) }
        return rebuilt(slices, overrides)
    }

    /**
     * SPLIT: slice [index] cut in two at its own strongest inner hit —
     * the detector at the FINE ear over just this slice, any hit at least
     * [SPLIT_MARGIN_MS] in from either end (a hit on the very edge is the
     * slice's own attack or the next slice's), the strongest one taken
     * and the cut snapped to the zero crossing before it, as every chop
     * cut is. The first half keeps the chip; the second takes the
     * classifier's word. Null when the slice holds no second hit.
     */
    fun split(index: Int): ChopReviewModel? {
        if (index !in rows.indices) return null
        val a = rows[index].slice
        val piece = a.snip
        val margin = (SPLIT_MARGIN_MS / 1000f * piece.sampleRate).toInt()
        val inner = Transients.detect(piece, Ear.FINE.config(Cut.ON))
            .filter { it.frame in margin..(piece.frameCount - margin) }
            .maxByOrNull { it.strength } ?: return null
        val at = Transients.zeroCrossingBefore(piece, inner.frame)
        if (at <= 0 || at >= piece.frameCount) return null
        val cut = a.sourceFrame + at
        val first = Chopper.slice(source, a.sourceFrame, cut, a.onset, Chopper.SLICE_CLEANUP)
        val second = Chopper.slice(source, cut, a.sourceFrame + piece.frameCount, inner.copy(frame = cut), Chopper.SLICE_CLEANUP)
        val slices = rows.map { it.slice }.toMutableList()
        slices[index] = first
        slices.add(index + 1, second)
        val overrides = rows.map { it.override }.toMutableList().also { it.add(index + 1, null) }
        return rebuilt(slices, overrides)
    }

    /** A model over [slices] with [overrides] laid back onto its rows, marked [edited]. */
    private fun rebuilt(slices: List<Slice>, overrides: List<DrumClass?>): ChopReviewModel {
        val m = ChopReviewModel(source, mode, slices, tape, edited = true)
        for ((i, o) in overrides.withIndex()) m.rows[i].override = o
        return m
    }

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

    /** RE-CHOP: fresh detection, fresh labels, overrides gone, hand edits gone; the tape reference rides along. */
    fun rechop(newMode: ChopMode = mode): ChopReviewModel = chop(source, newMode, tape)

    /** The bench's own re-chop: [newMode], with this model's corrected chips carried across ([carryingOverrides]). */
    fun rechopKeeping(newMode: ChopMode): ChopReviewModel = carryingOverrides(rechop(newMode))

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

        /** The HITS and GRID steppers' ceiling: four banks, the same bound the CLI's auto count keeps. */
        const val MAX_HITS = Chopper.AUTO_MAX

        /** An override follows a slice across a re-chop when the fresh slice starts within this many frames of it (two hops each way of a CUT nudge). */
        const val CARRY_TOLERANCE_FRAMES = 512

        /** SPLIT ignores a hit closer than this to either end of the slice. */
        const val SPLIT_MARGIN_MS = 30f

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
                    Chopper.byTransients(source, maxSlices = mode.maxSlices, config = mode.config(), cleanup = Chopper.SLICE_CLEANUP)
                is ChopMode.Grid ->
                    Chopper.intoEqualParts(source, mode.parts, cleanup = Chopper.SLICE_CLEANUP)
            }
            return ChopReviewModel(source, mode, slices, tape)
        }
    }
}
