package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The bodies SPLAY's catches can strike besides TONGUE. Each keeps SPLAY's gesture (common release,
 * coordinated catches, separating flams, bounded decay) because the pendulums and the catch times are
 * exactly model 2's ([Splay.prepareModel2Excitation]); only the thing they strike changes.
 *
 *  - [WIRE]: one string (a tuned delay loop), plucked at a point the upper arm's angle chooses.
 *  - [RUB]: a long-memory harmonic resonator rubbed continuously by the upper arm's pressure, with
 *    the catches as accents. It sings between catches and is SPLAY's first held (HOLD) voice.
 */
enum class SplayBody { WIRE, RUB }

data class SplayBodyRender(
    val snip: Snip,
    /** Conditioned 44.1 kHz output before the shared loudness stage. */
    val raw: FloatArray,
    /** Where a held render's loop starts, in frames; null for a finite note. The loop runs to the end. */
    val loopStart: Int?,
    /** The model-2 catches this render struck, unchanged. */
    val events: List<SplayContactEvent>,
)

/**
 * SPLAY model 3: the WIRE and RUB bodies. Model 3 never renders TONGUE, and TONGUE's models 1 and 2
 * keep their own code, defaults and seeds: this object only reads the model-2 catch list.
 *
 * Velocity changes the contact, not just the level: a softer touch is a wider catch pulse
 * ([contactSeconds]), which is a duller, rounder hit, and on RUB a darker rub. Loudness then
 * follows velocity with TONGUE's own ×v^0.65.
 *
 * HOLD (RUB only) plays the real motion for [LOOP_FROM_SECONDS], then a [CYCLE_SECONDS] window of it
 * (the catches, the upper-arm pressure and the rosin noise from that window) in a circle. RUB is linear
 * and loses energy, so a repeating push settles into a repeating sound: the loop is the last of
 * [HELD_CYCLES] cycles, and its seam is measured, not crossfaded. This is a real excerpt of the motion
 * played in a circle; it does not claim the chaotic motion itself repeats.
 */
object SplayBodies {
    const val MODEL = 3
    const val LOOP_FROM_SECONDS = 3.0
    const val CYCLE_SECONDS = 2.0
    const val HELD_CYCLES = 3
    private const val RATE = Dsp.RATE
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val TAU = 2.0 * PI
    private const val LN_1000 = 6.907755278982137
    private const val ACTIVE_SECONDS = Splay.ACTIVE_SECONDS
    private const val RELEASE_SECONDS = Splay.RELEASE_SECONDS
    /** The common release's force area, model 2's own. */
    private const val RELEASE_AREA = .070

    // WIRE: the loop's shape choices (backup prototype): low-pass basis 12 x note, 12 kHz ceiling, LOSS .15.
    private const val WIRE_LOSS = .15f
    private const val WIRE_FILTER_BASIS = 12f
    private const val WIRE_FILTER_CEILING_HZ = 12_000f

    // RUB: long harmonic memory; T60 falls with the partial number.
    private const val RUB_T60 = 2.8
    private const val RUB_T60_FALL = .45
    private const val RUB_MAX_MODES = 16
    private const val RUB_TOP_HZ = 16_000.0
    /** Where along the resonator the rub sits (a fixed bow point): shapes the partial balance. */
    private const val RUB_POINT = .13
    private const val RUB_FRICTION = .012
    /** The rub never fully lifts: the arms' pressure swells it from this floor, through a 40 ms lag (the bow's give). */
    private const val RUB_FLOOR = .30
    private const val RUB_LAG_SECONDS = .040
    private const val RUB_CATCH = .40
    /** The loop window's last stretch is blended toward the motion that led into the window, so the circle has no jump. */
    private const val SEAM_BLEND_SECONDS = .15

    fun supportsHold(body: SplayBody) = body == SplayBody.RUB

    /** SPLAY's model-2 controls, the same defaults as TONGUE at model 2. HOLD is RUB's only. */
    fun macrosFor(body: SplayBody): List<MacroSpec> = Splay.macrosFor(SplayVoice.TONGUE, 2)

    fun defaults(body: SplayBody): Map<String, Float> = macrosFor(body).associate { it.name to it.default }

    fun isLoop(body: SplayBody, macros: Map<String, Float>) = supportsHold(body) && (macros["HOLD"] ?: 0f) > 0f

