package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.pow
import kotlin.random.Random

/**
 * SILK — the Silk Road's own strings, tuned by SCALE rather than by the
 * piano (docs/superpowers/specs/2026-09-27-silk-string-engine-design.md).
 * Every voice is a recipe over [Strings]: TUNE walks whichever [SilkScales]
 * row SCALE snaps to, INFLECT bends the landed degree by up to ±50 cents,
 * and the character pair (COURSE/SLIDE for OUD, STIFF/PRESS for GUZHENG)
 * is the voice's identity.
 */
enum class SilkVoice { OUD, GUZHENG, SANTUR }

object Silk {

    /** The render follows the string's own decay between these bounds - PLUCK's own rule, unchanged (spec, "Macros"). */
    internal const val RING_FLOOR_SECONDS = 0.25f
    internal const val RING_CEILING_SECONDS = 4.0f

    /** BODY 1 is three times the string's RMS, PLUCK's own ceiling (spec, "Macros"). */
    internal const val BODY_MAX = 3f

    /** STRIKE's ends as a fraction of the string - PLUCK's own map, informed by the confirmed Arabic/Turkish scale lengths (research §2, T7) rather than any SILK-specific measurement. */
    internal const val STRIKE_BRIDGE = 0.03f
    internal const val STRIKE_CENTRE = 0.5f

    /**
     * GUZHENG's STIFF range: an inharmonicity coefficient B from 0 to
     * 1.5e-4, default about the computed B for string 21 (D2) - research
     * §3, Z-I8/Z.7 point 5. 1.5e-4 is the guqin proxy's own verbatim value
     * and the same string's own parameter table read the other way.
     */
    internal const val STIFF_B_MAX = 1.5e-4f
    internal const val STIFF_B_ANCHOR = 3.5e-5f

    /** The number of cascaded [Strings.Dispersion] sections GUZHENG's STIFF drives - research §3's own M=4 worked example. */
    internal const val STIFF_SECTIONS = 4

    /**
     * SANTUR's own sourced stiffness, fixed rather than a knob (spec,
     * "How much B": "Santur, average, 3.1e-4, confirmed (Heydarian)";
     * "SANTUR": "not on a knob"). `StringsTest`'s own measurement found
     * `Dispersion.forB` does not yet produce the stretch this B's own
     * physics predicts, at this B or GUZHENG's smaller one - see that
     * test's own KDoc; the wiring is correct regardless (its sign and
     * mechanism are proven at the `Strings` level), the audible gap is
     * a disclosed follow-on, not a reason to leave the source's own
     * number out.
     */
    internal const val SANTUR_B = 3.1e-4f

    /**
     * The nine macros: TUNE/SCALE/INFLECT/DAMP/PICK/STRIKE/BODY on every
     * voice, plus a character pair (spec, "Macros"). Every default here is
     * a placeholder awaiting the listening gate ("Phasing and gates"),
     * like PLUCK's own defaults before its own gate - a table edit, not a
     * refactor of [synthesize].
     */
    fun macrosFor(voice: SilkVoice): List<MacroSpec> = when (voice) {
        SilkVoice.OUD -> listOf(
            MacroSpec("TUNE", 0.3f),
            MacroSpec("SCALE", scaleMacroFor(SilkScales.RAST)),
            MacroSpec("INFLECT", 0.5f, neutral = 0.5f),
            MacroSpec("DAMP", 0.5f),
            MacroSpec("PICK", 0.5f),
            MacroSpec("STRIKE", 0.15f),
            MacroSpec("BODY", 0.3f),
            MacroSpec("COURSE", 0.2f),
            MacroSpec("SLIDE", 0f),
        )
        SilkVoice.GUZHENG -> listOf(
            MacroSpec("TUNE", 0.3f),
            MacroSpec("SCALE", scaleMacroFor(SilkScales.PENTATONIC)),
            MacroSpec("INFLECT", 0.5f, neutral = 0.5f),
            // Low DAMP is bright and long-ringing (spec, "GUZHENG": "bright,
            // long ring - DAMP default low").
            MacroSpec("DAMP", 0.3f),
            MacroSpec("PICK", 0.7f),
            MacroSpec("STRIKE", 0.15f),
            MacroSpec("BODY", 0.3f),
            // STIFF 0..1 maps to B 0..STIFF_B_MAX; the anchor default sits
            // at guzheng string 21's own computed B (research §3, ~3.5e-5).
            MacroSpec("STIFF", STIFF_B_ANCHOR / STIFF_B_MAX),
            MacroSpec("PRESS", 0f),
        )
        SilkVoice.SANTUR -> listOf(
            MacroSpec("TUNE", 0.3f),
            MacroSpec("SCALE", scaleMacroFor(SilkScales.SHUR)),
            MacroSpec("INFLECT", 0.5f, neutral = 0.5f),
            MacroSpec("DAMP", 0.5f),
            MacroSpec("PICK", 0.5f),
            MacroSpec("STRIKE", 0.15f),
            MacroSpec("BODY", 0.3f),
            // Every santur source says the four course loops are tuned to
            // exact unison; no spread is measured (spec, "SANTUR": "COURSE
            // defaults near 0"). Not exactly 0 - a placeholder awaiting
            // the audition gate, like every default in this file - so a
            // factory SANTUR pad still carries a trace of the "prompt
            // then aftersound" coupled-string character the spec asks for.
            MacroSpec("COURSE", 0.05f),
            MacroSpec("WASH", 0.2f),
        )
    }

