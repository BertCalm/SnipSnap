package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * MAGNET's voices: a single-coil guitar on its low E (JANGLE) and a humbucker guitar on its low
 * B (CHUG). Top level and named exactly this: the user-preset roster law resolves the voice enum
 * by the engine's name.
 */
enum class MagnetVoice { JANGLE, CHUG }

/**
 * MAGNET - an electric guitar string, dry, ready for VALVE (the rack's amp section).
 *
 * The string is [Strings.pluck] at a pick position of 0.085 of the string (the spike's 0.10 left a
 * fixed hole at the tenth harmonic on every note), its loop's damping pitch-compensated on JANGLE
 * (a higher note rings as long as the open string does, not a fraction of it), trimmed to its decay
 * and faded out over its end, read through a pickup:
 * Jaffe and Smith's position comb on the string's output ([Strings.pickup]), neck and bridge
 * weighted by BLEND, a humbucker as two aligned coils, then the pickup's resonance. The rest is the
 * melodic output chain. The amp is not in here: [landingChain] is the recipe that lands a pad
 * through VALVE, and the engine's own render is dry.
 *
 * TUNE is two octaves snapped to semitones from the open string; MUTE damps the loop and shortens
 * the note; PICK is the exciter's low-pass corner, a thumb to a wire; BLEND weights the two pickups
 * (0 neck, 1 bridge). Every number marked shape is a listening value from the Phase-0 spike or the
 * specification, to be re-heard by ear (docs/superpowers/specs/2026-09-29-magnet-valve-design.md).
 */
object Magnet {

    /** TUNE: two octaves, snapped to semitones (every melodic engine's rule). */
    const val TUNE_SEMITONES = 24

    /** The string is rendered at the toolkit's oversampled rate and brought down by the output chain. */
    internal const val RENDER_RATE = Dsp.RATE * Dsp.OVERSAMPLE

    /** The string's budget and the trim's floor: the spike's rule (the only one with measurements behind it). */
    private const val STRING_SECONDS = 4.0f
    private const val FLOOR_SECONDS = 0.25f

    /**
     * A fade over the last [ms] of a string, [power] the exponent of the ramp `1 - i/n`: 2 is the
     * squared fade [Strings.trimToDecay] gives a string it cuts at the ring ceiling, and each step up
     * is steeper at its end and gentler at its start.
     */
    private class EndFade(val ms: Float, val power: Int)

    /**
     * The fade over the last milliseconds of a string whose trim ended it on its decay.
     * [Strings.trimToDecay] cuts such a string 60 dB under its peak with no fade, and VALVE lifts
     * that quiet end by its own gain (22 dB at the first CHUG landing, 40 dB at gain 106), so a cut
     * nobody hears dry ends abruptly through the amp; a fade before the amp survives it. The first
     * build's squared 150 ms was the shortest of 50, 100, 150 and 200 ms at the default MUTE (CHUG at
     * gain 106 ended 50.7 dB under its peak, 0.7 dB inside the bar) and misses at MUTE 1 (47.5 dB, TUNE
     * step 3). Cubed 150 ms is the shortest and mildest of the squared, cubed and fourth-power shapes
     * at 150, 250, 400 and 600 ms that holds every cell of the grid at least 3 dB inside the bar
     * (worst 58.5 dB under: CHUG at gain 106, MUTE 1, TUNE step 24). Squared would need 250 ms, which
     * starts 10 ms into CHUG's shortest string (260 ms). Its fade is -10 dB 102 ms and -20 dB 70 ms
     * before the end (squared 150 ms: 84 and 47 ms).
     */
    private val DECAY_FADE = EndFade(ms = 150f, power = 3)

