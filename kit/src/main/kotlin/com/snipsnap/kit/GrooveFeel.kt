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
        val limit = clip.bars * Mpc3Clip.PULSES_PER_BAR
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

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
