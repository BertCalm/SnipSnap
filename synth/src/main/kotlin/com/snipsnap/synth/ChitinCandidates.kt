package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

/**
 * The CHITIN voices built for chitin-voices-1: HUSK, STING, HUM and PINCER were kept (20 Nov 2026);
 * GLINT is still a candidate. SHELL and WING both *mix* the synced pair
 * and add the phaser's correction to it. Each candidate reads a part of that same model the kept
 * voices leave unused:
 *
 * - HUSK keeps only what the phaser changes (its correction path) over the bare root sine:
 *   the body is gone and the moving bands are the instrument.
 * - STING multiplies the synced pair instead of mixing it, and is heard through the filter's
 *   band tap as well as its low tap, which the kept voices never use.
 * - GLINT lets the slave bend the phase of the root sine, and is struck, not held. At the strike the
 *   slave is synced, a harmonic ping; released from sync it runs on at GLASS times the root and keeps
 *   bending the root, so inharmonic partials near a wine glass's ring modes ring with the note.
 * - HUM moves the master pulse's width with the shared excursion; the slave's ratio stays put.
 * - PINCER is the spare: SHELL's pulse-and-slave source, slave leading, with the excursion run
 *   backwards and a plucked contour.
 *
 * The held part of a note carries a slow seeded drift on the excursion (two rates that never line
 * up), so a long note is never a fixed loop of the phaser. Renders are still deterministic.
 */
internal object ChitinCandidates {
    /**
     * GLINT's free slave, in multiples of the root. Bending the root's phase by a sine at this rate puts
     * the first sidebands at 2.6 and 4.6 times the root and the second at 6.2 and 8.2: close to a wine
     * glass's ring modes (about 1, 2.4 to 2.6, 4.2 to 4.6 and 6.6 to 7), and none of them harmonic.
     */
    internal const val GLASS = 3.6
    /** The slave's second copy runs this many hertz faster: a glass's split mode pair, a slow shimmer. */
    internal const val GLASS_BEAT_HZ = 1.3
    /** TUNE, BITE, COLOR, PHASE, MORPH, PACE, HOLD. */
    fun defaults(voice: ChitinVoice): List<Float> = when (voice) {
        ChitinVoice.HUSK -> listOf(.5f, .55f, .50f, .75f, .65f, .35f, .35f)
        ChitinVoice.STING -> listOf(.5f, .50f, .55f, .45f, .70f, .50f, .25f)
        ChitinVoice.GLINT -> listOf(.5f, .45f, .60f, .35f, .55f, .55f, .50f)
        ChitinVoice.HUM -> listOf(.5f, .35f, .50f, .50f, .70f, .30f, .35f)
        ChitinVoice.PINCER -> listOf(.5f, .55f, .32f, .40f, .75f, .55f, .20f)
        else -> error("$voice mixes the synced pair")
    }

    /** Two octaves upward from here, as for the kept voices. */
    fun lowestMidi(voice: ChitinVoice): Int = when (voice) {
        ChitinVoice.HUSK, ChitinVoice.HUM -> 36
        ChitinVoice.STING, ChitinVoice.GLINT -> 48
        ChitinVoice.PINCER -> 24
        else -> error("$voice mixes the synced pair")
    }

