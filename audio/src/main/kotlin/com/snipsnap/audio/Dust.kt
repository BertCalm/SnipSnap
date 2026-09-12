package com.snipsnap.audio

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * DUST — the tape's own dirt, put back under the hits cut from it.
 *
 * CRUSH, TAPE and DIRT are synthetic: the same grit on every kit. A
 * chop throws away everything between the hits, and that material is
 * the recording itself: the room answering each hit, and the floor the
 * tape sits on. DUST reads both back out of the ghosts and lays them
 * under every pad, so a kit sounds like one recording again rather than
 * sixteen clean slices. `docs/DUST.md`.
 *
 * Two ingredients in a [Print], made once per tape by [print]:
 *
 * - **ROOM** — the tails. After each hit the envelope falls; from where
 *   it has dropped [TAIL_DROP_DB] below its peak until the next hit is
 *   the room answering. Each tail's *noise* part (the STN split, so the
 *   bass note still ringing and the hat still decaying do not come
 *   along — that is the difference between dusty and muddy) is levelled
 *   and averaged into one short impulse, [ROOM_MAX_SEC] at most.
 * - **HISS** — the floor. The quietest [HISS_SEC] window of the tape,
 *   made into a seamless loop, levelled to unit RMS so [apply] sets how
 *   loud it sits.
 *
 * [apply] convolves the hit with ROOM (the FFT the classifier already
 * carries) and adds a bed of HISS, louder under a quiet hit the way real
 * tape reveals its floor when the music drops. Pure arithmetic: the same
 * hit, print and amount produce the same bytes.
 */
object Dust {

    /** The impulse is at most this long: a room, not a hall. */
    const val ROOM_MAX_SEC = 0.35f

    /** A tail shorter than this is a scrap between two close hits, not a room. */
    const val TAIL_MIN_SEC = 0.06f

    /** A tail starts where the hit's envelope has fallen this far below its peak. */
    const val TAIL_DROP_DB = -18f

    /**
     * A tail counts for as long as it is still above this, below the
     * hit's peak. A room rings on past it for [TAIL_MIN_SEC] and more; a
     * gated hit's own last decay is under it in a few tens of
     * milliseconds, and the STN split cannot tell a short low burst from
     * noise on its own — so the length of the ringing decides.
     */
    const val TAIL_FLOOR_DB = -40f

    /** Enough tails to average; caps the work on a long tape. */
    const val MAX_TAILS = 24

    /** The floor loop's length. */
    const val HISS_SEC = 0.25f

    /** The loop's seam crossfade. */
    const val HISS_XFADE_SEC = 0.02f

    /** At amount 1, the room's level: the tail can reach this share of the hit's own level, never more. */
    const val ROOM_GAIN = 0.25f

    /**
     * The room answers this long after the hit. A real room's first
     * reflections arrive after the direct sound, and an impulse that
     * starts on the attack itself thickens the attack — the difference
     * between a kick in a room and a kick under a blanket.
     */
    const val ROOM_PREDELAY_SEC = 0.008f

    /**
     * A tail whose noise part carries less than this share of its energy
     * is a note still ringing (a decaying tone splits almost wholly into
     * sines), not a room answering: skipped, so a kit of held notes does
     * not read as having rooms it never had.
     */
    const val NOISE_FRACTION_MIN = 0.25f

    /** At amount 1, the hiss bed under a full-scale hit, dB below the hit's peak. */
    const val HISS_DB_AT_FULL = -30f

    /** Extra hiss under a quiet hit, up to this many dB at silence. */
    const val HISS_QUIET_LIFT_DB = 8f

    /** The bed and the tail fade out over the output's last stretch, so a pad never ends on a click of hiss. */
    const val FADE_OUT_SEC = 0.03f

    /** A window quieter than this is digital silence, not a floor. */
    private const val SILENCE_RMS = 1e-6f

    /**
     * A tape's dust: both parts mono at [sampleRate], HISS at unit RMS,
     * ROOM with its absolute values summing to one — so a hit convolved
     * with it can never come out louder than the hit went in, whatever
     * the hit, and [ROOM_GAIN] means what it says.
     */
    data class Print(val hiss: Snip, val room: Snip) {
        val sampleRate: Int get() = room.sampleRate

        init {
            require(hiss.channels == 1 && room.channels == 1) { "a print is mono" }
            require(hiss.sampleRate == room.sampleRate) { "hiss and room share a rate" }
            require(room.frameCount > 0) { "a print has a room" }
        }
    }

