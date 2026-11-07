package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

enum class OtisOrganPercussion { SECOND, THIRD }
enum class OtisOrganRotary { BRAKE, SLOW, FAST }

/** Settings for a finite listening prototype, independent of the shipped TONEWHEEL patches. */
data class OtisOrganSettings(
    /** Hammond order: 16', 5 1/3', 8', 4', 2 2/3', 2', 1 3/5', 1 1/3', 1'. */
    val drawbars: List<Float> = listOf(1f, .85f, 1f, .42f, .22f, .30f, .10f, .12f, .06f),
    val click: Float = .45f,
    val percussionLevel: Float = .32f,
    val percussionHarmonic: OtisOrganPercussion = OtisOrganPercussion.THIRD,
    /** Time for percussion to fall 60 dB, rather than an exponential time constant. */
    val percussionDecaySeconds: Double = .30,
    val leakage: Float = .38f,
    val drive: Float = .20f,
    val attackSeconds: Double = .004,
    val releaseSeconds: Double = .15,
    val cabinetHz: Double = 5600.0,
    val rotary: Boolean = false,
    val rotaryStart: OtisOrganRotary = OtisOrganRotary.SLOW,
    val rotaryTarget: OtisOrganRotary = OtisOrganRotary.FAST,
    val rotaryChangeSeconds: Double = .75,
    val rotaryDepth: Float = .82f,
) {
    init {
        require(drawbars.size == 9 && drawbars.all { it.isFinite() && it in 0f..1f }) {
            "drawbars must contain nine finite values in 0..1"
        }
        for ((name, value) in listOf("click" to click, "percussionLevel" to percussionLevel,
            "leakage" to leakage, "drive" to drive, "rotaryDepth" to rotaryDepth)) {
            require(value.isFinite() && value in 0f..1f) { "$name must be finite and in 0..1" }
        }
        require(attackSeconds.isFinite() && attackSeconds in 0.0..2.0) { "attackSeconds must be finite and in 0..2" }
        require(releaseSeconds.isFinite() && releaseSeconds in .005..3.0) { "releaseSeconds must be finite and in .005..3" }
        require(percussionDecaySeconds.isFinite() && percussionDecaySeconds in .02..3.0) {
            "percussionDecaySeconds must be finite and in .02..3"
        }
        require(cabinetHz.isFinite() && cabinetHz in 200.0..20_000.0) { "cabinetHz must be finite and in 200..20000" }
        require(rotaryChangeSeconds.isFinite() && rotaryChangeSeconds in 0.0..20.0) {
            "rotaryChangeSeconds must be finite and in 0..20"
        }
    }
}

/**
 * Nine proper oscillators, finite key gates, and one optional cabinet rotor.
 * This returns unlevelled audio at the requested synthesis rate: the audition
 * wrapper owns oversampling, decimation and level matching for every family.
 */
object OtisOrganPrototype {
    private val ratios = doubleArrayOf(.5, 1.5, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0)
    private val leakageSemitones = intArrayOf(-2, -1, 1, 2)
    private val leakageGains = doubleArrayOf(.010, .026, .020, .008)
    private const val TWO_PI = 2.0 * PI
    private const val TAIL_SECONDS = .05

