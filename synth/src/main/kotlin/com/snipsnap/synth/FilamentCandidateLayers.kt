package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Round filament-voices-2: BRAID, HALO and SPRING each get a layer of their own on top of the
 * strand network. The owner heard round 1 (20 Nov 2026) and asked for all three to be "More
 * distinctive": through the network alone they shared FILAMENT's strand tone and envelope and read
 * as variations of one sound. The network now plays underneath ([bodyMix]) and each layer carries
 * the voice's identity in attack, sustain, spectrum and motion:
 * - BRAID: a string-machine ensemble. Seven players spread up to 22 cents apart, each on its own
 *   slow vibrato, a bright sawtooth with an airy top, a bowed swell, through a slow six-stage phaser.
 * - HALO: a glass harmonica. Nearly a pure sine with a split root (the slow glass "wah"), faint
 *   inharmonic glass modes that bloom in later than the root, and a long ring after the rub.
 * - SPRING: a plucked note into a spring tank. A short tap and the pluck pass through a long chain
 *   of allpasses with a feedback delay, so every arrival chirps and comes back ("boing").
 *   Held, the note is re-plucked six times per loop, a little unevenly.
 * Every layer is a pure function of its arguments (seeded), periodic over the loop for a held key,
 * and bounded by effort. FRAY, THREAD, PULSE and CURL have no layer and render exactly as before.
 */
internal object FilamentCandidateLayers {
    fun layered(voice: FilamentVoice): Boolean =
        voice == FilamentVoice.BRAID || voice == FilamentVoice.HALO || voice == FilamentVoice.SPRING

    /** How much of the strand network plays under the layer. */
    fun bodyMix(voice: FilamentVoice): Double = when (voice) {
        FilamentVoice.SPRING -> .25
        else -> .30
    }

    /** A held key's layer opens over this long from the zero state (the loop itself has no onset). */
    fun gateSeconds(voice: FilamentVoice): Double = when (voice) {
        FilamentVoice.BRAID -> .25
        FilamentVoice.HALO -> .70
        else -> .004
    }

    /** Layer level, set so a full-touch note sits near the kept voices' loudness. */
    private fun gain(voice: FilamentVoice): Double = when (voice) {
        FilamentVoice.BRAID -> .22
        FilamentVoice.HALO -> .0385
        else -> .19
    }

    /**
     * The layer for one note at [rate]. [frames] is the whole capture for a finite note, or one
     * loop period for a held key ([loop]); [sourceSeconds] is how long the player keeps playing.
     */
    fun layer(voice: FilamentVoice, inject: Double, hold: Double, hz: Double, effort: Double, velocity: Double,
              frames: Int, loop: Boolean, rate: Int, heldTilt: Double, seed: Int, sourceSeconds: Double,
              check: () -> Unit): DoubleArray {
        val out = when (voice) {
            FilamentVoice.BRAID -> braid(inject, hold, hz, velocity, frames, loop, rate, heldTilt, seed, sourceSeconds, check)
            FilamentVoice.HALO -> halo(inject, hold, hz, velocity, frames, loop, rate, heldTilt, seed, sourceSeconds, check)
            FilamentVoice.SPRING -> spring(inject, hold, hz, velocity, frames, loop, rate, heldTilt, seed, check)
            else -> error("$voice has no layer")
        }
        val scale = effort * gain(voice)
        // A finite note's layer always closes before the capture ends.
        val fade = if (loop) 0 else min(frames, (.4 * rate).toInt())
        for (i in out.indices) {
            var g = scale
            val left = frames - 1 - i
            if (left < fade) g *= .5 - .5 * cos(PI * left / fade)
            out[i] *= g
        }
        return out
    }

    private fun tiltGain(f: Double, tilt: Double) = (1.0 + (f / Filament.HELD_TILT_CORNER_HZ).pow(2)).pow(-tilt / 2.0)

    /** A frequency moved to a whole number of cycles over a held loop, so the loop closes. */
    private fun onLoop(f: Double, loop: Boolean, seconds: Double) = if (loop) max(1.0, round(f * seconds)) / seconds else f

    /** Partials fade out below a top that rises with the key, so held keys stay under their ceilings. */
    private fun top(f: Double, hz: Double, perRoot: Double, width: Double, highest: Double = 7_000.0): Double {
        val top = (hz * perRoot).coerceIn(1_500.0, highest)
        return ((top - f) / (width * top)).coerceIn(0.0, 1.0).pow(2)
    }