    /**
     * The fade added after [Strings.trimToDecay] to a string the ring ceiling cut. The trim's own 400
     * ms squared fade ends such a string with a quiet but not silent tail, which VALVE lifts: at
     * CHUG's gain 106, TUNE step 2 and MUTE 0 the landed note ended only 28.1 dB under its peak, and
     * JANGLE's pitch compensation sends more notes to the ceiling (50 of its 75 TUNE and MUTE cells
     * reach it, 33 without the law). A squared fade of 250 ms on top of the trim's is the shortest and
     * mildest of the squared, cubed and fourth-power shapes at 150, 250, 400 and 600 ms that keeps the
     * whole grid (CHUG at gain 106, the kit's lead amp and JANGLE's landing, 25 TUNE steps, MUTE 0,
     * the default and 1) at least 3 dB inside the bar: at 150 ms the worst cell is 47.6 dB under
     * (squared), 49.8 (cubed) and 50.9 (fourth power), at 250 ms squared 56.4. The fade starts 3.75 s
     * into a 4 s note; together with the trim's fade the note is -10 dB 237 ms and -20 dB 178 ms
     * before its end (225 and 126 ms with the trim's alone).
     */
    private val CEILING_FADE = EndFade(ms = 250f, power = 2)

    /** The exciter's corner at PICK 0 and 1 (shape): a thumb to a wire, wider than the spike's 4.6x. */
    private const val PICK_MIN_HZ = 600f
    private const val PICK_MAX_HZ = 16_000f

    /** Where the pick plucks, as a fraction of the string (shape): the exciter's first comb notch falls between harmonics. */
    private const val PICK_POSITION = 0.085f

    /** The pickups' positions from the bridge, as a fraction of the string (shape). */
    internal const val NECK = 0.42f
    internal const val BRIDGE = 0.12f

    /** A humbucker's coil spacing over an open string, 18 mm of 648 mm (shape); it grows as the string shortens. */
    private const val COIL_SPACING = 0.0278f

    /** Each coil of a humbucker, summed: the specification's humbucker sum (shape). */
    private const val HUMBUCKER_WEIGHT = 0.707f

    /** The output chain's DC corner, the same corner as Bore's. */
    private const val OUTPUT_DC_HZ = 20f

    /**
     * One voice's numbers: the root note, the string loop's body corner, the pickup's resonance
     * corner and damping, whether the pickup is a humbucker, and whether the loop's damping is
     * pitch-compensated ([compensated]: JANGLE yes, CHUG no). They are the specification's voice
     * table and the Phase-0 spike's (shape).
     */
    private class Spec(
        val rootMidi: Int,
        val bodyLoopHz: Float,
        val resonanceHz: Float,
        val resonanceDamping: Float,
        val humbucker: Boolean,
        val compensate: Boolean,
    )

    private fun specFor(voice: MagnetVoice): Spec = when (voice) {
        MagnetVoice.JANGLE -> Spec(rootMidi = 40, bodyLoopHz = 7_000f, resonanceHz = 4_500f, resonanceDamping = 0.40f, humbucker = false, compensate = true)
        MagnetVoice.CHUG -> Spec(rootMidi = 35, bodyLoopHz = 5_500f, resonanceHz = 2_800f, resonanceDamping = 0.55f, humbucker = true, compensate = false)
    }

    fun macrosFor(voice: MagnetVoice): List<MacroSpec> = when (voice) {
        MagnetVoice.JANGLE -> listOf(
            MacroSpec("TUNE", 0.5f, neutral = 0.5f),
            MacroSpec("MUTE", 0.15f),
            MacroSpec("PICK", 0.6f),
            MacroSpec("BLEND", 0.5f),
        )
        MagnetVoice.CHUG -> listOf(
            MacroSpec("TUNE", 0.5f, neutral = 0.5f),
            MacroSpec("MUTE", 0.35f),
            MacroSpec("PICK", 0.55f),
            MacroSpec("BLEND", 1.0f),
        )
    }

    fun defaults(voice: MagnetVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    /** The macros as a render sees them: defaults, then each known key clamped to 0..1; unknown keys are dropped. */
    internal fun settled(macros: Map<String, Float>, voice: MagnetVoice): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        return m
    }

    fun rootMidi(voice: MagnetVoice): Int = specFor(voice).rootMidi

