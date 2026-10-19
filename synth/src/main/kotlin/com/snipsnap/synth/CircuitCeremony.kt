package com.snipsnap.synth

import kotlin.math.floor

/**
 * Authored input plan for one continuous C4 ROOT audition. It changes new
 * excitation and participation, never the output of a ringing source or room.
 * Ordinary Circuit renders do not consult this diagnostic plan.
 */
internal object CircuitCeremony {
    const val title = "Desert-night ceremony"
    const val seconds = 60.0
    const val cellSeconds = 2.92
    const val cells = 19
    const val phraseSeconds = CircuitPhrase.ORIGIN + cells * cellSeconds + CircuitPhrase.RELEASE_PAD
    const val paceHz = 4.0 / cellSeconds
    const val invitationGain = 1.02

    data class GainPoint(val timeSeconds: Double, val gain: Double)
    data class Stage(val name: String, val startSeconds: Double, val endSeconds: Double, val intent: String)

    val gainPoints = listOf(
        GainPoint(0.0, .42),
        GainPoint(8.92, .58),
        GainPoint(23.52, .70),
        GainPoint(38.12, .82),
        GainPoint(42.5, 1.0),
        GainPoint(46.88, .80),
        GainPoint(52.72, .54),
        GainPoint(phraseSeconds, .32),
    )

    val stages = listOf(
        Stage("Arrival", 0.0, 8.92, "Introduce the recurring figure with quieter accompaniment."),
        Stage("Gathering", 8.92, 23.52, "Establish the complete motif and its first returned answers."),
        Stage("Call and listening", 23.52, 38.12, "Leave authored listening cells for the space to answer."),
        Stage("Shared crest", 38.12, 46.88, "Reach one gradual intensity crest at the same playing pace."),
        Stage("Settling", 46.88, phraseSeconds, "Withdraw new supporting gestures and the final affirmation."),
        Stage("Residual air", phraseSeconds, seconds, "Let previously excited bodies and the space decay."),
    )

    val replyCells = setOf(5, 7, 9, 11, 14, 17)

    /** Smooth intensity at the force input; the renderer owns the finite release. */
    fun gain(timeSeconds: Double): Double {
        require(timeSeconds.isFinite()) { "Ceremony time must be finite" }
        if (timeSeconds < 0.0 || timeSeconds > phraseSeconds) return 0.0
        for (upper in 1 until gainPoints.size) {
            val end = gainPoints[upper]
            if (timeSeconds <= end.timeSeconds) {
                val start = gainPoints[upper - 1]
                val fraction = (timeSeconds - start.timeSeconds) / (end.timeSeconds - start.timeSeconds)
                val blend = fraction * fraction * (3.0 - 2.0 * fraction)
                return start.gain + (end.gain - start.gain) * blend
            }
        }
        return gainPoints.last().gain
    }

    /** These cells offer an answer; the engine still checks its actual listening arrival. */
    fun replyCell(cell: Int): Boolean = cell in replyCells

    /** A little more pressure on a selected call, before its real reflected arrival. */
    fun accentInvitation(event: Circuit.Event): Circuit.Event {
        val cell = floor((event.timeSeconds - CircuitPhrase.ORIGIN) / cellSeconds).toInt()
        return if (event.source == 1 && event.reason == "answer-invitation" && replyCell(cell))
            event.copy(energy = event.energy * invitationGain) else event
    }

    /** Score omissions apply to new events, without moving or renormalizing retained gestures. */
    fun permits(event: Circuit.Event): Boolean {
        if (!event.timeSeconds.isFinite()) return false
        val cell = floor((event.timeSeconds - CircuitPhrase.ORIGIN) / cellSeconds).toInt()
        if (cell !in 0 until cells) return false
        val ordinaryVocal = event.source == 6 && !event.reply
        val weakWood = event.source == 4 && event.reason == "weak-wood"
        val risingRattle = event.source == 3 && event.reason == "rising-support"
        val weakRattle = event.source == 3 && event.reason == "weak-support"
        val settlingRattle = event.source == 3 && event.reason == "settling-support"
        if ((cell < 2 || cell == 9 || cell == 11) && (ordinaryVocal || weakWood || risingRattle)) return false
        if (cell >= 16 && (weakWood || risingRattle || weakRattle || settlingRattle)) return false
        if (cell == 18 && ordinaryVocal) return false
        return true
    }
}
