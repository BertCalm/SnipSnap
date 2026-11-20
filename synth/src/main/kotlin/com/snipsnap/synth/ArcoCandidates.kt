package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * ARCO's candidate voices for listening round arco-voices-1. CELLO, ERHU and SARANGI are the kept voices; the melodic gate asks for
 * six, so four candidates are built (one spare) and appended to [ArcoVoice] after them, so saved names and ordinals never move.
 * Nothing here has been heard.
 *
 * Each is a bowed instrument the kept three are not, and each uses a part of the friction model they leave unused. The bow, its stroke,
 * its bite, GRIP's window, HOLD, the stop, the LOOP's retune, the held key and the player ([Arco.played]) are the engine's own: a
 * candidate changes only what the bow drags ([Bowed]) and, where it says so, the box and the finger.
 *  - [ArcoVoice.WHEEL]: a hurdy-gurdy. A rosined wheel is the bow: it turns at one speed, so there is no stroke to bite and the key
 *    only presses the string onto it (BOW's attack, never an accent). The string's bridge is the *chien*, a loose foot that knocks
 *    the soundboard once a period, every time the string swings past it ([Chien]): BUZZ's rattle made period-locked, the knock
 *    ARCO's own KDoc names as WHEEL's research. A box with a hurdy-gurdy's two body modes; no vibrato (keys, not a finger on a string).
 *  - [ArcoVoice.BAR]: a bowed vibraphone bar. The bow drags a *bar*, not a string: a banded waveguide ([Banded], Essl and Cook's
 *    bowed bar, as STK's BandedWG plays it), one loop per bar mode at the tuned bar's ratios 1, 4.0, 10.7, each loop's band-pass
 *    keeping it at its mode. The bow locks to the first mode; the upper modes ring above it. No box, no vibrato.
 *  - [ArcoVoice.GLASS]: a rubbed glass. The same banded bow on a wine glass's modes (1, 2.32, 4.25, 6.63), with a wetter, gentler
 *    grip (a finger on a wet rim, not hair), a slower stroke and a lower friction ceiling.
 *  - [ArcoVoice.VIOL] (spare): a bass viol. CELLO's string bowed further from the bridge ([Spec.beta] 0.2 against 0.133), on gut
 *    (a lower bridge corner), into a flat-backed box with a nasal body mode near 1.4 kHz. Its window is CELLO's, measured again.
 */
internal object ArcoCandidates {

    /**
     * What the bow drags: the one thing a candidate swaps. [Strings.Bow] is the kept voices' and answers the same calls. [stop] is the
     * player's hand after the bow lifts: [scale] is what [Arco.stopScale] asks of a string's bridge, [perPeriod] what the note must keep
     * of itself each period to be 60 dB down when the release ends (a bar or a glass has no bridge to scale, so it takes that).
     */
    interface Bowed {
        fun next(vBow: Float, slope: Float): Float
        fun lift()
        fun stop(scale: Float, perPeriod: Float)
    }

    enum class Kind { STRING, WHEEL, BANDED }

    class Spec(
        val kind: Kind,
        val rootMidi: Int,
        val semitones: Int = 24,
        /** The kept voice whose GRIP window, tuning share and bite this candidate plays at, [windowShift] semitones on. */
        val window: ArcoVoice,
        val windowShift: Int = 0,
        val beta: Float = Arco.BETA,
        /** The bridge corner as a share of the window's (a gut string is darker). */
        val cornerScale: Float = 1f,
        /** The bridge corner's floor as a multiple of the note: a dark corner does not lock a high note. */
        val cornerFloorRatio: Float = 0f,
        /** The bite at BOW 1, as a multiple of the sustain velocity; 1 is none (the wheel). */
        val overshootMax: Float = Arco.OVERSHOOT_MAX_ERHU,
        val vibrato: Float = 1f,
        val body: List<Modes.Mode> = emptyList(),
        val liftShelfHz: Float = Arco.ERHU_LIFT_SHELF_HZ,
        val liftBellHz: Float = Arco.ERHU_LIFT_BELL_HZ,
        // the chien
        val chienThreshold: Float = 0f,
        val chienGain: Float = 0f,
        val chienHz: Float = 2_400f,
        // the banded bow
        val ratios: FloatArray = floatArrayOf(),
        val modeGains: FloatArray = floatArrayOf(),
        /** Each band's loss per trip at GRIP 0 and 1 (the upper bands ring longer with more grip: brightness). */
        val bandLossLow: Float = 0.999f,
        val bandLossHigh: Float = 0.999f,
        val bandRadius: Float = 0.9995f,
        val rhoMax: Float = Strings.Bow.RHO_MAX,
        /** The banded bow's friction slope: GRIP's pressure moves it between [slopeLow] and [slopeHigh]. */
        val slopeLow: Float = 3f,
        val slopeHigh: Float = 1.5f,
        val vScale: Float = 1f,
        /** How hard the banded bow's push drives the loops (each loop's input is the push times its mode's share times this). */
        val pushGain: Float = 1f,
    )

    private val WHEEL = Spec(
        kind = Kind.WHEEL, rootMidi = 50, window = ArcoVoice.SARANGI,
        overshootMax = 1f, vibrato = 0f,
        body = listOf(Modes.fixed(290f, 1.0f, 0.15f), Modes.fixed(560f, 0.7f, 0.1f)),
        chienThreshold = 0.45f, chienGain = 2f, chienHz = 1_800f,
    )

    private val BAR = Spec(
        kind = Kind.BANDED, rootMidi = 53, window = ArcoVoice.SARANGI,
        overshootMax = 1.3f, vibrato = 0f,
        ratios = floatArrayOf(1f, 4.0198f, 10.7184f, 18.0697f),
        modeGains = floatArrayOf(1f, 0.6f, 0.3f, 0.15f),
        bandLossLow = 0.996f, bandLossHigh = 0.9995f, bandRadius = 0.9997f,
        slopeLow = 6f, slopeHigh = 4f, pushGain = 12f,
    )

    private val GLASS = Spec(
        kind = Kind.BANDED, rootMidi = 60, window = ArcoVoice.SARANGI,
        overshootMax = 1f, vibrato = 0f,
        ratios = floatArrayOf(1f, 2.32f, 4.25f, 6.63f),
        modeGains = floatArrayOf(1f, 0.5f, 0.3f, 0.2f),
        bandLossLow = 0.997f, bandLossHigh = 0.9995f, bandRadius = 0.9998f,
        rhoMax = 0.7f, slopeLow = 6f, slopeHigh = 4f, pushGain = 16f,
    )

    private val VIOL = Spec(
        kind = Kind.STRING, rootMidi = 48, window = ArcoVoice.CELLO, windowShift = 12,
        beta = 0.15f, cornerScale = 0.6f, cornerFloorRatio = 3.2f, overshootMax = Arco.OVERSHOOT_MAX_ERHU, vibrato = 0.5f,
        body = listOf(Modes.fixed(130f, 0.8f, 0.2f), Modes.fixed(470f, 1.0f, 0.12f), Modes.fixed(1_400f, 1.8f, 0.06f)),
        liftShelfHz = Arco.CELLO_LIFT_SHELF_HZ, liftBellHz = Arco.CELLO_LIFT_BELL_HZ,
    )

    /** The candidate's numbers, or null for CELLO, ERHU and SARANGI (which never read this file). */
    fun of(voice: ArcoVoice): Spec? = when (voice) {
        ArcoVoice.CELLO, ArcoVoice.ERHU, ArcoVoice.SARANGI -> null
        ArcoVoice.WHEEL -> WHEEL
        ArcoVoice.BAR -> BAR
        ArcoVoice.GLASS -> GLASS
        ArcoVoice.VIOL -> VIOL
    }

    fun spec(voice: ArcoVoice): Spec = requireNotNull(of(voice)) { "$voice is a kept voice, not a candidate" }

    /**
     * The candidates' touch ([CandidateTouch]; the owner on every soft, medium, hard clip: "All sound the same"). A bow lighter than
     * [Arco.DEFAULT_BOW] is a slower, lighter stroke further from the bridge: fewer upper partials, less level, a slower start. The
     * clips' soft touch (BOW at about half the default, depth 0.5) loses 20 dB above two and a half times the note, is 7 dB quieter
     * before that and takes 0.4 s to rise; the held keys' soft layer (BOW at [ArcoKeys.SOFT_SHARE] of itself, depth 0.72) would lose
     * 29 dB. At the default BOW and above, the kept sound.
     */
    val TOUCH = CandidateTouch.Lean(shelfDbPerDepth = 40f, levelDbPerDepth = 14f, riseSecondsPerDepth = 0.8f, cornerRatio = 2.5f)

    /** A candidate's one-shot [x] at [hz] as bowed at [bow] ([TOUCH]). */
    fun touched(x: FloatArray, hz: Float, bow: Float): FloatArray =
        CandidateTouch.touched(x, hz, CandidateTouch.depth(bow, Arco.DEFAULT_BOW), TOUCH)

    /** What the bow drags for [s] at [hz], with the bridge's corner at [corner] and the tuning [share]. */
    fun bowed(s: Spec, hz: Float, corner: Float, share: Float, grip: Float, rate: Int): Bowed = when (s.kind) {
        Kind.STRING -> StringBowed(Strings.Bow(f = hz, beta = s.beta, bridgeHz = corner, share = share, rate = rate))
        Kind.WHEEL -> Chien(Strings.Bow(f = hz, beta = s.beta, bridgeHz = corner, share = share, rate = rate), s, rate)
        Kind.BANDED -> Banded(s, hz, grip, rate)
    }

    /**
     * The friction slope a candidate's bow plays at a pressure [pressure] (GRIP's, in its window): the string's own `5 - 4 p` for a string
     * or a wheel, and for the banded bow [Spec.slopeLow] to [Spec.slopeHigh] across the window's pressures (0.85 to 1).
     */
    fun slope(s: Spec, pressure: Float): Float = when (s.kind) {
        Kind.BANDED -> Dsp.lin(((pressure - 0.85f) / 0.15f), s.slopeLow, s.slopeHigh)
        else -> 5f - 4f * pressure
    }

    /** A plain string, for VIOL: [Strings.Bow] itself. */
    private class StringBowed(private val bow: Strings.Bow) : Bowed {
        override fun next(vBow: Float, slope: Float) = bow.next(vBow, slope)
        override fun lift() = bow.lift()
        override fun stop(scale: Float, perPeriod: Float) = bow.gain(scale)
    }

    /**
     * The chien: the wave leaving the bow, and where its swing passes [Spec.chienThreshold] of its own running peak the excess knocks a
     * resonance at [Spec.chienHz] (a two-pole, Q 6, in Double), added back at [Spec.chienGain]. The excess comes once a period, at the
     * same place in it, so the knock is locked to the string: a buzz with the note's own rhythm, periodic as the string is, so a LOOP and
     * a held key close on it as they do on the bare string. The running peak follows the wave (a 50 ms release), so a soft note and a
     * loud one knock alike, and the threshold is a share of the note, never an absolute level.
     */
    private class Chien(private val bow: Strings.Bow, private val s: Spec, rate: Int) : Bowed {
        private val a1: Double
        private val a2: Double
        private val b0: Double
        private var y1 = 0.0
        private var y2 = 0.0
        private var x1 = 0.0
        private var x2 = 0.0
        private var peak = 0f
        private val release = Math.exp(-1.0 / (0.05 * rate)).toFloat()

        init {
            val w = 2.0 * PI * s.chienHz / rate
            val alpha = sin(w) / (2.0 * 6.0)
            val a0 = 1.0 + alpha
            b0 = alpha / a0
            a1 = -2.0 * cos(w) / a0
            a2 = (1.0 - alpha) / a0
        }

        override fun next(vBow: Float, slope: Float): Float {
            val x = bow.next(vBow, slope)
            val a = abs(x)
            peak = if (a > peak) a else peak * release
            val excess = a - s.chienThreshold * peak
            val knock = if (excess > 0f) excess.toDouble() else 0.0
            val y = b0 * (knock - x2) - a1 * y1 - a2 * y2
            x2 = x1
            x1 = knock
            y2 = y1
            y1 = y
            return x + s.chienGain * 8f * y.toFloat()
        }

        override fun lift() = bow.lift()
        override fun stop(scale: Float, perPeriod: Float) = bow.gain(scale)
    }

    /**
     * The banded bow (Essl and Cook's banded waveguides, the shape STK's BandedWG plays): one loop per mode of the bar or the glass, each
     * a delay one period of its mode long (read between samples) through a constant-peak band-pass at the mode, in Double, fed back with
     * its loss. The bow sees the sum of the loops' outputs as the velocity under it, the friction ([Strings.Bow.rho], the string's own
     * table) turns the difference into a push, and the push goes into every loop, weighted by the mode's gain at the bow. The bow locks
     * to the first mode and the others ring above it, at their own ratios. [stop] sets every loop's loss so each falls by the same
     * amount in the same time. [lift] takes the bow off.
     */
    private class Banded(private val s: Spec, hz: Float, grip: Float, private val rate: Int) : Bowed {
        private val n = s.ratios.size
        private val length = DoubleArray(n)
        private val ring = Array(n) { DoubleArray(0) }
        private val write = IntArray(n)
        private val b0 = DoubleArray(n)
        private val a1 = DoubleArray(n)
        private val a2 = DoubleArray(n)
        private val x1 = DoubleArray(n)
        private val x2 = DoubleArray(n)
        private val y1 = DoubleArray(n)
        private val y2 = DoubleArray(n)
        private val baseLoss = DoubleArray(n)
        private val loss = DoubleArray(n)
        private val weight = DoubleArray(n)
        private var down = true
        private val back = DoubleArray(n)

        init {
            val trip = Dsp.lin(grip, s.bandLossLow, s.bandLossHigh).toDouble()
            val totalGain = s.modeGains.sum().toDouble()
            for (k in 0 until n) {
                val f = hz * s.ratios[k]
                val w = 2.0 * PI * f / rate
                // A band past the band limit is kept silent: it would only fold.
                if (f > 0.4 * Dsp.RATE) { weight[k] = 0.0; ring[k] = DoubleArray(4); length[k] = 2.0; continue }
                length[k] = rate.toDouble() / f
                ring[k] = DoubleArray(floor(length[k]).toInt() + 3)
                val q = f / (rate * (1.0 - s.bandRadius) / PI)
                val alpha = sin(w) / (2.0 * max(q, 1.0))
                val a0 = 1.0 + alpha
                b0[k] = alpha / a0
                a1[k] = -2.0 * cos(w) / a0
                a2[k] = (1.0 - alpha) / a0
                // The loss per trip is the band's own: its trips are shorter, so the same loss per second is a higher gain per trip.
                baseLoss[k] = trip.pow(1.0 / s.ratios[k])
                loss[k] = baseLoss[k]
                weight[k] = s.modeGains[k] / totalGain
            }
        }

        private fun delayed(k: Int): Double {
            val r = ring[k]
            val pos = write[k] - length[k]
            val i0 = floor(pos).toInt()
            val f = pos - i0
            val a = r[Math.floorMod(i0, r.size)]
            val b = r[Math.floorMod(i0 + 1, r.size)]
            return a + (b - a) * f
        }

        override fun next(vBow: Float, slope: Float): Float {
            var v = 0.0
            for (k in 0 until n) {
                back[k] = delayed(k)
                v += back[k]
            }
            val dv = (vBow * s.vScale - v).toFloat()
            val push = if (down) dv * Strings.Bow.rho(dv, slope, s.rhoMax) else 0f
            var out = 0.0
            for (k in 0 until n) {
                if (weight[k] == 0.0) continue
                val x = push * s.pushGain * weight[k] + loss[k] * back[k]
                val y = b0[k] * (x - x2[k]) - a1[k] * y1[k] - a2[k] * y2[k]
                x2[k] = x1[k]
                x1[k] = x
                y2[k] = y1[k]
                y1[k] = y
                val r = ring[k]
                r[write[k]] = y
                write[k] = (write[k] + 1) % r.size
                out += y
            }
            return out.toFloat()
        }

        override fun lift() {
            down = false
        }

        override fun stop(scale: Float, perPeriod: Float) {
            // Every loop falls by the same amount in the same time: a loop k times the note's frequency keeps the k-th root a trip.
            for (k in 0 until n) loss[k] = minOf(baseLoss[k], perPeriod.toDouble().pow(1.0 / s.ratios[k]))
        }
    }
}
