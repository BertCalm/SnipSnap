package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * PLUCK — Karplus-Strong physical modeling, 1983 and era-correct.
 *
 * The best fun-per-parameter ratio in synthesis: a burst of noise in a tuned
 * feedback loop *is* a plucked string, and the one knob that matters (DAMP)
 * always sounds good. Voices are body characters — the loop brightness, the
 * exciter colour and the ring length are voiced per instrument — and macros
 * refine within that character, never out of it.
 *
 * TUNE snaps to semitones across two octaves from the voice's root: pads get
 * notes, not frequencies, which is what makes a pluck kit playable as music.
 */
enum class PluckVoice { NYLON, HARP, KOTO, BANJO, SITAR }

object Pluck {

    /** Semitone span of the TUNE macro. Root at 0, two octaves up at 1. */
    const val TUNE_SEMITONES = 24

    /**
     * Per-voice nudge on top of [Dsp.MELODIC_LOUDNESS_TARGET], zeroed out
     * awaiting a listening pass (task-4-report.md) - a table edit here, not
     * a refactor of [render].
     */
    private val LOUDNESS_OFFSET: Map<PluckVoice, Float> = PluckVoice.entries.associateWith { 0f }

    /**
     * STRIKE's ends as a fraction of the string: the bridge and the centre.
     * The map between them is exponential because positions near the bridge
     * change fast (spec, "Macros"). Defaults sit on the quarter position the
     * audition read as SAME as the pre-STRIKE engine - placeholders until
     * the gate, like [LOUDNESS_OFFSET].
     */
    internal const val STRIKE_BRIDGE = 0.03f
    internal const val STRIKE_CENTRE = 0.5f

    /**
     * The render follows the string's own decay between these bounds
     * (spec, "Macros"): the ceiling is a file-size judgement (≈ 350–530 KB
     * per pad at 24-bit mono), the floor keeps a muted pluck from becoming
     * a click. `Dsp.levelTo`'s ceiling and `Dsp.fadeTail` are unchanged.
     */
    internal const val RING_FLOOR_SECONDS = 0.25f
    internal const val RING_CEILING_SECONDS = 4.0f