    /**
     * A catch's contact time: wider for a softer touch and a softer CONTACT, at most .38 of a period and
     * under 4 ms, and strictly shorter as either rises at every pitch (no cap flattens them). The backup
     * WIRE prototype's repaired law.
     */
    fun contactSeconds(contact: Double, velocity: Double, hz: Double): Double {
        val q = .10 + .28 * (1.0 - .75 * contact) * (1.0 - .65 * velocity)
        val w = q / hz
        return w / (1.0 + w / .004)
    }

    fun render(body: SplayBody, macros: Map<String, Float> = emptyMap(), midi: Int = Splay.DEFAULT_MIDI,
        velocity: Float = .8f, seed: Int = Splay.DEFAULT_SEED): Snip =
        renderDetailed(body, macros, midi, velocity, seed).snip

    fun renderDetailed(body: SplayBody, macros: Map<String, Float> = emptyMap(), midi: Int = Splay.DEFAULT_MIDI,
        velocity: Float = .8f, seed: Int = Splay.DEFAULT_SEED): SplayBodyRender {
        checkCancellation()
        require(midi in Splay.MIDI_MIN..Splay.MIDI_MAX) { "SPLAY MIDI must be ${Splay.MIDI_MIN}..${Splay.MIDI_MAX}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "SPLAY velocity must be 0..1" }
        for ((key, value) in macros) require(value.isFinite() && value in 0f..1f) { "SPLAY $key must be 0..1" }
        require(supportsHold(body) || (macros["HOLD"] ?: 0f) == 0f) { "SPLAY $body is finite only; HOLD is RUB's" }
        val held = isLoop(body, macros)
        val excitation = Splay.prepareModel2Excitation(macros)
        val contact = excitation.macros.getValue("CONTACT").toDouble()
        val hz = Keys.midiHz(midi).toDouble()
        val v = velocity.toDouble()
        val seconds = if (held) LOOP_FROM_SECONDS + HELD_CYCLES * CYCLE_SECONDS else ACTIVE_SECONDS + RELEASE_SECONDS
        // A held render runs a little past its end so the decimator's kernel never reaches the buffer's edge.
        val padFrames = if (held) RATE / 10 else 0
        val frames = (seconds * INTERNAL_RATE).roundToInt() + padFrames * Dsp.OVERSAMPLE
        val schedule = Schedule(excitation, contact, v, hz, held, frames)
        val raw = when (body) {
            SplayBody.WIRE -> wire(schedule, hz, frames)
            SplayBody.RUB -> rub(schedule, excitation, hz, v, seed, held, frames)
        }
        Tide.bandLimit(raw, INTERNAL_RATE)
        var out = Dsp.decimate(raw, RATE)
        val dcA = 1.0 - exp(-TAU * 8.0 / RATE)
        var dc = 0.0
        for (i in out.indices) {
            dc += dcA * (out[i] - dc)
            out[i] = (out[i] - dc).toFloat()
        }
        val loopStart: Int?
        if (held) {
            out = out.copyOf((seconds * RATE).roundToInt())
            loopStart = ((seconds - CYCLE_SECONDS) * RATE).roundToInt()
        } else {
            Dsp.fadeTail(out, ms = 45f)
            loopStart = null
        }
        check(out.all { it.isFinite() }) { "SPLAY $body nonfinite output" }
        val leveled = out.copyOf()
        Dsp.levelTo(leveled, RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.toDouble().pow(.65).toFloat(), ceiling = .94f)
        return SplayBodyRender(Snip(leveled, 1, RATE), out, loopStart, excitation.events)
    }

    /**
     * The catches as signed, unit-area pulses (each with its delayed negative image for a point pluck),
     * at the internal rate. A finite note strikes every catch once. A held note strikes the catches
     * before [LOOP_FROM_SECONDS] once and, from there on, the window's catches every cycle; a pulse that
     * runs past the window's end wraps to its start, so the force from the second cycle on repeats exactly.
     */
    private class Schedule(e: Splay.Model2Excitation, contact: Double, velocity: Double, hz: Double,
        val held: Boolean, val frames: Int) {
        val total = e.weights.sum()
        val cycle = (CYCLE_SECONDS * INTERNAL_RATE).roundToInt()
        val loopFrom = (LOOP_FROM_SECONDS * INTERNAL_RATE).roundToInt()
        val circularSize = if (held) cycle else 0
        /** Every pulse: the release and each catch, with its amplitude and the upper arm's point along the body. */
        val pulses = ArrayList<Pulse>()
        val width = contactSeconds(contact, velocity, hz)
        val kernel = kernel(width * INTERNAL_RATE)

        class Pulse(val start: Int, val amplitude: Double, val position: Double, val circular: Boolean)

        init {
            pulses += Pulse(0, RELEASE_AREA, .22, false)
            for (event in e.events) {
                val inWindow = held && event.seconds >= LOOP_FROM_SECONDS && event.seconds < LOOP_FROM_SECONDS + CYCLE_SECONDS
                if (held && event.seconds >= LOOP_FROM_SECONDS + CYCLE_SECONDS) continue
                val taper = if (held) 1.0 else smooth((ACTIVE_SECONDS - event.seconds) / .20)
                val amplitude = event.direction * event.strength * e.weights[event.participantId] *
                    event.coincidenceGain * taper / total
                val position = .22 + .17 * sin(event.upperAngleRadians)
                val start = (event.seconds * INTERNAL_RATE).roundToInt()
                pulses += if (inWindow) Pulse(start - loopFrom, amplitude, position, true)
                else Pulse(start, amplitude, position, false)
            }
        }

        /** Adds [pulse] (scaled by [gain]) into [linearOut] or [circularOut], the image [image] samples later and inverted. */
        fun stamp(pulse: Pulse, gain: Double, image: Int, linearOut: FloatArray, circularOut: FloatArray) {
            for ((offset, sign) in listOf(0 to 1.0, image to -1.0)) {
                if (image <= 0 && sign < 0) continue
                for (j in kernel.indices) {
                    val value = (pulse.amplitude * gain * sign * kernel[j]).toFloat()
                    if (pulse.circular) {
                        val n = Math.floorMod(pulse.start + offset + j, circularOut.size)
                        circularOut[n] += value
                    } else {
                        val n = pulse.start + offset + j
                        if (n >= linearOut.size) break
                        linearOut[n] += value
                    }
                }
            }
        }

        /** The force at internal frame [i]: the once-only catches plus, for a held note, the window's circle. */
        fun force(linearForce: FloatArray, circularForce: FloatArray, i: Int): Float =
            if (held && i >= loopFrom) linearForce[i] + circularForce[(i - loopFrom) % cycle] else linearForce[i]
    }

    /** Midpoint samples of a raised cosine over a fractional [length], normalized to unit area. */
    private fun kernel(length: Double): DoubleArray {
        val count = max(1, floor(length).toInt() + 1)
        val weights = DoubleArray(count) { j ->
            val centre = j + .5
            if (centre >= length) 0.0 else 1.0 - cos(TAU * centre / length)
        }
        val sum = weights.sum()
        return if (sum > 0.0) DoubleArray(count) { weights[it] / sum } else DoubleArray(1) { 1.0 }
    }

    private fun wire(s: Schedule, hz: Double, frames: Int): FloatArray {
        val damping = Strings.damping(WIRE_LOSS, min(WIRE_FILTER_BASIS * hz.toFloat(), WIRE_FILTER_CEILING_HZ))
        val tuning = Strings.tune(hz.toFloat(), damping.loopHz, INTERNAL_RATE)
        val loop = Strings.Loop(tuning.n, tuning.a, damping.fb, damping.loopHz, INTERNAL_RATE, cacheLoopFilter = true)
        val linear = FloatArray(frames)
        val circular = FloatArray(s.circularSize)
        val period = INTERNAL_RATE / hz
        for (pulse in s.pulses) {
            // A point pluck: the pulse and its inverted image, the pluck point's share of a period later.
            s.stamp(pulse, 1.0, (pulse.position * period).roundToInt(), linear, circular)
        }
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            if (i % 16384 == 0) checkCancellation()
            out[i] = loop.next(s.force(linear, circular, i))
        }
        return out
    }

