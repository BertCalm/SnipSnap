package com.snipsnap.synth

/**
 * Small call/listen/settle score for ordinary ROOT phrases. The renderer opts
 * into it; the other voices and the private minute keep their own performance.
 * Decisions concern new events and their input energy, never ringing output.
 */
internal object CircuitRootScore {
    const val invitationGain = 1.02
    const val quietSupportGain = .50

    fun permits(event: Circuit.Event, cell: Int, cells: Int, held: Boolean): Boolean {
        val at = scoreCell(cell, cells, held) ?: return false
        if (event.reply) return true // Its listening arrival and opportunity are checked separately.
        val listening = cells > 1 && at % 2 == 1
        if (listening && event.source == 6) return false
        // The opening and clay settling remain in every cell. A listening cell
        // withdraws secondary contacts rather than speaking over its answer.
        if (listening && secondarySupport(event)) return false
        // A finite phrase's final figure settles without extra supporting taps.
        if (!held && cells > 1 && at == cells - 1 && secondarySupport(event)) return false
        return true
    }

    fun allowsReply(cell: Int, cells: Int, held: Boolean): Boolean {
        val at = scoreCell(cell, cells, held) ?: return false
        return if (!held && cells == 1) true else at % 2 == 1
    }

    /** Slight emphasis on an actual tube invitation, not an unconditional reply. */
    fun cueEnergyScale(event: Circuit.Event, cell: Int, cells: Int, held: Boolean): Double =
        if (!event.reply && event.source == 1 && event.reason == "answer-invitation" &&
            allowsReply(cell, cells, held)) invitationGain else 1.0

    /** All retained stresses scale the gesture's energy before excitation. */
    fun energyScale(event: Circuit.Event, cell: Int, cells: Int, held: Boolean): Double {
        if (!permits(event, cell, cells, held)) return 0.0
        if (event.reply) return 1.0
        // A short phrase needs both its call and a chance to answer. Keep the
        // weak wood and rising shake present at a quieter input level.
        if (!held && ((event.source == 4 && event.reason == "weak-wood") ||
                    (event.source == 3 && event.reason == "rising-support"))) return quietSupportGain
        return cueEnergyScale(event, cell, cells, held)
    }

    private fun secondarySupport(event: Circuit.Event): Boolean =
        (event.source == 3 && event.reason in setOf("rising-support", "weak-support",
            "settling-support", "quiet-subdivision", "answer-support")) ||
            (event.source == 4 && event.reason in setOf("weak-wood", "settling-wood"))

    private fun scoreCell(cell: Int, cells: Int, held: Boolean): Int? {
        if (cells <= 0) return null
        // The phrase origin may put the last held cell's late gestures just
        // after the loop seam. They still belong to that last listening cell.
        return if (held) Math.floorMod(cell, cells) else cell.takeIf { it in 0 until cells }
    }
}
