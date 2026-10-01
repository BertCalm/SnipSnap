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
enum class SilkVoice { OUD, GUZHENG, SANTUR, SHAMISEN }

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
     * SHAMISEN's own fixed stiffness, unconditional like [SANTUR_B] but
     * with no source at all behind its magnitude - no B is measured for
     * silk or nylon shamisen strings anywhere in the research (the
     * guqin/guzheng proxies, [STIFF_B_ANCHOR] and the guqin's own 9e-5, are
     * both wound or nylon-over-steel strings, structurally stiffer than a
     * plain silk or nylon shamisen string). Its only job is sourced,
     * though: van Walstijn, Bridges & Mehes's tanpura-model finding that
     * the sawari buzz's own "precursor" disappears when a string has zero
     * stiffness (research §1, T8 - "with EI = 0 the precursor
     * disappears"), so SAWARI's reuse of [Strings.Loop]'s `jawari` needs
     * *some* nonzero dispersion to have anything to buzz off of. This
     * constant claims that precursor exists, nothing about its stretch
     * magnitude - smaller than [STIFF_B_ANCHOR] on purpose, since nothing
     * here backs a guzheng-scale value.
     */
    internal const val SHAMISEN_B = 1e-5f

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
            // The Phase 4 audition's own first finding: at COURSE 0.2
            // (+-5 cents, spec's own unsourced shape default) OUD and
            // GUZHENG's defaults read as the same instrument - neither
            // voice's identity mechanism is doing anything at its own
            // default (SLIDE is also 0 here). Unlike SLIDE/PRESS, which are
            // performance gestures a real player doesn't apply to every
            // note, a course's paired strings are *always* strung that way
            // - raising the spread here is honest to the instrument, not a
            // faked "liveliness". +-11.25 cents sits inside the range
            // real paired-string instruments (12-string guitar, mandolin,
            // oud course) actually drift by ear, strong enough to beat
            // audibly rather than just thicken the pitch.
            MacroSpec("COURSE", 0.45f),
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
            // Raised from OUD's own 0.3 (Phase 4 audition finding, see
            // OUD's COURSE comment above): STIFF's inharmonicity is
            // disclosed elsewhere in this file as inaudible at guzheng's
            // own sourced B, so it carries none of the "this is a guzheng,
            // not an oud" work at default. The body table is the other
            // always-present, non-gestural difference between the two
            // voices (GUZHENG's own 5 measured modes down to 83.7 Hz vs
            // OUD's sparser 2), so this brings more of that richer body
            // through at rest, widening the gap against OUD's own
            // unchanged, sparser-bodied default rather than touching either
            // voice's sourced PICK/DAMP values.
            MacroSpec("BODY", 0.5f),
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
            // Raised from 0.2 (Phase 4 audition finding: measured directly,
            // the default render ran only 0.96s against WASH 0's own 0.71s -
            // the bank was real but essentially inaudible at its own
            // default, while WASH 1 measured a genuine, smoothly decaying
            // 2.8s tail. 0.45 pushes both of WASH's own levers at once -
            // Dsp.lin's own t60 (0.5-3s) and bodyRing's own mix amount -
            // enough that a factory pad already carries an audible "prompt
            // then aftersound", not just the shipped-at-max version of it.
            MacroSpec("WASH", 0.45f),
        )
        SilkVoice.SHAMISEN -> listOf(
            MacroSpec("TUNE", 0.3f),
            MacroSpec("SCALE", scaleMacroFor(SilkScales.MIYAKO_BUSHI)),
            MacroSpec("INFLECT", 0.5f, neutral = 0.5f),
            MacroSpec("DAMP", 0.5f),
            // "PICK high" (spec, "SHAMISEN") - mirrors GUZHENG's own bright default.
            MacroSpec("PICK", 0.7f),
            // Sourced, not shape: "the bachi meets the string at about 1/6
            // of its length ... STRIKE's default is 1/6" (research §1, A5).
            MacroSpec("STRIKE", 1f / 6f),
            MacroSpec("BODY", 0.3f),
            // Raised from 0 (Phase 4 audition finding). Unlike OUD's SLIDE
            // or GUZHENG's PRESS, SAWARI is not a performance gesture - a
            // real sawari bridge buzzes on every note once engaged, the
            // same always-present-trait case as SANTUR's COURSE and this
            // voice's own SLAP, so defaulting it on is honest to the
            // instrument, not a faked liveliness. 0.35 is a modest trace,
            // not the ceiling: that ceiling is itself weak (measured:
            // SAWARI 1 adds only ~1.1% high-frequency energy over SAWARI 0's
            // ~0.7%, in the same spectral-energy window), because jawariP0
            // (see Strings.burstPeak's own KDoc) is deliberately the
            // excitation's own full-swing peak, so the fold-back fades
            // quadratically weaker as the note decays below it - the exact
            // mechanism SITAR already ships, reused verbatim, not a
            // SHAMISEN-specific miscalibration. Raising the ceiling itself
            // would mean recalibrating that shared reference against a
            // second voice's own PICK/loop parameters with no measurement
            // to aim at - a follow-on question, not solved here.
            MacroSpec("SAWARI", 0.35f),
            // A nonzero shape default, the same reasoning SANTUR's own
            // nonzero COURSE default uses - a factory pad still carries a
            // trace of the skin strike rather than reading as a dead knob.
            MacroSpec("SLAP", 0.2f),
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
        // C3, the nearest named note to nagauta honchoshi's own open
        // string 1 (research §1, P1: ~131 Hz; corroborated by P2's B2 and
        // P6's inferred ~125 Hz).
        SilkVoice.SHAMISEN -> 130.81f
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
            SilkVoice.SHAMISEN -> {
                val out = shamisen(freq, pick, damp, position, m.getValue("SAWARI"), rate, velocity)
                val withBody = withBody(out, voice, body, rate)
                // SLAP is a separate radiating path (the bachi striking the
                // skin directly, not the body frame's own response to the
                // string's bridge force) - added after BODY, onto the same
                // carrier, rather than re-processed through the body's own
                // resonators.
                val seed = Dsp.seedFor("SILK", SilkVoice.SHAMISEN, "SLAP", freq)
                trimToDecay(withSlap(withBody, m.getValue("SLAP"), rate, seed), rate)
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

    /** G1: open strings start about 3.5 % sharp (research §1) - PICK 1's own ceiling for [shamisenLoop]'s built-in glide. */
    private const val SHAMISEN_GLIDE_SHARP_RATIO = 1.035f

    /** PICK 0: no stretch, no glide - a softer stroke stretches the string less, down to none at all (spec's own causal claim; no source gives this floor's exact value). */
    private const val SHAMISEN_GLIDE_FLOOR_RATIO = 1.0f

    /**
     * G1's own sourced fast transient (3.5 % -> 1.5 % within 100 ms). The
     * SILK Phase 3 plan resolves a tension the source itself doesn't
     * settle: G1 also describes the pitch drifting further, toward a
     * long-term ~0.8 % (~14 cents) residual - past the 5-cent bound every
     * other SILK tuning test enforces. Rather than chase that asymptote
     * into a value that would fail every other voice's own tuning
     * standard, this glide models only the sourced 100 ms fast transient
     * and settles fully to the plucked degree (0 %, not 0.8 %) by its own
     * end - a disclosed simplification, not a silent contradiction of G1.
     * `SilkTest`'s own SHAMISEN tuning test measures from 150 ms on (spec,
     * "Testing", item 1), 50 ms of margin past this window's own end.
     */
    private const val SHAMISEN_GLIDE_SECONDS = 0.1f

    /**
     * SHAMISEN: one string, its own built-in pitch glide (not a macro - it
     * fires on every note, scaled by [pick]) plus SAWARI's reused
     * [jawari] and the voice's own small fixed [dispersion] (Task 1).
     * Unlike [oudCourseLoop]'s SLIDE and [guzhengLoop]'s PRESS, this glide
     * runs high -> low (G1: "starts sharp ... falls"), the opposite
     * direction from every other [Strings.Loop.retune] caller - so the
     * loop is built at [freq] itself (the settled, lower frequency, the
     * one [Strings.Loop]'s own `maxN` must be sized from) and immediately
     * retuned *up* to the sharp starting point, before the render loop's
     * first sample, then glided back down over [SHAMISEN_GLIDE_SECONDS].
     * A higher frequency always needs a *shorter* loop than the one
     * `maxN` was sized for, so the initial retune never trips
     * [Strings.Loop.retune]'s own `t.n <= maxN` require.
     */
    internal fun shamisenLoop(freq: Float, pick: Float, seconds: Float, damping: Strings.Damping, pickHz: Float, position: Float, jawari: Float, dispersion: Strings.Dispersion?, rate: Int, seed: Int): FloatArray {
        // [jawari] must be passed here too, not left at tune()'s own
        // default of 0 - Loop.retune's own internal tune() call always
        // uses the Loop's *stored* jawari (this same real value), and the
        // DC blocker tune() only budgets for when jawari > 0 shifts the
        // sample count (research: the blocker leads at the fundamental, a
        // negative phase delay, so it needs *more* samples budgeted, not
        // fewer). Build [t0] with jawari at 0 and this glide's own final
        // retune(freq) - re-deriving the budget with the loop's real,
        // nonzero jawari - can come out needing one more sample than
        // [maxN] had room for: caught by ordinary macro fuzzing, not a
        // theoretical concern.
        val t0 = Strings.tune(freq, damping.loopHz, rate, jawari = jawari, dispersion = dispersion)
        val out = FloatArray((seconds * rate).toInt().coerceAtLeast(t0.n + 2))
        val exc = Strings.pluckExciter(t0.n, freq, pickHz, position, seed, rate, out.size)
        val jawariP0 = if (jawari > 0f) Strings.burstPeak(t0.n, pickHz, seed, rate) else 1e-6f
        val loop = Strings.Loop(t0.n, t0.a, damping.fb, damping.loopHz, rate, jawari = jawari, jawariP0 = jawariP0, dispersion = dispersion)

        val sharpFreq = freq * Dsp.lin(pick, SHAMISEN_GLIDE_FLOOR_RATIO, SHAMISEN_GLIDE_SHARP_RATIO)
        loop.retune(sharpFreq)
        val glideSamples = (SHAMISEN_GLIDE_SECONDS * rate).toInt().coerceAtLeast(1)

        for (i in out.indices) {
            if (i in 1..glideSamples) {
                val progress = i.toFloat() / glideSamples
                // Clamped at [freq] itself as a second, cheap safety net:
                // pow()'s own floating-point error could still undershoot
                // freq by a hair right at progress=1, and freq's own
                // required loop length is exactly [maxN] now that the
                // budget above is consistent - zero headroom either way.
                val target = maxOf(freq, sharpFreq * (freq / sharpFreq).pow(progress))
                loop.retune(target)
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

    // Shape, informed by the qualitative facts research §1 does give: "PICK
    // high" and a "bright, short ring" (D1: falls 20 dB in ~270 ms, "half
    // or less" of a guitar's - the shortest sourced decay of any SILK
    // voice). No source fixes the loop low-pass corner or the pick-burst
    // brightness range in Hz, the same gap every other voice's own
    // loopHz/pickLo/pickHi already carries.
    private const val SHAMISEN_LOOP_HZ = 7000f
    private const val SHAMISEN_PICK_LO = 2500f
    private const val SHAMISEN_PICK_HI = 12_000f
    private const val SHAMISEN_RING = 0.15f

    /**
     * SHAMISEN: one string (spec, "SHAMISEN": "one string per note"),
     * [shamisenLoop]'s own built-in glide and SAWARI reuse doing the rest.
     */
    private fun shamisen(freq: Float, pick: Float, damp: Float, position: Float, sawari: Float, rate: Int, velocity: Float): FloatArray {
        val damping = Strings.damping(damp, SHAMISEN_LOOP_HZ)
        val pickHz = Dsp.expMap(pick, SHAMISEN_PICK_LO, SHAMISEN_PICK_HI)
        val seconds = Dsp.expMap(1f - damp, 0.3f * SHAMISEN_RING, RING_CEILING_SECONDS)
            .coerceIn(RING_FLOOR_SECONDS, RING_CEILING_SECONDS)
        val dispersion = Strings.Dispersion.forB(SHAMISEN_B, STIFF_SECTIONS)
        val seed = Dsp.seedFor("SILK", SilkVoice.SHAMISEN, freq)
        return shamisenLoop(freq, pick, seconds, damping, pickHz, position, sawari, dispersion, rate, seed)
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
        // The bare dō frame (573.2/630.8 Hz) and the whole instrument's own
        // neck-bending modes (67.55/85.38 Hz), all four measured (research
        // §1, B1-B4). `t60 = 2.2*Q/f`, `Q = 1/(2*zeta)`, the same formula
        // OUD's own table above uses - 1.76/0.55/0.32/0.20 s respectively
        // for 67.55/85.38/573.2/630.8 Hz.
        //
        // Gains are the one place this table reads differently from every
        // other voice's own: OUD's and GUZHENG's lowest sourced mode is
        // their loudest (nothing in their own research says otherwise),
        // but SHAMISEN's own radiated spectrum "peaks near 700 Hz and
        // falls about 25 dB an octave below it" (research §1, R1) - so the
        // frame modes, which sit close to that peak, carry meaningfully
        // more gain here than the neck modes, which the same rolloff (over
        // three octaves below 700 Hz) would put far down in the real
        // instrument's own radiated balance. These gains are still shape,
        // not the literal computed rolloff (which would put the neck
        // modes near-silent and defeat the point of a placeholder table) -
        // they encode the qualitative fact (frame louder than neck), not
        // the exact dB.
        SilkVoice.SHAMISEN -> listOf(
            Modes.fixed(67.55f, 0.15f, 1.76f),
            Modes.fixed(85.38f, 0.25f, 0.55f),
            Modes.fixed(573.2f, 0.8f, 0.32f),
            Modes.fixed(630.8f, 1.0f, 0.20f),
        )
    }

    private fun withBody(string: FloatArray, voice: SilkVoice, amount: Float, rate: Int): FloatArray =
        Strings.bodyRing(string, bodyFor(voice), amount, rate, RING_CEILING_SECONDS)

    /**
     * SLAP's own skin table - separate from [bodyFor], since SLAP drives a
     * synthetic burst rather than the string's own first difference (see
     * [withSlap]). The skin's own fundamental mode is genuinely unresolved:
     * the same lab's two mounted-skin measurements disagree by an order of
     * magnitude (research §1, B5 ~151.7 Hz vs B6 ~766.4 Hz, N.3 verifier
     * note i) - rather than pick one and call it settled, this ships one
     * mode near the sourced *radiated* peak instead (700 Hz, R1/R2 - two
     * independent, anechoic-adjacent labs), explicitly a stand-in for the
     * unresolved skin mode, not a claim about it. [t60] is unsourced shape,
     * short - a contact transient, not a sustained ring.
     */
    private val SHAMISEN_SKIN = listOf(Modes.fixed(700f, 1f, 0.15f))

    /** How long [withSlap]'s own noise burst runs - A2's own touch-noise duration and A3's own string-skin contact window both measure about 20 ms (research §1). */
    private const val SLAP_BURST_SECONDS = 0.02f

    /** [withSlap]'s own burst filter cutoff - unsourced; wide enough to carry the burst up toward SLAP's own high-frequency content (A4: skin-strike content reaches ~16 kHz) without simply being white noise. */
    private const val SLAP_BURST_HZ = 12_000f

    /**
     * SHAMISEN's SLAP: the bachi hitting the skin, in parallel with the
     * string rather than derived from it (spec, "SHAMISEN": "SLAP scales
     * the contact layer: a short noise burst through a small skin `Modes`
     * table, in parallel. 0 = string only, and the note's top end drops
     * with it"). Not a [Strings.bodyRing] call: that function's own
     * contract treats its `string` argument as both the drive source (via
     * its first difference) and the dry carrier the wet mix adds onto -
     * SLAP needs a *different* drive (a synthetic burst standing in for
     * the bachi/skin contact) added onto the *unmodified* string, so this
     * drives [SHAMISEN_SKIN] directly through [Modes.ring] instead.
     *
     * [Modes.ring]'s own output is not level-normalized (its own KDoc: a
     * low-frequency fixed mode measured roughly 250x louder than a
     * non-modal layer at an oversampled rate, before either was
     * gain-staged) - RMS-matched against [string] before mixing, the same
     * pattern [Strings.bodyRing] itself uses and for the same reason every
     * other character macro here reads consistently: [slap] means "this
     * many multiples of the string's own loudness", the same convention
     * [BODY_MAX]'s own KDoc states for BODY.
     */
    internal fun withSlap(string: FloatArray, slap: Float, rate: Int, seed: Int): FloatArray {
        if (slap <= 0f) return string
        val burstLen = (SLAP_BURST_SECONDS * rate).toInt().coerceAtLeast(1)
        val noise = Dsp.Noise(seed)
        val lp = Dsp.OnePole(rate)
        val burst = FloatArray(burstLen) { lp.lp(noise.next(), SLAP_BURST_HZ) }
        var mean = 0f
        for (v in burst) mean += v
        mean /= burstLen
        for (i in burst.indices) burst[i] -= mean

        val pad = (SHAMISEN_SKIN.maxOf { it.t60 } * rate).toInt()
        val drive = FloatArray(burstLen + pad)
        burst.copyInto(drive)
        val wet = Modes.ring(drive, 1f, SHAMISEN_SKIN, rate)

        fun rms(buf: FloatArray, n: Int): Float {
            val len = minOf(n, buf.size)
            var acc = 0.0
            for (i in 0 until len) acc += buf[i].toDouble() * buf[i]
            return kotlin.math.sqrt(acc / len.coerceAtLeast(1)).toFloat()
        }
        // Matched over the burst's own short window, not the string's full
        // duration: a plucked string's RMS averaged over its whole decay is
        // dominated by its own long, quiet tail, while the burst lands
        // entirely inside the loud attack - comparing against the full-note
        // average silently undersized the burst by roughly the string's own
        // peak-to-average ratio (a real bug, caught by the owner's own ear:
        // "I don't hear any difference in slap", confirmed by measuring
        // withSlap's own numbers directly - rms(string) over the full note
        // came out three orders of magnitude smaller than rms(wet), so even
        // at SLAP 1 the burst was inaudibly quiet against the attack it
        // actually overlaps).
        val g = rms(string, wet.size) / rms(wet, wet.size).coerceAtLeast(1e-9f)

        val outLen = maxOf(string.size, drive.size)
        val out = FloatArray(outLen)
        for (i in out.indices) {
            val dry = if (i < string.size) string[i] else 0f
            val wetSample = if (i < wet.size) wet[i] else 0f
            out[i] = dry + slap * g * wetSample
        }
        return out
    }

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