    /**
     * BODY defaults are placeholders until the audition (spec 2026-09-25,
     * "Macros"), like LOUDNESS_OFFSET; the spec's first values (0.35-0.6,
     * 1.05-1.8x the string) put the body's modes nearest a voice's root
     * above the fundamental - NYLON's 104 Hz air mode against its 110 Hz
     * root, HARP's 168.5 Hz A0 against 165 Hz, BANJO's 220/234 Hz head
     * modes under a 392 Hz note - which pulled the pitch detector off the
     * note and swamped PICK; an initial 0.45-0.75x pass was lowered to
     * NYLON 0.10, HARP 0.10, KOTO 0.15, BANJO 0.15 for the Phase 2 gate.
     * That gate heard every one of them read CLOSER to the instrument at
     * every amount, timid rather than wrong, so they were raised again -
     * bisected one voice at a time, holding the others at their own
     * known-good value so a failing voice never masked the ones after it -
     * to the highest each voice's own tests still pass: NYLON 0.30, HARP
     * 0.30, KOTO 0.45, BANJO 0.25 (BANJO's ceiling dropped under the
     * Phase 2b head-mode rework above, which strengthens the same 220/234
     * Hz modes that eat into PICK's reach), SITAR 0.0 (inert, no table). The
     * ceiling on every voice is
     * `PICK moves the centroid at every step of its travel`: PICK's top end
     * must still move the note's spectral centroid at the shipped default
     * body, or a bright pick stroke stops reading as brighter. NYLON also
     * has to clear `every Pluck semitone lands within five cents at the
     * default body` - `PICK brightens the attack` no longer competes for
     * NYLON's ceiling, since that test now forces BODY to 0 for its own
     * centroid measurement instead of relying on wherever the default sits.
     * BANJO dropped again at the third listen (2026-09-26), bisected the
     * same way, to 0.20: the bridge hills' gain doubling (3500/5000 Hz to
     * 0.70/0.55, for the string's tin) pulled `PICK moves the centroid`
     * dead between 0.9 and 1.0 at 0.25. `factory defaults all classify as
     * percussion` still reads BANJO's default as SNARE at every BODY down
     * to 0.10 tried - independent of BODY, traced instead to the same
     * gate's loop/pick brightening (loopHz 9000, pick 3000-14000) pushing
     * `highRatio` over the classifier's SNARE threshold - so it is left
     * failing rather than loosened or masked by a BODY this low.
     *
     * DOUBLE means something different on SITAR than on the other four
     * voices: everywhere else it's the 12-string trick, a second detuned
     * string under the first (see [synthesize]'s DOUBLE block). On SITAR
     * it rings the tarab - four sympathetic strings under the fret at the
     * played note's own octave-below/fifth/octave/octave-and-a-fifth
     * (see [sympathetic], [SYMPATHETIC_SERIES]) - because a sitar has no
     * second playing string to detune; DOUBLE 1 is a deliberate drone.
     */
    fun macrosFor(voice: PluckVoice): List<MacroSpec> = when (voice) {
        PluckVoice.NYLON -> listOf(
            MacroSpec("TUNE", 0.4f), MacroSpec("DAMP", 0.45f), MacroSpec("PICK", 0.4f),
            MacroSpec("STRIKE", 0.75f), MacroSpec("BODY", 0.30f), MacroSpec("DOUBLE", 0.15f),
        )
        PluckVoice.HARP -> listOf(
            MacroSpec("TUNE", 0.55f), MacroSpec("DAMP", 0.2f), MacroSpec("PICK", 0.6f),
            MacroSpec("STRIKE", 0.75f), MacroSpec("BODY", 0.30f), MacroSpec("DOUBLE", 0.2f),
        )
        // A koto is played with a pick close to the bridge.
        PluckVoice.KOTO -> listOf(
            MacroSpec("TUNE", 0.45f), MacroSpec("DAMP", 0.4f), MacroSpec("PICK", 0.75f),
            MacroSpec("STRIKE", 0.6f), MacroSpec("BODY", 0.45f), MacroSpec("DOUBLE", 0.45f),
        )
        // Fingerpicks near the bridge, short notes, and a default note where the head sits just above it (spec, "BANJO"; gate 2026-09-26).
        PluckVoice.BANJO -> listOf(
            MacroSpec("TUNE", 0.3f), MacroSpec("DAMP", 0.5f), MacroSpec("PICK", 0.7f),
            MacroSpec("STRIKE", 0.25f), MacroSpec("BODY", 0.20f), MacroSpec("DOUBLE", 0.1f),
        )
        // A sitar: plucked near the bridge with a wire mizrab, the sympathetic
        // strings present by default. BODY defaults to 0: bodyFor(SITAR) now
        // carries a table (see bodyFor), but it is a SHAPE, not a measurement
        // (research note 2026-09-26, section 5) - it ships as a candidate on
        // the audition page and stays off by default until the gate chips
        // one closer. DAMP 0.5 gives a default budget of about 1.3 s, under the
        // classifier's 1.5 s loop gate - the sympathetic strings pushed the
        // old 0.35 default past it (1.82 s, read as LOOP; Task 4 ruling).
        PluckVoice.SITAR -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DAMP", 0.5f), MacroSpec("PICK", 0.65f),
            MacroSpec("STRIKE", 0.3f), MacroSpec("BODY", 0f), MacroSpec("DOUBLE", 0.4f),
        )
    }

    fun defaults(voice: PluckVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /** SCRAMBLE near a preset; see [Thump.scramble] (docs/SYNTH_UPGRADE.md, U2). */
    fun scramble(voice: PluckVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + PluckPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    private fun rootFor(voice: PluckVoice): Float = when (voice) {
        PluckVoice.NYLON -> 110f
        PluckVoice.HARP -> 165f
        PluckVoice.KOTO -> 147f
        PluckVoice.BANJO -> 196f
        PluckVoice.SITAR -> 139f   // C#3, the common tonic of the playing string
    }

    /** The snapped note frequency the TUNE macro lands on for [voice]. */
    fun frequencyFor(voice: PluckVoice, tune: Float): Float =
        frequencyFor(voice, Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES))

    /**
     * The frequency [semitone] steps above [voice]'s root - the engine's own
     * notion of "in tune", so a test can assert against intent instead of a
     * second copy of the same formula.
     */
    internal fun frequencyFor(voice: PluckVoice, semitone: Int): Float =
        rootFor(voice) * 2f.pow(semitone / 12f)

    /**
     * The raw synth loop, at whatever [rate] the caller wants - split out of
     * [render] so U6's oversampled dispatch (docs/SYNTH_UPGRADE.md) can be
     * tested directly against a native-rate render, rather than trusting
     * that reading [render]'s own source matches what it actually does.
     *
     * Karplus-Strong's delay line is *sized* by the rate (`n = rate / freq`
     * inside [ks]), not just incrementally rate-aware the way a phase
     * accumulator is - a structural dependency, not just a constant to
     * thread through. It falls out naturally here: at 4x rate, [ks] builds
     * a 4x-longer delay line over 4x as many samples covering the *same*
     * real-time duration, so the string's pitch and decay are unaffected -
     * only the resolution of the loop and its exciter's low-pass changes.
     */
    internal fun synthesize(
        voice: PluckVoice,
        macros: Map<String, Float>,
        rate: Int,
        velocity: Float = 1f,
        stiffnessOverride: Float? = null,
        jawariOverride: Float? = null,
        sympatheticOverride: Sympathetic? = null,
    ): FloatArray {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val freq = frequencyFor(voice, m.getValue("TUNE"))
        val damp = m.getValue("DAMP")
        val pick = m.getValue("PICK")
        val double = m.getValue("DOUBLE")
        val body = Dsp.lin(m.getValue("BODY"), 0f, BODY_MAX)
        val position = Dsp.expMap(m.getValue("STRIKE"), STRIKE_BRIDGE, STRIKE_CENTRE)
        val stiffness = stiffnessOverride ?: stiffnessFor(voice)
        val jawari = (jawariOverride ?: jawariFor(voice)) * velocityDrive(velocity)

        // The body characters. loopHz is the low-pass inside the feedback
        // loop (what makes a kalimba woody and a harp glassy); pickLo/pickHi
        // bound the exciter colour; ring is the undamped tail length.
        val loopHz: Float
        val pickLo: Float
        val pickHi: Float
        val ring: Float
        when (voice) {
            PluckVoice.NYLON -> { loopHz = 3400f; pickLo = 1200f; pickHi = 6000f; ring = 1.1f }
            PluckVoice.HARP -> { loopHz = 5200f; pickLo = 1800f; pickHi = 9000f; ring = 1.3f }
            PluckVoice.KOTO -> { loopHz = 4200f; pickLo = 1500f; pickHi = 8000f; ring = 1.0f }
            // A steel string over a taut head, and the brightest string here: the loop keeps its treble (the gate heard 5.6 kHz as "not tinny enough") and the pick band sits above the others'.
            PluckVoice.BANJO -> { loopHz = 9000f; pickLo = 3000f; pickHi = 14000f; ring = 1.0f }
            // Steel strings under a wire plectrum: brighter than KOTO, darker
            // than BANJO, and the longest ring of the five (spec, "Voice constants").
            PluckVoice.SITAR -> { loopHz = 7000f; pickLo = 2500f; pickHi = 12000f; ring = 1.4f }
        }

        // The budget, not the length: DAMP 1 keeps today's thud (0.3 x the
        // voice's ring), DAMP 0 reaches the ceiling, and trimToDecay below
        // then cuts the buffer where the string actually stops ringing, so a
        // muted pluck stays a short file and a DAMP 0 harp gets its ring.
        val seconds = Dsp.expMap(1f - damp, 0.3f * ring, RING_CEILING_SECONDS)
            .coerceIn(RING_FLOOR_SECONDS, RING_CEILING_SECONDS)
        val out = ks(freq, seconds, damp, loopHz, Dsp.expMap(pick, pickLo, pickHi), seed = Dsp.seedFor("PLUCK", voice.name), rate = rate, position = position, stiffness = stiffness, jawari = jawari)
        if (double > 0.01f) {
            if (voice == PluckVoice.SITAR) {
                // The tarab: strings under the frets that ring in sympathy with
                // the played note. [sympatheticOverride] lets the audition swap
                // the tuning; the shipped default is the note's own series
                // (spec, "Sympathetic strings under DOUBLE") until the gate
                // chooses SYMPATHETIC_SCALE's scale-degree tuning instead. The
                // tarab are fed by the played string alone, the way the bridge
                // feeds them, so their sum is independent of the order of
                // s.ratios.
                val s = sympatheticOverride ?: SYMPATHETIC_SERIES
                val g = s.level * double
                val string = out.copyOf()
                for ((k, ratio) in s.ratios.withIndex()) {
                    val sign = if (k % 2 == 0) 1f else -1f
                    val hz = freq * ratio * (1f + sign * s.detune)
                    val loop = sympathetic(string, hz, rate, s.coupling, s.onset, (s.onsetSeconds * rate).toInt())
                    for (i in out.indices) out[i] += g * loop[i]
                }
            } else {
                // The 12-string trick: a second, slightly sharp string under the
                // first. Detune grows with the macro so it goes chorus -> honky.
                val det = ks(
                    freq * Dsp.lin(double, 1.002f, 1.012f), seconds, damp, loopHz,
                    Dsp.expMap(pick, pickLo, pickHi), seed = Dsp.seedFor("PLUCK", voice.name, "DOUBLE"), rate = rate, position = position,
                    stiffness = stiffness,
                )
                val g = double * 0.7f
                for (i in out.indices) out[i] += det[i] * g
            }
        }
        return trimToDecay(withBody(out, voice, body, rate), rate)
    }

    /**
     * [velocity] is a render parameter, not a macro - no knob, no preset,
     * no recipe carries it; only the jawari reads it today.
     */
    fun render(voice: PluckVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip =
        renderWith(voice, macros, velocity)

    /**
     * [render] with the audition's overrides: the generator hears stiffness
     * and jawari values the shipped constants do not carry.
     *
     * U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE and decimate, for
     * consistency with the other 6 engines and because the exciter's
     * one-pole low-pass is itself rate-aware. PLUCK has no tanh/drive
     * saturation stage generating fresh above-Nyquist harmonics the way
     * THUMP/TONEWHEEL/VOX do, so the audible effect here is smaller -
     * but it is still real plumbing, not a no-op: Dsp.decimate's own
     * low-pass changes what a keygroup sounds like near the top of its
     * range, same as every other engine.
     */
    internal fun renderWith(voice: PluckVoice, macros: Map<String, Float>, velocity: Float = 1f, stiffness: Float? = null, jawari: Float? = null, sympathetic: Sympathetic? = null): Snip {
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, renderRate, velocity = velocity, stiffnessOverride = stiffness, jawariOverride = jawari, sympatheticOverride = sympathetic)
        val out = Dsp.decimate(raw, RATE)

        // Loudness, not peak: a sine-heavy voice at equal peak reads quieter
        // (Dsp.MELODIC_LOUDNESS_TARGET's doc comment has the measurement).
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET + LOUDNESS_OFFSET.getValue(voice))
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /** The decay-following cut, with PLUCK's floor and ceiling - see [Strings.trimToDecay]. */
    internal fun trimToDecay(buf: FloatArray, rate: Int): FloatArray =
        Strings.trimToDecay(buf, rate, RING_FLOOR_SECONDS, RING_CEILING_SECONDS)

    /** BODY 1 is three times the string's RMS - the spike's "dominant", which stays reachable (spec, "Macros"). */
    internal const val BODY_MAX = 3f

    /**
     * Stiffness allpass coefficients (see [Strings.Loop]). SITAR's two
     * candidates put the tenth partial about 1 % and about 3 % sharp of
     * harmonic, found by StiffnessTest's probe, not by hand; the low one
     * ships until the gate chooses. KOTO and HARP carry zero: both passed a
     * Phase 2 gate and do not change unheard (the audition offers them the
     * low candidate). The coefficient is a raw z-domain value, so its delay
     * is about 24 samples at any rate and the dispersion it produces scales
     * with the render rate (RATE × OVERSAMPLE today); a reader deriving an
     * inharmonicity coefficient from it must say which rate.
     */
    internal const val SITAR_STIFFNESS_LOW = -0.92f    // measured: tenth partial 0.88 % sharp with the shipped jawari on (StiffnessTest's probe)
    internal const val SITAR_STIFFNESS_HIGH = -0.953f  // measured: tenth partial 2.96 % sharp with the shipped jawari on (StiffnessTest's probe); with the jawari at 0.3 the root note reads 5.6 c sharp (StiffnessTest measures it on every run), so if the gate chooses this candidate the drive steps down or the spec's fallback applies
    internal const val SITAR_STIFFNESS = SITAR_STIFFNESS_LOW

    internal fun stiffnessFor(voice: PluckVoice): Float = when (voice) {
        PluckVoice.SITAR -> SITAR_STIFFNESS
        PluckVoice.NYLON, PluckVoice.HARP, PluckVoice.KOTO, PluckVoice.BANJO -> 0f
    }

    /**
     * The jawari's drive (see [Strings.Loop]) times [velocityDrive]. 0.3 is
     * the starting point; the audition hears 0.15, 0.3 and 0.6 and the chips
     * choose (spec, "The jawari").
     */
    internal const val SITAR_JAWARI = 0.3f

    internal fun jawariFor(voice: PluckVoice): Float = when (voice) {
        PluckVoice.SITAR -> SITAR_JAWARI
        PluckVoice.NYLON, PluckVoice.HARP, PluckVoice.KOTO, PluckVoice.BANJO -> 0f
    }

    /** A soft note buzzes a little, a hard one fully. */
    private fun velocityDrive(velocity: Float): Float = Dsp.lin(velocity.coerceIn(0f, 1f), 0.3f, 1f)

    /**
     * The fixed body of each voice, in absolute Hz. Every row is a confirmed
     * or corrected line of docs/superpowers/plans/2026-09-25-pluck-depth-body-research.md
     * (source numbers in the comments); a t60 marked "shape" there is a
     * placeholder for the audition, not a measurement - except SITAR's,
     * a shape body under an explicit exception (see below).
     */
    internal fun bodyFor(voice: PluckVoice): List<Modes.Mode> = when (voice) {
        // Classical guitar - research note section 5.1 (Christensen & Vistisen
        // 1980; Jansson 2002; Su et al. 2024; Richardson via Woodhouse). The
        // 127 Hz Helmholtz antiresonance is deliberately absent: the source
        // calls it a notch in the response, not a radiating peak.
        PluckVoice.NYLON -> listOf(
            Modes.fixed(104f, 1.00f, 0.61f),   // A0 air resonance - measured, Q 29.0
            Modes.fixed(219f, 0.90f, 0.26f),   // T1 top plate - measured, Q 25.8
            Modes.fixed(286f, 0.35f, 0.15f),   // T2 dipole, quiet radiator - shape
            Modes.fixed(370f, 0.30f, 0.15f),   // higher air-cavity mode - shape
            Modes.fixed(436f, 0.25f, 0.12f),   // top-plate mode 3 - shape
            Modes.fixed(510f, 0.15f, 0.10f),   // top-plate mode 4 - shape
            Modes.fixed(645f, 0.10f, 0.08f),   // top-plate mode 5 - shape
        )
        // Koto - research note section 2, which quotes the Coaldrake ICA
        // 2019 conference paper directly, read in full (not the unreachable
        // 2020 JASA paper the rest of section 2's catalogue depends on):
        // "the (0,1) mode was at 100Hz and the (0,0) mode at 85Hz which the
        // acoustic camera confirmed." Section 5.2 is NOT cited here as
        // endorsing this table - it says "no table," because every other
        // mode in section 2 traces only to the unreachable source; these
        // two are the exception the controller ruled usable, because they
        // come from the source the verifier could actually open. The rest
        // of the koto catalogue stays out until the 2020 paper can be read.
        // Both decays below are shapes, not measurements: neither source
        // reports a Q or a bandwidth for either mode.
        PluckVoice.KOTO -> listOf(
            Modes.fixed(85f, 0.80f, 0.40f),    // air mode (0,0) - shape decay
            Modes.fixed(100f, 1.00f, 0.50f),   // first top-plate eigenmode - shape decay
        )
        // Concert harp - research note section 5.3 (Le Carrou, Gautier &
        // Foltete 2007, one Camac Atlantide Prestige). The 161.9 Hz pitch
        // mode is absent: the source excludes it from play.
        PluckVoice.HARP -> listOf(
            // t60 = 2.2*Q/f, Q = 1/(2*zeta) per row below.
            Modes.fixed(54.8f, 0.20f, 0.37f),  // global soundbox motion (5.5%) - shape from the source's damping % read as zeta; the eta reading doubles it
            Modes.fixed(80.9f, 0.15f, 0.28f),  // first bending (4.8%) - shape from the source's damping % read as zeta; the eta reading doubles it
            Modes.fixed(123.4f, 0.15f, 0.36f), // second bending - shape from the source's damping % read as zeta; the eta reading doubles it
            Modes.fixed(152.2f, 0.95f, 0.31f), // T1 soundboard - shape from the source's damping % read as zeta; the eta reading doubles it
            Modes.fixed(168.5f, 1.00f, 0.47f), // A0 soundbox air - shape from the source's damping % read as zeta; the eta reading doubles it
        )
        // Banjo - research note section 5.4 (Rae 2010; Politzer 2016;
        // Politzer, Woodhouse & Mansour 2021). The two bridge hills are one
        // specific bridge's; the source says other bridges put them elsewhere.
        // Gate-tuned 2026-09-26: the head fundamental leads, because at the
        // default note G4 the 509/803 Hz modes only thickened the string's
        // own harmonics ("closer to guitar").
        PluckVoice.BANJO -> listOf(
            Modes.fixed(220f, 0.80f, 0.15f),   // pot air, coupled doublet - shape; short, so it thumps rather than sings beside the note
            Modes.fixed(234f, 1.00f, 0.09f),   // head (0,1) - measured, bandwidth 20-30 Hz; the banjo's plunk, on every note
            Modes.fixed(509f, 0.70f, 0.15f),   // head (1,1) - shape
            Modes.fixed(803f, 0.60f, 0.12f),   // head (2,1) - shape
            Modes.fixed(850f, 0.70f, 0.10f),   // pot-air cylinder mode - shape
            Modes.fixed(1593f, 0.40f, 0.08f),  // head (5,1) - shape
            Modes.fixed(2055f, 0.30f, 0.06f),  // head (7,1), the last strong head mode - shape
            Modes.fixed(3500f, 0.70f, 0.05f),  // bridge hill - shape - raised at the gate: these formants are the tin
            Modes.fixed(5000f, 0.55f, 0.04f),  // bridge hill - shape - raised at the gate: these formants are the tin
        )
        // A SHAPE, not a measurement: the one paper that measures a sitar's
        // body is paywalled and could not be opened (research note
        // 2026-09-26, section 4), so under the note's explicit exception
        // these three modes are representative sitar/tanpura resonances
        // (a gourd's air resonance, the soundboard's main wood mode, a
        // bridge-region resonance) with t60 from a plausible Q by
        // t60 = 2.2 Q / f. They are candidates: BODY's default is 0 until
        // the gate chips one closer, and the note lists them as shapes.
        PluckVoice.SITAR -> listOf(
            Modes.fixed(110f, 1.00f, 0.20f),   // gourd air resonance - shape, Q ~ 10
            Modes.fixed(270f, 0.70f, 0.065f),  // soundboard main wood mode - shape, Q ~ 8
            Modes.fixed(520f, 0.50f, 0.025f),  // bridge-region resonance - shape, Q ~ 6
        )
    }

    /**
     * The string drives its body - see [Strings.bodyRing], where the drive
     * and its reasoning live; PLUCK passes its own table and ceiling.
     */
    internal fun withBody(string: FloatArray, voice: PluckVoice, amount: Float, rate: Int, differentiate: Boolean = true): FloatArray =
        Strings.bodyRing(string, bodyFor(voice), amount, rate, RING_CEILING_SECONDS, differentiate)

    /**
     * The 1983 algorithm itself: one period of filtered noise, then a delay
     * line feeding back through a low-pass. DAMP closes the loop filter and
     * pulls the feedback gain down together — one knob, two parameters,
     * always musical. The loop lives in [Strings] (SILK Phase 1a), shared
     * with SILK; this is PLUCK's door into it, and its loop-length floor is
     * [Strings.MIN_LOOP_SAMPLES].
     *
     * Internal, not private, so PluckTest can drive it with a synthetic
     * freq/rate pair and pin the loop-length invariant below directly -
     * the real voice table never reaches it (measured minimum `exact`
     * across every voice x TUNE semitone x DAMP was 175.93 samples, at
     * the since-removed KALIMBA's TUNE=1/DAMP=1, a 220 Hz root - swept and
     * printed against this function's own formula, not estimated; BANJO,
     * at 196 Hz, now has the shortest loop of the remaining voices), so
     * there is no reachable call site to assert against otherwise.
     *
     * [stiffness] is SITAR's dispersion and [jawari] its bridge buzz - see
     * [Strings.Loop] and [Strings.tune] for what they do and how their own
     * delay is budgeted into the loop length; both are skipped at 0, which
     * is every voice but SITAR.
     */
    internal fun ks(
        freq: Float,
        seconds: Float,
        damp: Float,
        bodyLoopHz: Float,
        pickHz: Float,
        seed: Int,
        rate: Int,
        position: Float = 0f,
        stiffness: Float = 0f,
        jawari: Float = 0f,
    ): FloatArray {
        require(stiffness > -1f && stiffness <= 0f) { "stiffness must be in (-1, 0], got $stiffness" }
        require(jawari in 0f..0.95f) { "jawari drive must be in [0, 0.95], got $jawari" }
        return Strings.pluck(freq, seconds, Strings.damping(damp, bodyLoopHz), pickHz, seed, rate, position, stiffness, jawari)
    }

    /** How much of the main string reaches each sympathetic loop, sample by sample, the way the bridge transmits it. */
    private const val SYMPATHETIC_COUPLING = 0.05f
    /** Their sum enters the output at this times DOUBLE, so DOUBLE 1 is a drone on purpose. */
    private const val SYMPATHETIC_LEVEL = 0.5f
    private const val SYMPATHETIC_LOOP_HZ = 4000f
    private const val SYMPATHETIC_FEEDBACK = 0.995f

    /**
     * How the sitar's sympathetic strings are tuned and fed. [ratios] are each loop's
     * frequency over the played note; [detune] a signed cents-like spread
     * applied alternately (+, -, +, -) so no two loops sit exactly on a
     * harmonic of the note; [onset] the coupling for the first
     * [onsetSeconds] of the note, the pluck's transient reaching the tarab
     * through the bridge before the steady [coupling] takes over.
     */
    internal data class Sympathetic(
        val ratios: FloatArray,
        val detune: Float,
        val onset: Float,
        val onsetSeconds: Float,
        val coupling: Float = SYMPATHETIC_COUPLING,
        val level: Float = SYMPATHETIC_LEVEL,
    )

    /** The played note's own series: the drone below, the fifth, the octave, the octave and a fifth. Ships until the gate chooses. */
    internal val SYMPATHETIC_SERIES = Sympathetic(ratios = floatArrayOf(0.5f, 1.5f, 2f, 3f), detune = 0f, onset = SYMPATHETIC_COUPLING, onsetSeconds = 0f)

    /**
     * Scale degrees - the second, the major third, the fourth, the major
     * sixth - whose harmonics do not sit on the note's own partials, so
     * they are heard as strings and not as a resonance inside the note;
     * detuned three thousandths so they shimmer; and fed the pluck's first
     * ten milliseconds at ten times the steady coupling, as the bridge
     * transmits the transient. A candidate for the gate.
     */
    internal val SYMPATHETIC_SCALE = Sympathetic(ratios = floatArrayOf(9f / 8f, 5f / 4f, 4f / 3f, 5f / 3f), detune = 0.003f, onset = 0.5f, onsetSeconds = 0.010f)

    /**
     * One sympathetic string: a Karplus-Strong loop at [hz] with no burst of
     * its own, fed continuously by [input] - at [onset] for the first
     * [onsetSamples], then at the steady [coupling] - ringing with
     * [SYMPATHETIC_FEEDBACK] under a darker low-pass. Tuned the way
     * [Strings.tune] is (integer delay, fractional allpass, the low-pass's
     * delay in the budget) - its own local copy of that math, since this
     * loop's shape (no burst, continuously fed, no stiffness or jawari) is
     * different enough from [Strings.pluck]'s that sharing the function
     * would need a third caller shape neither SILK nor PLUCK currently
     * needs - so the loop rings at the ratio it was given. The real tarab
     * strings sit under and against each other physically too, but this
     * model couples each loop only to the played string, not to its
     * neighbors.
     *
     * A tarab rings long, not forever: [SYMPATHETIC_FEEDBACK] at 0.995 is a
     * decay of about five seconds at C#4's fundamental and ten an octave
     * below, and the 4 kHz loop low-pass shortens the partials further. At
     * 0.999 the loops held the render above the trim threshold to the end
     * of the string's own budget.
     */
    private fun sympathetic(input: FloatArray, hz: Float, rate: Int, coupling: Float, onset: Float, onsetSamples: Int): FloatArray {
        val filterA = 1.0 - exp(-2.0 * PI * min(SYMPATHETIC_LOOP_HZ, rate * 0.45f) / rate)
        val poleR = 1.0 - filterA
        val w = 2.0 * PI * hz / rate
        val filterPhase = -atan2(poleR * sin(w), 1.0 - poleR * cos(w))
        val exact = (rate / hz) - (-filterPhase / w) - 0.5
        require(exact >= Strings.MIN_LOOP_SAMPLES) { "sympathetic loop at $hz Hz is too short ($exact samples)" }
        val n = floor(exact).toInt()
        val frac = (exact - n).toFloat()
        val a = (1f - frac) / (1f + frac)
        var apX1 = 0f
        var apY1 = 0f
        val lp = Dsp.OnePole(rate)
        val out = FloatArray(input.size)
        for (i in input.indices) {
            val fed = (if (i < onsetSamples) onset else coupling) * input[i]
            if (i <= n) { out[i] = fed; continue }
            val d = 0.5f * (out[i - n] + out[i - n - 1])
            val tuned = a * (d - apY1) + apX1
            apX1 = d
            apY1 = tuned
            out[i] = fed + SYMPATHETIC_FEEDBACK * lp.lp(tuned, SYMPATHETIC_LOOP_HZ)
        }
        return out
    }
}
