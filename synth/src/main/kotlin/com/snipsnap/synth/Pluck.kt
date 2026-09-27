package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.pow
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
enum class PluckVoice { NYLON, HARP, KOTO, BANJO }

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
     * Hz modes that eat into PICK's reach). The ceiling on every voice is
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
    internal fun synthesize(voice: PluckVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val freq = frequencyFor(voice, m.getValue("TUNE"))
        val damp = m.getValue("DAMP")
        val pick = m.getValue("PICK")
        val double = m.getValue("DOUBLE")
        val body = Dsp.lin(m.getValue("BODY"), 0f, BODY_MAX)
        val position = Dsp.expMap(m.getValue("STRIKE"), STRIKE_BRIDGE, STRIKE_CENTRE)

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
        }

        // The budget, not the length: DAMP 1 keeps today's thud (0.3 x the
        // voice's ring), DAMP 0 reaches the ceiling, and trimToDecay below
        // then cuts the buffer where the string actually stops ringing, so a
        // muted pluck stays a short file and a DAMP 0 harp gets its ring.
        val seconds = Dsp.expMap(1f - damp, 0.3f * ring, RING_CEILING_SECONDS)
            .coerceIn(RING_FLOOR_SECONDS, RING_CEILING_SECONDS)
        val out = ks(freq, seconds, damp, loopHz, Dsp.expMap(pick, pickLo, pickHi), seed = Dsp.seedFor("PLUCK", voice.name), rate = rate, position = position)
        if (double > 0.01f) {
            // The 12-string trick: a second, slightly sharp string under the
            // first. Detune grows with the macro so it goes chorus -> honky.
            val det = ks(
                freq * Dsp.lin(double, 1.002f, 1.012f), seconds, damp, loopHz,
                Dsp.expMap(pick, pickLo, pickHi), seed = Dsp.seedFor("PLUCK", voice.name, "DOUBLE"), rate = rate, position = position,
            )
            val g = double * 0.7f
            for (i in out.indices) out[i] += det[i] * g
        }
        return trimToDecay(withBody(out, voice, body, rate), rate)
    }

    fun render(voice: PluckVoice, macros: Map<String, Float> = emptyMap()): Snip {
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE and decimate, for
        // consistency with the other 6 engines and because the exciter's
        // one-pole low-pass is itself rate-aware. PLUCK has no tanh/drive
        // saturation stage generating fresh above-Nyquist harmonics the way
        // THUMP/TONEWHEEL/VOX do, so the audible effect here is smaller -
        // but it is still real plumbing, not a no-op: Dsp.decimate's own
        // low-pass changes what a keygroup sounds like near the top of its
        // range, same as every other engine.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, renderRate)
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
     * The fixed body of each voice, in absolute Hz. Every row is a confirmed
     * or corrected line of docs/superpowers/plans/2026-09-25-pluck-depth-body-research.md
     * (source numbers in the comments); a t60 marked "shape" there is a
     * placeholder for the audition, not a measurement.
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
    ): FloatArray = Strings.pluck(freq, seconds, Strings.damping(damp, bodyLoopHz), pickHz, seed, rate, position)
}
