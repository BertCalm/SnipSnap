package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.Random
import kotlin.math.*

/** CARTRIDGE: chip breakcore, the kit of an 8-bit console's sound chip. Its character is four things: pulse
 * duty (12.5, 25 and 50 percent), a stepped 4-bit triangle, a linear-feedback shift register for noise (long
 * mode a 15-bit register that hisses, short mode a 93-step loop that rings metallic and pitched, its clock
 * rate setting that pitch), and pitch and volume that move only on the 60 Hz frame, never smoothly, with
 * sixteen volume levels.
 *
 * The characteristic gesture is: a game-console arpeggio, a crushed break smashed through it, and noise-channel
 * drums.
 *
 * None of the character is aliasing. The pulse is the difference of two polyBLEP saws; the triangle staircase
 * and the shift register render at [Dsp.OVERSAMPLE]x and come down through [Dsp.decimate], so the steps and the
 * edges stay and nothing folds back into the band. Pitch and volume read the chip frame, [frameOf]. Velocity is
 * not a chip feature, so it is mapped onto chip settings: the duty (soft 50 percent, round; hard 12.5 percent,
 * thin and biting), the sweep depth, the noise clock and mode, and the starting volume step. A take varies the
 * shift register's start state, a few cents (a constant ratio on the oscillator's own frequency; no TUNE is
 * involved) and the vibrato phase. Crushed Break is [Shatter]'s Crack slice cut to 4 bits, held at about
 * 11 kHz and low-passed back. Every constant awaits the owner's listen. */
object Cartridge : BreakcoreKit {
    const val VERSION = 1
    const val ID = "cartridge-breakcore"
    override val id = ID
    override val title = "CARTRIDGE"
    override val bpm = 190

    /** The chip's frame rate: every pitch and volume change lands on one of these. */
    const val FPS = 60

    override val layout: List<Splinter.Part> = listOf(
        Splinter.Part("Chip Kick", DrumClass.KICK, 1f),
        Splinter.Part("Chip Snare", DrumClass.SNARE, .9f),
        Splinter.Part("Chip Hat", DrumClass.HAT_CLOSED, .45f, 1),
        Splinter.Part("Chip Open Hat", DrumClass.HAT_OPEN, .45f, 1),
        Splinter.Part("Lead C", DrumClass.TONAL, .7f, 2),
        Splinter.Part("Lead G", DrumClass.TONAL, .7f, 2),
        Splinter.Part("Arp Cm", DrumClass.TONAL, .65f),
        Splinter.Part("Arp Ab", DrumClass.TONAL, .65f),
        Splinter.Part("Tri Bass C", DrumClass.TONAL, .85f),
        Splinter.Part("Tri Bass G", DrumClass.TONAL, .85f),
        Splinter.Part("Sweep Up", DrumClass.PERC, .6f),
        Splinter.Part("Blip", DrumClass.TONAL, .55f),
        Splinter.Part("Explosion", DrumClass.PERC, .8f),
        Splinter.Part("Crushed Break", DrumClass.PERC, .8f),
        Splinter.Part("Duty Sweep", DrumClass.TONAL, .65f),
        Splinter.Part("Echo Lead", DrumClass.TONAL, .65f, 2),
        Splinter.Part("Noise Roll", DrumClass.LOOP, .7f),
        Splinter.Part("Arp Rise", DrumClass.LOOP, .6f),
    )

