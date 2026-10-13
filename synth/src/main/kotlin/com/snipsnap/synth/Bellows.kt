package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

enum class BellowsVoice { CHAMBER, COUNTER, FOLK, WHEEL }
enum class BellowsGesture { STEADY, SWELL, ACCENT, PULSE }

/**
 * Four dry, oscillator-based bellows/wheel approximations, inspired by XOleg's Sacred Bellows.
 * CHAMBER has detuned reed ranks and a short absorbed chamber; COUNTER explicitly blends
 * darker pull/brighter push TIMBRES at the same pitch; FOLK has asymmetric reeds; WHEEL
 * has a wheel-bowed melody, optional tracking octave/fifth drones and pressure-gated buzz.
 * These are instrument-inspired synthesizers, not reconstructed acoustic instruments.
 *
 * PRESSURE changes source level, harmonic response and brightness. BODY changes model-specific
 * resonance; MOTION changes breathing, pitch/wheel motion and gesture depth. HOLD below .999
 * controls the finite note's gate; its final step exports one settled repeating period. Each
 * render owns all phases/filter stores. There is no live global player or coupling state.
 *
 * Held sources fit oscillator, reed-beat and movement cycles to the same 2.8-second period;
 * the root moves by less than .01 cent. Beat rates are quantized to about .36 Hz, and tracking
 * drones fit nearby cycles, so HOLD can differ slightly from its finite counterpart. All
 * filters warm for a complete period. Wrapped band limiting/decimation retains the interior
 * period, avoiding a seam fade or an artificial level dip. This is periodic synthesis, not
 * a guarantee that arbitrary unquantized detuning can close a short sampled loop.
 *
 * Signal runs at 4x: PolyBLEP/BLAMP reeds -> pressure nonlinearity -> chamber/body -> voice
 * filter/DC -> eight-pole anti-alias lowpass -> shared decimation. Shared melodic matching is
 * the forte reference, followed by velocity^.60 so export layers retain physical dynamics.
 * renderRaw supplies the same conditioned source before matching for honest audition A/Bs.
 */
object Bellows {
    const val MODEL_VERSION = 1
    const val ROOT_MIDI = 48
    const val TUNE_SEMITONES = 24
    /** Fixed model headroom for source-unit PCM: preserves all gesture/velocity ratios.
     * The first C3/C5 macro-corner sweep measured an unscaled peak of 3.775, leaving
     * over 6 dB of margin here. This is neither a patch normalizer nor a limiter;
     * production subsequently matches the same source to the melodic forte reference.
     */
    const val RAW_SOURCE_HEADROOM = .125f
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val CONTROL_STRIDE = 64