    private fun braid(inject: Double, hold: Double, hz: Double, velocity: Double, frames: Int, loop: Boolean, rate: Int,
                      heldTilt: Double, seed: Int, sourceSeconds: Double, check: () -> Unit): DoubleArray {
        val bright = .12 + .88 * velocity
        val count = 60
        val weights = DoubleArray(count) { k ->
            val n = k + 1.0
            val f = n * hz
            if (f >= 16_000.0) 0.0 else {
                val w = if (k == 0) 1.0 else (.55 + .65 * inject) / n * bright.pow(.5 + .13 * n)
                val body = 1.0 / sqrt(1.0 + (f / max(1_400.0, 6.0 * hz)).pow(4))
                val held = if (heldTilt != 0.0) tiltGain(f, heldTilt) / tiltGain(hz, heldTilt) else 1.0
                w * top(f, hz, 24.0, .5) * body * held
            }
        }
        val norm = weights.sum()
        // One cycle of each player's waveform: the same partials, each player's in its own phases,
        // so the section never lines up into one peaky wave.
        val size = 8192
        val players = 7
        val tables = Array(players) { p ->
            check()
            val table = DoubleArray(size + 1)
            for (j in 0 until size) {
                val phase = 2.0 * PI * j / size
                var v = 0.0
                for (k in 0 until count) if (weights[k] > 1e-7) v += weights[k] * sin((k + 1) * phase + 1.3 * k * p)
                table[j] = v / norm
            }
            table[size] = table[0]
            table
        }
        val cents = doubleArrayOf(0.0, -7.0, 7.0, -14.0, 14.0, -22.0, 22.0)
        val levels = doubleArrayOf(1.0, .85, .85, .72, .72, .60, .60).let { l -> val s = l.sum(); DoubleArray(l.size) { l[it] / s } }
        val seconds = frames.toDouble() / rate
        val random = Random(Dsp.seedFor("filament-braid-section", seed))
        val out = DoubleArray(frames)
        for (p in cents.indices) {
            check()
            val f = onLoop(hz * 2.0.pow(cents[p] / 1200.0), loop, seconds)
            val start = random.nextDouble()
            // Each player's own slow vibrato, about 3 cents at .15 to .4 Hz.
            val vibrato = onLoop(.15 + .25 * random.nextDouble(), loop, seconds)
            val vibratoPhase = random.nextDouble() * 2.0 * PI
            val depth = f * (3.0 / 1731.0) / vibrato
            for (i in 0 until frames) {
                val time = i.toDouble() / rate
                val cycles = if (loop) (f * seconds) * i / frames else f * time
                val position = cycles + start + depth * (sin(2.0 * PI * vibrato * time + vibratoPhase) - sin(vibratoPhase)) / (2.0 * PI)
                val x = (position - floor(position)) * size
                val j = x.toInt().coerceAtMost(size - 1)
                val a = x - j
                out[i] += levels[p] * (tables[p][j] + a * (tables[p][j + 1] - tables[p][j]))
            }
        }
        if (!loop) {
            val attack = (.07 + .10 * (1.0 - inject)) * (1.0 + 2.5 * (1.0 - velocity))
            for (i in 0 until frames) {
                val time = i.toDouble() / rate
                val opening = (1.0 - exp(-time / attack)).pow(2)
                val sustain = exp(-max(0.0, time - .4) / (4.0 + 3.0 * hold))
                val release = (1.0 - (time - sourceSeconds) / .9).coerceIn(0.0, 1.0).pow(2)
                out[i] *= opening * sustain * release
            }
        }
        return phaser(out, rate, loop, check)
    }

    /** BRAID's slow phaser: six first-order allpasses swept between 300 Hz and 2.6 kHz, about .22 Hz. */
    private fun phaser(x: DoubleArray, rate: Int, loop: Boolean, check: () -> Unit): DoubleArray {
        val seconds = x.size.toDouble() / rate
        val sweep = onLoop(.22, loop, seconds)
        val stages = 6
        val mix = .7
        val coefficient = DoubleArray(x.size) { i ->
            val fc = 300.0 * (2_600.0 / 300.0).pow(.5 - .5 * cos(2.0 * PI * sweep * i / rate))
            val t = tan(PI * fc / rate)
            (t - 1.0) / (t + 1.0)
        }
        val state = DoubleArray(stages)
        val out = DoubleArray(x.size)
        // A held loop runs round twice first so its last pass is the settled, periodic one.
        val passes = if (loop) 3 else 1
        for (pass in 0 until passes) {
            check()
            for (i in x.indices) {
                val a = coefficient[i]
                var v = x[i]
                for (k in 0 until stages) {
                    val o = a * v + state[k]
                    state[k] = Filament.floored(v - a * o)
                    v = o
                }
                if (pass == passes - 1) out[i] = (x[i] + mix * v) / (1.0 + mix)
            }
        }
        return out
    }

