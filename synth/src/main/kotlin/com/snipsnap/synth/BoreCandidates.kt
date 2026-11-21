package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * BORE's candidate voices for listening round bore-voices-1. FLUTE and SAX are the kept voices; the melodic gate asks for six, so
 * five candidates are built (one spare) and appended to [BoreVoice] after them, so saved names and ordinals never move. The owner
 * kept all five on 2026-11-20 (bore-voices-1's range clips; touch-bore-1's soft, medium, hard), so they are now voices
 * ([Bore.KEPT_VOICES]) and "candidate" here names the five built in this file.
 *
 * Each is a blown instrument the engine's two voices are not, and each uses a part of the valve-in-a-pipe model they leave unused:
 *  - [BoreVoice.HOLLOW]: SAX's single reed on a *closed cylinder* (a clarinet's chalumeau). The pipe is half a period long and
 *    reflects with its sign turned over ([Spec.roundTrip] 0.5, [Spec.loopGain] -0.95, no DC blocker), so only the odd harmonics
 *    sustain. The design's own HOLLOW (bore spec, "The bore", and its voice table: A2, the anchor, odd over even by 39 to 43 dB).
 *  - [BoreVoice.SHAWM]: a *double reed* on a cone: the same reflection table with a steeper slope (the design's sentence for a
 *    double reed), so the reed closes sooner and harder, and two fixed formants after the pipe (the design's formant hook: a
 *    vowel the bore does not make, here near 1.2 and 2.9 kHz, where a shawm or an oboe is nasal).
 *  - [BoreVoice.SHAKU]: FLUTE's jet, set further off the edge and blown with far more of its breath heard: the jet's turbulence
 *    raised, and the *edge noise* (the rush of air past the labium, which a flute player hides and an end-blown bamboo flute
 *    makes part of its tone) added outside the pipe. The note is found from below by a bend the player makes with the jaw
 *    ([Spec.bendCents], the meri), longer and deeper than the player's scoop.
 *  - [BoreVoice.HORN]: a *lip valve*. The lips are a mass on a spring tuned near the note, pushed open by the mouth's pressure
 *    against the pipe's; how far they open sets how much of the mouth's pressure gets in (STK's brass, the lip a resonant
 *    two-pole, its opening the square of its displacement, held at 1). A brass bell then lifts what the bore holds above 1 kHz.
 *  - [BoreVoice.VESSEL]: FLUTE's jet blown across a *vessel*, not a pipe: one Helmholtz resonance (a two-pole band-pass at the
 *    note, [Helmholtz]) in place of the bore's delay line, so there is no harmonic series to feed; what the jet's soft clip makes
 *    above the note is all there is. An ocarina.
 *
 * Every candidate takes the player ([Bore.Human]) exactly as the kept voices do, from the same [Bore.blow]: the held LOOP and a held
 * key are cut from the same held note through the same chain, so a kept candidate needs no more work there.
 */
internal object BoreCandidates {

    /** How the valve meets the pipe. */
    enum class Kind { REED, JET, VESSEL }