    private fun shape(v: Double) = v.pow(.8)
    private fun cents(t: Int, spread: Double) = listOf(0.0, -spread, spread)[t % 3] * (1 + t / 3)
    private fun ratioOf(t: Int, spread: Double) = 2.0.pow(cents(t, spread) / 1200)
    private fun hzOf(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun rng(name: String, pad: Int, t: Int) = Random(Dsp.seedFor("cartridge", name, pad, t).toLong())
    private fun beatSamples() = Breakcore.beat(bpm).roundToInt()

    // ---- the chip's clock, and the three oscillators ----

    /** The chip frame that base-rate-times-[Dsp.OVERSAMPLE] sample [i4] falls in. */
    internal fun frameOf(i4: Int): Int = ((i4.toLong() * FPS) / (Dsp.RATE.toLong() * Dsp.OVERSAMPLE)).toInt()

    /** One past the last chip frame an [n]-sample note touches. */
    private fun endFrame(n: Int): Int = (n.toLong() * FPS / Dsp.RATE).toInt() + 1

    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1 }
        t > 1 - dt -> { val x = (t - 1) / dt; x * x + x + x + 1 }
        else -> 0.0
    }

    private fun down(up: FloatArray, n: Int): FloatArray = Dsp.decimate(up, Dsp.RATE).copyOf(n)

    /** A pulse at [Dsp.OVERSAMPLE]x, before the decimator: the difference of two polyBLEP saws, the second a duty
     * behind the first, so it is high for [duty] of the cycle and has no mean. [hz], [duty] and [vol] (0 to 15)
     * are read once per chip frame; a new duty takes hold at the next cycle start, as on the chip. */
    internal fun pulseUp(n: Int, phase0: Double, hz: (Int) -> Double, duty: (Int) -> Double, vol: (Int) -> Int): FloatArray {
        val up = FloatArray(n * Dsp.OVERSAMPLE); val rate = Dsp.RATE.toDouble() * Dsp.OVERSAMPLE
        var p = phase0 - floor(phase0); var lastF = -1; var dt = 0.0; var amp = 0.0; var d = duty(0)
        for (i in up.indices) {
            val f = frameOf(i)
            if (f != lastF) { lastF = f; dt = hz(f) / rate; amp = vol(f).coerceIn(0, 15) / 15.0 }
            val q = if (p >= d) p - d else p - d + 1
            val s1 = 2 * p - 1 - blep(p, dt); val s2 = 2 * q - 1 - blep(q, dt)
            up[i] = ((s2 - s1) * .5 * amp).toFloat()
            p += dt; if (p >= 1) { p -= 1; d = duty(f) }
        }
        return up
    }

    private fun pulse(n: Int, phase0: Double, hz: (Int) -> Double, duty: (Int) -> Double, vol: (Int) -> Int): FloatArray =
        down(pulseUp(n, phase0, hz, duty, vol), n)

    /** The triangle's level at [phase] (0 to 1): 16 levels up, 16 down, 32 steps a cycle. */
    internal fun triLevel(phase: Double): Int {
        val k = (phase * 32).toInt().coerceIn(0, 31)
        return if (k < 16) k else 31 - k
    }

    /** The stepped triangle at [Dsp.OVERSAMPLE]x, before the decimator: sixteen distinct levels per cycle, scaled by the volume step. */
    internal fun triangleUp(n: Int, phase0: Double, hz: (Int) -> Double, vol: (Int) -> Int): FloatArray {
        val up = FloatArray(n * Dsp.OVERSAMPLE); val rate = Dsp.RATE.toDouble() * Dsp.OVERSAMPLE
        var p = phase0 - floor(phase0); var lastF = -1; var dt = 0.0; var amp = 0.0
        for (i in up.indices) {
            val f = frameOf(i)
            if (f != lastF) { lastF = f; dt = hz(f) / rate; amp = vol(f).coerceIn(0, 15) / 15.0 }
            up[i] = ((triLevel(p) - 7.5) / 7.5 * amp).toFloat()
            p += dt; if (p >= 1) p -= 1
        }
        return up
    }

    private fun triangle(n: Int, phase0: Double, hz: (Int) -> Double, vol: (Int) -> Int): FloatArray =
        down(triangleUp(n, phase0, hz, vol), n)

    /** The 15-bit shift register. Feedback is bit 0 xor bit 1 (long mode) or bit 0 xor bit 6 (short mode, a
     * 93-step loop from the states [lfsrStart] gives); the output is high while bit 0 is clear. */
    internal class Lfsr(var state: Int, val short: Boolean) {
        fun step() { val x = (state xor (state shr (if (short) 6 else 1))) and 1; state = (state shr 1) or (x shl 14) }
        fun out(): Float = if ((state and 1) == 0) 1f else -1f
    }

    /** A register state [advance] steps into the loop from state 1, so every take stays on the 93-step loop in short mode. */
    internal fun lfsrStart(short: Boolean, advance: Int): Int { val l = Lfsr(1, short); repeat(advance) { l.step() }; return l.state }

    /** Steps before the register returns to state 1. */
    internal fun lfsrPeriod(short: Boolean): Int {
        val l = Lfsr(1, short); var n = 0
        do { l.step(); n++ } while (l.state != 1 && n < 100_000)
        return n
    }

    private fun advance(t: Int, variant: Int) = t * 211 + variant * 37 + 5

    /** LFSR noise at [Dsp.OVERSAMPLE]x, before the decimator: the register is clocked at [clock] Hz (read per frame) and held between clocks. */
    internal fun noiseUp(n: Int, state0: Int, short: Boolean, clock: (Int) -> Double, vol: (Int) -> Int): FloatArray {
        val up = FloatArray(n * Dsp.OVERSAMPLE); val rate = Dsp.RATE.toDouble() * Dsp.OVERSAMPLE
        val l = Lfsr(state0, short); var acc = 0.0; var lastF = -1; var step = 0.0; var amp = 0.0
        for (i in up.indices) {
            val f = frameOf(i)
            if (f != lastF) { lastF = f; step = min(clock(f), 60000.0) / rate; amp = vol(f).coerceIn(0, 15) / 15.0 }
            acc += step; while (acc >= 1) { acc -= 1; l.step() }
            up[i] = (l.out() * amp).toFloat()
        }
        return up
    }

    private fun noise(n: Int, state0: Int, short: Boolean, clock: (Int) -> Double, vol: (Int) -> Int): FloatArray =
        down(noiseUp(n, state0, short, clock, vol), n)

    // ---- frame envelopes: whole volume steps, never a ramp ----

    /** Full volume [start] held to [endFrame], stepping down by one every [droop] frames if given, then [release] frames of falling steps. */
    private fun held(start: Int, endFrame: Int, release: Int, droop: Int = 0): (Int) -> Int = { f ->
        val base = start - (if (droop > 0) f / droop else 0)
        when {
            f >= endFrame -> 0
            endFrame - f <= release -> (base * (endFrame - f).toDouble() / (release + 1)).roundToInt().coerceIn(0, 15)
            else -> base.coerceIn(0, 15)
        }
    }

    /** Starting volume step for a pitched voice: 11 soft, 13 medium, 15 hard. */
    private fun vol0(v: Double) = (9 + 6 * v).roundToInt()
    /** Starting volume step for a noise voice: 10, 13, 15. */
    private fun noiseStart(v: Double) = (8 + 7 * v).roundToInt()
    /** Soft is 50 percent, round; medium 25; hard 12.5, thin and biting. */
    private fun dutyFor(v: Double) = if (v < .5) .5 else if (v < .85) .25 else .125

    private fun vibCents(f: Int, t: Int, delayFrames: Int = 10): Double {
        val ramp = ((f - delayFrames) / 6.0).coerceIn(0.0, 1.0)
        val rate = 6.0 + .5 * listOf(0, -1, 1)[t % 3]; val phase = listOf(0.0, .33, .66)[t % 3]
        return 22.0 * ramp * sin(2 * PI * (rate * f / FPS + phase))
    }

    /** Where the arp sits in each chip frame: [base] plus the next of [intervals], cycling, one per frame. */
    internal fun arpSchedule(frames: Int, base: Int, intervals: IntArray): IntArray = IntArray(frames) { base + intervals[it % intervals.size] }

    // ---- the pads ----

    private fun chipKick(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.3); val ratio = ratioOf(t, 5.0)
        val f1 = 65.41 * ratio; val depth = 4 + 6 * v; val start = (11 + 4 * v).roundToInt()
        val body = triangle(n, 0.0, { f -> f1 * (1 + (depth - 1) * exp(-f / 2.2)) }, { f -> (start * exp(-f / 6.5)).roundToInt() })
        val ns = noiseStart(v)
        val tick = noise(n, lfsrStart(false, advance(t, 0)), false, { 22000.0 * ratio }, { f -> when (f) { 0 -> ns; 1 -> ns / 2; else -> 0 } })
        return FloatArray(n) { body[it] + .55f * tick[it] }
    }

    /** One snare hit [n] samples long: long-mode noise stepping down, a pulse body dropping in pitch. [variant] gives a roll's hits their own register state. */
    private fun chipSnare(v: Double, t: Int, variant: Int, n: Int): FloatArray {
        val ratio = ratioOf(t, 5.0); val ns = noiseStart(v)
        val hiss = noise(n, lfsrStart(false, advance(t, variant)), false, { (7000 + 11000 * v) * ratio }, { f -> (ns * exp(-f / 3.6)).roundToInt() })
        val body = pulse(n, 0.0, { f -> (170 + 190 * exp(-f / 1.5)) * ratio }, { dutyFor(v) }, { f -> (ns * .9 * exp(-f / 2.2)).roundToInt() })
        return FloatArray(n) { .75f * hiss[it] + .5f * body[it] }
    }

    private fun chipHat(v: Double, t: Int, open: Boolean): FloatArray {
        val n = Breakcore.frames(if (open) .2 else .03); val start = noiseStart(v)
        val openVol: (Int) -> Int = { f -> (start * exp(-f / 4.0)).roundToInt() }
        val closedVol: (Int) -> Int = { f -> when (f) { 0 -> start; 1 -> (start * .45).roundToInt(); else -> 0 } }
        val ratio = ratioOf(t, 6.0)
        return noise(n, lfsrStart(true, advance(t, 3)), true, { (12000 + 14000 * v) * ratio }, if (open) openVol else closedVol)
    }

    /** A pulse (by velocity: 50, 25, 12.5 percent) with vibrato that comes in after ten frames. */
    private fun lead(midi: Int, v: Double, t: Int, duty: Double = dutyFor(v), start: Int = vol0(v)): FloatArray {
        val n = beatSamples(); val r = rng("lead", midi, t); val base = hzOf(midi) * ratioOf(t, 5.0)
        return pulse(n, r.nextDouble(), { f -> base * 2.0.pow(vibCents(f, t) / 1200) }, { duty }, held(start, endFrame(n), 3, 6))
    }

    private fun arp(base: Int, intervals: IntArray, v: Double, t: Int): FloatArray {
        val n = beatSamples(); val end = endFrame(n); val r = rng("arp", base, t); val ratio = ratioOf(t, 5.0)
        val sched = arpSchedule(end + 2, base, intervals)
        return pulse(n, r.nextDouble(), { f -> hzOf(sched[f]) * ratio }, { dutyFor(v) }, held(vol0(v), end, 2))
    }

    /** The triangle with a pitch punch on the attack, deeper when harder: the sweep depth. */
    private fun triBass(hz0: Double, v: Double, t: Int): FloatArray {
        val n = beatSamples(); val ratio = ratioOf(t, 4.0); val depth = 9 * v; val punch = doubleArrayOf(1.0, .55, .2)
        return triangle(n, 0.0, { f -> hz0 * ratio * 2.0.pow((if (f < 3) punch[f] * depth else 0.0) / 12) }, held((10 + 5 * v).roundToInt(), endFrame(n), 3))
    }

    /** A pulse that climbs in frame steps from G4, 20 to 30 semitones in ten frames: the pickup sound. */
    private fun sweepUp(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.24); val perFrame = 1.6 + 1.4 * v; val ratio = ratioOf(t, 5.0)
        return pulse(n, rng("sweep", 10, t).nextDouble(), { f -> 392.0 * ratio * 2.0.pow(min(f, 10) * perFrame / 12) }, { dutyFor(v) }, held(vol0(v), endFrame(n), 4))
    }

    /** B5 then E6, three frames each, the second falling away in steps. */
    private fun blip(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.1); val ratio = ratioOf(t, 5.0); val start = vol0(v)
        val vol: (Int) -> Int = { f -> if (f < 3) start else (start * (6 - f) / 4.0).roundToInt() }
        return pulse(n, rng("blip", 11, t).nextDouble(), { f -> hzOf(if (f < 3) 83 else 88) * ratio }, { dutyFor(v) }, vol)
    }

    /** Long-mode noise whose clock falls from a bright hiss to a rumble, the volume stepping down over about .6 s. */
    private fun explosion(v: Double, t: Int): FloatArray {
        val n = Breakcore.frames(.6); val start = (10 + 5 * v).roundToInt(); val ratio = ratioOf(t, 6.0)
        return noise(n, lfsrStart(false, advance(t, 4)), false, { f -> (700 + (5000 + 9000 * v) * exp(-f / 9.0)) * ratio },
            { f -> (start * (1 - f / 36.0).coerceAtLeast(0.0).pow(1.7)).roundToInt() })
    }

    /** [Shatter]'s Crack slice cut to 4 bits (16 levels), held at about 11 kHz, low-passed back down and
     * driven a little, so the crack stays punchy. The cutoff and the drive open up with velocity. */
    private fun crushedBreak(v: Double, t: Int): FloatArray {
        val src = Shatter.renderPad(9, v, t).samples
        val peak = src.maxOf { abs(it) }
        if (peak < 1e-6f) return src
        val hold = Dsp.RATE / (11025.0 * listOf(1.0, .96, 1.04)[t % 3])
        val crushed = FloatArray(src.size); var level = 0f; var next = 0.0
        for (i in src.indices) {
            if (i >= next) { level = ((src[i] / peak * 8).roundToInt().coerceIn(-8, 7)) / 8f; next += hold }
            crushed[i] = level
        }
        val cutoff = 4500.0 + 4000 * v
        var y = Breakcore.svf(crushed, 1.414f, 0) { cutoff }
        y = Breakcore.svf(y, 1.414f, 0) { cutoff }
        val driven = Breakcore.drive(y, 1.3 + .8 * v)
        val top = driven.maxOf { abs(it) }
        return FloatArray(driven.size) { driven[it] / top }
    }

    private fun dutySweep(v: Double, t: Int): FloatArray {
        val n = beatSamples(); val end = endFrame(n); val top = .25 + .25 * v; val ratio = ratioOf(t, 5.0)
        val duty: (Int) -> Double = { f ->
            val x = (f.toDouble() / (end - 1)).coerceIn(0.0, 1.0); val tri = 1 - abs(2 * x - 1)
            ((.125 + (top - .125) * tri) * 32).roundToInt() / 32.0
        }
        return pulse(n, rng("duty", 14, t).nextDouble(), { 130.81 * ratio }, duty, held(vol0(v), end, 3))
    }

    /** Lead C, and the chip's echo trick: the same note an eighth later, a third as loud, at 50 percent duty. */
    private fun echoLead(v: Double, t: Int): FloatArray {
        val dry = lead(72, v, t); val wet = lead(72, v, t, duty = .5, start = (vol0(v) / 3.0).roundToInt())
        val delay = (Breakcore.beat(bpm) / 2).roundToInt(); val out = FloatArray(dry.size + delay)
        for (i in dry.indices) out[i] += dry[i]
        for (i in wet.indices) out[delay + i] += wet[i]
        return out
    }

    /** Where Noise Roll's hits land, in beats: sixteenths, then thirty-seconds for two beats, then sixty-fourths. */
    internal fun rollOnsetBeats(): List<Double> =
        List(4) { it * .25 } + List(8) { 1 + it * .125 } + List(8) { 2 + it * .125 } + List(16) { 3 + it / 16.0 }

    private fun noiseRoll(v: Double, t: Int): FloatArray {
        val beat = Breakcore.beat(bpm); val onsets = rollOnsetBeats().map { (it * beat).roundToInt() }
        val tail = Breakcore.frames(.24); val out = FloatArray(onsets.last() + tail); val fade = Breakcore.frames(.002)
        for ((k, at) in onsets.withIndex()) {
            val end = onsets.getOrNull(k + 1)?.minus(at) ?: tail
            val hit = chipSnare(v, t, k, end)
            val level = ((.3 + .7 * k / onsets.lastIndex) * 15).roundToInt() / 15.0
            for (j in hit.indices) out[at + j] += (hit[j] * level * (if (end - j < fade) (end - j).toDouble() / fade else 1.0)).toFloat()
        }
        return out
    }

    /** The climb: C minor seventh tones from C4 up three octaves to C7, a step every 9.5 frames for two beats and every 4.75 for the next two. */
    private fun arpRise(v: Double, t: Int): FloatArray {
        val beat = Breakcore.beat(bpm); val half = 2 * beat * FPS / Dsp.RATE
        val n = (4 * beat).roundToInt() + Breakcore.frames(.2); val end = endFrame(n); val total = 4 * beat * FPS / Dsp.RATE
        val r = rng("rise", 17, t); val ratio = ratioOf(t, 5.0); val lo = 6; val hi = vol0(v)
        val tones = intArrayOf(0, 3, 7, 10)
        fun midi(f: Int): Int {
            val k = if (f < half) (f / (half / 4)).toInt() else if (f < 2 * half) 4 + ((f - half) / (half / 8)).toInt() else 12
            return 60 + 12 * (k / 4) + tones[k % 4]
        }
        val vol: (Int) -> Int = { f ->
            val base = (lo + (hi - lo) * min(1.0, f / total)).roundToInt()
            if (f >= end) 0 else if (end - f <= 5) (base * (end - f) / 6.0).roundToInt() else base
        }
        return pulse(n, r.nextDouble(), { f -> hzOf(midi(f)) * ratio }, { dutyFor(v) }, vol)
    }

    override fun renderPad(pad: Int, velocity: Double, take: Int): Snip {
        require(pad in layout.indices && velocity.isFinite() && velocity in 0.0..1.0 && take >= 0)
        val v = velocity; val t = take
        if (v == 0.0) return Snip(FloatArray(Breakcore.frames(.1)), 1, Dsp.RATE)
        val x = when (pad) {
            0 -> chipKick(v, t)
            1 -> chipSnare(v, t, 0, Breakcore.frames(.24))
            2 -> chipHat(v, t, false)
            3 -> chipHat(v, t, true)
            4 -> lead(72, v, t)
            5 -> lead(79, v, t)
            6 -> arp(72, intArrayOf(0, 3, 7), v, t)
            7 -> arp(68, intArrayOf(0, 4, 7), v, t)
            8 -> triBass(65.41, v, t)
            9 -> triBass(49.0, v, t)
            10 -> sweepUp(v, t)
            11 -> blip(v, t)
            12 -> explosion(v, t)
            13 -> crushedBreak(v, t)
            14 -> dutySweep(v, t)
            15 -> echoLead(v, t)
            16 -> noiseRoll(v, t)
            17 -> arpRise(v, t)
            else -> error("pad")
        }
        return Breakcore.finish(x, GAINS[pad] * shape(v), x.size.toDouble() / Dsp.RATE)
    }

    /** Fixed per-pad gain, set once from the peak over all three velocities and all three takes: the loudest cell of each pad sits at .85. */
    private val GAINS = doubleArrayOf(0.454, 0.628, 0.446, 0.442, 0.883, 0.887, 0.882, 0.880, 0.460, 0.460, 0.856, 0.886, 0.627, 0.762, 0.644, 0.795, 0.583, 0.878)
}