    private fun rub(s: Schedule, e: Splay.Model2Excitation, hz: Double, velocity: Double, seed: Int,
        held: Boolean, frames: Int): FloatArray {
        val count = (1..RUB_MAX_MODES).count { hz * it < RUB_TOP_HZ }
        val cr = DoubleArray(count); val sr = DoubleArray(count)
        val re = DoubleArray(count); val im = DoubleArray(count)
        val rubGain = DoubleArray(count)
        for (k in 0 until count) {
            val partial = k + 1
            val t60 = RUB_T60 / (1.0 + RUB_T60_FALL * k)
            val radius = exp(-LN_1000 / (t60 * INTERNAL_RATE))
            cr[k] = radius * cos(TAU * hz * partial / INTERNAL_RATE)
            sr[k] = radius * sin(TAU * hz * partial / INTERNAL_RATE)
            rubGain[k] = (.35 + .65 * sin(PI * partial * RUB_POINT).pow(2)) / partial
        }
        // The catches strike every partial through one shared accent buffer (the rub, not the catch
        // point, is what moves RUB's partial balance).
        val accent = FloatArray(frames); val accentC = FloatArray(s.circularSize)
        for (pulse in s.pulses) s.stamp(pulse, 1.0, 0, accent, accentC)
        val accentGain = DoubleArray(count) { k ->
            RUB_CATCH * (.45 + .75 * sin(PI * (k + 1) * RUB_POINT * 1.7).pow(2)) / sqrt(k + 1.0)
        }
        // One shared rosin-noise stream, low-passed darker for a softer touch.
        val noise = Dsp.Noise(Dsp.seedFor("SPLAY", MODEL, "RUB", seed, "rosin"))
        val noiseHz = 900.0 + 6000.0 * velocity.pow(1.5)
        val lpA = 1.0 - exp(-TAU * noiseHz / INTERNAL_RATE)
        val noiseFrames = if (held) s.loopFrom + s.cycle else frames
        val rosin = FloatArray(noiseFrames)
        var lp1 = 0.0; var lp2 = 0.0
        for (i in rosin.indices) {
            lp1 += lpA * (noise.next() - lp1); lp2 += lpA * (lp1 - lp2)
            rosin[i] = lp2.toFloat()
        }
        val pressure = e.upperPressure
        val control = 1.0 / e.dtSeconds
        fun pressureAt(t: Double): Double {
            val position = t * control
            val k = min(position.toInt(), pressure.size - 2)
            val mix = (position - k).coerceIn(0.0, 1.0)
            return pressure[k] + mix * (pressure[k + 1] - pressure[k])
        }
        val blend = (SEAM_BLEND_SECONDS * INTERNAL_RATE).roundToInt()
        val lagA = 1.0 - exp(-1.0 / (RUB_LAG_SECONDS * INTERNAL_RATE))
        var lagged = pressureAt(0.0)
        fun swell(p: Double): Double {
            lagged += lagA * (p - lagged)
            return RUB_FLOOR + (1.0 - RUB_FLOOR) * lagged
        }
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            if (i % 16384 == 0) checkCancellation()
            // The rub: real pressure and noise, or, once held, the window's, with its last stretch
            // blended (pressure linearly, noise at constant power) toward what led into the window.
            val rub: Double
            if (held && i >= s.loopFrom) {
                val phase = (i - s.loopFrom) % s.cycle
                val now = s.loopFrom + phase
                val x = ((phase - (s.cycle - blend)).toDouble() / blend).coerceIn(0.0, 1.0)
                val before = now - s.cycle
                val p = (1.0 - x) * pressureAt(now.toDouble() / INTERNAL_RATE) + x * pressureAt(before.toDouble() / INTERNAL_RATE)
                val n = cos(.5 * PI * x) * rosin[now] + sin(.5 * PI * x) * rosin[before]
                rub = swell(p) * n
            } else {
                val t = i.toDouble() / INTERNAL_RATE
                val active = if (t < ACTIVE_SECONDS) smooth(t / .012) * smooth((ACTIVE_SECONDS - t) / .20) else 0.0
                rub = swell(pressureAt(t)) * rosin[i] * active
            }
            val hit = s.force(accent, accentC, i).toDouble()
            var sum = 0.0
            for (k in 0 until count) {
                val force = RUB_FRICTION * rub * rubGain[k] + accentGain[k] * hit
                val x = cr[k] * re[k] - sr[k] * im[k]
                im[k] = sr[k] * re[k] + cr[k] * im[k] + force
                re[k] = x
                sum += x
            }
            out[i] = sum.toFloat()
        }
        return out
    }

    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("SPLAY render cancelled")
    }
}
