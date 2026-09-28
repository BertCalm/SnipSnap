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
enum class SilkVoice { OUD }

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
            oudCourseLoop(detunedStart, detunedTarget, seconds, damping, pickHz, position, slide, rate, seed)
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
        val loop = Strings.Loop(t0.n, t0.a, damping.fb, damping.loopHz, rate)

        val gliding = slideAmount > 0.01f && startFreq != targetFreq
        // "A slow slide" is unsourced (spec, "OUD": no source measures a
        // slide's rise time) - 0.35 s at SLIDE 1 is a shape, awaiting the
        // audition gate, like PLUCK's own placeholder constants.
        val slideSamples = if (gliding) (Dsp.lin(slideAmount, 0.05f, 0.35f) * rate).toInt().coerceAtLeast(1) else 0

        for (i in out.indices) {
            if (gliding && i in 1..slideSamples) {
                val progress = i.toFloat() / slideSamples
                loop.retune(startFreq * (targetFreq / startFreq).pow(progress))
            }
            out[i] = loop.next(if (i < exc.size) exc[i] else 0f)
        }
        return out
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
    }

    private fun withBody(string: FloatArray, voice: SilkVoice, amount: Float, rate: Int): FloatArray =
        Strings.bodyRing(string, bodyFor(voice), amount, rate, RING_CEILING_SECONDS)

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