    fun supportsModel(model: Int): Boolean = model == MODEL_VERSION
    @Suppress("UNUSED_PARAMETER")
    fun rootMidi(voice: BellowsVoice): Int = ROOT_MIDI
    @Suppress("UNUSED_PARAMETER")
    fun midiRange(voice: BellowsVoice): IntRange = ROOT_MIDI..ROOT_MIDI + TUNE_SEMITONES
    fun midiFor(voice: BellowsVoice, tune: Float): Int = rootMidi(voice) + (unit(tune, .5f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(voice: BellowsVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= .999f
    fun gestureFor(voice: BellowsVoice): BellowsGesture = when (voice) {
        BellowsVoice.CHAMBER -> BellowsGesture.SWELL
        BellowsVoice.COUNTER -> BellowsGesture.SWELL
        BellowsVoice.FOLK -> BellowsGesture.ACCENT
        BellowsVoice.WHEEL -> BellowsGesture.PULSE
    }

    fun macrosFor(voice: BellowsVoice): List<MacroSpec> {
        val pressure = when (voice) {
            BellowsVoice.CHAMBER -> .54f
            BellowsVoice.COUNTER -> .46f
            BellowsVoice.FOLK -> .63f
            BellowsVoice.WHEEL -> .66f
        }
        return listOf(
            MacroSpec("TUNE", .5f, neutral = .5f),
            MacroSpec("PRESSURE", pressure, neutral = .5f),
            MacroSpec("BODY", if (voice == BellowsVoice.CHAMBER) .62f else .45f, neutral = .45f),
            MacroSpec("MOTION", if (voice == BellowsVoice.WHEEL) .42f else .25f, neutral = 0f),
            MacroSpec("HOLD", if (voice == BellowsVoice.FOLK) .20f else .38f, neutral = 0f),
        ) + when (voice) {
            BellowsVoice.CHAMBER -> listOf(MacroSpec("REGISTER", .42f), MacroSpec("BEAT", .28f))
            BellowsVoice.COUNTER -> listOf(MacroSpec("REGISTER", .38f), MacroSpec("DIRECTION", .25f))
            BellowsVoice.FOLK -> listOf(MacroSpec("REGISTER", .66f), MacroSpec("BEAT", .38f))
            BellowsVoice.WHEEL -> listOf(MacroSpec("DRONE", .32f), MacroSpec("BUZZ", .58f))
        }
    }

    fun defaults(voice: BellowsVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(macros: Map<String, Float>, voice: BellowsVoice): Map<String, Float> =
        defaults(voice).mapValues { (key, value) -> unit(macros[key] ?: value, value) }

    fun scramble(voice: BellowsVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val d = defaults(voice)
        val base = d + (near?.macros ?: emptyMap()).filterKeys { it in d }
        return Dsp.scrambleNear(base, unit(temperature, .35f), random).toMutableMap().also {
            it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f)
            if (near is BellowsPatch) it["TUNE"] = it.getValue("TUNE").coerceAtMost(
                ((midiRange(voice).last - near.midi).toFloat() / TUNE_SEMITONES).coerceIn(0f, 1f))
        }
    }

    fun drumClassFor(voice: BellowsVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(macros, voice).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    private fun unit(x: Float, fallback: Float = 0f): Float = if (x.isFinite()) x.coerceIn(0f, 1f) else fallback
    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    private fun wrap(x: Double): Double = x - floor(x)
    private fun flush(x: Double): Double = if (abs(x) < 1e-25) 0.0 else x
    private fun checkCancelled(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("BELLOWS render no longer wanted")
    }

    private fun blep(p: Double, dt: Double): Double = when {
        p < dt -> { val x = p / dt; x + x - x * x - 1.0 }
        p > 1.0 - dt -> { val x = (p - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }
    private fun blamp(p: Double, dt: Double): Double = when {
        p < dt -> { val x = 1.0 - p / dt; x * x * x / 3.0 }
        p > 1.0 - dt -> { val x = 1.0 + (p - 1.0) / dt; x * x * x / 3.0 }
        else -> 0.0
    }
    private fun saw(p: Double, dt: Double): Double = 2.0 * p - 1.0 - blep(p, dt)
    private fun pulse(p: Double, dt: Double, width: Double): Double =
        ((if (p < width) 1.0 else -1.0) + blep(p, dt) - blep(wrap(p - width), dt) - (2.0 * width - 1.0)) /
            (2.0 * max(width, 1.0 - width))
    private fun triangle(p: Double, dt: Double): Double =
        1.0 - 4.0 * abs(p - .5) + 4.0 * dt * (blamp(p, dt) - blamp(wrap(p + .5), dt))

    /** Cached-coefficient linear-trapezoidal SVF; control changes are evaluated every 64 samples. */
    private class Filter(hz: Double, damping: Double) {
        private var a1 = 0.0; private var a2 = 0.0; private var a3 = 0.0
        private var k = damping
        private var ic1 = 0.0; private var ic2 = 0.0
        var low = 0.0; private set
        var band = 0.0; private set
        init { coefficients(hz, damping) }
        fun coefficients(hz: Double, damping: Double) {
            val g = tan(PI * hz.coerceIn(15.0, INTERNAL_RATE * .45) / INTERNAL_RATE)
            k = damping.coerceAtLeast(.35)
            a1 = 1.0 / (1.0 + g * (g + k)); a2 = g * a1; a3 = g * a2
        }
        fun step(x: Double): Double {
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = flush(2.0 * v1 - ic1); ic2 = flush(2.0 * v2 - ic2)
            low = v2; band = v1
            return low
        }
    }

    /* Cassotto and threshold-buzz concepts adapted from:
     * XO_OX Designs, OlegEngine.h, Copyright (c) 2026 XO_OX Designs, MIT License.
     * https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Source/Engines/Oleg/OlegEngine.h
     * Permission is hereby granted, free of charge, to any person obtaining a copy of this
     * software and associated documentation files (the "Software"), to deal in the Software
     * without restriction, including without limitation the rights to use, copy, modify,
     * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
     * permit persons to whom the Software is furnished to do so, subject to the following
     * conditions: The above copyright notice and this permission notice shall be included
     * in all copies or substantial portions of the Software.
     * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
     * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
     * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
     * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
     * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE
     * OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
     */
    private class Chamber(body: Double) {
        private val delay = DoubleArray((INTERNAL_RATE * (.0026 + .0012 * body)).roundToInt())
        private var at = 0
        private val absorption = Filter(2800.0 - 1400.0 * body, 1.5)
        private val ap1 = Allpass((INTERNAL_RATE * .0011).roundToInt(), .43)
        private val ap2 = Allpass((INTERNAL_RATE * .0017).roundToInt(), .37)
        private val depth = .10 + .64 * body
        fun step(x: Double): Double {
            val delayed = delay[at]
            delay[at] = x + .48 * absorption.step(delayed)
            if (++at == delay.size) at = 0
            val diffused = ap2.step(ap1.step(delayed * .52))
            return x * (1.0 - depth) + diffused * depth
        }
    }
    private class Allpass(size: Int, private val gain: Double) {
        private val history = DoubleArray(size)
        private var at = 0
        fun step(x: Double): Double {
            val y = history[at] - gain * x
            history[at] = x + gain * y
            if (++at == history.size) at = 0
            return y
        }
    }

    private data class Plan(val frames: Int, val gate: Double, val loop: Boolean, val hz: Double,
                            val frequencies: DoubleArray, val motionCycles: Int, val wheelCycles: Int)
    private fun plan(voice: BellowsVoice, m: Map<String, Float>, playedMidi: Int): Plan {
        val note = Keys.midiHz(playedMidi).toDouble()
        val held = isLoop(m.getValue("HOLD"))
        val gate = .27 + 3.25 * m.getValue("HOLD").toDouble().pow(1.35)
        val cycleCount = (2.8 * note).roundToInt()
        val frames = if (held) (cycleCount * Dsp.RATE / note).roundToInt() else ((gate + 1.05) * Dsp.RATE).roundToInt()
        val seconds = frames.toDouble() / Dsp.RATE
        val hz = if (held) cycleCount / seconds else note
        val beat = m["BEAT"]?.toDouble() ?: .18
        val cents = if (beat <= 0.0) 0.0 else 1.0 + 25.0 * beat * beat
        val detune = 2.0.pow(cents / 1200.0)
        fun fitted(f: Double): Double = if (held) (f * seconds).roundToInt() / seconds else f
        val frequencies = when (voice) {
            BellowsVoice.CHAMBER, BellowsVoice.FOLK -> doubleArrayOf(hz, fitted(hz * detune), fitted(hz / detune), hz * 2.0)
            BellowsVoice.COUNTER -> doubleArrayOf(hz, fitted(hz * 2.0.pow(4.5 / 1200.0)), hz * 2.0, hz * .5)
            BellowsVoice.WHEEL -> doubleArrayOf(hz, fitted(hz * .5), fitted(hz * 2.0.pow(-7.0 / 12.0)), hz)
        }
        return Plan(frames, if (held) seconds else gate, held, hz, frequencies,
            1 + (m.getValue("MOTION") * 5).roundToInt(), (seconds * (3.0 + m.getValue("MOTION") * 6.0)).roundToInt())
    }

    private fun gestureLevel(gesture: BellowsGesture, u: Double, motion: Double, cycles: Int, loop: Boolean): Double {
        val breathing = sin(2.0 * PI * u)
        val movement = sin(2.0 * PI * cycles * u)
        return when (gesture) {
            BellowsGesture.STEADY -> 1.0 + motion * (.12 * breathing + .05 * movement)
            BellowsGesture.SWELL -> if (loop) .74 + (.12 + .12 * motion) * breathing else
                .38 + .66 * sin(PI * u.coerceIn(0.0, 1.0)) + .08 * motion * movement
            BellowsGesture.ACCENT -> if (loop) .70 + (.16 + .15 * motion) * cos(2.0 * PI * u) else
                .66 + .47 * exp(-u * (12.0 + 10.0 * motion)) + .10 * motion * movement
            BellowsGesture.PULSE -> {
                val pulse = (.5 + .5 * cos(2.0 * PI * cycles * u)).pow(3.0)
                .44 + (.36 + .42 * motion) * pulse
            }
        }
    }

    /** Dimensionless player pressure for reproducible gesture/threshold diagnostics. */
    fun pressureAt(voice: BellowsVoice, macros: Map<String, Float>, position: Double,
                   velocity: Float = 1f, gesture: BellowsGesture = gestureFor(voice), loop: Boolean = false): Float {
        val m = settled(macros, voice)
        val motion = m.getValue("MOTION").toDouble()
        val cycles = 1 + (motion * 5).roundToInt()
        return ((.08 + 1.12 * m.getValue("PRESSURE")) * unit(velocity, 1f).toDouble().pow(.55) *
            gestureLevel(gesture, position, motion, cycles, loop)).coerceIn(0.0, 1.4).toFloat()
    }

    private fun synthesize(voice: BellowsVoice, m: Map<String, Float>, p: Plan, velocity: Float,
                           gesture: BellowsGesture, playedMidi: Int, cancelled: () -> Boolean): FloatArray {
        val n = p.frames * Dsp.OVERSAMPLE
        val warm = if (p.loop) n else 0
        val margin = if (p.loop) 2048 else 0
        val raw = FloatArray(warm + n + margin)
        if (velocity == 0f) return raw
        val body = m.getValue("BODY").toDouble()
        val motion = m.getValue("MOTION").toDouble()
        val register = m["REGISTER"]?.toDouble() ?: .5
        val direction = m["DIRECTION"]?.toDouble() ?: .5
        val drone = m["DRONE"]?.toDouble() ?: 0.0
        val buzz = m["BUZZ"]?.toDouble() ?: 0.0
        val phases = Dsp.phases(4, Dsp.seedFor("BELLOWS", MODEL_VERSION, voice, playedMidi))
        val shifted = DoubleArray(4)
        val steps = DoubleArray(4) { p.frequencies[it] / INTERNAL_RATE }
        val main = Filter(4000.0, 1.4)
        val formant = Filter(when (voice) {
            BellowsVoice.CHAMBER -> 1200.0 - 500.0 * body
            BellowsVoice.COUNTER -> 850.0 + 800.0 * body
            BellowsVoice.FOLK -> 550.0 + 1600.0 * body
            BellowsVoice.WHEEL -> 450.0 + 1200.0 * body
        }, 1.5 - .8 * body)
        val chamber = if (voice == BellowsVoice.CHAMBER) Chamber(body) else null
        val extraction = Filter(350.0, 1.1)
        val rattle = Filter(600.0 + 650.0 * body, .9)
        val antiAlias = arrayOf(Filter(19500.0, 1.9616), Filter(19500.0, 1.6629), Filter(19500.0, 1.1111), Filter(19500.0, .3902))
        val pressureScale = (.08 + 1.12 * m.getValue("PRESSURE")) * velocity.toDouble().pow(.55)
        val sourceGain = velocity.toDouble().pow(.35)
        val dcCoeff = 1.0 - exp(-2.0 * PI * 18.0 / INTERNAL_RATE)
        val attack = when (voice) {
            BellowsVoice.CHAMBER -> .023
            BellowsVoice.COUNTER -> .017
            BellowsVoice.FOLK -> .008
            BellowsVoice.WHEEL -> .045
        }
        val release = when (voice) {
            BellowsVoice.CHAMBER -> .15 + .13 * body
            BellowsVoice.COUNTER -> .12 + .10 * body
            BellowsVoice.FOLK -> .065 + .08 * body
            BellowsVoice.WHEEL -> .16 + .12 * body
        }
        var pressure = if (p.loop) pressureScale * gestureLevel(gesture, 0.0, motion, p.motionCycles, true) else 0.0
        var pressureStep = 0.0
        var dc = 0.0
        for (i in raw.indices) {
            if (i % 8192 == 0) checkCancelled(cancelled)
            val index = if (p.loop) i % n else i
            val t = index.toDouble() / INTERNAL_RATE
            val u = if (p.loop) index.toDouble() / n else (t / p.gate).coerceAtMost(1.0)
            if (index % CONTROL_STRIDE == 0) {
                val hop = if (p.loop) minOf(CONTROL_STRIDE, n - index) else CONTROL_STRIDE
                val next = if (p.loop) ((index + hop) % n).toDouble() / n else
                    ((t + CONTROL_STRIDE.toDouble() / INTERNAL_RATE) / p.gate).coerceAtMost(1.0)
                val target = pressureScale * gestureLevel(gesture, next, motion, p.motionCycles, p.loop)
                pressureStep = (target.coerceIn(0.0, 1.4) - pressure) / hop
                val cutoff = when (voice) {
                    BellowsVoice.CHAMBER -> 1300.0 + 4000.0 * register + 3000.0 * pressure
                    BellowsVoice.COUNTER -> 850.0 + 2500.0 * register + 1600.0 * direction + 2800.0 * pressure
                    BellowsVoice.FOLK -> 1500.0 + 4200.0 * register + 3000.0 * pressure
                    BellowsVoice.WHEEL -> 1900.0 + 3300.0 * pressure
                }
                main.coefficients(cutoff * (p.hz / 261.625565).pow(.30), if (voice == BellowsVoice.FOLK) 1.05 else 1.45)
            }
            pressure = (pressure + pressureStep).coerceIn(0.0, 1.4)
            val env = if (p.loop) 1.0 else if (t < p.gate) smooth(t / attack) else
                exp(-6.907755 * (t - p.gate) / release) * (1.0 - smooth((t - p.gate - release) / .15))
            val vibCycles = if (p.loop) p.wheelCycles.toDouble() else p.gate * (3.0 + 6.0 * motion)
            // Phase displacement, rather than integrated frequency, has exact periodic closure.
            val vibrato = sin(2.0 * PI * vibCycles * u) * motion * (if (voice == BellowsVoice.WHEEL) .18 else .045)
            val breathing = sin(2.0 * PI * u) * motion * .035
            for (k in shifted.indices) shifted[k] = wrap(phases[k] +
                (if (k == 0 || voice != BellowsVoice.WHEEL) vibrato + breathing else 0.0))
            val signal = when (voice) {
                BellowsVoice.CHAMBER -> {
                    val rank = .48 * saw(shifted[0], steps[0]) + .25 * saw(shifted[1], steps[1]) +
                        .20 * saw(shifted[2], steps[2]) + .07 * pulse(shifted[0], steps[0], .45)
                    (1.0 - .23 * register) * rank + .23 * register * saw(shifted[3], steps[3])
                }
                BellowsVoice.COUNTER -> {
                    val pull = .68 * triangle(shifted[0], steps[0]) + .32 * triangle(shifted[1], steps[1])
                    val push = .48 * triangle(shifted[0], steps[0]) + .52 * saw(shifted[1], steps[1])
                    val ranks = pull * (1.0 - direction) + push * direction
                    ranks * (1.0 - .22 * register) + .22 * register * triangle(shifted[2], steps[2])
                }
                BellowsVoice.FOLK -> {
                    val duty = .28 + .15 * (1.0 - register)
                    .48 * pulse(shifted[0], steps[0], duty) + .27 * pulse(shifted[1], steps[1], duty) +
                        .15 * saw(shifted[2], steps[2]) + .10 * register * saw(shifted[3], steps[3])
                }
                BellowsVoice.WHEEL -> .76 * saw(shifted[0], steps[0]) + drone *
                    (.16 * saw(shifted[1], steps[1]) + .14 * saw(shifted[2], steps[2]))
            }
            for (k in phases.indices) phases[k] = wrap(phases[k] + steps[k])
            val breath = pressure * env
            var x = signal * breath * sourceGain
            if (voice == BellowsVoice.FOLK) x = tanh(x * (1.25 + 2.2 * pressure)) / 1.65
            if (voice == BellowsVoice.COUNTER) x = .76 * x + .24 * tanh(x * (1.0 + 1.6 * pressure))
            if (chamber != null) x = chamber.step(x)
            if (voice == BellowsVoice.WHEEL) {
                extraction.step(x)
                // Small smooth pressure transition avoids a discontinuous gate at threshold .52.
                val lift = smooth((breath - .49) / .06)
                val excess = ((breath - .49) / .70).coerceIn(0.0, 1.0)
                val gate = lift * excess.pow(.65)
                val impact = tanh(extraction.band * (2.0 + 13.0 * buzz)) * gate
                rattle.step(impact)
                x += buzz * (.55 * impact + .45 * rattle.band) * gate
            }
            formant.step(x)
            x += formant.band * body * when (voice) {
                BellowsVoice.CHAMBER -> .20
                BellowsVoice.COUNTER -> .24
                BellowsVoice.FOLK -> .40
                BellowsVoice.WHEEL -> .28
            }
            x = main.step(x)
            dc = flush(dc + dcCoeff * (x - dc)); x -= dc
            for (filter in antiAlias) x = filter.step(x)
            check(x.isFinite()) { "BELLOWS $voice produced a nonfinite sample" }
            raw[i] = flush(x).toFloat()
        }
        return raw
    }

    /** Conditioned 44.1 kHz source before loudness matching; includes velocity's acoustic response. */
    fun renderRaw(voice: BellowsVoice, macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI,
                  velocity: Float = 1f, gesture: BellowsGesture = gestureFor(voice), model: Int = MODEL_VERSION,
                  cancelled: () -> Boolean = { false }): Snip {
        require(supportsModel(model)) { "unsupported bellows model $model" }
        require(velocity.isFinite() && velocity in 0f..1f) { "BELLOWS velocity out of 0..1: $velocity" }
        val m = settled(macros, voice)
        val played = midi + (m.getValue("TUNE") * TUNE_SEMITONES).roundToInt()
        require(midi in midiRange(voice) && played in midiRange(voice)) { "BELLOWS $voice played midi out of 48..72: $played (base $midi)" }
        checkCancelled(cancelled)
        val p = plan(voice, m, played)
        val native = synthesize(voice, m, p, velocity, gesture, played, cancelled)
        checkCancelled(cancelled)
        val decimated = Dsp.decimate(native, Dsp.RATE)
        checkCancelled(cancelled)
        val out = if (p.loop) decimated.copyOfRange(p.frames, p.frames * 2) else decimated
        if (p.loop) {
            val mean = out.sumOf { it.toDouble() } / out.size
            for (i in out.indices) out[i] -= mean.toFloat()
        } else Dsp.fadeTail(out)
        for (i in out.indices) out[i] *= RAW_SOURCE_HEADROOM
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }

    fun renderLoop(voice: BellowsVoice, macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI,
                   velocity: Float = 1f, gesture: BellowsGesture = gestureFor(voice), model: Int = MODEL_VERSION,
                   cancelled: () -> Boolean = { false }): FloatArray =
        render(voice, macros + ("HOLD" to 1f), midi, velocity, gesture, model, cancelled).samples

    fun render(voice: BellowsVoice, macros: Map<String, Float> = emptyMap(), midi: Int = ROOT_MIDI,
               velocity: Float = 1f, gesture: BellowsGesture = gestureFor(voice), model: Int = MODEL_VERSION,
               cancelled: () -> Boolean = { false }): Snip {
        val raw = renderRaw(voice, macros, midi, velocity, gesture, model, cancelled)
        val out = raw.samples
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        val dynamics = velocity.pow(.60f)
        for (i in out.indices) out[i] *= dynamics
        checkCancelled(cancelled)
        return raw
    }
}
