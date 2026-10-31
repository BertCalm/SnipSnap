package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finite, control-rate musical memory for the air/reed duet. A player hears the previous
 * complete activity snapshot, remembers the contour of an actual turn, and shapes an answer.
 * Breath reserves are bounded behavioural proxies, not a simulation of human lungs.
 *
 * Play imitates a heard contour; Fight can renew an assertion before yielding; Protect
 * covers an air player that has been heard and is losing activity or spending its reserve.
 * The final shared settling is an authored ending, not an emergent physical claim.
 * All timing, articulation and reserve laws are proposed listening shapes.
 */
class ObbligatoConversation internal constructor(
    private val mode: Mode,
    private val heldSeconds: Double,
    mischief: Float,
    seed: Int,
    private val overlap: Boolean = false,
    private val moreOverlap: Boolean = false,
) {
    enum class Mode { PLAY, FIGHT, PROTECT }
    enum class Player { AIR, REED }

    /** The memory describes the preceding heard turn, not a programmed copy of its envelope. */
    data class Event(
        val seconds: Double,
        val actor: Player,
        val kind: String,
        val rememberedDuration: Double,
        val rememberedIntensity: Double,
        val rememberedSlope: Double,
        val strength: Double,
        val duration: Double,
        val airReserve: Double,
        val reedReserve: Double,
        val heardAir: Double,
        val heardReed: Double,
    )

    init {
        require(heldSeconds.isFinite() && heldSeconds > .5)
        require(mischief.isFinite() && mischief in 0f..1f)
    }

    private val temperament = mischief.toDouble()
    private val noise = Dsp.Noise(Dsp.seedFor("OBBLIGATO", 8, "CONVERSATION", mode, seed))
    private val airPersonality = noise.next().toDouble()
    private val reedPersonality = noise.next().toDouble()
    private val gestures = ArrayList<Gesture>()
    private val ledger = ArrayList<Event>()
    private val live = PerformanceReader(gestures, overlap, moreOverlap)
    private var seconds = 0.0
    private var turnAge = 0.0
    private var turnIntegral = 0.0
    private var turnAudibleSeconds = 0.0
    private var turnFirst = 0.0
    private var turnLast = 0.0
    private var bodyStarted = false
    private var bodyStartsAt = CROSSOVER + .04
    private var leader = Player.AIR
    private var airEver = false
    private var reedEver = false
    private var airPeak = 0.0
    private var readiness = 0.0
    private var supportReadiness = 0.0
    private var heldGround = false
    private var supported = false
    private var settling = false
    private var currentDuration = .62 + .06 * temperament * airPersonality

    var airReserve = 1.0
        private set
    var reedReserve = 1.0
        private set
    val events: List<Event> get() = ledger
    val airPresence get() = live.airPresence
    val reedPresence get() = live.reedPresence
    val airPressure get() = live.airPressure
    val reedPressure get() = live.reedPressure
    val airPitch get() = live.airPitch
    val reedPitch get() = live.reedPitch
    val airTurbulence get() = live.airTurbulence
    val reedTurbulence get() = live.reedTurbulence
    val airTone get() = live.airTone
    val reedTone get() = live.reedTone

    init {
        gestures += Gesture(0.0, Player.AIR, "OPENING", .83, currentDuration, .30,
            1.0, 1.0, FLOOR, .64, .57, 1.0, 1.0, 0.0, 0.0, 1.0, 1.0)
        live.at(0.0)
    }

    /** No foreign activity enters a disabled player's state, including its reserve or memory. */
    internal fun step(dtSeconds: Double, heardAir: Double, heardReed: Double, responses: Boolean = true) {
        require(dtSeconds.isFinite() && dtSeconds > 0.0 && dtSeconds <= .05)
        seconds += dtSeconds
        turnAge += dtSeconds
        live.at(seconds)
        if (!responses) return
        val air = activity(heardAir)
        val reed = activity(heardReed)
        airEver = airEver || air >= AUDIBLE
        reedEver = reedEver || reed >= AUDIBLE
        airPeak = maxOf(airPeak, air)
        val heard = if (leader == Player.AIR) air else reed
        if (heard >= AUDIBLE) {
            turnAudibleSeconds += dtSeconds
            // The compulsory handoff rises even for a level sustained tone. Remember
            // the voiced body after the crossover and one meter time constant, so a
            // reply does not mistake that entrance ramp for the player's own contour.
            if (turnAge >= bodyStartsAt) {
                if (!bodyStarted) { turnFirst = heard; bodyStarted = true }
                turnLast = heard
            }
        }
        turnIntegral += heard * dtSeconds
        // A foreground gesture spends reserve; a supporting player recovers. The reserve
        // changes decisions and answer strength, never lowers pipe pressure out of capture.
        airReserve = reserve(airReserve, live.airPresence, air >= AUDIBLE, dtSeconds)
        reedReserve = reserve(reedReserve, live.reedPresence, reed >= AUDIBLE, dtSeconds)
        if (settling || seconds >= heldSeconds - CROSSOVER) return
        val endingStart = heldSeconds - minOf(.72, heldSeconds * .22)
        if (seconds >= endingStart && airEver && reedEver && turnAge >= CROSSOVER) {
            enter(if (mode == Mode.PROTECT) Player.REED else leader, "SETTLE", air, reed)
            settling = true
            return
        }
        when (mode) {
            Mode.PLAY -> play(dtSeconds, air, reed)
            Mode.FIGHT -> fight(dtSeconds, air, reed)
            Mode.PROTECT -> protect(dtSeconds, air, reed)
        }
    }

    private fun play(dt: Double, air: Double, reed: Double) {
        val available = air >= AUDIBLE && reed >= AUDIBLE
        val heard = if (leader == Player.AIR) air else reed
        readiness = charge(readiness, if (available) heard * (1.12 + .18 * temperament) - .04 else -.8, dt)
        val threshold = .28 + .25 * currentDuration
        if (available && turnAge >= MIN_DWELL && readiness >= threshold) {
            enter(other(leader), "ANSWER", air, reed)
        }
    }

    private fun fight(dt: Double, air: Double, reed: Double) {
        val available = air >= AUDIBLE && reed >= AUDIBLE
        val heard = if (leader == Player.AIR) air else reed
        readiness = charge(readiness, if (available) heard * (1.25 + .18 * temperament) - .04 else -.8, dt)
        val threshold = .27 + .10 * currentDuration - .06 * temperament
        if (!available || turnAge < MIN_DWELL || readiness < threshold) return
        val ownReserve = if (leader == Player.AIR) airReserve else reedReserve
        val intensity = turnIntegral / turnAge.coerceAtLeast(.001)
        // Holding ground is a distinct renewed articulation. It requires heard intensity
        // and remaining effort; one renewal is allowed before this player must yield.
        if (!heldGround && temperament > .10 && ownReserve > .64 && intensity > .40) {
            enter(leader, "HOLD", air, reed)
            heldGround = true
        } else {
            enter(other(leader), "COUNTER", air, reed)
            heldGround = false
        }
    }

    private fun protect(dt: Double, air: Double, reed: Double) {
        if (supported) return
        // Remembered presence permits covering a temporarily silent partner. A player
        // absent from the outset cannot create a need for protection.
        if (!airEver || reed < AUDIBLE) {
            supportReadiness = charge(supportReadiness, -.8, dt)
            return
        }
        val loss = if (airPeak >= AUDIBLE) (1.0 - air / airPeak).coerceIn(0.0, 1.0) else 0.0
        val spent = (.88 - airReserve).coerceAtLeast(0.0)
        supportReadiness = charge(supportReadiness, 1.8 * loss + .9 * spent + .10, dt)
        if (turnAge >= .65 && supportReadiness >= .19 - .04 * temperament) {
            enter(Player.REED, "SUPPORT", air, reed)
            supported = true
        }
    }

    private fun enter(next: Player, kind: String, air: Double, reed: Double) {
        val passing = next != leader && kind != "SETTLE"
        val rememberedDuration = turnAudibleSeconds.coerceIn(0.0, heldSeconds)
        val rememberedIntensity = (turnIntegral / turnAge.coerceAtLeast(.001)).coerceIn(0.0, 1.0)
        val rememberedSlope = (turnLast - turnFirst).coerceIn(-1.0, 1.0)
        val personality = if (next == Player.AIR) airPersonality else reedPersonality
        val ownReserve = if (next == Player.AIR) airReserve else reedReserve
        val strength = when (kind) {
            "SETTLE" -> .62
            "SUPPORT" -> .78 + .17 * (1.0 - airReserve)
            "HOLD" -> .72 + .16 * ownReserve
            else -> .65 + .28 * rememberedIntensity + .05 * temperament * personality
        }.coerceIn(.60, 1.0)
        val duration = when (kind) {
            "SETTLE" -> (heldSeconds - seconds).coerceAtLeast(CROSSOVER)
            "SUPPORT" -> .72 + .25 * rememberedDuration
            "HOLD" -> .40 + .15 * ownReserve
            else -> if (mode == Mode.PLAY) .36 + .55 * rememberedDuration + .12 * rememberedSlope
                else .32 + .45 * rememberedDuration - .08 * temperament * personality
        }.coerceIn(.32, 1.20)
        val slope = when (mode) {
            Mode.PLAY -> rememberedSlope
            Mode.FIGHT -> -rememberedSlope + .15 * temperament * personality
            Mode.PROTECT -> -.35
        }.coerceIn(-1.0, 1.0)
        val share = when {
            kind == "SETTLE" && mode == Mode.PROTECT -> .05
            // The independent pipes can oppose phase near unison. Keep the current
            // lead while both soften, so the ending does not become a cancellation gap.
            kind == "SETTLE" -> if (next == Player.AIR) .94 else .06
            mode == Mode.PROTECT -> .22
            next == Player.AIR -> 1.0
            else -> 0.0
        }
        ledger += Event(seconds, next, kind, rememberedDuration, rememberedIntensity,
            rememberedSlope, strength, duration, airReserve, reedReserve, air, reed)
        gestures += Gesture(seconds, next, kind, strength, duration, slope, share,
            live.airPresence, live.reedPresence, live.airTone, live.reedTone,
            live.airPressure, live.reedPressure, live.airPitch, live.reedPitch,
            live.airTurbulence, live.reedTurbulence, passing)
        leader = next
        currentDuration = duration
        turnAge = 0.0
        turnIntegral = 0.0
        turnAudibleSeconds = 0.0
        turnFirst = 0.0
        turnLast = 0.0
        bodyStarted = false
        bodyStartsAt = (if (overlap && passing) {
            if (moreOverlap) MORE_OVERLAP_SECONDS else OVERLAP_SECONDS
        } else CROSSOVER) + .04
        readiness = 0.0
        live.at(seconds)
    }

    internal fun performanceReader() = PerformanceReader(gestures, overlap, moreOverlap)

    internal data class Gesture(
        val seconds: Double, val actor: Player, val kind: String, val strength: Double,
        val duration: Double, val slope: Double, val airShare: Double,
        val fromAir: Double, val fromReed: Double, val fromAirTone: Double, val fromReedTone: Double,
        val fromAirPressure: Double, val fromReedPressure: Double,
        val fromAirPitch: Double, val fromReedPitch: Double,
        val fromAirTurbulence: Double, val fromReedTurbulence: Double,
        val passing: Boolean = false,
    )

    /** Replays the recorded, value-continuous gesture curves after source conditioning. */
    internal class PerformanceReader internal constructor(
        private val gestures: List<Gesture>,
        private val overlap: Boolean = false,
        moreOverlap: Boolean = false,
    ) {
        // Saved model 9 keeps its original shape. Model 10 adds time at the edges,
        // leaving the near-equal middle brief enough to preserve a distinct lead.
        private val prelude = if (moreOverlap) MORE_OVERLAP_PRELUDE else OVERLAP_PRELUDE
        private val tail = if (moreOverlap) MORE_OVERLAP_TAIL else OVERLAP_TAIL
        private val overlapSeconds = if (moreOverlap) MORE_OVERLAP_SECONDS else OVERLAP_SECONDS
        private val support = if (moreOverlap) MORE_OVERLAP_SUPPORT else OVERLAP_SUPPORT
        private var index = 0
        var airPresence = 1.0
            private set
        var reedPresence = FLOOR
            private set
        var airTone = .64
            private set
        var reedTone = .57
            private set
        var airPressure = 1.0
            private set
        var reedPressure = 1.0
            private set
        var airPitch = 0.0
            private set
        var reedPitch = 0.0
            private set
        var airTurbulence = 1.0
            private set
        var reedTurbulence = 1.0
            private set

        fun at(seconds: Double) {
            while (index + 1 < gestures.size && gestures[index + 1].seconds <= seconds) index++
            val g = gestures[index]
            val age = (seconds - g.seconds).coerceAtLeast(0.0)
            val crossover = smooth(age / CROSSOVER)
            val progress = (age / g.duration).coerceIn(0.0, 1.0)
            val wave = sin(PI * progress)
            val bump = (wave * wave * (1.0 + .35 * g.slope * (2.0 * progress - 1.0))).coerceIn(0.0, 1.0)
            val settling = g.kind == "SETTLE"
            val airActive = g.actor == Player.AIR
            val airBase = sqrt(FLOOR * FLOOR + (1.0 - FLOOR * FLOOR) * g.airShare)
            val reedBase = sqrt(FLOOR * FLOOR + (1.0 - FLOOR * FLOOR) * (1.0 - g.airShare))
            val gestureGain = if (settling) .86 else .84 + .14 * g.strength * bump
            val a = airBase * if (airActive || settling) gestureGain else 1.0
            val r = reedBase * if (!airActive || settling) gestureGain else 1.0
            if (!overlap || !g.passing) {
                airPresence = sqrt(mix(g.fromAir * g.fromAir, a * a, crossover)).coerceIn(FLOOR, 1.0)
                reedPresence = sqrt(mix(g.fromReed * g.fromReed, r * r, crossover)).coerceIn(FLOOR, 1.0)
            } else {
                // Let the answer join before the old phrase yields, then retain its
                // outgoing tail. Keep the balanced middle brief: near-unison pipes
                // can oppose phase if held at equal weight for too long.
                val fromIn = if (airActive) g.fromAir else g.fromReed
                val fromOut = if (airActive) g.fromReed else g.fromAir
                val targetIn = if (airActive) a else r
                val targetOut = if (airActive) r else a
                val joinIn = maxOf(fromIn, minOf(support, targetIn))
                val joinOut = maxOf(targetOut, support)
                val incoming: Double
                val outgoing: Double
                when {
                    age < prelude -> {
                        incoming = mix(fromIn * fromIn, joinIn * joinIn, smooth(age / prelude))
                        outgoing = fromOut * fromOut
                    }
                    age < prelude + CROSSOVER -> {
                        val f = smooth((age - prelude) / CROSSOVER)
                        incoming = mix(joinIn * joinIn, targetIn * targetIn, f)
                        outgoing = mix(fromOut * fromOut, joinOut * joinOut, f)
                    }
                    else -> {
                        incoming = targetIn * targetIn
                        outgoing = mix(joinOut * joinOut, targetOut * targetOut,
                            smooth((age - prelude - CROSSOVER) / tail))
                    }
                }
                // Both retain one common power budget; overlapping does not grant a
                // second full-strength voice or independently normalize either player.
                val budget = mix(g.fromAir * g.fromAir + g.fromReed * g.fromReed,
                    a * a + r * r, smooth(age / overlapSeconds))
                val correction = minOf(1.0, budget / (incoming + outgoing).coerceAtLeast(1e-12))
                airPresence = sqrt((if (airActive) incoming else outgoing) * correction).coerceIn(FLOOR, 1.0)
                reedPresence = sqrt((if (airActive) outgoing else incoming) * correction).coerceIn(FLOOR, 1.0)
            }
            val tone = if (settling) .40 else .46 + .48 * g.strength * bump
            airTone = mix(g.fromAirTone, if (airActive || settling) tone else .48, crossover).coerceIn(0.0, 1.0)
            reedTone = mix(g.fromReedTone, if (!airActive || settling) tone else .44, crossover).coerceIn(0.0, 1.0)
            airPressure = mix(g.fromAirPressure, 1.0 + if (airActive && !settling) .020 * g.strength * bump else 0.0, crossover)
            reedPressure = mix(g.fromReedPressure, 1.0 + if (!airActive && !settling) .020 * g.strength * bump else 0.0, crossover)
            val inflection = if (settling) 0.0 else 2.2 * g.strength * g.slope * bump
            airPitch = mix(g.fromAirPitch, if (airActive) inflection else 0.0, crossover)
            reedPitch = mix(g.fromReedPitch, if (!airActive) inflection else 0.0, crossover)
            val airBreath = if (settling) .72 else if (airActive) .76 + .30 * bump else .84
            val reedBreath = if (settling) .76 else if (!airActive) .84 + .24 * bump else .90
            airTurbulence = mix(g.fromAirTurbulence, airBreath, crossover)
            reedTurbulence = mix(g.fromReedTurbulence, reedBreath, crossover)
        }

        companion object {
            private fun smooth(value: Double): Double = .5 - .5 * cos(PI * value.coerceIn(0.0, 1.0))
            private fun mix(a: Double, b: Double, f: Double) = a + (b - a) * f
        }
    }

    private fun activity(value: Double) = if (value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0
    private fun charge(value: Double, rate: Double, dt: Double) = (value + rate * dt).coerceIn(0.0, 1.0)
    private fun reserve(value: Double, prominence: Double, audible: Boolean, dt: Double): Double =
        (value + dt * if (audible && prominence > .65) -.26 * prominence else .22).coerceIn(.12, 1.0)
    private fun other(player: Player) = if (player == Player.AIR) Player.REED else Player.AIR

    companion object {
        const val FLOOR = .18
        const val MIN_DWELL = .32
        const val CROSSOVER = .14
        const val OVERLAP_PRELUDE = .06
        const val OVERLAP_TAIL = .04
        const val OVERLAP_SECONDS = .24
        const val OVERLAP_SUPPORT = .35
        const val MORE_OVERLAP_PRELUDE = .085
        const val MORE_OVERLAP_TAIL = .065
        const val MORE_OVERLAP_SECONDS = MORE_OVERLAP_PRELUDE + CROSSOVER + MORE_OVERLAP_TAIL
        const val MORE_OVERLAP_SUPPORT = .40
        private const val AUDIBLE = .025
    }
}
