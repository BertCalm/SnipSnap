package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * The bodies SPLAY's catches can strike besides TONGUE. Each keeps SPLAY's gesture (common release,
 * coordinated catches, separating flams, bounded decay) because the pendulums and the catch times are
 * exactly model 2's ([Splay.prepareModel2Excitation]); only the thing they strike changes.
 *
 *  - [WIRE]: one string (a tuned delay loop), plucked at a point the upper arm's angle chooses.
 *  - [RUB]: a long-memory harmonic resonator rubbed continuously by the upper arm's pressure. Each catch
 *    is a stick-slip gesture (the bow grips, the ringing ducks, then it slips and strikes), landing where
 *    the upper arm points. It sings between catches and is SPLAY's first held (HOLD) voice.
 *  - [AIR]: a stopped tube (odd harmonics, hollow) blown by one breath stream. The upper arm's pressure
 *    keeps a low breath going; each catch opens a valve, a puff with a tongued pop at its front. Holds.
 *  - [BAR]: a free mallet bar (inharmonic modes 1 : 2.756 : 5.404 : 8.933 : 13.344). The catch width is
 *    the mallet's hardness, and the upper arm moves the strike from the bar's middle toward its end.
 *  - [SKIN]: a damped drum membrane (modes 1 : 1.594 : 2.136 : 2.296 : 2.653 : 2.918). The upper arm
 *    moves the strike from the centre (a round boom) toward the edge (a ringing knock), and a hard
 *    catch bends the pitch up for a moment, as a struck head does.
 */