    /**
     * One candidate's numbers. Pressures are absolute mouth pressures (reeds: a share of the closing pressure). Every value is a
     * first guess checked against the candidate tests (speaks, in tune, distinct, touch); none has been heard.
     */
    class Spec(
        val kind: Kind,
        val rootMidi: Int,
        /** The played pitch's residual against TUNE, cancelled here (cents; the loop is tuned this much sharp). */
        val pinCents: Float,
        val roundTrip: Double = 1.0,
        val loopGain: Float = Bore.LOOP_GAIN,
        val dcBlock: Boolean = true,
        /** The DC blocker's corner as a divisor of the note (0: the flute's fixed 2 Hz). */
        val dcDivisor: Float = 0f,
        /** The bore's one-pole corner: [bellLow] Hz at LIP 0 to [bellHigh] Hz at LIP 1 for a reed; a ratio to the note for a jet. */
        val bellLow: Float = 2_500f,
        val bellHigh: Float = 2_500f,
        val bellBreathLow: Float = 0.8f,
        val bellBreathHigh: Float = 1.3f,
        // the reed
        val slope: Float = Bore.REED_SLOPE,
        val offsetLow: Float = Bore.OFFSET_LOOSE,
        val offsetHigh: Float = Bore.OFFSET_TIGHT,
        val shareLow: Float = 0.7f,
        val shareHigh: Float = Bore.SHARE_TOP,
        // the jet
        val pressureLow: Float = Bore.FLUTE_PRESSURE_LOW,
        val pressureHigh: Float = Bore.FLUTE_PRESSURE_HIGH,
        val jetOffsetLow: Float = Bore.JET_OFFSET_LOW,
        val jetOffsetHigh: Float = Bore.JET_OFFSET_HIGH,
        val jetGain: Float = Bore.JET_GAIN_PER_P,
        val turbulenceLow: Float = 0.05f,
        val turbulenceHigh: Float = 0.3f,
        /** The edge noise heard outside the pipe, as a share of the steady wave's level, at BREATH 0 and 1 (SHAKU). */
        val edgeLow: Float = 0f,
        val edgeHigh: Float = 0f,
        val edgeHz: Float = 3_000f,
        /** A bend up into the note from this many cents below, over [bendSeconds] (SHAKU's meri). */
        val bendCents: Float = 0f,
        val bendSeconds: Double = 0.1,
        /** The vessel's Q at LIP 0 and 1 (VESSEL). */
        val qLow: Float = 30f,
        val qHigh: Float = 30f,
        /** Whether the tongue's pop and seed start the note (the reeds' onset). */
        val tongued: Boolean = false,
        val seedLevel: Float = 0.3f,
        /** Fixed formants after the pipe: centre Hz, gain dB, Q. */
        val formants: List<Triple<Float, Float, Float>> = emptyList(),
        val voicing: Bore.Voicing = Bore.Voicing.NONE,
        val opening: Bore.Opening = Bore.OPENING_SOFTENED,
        /** How long the note takes to let go after the breath stops (the kept voices' is [Bore.RELEASE_SECONDS], 80 ms). */
        val releaseSeconds: Float = 0.25f,
        /** The chiff's breath burst as a share of the kept voices' ([Bore.CHIFF_NOISE]). */
        val chiffScale: Float = 1f,
    )

    /** A candidate's opening: the mean left to the output's high-pass (no step at the first sample, [Bore.Opening]) and no trim. */
    private val OPEN = Bore.Opening(centred = false, trimDb = 0f)

    private val HOLLOW = Spec(
        kind = Kind.REED, rootMidi = 45, pinCents = 0f,
        roundTrip = 0.5, loopGain = -0.95f, dcBlock = false,
        bellLow = 5_500f, bellHigh = 1_600f, bellBreathLow = 0.8f, bellBreathHigh = 1.3f,
        offsetLow = 0.5f, offsetHigh = 0.72f, shareLow = 0.68f, shareHigh = 0.93f,
        turbulenceLow = 0.02f, turbulenceHigh = 0.2f,
        tongued = true,
        opening = OPEN,
    )

    private val SHAWM = Spec(
        kind = Kind.REED, rootMidi = 55, pinCents = 4f,
        dcDivisor = Bore.SAX_BLOCKER_DIVISOR,
        bellLow = 4_000f, bellHigh = 4_000f,
        slope = -0.5f, offsetLow = 0.45f, offsetHigh = 0.7f, shareLow = 0.72f, shareHigh = 0.93f,
        turbulenceLow = 0.02f, turbulenceHigh = 0.2f,
        tongued = true,
        formants = listOf(Triple(1_200f, 9f, 2.5f), Triple(2_900f, 7f, 3f)),
        voicing = Bore.Voicing(-10f, 0f),
        opening = Bore.Opening(centred = false, trimDb = 2f),
    )

    private val SHAKU = Spec(
        kind = Kind.JET, rootMidi = 62, pinCents = -3f,
        dcDivisor = 0f,
        bellLow = 1.2f, bellHigh = 2.5f, bellBreathLow = 0.9f, bellBreathHigh = 1.2f,
        pressureLow = 0.9f, pressureHigh = 1.05f,
        jetOffsetLow = 0.3f, jetOffsetHigh = 0.7f,
        turbulenceLow = 0.15f, turbulenceHigh = 0.5f,
        edgeLow = 0.2f, edgeHigh = 0.5f, edgeHz = 1_500f,
        bendCents = 60f, bendSeconds = 0.06,
        opening = OPEN,
    )