    fun defaults(voice: SilkVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /**
     * SCRAMBLE near a preset; see [Thump.scramble] (docs/SYNTH_UPGRADE.md,
     * U2). SilkPresets lands with registration (Phase 1b, Task 8); until
     * then every scramble rolls from the voice's own defaults, same as
     * `near == null, temperature >= 1` would on any other engine.
     *
     * INFLECT is excluded from the roll and pinned back to 0.5 (spec,
     * "INFLECT": "SCRAMBLE leaves it at 0.5" - a dice roll never detunes a
     * pad; INFLECT is a deliberate move, like KEY on the kit screen).
     */
    fun scramble(voice: SilkVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        val rolled = Dsp.scrambleNear(seed, temperature, random)
        return rolled + ("INFLECT" to 0.5f)
    }

    /** The voice's root - the bottom of TUNE's span, one full period of SCALE below its own centre. */
    internal fun rootFor(voice: SilkVoice): Float = when (voice) {
        SilkVoice.OUD -> 65.41f // C2, the confirmed Arabic oud tuning's lowest course (research §2, T1)
        SilkVoice.GUZHENG -> 73.41f // D2, string 21 of 21 - the confirmed range's own bottom (research §3, Z-U1)
        // E3, the confirmed 9-bridge santur's own bottom (research §4;
        // spec, "SANTUR": "the 9-bridge santur runs E3-F6... Root E3").
        // TUNE's own two-period span (shared SILK infrastructure since
        // Phase 1b) reaches E3-E5 on SHUR, not the full E3-F6 range a
        // real santur's 18 courses span - the same sub-range every SILK
        // voice's TUNE already accepts (OUD's own neck exceeds two
        // periods of RAST too). Widening that shared convention for one
        // voice is bigger than this task; a named follow-on if the
        // listening gate finds it too narrow, not solved here.
        SilkVoice.SANTUR -> 164.81f
    }

    /** The macro value that snaps [SilkScales.snap] to exactly [scale] - each voice's own default row. */
    private fun scaleMacroFor(scale: SilkScales.Scale): Float {
        val idx = SilkScales.TABLE.indexOf(scale)
        return idx / (SilkScales.TABLE.size - 1).toFloat()
    }

    /** The snapped note frequency TUNE/SCALE/INFLECT land on for [voice]. */
    internal fun frequencyFor(voice: SilkVoice, tune: Float, scaleMacro: Float, inflect: Float = 0.5f): Float =
        SilkScales.frequencyFor(rootFor(voice), SilkScales.snap(scaleMacro), tune, inflect)

    /**
     * The frequency one [SilkScales] degree below wherever [tune] (in
     * [scale]) lands - OUD's SLIDE glides in from here. Clamped at the
     * root: a slide already sitting on SCALE's own bottom degree has
     * nowhere lower to start from and simply doesn't slide.
     */
    internal fun oneDegreeBelow(root: Float, scale: SilkScales.Scale, tune: Float): Float {
        val size = scale.cents.size
        val degree = Math.round(tune.coerceIn(0f, 1f) * (2 * size))
        val prevTune = (degree - 1).coerceAtLeast(0) / (2f * size)
        return SilkScales.frequencyFor(root, scale, prevTune)
    }

    internal fun synthesize(voice: SilkVoice, macros: Map<String, Float>, rate: Int, velocity: Float = 1f): FloatArray {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val scale = SilkScales.snap(m.getValue("SCALE"))
        val root = rootFor(voice)
        val freq = SilkScales.frequencyFor(root, scale, m.getValue("TUNE"), m.getValue("INFLECT"))
        val damp = m.getValue("DAMP")
        val pick = m.getValue("PICK")
        val body = Dsp.lin(m.getValue("BODY"), 0f, BODY_MAX)
        val position = Dsp.expMap(m.getValue("STRIKE"), STRIKE_BRIDGE, STRIKE_CENTRE)

        return when (voice) {
            SilkVoice.OUD -> {
                val slideFrom = if (m.getValue("SLIDE") > 0.01f) oneDegreeBelow(root, scale, m.getValue("TUNE")) else freq
                val out = oud(freq, slideFrom, damp, pick, position, m.getValue("COURSE"), m.getValue("SLIDE"), rate, velocity)
                trimToDecay(withBody(out, voice, body, rate), rate)
            }
            SilkVoice.GUZHENG -> {
                val out = guzheng(freq, damp, pick, position, m.getValue("STIFF"), m.getValue("PRESS"), rate, velocity)
                trimToDecay(withBody(out, voice, body, rate), rate)
            }
            SilkVoice.SANTUR -> {
                val out = santur(freq, damp, pick, position, m.getValue("COURSE"), rate, velocity)
                // architecture diagram (spec): course -> body (Modes) ->
                // [sympathetic bank] - WASH chains after BODY, not before.
                val withBody = withBody(out, voice, body, rate)
                trimToDecay(withWash(withBody, scale, root, m.getValue("WASH"), rate), rate)
            }
        }
    }

    /**
     * OUD: two loops per note (spec, "OUD" - `Course(N = 2)`), a pick burst
     * near the bridge, and (when [slideFrom] differs from [freq]) both
     * loops gliding in from below on one shared pitch envelope.
     */
    private fun oud(freq: Float, slideFrom: Float, damp: Float, pick: Float, position: Float, course: Float, slide: Float, rate: Int, velocity: Float): FloatArray {
        val loopHz = 5200f
        val pickLo = 1200f
        val pickHi = 7500f
        val ring = 1.2f
        val damping = Strings.damping(damp, loopHz)
        val pickHz = Dsp.expMap(pick, pickLo, pickHi)
        val seconds = Dsp.expMap(1f - damp, 0.3f * ring, RING_CEILING_SECONDS)
            .coerceIn(RING_FLOOR_SECONDS, RING_CEILING_SECONDS)

        val detunes = Strings.courseDetuneCents(Dsp.seedFor("SILK", SilkVoice.OUD, freq), count = 2, spread = course)
        val loops = detunes.mapIndexed { k, cents ->
            val detunedTarget = freq * 2f.pow(cents / 1200f)
            val detunedStart = slideFrom * 2f.pow(cents / 1200f)
            val seed = Dsp.seedFor("SILK", SilkVoice.OUD, "COURSE", k, freq)
            // "The pair gets slightly unequal feedback so it decays
            // unevenly - the 'prompt then aftersound' of coupled strings,
            // cheaply" (spec, "OUD") - [Strings.COURSE_FB_STEP], the same
            // step [Strings.course] uses (see that constant's own KDoc for
            // why it's kept smaller than its original value: a real
            // regression this voice's own tuning test caught in SILK
            // Phase 1b, at this voice's own render path, though a later
            // direct probe of `Strings.course` itself did not reproduce
            // it - the smaller step costs nothing either way).
            val fbK = (damping.fb * (1f - Strings.COURSE_FB_STEP * k)).coerceIn(0f, 0.999f)
            oudCourseLoop(detunedStart, detunedTarget, seconds, Strings.Damping(damping.loopHz, fbK), pickHz, position, slide, rate, seed)
        }
        val out = FloatArray(loops.maxOf { it.size })
        for (loop in loops) for (i in loop.indices) out[i] += loop[i]
        return out
    }

    /**
     * One course loop, gliding from [startFreq] to [targetFreq] over the
     * first [slideAmount]-scaled fraction of the note if they differ, then
     * held. [Strings.Loop] is built at [startFreq] - the lower of the two,
     * so it always has room to [Strings.Loop.retune] up toward the target
     * (see [Strings.Loop.retune]'s own contract). At `slideAmount` 0,
     * [startFreq] and [targetFreq] are the same value and no retuning
     * happens at all.
     */
    private fun oudCourseLoop(startFreq: Float, targetFreq: Float, seconds: Float, damping: Strings.Damping, pickHz: Float, position: Float, slideAmount: Float, rate: Int, seed: Int): FloatArray {
        val t0 = Strings.tune(startFreq, damping.loopHz, rate)
        val out = FloatArray((seconds * rate).toInt().coerceAtLeast(t0.n + 2))
        val exc = Strings.pluckExciter(t0.n, startFreq, pickHz, position, seed, rate, out.size)
        val risha = rishaTick(Dsp.seedFor(seed, "RISHA"), rate)
        for (i in risha.indices) if (i < exc.size) exc[i] += risha[i]
        val loop = Strings.Loop(t0.n, t0.a, damping.fb, damping.loopHz, rate)

        val gliding = slideAmount > 0.01f && startFreq != targetFreq
        // "A slow slide" is unsourced (spec, "OUD": no source measures a
        // slide's rise time) - 0.35 s at SLIDE 1 is a shape, awaiting the
        // audition gate, like PLUCK's own placeholder constants.
        val slideSamples = if (gliding) (Dsp.lin(slideAmount, 0.05f, 0.35f) * rate).toInt().coerceAtLeast(1) else 0
        // "The finger's extra damping is modelled as a slightly lower
        // loop gain for the slide's duration" (spec, "OUD", Erkut §2) -
        // dropped for the glide's own span and restored once it settles.
        if (gliding) loop.gain(SLIDE_GAIN_SCALE)

        for (i in out.indices) {
            if (gliding && i in 1..slideSamples) {
                val progress = i.toFloat() / slideSamples
                loop.retune(startFreq * (targetFreq / startFreq).pow(progress))
            } else if (gliding && i == slideSamples + 1) {
                loop.gain(1f)
            }
            out[i] = loop.next(if (i < exc.size) exc[i] else 0f)
        }
        return out
    }

    /** Unsourced (spec, "OUD" gives no measurement of the risha's own rise time or spectral shape) - a shape constant awaiting the audition gate, like this file's other placeholders. */
    private const val RISHA_SECONDS = 0.004f
    private const val RISHA_HP_HZ = 3000f
    private const val RISHA_GAIN = 0.12f

    /** OUD's SLIDE models finger damping as a lower loop gain for the slide's own span - see [oudCourseLoop]; unsourced in its exact ratio, a shape constant like [RISHA_GAIN]. */
    private const val SLIDE_GAIN_SCALE = 0.97f

    /**
     * The very short high-passed tick alongside the pick burst that
     * stands for the risha (spec, "OUD": "the pick burst near the bridge
     * (STRIKE low), plus a very short high-passed tick for the risha").
     * A cheap one-pole high-pass - noise minus its own low-pass, the same
     * shape [Strings.rawBurst]'s own low-pass builds the pick burst from
     * - rather than a dedicated filter class; zero-meaned for the same
     * reason [Strings.rawBurst] is (a DC-free exciter, or the loop's own
     * filter passes a fake sub-thump through untouched).
     */
    private fun rishaTick(seed: Int, rate: Int): FloatArray {
        val len = (RISHA_SECONDS * rate).toInt().coerceAtLeast(1)
        val noise = Dsp.Noise(seed)
        val lp = Dsp.OnePole(rate)
        val tick = FloatArray(len)
        for (i in 0 until len) {
            val n = noise.next()
            tick[i] = (n - lp.lp(n, RISHA_HP_HZ)) * RISHA_GAIN
        }
        var mean = 0f
        for (v in tick) mean += v
        mean /= len
        for (i in tick.indices) tick[i] -= mean
        return tick
    }

    /**
     * GUZHENG: one bright, long-ringing string per note - STIFF's
     * dispersion cascade, PRESS's pressed bend up, a fingerpicked
     * excitation (PICK high, STRIKE low).
     */
    private fun guzheng(freq: Float, damp: Float, pick: Float, position: Float, stiff: Float, press: Float, rate: Int, velocity: Float): FloatArray {
        val loopHz = 6500f
        val pickLo = 2000f
        val pickHi = 10_000f
        val ring = 0.3f
        val damping = Strings.damping(damp, loopHz)
        val pickHz = Dsp.expMap(pick, pickLo, pickHi)
        val seconds = Dsp.expMap(1f - damp, 0.3f * ring, RING_CEILING_SECONDS)
            .coerceIn(RING_FLOOR_SECONDS, RING_CEILING_SECONDS)

        // A finding from this task, not a claim the spec made: at
        // guzheng-scale B, Strings.Dispersion.forB's frequency-independent
        // coefficient (Task 3's own simplification of the corrected
        // Rauhala design, which needs no re-derivation per note) produces
        // a partial stretch on the order of hundredths of a cent - three
        // orders of magnitude under the ~13-56 cent stretch the physics
        // itself (fk = k*f0*sqrt(1+B*k^2)) predicts at k=20. STIFF's wiring
        // is correct and its direction is proven at the Strings level
        // (StringsTest's own probe, with a deliberately stronger
        // coefficient); at GUZHENG's own sourced B it is not yet audible.
        // Closing that gap needs the per-note re-derivation the papers'
        // own piano application actually does, which this phase did not
        // reverse-engineer with confidence - a listening-gate / follow-on
        // question, not a guess to paper over here.
        val b = Dsp.lin(stiff, 0f, STIFF_B_MAX)
        val dispersion = Strings.Dispersion.forB(b, STIFF_SECTIONS)

        // PRESS: four snapped stops, 0/100/200/300 cents up from the
        // plucked degree (spec, "GUZHENG": the note reaches fa and ti this
        // way, off the pentatonic table on purpose).
        val pressSteps = Math.round(press.coerceIn(0f, 1f) * 3f)
        val pressed = freq * 2f.pow(pressSteps * 100f / 1200f)

        val seed = Dsp.seedFor("SILK", SilkVoice.GUZHENG, freq)
        return guzhengLoop(freq, pressed, seconds, damping, pickHz, position, dispersion, rate, seed)
    }

    /**
     * One string, built at [plucked] - the lower of the two frequencies,
     * so it always has room to [Strings.Loop.retune] up toward [pressed]
     * (see that contract). At [pressed] == [plucked] (PRESS 0) no
     * retuning happens at all.
     */
    private fun guzhengLoop(plucked: Float, pressed: Float, seconds: Float, damping: Strings.Damping, pickHz: Float, position: Float, dispersion: Strings.Dispersion?, rate: Int, seed: Int): FloatArray {
        val t0 = Strings.tune(plucked, damping.loopHz, rate, dispersion = dispersion)
        val out = FloatArray((seconds * rate).toInt().coerceAtLeast(t0.n + 2))
        val exc = Strings.pluckExciter(t0.n, plucked, pickHz, position, seed, rate, out.size)
        val loop = Strings.Loop(t0.n, t0.a, damping.fb, damping.loopHz, rate, dispersion = dispersion)

        val pressing = pressed != plucked
        // The press's own rise time is unsourced (spec, "GUZHENG": "the
        // bend's rise time is shape: no source measures it") - a shape
        // constant awaiting the audition gate, like PLUCK's own placeholders.
        val riseSamples = if (pressing) (0.12f * rate).toInt().coerceAtLeast(1) else 0

        for (i in out.indices) {
            if (pressing && i in 1..riseSamples) {
                val progress = i.toFloat() / riseSamples
                loop.retune(plucked * (pressed / plucked).pow(progress))
            }
            out[i] = loop.next(if (i < exc.size) exc[i] else 0f)
        }
        return out
    }

    /**
     * SANTUR: four loops per note (spec, "Architecture" diagram; "SANTUR":
     * `Course(N = 4)`), driven by a mallet rather than a pick, carrying
     * the instrument's own sourced (fixed, not knobbed) stiffness
     * dispersion ([SANTUR_B]) on every loop.
     */
    private fun santur(freq: Float, damp: Float, pick: Float, position: Float, course: Float, rate: Int, velocity: Float): FloatArray {
        val loopHz = 6000f
        // Askenfelt & Jansson's piano-hammer contact-time proxy the spec
        // itself cites (spec, "SANTUR": "no mezrab is measured; the piano
        // proxy runs from about 4 ms in the bass to under 1 ms in the
        // treble") read directly as mallet.hardness = 1 / contactTime:
        // 4 ms -> 250 Hz (wide, dark), under 1 ms -> 1000 Hz (narrow,
        // bright) - sourced anchors for the range's own two ends, not a
        // guessed span.
        val pickLo = 250f
        val pickHi = 1000f
        val ring = 1.0f
        val damping = Strings.damping(damp, loopHz)
        val pickHz = Dsp.expMap(pick, pickLo, pickHi)
        val seconds = Dsp.expMap(1f - damp, 0.3f * ring, RING_CEILING_SECONDS)
            .coerceIn(RING_FLOOR_SECONDS, RING_CEILING_SECONDS)

        val dispersion = Strings.Dispersion.forB(SANTUR_B, STIFF_SECTIONS)
        val seed = Dsp.seedFor("SILK", SilkVoice.SANTUR, freq)
        return Strings.course(
            freq, seconds, damping, pickHz, seed, rate,
            count = 4, spread = course, position = position,
            dispersion = dispersion, exciter = Strings::mallet,
        )
    }

    private const val WASH_T60_FLOOR = 0.5f
    private const val WASH_T60_CEILING = 3f

    /**
     * SANTUR's WASH, applied after [withBody] (architecture diagram:
     * `body (Modes) -> [sympathetic bank]`): [washModesFor] built fresh
     * from [scale]/[root] every call - SCALE-following, not a fixed
     * table - rung via [Strings.bodyRing], the same function [withBody]
     * calls, a second time with a dynamic one. WASH drives both the
     * bank's level ([Strings.bodyRing]'s own `amount`) and its t60
     * together (spec, "SANTUR": "WASH sets the bank's level and its t60
     * together"); the "a few seconds" range is unsourced ("no santur t60
     * is measured") - shape, like this file's other placeholder ranges.
     * [root]/[scale] carry no INFLECT bend - the un-inflected table the
     * spec asks for ("WASH's sympathetic bank stays on the *un-inflected*
     * table - the santur's strings are tuned to the dastgah").
     */
    private fun withWash(string: FloatArray, scale: SilkScales.Scale, root: Float, wash: Float, rate: Int): FloatArray {
        val t60 = Dsp.lin(wash, WASH_T60_FLOOR, WASH_T60_CEILING)
        val modes = washModesFor(root, scale, gain = 1f, t60 = t60)
        return Strings.bodyRing(string, modes, wash, rate, RING_CEILING_SECONDS)
    }

    /** The fixed body of each voice - see [Pluck.bodyFor]'s own KDoc for the drive's reasoning, shared via [Strings.bodyRing]. */
    private fun bodyFor(voice: SilkVoice): List<Modes.Mode> = when (voice) {
        // A Turkish ud, strung and radiating - the only two modes measured
        // (Erkut 2002, research §2). Neither source says which is the air
        // mode; gains are shape. t60 = 2.2*Q/f.
        SilkVoice.OUD -> listOf(
            Modes.fixed(113f, 1.0f, 0.193f), // Q 9.91 - measured
            Modes.fixed(182f, 0.8f, 0.122f), // Q 10.06 - measured
        )
        // A complete guzheng, strung and radiating - Deng 2016's five
        // measured modes (research §3, Z-B7). No source gives a Q for any
        // guzheng mode, so every gain and t60 here is shape.
        SilkVoice.GUZHENG -> listOf(
            Modes.fixed(83.69f, 1.0f, 0.35f),
            Modes.fixed(138.13f, 0.85f, 0.30f),
            Modes.fixed(172.50f, 0.7f, 0.26f),
            Modes.fixed(197.19f, 0.6f, 0.22f),
            Modes.fixed(275.00f, 0.45f, 0.18f),
        )
        // No santur body mode is measured at all (spec, "SANTUR": "no
        // santur body mode is measured; BODY is a neutral shape and says
        // so"). One placeholder mode, not an empty table, so BODY still
        // does something at BODY > 0 rather than reading as a dead knob -
        // every number here is shape, awaiting the audition gate.
        SilkVoice.SANTUR -> listOf(
            Modes.fixed(200f, 0.5f, 0.3f),
        )
    }

    private fun withBody(string: FloatArray, voice: SilkVoice, amount: Float, rate: Int): FloatArray =
        Strings.bodyRing(string, bodyFor(voice), amount, rate, RING_CEILING_SECONDS)

    /**
     * SANTUR's WASH: unlike every other voice's fixed [bodyFor] table
     * (one measured instrument, baked in), this bank is *dynamic* - tuned
     * to whichever SCALE the pad is currently on, across three octaves
     * (spec, "SANTUR": "a bank of resonators... tuned to the current
     * SCALE's degrees across three octaves... The bank follows SCALE, so
     * a SHUR santur rings in SHUR").
     *
     * [scale.cents] already starts at 0 (every shipped row's own
     * invariant - see `SilkScalesTest`), so walking it across three whole
     * octave transpositions already reaches every degree in the span
     * exactly once: octave 0's own degree 0 is [root] itself, octave 1's
     * degree 0 is one octave up, and so on - nothing here separately adds
     * "the top-of-period degree", which would land on the exact same
     * frequency as the next octave's own degree 0 and double a resonator
     * there instead of representing one degree per mode (the interaction
     * the SILK Phase 2 plan's own review round caught). The span's own
     * top note - three literal octaves above [root] - is added once,
     * separately, closing the range.
     *
     * "Octaves" is read literally (1200 cents), not as three periods of
     * [scale] itself: for [SilkScales.BOHLEN_PIERCE] specifically (period
     * ~1901.955 cents, a tritave, not an octave) this means its own
     * degrees do not land on this bank's own three-octave boundary - an
     * accepted consequence of the spec's own wording, not a bug, should a
     * future voice ever pair BOHLEN_PIERCE with a WASH-style bank.
     *
     * [gain] is uniform across every mode - no source weights a santur's
     * sympathetic strings against each other, so nothing here invents a
     * curve; overall level is [Strings.bodyRing]'s own `amount` argument,
     * which WASH also drives. [t60] is likewise shared by every mode -
     * WASH's own single knob sets the whole bank's decay together (spec,
     * "SANTUR": "WASH sets the bank's level and its t60 together").
     */
    internal fun washModesFor(root: Float, scale: SilkScales.Scale, gain: Float, t60: Float): List<Modes.Mode> {
        val modes = mutableListOf<Modes.Mode>()
        for (octave in 0..2) {
            for (cents in scale.cents) {
                val hz = root * 2f.pow((cents + 1200f * octave) / 1200f)
                modes.add(Modes.fixed(hz, gain, t60))
            }
        }
        modes.add(Modes.fixed(root * 2f.pow(3f), gain, t60)) // the span's own top: 3 literal octaves above root
        return modes
    }

    private fun trimToDecay(buf: FloatArray, rate: Int): FloatArray =
        Strings.trimToDecay(buf, rate, RING_FLOOR_SECONDS, RING_CEILING_SECONDS)

    fun render(voice: SilkVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip =
        renderWith(voice, macros, velocity)

    /** U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE and decimate, same as every other engine including PLUCK, whose loop this shares. */
    internal fun renderWith(voice: SilkVoice, macros: Map<String, Float>, velocity: Float = 1f): Snip {
        val renderRate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, renderRate, velocity)
        val out = Dsp.decimate(raw, Dsp.RATE)
        Dsp.levelTo(out, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }
}