enum class SplayBody { WIRE, RUB, AIR, BAR, SKIN }

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
 * SPLAY model 3: the WIRE, RUB, AIR, BAR and SKIN bodies. Model 3 never renders TONGUE, and TONGUE's models 1 and 2
 * keep their own code, defaults and seeds: this object only reads the model-2 catch list.
 *
 * Velocity changes the contact, not just the level: a softer touch is a wider catch pulse
 * ([contactSeconds]), which is a duller, rounder hit, and on RUB a darker rub. Loudness then
 * follows velocity with TONGUE's own ×v^0.65.
 *
 * HOLD (RUB and AIR) plays the real motion for [LOOP_FROM_SECONDS], then a [CYCLE_SECONDS] window of it
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
    private const val RUB_CATCH = 4.0
    /**
     * Each catch is a stick-slip gesture, not just an accent (the owner's round-1 note: "We need a bit of
     * a catch like gesture in the sound"). For [STICK_SECONDS] before it the bow grips: the rub sags by up
     * to [STICK_DEPTH] and the grip holds the resonator back ([GRAB_PER_SECOND]), so the ringing ducks.
     * At the catch it slips: the catch strikes the partials, the rub surges by up to [SLIP_GAIN] over
     * [SLIP_SECONDS], and a short band-passed scrape of friction noise ([SCRAPE_SECONDS]) rides on top.
     * A stronger catch grips and slips harder. Measured (C4, 16 mechanisms): the dip-to-peak swing around
     * a catch is about 9.5 dB, against 2.5 dB before.
     */
    private const val STICK_SECONDS = .010
    private const val STICK_DEPTH = .75
    private const val SLIP_SECONDS = .030
    private const val SLIP_GAIN = 1.0
    /** How hard a grip holds the resonator back, per second, at a full touch (60% of it at the softest). */
    private const val GRAB_PER_SECOND = 300.0
    private const val SCRAPE_SECONDS = .018
    private const val SCRAPE_GAIN = .35
    /**
     * Where along the resonator a catch lands, as on WIRE: the upper arm's angle picks a point between these
     * anchors, so successive catches strike different partial balances.
     */
    private val CATCH_POINTS = doubleArrayOf(.06, .22, .38)
    /** The loop window's last stretch is blended toward the motion that led into the window, so the circle has no jump. */
    private const val SEAM_BLEND_SECONDS = .15

    /** The pitch every body's per-note decay laws are quoted at (C4). */
    private const val REFERENCE_HZ = 261.6255653005986

    // AIR: a stopped tube, so odd harmonics only (the hollow sound), each a narrow resonance fed by breath.
    private val AIR_HARMONICS = doubleArrayOf(1.0, 3.0, 5.0)
    private val AIR_GAINS = doubleArrayOf(1.0, .45, .22)
    /** The fundamental's ring at C4; a lower note rings longer, a higher shorter (nearly constant width in cents). */
    private const val AIR_T60 = .55
    /** The steady breath the upper arm's pressure keeps going, against a full puff (1). */
    private const val AIR_FLOW = .20
    private const val AIR_FLOOR = .25
    /** A puff: the valve opens over 3 ms (hard) to 12 ms (soft) and closes with this time constant. */
    private const val AIR_PUFF_SECONDS = .075
    /** The tongued pop at the front of each puff, struck into the tube's resonances. */
    private const val AIR_POP = 3.0
    /** The breath heard outside the tube, against the tube's own sound. */
    private const val AIR_BREATH = .035

    // BAR: a free-free beam. Ratios are (beta_k / beta_1)^2.
    private val BAR_BETA = doubleArrayOf(4.730040744862704, 7.853204624095838, 10.995607838001671, 14.137165491257464, 17.27875965739948)
    private val BAR_RADIATION = doubleArrayOf(1.0, 1.0, .85, .60, .40)
    /** The fundamental's ring at C4, scaled by (C4/note)^.35; each higher mode rings as 1/ratio^.6 of it. */
    private const val BAR_T60 = 1.25

    // SKIN: membrane modes (m, n) with their Bessel zeros j_mn; ratios are j_mn / j_01.
    private val SKIN_M = intArrayOf(0, 1, 2, 0, 3, 1)
    private val SKIN_ZERO = doubleArrayOf(2.404825557695773, 3.831705970207512, 5.135622301840683, 5.520078110286311, 6.380161895923984, 7.015586669815619)
    private val SKIN_RADIATION = doubleArrayOf(1.0, .85, .75, .55, .65, .50)
    private val SKIN_T60_SHARE = doubleArrayOf(1.0, .62, .50, .45, .40, .36)
    /** The (0,1) mode's ring at C4, scaled by (C4/note)^.3: heavily damped. */
    private const val SKIN_T60 = .50
    /** A full catch bends the head's pitch up by this fraction (about 20 cents), falling back over [SKIN_BEND_SECONDS]. */
    private const val SKIN_BEND = .012
    private const val SKIN_BEND_SECONDS = .020

    /**
     * How much wider than WIRE's and RUB's a body's contact is at [velocity]. BAR's is a mallet and SKIN's a
     * hand or felt beater: both stay on longer for a soft touch, so the touch changes the tone more than the
     * shared contact law alone does (about 1.5x on BAR and 1.8x on SKIN between a soft and a full touch).
     */
    private fun contactScale(body: SplayBody, velocity: Double): Double = when (body) {
        SplayBody.BAR -> 1.4 - .6 * velocity
        SplayBody.SKIN -> 3.0 * (1.5 - .8 * velocity)
        else -> 1.0
    }

    fun supportsHold(body: SplayBody) = body == SplayBody.RUB || body == SplayBody.AIR

    /** SPLAY's model-2 controls, the same defaults as TONGUE at model 2. HOLD is RUB's and AIR's only. */
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
        require(supportsHold(body) || (macros["HOLD"] ?: 0f) == 0f) { "SPLAY $body is finite only; HOLD is RUB's and AIR's" }
        val held = isLoop(body, macros)
        val excitation = Splay.prepareModel2Excitation(macros)
        val contact = excitation.macros.getValue("CONTACT").toDouble()
        val hz = Keys.midiHz(midi).toDouble()
        val v = velocity.toDouble()
        val seconds = if (held) LOOP_FROM_SECONDS + HELD_CYCLES * CYCLE_SECONDS else ACTIVE_SECONDS + RELEASE_SECONDS
        // A held render runs a little past its end so the decimator's kernel never reaches the buffer's edge.
        val padFrames = if (held) RATE / 10 else 0
        val frames = (seconds * INTERNAL_RATE).roundToInt() + padFrames * Dsp.OVERSAMPLE
        val schedule = Schedule(excitation, contact, v, hz, held, frames, contactScale(body, v))
        val raw = when (body) {
            SplayBody.WIRE -> wire(schedule, hz, frames)
            SplayBody.RUB -> rub(schedule, excitation, hz, v, seed, held, frames)
            SplayBody.AIR -> air(schedule, excitation, hz, v, seed, held, frames)
            SplayBody.BAR -> bar(schedule, hz, frames)
            SplayBody.SKIN -> skin(schedule, hz, v, frames)
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
        val held: Boolean, val frames: Int, widthScale: Double = 1.0) {
        val total = e.weights.sum()
        val cycle = (CYCLE_SECONDS * INTERNAL_RATE).roundToInt()
        val loopFrom = (LOOP_FROM_SECONDS * INTERNAL_RATE).roundToInt()
        val circularSize = if (held) cycle else 0
        /** Every pulse: the release and each catch, with its amplitude and the upper arm's point along the body. */
        val pulses = ArrayList<Pulse>()
        val width = contactSeconds(contact, velocity, hz) * widthScale
        val kernel = kernel(width * INTERNAL_RATE)

        /** The strongest untapered catch, so a held and a finite note grade their catches alike. */
        val catchScale = e.events.maxOfOrNull {
            abs(it.direction * it.strength * e.weights[it.participantId] * it.coincidenceGain / total)
        }?.takeIf { it > 0.0 } ?: 1.0

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

        /**
         * Adds [shape] × [gain] into [linearOut] or [circularOut] from [offset] samples after [pulse]'s start
         * ([offset] may be negative, for what comes before a catch), wrapping a circular pulse like [stamp].
         */
        fun stampShape(pulse: Pulse, offset: Int, shape: FloatArray, gain: Double, linearOut: FloatArray, circularOut: FloatArray) {
            for (j in shape.indices) {
                val value = (gain * shape[j]).toFloat()
                if (pulse.circular) {
                    circularOut[Math.floorMod(pulse.start + offset + j, circularOut.size)] += value
                } else {
                    val n = pulse.start + offset + j
                    if (n < 0) continue
                    if (n >= linearOut.size) break
                    linearOut[n] += value
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
        // The catches strike the partials through three accent buffers, one per catch point: each catch is
        // shared between the two anchors either side of where the upper arm puts it, so its partial balance
        // follows the arm, as WIRE's pluck point does.
        val anchors = CATCH_POINTS.size
        val accents = Array(anchors) { FloatArray(frames) }
        val accentsC = Array(anchors) { FloatArray(s.circularSize) }
        for (pulse in s.pulses) {
            val w = anchorWeights(pulse.position)
            for (j in 0 until anchors) if (w[j] > 0.0) s.stamp(pulse, w[j], 0, accents[j], accentsC[j])
        }
        val accentGain = Array(anchors) { j ->
            DoubleArray(count) { k -> RUB_CATCH * (.25 + .75 * sin(PI * (k + 1) * CATCH_POINTS[j]).pow(2)) / sqrt(k + 1.0) }
        }
        // The stick-slip gesture of every catch (not the release): grip before it, slip and scrape after.
        val grip = FloatArray(frames); val gripC = FloatArray(s.circularSize)
        val slip = FloatArray(frames); val slipC = FloatArray(s.circularSize)
        val scrape = FloatArray(frames); val scrapeC = FloatArray(s.circularSize)
        val gripShape = gripShape(STICK_SECONDS * INTERNAL_RATE)
        val slipShape = burstShape(SLIP_SECONDS * INTERNAL_RATE)
        val scrapeShape = burstShape(SCRAPE_SECONDS * INTERNAL_RATE)
        for (pulse in s.pulses.drop(1)) {
            val grade = (abs(pulse.amplitude) / s.catchScale).pow(.7).coerceAtMost(1.0)
            s.stampShape(pulse, -gripShape.size, gripShape, grade, grip, gripC)
            s.stampShape(pulse, 0, slipShape, grade, slip, slipC)
            s.stampShape(pulse, 0, scrapeShape, grade, scrape, scrapeC)
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
        // The slip's friction scrape: its own seeded noise, band-passed above the rub, brighter for a harder touch.
        val scrapeNoise = Dsp.Noise(Dsp.seedFor("SPLAY", MODEL, "RUB", seed, "scrape"))
        val hpA = 1.0 - exp(-TAU * 1200.0 / INTERNAL_RATE)
        val scrapeLpA = 1.0 - exp(-TAU * (2200.0 + 4800.0 * velocity.pow(1.5)) / INTERNAL_RATE)
        val grit = FloatArray(noiseFrames)
        var hp = 0.0; var g1 = 0.0; var g2 = 0.0
        for (i in grit.indices) {
            val raw = scrapeNoise.next().toDouble()
            hp += hpA * (raw - hp)
            g1 += scrapeLpA * ((raw - hp) - g1); g2 += scrapeLpA * (g1 - g2)
            grit[i] = g2.toFloat()
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
        val hits = DoubleArray(anchors)
        // A harder touch grips harder.
        val grab = GRAB_PER_SECOND * (.6 + .4 * velocity) / INTERNAL_RATE
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            if (i % 16384 == 0) checkCancellation()
            // The rub: real pressure and noise, or, once held, the window's, with its last stretch
            // blended (pressure linearly, noise at constant power) toward what led into the window.
            val rub: Double
            val gritNow: Double
            if (held && i >= s.loopFrom) {
                val phase = (i - s.loopFrom) % s.cycle
                val now = s.loopFrom + phase
                val x = ((phase - (s.cycle - blend)).toDouble() / blend).coerceIn(0.0, 1.0)
                val before = now - s.cycle
                val p = (1.0 - x) * pressureAt(now.toDouble() / INTERNAL_RATE) + x * pressureAt(before.toDouble() / INTERNAL_RATE)
                val n = cos(.5 * PI * x) * rosin[now] + sin(.5 * PI * x) * rosin[before]
                gritNow = cos(.5 * PI * x) * grit[now] + sin(.5 * PI * x) * grit[before]
                rub = swell(p) * n
            } else {
                val t = i.toDouble() / INTERNAL_RATE
                val active = if (t < ACTIVE_SECONDS) smooth(t / .012) * smooth((ACTIVE_SECONDS - t) / .20) else 0.0
                rub = swell(pressureAt(t)) * rosin[i] * active
                gritNow = grit[i] * active
            }
            // Stick then slip: the rub sags while the bow grips and surges as it lets go.
            val stick = min(1.0, s.force(grip, gripC, i).toDouble())
            val surge = s.force(slip, slipC, i).toDouble()
            val bowed = rub * max(0.0, 1.0 - STICK_DEPTH * stick + SLIP_GAIN * surge)
            for (j in 0 until anchors) hits[j] = s.force(accents[j], accentsC[j], i).toDouble()
            // While the bow grips it also holds the resonator back: the ringing ducks, then rebounds on the slip.
            val hold = 1.0 - grab * stick
            var sum = 0.0
            for (k in 0 until count) {
                var hit = 0.0
                for (j in 0 until anchors) hit += accentGain[j][k] * hits[j]
                val force = RUB_FRICTION * bowed * rubGain[k] + hit
                val x = hold * (cr[k] * re[k] - sr[k] * im[k])
                im[k] = hold * (sr[k] * re[k] + cr[k] * im[k]) + force
                re[k] = x
                sum += x
            }
            sum += SCRAPE_GAIN * s.force(scrape, scrapeC, i) * gritNow
            out[i] = sum.toFloat()
        }
        return out
    }

    /**
     * AIR: one breath stream through a stopped tube. The upper arm's pressure keeps a low breath going
     * (from [AIR_FLOOR]); every catch opens a valve, a puff of breath whose front is a tongued pop struck
     * into the tube. A harder touch blows brighter breath and overblows a little more into the upper
     * harmonics. HOLD loops the window's pressure and breath as RUB does.
     */
    private fun air(s: Schedule, e: Splay.Model2Excitation, hz: Double, velocity: Double, seed: Int,
        held: Boolean, frames: Int): FloatArray {
        val count = AIR_HARMONICS.count { hz * it < RUB_TOP_HZ }
        val cr = DoubleArray(count); val sr = DoubleArray(count)
        val re = DoubleArray(count); val im = DoubleArray(count)
        val drive = DoubleArray(count); val pass = DoubleArray(count)
        val t60 = AIR_T60 * sqrt(REFERENCE_HZ / hz)
        val richness = .35 + .65 * velocity
        for (k in 0 until count) {
            val radius = exp(-LN_1000 / (t60 / (1.0 + .35 * k) * INTERNAL_RATE))
            val f = hz * AIR_HARMONICS[k]
            cr[k] = radius * cos(TAU * f / INTERNAL_RATE)
            sr[k] = radius * sin(TAU * f / INTERNAL_RATE)
            drive[k] = AIR_GAINS[k] * richness.pow(k)
            // Breath enters each resonance scaled so a steady tone at its centre passes at unity.
            pass[k] = 1.0 - radius
        }
        // Every pulse (the release too) opens the valve and pops the tube.
        val valve = FloatArray(frames); val valveC = FloatArray(s.circularSize)
        val pop = FloatArray(frames); val popC = FloatArray(s.circularSize)
        val puff = puffShape((.003 + .009 * (1.0 - velocity)) * INTERNAL_RATE, AIR_PUFF_SECONDS * INTERNAL_RATE)
        for (pulse in s.pulses) {
            val grade = (abs(pulse.amplitude) / s.catchScale).pow(.7).coerceAtMost(1.0)
            s.stampShape(pulse, 0, puff, grade, valve, valveC)
            s.stamp(pulse, 1.0, 0, pop, popC)
        }
        // One shared breath stream, low-passed darker for a softer touch.
        val noise = Dsp.Noise(Dsp.seedFor("SPLAY", MODEL, "AIR", seed, "breath"))
        val lpA = 1.0 - exp(-TAU * (700.0 + 3300.0 * velocity.pow(1.5)) / INTERNAL_RATE)
        val noiseFrames = if (held) s.loopFrom + s.cycle else frames
        val breath = FloatArray(noiseFrames)
        var lp1 = 0.0; var lp2 = 0.0
        for (i in breath.indices) {
            lp1 += lpA * (noise.next() - lp1); lp2 += lpA * (lp1 - lp2)
            breath[i] = lp2.toFloat()
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
            return AIR_FLOOR + (1.0 - AIR_FLOOR) * lagged
        }
        // The breath heard outside the tube loses its lowest rumble.
        val hpA = 1.0 - exp(-TAU * 300.0 / INTERNAL_RATE)
        var hp = 0.0
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            if (i % 16384 == 0) checkCancellation()
            val flow: Double
            val breathNow: Double
            if (held && i >= s.loopFrom) {
                val phase = (i - s.loopFrom) % s.cycle
                val now = s.loopFrom + phase
                val x = ((phase - (s.cycle - blend)).toDouble() / blend).coerceIn(0.0, 1.0)
                val before = now - s.cycle
                val p = (1.0 - x) * pressureAt(now.toDouble() / INTERNAL_RATE) + x * pressureAt(before.toDouble() / INTERNAL_RATE)
                breathNow = cos(.5 * PI * x) * breath[now] + sin(.5 * PI * x) * breath[before]
                flow = AIR_FLOW * swell(p)
            } else {
                val t = i.toDouble() / INTERNAL_RATE
                val active = if (t < ACTIVE_SECONDS) smooth(t / .012) * smooth((ACTIVE_SECONDS - t) / .20) else 0.0
                breathNow = breath[i].toDouble()
                flow = AIR_FLOW * swell(pressureAt(t)) * active
            }
            val blown = breathNow * (flow + s.force(valve, valveC, i))
            val popped = AIR_POP * s.force(pop, popC, i)
            var sum = 0.0
            for (k in 0 until count) {
                val force = drive[k] * (pass[k] * blown + popped)
                val x = cr[k] * re[k] - sr[k] * im[k]
                im[k] = sr[k] * re[k] + cr[k] * im[k] + force
                re[k] = x
                sum += x
            }
            hp += hpA * (blown - hp)
            out[i] = (sum + AIR_BREATH * (blown - hp)).toFloat()
        }
        return out
    }

    /**
     * BAR: a free bar's inharmonic modes. Each catch is a mallet stroke: the contact width is the mallet's
     * hardness (a softer touch is a wider, duller stroke), and the upper arm moves the stroke from the bar's
     * middle (fundamental and third mode) toward a third along it (the 2.756 mode joins in).
     */
    private fun bar(s: Schedule, hz: Double, frames: Int): FloatArray {
        val ratios = BAR_BETA.map { (it / BAR_BETA[0]).pow(2) }.filter { hz * it < RUB_TOP_HZ }
        val count = ratios.size
        val t1 = (BAR_T60 * (REFERENCE_HZ / hz).pow(.35)).coerceIn(.45, 2.6)
        val cr = DoubleArray(count); val sr = DoubleArray(count)
        val re = DoubleArray(count); val im = DoubleArray(count)
        for (k in 0 until count) {
            val radius = exp(-LN_1000 / (t1 / ratios[k].pow(.6) * INTERNAL_RATE))
            cr[k] = radius * cos(TAU * hz * ratios[k] / INTERNAL_RATE)
            sr[k] = radius * sin(TAU * hz * ratios[k] / INTERNAL_RATE)
        }
        val strokes = Array(count) { FloatArray(frames) }
        val strokesC = Array(count) { FloatArray(s.circularSize) }
        for (pulse in s.pulses) {
            val x = .5 - .5 * (pulse.position - .05)
            for (k in 0 until count) s.stamp(pulse, BAR_RADIATION[k] * barShape(BAR_BETA[k], x), 0, strokes[k], strokesC[k])
        }
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            if (i % 16384 == 0) checkCancellation()
            var sum = 0.0
            for (k in 0 until count) {
                val x = cr[k] * re[k] - sr[k] * im[k]
                im[k] = sr[k] * re[k] + cr[k] * im[k] + s.force(strokes[k], strokesC[k], i)
                re[k] = x
                sum += x
            }
            out[i] = sum.toFloat()
        }
        return out
    }

    /**
     * SKIN: a damped membrane. The upper arm moves each stroke from near the centre (the (0,1) and (0,2)
     * modes: a round boom) toward the edge (the asymmetric modes: a ringing knock). A catch tightens the
     * head for a moment, so its pitch jumps up by up to [SKIN_BEND] and falls back; a harder touch bends more.
     */
    private fun skin(s: Schedule, hz: Double, velocity: Double, frames: Int): FloatArray {
        val count = SKIN_ZERO.indices.count { hz * SKIN_ZERO[it] / SKIN_ZERO[0] < RUB_TOP_HZ }
        val t1 = (SKIN_T60 * (REFERENCE_HZ / hz).pow(.3)).coerceIn(.2, 1.0)
        val radius = DoubleArray(count); val theta = DoubleArray(count)
        val cr = DoubleArray(count); val sr = DoubleArray(count)
        val re = DoubleArray(count); val im = DoubleArray(count)
        for (k in 0 until count) {
            radius[k] = exp(-LN_1000 / (t1 * SKIN_T60_SHARE[k] * INTERNAL_RATE))
            theta[k] = TAU * hz * SKIN_ZERO[k] / SKIN_ZERO[0] / INTERNAL_RATE
            cr[k] = radius[k] * cos(theta[k])
            sr[k] = radius[k] * sin(theta[k])
        }
        val strokes = Array(count) { FloatArray(frames) }
        val strokesC = Array(count) { FloatArray(s.circularSize) }
        val bend = FloatArray(frames); val bendC = FloatArray(s.circularSize)
        val bendShape = burstShape(SKIN_BEND_SECONDS * INTERNAL_RATE)
        val depth = SKIN_BEND * (.4 + .6 * velocity)
        for (pulse in s.pulses) {
            val r = .25 + 1.6 * (pulse.position - .05)
            for (k in 0 until count) {
                s.stamp(pulse, SKIN_RADIATION[k] * besselJ(SKIN_M[k], SKIN_ZERO[k] * r), 0, strokes[k], strokesC[k])
            }
            val grade = (abs(pulse.amplitude) / s.catchScale).pow(.7).coerceAtMost(1.0)
            s.stampShape(pulse, 0, bendShape, grade * depth, bend, bendC)
        }
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            if (i % 16384 == 0) checkCancellation()
            val b = min(s.force(bend, bendC, i).toDouble(), SKIN_BEND)
            var sum = 0.0
            for (k in 0 until count) {
                var c = cr[k]; var sn = sr[k]
                if (b > 0.0) {
                    // The rotation for θ(1 + b), from θ's by angle addition with a short series for the small θb.
                    val y = theta[k] * b
                    val cy = 1.0 - y * y / 2.0 + y * y * y * y / 24.0
                    val sy = y - y * y * y / 6.0
                    c = cr[k] * cy - sr[k] * sy
                    sn = sr[k] * cy + cr[k] * sy
                }
                val x = c * re[k] - sn * im[k]
                im[k] = sn * re[k] + c * im[k] + s.force(strokes[k], strokesC[k], i)
                re[k] = x
                sum += x
            }
            out[i] = sum.toFloat()
        }
        return out
    }

    /** A free-free beam's mode shape at [x] along it (0 = an end, .5 = the middle), 1 at the ends. */
    private fun barShape(beta: Double, x: Double): Double {
        val sigma = (cosh(beta) - cos(beta)) / (sinh(beta) - sin(beta))
        return (cosh(beta * x) + cos(beta * x) - sigma * (sinh(beta * x) + sin(beta * x))) / 2.0
    }

    /** The Bessel function J_m(x) by its power series (x here is under 6, so 40 terms are ample). */
    private fun besselJ(m: Int, x: Double): Double {
        var term = 1.0
        for (j in 1..m) term *= x / 2.0 / j
        var sum = term
        for (k in 1..40) {
            term *= -(x * x / 4.0) / (k * (k + m))
            sum += term
        }
        return sum
    }

    /** A puff: the valve opens with time constant [rise] samples and closes with [tau], cut after 6 τ. */
    private fun puffShape(rise: Double, tau: Double): FloatArray =
        FloatArray((6.0 * tau).roundToInt()) { j -> ((1.0 - exp(-j / rise)) * exp(-j / tau)).toFloat() }

    /** How a catch at [position] along the resonator is shared between the two nearest [CATCH_POINTS]. */
    private fun anchorWeights(position: Double): DoubleArray {
        val w = DoubleArray(CATCH_POINTS.size)
        val p = position.coerceIn(CATCH_POINTS.first(), CATCH_POINTS.last())
        for (j in 0 until CATCH_POINTS.size - 1) {
            val lo = CATCH_POINTS[j]; val hi = CATCH_POINTS[j + 1]
            if (p <= hi) { val x = (p - lo) / (hi - lo); w[j] = 1.0 - x; w[j + 1] = x; return w }
        }
        w[w.size - 1] = 1.0
        return w
    }

    /** The grip before a catch: a sag that deepens over [length] samples and lets go at the catch. */
    private fun gripShape(length: Double): FloatArray {
        val n = max(1, length.roundToInt())
        return FloatArray(n) { j -> sin(.5 * PI * (j + 1.0) / n).pow(2).toFloat() }
    }

    /** A slip burst: a 1 ms rise, then an exponential fall with time constant [tau] samples, cut after 6 τ. */
    private fun burstShape(tau: Double): FloatArray {
        val rise = .001 * INTERNAL_RATE
        return FloatArray((6.0 * tau).roundToInt()) { j -> ((1.0 - exp(-j / rise)) * exp(-j / tau)).toFloat() }
    }

    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("SPLAY render cancelled")
    }
}