    /**
     * The dust of [tape], or null when there is nothing to take: no hits
     * at all, or every tail too short to be a room (a tight, gated
     * break). A tape whose floor is digital silence still yields a print
     * whose HISS is silent — the room alone is worth having.
     */
    fun print(tape: Snip, config: Transients.Config = Transients.Config()): Print? {
        if (tape.frameCount == 0) return null
        val mono = if (tape.channels == 1) tape else Cleanup.toMono(tape)
        val rate = mono.sampleRate
        val onsets = Transients.detect(mono, config)
        if (onsets.isEmpty()) return null

        val roomFrames = (ROOM_MAX_SEC * rate).toInt()
        val minTail = max((TAIL_MIN_SEC * rate).toInt(), Spectral.FRAME * 2)
        val drop = 10f.pow(TAIL_DROP_DB / 20f)
        val peakWindow = (0.05f * rate).toInt()
        val rmsWindow = (0.005f * rate).toInt().coerceAtLeast(1)

        val room = FloatArray(roomFrames)
        var tails = 0
        for ((i, onset) in onsets.withIndex()) {
            if (tails >= MAX_TAILS) break
            val start = onset.frame
            val next = if (i + 1 < onsets.size) onsets[i + 1].frame else mono.frameCount
            // The hit's own peak, in the first 50 ms.
            var peak = 0f
            for (f in start until min(next, start + peakWindow)) peak = max(peak, kotlin.math.abs(mono.samples[f]))
            if (peak <= SILENCE_RMS) continue
            // The tail starts where a 5 ms RMS has fallen TAIL_DROP_DB below that peak.
            var tailStart = -1
            var f = start + peakWindow
            while (f + rmsWindow <= next) {
                if (rms(mono.samples, f, f + rmsWindow) < peak * drop) {
                    tailStart = f
                    break
                }
                f += rmsWindow
            }
            if (tailStart < 0) continue
            val tailEnd = min(next, tailStart + roomFrames)
            if (tailEnd - tailStart < minTail) continue
            // How long the tail rings before it is under the hit's own floor.
            val floor = peak * 10f.pow(TAIL_FLOOR_DB / 20f)
            var activeEnd = tailStart
            var g = tailStart
            while (g + rmsWindow <= tailEnd) {
                if (rms(mono.samples, g, g + rmsWindow) >= floor) activeEnd = g + rmsWindow
                g += rmsWindow
            }
            if (activeEnd - tailStart < minTail) continue

            val tail = Snip(mono.samples.copyOfRange(tailStart, tailEnd), 1, rate)
            // The noise part only: what the room is, without what the drums were.
            val noise = Separate.stn(tail).noise
            val tailEnergy = energy(tail.samples)
            if (tailEnergy <= SILENCE_RMS || energy(noise.samples) / tailEnergy < NOISE_FRACTION_MIN) continue
            // Levelled by its opening 20 ms, so a loud hit's room and a quiet hit's room count the same.
            val head = rms(noise.samples, 0, min(noise.frameCount, (0.02f * rate).toInt().coerceAtLeast(1)))
            if (head <= SILENCE_RMS) continue
            for (k in 0 until min(noise.frameCount, roomFrames)) room[k] += noise.samples[k] / head
            tails++
        }
        if (tails == 0) return null
        // Average, fade the last fifth out, then absolute values summing to one.
        for (k in room.indices) room[k] /= tails
        val fadeFrom = (roomFrames * 0.8f).toInt()
        for (k in fadeFrom until roomFrames) {
            val t = (k - fadeFrom).toFloat() / (roomFrames - fadeFrom)
            room[k] *= (0.5f * (1f + cos(Math.PI * t))).toFloat()
        }
        val l1 = room.fold(0.0) { acc, v -> acc + kotlin.math.abs(v) }.toFloat()
        if (l1 <= SILENCE_RMS) return null
        for (k in room.indices) room[k] /= l1

        return Print(hiss = hissLoop(mono), room = Snip(room, 1, rate))
    }

    /**
     * The quietest [HISS_SEC] window of [mono] that is not digital silence,
     * as a seamless loop at unit RMS — or a silent loop when the whole tape
     * is silent between the hits (a print still has a room to give).
     */
    private fun hissLoop(mono: Snip): Snip {
        val rate = mono.sampleRate
        val win = (HISS_SEC * rate).toInt().coerceAtLeast(2)
        val xfade = (HISS_XFADE_SEC * rate).toInt().coerceIn(1, win / 4)
        var bestAt = -1
        var best = Float.MAX_VALUE
        var at = 0
        while (at + win <= mono.frameCount) {
            val r = rms(mono.samples, at, at + win)
            if (r < best) {
                best = r
                bestAt = at
            }
            at += win / 2
        }
        val loopLen = win - xfade
        // The quietest window IS the floor; a floor that is digital silence is a silent loop, not the next-quietest window with a hit's edge in it.
        if (bestAt < 0 || best <= SILENCE_RMS) return Snip(FloatArray(loopLen.coerceAtLeast(1)), 1, rate)
        // The loop's head is the window's tail fading out under the
        // window's head fading in (equal power), so the wrap from the loop's
        // last frame runs straight on into material that continues it: a
        // blend at the seam, not a cut.
        val out = FloatArray(loopLen)
        for (k in 0 until loopLen) {
            out[k] = if (k < xfade) {
                val t = k.toFloat() / xfade
                mono.samples[bestAt + loopLen + k] * cos(t * Math.PI / 2).toFloat() +
                    mono.samples[bestAt + k] * kotlin.math.sin(t * Math.PI / 2).toFloat()
            } else {
                mono.samples[bestAt + k]
            }
        }
        val r = rms(out, 0, out.size)
        if (r > SILENCE_RMS) for (k in out.indices) out[k] /= r
        return Snip(out, 1, rate)
    }