    private fun halo(inject: Double, hold: Double, hz: Double, velocity: Double, frames: Int, loop: Boolean, rate: Int,
                     heldTilt: Double, seed: Int, sourceSeconds: Double, check: () -> Unit): DoubleArray {
        val bright = .12 + .88 * velocity
        // (ratio, beat in Hz, level): a split root, a faint octave, then a wine glass's inharmonic modes.
        val parts = listOf(
            Triple(1.0, 0.0, .80), Triple(1.0, .55, .30),
            Triple(2.0, 0.0, .10 * bright.pow(1.5)), Triple(2.0, .9, .05 * bright.pow(1.5)),
            Triple(2.32, 0.0, .07 * bright.pow(1.8)), Triple(2.32, 1.3, .05 * bright.pow(1.8)),
            Triple(4.25, 0.0, .045 * bright.pow(2.4)), Triple(4.25, 1.7, .03 * bright.pow(2.4)),
            Triple(6.63, 0.0, .025 * bright.pow(3.0)), Triple(9.38, 0.0, .012 * bright.pow(3.6)),
        )
        val seconds = frames.toDouble() / rate
        val random = Random(Dsp.seedFor("filament-halo-glass", seed))
        val out = DoubleArray(frames)
        for ((ratio, beat, level) in parts) {
            val start = random.nextDouble() * 2.0 * PI
            val nominal = hz * ratio + beat
            if (nominal >= 16_000.0 || nominal > max(1_900.0, 14.0 * hz)) continue
            check()
            val f = onLoop(nominal, loop, seconds)
            val held = if (heldTilt != 0.0) tiltGain(f, heldTilt) / tiltGain(hz, heldTilt) else 1.0
            val late = ratio > 1.5
            val bloom = (.35 + .45 * (1.0 - inject)) * (1.0 + 1.2 * (1.0 - velocity)) * if (late) 1.8 else 1.0
            val ring = (1.1 + .9 * hold) / (1.0 + .25 * (ratio - 1.0))
            for (i in 0 until frames) {
                val time = i.toDouble() / rate
                val phase = if (loop) 2.0 * PI * (f * seconds) * i / frames else 2.0 * PI * f * time
                var envelope = 1.0
                if (!loop) {
                    envelope = sin(min(1.0, time / bloom) * PI * .5).pow(2) * exp(-time / (6.0 + 4.0 * hold))
                    if (time > sourceSeconds) envelope *= exp(-(time - sourceSeconds) / ring)
                }
                out[i] += level * held * envelope * sin(phase + start)
            }
        }
        return out
    }

