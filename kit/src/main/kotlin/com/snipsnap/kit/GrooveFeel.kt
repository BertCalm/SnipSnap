package com.snipsnap.kit

import com.snipsnap.mpc3.Mpc3Clip

/**
 * Groove templates — the MPC's own legendary trick: steal the *feel*, not
 * the notes. [extract] reads how a donor clip leans — how late or early
 * each 16th-position lands, how hard it hits relative to the rest — and
 * [apply] lays that pocket onto another clip's notes: your pattern, that
 * drummer's timing.
 *
 * Pure note-list arithmetic, deterministic, and honest about coverage: a
 * position the donor never plays contributes nothing, so the target stays
 * straight there rather than guessing.
 */
object GrooveFeel {

    /** Feel resolves per 16th within a 4/4 bar. */
    const val POSITIONS = 16

    /**
     * Ceiling on a generated offset at full weight: 90 pulses, three eighths
     * of a 16th. Wide enough to lean audibly, narrow enough that a note stays
     * clearly attached to its own step rather than going ambiguous between two.
     */
    const val FEEL_MAX_OFFSET_PULSES: Long = 90L

    /**
     * How far each 16th position is allowed to lean, as a fraction of
     * [FEEL_MAX_OFFSET_PULSES]. A groove leans on its weak beats, not on its
     * pulse: a large offset on a downbeat does not read as human, it reads as
     * wrong, because the downbeat is what the listener counts from. Order is
     * 1 e & a, repeated for four beats.
     */
    val FEEL_WEIGHT: List<Float> = listOf(
        0.25f, 1f, 0.6f, 1f,
        0.25f, 1f, 0.6f, 1f,
        0.25f, 1f, 0.6f, 1f,
        0.25f, 1f, 0.6f, 1f,
    )

    /**
     * A rolled feel: sixteen offsets drawn once from [seed], weighted by
     * metric position. Generated at FULL magnitude — the axis position scales
     * it in [applyFeel], so rerolling and moving the slider stay independent.
     *
     * Timing only. Accents are null throughout: this app's material is chopped
     * breaks whose velocities are already a real performance, so random
     * dynamics would be noise on signal. Accent STRUCTURE is worth having and
     * comes from a real donor — subsystem B.
     */
    fun generated(seed: Int): Template {
        val rnd = kotlin.random.Random(seed)
        return Template(
            offsets = (0 until POSITIONS).map { pos ->
                val span = FEEL_MAX_OFFSET_PULSES.toFloat() * FEEL_WEIGHT[pos]
                Math.round(((rnd.nextFloat() * 2f - 1f) * span).toDouble())
            },
            accents = List(POSITIONS) { null },
        )
    }

    data class Template(
        /** Pulses late (+) or early (−) per 16th position; null = donor silent there. */
        val offsets: List<Long?>,
        /** Velocity vs the donor's own average per position; null = donor silent there. */
        val accents: List<Float?>,
        /**
         * Constant push (+) or drag (−) for a whole lane, in pulses — the pocket.
         * Separate from [offsets] on purpose: a lane's lean is one number
         * estimated from every note that lane plays, so it survives a donor
         * too sparse to fill sixteen positions. Absent key = that lane is
         * straight. Notes on pads outside the five named lanes have no lane
         * and so take [offsets] only.
         *
         * An extractor that learns BOTH layers from one donor must decompose,
         * not double-count: take the lane median first, then compute
         * [offsets] from the residual. Applying two independently-learned
         * layers overshoots the lean. Nothing in subsystem A extracts — this
         * note is here for whoever implements it.
         */
        val laneOffsets: Map<GrooveEdit.Lane, Long> = emptyMap(),
        /** Velocity scale for a whole lane; absent key = unscaled. Same decomposition rule as [laneOffsets], by division. */
        val laneAccents: Map<GrooveEdit.Lane, Float> = emptyMap(),
    ) {
        init {
            require(offsets.size == POSITIONS && accents.size == POSITIONS) {
                "a template covers exactly $POSITIONS positions"
            }
        }
    }

    fun extract(donor: Mpc3Clip): Template {
        require(donor.notes.isNotEmpty()) { "the donor clip has no notes to learn from" }
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val offsets = Array(POSITIONS) { mutableListOf<Long>() }
        val velocities = Array(POSITIONS) { mutableListOf<Float>() }
        for (n in donor.notes) {
            val grid = (n.timePulses + s16 / 2) / s16 * s16
            val pos = ((grid / s16) % POSITIONS).toInt()
            offsets[pos] += n.timePulses - grid
            velocities[pos] += n.velocity
        }
        val meanVel = donor.notes.map { it.velocity }.average().toFloat().coerceAtLeast(1e-3f)
        return Template(
            offsets = offsets.map { if (it.isEmpty()) null else median(it) },
            accents = velocities.map { if (it.isEmpty()) null else (it.average().toFloat() / meanVel) },
        )
    }