    private val PANPIPE = Spec(
        kind = Kind.JET, rootMidi = 60, pinCents = -5f,
        roundTrip = 0.5, loopGain = -0.95f, dcBlock = false,
        bellLow = 3f, bellHigh = 6f, bellBreathLow = 0.9f, bellBreathHigh = 1.2f,
        pressureLow = 0.82f, pressureHigh = 1.02f,
        jetOffsetLow = 0.1f, jetOffsetHigh = 0.25f,
        turbulenceLow = 0.02f, turbulenceHigh = 0.1f,
        edgeLow = 0.004f, edgeHigh = 0.012f, edgeHz = 4_000f,
        opening = OPEN,
    )

    private val VESSEL = Spec(
        kind = Kind.VESSEL, rootMidi = 60, pinCents = 0f,
        pressureLow = 0.85f, pressureHigh = 1.02f, chiffScale = 0.4f,
        jetOffsetLow = 0.1f, jetOffsetHigh = 0.45f,
        turbulenceLow = 0.06f, turbulenceHigh = 0.35f,
        qLow = 25f, qHigh = 10f,
        tongued = true, seedLevel = 0.3f,
        opening = OPEN,
    )

    /** The candidate's numbers, or null for FLUTE and SAX (which never read this file). */
    fun of(voice: BoreVoice): Spec? = when (voice) {
        BoreVoice.FLUTE, BoreVoice.SAX -> null
        BoreVoice.HOLLOW -> HOLLOW
        BoreVoice.SHAWM -> SHAWM
        BoreVoice.SHAKU -> SHAKU
        BoreVoice.PANPIPE -> PANPIPE
        BoreVoice.VESSEL -> VESSEL
    }

    fun spec(voice: BoreVoice): Spec = requireNotNull(of(voice)) { "$voice is a kept voice, not a candidate" }

    /** The BREATH every voice starts at ([Bore.macrosFor]): the candidates' touch is measured down from it. */
    const val DEFAULT_BREATH = 0.6f

    /**
     * The candidates' touch ([CandidateTouch]; the owner on every soft, medium, hard clip: "All sound the same"). A breath softer than
     * [DEFAULT_BREATH] is a gentler air stream that leaves mostly the fundamental and the next partials, quieter and slower to speak.
     * The clips' soft touch (BREATH at 0.62 of the default, depth 0.38) loses 18.5 dB above three times the note, is 7.7 dB quieter
     * before that and takes 0.3 s to rise; a candidate's held soft layer is blown at that same touch
     * ([BoreKeys.CANDIDATE_SOFT_BREATH_SHARE]) and shaped by [HELD_TOUCH]. The corner sits higher than ARCO's so a soft low reed keeps enough of its edge to stay a melodic note to the
     * classifier. At the default BREATH and above, the kept sound.
     */
    val TOUCH = CandidateTouch.Lean(shelfDbPerDepth = 48f, levelDbPerDepth = 20f, riseSecondsPerDepth = 0.8f, cornerRatio = 3f)

    /** A candidate's one-shot [x] at [hz] as blown at [breath] ([TOUCH]). */
    fun touched(x: FloatArray, hz: Float, breath: Float): FloatArray =
        CandidateTouch.touched(x, hz, CandidateTouch.depth(breath, DEFAULT_BREATH), TOUCH)

    /**
     * [TOUCH] on a held key (`BoreKeys`): the same darker shelf and the same slower rise, no level. A held key's level is the player's
     * velocity, once, in the host (the BELLOWS rule), so the zone is levelled on its loop after the touch, as every zone is. The touch
     * goes on the held note before the zone is cut from it, so the join is searched and checked on what ships ([Bore.checkJoin]).
     * The LOOP's held note ([Bore.heldNote]) is not touched: the LOOP is the one-shot's, and plays at the patch's own BREATH.
     */
    val HELD_TOUCH = CandidateTouch.Lean(shelfDbPerDepth = TOUCH.shelfDbPerDepth, levelDbPerDepth = 0f, riseSecondsPerDepth = TOUCH.riseSecondsPerDepth, cornerRatio = TOUCH.cornerRatio)

