package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Experimental control-rate Fight controller: air asserts, an audible reed readies while
 * hearing it, the reed interrupts, then the air readies its counter. These are bounded
 * behavioural states, an analogy of a duet rather than a simulation of human players.
 *
 * Only the actual, smoothed pipe activity advances readiness. There is no list of entry
 * times and no deadline that invents a response to an absent source. Radiation changes
 * prominence without lowering either bore's mouth pressure below its speaking point.
 * The caller supplies the previous complete activity snapshot before advancing either
 * source, applies the returned radiation gains, and retains its existing pressure response.
 *
 * All thresholds, dwell times and pitch shapes below are audition choices, not heard or
 * acoustically calibrated constants. State and radiation are bounded by construction;
 * at most one entry can occur per minimum dwell, and no new entry begins in the release.
 */
class ObbligatoInteraction internal constructor(
    private val heldSeconds: Double,
    mischief: Float,
    seed: Int,
) {
    enum class Player { AIR, REED }

    /** An entry is the trigger of a smooth crossover, not its later equal-gain midpoint. */
    data class Event(
        val seconds: Double,
        val previousLeader: Player,
        val leader: Player,
        val readinessAtEntry: Double,
        val heardAir: Double,
        val heardReed: Double,
    )

    init {
        require(heldSeconds.isFinite() && heldSeconds > 0.0) { "interaction needs a finite held duration" }
        require(mischief.isFinite() && mischief in 0f..1f) { "interaction mischief must be in 0..1" }
    }

    private val temperament = mischief.toDouble()
    private val noise = Dsp.Noise(Dsp.seedFor("OBBLIGATO", 6, "INTERACTION", seed))
    // Draw only bounded, per-player personality here. A fresh draw at each actual entry
    // changes the next appetite, not an absolute clock or a hidden entry schedule.
    private val airPersonality = noise.next().toDouble()
    private val reedPersonality = noise.next().toDouble()
    private val airChargeRate = 1.04 + .16 * temperament + .10 * airPersonality * temperament
    private val reedChargeRate = 1.10 + .20 * temperament + .10 * reedPersonality * temperament
    val minimumDwellSeconds: Double = .32 - .05 * temperament
    val crossoverSeconds: Double = .11
    private var airThreshold = nextThreshold(Player.AIR)
    private var reedThreshold = nextThreshold(Player.REED)
    private var seconds = 0.0
    private var leaderAge = 0.0
    private var airAudible = false
    private var reedAudible = false
    private var crossoverAge = crossoverSeconds
    private var crossoverStartAirShare = 1.0
    private var airShare = 1.0
    private var lastAirEntry = 0.0
    private var lastReedEntry = -PITCH_PUSH_SECONDS
    private val ledger = ArrayList<Event>()

    var leader: Player = Player.AIR
        private set
    var airReadiness: Double = 0.0
        private set
    var reedReadiness: Double = 0.0
        private set
    var airPresence: Double = 1.0
        private set
    var reedPresence: Double = RADIATION_FLOOR
        private set
    var airPitchPush: Double = 0.0
        private set
    var reedPitchPush: Double = 0.0
        private set
    val events: List<Event> get() = ledger

    /**
     * Advance once per control block, using normalized outgoing activity in 0..1.
     * [responses] false removes activity-driven entries; the opening air assertion stays.
     * A causal source-weakening probe belongs in the caller, where it changes actual source
     * radiation and therefore these meters. The controller has no privileged probe input.
     * This method allocates only when recording an entry, never for an ordinary block.
     */
    internal fun step(dtSeconds: Double, heardAir: Double, heardReed: Double, responses: Boolean = true) {
        require(dtSeconds.isFinite() && dtSeconds > 0.0 && dtSeconds <= .05) {
            "interaction requires a positive control step no longer than 50 ms"
        }
        val air = activity(heardAir)
        val reed = activity(heardReed)
        seconds += dtSeconds
        leaderAge += dtSeconds
        crossoverAge += dtSeconds
        airAudible = audible(air, airAudible)
        reedAudible = audible(reed, reedAudible)

        val mayAnswer = responses && airAudible && reedAudible && seconds < heldSeconds - crossoverSeconds
        // The leader is spending its gesture, not saving another interruption while it
        // speaks. The challenger forgets readiness when either physical source falls quiet.
        if (leader == Player.AIR) {
            airReadiness = 0.0
            reedReadiness = readiness(reedReadiness, air, reedChargeRate, mayAnswer, dtSeconds)
            if (mayAnswer && leaderAge >= minimumDwellSeconds && reedReadiness >= reedThreshold) {
                enter(Player.REED, reedReadiness, air, reed)
            }
        } else {
            reedReadiness = 0.0
            airReadiness = readiness(airReadiness, reed, airChargeRate, mayAnswer, dtSeconds)
            if (mayAnswer && leaderAge >= minimumDwellSeconds && airReadiness >= airThreshold) {
                enter(Player.AIR, airReadiness, air, reed)
            }
        }

        val progress = (crossoverAge / crossoverSeconds).coerceIn(0.0, 1.0)
        val curve = .5 - .5 * cos(PI * progress)
        val targetAirShare = if (leader == Player.AIR) 1.0 else 0.0
        airShare = crossoverStartAirShare + (targetAirShare - crossoverStartAirShare) * curve
        // Complementary power leaves both sources present through every handoff. Since
        // the bores keep speaking, this envelope cannot cause the earlier capture failure.
        airPresence = radiation(airShare, true)
        reedPresence = radiation(airShare, false)
        airPitchPush = pitchPush(seconds - lastAirEntry)
        reedPitchPush = -pitchPush(seconds - lastReedEntry)
    }

    private fun enter(next: Player, readiness: Double, air: Double, reed: Double) {
        ledger += Event(seconds, leader, next, readiness, air, reed)
        leader = next
        leaderAge = 0.0
        crossoverStartAirShare = airShare
        crossoverAge = 0.0
        airReadiness = 0.0
        reedReadiness = 0.0
        if (next == Player.AIR) {
            lastAirEntry = seconds
            airThreshold = nextThreshold(Player.AIR)
        } else {
            lastReedEntry = seconds
            reedThreshold = nextThreshold(Player.REED)
        }
    }

    private fun nextThreshold(player: Player): Double {
        val personality = if (player == Player.AIR) airPersonality else reedPersonality
        val base = if (player == Player.AIR) .46 else .42
        return (base + .055 * temperament * personality + .07 * temperament * noise.next()).coerceIn(.30, .60)
    }

    private fun readiness(value: Double, opponent: Double, rate: Double, mayAnswer: Boolean, dt: Double): Double =
        (value + dt * if (mayAnswer) opponent * rate - .08 else -FORGET_PER_SECOND).coerceIn(0.0, 1.0)

    private fun pitchPush(age: Double): Double {
        if (age <= 0.0 || age >= PITCH_PUSH_SECONDS) return 0.0
        val curve = sin(PI * age / PITCH_PUSH_SECONDS)
        return (4.0 + temperament) * curve * curve
    }

    private fun activity(value: Double) = if (value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0
    private fun audible(value: Double, wasAudible: Boolean) = value >= if (wasAudible) AUDIBLE_OFF else AUDIBLE_ON

    internal fun radiationReader() = Radiation(ledger, crossoverSeconds)

    /** Audio-rate reading of recorded decisions, independent of the control stepping rate. */
    internal class Radiation(private val entries: List<Event>, private val crossover: Double) {
        private var next = 0
        private var entry: Event? = null
        var air = 1.0
            private set
        var reed = RADIATION_FLOOR
            private set

        fun at(seconds: Double) {
            while (next < entries.size && entries[next].seconds <= seconds) entry = entries[next++]
            val event = entry
            val share = if (event == null) 1.0 else {
                val progress = ((seconds - event.seconds) / crossover).coerceIn(0.0, 1.0)
                val curve = .5 - .5 * cos(PI * progress)
                val start = if (event.previousLeader == Player.AIR) 1.0 else 0.0
                val target = if (event.leader == Player.AIR) 1.0 else 0.0
                start + (target - start) * curve
            }
            air = radiation(share, true)
            reed = radiation(share, false)
        }
    }

    companion object {
        const val RADIATION_FLOOR = .18
        private const val FLOOR_POWER = RADIATION_FLOOR * RADIATION_FLOOR
        private const val AUDIBLE_ON = .045
        private const val AUDIBLE_OFF = .020
        private const val FORGET_PER_SECOND = .70
        private const val PITCH_PUSH_SECONDS = .32
        private fun radiation(airShare: Double, air: Boolean): Double =
            sqrt(FLOOR_POWER + (1.0 - FLOOR_POWER) * (if (air) airShare else 1.0 - airShare))
    }
}