    /** The snapped number of semitones TUNE lands on above the open string. */
    fun semitonesFor(tune: Float): Int = (tune.coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()

    /** The note TUNE lands on: the one function the render, the seed and every tuning test read. */
    fun frequencyFor(voice: MagnetVoice, tune: Float): Float = Keys.midiHz(rootMidi(voice) + semitonesFor(tune))

    /** A roll from the defaults (MAGNET has no preset roster to seed from); [near] pulls it toward a patch. */
    fun scramble(voice: MagnetVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /**
     * The amp each voice lands through, both the owner's picks by ear at the second listen of
     * 2026-10-01/02 (the numbers stay *shape*: a later listen may move them). DRIVE is a gain on
     * VALVE's law. CHUG's DRIVE 0.85 is gain 106 on the V1.1 law, the number the specification
     * wrote for it when it was written on V1's law (an earlier build landed it at 0.71, gain 13, and
     * the owner chose gain 106 over it). JANGLE's is VALVE's own default amp, written out in
     * full so that a change to VALVE's defaults cannot move it: the earlier JANGLE amp (DRIVE 0.25,
     * TONE 0.55, CAB 0.35) was a plain filter, a gain of 0.257 into a tube that is linear there,
     * and the owner chose this one with the ring of [string]'s pitch compensation.
     */
    val LANDING_VALVE: Map<MagnetVoice, Map<String, Float>> = mapOf(
        MagnetVoice.JANGLE to mapOf("DRIVE" to 0.70f, "SAG" to 0.35f, "TONE" to 0.50f, "CAB" to 0.60f),
        MagnetVoice.CHUG to mapOf("DRIVE" to 0.85f, "SAG" to 0.4f, "TONE" to 0.3f, "CAB" to 0.95f),
    )

    /** The rack chain that lands a [voice] pad through VALVE. Never null: MAGNET has no loop mode. */
    fun landingChain(voice: MagnetVoice): FxChain = FxChain().withSection("valve", LANDING_VALVE.getValue(voice))

    /**
     * The dry electric string at [RENDER_RATE], before any pickup: trimmed to its decay and faded
     * out over its end, by [DECAY_FADE] where the trim ended it on its decay and by [CEILING_FADE]
     * (after the trim's own fade) where the ring ceiling cut it. [compensate] is the voice's
     * [Spec.compensate] unless a test overrides it, to read the same chain with the ring law off.
     */
    internal fun string(voice: MagnetVoice, macros: Map<String, Float>, compensate: Boolean = specFor(voice).compensate): FloatArray {
        val m = settled(macros, voice)
        val spec = specFor(voice)
        val f0 = frequencyFor(voice, m.getValue("TUNE"))
        val base = Strings.damping(m.getValue("MUTE"), spec.bodyLoopHz)
        val damping = if (compensate) compensated(base, f0.toDouble() / Keys.midiHz(spec.rootMidi)) else base
        val pickHz = Dsp.expMap(m.getValue("PICK"), PICK_MIN_HZ, PICK_MAX_HZ)
        val seed = Dsp.seedFor("MAGNET", voice.name, f0)
        val raw = Strings.pluck(f0, STRING_SECONDS, damping, pickHz, seed, RENDER_RATE, position = PICK_POSITION)
        val trimmed = Strings.trimToDecay(raw, RENDER_RATE, FLOOR_SECONDS, STRING_SECONDS)
        // A trim on the decay returns a shorter copy; a ceiling or budget cut returns [raw] itself, already faded
        // by the trim. Either way the string then ends on its own fade, the path's own (the amp lifts a quiet end).
        fade(trimmed, if (trimmed.size < raw.size) DECAY_FADE else CEILING_FADE, RENDER_RATE)
        return trimmed
    }

    /**
     * [base]'s damping with the string's pitch compensated: [r] is the note over the open string
     * (1 or more), the loop loses a fixed amount per round trip and a note r times higher makes r
     * times the trips per second, so the feedback is `fb^(1/r)` (the fundamental then loses the same
     * decibels per second at every pitch) and the loop's low-pass corner rises with the square root
     * of r (the upper partials, which a fixed corner kills in the first tenths of a second, ring on
     * with it). At r = 1 every operation is exact, so the open string is [base] bit for bit. The
     * spike's law L2 (docs/superpowers/specs/2026-09-29-magnet-valve-design.md, "R1, as built").
     */
    internal fun compensated(base: Strings.Damping, r: Double): Strings.Damping = Strings.Damping(
        loopHz = (base.loopHz * sqrt(r)).toFloat(),
        fb = base.fb.toDouble().pow(1.0 / r).toFloat(),
    )

    /** [fade] over the end of [buf], in place: the ramp `1 - i/n` over the last `ms`, raised to the power. */
    private fun fade(buf: FloatArray, fade: EndFade, rate: Int) {
        val n = minOf(buf.size, (fade.ms / 1000f * rate).toInt())
        if (n <= 0) return
        val start = buf.size - n
        for (i in 0 until n) {
            val g = 1f - i.toFloat() / n
            var w = g
            repeat(fade.power - 1) { w *= g }
            buf[start + i] *= w
        }
    }

    /**
     * The string read through the pickups at [f0] and [blend] (0 neck alone, 1 bridge alone): the
     * neck group at [neck] weighted `1 - blend`, the bridge group at [bridge] weighted `blend`, each
     * ONE [Strings.pickup] call (a humbucker group is its two coils, aligned; the two groups are
     * never aligned to each other), summed, then the pickup's resonance unless [resonance] is false.
     * The position and resonance arguments exist so a test can read the comb alone.
     */
    internal fun pickup(
        voice: MagnetVoice,
        y: FloatArray,
        f0: Float,
        blend: Float,
        resonance: Boolean = true,
        neck: Float = NECK,
        bridge: Float = BRIDGE,
    ): FloatArray {
        val spec = specFor(voice)
        val dp = COIL_SPACING * f0 / Keys.midiHz(spec.rootMidi)
        val b = blend.coerceIn(0f, 1f)
        val out = FloatArray(y.size)
        fun add(position: Float, weight: Float) {
            if (weight == 0f) return
            val positions = if (spec.humbucker) floatArrayOf(position, position + dp) else floatArrayOf(position)
            val each = if (spec.humbucker) HUMBUCKER_WEIGHT * weight else weight
            val combed = Strings.pickup(y, positions, FloatArray(positions.size) { each }, f0, RENDER_RATE)
            for (i in out.indices) out[i] += combed[i]
        }
        add(neck, 1f - b)
        add(bridge, b)
        if (!resonance) return out
        val svf = Dsp.TptSvf(RENDER_RATE)
        for (i in out.indices) {
            svf.process(out[i], spec.resonanceHz, spec.resonanceDamping)
            out[i] = svf.low
        }
        return out
    }

    /**
     * The melodic output chain, copied from the other engines' (`Bore.condition` and `Bore.finish`
     * with rasp and bite at zero are the authority for the exact calls): band-limit, decimate to the
     * rack's rate, remove the mean and the DC corner, level to the melodic target, fade the tail.
     * Works in place on [buf] and returns the rack-rate samples. [buf] is at [RENDER_RATE] and no
     * other rate: the decimator always brings it down to `Dsp.RATE` by the oversampling factor, so a
     * buffer at another rate would be band-limited at one rate and decimated at another. [rate]
     * stays a parameter to name what the caller believes it is passing.
     */
    internal fun finish(buf: FloatArray, rate: Int): FloatArray {
        require(rate == RENDER_RATE) { "finish reads a buffer at RENDER_RATE ($RENDER_RATE Hz), not $rate Hz" }
        Tide.bandLimit(buf, rate)
        val out = Dsp.decimate(buf, Dsp.RATE)
        var sum = 0.0
        for (v in out) sum += v
        val mean = if (out.isEmpty()) 0f else (sum / out.size).toFloat()
        val hp = Dsp.OnePole(Dsp.RATE)
        for (i in out.indices) {
            val x = out[i] - mean
            out[i] = x - hp.lp(x, OUTPUT_DC_HZ)
        }
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }

    /** One dry note at the rack's rate. The seed is part of the sound: it is derived, never an argument. */
    fun render(voice: MagnetVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = settled(macros, voice)
        val f0 = frequencyFor(voice, m.getValue("TUNE"))
        val picked = pickup(voice, string(voice, m), f0, m.getValue("BLEND"))
        return Snip(finish(picked, RENDER_RATE), channels = 1, sampleRate = Dsp.RATE)
    }
}