    fun synthesize(
        notes: List<OtisPrototypeNote>,
        settings: OtisOrganSettings,
        seed: Int,
        rate: Int,
    ): FloatArray {
        require(rate >= 8000) { "organ synthesis rate must be at least 8000 Hz" }
        require(notes.isNotEmpty()) { "organ audition needs a note" }
        require(notes.all { it.midi in 0..127 && it.startSeconds.isFinite() && it.startSeconds >= 0.0 &&
            it.gateSeconds.isFinite() && it.gateSeconds > 0.0 && it.velocity.isFinite() && it.velocity in 0f..1f }) {
            "organ notes need a MIDI pitch, nonnegative start, positive gate, and velocity in 0..1"
        }

        // A muted note is useful in an audition recipe, but it must not
        // occupy a key or consume the next audible note's percussion.
        val sorted = notes.withIndex().filter { it.value.velocity > 0f }
            .sortedWith(compareBy({ it.value.startSeconds }, { it.index }))
        val seconds = notes.maxOf { it.startSeconds + it.gateSeconds } + settings.releaseSeconds + TAIL_SECONDS
        require(seconds * rate <= Int.MAX_VALUE) { "organ audition is too long" }
        val out = FloatArray(ceil(seconds * rate).toInt())
        // The output is 44.1 kHz even when this loop runs at 4x. Pruning
        // carriers before synthesis prevents an upper drawbar folding below it.
        val ceiling = min(20_000.0, rate * .45)
        val voices = sorted.map { indexed ->
            Voice(indexed.value, settings, Dsp.seedFor(seed, "OTIS_ORGAN", indexed.index, indexed.value.midi), rate, ceiling)
        }

        var nextVoice = 0
        val active = ArrayList<Voice>()
        val rotor = if (settings.rotary) Rotor(settings, rate) else null
        val cabinetCoeff = 1.0 - exp(-TWO_PI * min(settings.cabinetHz, rate * .45) / rate)
        val preampCoeff = 1.0 - exp(-TWO_PI * 700.0 / rate)
        val sagCoeff = 1.0 - exp(-1.0 / (.055 * rate))
        val ampGain = 1.0 + settings.drive * 6.0
        val ampBias = settings.drive * .12
        val ampZero = tanh(ampGain * ampBias)
        val ampScale = tanh(ampGain)
        var preampLow = 0.0
        var ampLoad = 0.0
        var cabinet = 0.0

        for (frame in out.indices) {
            // Released oscillator tails remain audible, but only physically
            // held keys stop the percussion circuit from rearming.
            while (nextVoice < voices.size && voices[nextVoice].startFrame <= frame) {
                val voice = voices[nextVoice++]
                val keysHeld = active.any { frame < it.offFrame }
                voice.percussionArmed = !keysHeld
                active.add(voice)
            }
            var mixed = 0.0
            val iterator = active.iterator()
            while (iterator.hasNext()) {
                val voice = iterator.next()
                if (frame >= voice.endFrame) iterator.remove()
                else mixed += voice.sample(frame)
            }

            // One amp for the whole registration/chord, then the cabinet.
            // The caller renders this stage oversampled before decimation.
            // Gentle asymmetric transfer adds some even harmonics; the slow
            // supply load softens the held body after the contact attack.
            // At DRIVE=0 this entire stage is linear, preserving isolated stops.
            val driven = if (settings.drive == 0f) mixed else {
                preampLow += preampCoeff * (mixed - preampLow)
                ampLoad += sagCoeff * (abs(mixed) - ampLoad)
                val input = mixed + .12 * settings.drive * (mixed - preampLow)
                val sag = 1.0 / (1.0 + .30 * settings.drive * ampLoad)
                (tanh(ampGain * (input * sag + ampBias)) - ampZero) / ampScale
            }
            cabinet += cabinetCoeff * (driven - cabinet)
            out[frame] = rotor?.sample(cabinet.toFloat(), frame) ?: cabinet.toFloat()
        }
        return out
    }

    private class Voice(
        private val note: OtisPrototypeNote,
        private val settings: OtisOrganSettings,
        seed: Int,
        private val rate: Int,
        ceiling: Double,
    ) {
        val startFrame = (note.startSeconds * rate).toInt()
        val offFrame = ((note.startSeconds + note.gateSeconds) * rate).toInt().coerceAtLeast(startFrame + 1)
        val endFrame = offFrame + ceil(settings.releaseSeconds * rate).toInt()
        var percussionArmed = false
        private val base = 440.0 * 2.0.pow((note.midi - 69) / 12.0)
        private val phase = Dsp.phases(9, seed)
        private val increments = DoubleArray(9) { base * ratios[it] / rate }
        private val gains = DoubleArray(9) {
            if (base * ratios[it] < ceiling) settings.drawbars[it].toDouble().pow(1.6) * .22 else 0.0
        }
        private val leakPhase = Dsp.phases(36, Dsp.seedFor(seed, "LEAKAGE"))
        private val leakIncrements = DoubleArray(36) { k ->
            base * ratios[k / 4] * 2.0.pow(leakageSemitones[k % 4] / 12.0) / rate
        }
        private val leakLevels = DoubleArray(36) { k ->
            if (leakIncrements[k] * rate < ceiling) gains[k / 4] * settings.leakage * leakageGains[k % 4] else 0.0
        }
        private val percussionRatio = if (settings.percussionHarmonic == OtisOrganPercussion.SECOND) 2.0 else 3.0
        private var percussionPhase = Dsp.phases(1, Dsp.seedFor(seed, "PERCUSSION"))[0]
        private val percussionIncrement = base * percussionRatio / rate
        private var percussionEnvelope = if (base * percussionRatio < ceiling) settings.percussionLevel.toDouble() else 0.0
        private val percussionDecay = exp(-6.907755278982137 / (settings.percussionDecaySeconds * rate))
        private var percussionEdge = if (base * percussionRatio * 2.0 < ceiling) 1.0 else 0.0
        private val percussionEdgeDecay = exp(-6.907755278982137 / (.06 * rate))
        private val clickNoise = Dsp.Noise(Dsp.seedFor(seed, "CLICK"))
        private var clickEnvelope = settings.click.toDouble() * .60
        private val clickDecay = exp(-1.0 / (.002 * rate))
        private val contactBounceFrame = (.0013 * rate).toInt()
        private val clickLowCoeff = 1.0 - exp(-TWO_PI * min(8000.0, rate * .4) / rate)
        private val clickHighCoeff = 1.0 - exp(-TWO_PI * 1100.0 / rate)
        private var clickLow = 0.0
        private var clickHigh = 0.0

        fun sample(frame: Int): Double {
            var tone = 0.0
            var contact = 0.0
            for (i in phase.indices) {
                if (gains[i] == 0.0) continue
                phase[i] = advance(phase[i], increments[i])
                tone += sin(TWO_PI * phase[i]) * gains[i]
            }
            if (settings.leakage > 0f) for (i in leakPhase.indices) {
                if (leakLevels[i] == 0.0) continue
                leakPhase[i] = advance(leakPhase[i], leakIncrements[i])
                tone += sin(TWO_PI * leakPhase[i]) * leakLevels[i]
            }
            if (percussionArmed) {
                percussionPhase = advance(percussionPhase, percussionIncrement)
                val fundamental = sin(TWO_PI * percussionPhase)
                val edge = .14 * percussionEdge * sin(2.0 * TWO_PI * percussionPhase)
                tone += (fundamental + edge) * percussionEnvelope * 1.15 * note.velocity
                percussionEnvelope *= percussionDecay
                percussionEdge *= percussionEdgeDecay
            }
            // Contacts on separate bus bars close a little apart. This second
            // short burst makes a contact clack rather than a longer noise wash.
            if (frame - startFrame == contactBounceFrame) clickEnvelope += settings.click * .22
            if (clickEnvelope > 1e-8) {
                val noise = clickNoise.next().toDouble()
                clickHigh += clickHighCoeff * (noise - clickHigh)
                clickLow += clickLowCoeff * (noise - clickHigh - clickLow)
                contact = clickLow * clickEnvelope * note.velocity
                clickEnvelope *= clickDecay
            }

            val age = (frame - startFrame).toDouble() / rate
            val attack = if (settings.attackSeconds == 0.0) 1.0 else (age / settings.attackSeconds).coerceIn(0.0, 1.0)
            val release = if (frame < offFrame) 1.0 else {
                val x = (1.0 - (frame - offFrame) / (settings.releaseSeconds * rate)).coerceIn(0.0, 1.0)
                x * x * (3.0 - 2.0 * x)
            }
            // A key's contact is heard before the slower tone onset settles.
            val articulated = tone * attack + contact * (.25 + .75 * attack)
            return articulated * release * (.65 + .35 * note.velocity) * note.velocity.coerceAtMost(1f)
        }
    }