    /**
     * [hit] with [print]'s dust under it at [amount] (0..1): the hit plus
     * its room tail (after [ROOM_PREDELAY_SEC]) plus a hiss bed, the whole
     * thing the pre-delay and [print]'s room length longer than the hit.
     * Amount 0 is [hit] itself, untouched. Channels are dusted alike; a
     * print at another rate is resampled first.
     */
    fun apply(hit: Snip, print: Print, amount: Float): Snip {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        if (amount <= 0f || hit.frameCount == 0) return hit
        val p = if (print.sampleRate == hit.sampleRate) {
            print
        } else {
            Print(Resampler.resample(print.hiss, hit.sampleRate), Resampler.resample(print.room, hit.sampleRate))
        }
        val rate = hit.sampleRate
        val predelay = (ROOM_PREDELAY_SEC * rate).toInt()
        val n = hit.frameCount + predelay + p.room.frameCount - 1
        val fadeFrames = min((FADE_OUT_SEC * rate).toInt(), n / 4).coerceAtLeast(1)
        val roomGain = ROOM_GAIN * amount

        // Hiss sits HISS_DB_AT_FULL under the hit's peak at full amount, lifted for a quiet hit.
        var hitPeak = 0f
        for (v in hit.samples) hitPeak = max(hitPeak, kotlin.math.abs(v))
        val lift = HISS_QUIET_LIFT_DB * (1f - hitPeak.coerceIn(0f, 1f))
        val hissGain = if (hitPeak <= 0f) 0f else hitPeak * 10f.pow((HISS_DB_AT_FULL + lift) / 20f) * amount

        val out = FloatArray(n * hit.channels)
        // What DUST adds, kept apart from the hit so the clip guard below can
        // scale the dust and never the hit.
        val added = FloatArray(n * hit.channels)
        val roomSpectrumRe: FloatArray
        val roomSpectrumIm: FloatArray
        val fftSize = nextPowerOfTwo(n)
        run {
            roomSpectrumRe = FloatArray(fftSize)
            roomSpectrumIm = FloatArray(fftSize)
            System.arraycopy(p.room.samples, 0, roomSpectrumRe, predelay, p.room.frameCount)
            Fft.forward(roomSpectrumRe, roomSpectrumIm)
        }
        for (ch in 0 until hit.channels) {
            val re = FloatArray(fftSize)
            val im = FloatArray(fftSize)
            for (f in 0 until hit.frameCount) re[f] = hit.samples[f * hit.channels + ch]
            Fft.forward(re, im)
            for (k in 0 until fftSize) {
                val a = re[k]
                val b = im[k]
                re[k] = a * roomSpectrumRe[k] - b * roomSpectrumIm[k]
                im[k] = a * roomSpectrumIm[k] + b * roomSpectrumRe[k]
            }
            Fft.inverse(re, im)
            val loop = p.hiss.samples
            for (f in 0 until n) {
                val dry = if (f < hit.frameCount) hit.samples[f * hit.channels + ch] else 0f
                val bed = if (loop.isNotEmpty()) loop[f % loop.size] * hissGain else 0f
                var dust = re[f] * roomGain + bed
                // The bed and the tail fade out over the last stretch; the dry hit is already over by then.
                if (f >= n - fadeFrames) {
                    val t = (f - (n - fadeFrames)).toFloat() / fadeFrames
                    dust *= (0.5f * (1f + cos(Math.PI * t))).toFloat()
                }
                out[f * hit.channels + ch] = dry
                added[f * hit.channels + ch] = dust
            }
        }
        // Never clip, and never touch the hit: when hit plus dust would crest,
        // the dust alone is scaled down — the largest share of it that fits,
        // found by halving.
        var s = 1f
        if (crests(out, added, 1f)) {
            var lo = 0f
            var hi = 1f
            repeat(12) {
                val mid = (lo + hi) / 2f
                if (crests(out, added, mid)) hi = mid else lo = mid
            }
            s = lo
        }
        for (i in out.indices) out[i] += added[i] * s
        return Snip(out, hit.channels, rate)
    }

    /** Whether [dry] plus [added] at [scale] would exceed full scale anywhere. */
    private fun crests(dry: FloatArray, added: FloatArray, scale: Float): Boolean {
        for (i in dry.indices) if (kotlin.math.abs(dry[i] + added[i] * scale) > 0.999f) return true
        return false
    }

    /** Sum of squares, the energy the STN parts are compared by. */
    private fun energy(samples: FloatArray): Float {
        var acc = 0.0
        for (v in samples) acc += v.toDouble() * v
        return acc.toFloat()
    }

    private fun rms(samples: FloatArray, from: Int, to: Int): Float {
        if (to <= from) return 0f
        var acc = 0.0
        for (i in from until to) acc += samples[i].toDouble() * samples[i]
        return sqrt(acc / (to - from)).toFloat()
    }

    private fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }
}