    private fun spring(inject: Double, hold: Double, hz: Double, velocity: Double, frames: Int, loop: Boolean, rate: Int,
                       heldTilt: Double, seed: Int, check: () -> Unit): DoubleArray {
        val bright = .12 + .88 * velocity
        // Above E5 the tank, the tap and the top thin out by C6, so the top keys keep a clear root under
        // the held ceiling; at and below E5 every factor is exactly 1.
        val above = (12.0 * kotlin.math.log2(max(1.0, hz / 659.2551)) / 8.0).coerceIn(0.0, 1.0)
        val count = 40
        val weights = DoubleArray(count) { k ->
            val n = k + 1.0
            val f = n * hz
            if (f >= 16_000.0) 0.0 else (if (k == 0) 1.0 else (.55 + .65 * inject) / n.pow(.9) * bright.pow(.3 + .1 * n)) *
                top(f, hz, 14.0, .3, 7_000.0 - 3_500.0 * above)
        }
        val norm = weights.sum()
        val seconds = frames.toDouble() / rate
        val random = Random(Dsp.seedFor("filament-spring-tank", seed))
        // The plucks: one for a finite note, six a loop (a little uneven) for a held key.
        val plucks = if (loop) 6 else 1
        val at = IntArray(plucks) { j -> if (loop) ((j + .08 * random.nextDouble()) * frames / plucks).toInt() else 0 }
        val strength = DoubleArray(plucks) { if (loop) .75 + .25 * random.nextDouble() else 1.0 }
        // A raised-cosine push (the finger) of width twice the attack, summing to one.
        val attack = .0015 * (1.0 + 3.0 * (1.0 - velocity))
        val width = max(2, (2.0 * attack * rate).toInt())
        val bump = DoubleArray(width) { .5 - .5 * cos(2.0 * PI * (it + .5) / width) }
        val bumpSum = bump.sum()
        for (j in bump.indices) bump[j] /= bumpSum
        val push = DoubleArray(frames)
        for (p in 0 until plucks) for (j in 0 until width) push[(at[p] + j) % frames] += strength[p] * bump[j]
        // The tap: a short seeded scrape that sets the spring ringing, darker on low keys.
        val tapFrames = (.004 * rate).toInt()
        val noise = DoubleArray(tapFrames) { (random.nextDouble() * 2.0 - 1.0) * sin(PI * it / tapFrames).pow(2) }
        val tapCorner = 1.0 - exp(-2.0 * PI * (10.0 * hz).coerceIn(400.0, 3_000.0) / rate)
        var l1 = 0.0; var l2 = 0.0; var l3 = 0.0
        val shapedTap = DoubleArray(tapFrames + (.004 * rate).toInt()) { i ->
            val v = if (i < tapFrames) noise[i] else 0.0
            l1 += tapCorner * (v - l1); l2 += tapCorner * (l1 - l2); l3 += tapCorner * (l2 - l3); l3
        }
        val tapPeak = shapedTap.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-12)
        val tapLevel = (.1 + .4 * velocity) * .5 * (1.0 - .7 * above) / tapPeak
        val excitation = DoubleArray(frames)
        for (p in 0 until plucks) for (j in shapedTap.indices) {
            val i = at[p] + j
            if (loop || i < frames) excitation[i % frames] += strength[p] * tapLevel * shapedTap[j]
        }
        // Each partial rings down on its own; the push is its envelope's onset. A held key runs the
        // decays round four times so the last pass is the periodic one.
        val passes = if (loop) 4 else 1
        for (k in 0 until count) {
            if (weights[k] <= 1e-7) continue
            check()
            val n = k + 1.0
            val tau = (.35 + .5 * hold) / (1.0 + .18 * (n - 1.0))
            val r = exp(-1.0 / (tau * rate))
            val w = weights[k] / norm
            var e = 0.0
            for (pass in 0 until passes) for (i in 0 until frames) {
                e = e * r + push[i]
                if (pass == passes - 1) {
                    val phase = if (loop) 2.0 * PI * (n * hz * seconds) * i / frames else 2.0 * PI * n * hz * i / rate
                    excitation[i] += w * e * sin(phase)
                }
            }
        }
        // Held keys thin their top like the kept voices (one pole at the tilt corner, root level kept).
        if (heldTilt != 0.0) {
            val c = 1.0 - exp(-2.0 * PI * Filament.HELD_TILT_CORNER_HZ / rate)
            val keep = sqrt(1.0 + (hz / Filament.HELD_TILT_CORNER_HZ).pow(2))
            var s = 0.0
            for (pass in 0 until (if (loop) 2 else 1)) for (i in 0 until frames) {
                s += c * (excitation[i] - s)
                if (pass == (if (loop) 1 else 0)) excitation[i] = s * keep
            }
        }
        val tank = Tank(rate)
        val out = DoubleArray(frames)
        val corner = 1.0 - exp(-2.0 * PI * (12.0 * hz).coerceIn(900.0, 2_200.0 - 600.0 * above) / rate)
        val wet = .8 * (1.0 - .65 * above)
        var o1 = 0.0; var o2 = 0.0
        for (pass in 0 until passes) {
            check()
            for (i in 0 until frames) {
                val x = excitation[i]
                val mixed = .55 * x + wet * tank.next(x)
                o1 += corner * (mixed - o1); o2 += corner * (o1 - o2)
                if (pass == passes - 1) out[i] = o2
            }
        }
        return out
    }

    /**
     * A spring tank: 120 stretched allpasses (a + z^-K) / (1 + a z^-K) inside a 47 ms feedback loop,
     * the parametric spring of Välimäki, Parker and Abel (2010) with K set so each stage is one
     * sample at 44.1 kHz whatever the rate. Higher partials are held back longer than low ones, so a
     * click comes out as a chirp, and each return through the loop chirps longer: the spring's "boing".
     */
    private class Tank(rate: Int) {
        private val stages = 120
        private val stretch = max(1, rate / Dsp.RATE)
        private val a = .8
        private val memory = Array(stages) { DoubleArray(stretch) }
        private var tap = 0
        private val ring = DoubleArray((.047 * rate).toInt())
        private var position = 0
        private val feedback = .62

        fun next(input: Double): Double {
            var v = input + feedback * ring[position]
            for (k in 0 until stages) {
                // w[n] = x[n] - a w[n-K]; y[n] = a w[n] + w[n-K]
                val old = memory[k][tap]
                val w = Filament.floored(v - a * old)
                memory[k][tap] = w
                v = a * w + old
            }
            tap = (tap + 1) % stretch
            ring[position] = v
            position = (position + 1) % ring.size
            return v
        }
    }
}