    /**
     * The target's notes snap to the 16th grid, then take the donor's
     * pocket: the position's offset in time, the position's accent scaling
     * the note's own velocity. Donor-silent positions stay straight and
     * unscaled.
     */
    fun apply(template: Template, clip: Mpc3Clip, suffix: String = "Feel"): Mpc3Clip {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val limit = clip.lengthPulses
        return clip.copy(
            name = GrooveVariations.variantName(clip.name, suffix),
            notes = clip.notes.map { n ->
                val grid = (n.timePulses + s16 / 2) / s16 * s16
                val pos = ((grid / s16) % POSITIONS).toInt()
                n.copy(
                    timePulses = (grid + (template.offsets[pos] ?: 0L)).coerceIn(0L, limit - 1),
                    velocity = (n.velocity * (template.accents[pos] ?: 1f)).coerceIn(0.05f, 1f),
                )
            },
        )
    }

    /**
     * One bipolar axis. [t] runs −1 (fully snapped to the 16th grid) through
     * 0 (exactly as played) to +1 (fully leaned by [template]).
     *
     * TIGHTEN and HUMANIZE are the same machine read at two signs, which is
     * why this is one function and not two. The centre is not an arbitrary
     * midpoint — it is the performance itself, which is what lets "AS PLAYED"
     * be an honest label.
     *
     * Does NOT rename the clip, unlike [apply]. [GrooveVariations.standard]
     * derives every variation's name from its input and MIDI export uses
     * those names as filenames, so renaming here would silently rename every
     * exported file.
     *
     * The pocket ([Template.laneOffsets]) is added AFTER the blend and is
     * unaffected by [t], so "fully tight with a dragging snare" stays
     * reachable — that combination is most of the MPC catalogue, and a naive
     * single control would make it impossible.
     *
     * The `t == 0f` short-circuit deliberately skips [GrooveEdit.dedupeLouder].
     * Every other value of [t] runs the map through it, so a clip carrying two
     * notes at an identical (note, timePulses) address keeps both at the
     * centre detent and collapses to the louder one at any other position on
     * the axis. That is not an oversight: such clips are genuinely reachable
     * — [CapturedGroove.clip] with a `quantizeTo` grid can bucket two hits
     * from the same pad onto one pulse, and [GrooveStore.clipFromJson] reads
     * `groove.json` written by another tool, with no uniqueness check on the
     * way in ([Mpc3Clip]'s own constructor enforces none either). AS PLAYED
     * must return exactly what is stored; a centre detent that silently edits
     * the groove — even just by merging duplicate notes — would be a worse
     * failure than a note-count step the moment [t] moves off zero.
     */
    fun applyFeel(clip: Mpc3Clip, t: Float, template: Template): Mpc3Clip {
        require(t in -1f..1f) { "feel wants -1..1, got $t" }
        // Structural, not arithmetic: "as played" must not depend on a lerp
        // round-tripping Long pulses through Float.
        if (t == 0f) return clip
        val grid = Mpc3Clip.PULSES_PER_16TH
        // The clip's own length, not `bars * 3840`: an ORBIT clip declares
        // its bar, and wrapping a 3/4 clip against a 4/4 one would put a
        // note past the end that `Mpc3Clip` then refuses to hold.
        val limit = clip.lengthPulses
        return clip.copy(
            notes = GrooveEdit.dedupeLouder(
                clip.notes.map { n ->
                    // UNWRAPPED on purpose. A hit at 7600 in a 2-bar clip
                    // snaps to 7680, which wraps to 0; lerping toward the
                    // wrapped target would send a half-tight note to 3800,
                    // the middle of the bar, instead of 7640. Wrap last.
                    val snapped = (n.timePulses + grid / 2) / grid * grid
                    val pos = ((snapped / grid) % POSITIONS).toInt()
                    val lane = GrooveEdit.Lane.entries.firstOrNull { GrooveEdit.noteFor(it) == n.note }
                    val lead = if (t < 0f) {
                        n.timePulses + Math.round((snapped - n.timePulses) * -t.toDouble())
                    } else {
                        n.timePulses + Math.round(t.toDouble() * (template.offsets[pos] ?: 0L))
                    }
                    val withPocket = lead + (lane?.let { template.laneOffsets[it] } ?: 0L)
                    // Dynamics ride the LOOSE half only - tightening timing
                    // is not a reason to flatten velocity.
                    val velocity = if (t > 0f) {
                        val scale = (template.accents[pos] ?: 1f) * (lane?.let { template.laneAccents[it] } ?: 1f)
                        val target = n.velocity * scale
                        (n.velocity + (target - n.velocity) * t).coerceIn(0.05f, 1f)
                    } else {
                        n.velocity
                    }
                    n.copy(timePulses = LiveRecord.wrappedInto(withPocket, limit), velocity = velocity)
                },
            ),
        )
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