    /** Mono rotary coloration, shared by every note; deliberately no room effect. */
    private class Rotor(private val settings: OtisOrganSettings, private val rate: Int) {
        private val startRates = rates(settings.rotaryStart)
        private val targetRates = rates(settings.rotaryTarget)
        private var hornRate = startRates.first
        private var bassRate = startRates.second
        private var hornPhase = 0.0
        private var bassPhase = .25
        private val hornAcceleration = 1.0 - exp(-1.0 / (.95 * rate))
        private val bassAcceleration = 1.0 - exp(-1.0 / (1.65 * rate))
        private val crossoverCoeff = 1.0 - exp(-TWO_PI * 800.0 / rate)
        private var low = 0.0
        private val delay = .0015 * rate
        private val doppler = .00036 * rate
        private val hornLine = FloatArray(ceil(delay + doppler).toInt() + 4)
        private val bassLine = FloatArray(hornLine.size)
        private var position = 0

        fun sample(input: Float, frame: Int): Float {
            val target = if (frame.toDouble() / rate >= settings.rotaryChangeSeconds) targetRates else startRates
            hornRate += (target.first - hornRate) * hornAcceleration
            bassRate += (target.second - bassRate) * bassAcceleration
            hornPhase = advance(hornPhase, hornRate / rate)
            bassPhase = advance(bassPhase, bassRate / rate)
            low += crossoverCoeff * (input - low)
            hornLine[position] = (input - low).toFloat()
            bassLine[position] = low.toFloat()
            val angle = TWO_PI * hornPhase
            val depth = settings.rotaryDepth
            val horn = Dsp.tap(hornLine, position, (delay + doppler * depth * sin(angle)).toFloat())
            // Both crossover halves share a base delay, avoiding a permanent
            // horn/drum time offset around their crossover frequency.
            val bass = Dsp.tap(bassLine, position, delay.toFloat())
            position = (position + 1) % hornLine.size
            return (horn * (1.0 + .56 * depth * sin(angle + PI / 2.0)) +
                bass * (1.0 + .34 * depth * sin(TWO_PI * bassPhase))).toFloat()
        }

        private fun rates(speed: OtisOrganRotary): Pair<Double, Double> = when (speed) {
            OtisOrganRotary.BRAKE -> 0.0 to 0.0
            OtisOrganRotary.SLOW -> .7 to .6
            OtisOrganRotary.FAST -> 6.7 to 5.8
        }
    }

    private fun advance(phase: Double, increment: Double): Double {
        val next = phase + increment
        return next - kotlin.math.floor(next)
    }
}
