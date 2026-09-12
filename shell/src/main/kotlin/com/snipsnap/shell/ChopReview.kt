package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Chopper
import com.snipsnap.audio.Classification
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Similar
import com.snipsnap.audio.Slice
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Tempo
import com.snipsnap.audio.TempoEstimate
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
 *
 * Round two (`docs/CHOP_CONTROLS.md` §8): ON THE GRID ([GridSnap]) —
 * every cut snapped to the nearest 8th, 16th or 32nd of the source's
 * own tempo, anchored on the first cut, and never after the attack it
 * found; and FOLD DOUBLES ([folds]) — near-identical slices of one class
 * as one pad with the repeats cycling under it as a round-robin chain,
 * so sixteen slices of a break become the five sounds the drummer
 * played.
 */
class ChopReviewModel private constructor(
    val source: Snip,
    val mode: ChopMode,
    slices: List<Slice>,
    /** Where [source] sits in a tape on the SNIPS shelf, when it came off one; null = nothing to go back to. */
    val tape: TapeRef? = null,
    /** True once [merged] or [split] moved a cut by hand: the slices are no longer exactly what [mode] would cut. */
    val edited: Boolean = false,
    /**
     * The source's tempo, measured once and shared by every model cut
     * from the same source (a re-chop, a merge, a split): the bench asks
     * many times and the tape never changes.
     */
    private val tempoLazy: Lazy<TempoEstimate?> = lazy { estimateTempo(source) },
    /** ON THE GRID's fitted line spacing in frames (see [snapped]), null when the cuts were not snapped. */
    val gridStep: Double? = null,
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
        data class ByHits(val maxSlices: Int = 16, val ear: Ear = Ear.NORMAL, val cut: Cut = Cut.ON, val grid: GridSnap = GridSnap.OFF) : ChopMode {
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
     * ON THE GRID: the pulse every cut snaps to, as divisions of a beat
     * of the source's own tempo. OFF leaves the cuts where the hits were.
     * The grid is anchored on the first cut (captures rarely start on
     * the one, and the tempo estimate has a period but no phase), and a
     * cut never lands after the attack the detector found — a hit that
     * pushed early keeps its cut and its click, a hit that dragged late
     * gets a cut a hair early and a little air in front. Two hits on one
     * line become one slice, the stronger hit's.
     */
    enum class GridSnap(val perBeat: Int, val label: String) {
        OFF(0, "OFF"), EIGHTH(2, "8TH"), SIXTEENTH(4, "16TH"), THIRTY_SECOND(8, "32ND")
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
                // `tempo` is already measured whenever the grid is on: [chop]
                // forced it to snap, so reading it here is a lookup, not DSP.
                if (mode.grid != GridSnap.OFF) parts += if (tempo != null) "ON THE ${mode.grid.label}" else "ON THE ${mode.grid.label} (NO TEMPO)"
            }
            is ChopMode.Grid -> parts += "GRID ×${mode.parts}"
        }
        if (edited) parts += "EDITED"
        return parts.joinToString(" · ")
    }

    /**
     * How many hits [source] wants at this ear — the knee in the sorted
     * loudness curve where the real hits end and the detector's table
     * scraps begin (`Chopper.autoSliceCount`), capped at the stepper's
     * ceiling. The AUTO button's answer; null when the tape has no hit
     * at all, which is a refusal, not a count of one.
     */
    fun autoCount(): Int? {
        val config = (mode as? ChopMode.ByHits)?.config() ?: Transients.Config()
        return Chopper.autoSliceCount(source, config).takeIf { it > 0 }?.coerceAtMost(MAX_HITS)
    }

    /**
     * This model's overrides carried onto [fresh], chip by chip, wherever
     * a fresh slice starts within [CARRY_TOLERANCE_FRAMES] of a slice the
     * user had corrected — so nudging CUT, or one step of HITS, does not
     * throw away ten relabelled chips. One to one, nearest pairs first:
     * a corrected chip lands on at most one fresh slice, so when FINE
     * reveals a ghost a hair from a slice the user relabelled, the ghost
     * does not inherit the label too. A slice that moved further than
     * the tolerance is a different slice and takes the classifier's
     * word. Returns [fresh] itself, mutated.
     */
    fun carryingOverrides(fresh: ChopReviewModel): ChopReviewModel {
        val corrected = rows.filter { it.override != null }
        if (corrected.isEmpty()) return fresh
        val pairs = ArrayList<Triple<Int, Row, Row>>()
        for (old in corrected) for (row in fresh.rows) {
            val d = kotlin.math.abs(old.slice.sourceFrame - row.slice.sourceFrame)
            if (d <= CARRY_TOLERANCE_FRAMES) pairs += Triple(d, old, row)
        }
        val usedOld = HashSet<Row>()
        val usedFresh = HashSet<Row>()
        for ((_, old, row) in pairs.sortedBy { it.first }) {
            if (old in usedOld || row in usedFresh) continue
            row.override = old.override
            usedOld += old
            usedFresh += row
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
        val m = ChopReviewModel(source, mode, slices, tape, edited = true, tempoLazy = tempoLazy, gridStep = gridStep)
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
    fun rechop(newMode: ChopMode = mode): ChopReviewModel = chop(source, newMode, tape, tempoLazy)

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

    /** The source's tempo, when it confidently has one — measured once per source (see [tempoLazy]). */
    val tempo: TempoEstimate? by tempoLazy

    // ---------- FOLD DOUBLES ----------

    /**
     * One pad's worth of slices: [takes] in capture order, the first the
     * pad's own sound and the rest cycling under it as a round-robin
     * chain. A lone slice is a fold of one.
     */
    data class Fold(val takes: List<Row>) {
        val lead: Row get() = takes.first()
        val size: Int get() = takes.size
    }

    /**
     * The rows folded: near-identical slices of one class as one fold —
     * `Similar.distance` over the classifier's own features within
     * [within] (level left out, so a ghost snare folds with the snare),
     * single linkage, never across classes (a snare the user relabelled
     * TOM is a TOM and folds with toms), never wider than a chain can
     * cycle (`Robin.MAX_TAKES`; a bigger run becomes two folds). Folds
     * in capture order of their first slice; takes in capture order.
     */
    fun folds(within: Float = FOLD_WITHIN): List<Fold> {
        val n = rows.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            // Path halving: each step points x at its grandparent, then moves up.
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (i in 0 until n) for (j in i + 1 until n) {
            val a = rows[i]
            val b = rows[j]
            if (a.effectiveClass != b.effectiveClass) continue
            if (Similar.distance(a.classification.features, b.classification.features) <= within) {
                val ra = find(i)
                val rb = find(j)
                if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
            }
        }
        val groups = LinkedHashMap<Int, MutableList<Row>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { mutableListOf() } += rows[i]
        // Chunking a long run makes a second fold led by a later slice; the
        // promise is capture order of leads, so sort after chunking.
        return groups.values.flatMap { takes -> takes.chunked(Robin.MAX_TAKES).map { Fold(it) } }.sortedBy { it.lead.n }
    }

    /**
     * What each row says about its fold, by row: the lead of a fold of
     * many says how many cycle under it, a take says which it is and
     * whose; a fold of one says nothing.
     */
    fun foldTags(within: Float = FOLD_WITHIN): List<String?> {
        val tags = arrayOfNulls<String>(rows.size)
        for (fold in folds(within)) {
            if (fold.size < 2) continue
            for ((k, row) in fold.takes.withIndex()) {
                tags[row.n - 1] = if (k == 0) "×${fold.size} TAKES" else "TAKE ${k + 1} OF ${fold.size} · = ${fold.lead.n}"
            }
        }
        return tags.toList()
    }

    /** FOLD placement: one pad per fold, laid out by the lead's class, whole banks. */
    fun foldedPreview(within: Float = FOLD_WITHIN): List<Fold?> {
        val fs = folds(within)
        return AutoPlace.arrange(fs, bankAlignedPadCount(fs.size)) { it.lead.effectiveClass }
    }

    /**
     * SEND TO GRID, folded: each pad the fold's lead with the other takes
     * cycling under it (`ArrangedPad.takes` → a chain pad), the lead's
     * provenance plus how many folded. [SendResult.sliceCount] is the
     * pad count here; the toast says both numbers.
     */
    fun sendToGridFolded(within: Float = FOLD_WITHIN): SendResult {
        val placed = foldedPreview(within)
        val arranged = placed.map { fold ->
            fold?.let {
                val lead = arrangedPad(it.lead)
                lead.copy(
                    takes = it.takes.drop(1).map { r -> r.slice.snip },
                    source = lead.source + ("folded" to it.size.toString()),
                )
            }
        }
        val choke = placed.any { it != null && AutoPlace.muteGroupFor(it.lead.effectiveClass) != 0 }
        return SendResult(arranged, placed.count { it != null }, choke)
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

        /** FOLD's "the same sound": DOUBLES' own opening ring over the same distance. */
        const val FOLD_WITHIN = Doubles.DEFAULT_WITHIN

        /** A tempo under this confidence is numerology, not a pulse: no grid, no groove. */
        const val TEMPO_CONFIDENCE = 0.3f

        /** The one place the tempo is measured. */
        private fun estimateTempo(source: Snip): TempoEstimate? =
            Tempo.estimate(source)?.takeIf { it.confidence >= TEMPO_CONFIDENCE }

        /** The fitted grid may differ from the tempo estimate's by this much either way; further is a bad fit and the seed stands. */
        const val GRID_FIT_TOLERANCE = 0.1

        /**
         * [slices] with every cut on [grid], anchored on the first cut.
         * The tempo estimate seeds the line spacing and decides which line
         * each hit is nearest to; the spacing is then **fitted to the
         * hits** (least squares of each cut's offset from the anchor
         * against its line index, twice) — an estimate a percent off
         * drifts past the later hits within a few bars, and the pulse of
         * this take is the hits themselves. A fit further than
         * [GRID_FIT_TOLERANCE] from the seed is not trusted and the seed
         * stands. Each cut then moves to its line, but never later than
         * the cut the detector made (which already sits a backoff before
         * the attack), so no attack is shaved; two hits on one line keep
         * the stronger, and the audio between joins that slice. Cut from
         * [source] again so each slice's cleanup is its own. Returns the
         * slices and the fitted spacing.
         */
        internal fun snapped(source: Snip, slices: List<Slice>, grid: GridSnap, bpm: Float): Pair<List<Slice>, Double> {
            val seed = 60.0 / bpm / grid.perBeat * source.sampleRate
            if (slices.size < 2 || grid == GridSnap.OFF) return slices to seed
            val anchor = slices.first().sourceFrame
            var step = seed
            repeat(2) {
                var num = 0.0
                var den = 0.0
                for (s in slices) {
                    val k = Math.round((s.sourceFrame - anchor) / step).toDouble()
                    num += k * (s.sourceFrame - anchor)
                    den += k * k
                }
                if (den > 0.0) {
                    val fitted = num / den
                    if (kotlin.math.abs(fitted - seed) <= seed * GRID_FIT_TOLERANCE) step = fitted
                }
            }
            val kept = LinkedHashMap<Long, Slice>()
            for (s in slices) {
                val k = Math.round((s.sourceFrame - anchor) / step)
                val had = kept[k]
                if (had == null || (s.onset?.strength ?: 0f) > (had.onset?.strength ?: 0f)) kept[k] = s
            }
            val cuts = kept.entries.sortedBy { it.key }.map { (k, s) ->
                val line = anchor + Math.round(k * step).toInt()
                minOf(line, s.sourceFrame) to s
            }
            return cuts.mapIndexed { i, (cut, s) ->
                val end = if (i + 1 < cuts.size) cuts[i + 1].first else source.frameCount
                Chopper.slice(source, cut, end, s.onset, Chopper.SLICE_CLEANUP)
            }.filter { it.snip.frameCount > 0 } to step
        }

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
        fun chop(source: Snip, mode: ChopMode = ChopMode.ByHits(), tape: TapeRef? = null): ChopReviewModel =
            chop(source, mode, tape, lazy { estimateTempo(source) })

        /** [chop] with a tempo already measured for this source (or not yet, but shared). */
        private fun chop(source: Snip, mode: ChopMode, tape: TapeRef?, tempoLazy: Lazy<TempoEstimate?>): ChopReviewModel {
            var slices = when (mode) {
                is ChopMode.ByHits ->
                    Chopper.byTransients(source, maxSlices = mode.maxSlices, config = mode.config(), cleanup = Chopper.SLICE_CLEANUP)
                is ChopMode.Grid ->
                    Chopper.intoEqualParts(source, mode.parts, cleanup = Chopper.SLICE_CLEANUP)
            }
            var gridStep: Double? = null
            if (mode is ChopMode.ByHits && mode.grid != GridSnap.OFF) {
                // A tape with no pulse keeps its cuts where the hits were; the label says so.
                tempoLazy.value?.let {
                    val (snappedSlices, step) = snapped(source, slices, mode.grid, it.bpm)
                    slices = snappedSlices
                    gridStep = step
                }
            }
            return ChopReviewModel(source, mode, slices, tape, tempoLazy = tempoLazy, gridStep = gridStep)
        }
    }
}