    /** The synced pair and the root sine, kept apart. [width] is the master pulse's duty cycle. */
    internal fun split(frames: Int, rate: Int, root: Double, pulse: Boolean, ratio: (Double) -> Double,
        width: (Double) -> Double = { .5 }): Array<DoubleArray> {
        val half = ChitinSource.HALF
        val n = frames + 2 * half
        val master = DoubleArray(n); val slave = DoubleArray(n); val support = DoubleArray(n)
        val da = root / rate
        var pa = ((-half * da) % 1 + 1) % 1
        var pb = (ratio(-half.toDouble() / rate) * pa) % 1
        fun jump(buffer: DoubleArray, event: Double, delta: Double) {
            if (abs(delta) < 1e-14) return
            for (j in max(0, ceil(event - half).toInt())..min(n - 1, floor(event + half).toInt()))
                buffer[j] += delta * ChitinSource.residual(j - event)
        }
        for (i in 0 until n) {
            if (i % 2048 == 0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            val w = if (pulse) width((i - half).toDouble() / rate).coerceIn(.08, .92) else .5
            master[i] += if (pulse) { if (pa < w) 1.0 else -1.0 } else 2 * pa - 1
            slave[i] += 2 * pb - 1; support[i] = sin(2 * PI * pa)
            val db = da * ratio((i - half + .5) / rate)
            var remaining = 1.0; var elapsed = 0.0
            while (remaining > 1e-12) {
                val ma = (1 - pa) / da
                val sb = (1 - pb) / db
                val edge = if (pulse && pa < w - 1e-12) (w - pa) / da else Double.POSITIVE_INFINITY
                val dt = minOf(remaining, ma, sb, edge)
                pa += da * dt; pb += db * dt; elapsed += dt; remaining -= dt
                val sync = abs(dt - ma) < 1e-10
                val wrap = abs(dt - sb) < 1e-10
                if (sync) {
                    jump(master, i + elapsed, if (pulse) 2.0 else -2.0)
                    jump(slave, i + elapsed, -2 * pb)
                    pa = 0.0; pb = 0.0
                } else {
                    if (wrap) { jump(slave, i + elapsed, -2.0); pb = 0.0 }
                    if (abs(dt - edge) < 1e-10) jump(master, i + elapsed, -2.0)
                }
                if (dt < 1e-14 && !sync && !wrap) break
            }
        }
        return arrayOf(master.copyOfRange(half, half + frames), slave.copyOfRange(half, half + frames),
            support.copyOfRange(half, half + frames))
    }

    fun inspect(p: Chitin.Plan, started: Long): ChitinRender {
        val voice = p.voice
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val n = p.frames * 4
        val v = p.velocity
        val bite = p.m.getValue("BITE").toDouble()
        val color = p.m.getValue("COLOR").toDouble()
        val gate = p.gate.toDouble() / Dsp.RATE
        val release = p.release.toDouble() / Dsp.RATE
        val struck = voice == ChitinVoice.GLINT || voice == ChitinVoice.PINCER
        val random = Random(Dsp.seedFor("CHITIN_DRIFT", voice.name, Chitin.midiFor(voice, p.m.getValue("TUNE"))))
        val phaseA = random.nextDouble(); val phaseB = random.nextDouble()
        val amount = p.m.getValue("MORPH") * (.7 + .3 * v)
        /** The shared excursion as this voice plays it. */
        fun sweep(t: Double): Double {
            if (voice == ChitinVoice.PINCER) {
                // Backwards: wide open at the pluck, closing at PACE; the release closes what is left.
                val closing = if (t < gate) 1.0 else 1 - Chitin.smooth((t - gate) / release)
                return amount * exp(-max(0.0, t) / (.55 * p.rise + .04)) * closing
            }
            val drift = .6 * sin(2 * PI * (.17 * t + phaseA)) + .4 * sin(2 * PI * (.41 * t + phaseB))
            return (p.morph(t) * (1 + .16 * drift * Chitin.smooth((t - .3) / 1.2))).coerceIn(0.0, 1.0)
        }
        val wingLike = voice == ChitinVoice.STING || voice == ChitinVoice.GLINT || voice == ChitinVoice.HUSK
        val source = DoubleArray(n)
        val sine: DoubleArray
        when (voice) {
            ChitinVoice.HUSK -> {
                val parts = split(n, rate, p.root, false, { t -> 1 + bite + bite * sweep(t) * 4 })
                for (i in 0 until n) source[i] = .35 * parts[0][i] + .60 * parts[1][i] + .05 * parts[2][i]
                sine = parts[2]
            }
            ChitinVoice.STING -> {
                val parts = split(n, rate, p.root, false, { t -> 1 + bite + bite * sweep(t) * 3 })
                // The product carries a standing offset; follow it from its first cycle so the note
                // does not open with a thump.
                val cycle = min(n, (rate / p.root).roundToInt())
                var mean = (0 until cycle).sumOf { parts[0][it] * parts[1][it] } / cycle
                val a = 1 - exp(-2 * PI * (.3 * p.root) / rate)
                for (i in 0 until n) {
                    val product = parts[0][i] * parts[1][i]
                    mean += a * (product - mean)
                    source[i] = 1.25 * (product - mean) + .30 * parts[2][i]
                }
                sine = parts[2]
            }
            ChitinVoice.GLINT -> {
                sine = DoubleArray(n)
                var pa = 0.0
                // The released slave and its mode-pair twin. Both start at zero phase, so the note still
                // opens on the root sine's zero crossing.
                var pg = 0.0; var ph = 0.0
                val dg = GLASS * p.root / rate; val dh = (GLASS * p.root + GLASS_BEAT_HZ) / rate
                for (i in 0 until n) {
                    val t = i.toDouble() / rate
                    val m = sweep(t)
                    val ratio = 1 + 2 * bite + 4 * bite * m
                    // The synced strike drives the bend hard and lets go; the excursion swells it a little.
                    val index = (.8 + 3.2 * bite) * ((.30 + .70 * v) * exp(-t / .30) + .06 + .30 * m)
                    val window = .5 - .5 * cos(2 * PI * pa)
                    // The glass: the released slave keeps bending the root for the whole note. A harder
                    // touch bends it further and adds a crisp 35 ms flash of upper partials at the hit.
                    val glass = (.55 + .9 * bite) * (.6 + .4 * v) * (1 + 1.4 * v * exp(-t / .035))
                    val ring = .65 * sin(2 * PI * pg) + .35 * sin(2 * PI * ph)
                    sine[i] = sin(2 * PI * pa)
                    source[i] = .8 * sin(2 * PI * pa + index * window * sin(2 * PI * ratio * pa) + glass * ring)
                    pa += p.root / rate
                    if (pa >= 1) pa -= 1.0
                    pg += dg; if (pg >= 1) pg -= 1.0
                    ph += dh; if (ph >= 1) ph -= 1.0
                }
            }
            ChitinVoice.HUM -> {
                val parts = split(n, rate, p.root, true, { 1 + .5 * bite },
                    { t -> .5 - .40 * sweep(t) })
                // A narrow pulse stands off centre. Take that standing offset out here, where it is known
                // exactly, or the note would open with a step and carry a thump.
                for (i in 0 until n) {
                    val offset = 2 * (.5 - .40 * sweep(i.toDouble() / rate)).coerceIn(.08, .92) - 1
                    source[i] = .68 * (parts[0][i] - offset) + (.10 + .30 * bite) * parts[1][i] + .08 * parts[2][i]
                }
                sine = parts[2]
            }
            else -> {
                // PINCER: the slave leads and reaches further, so the pluck is mostly sync.
                val parts = split(n, rate, p.root, true, { t -> 1 + bite + bite * sweep(t) * 4 })
                for (i in 0 until n) source[i] = .35 * parts[0][i] + .57 * parts[1][i] + .08 * parts[2][i]
                sine = parts[2]
            }
        }
        val filter = Dsp.TptSvf(rate)
        val phaser = ChitinPhaser(rate, p.root, p.m.getValue("PHASE").toDouble(), wingLike, true)
        val raw = FloatArray(n); val before = FloatArray(n)
        var filterPeak = 0.0
        val glint = voice == ChitinVoice.GLINT
        // GLINT rings longer: a glass keeps sounding well after the hit.
        val ringTime = gate * (if (glint) .70 else .42)
        for (i in 0 until n) {
            if (i % 2048 == 0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            val t = i.toDouble() / rate
            val m = sweep(t)
            // Touch moves the filter's resting place, so soft and hard differ in tone at any level.
            // STING's band sits lower, as a formant a few harmonics up, and the excursion carries it.
            // GLINT's rests higher, so its glass partials (up to eight times the root) pass at any COLOR.
            val reach = if (voice == ChitinVoice.STING) 1.2 + 8 * color else if (glint) 6 + 20 * color else 2 + 18 * color
            val cutoff = (p.root * reach * 2.0.pow(1.4 * m + .6 * exp(-t / .045) * (.25 + .75 * v) - .9 * (1 - v)))
                .coerceIn(60.0, 10_000.0)
            filter.process(source[i].toFloat(), cutoff.toFloat(), if (wingLike) 1.05f else 1.35f)
            filterPeak = max(filterPeak, abs(filter.low.toDouble()))
            // GLINT's onset is crisper: 3 to 5 ms, against 5 to 12 ms.
            val opened = Chitin.smooth(t / (if (glint) .005 - .002 * v else .012 - .007 * v))
            val closed = if (t < gate) 1.0 else 1 - Chitin.smooth((t - gate) / release)
            val amp = opened * closed * (if (struck) exp(-t / ringTime) else 1.0)
            val tap = if (voice == ChitinVoice.STING) .30 * filter.low + 1.0 * filter.band else filter.low.toDouble()
            val z = .65 * tap * amp
            // GLINT is driven gently: the drive would mix its glass partials into a grey wash.
            val drive = if (glint) 1 + .5 * bite + .5 * m else 1 + 2.5 * bite + 1.5 * m
            val onset = if (struck) 1.0 else 1 + .75 * exp(-t / .08)
            val x = if (v == 0.0) 0.0 else onset * tanh(drive * z) / tanh(drive)
            before[i] = x.toFloat()
            val nextTime = (i / 64 * 64 + 64).toDouble() / rate
            val y = phaser.step(x, m, sweep(nextTime))
            raw[i] = (if (voice == ChitinVoice.HUSK) {
                // Only the correction, over the bare root: the dry body never reaches the output.
                if (v == 0.0) 0.0 else .50 * sine[i] * amp * (1 + .35 * exp(-t / .08)) + (1.5 + 1.5 * v) * (y - x)
            } else y).toFloat()
            check(raw[i].isFinite()) { "CHITIN nonfinite output at $i" }
        }
        val rawPeak = raw.maxOf { abs(it.toDouble()) }; val rawDc = raw.sumOf { it.toDouble() } / raw.size
        val conditioned = raw.copyOf()
        Tide.bandLimit(conditioned, rate)
        val out = Dsp.decimate(conditioned, Dsp.RATE)
        // The kept voices also subtract the whole note's mean. A narrow pulse or a product is lopsided,
        // the drive turns that into a standing offset, and subtracting it would open the note with a
        // step. The 20 Hz high-pass alone removes the offset as it grows with the note.
        val hp = Dsp.OnePole(Dsp.RATE)
        for (i in out.indices) { val x = out[i]; out[i] = x - hp.lp(x, 20f) }
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET); Dsp.fadeTail(out)
        return ChitinRender(Snip(out, 1, Dsp.RATE), raw, before, ChitinDiagnostics(rawPeak, rawDc, filterPeak,
            phaser.energyExcess, phaser.storage, (System.nanoTime() - started) / 1e6))
    }
}