    /** A candidate's held note [x] at [hz] as blown at [breath] ([HELD_TOUCH]); untouched at the default BREATH and above. */
    fun heldTouched(x: FloatArray, hz: Float, breath: Float): FloatArray =
        CandidateTouch.touched(x, hz, CandidateTouch.depth(breath, DEFAULT_BREATH), HELD_TOUCH)

    fun bellHz(s: Spec, hz: Float, breath: Float, lip: Float): Float = when (s.kind) {
        Kind.JET, Kind.VESSEL -> hz * Dsp.expMap(lip, s.bellLow, s.bellHigh) * Dsp.lin(breath, s.bellBreathLow, s.bellBreathHigh)
        else -> Dsp.expMap(lip, s.bellLow, s.bellHigh) * Dsp.lin(breath, s.bellBreathLow, s.bellBreathHigh)
    }

    fun offset(s: Spec, lip: Float): Float = Dsp.lin(lip, s.offsetLow, s.offsetHigh)

    fun closing(s: Spec, offset: Float): Float = (1f - offset) / -s.slope

    /** The mouth pressure BREATH asks for: a share of the closing pressure on a reed, an absolute pressure otherwise. */
    fun pressure(s: Spec, breath: Float, lip: Float): Float = when (s.kind) {
        Kind.REED -> closing(s, offset(s, lip)) * Dsp.lin(breath, s.shareLow, s.shareHigh)
        else -> Dsp.lin(breath, s.pressureLow, s.pressureHigh)
    }

    fun turbulence(s: Spec, breath: Float): Float = Dsp.lin(breath, s.turbulenceLow, s.turbulenceHigh)

    /**
     * The vessel: one Helmholtz resonance, a constant-peak band-pass (the RBJ cookbook's) at the note with a peak gain of [gain], in
     * Double (a two-pole at a few hundred hertz at 176.4 kHz holds its tuning to a cent only in Double). [reflected] is the
     * cavity's pressure this sample, from what was put in before it; [inject] puts the jet's flow in. The same two calls the pipe
     * answers ([Strings.Loop.reflected] and [Strings.Loop.inject]), so the jet's code is the flute's.
     */
    class Helmholtz(hz: Float, private val q: Float, private val gain: Float, private val rate: Int) {
        private var b0 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init { retune(hz) }

        fun retune(hz: Float) {
            val w = 2.0 * PI * hz / rate
            val alpha = sin(w) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = gain * alpha / a0
            a1 = -2.0 * cos(w) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun reflected(): Float = y1.toFloat()

        fun inject(x: Float) {
            val y = b0 * (x - x2) - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x.toDouble()
            y2 = y1
            y1 = y
        }
    }

    /** How long SHAKU's edge noise takes to come in after the note starts, squared in (seconds). */
    const val EDGE_RISE_SECONDS = 0.3

    /** The edge noise: the turbulence's own noise band-passed at [hz] (Q 0.7), heard outside the pipe. */
    class Edge(hz: Float, rate: Int) {
        private val bp = Dsp.Biquad().also { it.bandpass(hz, 0.7f, rate) }
        fun next(white: Float): Float = bp.process(white)
    }

    /** The formants and the candidate's own voicing are applied in [Bore.condition]; this is the formant pass alone, in place, at [Dsp.RATE]. */
    fun formants(s: Spec, buf: FloatArray) {
        for ((hz, db, q) in s.formants) {
            val f = Dsp.Biquad().also { it.peaking(hz, db, q) }
            for (i in buf.indices) buf[i] = f.process(buf[i])
        }
    }

    /** The meri: cents under the note at [t], a raised-cosine bend up from [Spec.bendCents] over [Spec.bendSeconds] times three. */
    fun bend(s: Spec, t: Double): Float {
        if (s.bendCents <= 0f) return 0f
        val span = 3.0 * s.bendSeconds
        if (t >= span) return 0f
        return (-s.bendCents * (0.5 + 0.5 * cos(PI * t / span))).toFloat()
    }

}
