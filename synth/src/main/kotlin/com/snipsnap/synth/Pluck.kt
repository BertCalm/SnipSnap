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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
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
     * The smallest Karplus-Strong loop length [ks] will accept, in
     * samples. Below this, splitting the loop into an integer delay [n]
     * plus a fractional allpass remainder stops being meaningful - see
     * [ks]'s `require` for why going below it used to produce a silently
     * unstable filter instead of a clear failure.
     */
    private const val MIN_LOOP_SAMPLES = 2.0

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
        sympatheticSoloOverride: Boolean = false,
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
                // Audition-only: [sympatheticSoloOverride] silences the main
                // string right here - after it has fed [string], so the tarab
                // still hear it, but before their sum is added to [out] - so
                // what [out] holds after the loop below is the tarab alone.
                // Never reachable from a macro or preset, like [jawariOverride].
                if (sympatheticSoloOverride) out.fill(0f)
                for ((k, ratio) in s.ratios.withIndex()) {
                    val sign = if (k % 2 == 0) 1f else -1f
                    val hz = freq * ratio * (1f + sign * s.detune)
                    val loop = sympathetic(string, hz, rate, s.coupling, s.onset, (s.onsetSeconds * rate).toInt())
                    for (i in out.indices) out[i] += g * loop[i]
                }
                // [ks]'s own output high-pass cleans the string (its own
                // residual measures ~1e-6 of peak, negligible) before it
                // ever reaches [string] above, but each tarab loop is a
                // near-unity-feedback resonator (SYMPATHETIC_FEEDBACK
                // 0.995) whose own DC gain is 1/(1-0.995) = 200 - even that
                // negligible residual, fed continuously for the note's
                // whole ~1.3 s budget, comes back out amplified into a
                // measurable offset (measured: the combined DOUBLE-default
                // render alone, without this, still failed `the jawari
                // leaves no offset`'s bound by about 1.2x after [ks]'s
                // fix closed the same bound's dry-string half outright).
                // Same fix, same reasoning, one level up: a plain one-pole
                // high-pass over the finished sum, applied once, outside
                // every loop's own feedback path.
                if (jawari > 0f) {
                    val dcHp = Dsp.OnePole(rate)
                    for (i in out.indices) out[i] -= dcHp.lp(out[i], 2f)
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
    internal fun renderWith(voice: PluckVoice, macros: Map<String, Float>, velocity: Float = 1f, stiffness: Float? = null, jawari: Float? = null, sympathetic: Sympathetic? = null, sympatheticSolo: Boolean = false): Snip {
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, renderRate, velocity = velocity, stiffnessOverride = stiffness, jawariOverride = jawari, sympatheticOverride = sympathetic, sympatheticSoloOverride = sympatheticSolo)
        val out = Dsp.decimate(raw, RATE)

        // Loudness, not peak: a sine-heavy voice at equal peak reads quieter
        // (Dsp.MELODIC_LOUDNESS_TARGET's doc comment has the measurement).
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET + LOUDNESS_OFFSET.getValue(voice))
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /**
     * Cuts [buf] where its 5 ms RMS envelope has fallen 60 dB below its
     * peak, never under [RING_FLOOR_SECONDS] - that's the normal case, and
     * the string stopped ringing before the budget ran out.
     *
     * If the scan never finds that point, the string was still ringing when
     * the buffer ran out, and which fade applies depends on why: at the ring
     * ceiling (`buf.size >= RING_CEILING_SECONDS * rate`) the string was cut
     * off mid-ring for real, so the last 400 ms gets the long squared fade.
     * Short of the ceiling, this was a DAMP-driven budget cut (a high DAMP
     * gave `synthesize` only a few hundred ms to work with), and the render
     * is still audible for nearly all of that budget - re-enveloping the
     * whole thing with a 400 ms fade would choke the very thud DAMP asked
     * for, so only the last 30 ms is faded, just enough to declick the cut.
     * Either way `render`'s own 4 ms `Dsp.fadeTail` then has nothing audible
     * left to touch.
     */
    internal fun trimToDecay(buf: FloatArray, rate: Int): FloatArray {
        val block = (rate * 0.005f).toInt().coerceAtLeast(1)
        val blocks = (buf.size + block - 1) / block
        if (blocks == 0) return buf
        val rms = DoubleArray(blocks)
        for (b in 0 until blocks) {
            val start = b * block
            val end = min(buf.size, start + block)
            var acc = 0.0
            for (i in start until end) acc += buf[i].toDouble() * buf[i]
            rms[b] = sqrt(acc / (end - start))
        }
        val peak = rms.max()
        if (peak <= 0.0) return buf
        val floorBlocks = ((RING_FLOOR_SECONDS * rate) / block).toInt()
        var last = blocks - 1
        // The scan never steps below floorBlocks, so that block is always
        // kept; when the loop stops because last == floorBlocks (rather than
        // finding a loud block), the block just above it was already walked
        // and found quiet on the previous iteration, so keeping both here is
        // not a guess.
        while (last > floorBlocks && rms[last] < peak * 0.001) last--
        val end = min(buf.size, (last + 2) * block)
        if (end < buf.size) return buf.copyOf(end)
        if (buf.size >= (RING_CEILING_SECONDS * rate).toInt()) {
            fadeCeiling(buf, ms = 400f, rate = rate)
        } else {
            fadeCeiling(buf, ms = 30f, rate = rate)
        }
        return buf
    }

    /**
     * A squared fade over the last [ms]. At the ring ceiling the string is
     * still moving, and a linear fade's last few milliseconds would sit
     * only ~30 dB down; squaring it puts them past -60 dB.
     */
    private fun fadeCeiling(buf: FloatArray, ms: Float, rate: Int) {
        val n = min(buf.size, (ms / 1000f * rate).toInt())
        if (n <= 0) return
        val start = buf.size - n
        for (i in 0 until n) {
            val g = 1f - i.toFloat() / n
            buf[start + i] *= g * g
        }
    }

    /** BODY 1 is three times the string's RMS - the spike's "dominant", which stays reachable (spec, "Macros"). */
    internal const val BODY_MAX = 3f

    /**
     * Stiffness allpass coefficients (see [ks]). SITAR's two candidates put
     * the tenth partial about 1 % and about 3 % sharp of harmonic, found by
     * StiffnessTest's probe, not by hand; the low one ships until the gate
     * chooses. KOTO and HARP carry zero: both passed a Phase 2 gate and do
     * not change unheard (the audition offers them the low candidate). The
     * coefficient is a raw z-domain value, so its delay is about 24 samples
     * at any rate and the dispersion it produces scales with the render
     * rate (RATE × OVERSAMPLE today); a reader deriving an inharmonicity
     * coefficient from it must say which rate.
     */
    internal const val SITAR_STIFFNESS_LOW = -0.92f    // measured: tenth partial 0.88 % sharp with the shipped jawari on (StiffnessTest's probe)
    internal const val SITAR_STIFFNESS_HIGH = -0.953f  // measured: tenth partial 2.96 % sharp with the shipped jawari on (StiffnessTest's probe); with the jawari at 0.3 the root note reads 5.6 c sharp (StiffnessTest measures it on every run), so if the gate chooses this candidate the drive steps down or the spec's fallback applies
    internal const val SITAR_STIFFNESS = SITAR_STIFFNESS_LOW

    internal fun stiffnessFor(voice: PluckVoice): Float = when (voice) {
        PluckVoice.SITAR -> SITAR_STIFFNESS
        PluckVoice.NYLON, PluckVoice.HARP, PluckVoice.KOTO, PluckVoice.BANJO -> 0f
    }

    /**
     * The wrap's drive (see [ks]) times [velocityDrive]. **0.010, settled**
     * at a second, cleaner gate (2026-09-27) than the one that first
     * chipped 0.015: a plain two-clip A/B (0.010 vs 0.015, no other
     * context) had Josh pick 0.010 outright. That preference and a
     * measurement agree, independently: `harmonicsOverFundamental`
     * (2nd-8th harmonic over the fundamental, 0.15-0.35 s past onset -
     * `PluckSpectra`'s own KDoc names it the measure a jawari should be
     * judged against) reads 4.4709 with no wrap at all, rises to 4.6422 at
     * 0.010, and had already fallen back to 3.0152 by 0.015 - so 0.015,
     * the depth an earlier gate chipped before anyone had measured this,
     * was already past the peak. `the wrap adds harmonics over the
     * fundamental at the shipped depth` (PluckTest) is that claim, now a
     * real assertion. See the spec's "The jawari" for the three
     * engineering rounds and the two gates in full.
     *
     * This is a much smaller number than the old rail-driven mechanism's
     * 0.3, because it now scales a fractional-sample shortening
     * ([SITAR_WRAP_REF], [SITAR_WRAP_MAX]) rather than a per-sample y²
     * pulldown.
     *
     * The chosen depth still has a real, measured tuning cost, smaller
     * than 0.015's but not gone: swept across all 25 semitones at this
     * default (DOUBLE 0, `TuningAccuracyTest`'s "the wrap's tuning cost
     * stays inside a quarter tone..."), it reads at worst +11.4 cents
     * sharp (semitone 2, not the root) and -15.7 cents flat at the top
     * (semitone 24), crossing between semitones 8 and 9 - about a third of
     * the way up the range, same crossing point as at 0.015, a third of
     * that depth's own worst-case cents. Most notes still fall outside the
     * ordinary five-cent bound. [ks]'s KDoc on [jawari] has the likely
     * reason the sign flips with register. Three tests beyond the
     * dedicated sweep also reach this cost and were adjusted, not
     * loosened blind: `STRIKE at either end keeps every Pluck voice within
     * five cents` now skips SITAR (its own quarter-tone companion test
     * covers STRIKE's two ends), and `the sympathetic strings at DOUBLE 1
     * keep every sitar note inside a quarter tone, the wrap's own cost
     * aside` / `the scale tuning at DOUBLE 1 keeps every sitar note inside
     * a quarter tone, the wrap's own cost aside` (both renamed from a
     * five-cent bound they were never actually testing, since the residual
     * is this same string cost, not anything DOUBLE-1-specific).
     */
    internal const val SITAR_JAWARI = 0.010f
    /** How much the loop shortens per unit of swing (see [ks]'s KDoc on [jawari]). */
    internal const val SITAR_WRAP_REF = 0.03f
    /** The loop never shortens by more than this fraction of its own period. */
    internal const val SITAR_WRAP_MAX = 0.03f

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
     * The string drives its body. The drive is the string's FIRST
     * DIFFERENCE, because the bridge force follows the string's slope at
     * the bridge, the velocity-like quantity - not, as a 34 dB tilt might
     * suggest, to hide the burst from the body's low modes. The
     * differentiator's own gain, `2*sin(theta/2)`, and [Modes.ring]'s own
     * onset peak, `1/sin(theta)` (`theta = 2*pi*hz/rate`), multiply to 1.0
     * at every body frequency, so it is the table's GAIN column that
     * governs each mode's burst response, and `ring`'s documented
     * low-frequency onset hazard is cancelled outright, not merely
     * reduced. What differentiating the drive actually buys: it removes
     * the burst's DC step (the spike's knock came from driving the body
     * with the string's raw displacement, DC and all), and it re-tilts
     * the balance among a voice's own sourced modes toward the high ones
     * by the differentiator's own frequency slope - NYLON's 645 Hz mode
     * gains on its 104 Hz mode by about 16 dB, BANJO's 5000 Hz mode on
     * its 220 Hz mode by about 27 dB, KOTO's 100 Hz mode on its 85 Hz
     * mode by about 1.4 dB. The body's level against the string is set by
     * the RMS match below, not by the drive.
     *
     * The body's own longest mode can ring well past the string that
     * struck it: a muted string's DAMP-driven budget is a few hundred ms,
     * a body mode's t60 can run past a second, and [Modes.ring] itself
     * only ever returns as many samples as it was given to excite - it
     * does not extend the ring on its own. So the drive here, and the
     * ring it produces, run `pad` samples past the string's own length
     * (`pad` sized off the table's own longest t60), and the returned
     * buffer follows that ring out toward the ring ceiling rather than
     * being cut where the string itself ends; [trimToDecay] (in
     * [synthesize]) follows the combined tail from there. The RMS match
     * is taken over the string's own length only, on both sides, so
     * [amount] means "times the string" the same way whether or not the
     * table's tail outlives it; the body is then added on top of the
     * string where the string still runs, and on its own past the
     * string's end. Amount 0 returns [string] itself: BODY 0 is the
     * string, byte for byte. [differentiate] exists only so the knock
     * test below can reproduce the spike's displacement drive for
     * comparison - production never sets it false.
     */
    internal fun withBody(string: FloatArray, voice: PluckVoice, amount: Float, rate: Int, differentiate: Boolean = true): FloatArray {
        if (amount <= 0f) return string
        val table = bodyFor(voice)
        if (table.isEmpty()) return string
        val pad = (table.maxOf { it.t60 } * rate).toInt()
        val driveLen = string.size + pad
        // `differentiate = false` reproduces the spike's displacement drive;
        // only the knock test passes it, production never does. Either way
        // the drive is silent past the string's own length - there is
        // nothing left to differentiate or copy once the string has ended,
        // and the padding is what lets the body ring on regardless.
        val drive = FloatArray(driveLen)
        if (differentiate) {
            var prev = 0f
            for (i in string.indices) {
                drive[i] = string[i] - prev
                prev = string[i]
            }
        } else {
            string.copyInto(drive)
        }
        val wet = Modes.ring(drive, 1f, table, rate)
        val g = rms(string, string.size) / rms(wet, string.size).coerceAtLeast(1e-9f)
        val outLen = min(driveLen, (RING_CEILING_SECONDS * rate).toInt())
        val out = FloatArray(outLen)
        for (i in out.indices) out[i] = (if (i < string.size) string[i] else 0f) + amount * g * wet[i]
        return out
    }

    /** RMS of the first [n] samples of [buf] (all of it by default). */
    private fun rms(buf: FloatArray, n: Int = buf.size): Float {
        val len = min(n, buf.size)
        var acc = 0.0
        for (i in 0 until len) acc += buf[i].toDouble() * buf[i]
        return sqrt(acc / len.coerceAtLeast(1)).toFloat()
    }

    /**
     * The 1983 algorithm itself: one period of filtered noise, then a delay
     * line feeding back through a low-pass. DAMP closes the loop filter and
     * pulls the feedback gain down together — one knob, two parameters,
     * always musical.
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
     * [stiffness] is a first-order allpass coefficient in (-1, 0]; 0 is no
     * allpass and the Phase 2 loop exactly. A negative value delays low
     * partials more than high ones so the upper partials sit sharp of
     * harmonic, the stiff-string law `n*sqrt(1 + B*n^2)` with B rising as
     * the coefficient falls. Its phase delay at the fundamental is
     * subtracted from the loop length so the note stays in tune.
     *
     * [jawari] is the wrap's drive: how much the loop shortens, per
     * [SITAR_WRAP_REF] of the string's own swing - tracked by a slow
     * envelope follower, not sample by sample - capped at [SITAR_WRAP_MAX]
     * of the period. The note's average pitch is kept in tune by `exact`'s
     * own budget below (`(rate/freq) / (1 - min(jawari, SITAR_WRAP_MAX))`),
     * a static correction computed once from the drive alone, not a
     * runtime giveback - there is nothing left to give back once the
     * budget already accounts for it. What remains audible is the
     * within-cycle motion itself, which shrinks as the note decays because
     * the envelope driving it does. Skipped entirely at 0.
     *
     * Measured (not assumed): swept across all 25 semitones at the shipped
     * default, the sign is not a coin flip at the ends - it is a smooth,
     * monotonic slide from sharp at the bottom of the range to flat at the
     * top, crossing between semitones 8 and 9 (about a third of the way
     * up, not at the range's middle); the full table and the worst cents
     * on each side are in [SITAR_JAWARI]'s KDoc, not repeated here.
     *
     * The likely reason, not yet proven by direct instrumentation of
     * [env] itself: the static budget above assumes the envelope sits at
     * [SITAR_WRAP_REF] - the one level at which the runtime shortening
     * (`jawari * n * env / SITAR_WRAP_REF`) exactly equals the budgeted
     * amount. Away from that level the two disagree, and a fixed
     * real-time measurement window would disagree with it differently by
     * register: a loop's round trips happen at its own fundamental, so
     * over any fixed time span a high note completes far more of them
     * than a low one, and if the envelope decays a roughly fixed fraction
     * per round trip - similar to the loop's own per-trip feedback decay -
     * it has fallen correspondingly further by the time that span is
     * read. At this voice's DAMP default the per-trip feedback gain is
     * 0.969, so by 0.30 s in (measuredHz's own window) a note at the
     * bottom of the range has completed only ~42 round trips and retains
     * about 27% of its own recent peak, against ~167 round trips and
     * about 0.5% for a note at the top - a low note's envelope is
     * therefore still the closer of the two to [SITAR_WRAP_REF] (or
     * pinned at the [SITAR_WRAP_MAX] cap) when the window is read, so it
     * plausibly shortens more than the
     * budget assumed and reads sharp; a high note's envelope has likely
     * fallen much further below [SITAR_WRAP_REF] by the same real time,
     * so it shortens less than the budget assumed and reads flat. That
     * direction matches the measured crossover; the exact crossing point
     * (semitones 8-9, not the register midpoint) has not been derived
     * from this argument, only observed.
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
        val loopHz = bodyLoopHz * Dsp.lin(1f - damp, 0.35f, 1.6f)
        val fb = Dsp.lin(1f - damp, 0.94f, 0.998f)

        // The loop length is almost never a whole number of samples, and
        // truncating it (the old `(rate / freq).toInt()`) detunes the
        // string by an amount that depends on the fractional remainder at
        // each frequency - non-monotonically across the keyboard, so a
        // pentatonic run of pads came out sour relative to *each other*,
        // not merely transposed (measured against the pre-fix loop: tens
        // of cents flat and growing worse at higher TUNE, see task-11's
        // report for the full table - flat, not the sharp direction this
        // task was originally filed under. Truncation alone is a small
        // sharp error - `44100/880` truncates from 50.11 to 50, ~4 cents -
        // but the loop filter's own phase lag below, unaccounted for in
        // the pre-fix loop, pulls flat and outweighs it at every note
        // measured). The classic Karplus-Strong fix (Jaffe & Smith) keeps
        // the delay line an integer length and carries the leftover
        // fraction through a first-order allpass instead.
        //
        // That alone isn't the whole loop, though: two other stages in the
        // feedback path have their own delay, and both must come out of
        // the same budget the allpass fills in, or the loop still rings
        // flat by an amount that shifts with DAMP and the note (confirmed
        // by zero-crossing and FFT measurement on the rendered tail, not
        // assumed):
        //  - [loopLp], a one-pole lowpass, has a frequency-dependent phase
        //    lag at the fundamental - real here, since DAMP can pull
        //    loopHz down close to the note itself. [filterA]/[poleR] use
        //    the same coefficient as [Dsp.OnePole.lp], so this is the
        //    filter's actual closed-form phase, not an approximation.
        //  - the two-tap average below (`0.5*(d, d-1)`) is a fixed-phase
        //    FIR, exactly 0.5 samples of delay at every frequency, kept
        //    from the pre-fix loop rather than dropped in favor of a
        //    single-tap read. Its own magnitude response (|cos(w/2)|) is
        //    near-unity at audible frequencies and isn't what's at stake;
        //    what matters is its 0.5-sample shift in the loop's total
        //    length, which - in a loop this resonant (DAMP low enough to
        //    put `fb` near 0.998, dozens of round trips before decay) -
        //    moves the comb's teeth relative to [loopLp]'s fixed rolloff
        //    and re-rolls which harmonic of the one-period noise burst
        //    rings loudest. Measured, not assumed: dropping the average
        //    for a plain single-tap read put HARP's 2nd harmonic louder
        //    than its fundamental at TUNE semitone 20, enough to fool a
        //    general-purpose pitch detector into an octave error. Keeping
        //    the average (and budgeting its exact 0.5-sample delay here)
        //    reproduces the pre-fix engine's harmonic balance.
        val filterA = 1.0 - exp(-2.0 * PI * min(loopHz, rate * 0.45f) / rate)
        val poleR = 1.0 - filterA
        val w = 2.0 * PI * freq / rate
        val filterPhase = -atan2(poleR * sin(w), 1.0 - poleR * cos(w))
        val filterDelay = -filterPhase / w

        // The stiffness allpass H(z) = (c + z⁻¹) / (1 + c·z⁻¹): its phase at the
        // fundamental is part of the loop's delay, the same way the low-pass's
        // is, so it enters the budget here and the fundamental stays put.
        val stiffDelay = if (stiffness != 0f) {
            val c = stiffness.toDouble()
            val phase = atan2(-sin(w), c + cos(w)) - atan2(-c * sin(w), 1.0 + c * cos(w))
            -phase / w
        } else 0.0

        val budgetedJawari = min(jawari, SITAR_WRAP_MAX)
        val exact = (if (jawari > 0f) (rate / freq).toDouble() / (1.0 - budgetedJawari) else (rate / freq).toDouble()) - filterDelay - stiffDelay - 0.5
        // n and frac must come from the SAME exact - splitting them and
        // then independently coercing n up (the old `.coerceAtLeast(2)`)
        // decouples them: frac keeps whatever floor(exact) - n produced,
        // which goes negative the moment exact < n. A negative frac drives
        // `a` above 1 - |a|>1 is an unconditionally unstable feedback
        // allpass, not a degraded one. Guaranteeing frac stays in [0,1) by
        // construction means never separating n from exact after this
        // require: as long as exact clears MIN_LOOP_SAMPLES, floor(exact)
        // >= MIN_LOOP_SAMPLES and frac = exact - floor(exact) is safe by
        // definition, no clamp needed. Unreachable today - the closest any
        // voice/TUNE/DAMP corner ever came to it was 175.93 samples, on the
        // since-removed KALIMBA at TUNE=1/DAMP=1 - this is a require, not
        // a silent coerce, so raising a voice root, widening
        // TUNE_SEMITONES, or adding a high-pitched voice fails loudly
        // here, naming the real cause, instead of surfacing later as a
        // distant isFinite() failure with no trail back to this loop.
        require(exact >= MIN_LOOP_SAMPLES) {
            "Pluck loop length ($exact samples, freq=$freq Hz at rate=$rate) fell " +
                "below the Karplus-Strong minimum of $MIN_LOOP_SAMPLES samples - a " +
                "note this high (or a filter delay this large) needs either a lower " +
                "root, a narrower TUNE_SEMITONES span, or this allpass revisited; " +
                "coercing the loop length up here without also correcting the " +
                "fractional remainder used to produce an unconditionally unstable " +
                "feedback allpass."
        }
        val n = floor(exact).toInt()
        val frac = (exact - n).toFloat()
        val a = (1f - frac) / (1f + frac)
        var apX1 = 0f
        var apY1 = 0f
        var stX1 = 0f
        var stY1 = 0f

        val out = FloatArray((seconds * rate).toInt().coerceAtLeast(n + 2))

        val noise = Dsp.Noise(seed)
        val pickLp = Dsp.OnePole(rate)
        val burst = FloatArray(n)
        for (i in 0 until n) burst[i] = pickLp.lp(noise.next(), pickHz)
        // Zero-mean the exciter: the loop filter passes DC untouched, so any
        // net offset in the burst survives as a sub-thump long after the
        // string content is damped away — a dark pluck decayed into a fake
        // kick until this subtraction.
        var mean = 0f
        for (v in burst) mean += v
        mean /= n
        for (i in 0 until n) burst[i] -= mean

        // Pick position (Jaffe & Smith 1983): the burst minus a copy of
        // itself delayed by `position` of one period. The comb's notches
        // fall on every harmonic k where k*position is a whole number: the
        // centre kills the even harmonics, the bridge thins the low ones.
        // The period here is the string's physical period `rate / freq`,
        // not the integer delay-line length `n` - the loop's allpass,
        // filter lag, and two-tap average make up the rest of that period
        // (see `exact` above), and a comb cut to `n` alone puts its
        // notches ~3% off the true harmonics at high DAMP (measured on
        // the since-removed KALIMBA voice: the 2nd-harmonic null missed
        // the 20 dB gate). The
        // exciter grows to n + combDelay samples, and the extra samples enter
        // the loop as INPUT through the `+=` below, not as initial state -
        // the loop's own length and tuning budget are untouched. position
        // = 0 reproduces the pre-STRIKE exciter sample for sample.
        // The coerceIn(1, n) clamp is unreachable in production: combDelay / n
        // <= ~0.5 * period/(period - lag), at most ~0.5 across the voice
        // table, and the lower bound needs position * period < 0.5 samples.
        val combDelay = if (position > 0f) (position * rate / freq).roundToInt().coerceIn(1, n) else 0
        val excLen = min(n + combDelay, out.size)
        for (i in 0 until excLen) {
            val x = if (i < n) burst[i] else 0f
            val xd = if (combDelay > 0 && i - combDelay in 0 until n) burst[i - combDelay] else 0f
            out[i] = x - xd
        }

        val loopLp = Dsp.OnePole(rate)
        val dMax = SITAR_WRAP_MAX * n
        // Attack and release far longer than one period (about 3.6 ms at
        // this note) so the envelope - and the shortening it drives -
        // changes slowly relative to the loop instead of swinging with
        // every cycle. Two prior designs drove the shortening from the
        // raw or DC-drained rail directly, both of which retain (or
        // isolate) a ripple at the carrier rate; shifting a delay line's
        // read position at the carrier's own rate is phase modulation
        // synchronous with the loop's signal, not a static nonlinearity,
        // and it measurably broke tuning and reversed the wrap's own
        // velocity and drive trends. A follower this slow cannot do that:
        // within any few periods the shortening it produces is close to
        // constant, closer to a static fractional-delay shift than a
        // modulator.
        val envAttackK = (1.0 - exp(-1.0 / (0.002 * rate))).toFloat()
        val envReleaseK = (1.0 - exp(-1.0 / (0.015 * rate))).toFloat()
        var env = 0f
        for (i in n + 1 until out.size) {
            val d = if (jawari == 0f) 0.5f * (out[i - n] + out[i - n - 1]) else {
                // The envelope tracks the rail's overall swing (both
                // directions, so it is a clean amplitude follower, not a
                // half-wave one with its own zero-crossing ripple); the
                // one-sidedness of the physical effect is carried by
                // `shorten` itself, which only ever shortens the loop,
                // never lengthens it.
                // DIAGNOSTIC ONLY (coordinator-requested, not shipped): the
                // envelope-driven shortening restored on top of Step A's
                // budgeted `exact` (which now bakes the reference-level
                // shortening into the loop length up front, the same way
                // filterDelay/stiffDelay already are), with no giveback at
                // all - the prior round's wrapMean tracking is gone
                // entirely, not just skipped, since the budget itself now
                // accounts for it and there is nothing left to give back.
                val rectified = kotlin.math.abs(out[i - 1])
                env += (if (rectified > env) envAttackK else envReleaseK) * (rectified - env)
                val shorten = min(dMax, jawari * n * env / SITAR_WRAP_REF)
                val pos = (i - n + shorten).coerceAtLeast(1f)
                val j = floor(pos).toInt()
                val f = pos - j
                // j+1 <= i-1 always: pos <= i - n + dMax = i - 0.97n, and n
                // >= MIN_LOOP_SAMPLES, so both taps of both interpolated
                // reads stay behind the write cursor.
                val a1 = out[j] * (1f - f) + out[j + 1] * f
                val a0 = out[j - 1] * (1f - f) + out[j] * f
                0.5f * (a1 + a0)
            }
            // First-order allpass: y[i] = a*(x[i] - y[i-1]) + x[i-1]. Order
            // matters here - it's the *tuned* sample that must feed both
            // the loop filter and the output, or the correction never
            // reaches the loop it was meant to fix.
            val tuned = a * (d - apY1) + apX1
            apX1 = d
            apY1 = tuned
            val stiff = if (stiffness != 0f) {
                val s = stiffness * (tuned - stY1) + stX1
                stX1 = tuned
                stY1 = s
                s
            } else tuned
            val y = loopLp.lp(stiff, loopHz)
            out[i] += fb * y
        }

        // The wrap's one-sided shortening leaves a small DC offset on
        // [out] (measured, not assumed: `the jawari leaves no offset`
        // is what catches it) - the old rail-driven mechanism had its own
        // in-loop DC blocker for the same one-sidedness, and had to budget
        // its phase delay into `exact` because it sat IN the feedback
        // path, the same way the low-pass's and stiffness allpass's own
        // delays are. This one sits OUTSIDE the loop instead: a plain
        // one-pole high-pass over the finished [out], applied once after
        // the loop above - there is no feedback path here for it to
        // disturb, so no phase delay to add to the tuning budget. 2 Hz
        // matches the old in-loop blocker's own corner: far enough below
        // every voice's lowest note that its dispersion at the
        // fundamental is negligible, and the corner's job is only how
        // fast it drains a slow offset, not where it sits relative to the
        // note. `Dsp.OnePole(rate)` - [rate] is this function's own
        // parameter, explicitly, not the class's default - the low-pass
        // it computes must scale with whichever rate `ks` is actually
        // running at (native or the 4x oversampled path); constructing it
        // with no argument would silently pin the coefficient to
        // `Dsp.RATE` regardless, and the two paths would decay at
        // different real-time rates the moment their sample rates differ
        // (`the oversampled render's decay time matches a direct
        // native-rate render` is what would catch that, for every voice).
        if (jawari > 0f) {
            val dcHp = Dsp.OnePole(rate)
            for (i in out.indices) out[i] -= dcHp.lp(out[i], 2f)
        }
        return out
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
     * [SYMPATHETIC_FEEDBACK] under a darker low-pass. Tuned the way [ks]
     * is (integer delay, fractional allpass, the low-pass's delay in the
     * budget), so the loop rings at the ratio it was given. The real tarab
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
        require(exact >= MIN_LOOP_SAMPLES) { "sympathetic loop at $hz Hz is too short ($exact samples)" }
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
