package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** A box: the raw bowed string, the voice and the render rate in, the boxed string (the string's length) out. */
internal typealias BodyBox = (FloatArray, ArcoVoice, Int) -> FloatArray

/**
 * The candidates of R1d's BODY listening page ([ArcoBodyGenerator]), each one a function from the raw bowed string to a boxed raw string: the same shape as
 * [Arco.withBody]'s result, cut to the string's length (BODY never makes a note longer), ready for [Arco.finish]. They are test code on top of the engine's own
 * pieces ([Arco.bodyFor], [Strings.bodyRing], [Modes], [Dsp.Biquad], [Arco.finish]); nothing in `src/main` is touched and nothing here is in the engine.
 *
 * **Why they exist.** The owner heard R1c's BODY 1 (the narrow box ringing 1.75 times the string) and wrote "Body doesn't seem to do anything" and "Body
 * still seems a little light". R1d's ruler ([ArcoBodyMeasure], D, the 1/k-weighted colour distance in dB of BODY 1 against the default, loudness equalised) read R1c at a
 * median of 2.617 dB over its ten-note grid. Three designs were built and measured to reach about 8 dB; nobody can listen but the owner, so the engine change waits for
 * the owner's ears and these are the candidates the page plays.
 *
 * **Every constant below is a listening value left by the R1d design competition**, not a measured instrument and not tuned here: the numbers are the competition's, copied
 * so that each function is the design's own code and the page's medians of D match the competition's (the generator throws if one is more than 0.05 dB off).
 *
 *  - [bed] is design A, the formant bed: R1c's narrow box HELD at its knee value (0.5 times the string, not climbing), then a cascade of broad peaking biquads
 *    ([Dsp.Biquad.peaking]) at fixed frequencies, every dB multiplied by the strength and by the depth ([bedDepthFor], 1 at BODY 1). The competition's ladder rungs are strength 0.62 and 1.
 *  - [broad] is design B, the broad modal box: R1c's narrow box exactly (climbing to 1.75 times the string at BODY 1), then a second [Strings.bodyRing] on its output through one broad
 *    [Modes.fixed] row at `broadTop` times the narrow box's output, cut to the string's length. The ladder rungs are broadTop 2 and 6.
 *  - [louder] is the control: R1c's narrow box alone, at 12 times the string. The judges' numpy run of R1c with only its top raised to 12 reached the same D as the designs (7.92) with the top
 *    two octave bands 14 dB down at equal energy, so a high D can mean a louder narrow box and a duller note: the page plays it unlabelled to find out which the owner hears.
 *  - [plain] and [last] are R1c's own box at BODY 0.5 (the default) and BODY 1 ("what you heard last time"), written out from R1c's curve so the page does not move when the engine does.
 *    The generator checks both are [Arco.render]'s own on this tree, so it fails the day the engine is no longer R1c.
 */
internal object ArcoBodyCandidates {

    // ---- R1c's box, written out ---------------------------------------------------------

    /** R1c's knee: the knob is the string's own box (R1b's) up to here. [Arco.BODY_KNEE]; the generator checks it still is. */
    const val R1C_BODY_KNEE = 0.5f

    /** R1c's box at BODY 1, in times the string: the owner's last page. [Arco.BODY_TOP]; the generator checks it still is. */
    const val R1C_BODY_TOP = 1.75f

    /** R1c's `boxAmountFor`, verbatim with its top as a parameter (design A's probe seam): identity at or under the knee, straight to [top] at BODY 1. */
    private fun boxAmountFor(body: Float, top: Float): Float =
        if (body <= R1C_BODY_KNEE) body else R1C_BODY_KNEE + (body - R1C_BODY_KNEE) * (top - R1C_BODY_KNEE) / (1f - R1C_BODY_KNEE)

    /** [Strings.bodyRing] of [string] through [table] at [amount], cut to the string's length (R1c's `withBody` rule: a box that rang on would turn a note into a LOOP). */
    private fun ringBox(string: FloatArray, table: List<Modes.Mode>, amount: Float, rate: Int): FloatArray {
        val rung = Strings.bodyRing(string, table, amount, rate, Arco.BODY_CEILING_SECONDS)
        return if (rung.size == string.size) rung else rung.copyOf(string.size)
    }

    /** What the page calls THE PLAIN ONE: R1c's box at the default BODY 0.5. */
    fun plain(raw: FloatArray, voice: ArcoVoice, rate: Int): FloatArray = ringBox(raw, Arco.bodyFor(voice), boxAmountFor(Arco.DEFAULT_BODY, R1C_BODY_TOP), rate)

    /** What the page calls WHAT YOU HEARD LAST TIME: R1c's box at BODY 1, 1.75 times the string. */
    fun last(raw: FloatArray, voice: ArcoVoice, rate: Int): FloatArray = ringBox(raw, Arco.bodyFor(voice), boxAmountFor(1f, R1C_BODY_TOP), rate)

    // ---- design A: the formant bed ---------------------------------------------------------

    /** Design A's narrow box at BODY 1: [boxAmountFor] with its top at the knee, which is R1c's default box, [plain], to the bit (the generator checks it). */
    fun heldBox(raw: FloatArray, voice: ArcoVoice, rate: Int): FloatArray = ringBox(raw, Arco.bodyFor(voice), boxAmountFor(1f, HELD_BOX_TOP), rate)

    /**
     * One broad resonance of the bed: a peaking biquad at [hz], [q] sharp (1.5 is about an octave wide, 5 about a quarter of one), [db] high at full depth and strength
     * (negative: a trough or a roll-off). Fixed in frequency, not following the pitch.
     */
    class Formant(val hz: Float, val q: Float, val db: Float)

    /** The narrow box's amount in design A: held at the knee's value above the knee (design A's `BODY_TOP = BODY_KNEE`), so the string keeps R1c's default box and the bed does the rest. */
    private const val HELD_BOX_TOP = R1C_BODY_KNEE

    /**
     * CELLO's bank, design A's: below 300 Hz the narrow box stays as it is, a broad hump at 120 Hz carries the first partials, a plateau at 570 Hz, a trough at 1.25 kHz, a hill at
     * 2.3 kHz and a wide roll-off centred at 5 kHz. Centres are the competition's physics scout's, the Q and the gains guesses (listening). The depth, plus and minus 24 to 28 dB,
     * is three to four times a real body's: the judges' risk is a wah or a nasal honk.
     */
    private val CELLO_FORMANTS = listOf(
        Formant(120f, 1.5f, 7f),
        Formant(570f, 1.9f, 24f),
        Formant(1250f, 1.6f, -24f),
        Formant(2300f, 4f, 28f),
        Formant(5000f, 1.5f, -12f),
    )

    /**
     * ERHU's bank, design A's: three broad humps (520 Hz, 1.3 kHz, 2.65 kHz), a trough between the first two, a cut at 3.8 kHz and a roll-off at 5 kHz. No erhu Q was found, so every Q and
     * gain is a guess (listening). The judges' note: ERHU's D is flattered by the 3.8 and 5 kHz cuts, where its default note holds little energy.
     */
    private val ERHU_FORMANTS = listOf(
        Formant(520f, 1.7f, 18f),
        Formant(910f, 2.3f, -7f),
        Formant(1300f, 1.5f, 20f),
        Formant(2650f, 2.5f, 20f),
        Formant(3800f, 2.8f, -10f),
        Formant(5000f, 1.5f, -12f),
    )

    private fun formantsFor(voice: ArcoVoice): List<Formant> = when (voice) {
        ArcoVoice.CELLO -> CELLO_FORMANTS
        ArcoVoice.ERHU -> ERHU_FORMANTS
    }

    /** How far to full the bed is at [body]: 0 at or under the knee, 1 at BODY 1 (design A's `bedDepthFor`, verbatim). */
    private fun bedDepthFor(body: Float): Float =
        if (body <= R1C_BODY_KNEE) 0f else ((body - R1C_BODY_KNEE) / (1f - R1C_BODY_KNEE)).coerceAtMost(1f)

    /**
     * Design A at BODY 1 with every [Formant.db] times [strength] (the competition's rungs: 0.62 and 1): the narrow box held at the knee, then the bank, one [Dsp.Biquad] per formant in
     * the bank's order, in place on the cut string, so the result is the string's length. Design A's `withBody`, with its depth at BODY 1.
     */
    fun bed(raw: FloatArray, voice: ArcoVoice, rate: Int, strength: Float): FloatArray {
        val boxed = heldBox(raw, voice, rate)
        val depth = bedDepthFor(1f)
        for (f in formantsFor(voice)) {
            val peak = Dsp.Biquad()
            peak.peaking(f.hz, f.db * strength * depth, f.q, rate)
            for (i in boxed.indices) boxed[i] = peak.process(boxed[i])
        }
        return boxed
    }

    // ---- design B: the broad modal box ---------------------------------------------------------

    /** The broad box's centre and Q, design B's: CELLO in the plates' crowd of modes between 300 and 700 Hz, ERHU on the skin's first membrane hump. Shape, not measured: first guesses (listening). */
    private const val CELLO_BROAD_HZ = 400f
    private const val CELLO_BROAD_Q = 3f
    private const val ERHU_BROAD_HZ = 650f
    private const val ERHU_BROAD_Q = 4f

    /** One broad mode: gain 1 because the RMS match takes the level off any single row, t60 `2.2 Q / f` (16.5 ms CELLO, 13.5 ms ERHU). */
    private fun broadRow(hz: Float, q: Float): List<Modes.Mode> = listOf(Modes.fixed(hz, 1f, 2.2f * q / hz))

    private val CELLO_BROAD = broadRow(CELLO_BROAD_HZ, CELLO_BROAD_Q)
    private val ERHU_BROAD = broadRow(ERHU_BROAD_HZ, ERHU_BROAD_Q)

    private fun broadFor(voice: ArcoVoice): List<Modes.Mode> = when (voice) {
        ArcoVoice.CELLO -> CELLO_BROAD
        ArcoVoice.ERHU -> ERHU_BROAD
    }

    /** Nothing at or under the knee, then [top] times the square of how far up the rest of the knob BODY is (design B's `broadAmountFor`, verbatim): [top] at BODY 1. */
    private fun broadAmountFor(body: Float, top: Float): Float {
        if (body <= R1C_BODY_KNEE) return 0f
        val up = (body - R1C_BODY_KNEE) / (1f - R1C_BODY_KNEE)
        return top * up * up
    }

    /**
     * Design B at BODY 1 with the broad box at [broadTop] times the narrow box's output (the competition's rungs: 2 and 6): R1c's narrow box exactly (1.75 times the string), then
     * [Strings.bodyRing] again on that output through the broad row, cut to the string's length. Design B's `withBody`, with its amount at BODY 1.
     */
    fun broad(raw: FloatArray, voice: ArcoVoice, rate: Int, broadTop: Float): FloatArray {
        val narrow = ringBox(raw, Arco.bodyFor(voice), boxAmountFor(1f, R1C_BODY_TOP), rate)
        return ringBox(narrow, broadFor(voice), broadAmountFor(1f, broadTop), rate)
    }

    // ---- the louder-box control ---------------------------------------------------------

    /** The control's amount, in times the string: R1c's narrow box with its top raised from 1.75 to 12 (the judges' numpy run; D 7.92, the top two octave bands 14 dB down at equal energy). */
    const val LOUDER_BOX = 12f

    /** The control: R1c's narrow box alone at [LOUDER_BOX] times the string, cut to the string's length. */
    fun louder(raw: FloatArray, voice: ArcoVoice, rate: Int): FloatArray = ringBox(raw, Arco.bodyFor(voice), LOUDER_BOX, rate)

    // ---- the candidate list ---------------------------------------------------------

    /** The competition's rung strengths of design A and amounts of design B. */
    const val BED_LOW = 0.62f
    const val BED_FULL = 1f
    const val BROAD_LOW = 2f
    const val BROAD_FULL = 6f

    /**
     * One candidate of the page: [key] (its name in the generator's checks and in `key.json`, never on the page), the design it is and its [setting] in words, the box it rings
     * ([box]) and the median D over the ten-note grid at BODY 1 against BODY 0.5 that the competition measured for it ([competitionD], dB: the generator holds this code to it within 0.05).
     */
    class Candidate(
        val key: String, val design: String, val setting: String, val competitionD: Double,
        val box: BodyBox,
    )

    val BED_62 = Candidate("A062", "A, the formant bed", "strength 0.62", 5.98) { raw, voice, rate -> bed(raw, voice, rate, BED_LOW) }
    val BED_100 = Candidate("A100", "A, the formant bed", "strength 1.0", 8.66) { raw, voice, rate -> bed(raw, voice, rate, BED_FULL) }
    val BROAD_2 = Candidate("B2", "B, the broad modal box", "broadTop 2.0", 6.09) { raw, voice, rate -> broad(raw, voice, rate, BROAD_LOW) }
    val BROAD_6 = Candidate("B6", "B, the broad modal box", "broadTop 6.0", 8.46) { raw, voice, rate -> broad(raw, voice, rate, BROAD_FULL) }
    val LOUDER = Candidate("LOUD", "the louder-box control", "R1c's narrow box alone, 12 times the string", 7.92) { raw, voice, rate -> louder(raw, voice, rate) }

    /** The five new candidates, in the order the generator tables them. */
    val ALL: List<Candidate> = listOf(BED_62, BED_100, BROAD_2, BROAD_6, LOUDER)

    // ---- the render ---------------------------------------------------------

    /**
     * [Arco.render]'s own path for a one-shot (the settled macros, the bow, the box, [Arco.finish]) with [box] as the box. With [plain] or [last] it is
     * [Arco.render] at BODY 0.5 or 1 to the sample (the generator checks it), so a candidate is rendered exactly as the engine renders a note and then
     * is as loud as every other render ([Arco.finish] levels it); the page's own gain is the audition level, the same as the re-listen page's.
     */
    fun renderThrough(voice: ArcoVoice, macros: Map<String, Float>, box: BodyBox): Snip {
        val m = Arco.settled(macros, voice)
        require(!Arco.isLoop(m.getValue("HOLD"))) { "the page plays one-shots" }
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = Arco.bow(voice, Arco.frequencyFor(voice, m.getValue("TUNE")), m, rate)
        return Snip(Arco.finish(box(raw, voice, rate), rate), channels = 1, sampleRate = Dsp.RATE)
    }
}
