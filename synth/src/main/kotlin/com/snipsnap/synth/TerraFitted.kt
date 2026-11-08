package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * TERRA's fitted hand drums: DJEMBE (the bass and open strokes), DHOLAK (the bass head) and BAYAN
 * (the tabla's bass drum, its ghe). Each stroke is resynthesised from the owner's reference
 * recordings (2026-11-08: phone screen recordings of a djembe, a dholak and a bayan, measured
 * only, never shipped), then tuned by ear over rounds 8 to 10 (docs/TERRA_HANDS.md).
 *
 * The characteristic gesture: **the hand lands → the head's own notes ring for a fifth of a second
 * to two seconds, paired notes beating, the bass ones bending as the head relaxes or the heel lets
 * go → a soft wash of the skin, the shell and the room fills every gap between them, fading with
 * the notes → silence.**
 *
 * Why it is built this way (measured against the references, docs/TERRA_HANDS.md):
 * - **Partials, measured.** A stroke's strongest notes: frequency, starting amplitude, T60, whether
 *   it bends, and how long a low (air) note takes to build. The rounds before rang 0.05 s where a
 *   real head rings 0.2 to 2 s, which left only a click and a boom: the "drum machine rimshot".
 * - **Struck by the hand.** Each partial is convolved with the house half-sine contact (the same
 *   push at any FORCE, shorter for a harder hand) analytically: its output is
 *   `Im(A S_n G_n)`, `S` the partial's running pole, `G` the contact's sum at that pole (partial
 *   over the contact's own first samples). Exact for a steady partial, and a bending one moves too
 *   little during a 2 ms contact to matter.
 * - **The residual, fitted.** Between and above its notes a real stroke is full, 20 to 50 dB under
 *   its strongest note; the partials alone left holes 80 to 100 dB deep. Each third-octave band
 *   gets unit-RMS noise through an 8-pole Butterworth band-pass, with its own level and decay,
 *   solved against the reference in an attack and a body window. Noise, not a separate click: it
 *   fades with the stroke at the level the real one has.
 * - **The skin.** The head's own short upper modes (Bessel, as [TerraHands]) above 1.3 kHz, struck
 *   by the same contact: the attack's grain.
 *
 * Literal: the partial tables, bends and residual bands (measured, then the owner's three tweaks:
 * the bass a deeper, longer, thumpier bowl; the open tone ringing 1.8x longer; the dholak falling
 * 7.6 semitones). Proposed: the macro ranges. Not simulated: the room as a room.
 */
internal object TerraFitted {

    /**
     * 1.45 s: the house classifier hears anything over 1.5 s as a phrase (LOOP), not a hit, and the
     * bass strokes ring longer than that. The last [FADE_SECONDS] taper on a raised cosine, so a
     * boom still at -27 dB (the djembe bass) fades rather than stops.
     */
    const val SECONDS = 1.45f
    private const val FADE_SECONDS = 0.3

    fun frames(rate: Int): Int = (SECONDS * rate).toInt()

    /** One measured note: frequency, starting amplitude (against the stroke's strongest), T60, whether it bends, and its air build-up time (0: none). */
    class Partial(val hz: Double, val amp: Double, val t60: Double, val bends: Boolean, val rise: Double)

    /** One third-octave band of the residual: its edges, its level against the partials' peak, its decay's T60. */
    class Band(val lo: Double, val hi: Double, val a: Double, val t: Double)

    /** A fitted stroke: its partials and residual, the body's gain on its own peak, the contact, the skin, and its bend (depth, time). */
    private class Stroke(
        val partials: List<Partial>, val bands: List<Band>, val bodyGain: Double, val widthMs: Double,
        val skinF01: Double, val skinImpacts: List<TerraHands.Impact>, val skinT60: Double, val skinLevel: Double, val skinSeed: Int,
        val bendDepth: Double, val bendSeconds: Double,
    )

    // ---------------------------------------------------------------- the measured tables

    private val DJEMBE_BASS_PARTIALS = listOf(
        Partial(58.0, 0.79433, 3.24, bends = true, rise = 0.03),
        Partial(121.8, 1.0, 0.12, bends = false, rise = 0.0),
        Partial(75.1549, 0.21941, 0.27, bends = false, rise = 0.03),
        Partial(385.0, 0.087236, 0.5, bends = false, rise = 0.0),
        Partial(559.0, 0.059746, 0.6, bends = false, rise = 0.0),
        Partial(583.0, 0.087279, 0.7, bends = false, rise = 0.0),
        Partial(768.0, 0.047458, 0.6, bends = false, rise = 0.0),
        Partial(807.0, 0.042297, 0.6, bends = false, rise = 0.0),
        Partial(884.0, 0.059746, 0.6, bends = false, rise = 0.0),
    )
    private val DJEMBE_BASS_BANDS = listOf(
        Band(62.9961, 79.3701, 179.979, 0.04),
        Band(79.3701, 100.0, 161.335, 0.048),
        Band(100.0, 125.992, 122.468, 0.074),
        Band(125.992, 158.74, 80.6229, 0.062),
        Band(158.74, 200.0, 17.2794, 0.088),
        Band(200.0, 251.984, 2.06196, 0.126),
        Band(251.984, 317.48, 0.177029, 0.395),
        Band(317.48, 400.0, 0.153331, 0.614),
        Band(400.0, 503.968, 0.18581, 0.432),
        Band(503.968, 634.96, 0.0855182, 1.243),
        Band(634.96, 800.0, 0.0568464, 0.733),
        Band(800.0, 1007.94, 0.0376708, 1.243),
        Band(1007.94, 1269.92, 0.0839128, 0.331),
        Band(1269.92, 1600.0, 0.0394525, 0.472),
        Band(1600.0, 2015.87, 0.0197663, 0.614),
        Band(2015.87, 2539.84, 0.0138737, 0.8),
        Band(2539.84, 3200.0, 0.013762, 0.562),
        Band(3200.0, 4031.75, 0.00908081, 0.733),
        Band(4031.75, 5079.68, 0.0058688, 0.671),
        Band(5079.68, 6400.0, 0.00484099, 0.562),
        Band(6400.0, 8063.49, 0.00365327, 0.562),
    )
    private val DJEMBE_OPEN_PARTIALS = listOf(
        Partial(71.0, 0.3237, 1.0, bends = false, rise = 0.03),
        Partial(385.0, 0.53606, 0.63, bends = false, rise = 0.0),
        Partial(559.0, 1.0, 0.81, bends = false, rise = 0.0),
        Partial(582.0, 0.79433, 0.81, bends = false, rise = 0.0),
        Partial(768.0, 0.56638, 0.9, bends = false, rise = 0.0),
        Partial(807.0, 0.35736, 0.9, bends = false, rise = 0.0),
        Partial(886.0, 0.46947, 0.54, bends = false, rise = 0.0),
        Partial(983.0, 0.28386, 0.9, bends = false, rise = 0.0),
        Partial(1145.0, 0.46947, 0.54, bends = false, rise = 0.0),
        Partial(1182.0, 0.52676, 0.54, bends = false, rise = 0.0),
        Partial(1375.0, 0.40384, 0.414, bends = false, rise = 0.0),
    )
    private val DJEMBE_OPEN_BANDS = listOf(
        Band(79.3701, 100.0, 0.443853, 0.097),
        Band(100.0, 125.992, 0.127974, 0.15),
        Band(125.992, 158.74, 0.073852, 0.164),
        Band(158.74, 200.0, 0.0712208, 0.195),
        Band(200.0, 251.984, 0.0232559, 0.304),
        Band(251.984, 317.48, 0.00983444, 0.562),
        Band(400.0, 503.968, 0.0651586, 0.395),
        Band(503.968, 634.96, 1.24468, 0.04),
        Band(634.96, 800.0, 0.0473546, 0.562),
        Band(800.0, 1007.94, 0.0807042, 0.432),
        Band(1007.94, 1269.92, 0.237833, 0.278),
        Band(1269.92, 1600.0, 0.198013, 0.233),
        Band(1600.0, 2015.87, 0.0497438, 0.304),
        Band(2015.87, 2539.84, 0.0307025, 0.304),
        Band(2539.84, 3200.0, 0.0178499, 0.362),
        Band(3200.0, 4031.75, 0.00653007, 0.472),
        Band(4031.75, 5079.68, 0.00167694, 0.733),
        Band(5079.68, 6400.0, 0.00139381, 0.733),
        Band(6400.0, 8063.49, 0.000837718, 0.733),
    )
    private val DHOLAK_PARTIALS = listOf(
        Partial(95.0, 0.55501, 1.3, bends = true, rise = 0.03),
        Partial(190.0, 0.15755, 0.5, bends = true, rise = 0.0),
        Partial(373.0, 1.0, 0.3, bends = false, rise = 0.02),
        Partial(532.0, 0.14806, 0.45, bends = false, rise = 0.02),
        Partial(563.0, 0.11154, 0.5, bends = false, rise = 0.02),
        Partial(751.0, 0.12107, 0.6, bends = false, rise = 0.02),
        Partial(1047.0, 0.062752, 0.7, bends = false, rise = 0.02),
    )
    private val DHOLAK_BANDS = listOf(
        Band(62.9961, 79.3701, 5.61628, 0.115),
        Band(79.3701, 100.0, 5.48548, 0.179),
        Band(100.0, 125.992, 28.3203, 0.04),
        Band(125.992, 158.74, 3.7904, 0.137),
        Band(158.74, 200.0, 0.574886, 0.233),
        Band(200.0, 251.984, 1.15651, 0.04),
        Band(251.984, 317.48, 0.105985, 0.395),
        Band(400.0, 503.968, 0.0742078, 0.562),
        Band(503.968, 634.96, 0.066556, 1.482),
        Band(634.96, 800.0, 0.13825, 0.362),
        Band(800.0, 1007.94, 0.0308519, 1.042),
        Band(1007.94, 1269.92, 0.0814609, 0.432),
        Band(1269.92, 1600.0, 0.0179816, 0.614),
        Band(1600.0, 2015.87, 0.0177363, 0.671),
        Band(2015.87, 2539.84, 0.00985261, 0.671),
        Band(2539.84, 3200.0, 0.00479591, 0.874),
        Band(3200.0, 4031.75, 0.00350248, 0.874),
        Band(4031.75, 5079.68, 0.00279169, 0.954),
        Band(5079.68, 6400.0, 0.00292965, 0.671),
        Band(6400.0, 8063.49, 0.00278303, 0.8),
    )
    private val BAYAN_PARTIALS = listOf(
        Partial(92.0, 0.68155, 2.0, bends = true, rise = 0.03),
        Partial(267.0, 0.53889, 0.8, bends = true, rise = 0.0),
        Partial(363.0, 0.24156, 0.6, bends = true, rise = 0.0),
        Partial(460.0, 0.44425, 0.7, bends = true, rise = 0.0),
        Partial(741.0, 1.0, 0.3, bends = false, rise = 0.0),
    )
    private val BAYAN_BANDS = listOf(
        Band(62.9961, 79.3701, 24.9795, 0.097),
        Band(79.3701, 100.0, 166.749, 0.04),
        Band(100.0, 125.992, 9.00231, 0.15),
        Band(125.992, 158.74, 3.18919, 0.137),
        Band(158.74, 200.0, 0.572981, 0.331),
        Band(200.0, 251.984, 0.312152, 0.278),
        Band(317.48, 400.0, 0.161475, 0.671),
        Band(400.0, 503.968, 0.0871004, 1.482),
        Band(503.968, 634.96, 0.0135551, 3.0),
        Band(1007.94, 1269.92, 0.00584829, 3.0),
        Band(1269.92, 1600.0, 0.00417518, 3.0),
        Band(1600.0, 2015.87, 0.00186515, 2.515),
        Band(2015.87, 2539.84, 0.00159302, 3.0),
        Band(2539.84, 3200.0, 0.00119335, 1.931),
        Band(3200.0, 4031.75, 0.000754048, 1.931),
        Band(4031.75, 5079.68, 0.000838866, 1.482),
        Band(5079.68, 6400.0, 0.000776156, 0.954),
        Band(6400.0, 8063.49, 0.000594755, 0.874),
    )

    // The bass: round 10's B (a 58 Hz bowl, +6 dB and T60 x1.8 over the reference's 71 Hz, the
    // head-air thump at 2.1x it, the palm's thud +9 dB in the 80-260 Hz bands), a 3% droop in 20 ms.
    private val DJEMBE_BASS = Stroke(DJEMBE_BASS_PARTIALS, DJEMBE_BASS_BANDS, bodyGain = 2.498, widthMs = 1.6,
        skinF01 = 260.0, skinImpacts = listOf(TerraHands.Impact(0.0, 0.45, 0.7, 0.0), TerraHands.Impact(0.35, 0.7, 0.3, 1.2)),
        skinT60 = 0.05, skinLevel = 0.15849, skinSeed = 7, bendDepth = 0.03, bendSeconds = 0.02)

    // The open tone: round 9's B (the reference's partials ringing 1.8x longer), no bend.
    private val DJEMBE_OPEN = Stroke(DJEMBE_OPEN_PARTIALS, DJEMBE_OPEN_BANDS, bodyGain = 1.0, widthMs = 0.9,
        skinF01 = 260.0, skinImpacts = listOf(TerraHands.Impact(0.6, 0.92, 0.6, 0.0), TerraHands.Impact(0.65, 0.95, 0.4, 0.8)),
        skinT60 = 0.06, skinLevel = 0.19953, skinSeed = 7, bendDepth = 0.0, bendSeconds = 0.1)

    // The dholak's bass head: its 95 Hz falls from 1.55x (round 9's B, 7.6 semitones) over a 100 ms
    // time constant; the treble head rings in sympathy, building over 20 ms.
    private val DHOLAK_STROKE = Stroke(DHOLAK_PARTIALS, DHOLAK_BANDS, bodyGain = 1.0, widthMs = 0.9,
        skinF01 = 250.0, skinImpacts = listOf(TerraHands.Impact(0.3, 0.7, 1.0, 0.0)),
        skinT60 = 0.05, skinLevel = 0.31623, skinSeed = 31, bendDepth = 0.55, bendSeconds = 0.1)

    // The bayan's ghe: 92 Hz and partials near 2.9, 3.95 and 5 times it; the heel lets go from 1.6x
    // over a 50 ms time constant (BEND 1), or the ghe holds its note (BEND 0). The owner: "Both work".
    private val BAYAN_STROKE = Stroke(BAYAN_PARTIALS, BAYAN_BANDS, bodyGain = 1.0, widthMs = 1.2,
        skinF01 = 180.0, skinImpacts = listOf(TerraHands.Impact(0.55, 0.8, 1.0, 0.0)),
        skinT60 = 0.05, skinLevel = 0.19953, skinSeed = 21, bendDepth = 0.6, bendSeconds = 0.05)

    // ---------------------------------------------------------------- macros

    fun macros(voice: TerraVoice): List<MacroSpec> = when (voice) {
        // TUNE: +-5 semitones around the stroke as heard. DECAY: every ring and the residual's,
        // 0.5x to 2x. FORCE: the contact, 2x longer (softer) to half (harder). STROKE: 0 the bass,
        // 1 the open tone, between a hand landing between them. THUMP: the palm's thud (the
        // residual's 80-260 Hz bands and the head-air thump), +-9 dB.
        TerraVoice.DJEMBE -> listOf(MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.5f), MacroSpec("FORCE", 0.5f),
            MacroSpec("STROKE", 0f), MacroSpec("THUMP", 0.5f))
        // DROP: how far the bass falls, from none (0) through the heard 1.55x (0.5) to 2.1x (1).
        // TREBLE: the treble head's sympathetic ring, +-12 dB.
        TerraVoice.DHOLAK -> listOf(MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.5f), MacroSpec("FORCE", 0.5f),
            MacroSpec("DROP", 0.5f), MacroSpec("TREBLE", 0.5f))
        // BEND: 0 the ghe holds its note, 1 the heel lets go (falls from 1.6x).
        TerraVoice.BAYAN -> listOf(MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.5f), MacroSpec("FORCE", 0.5f),
            MacroSpec("BEND", 1f))
        else -> error("$voice is not a fitted TERRA hand drum")
    }

    private fun tuneRatio(m: Map<String, Float>) = 2.0.pow((m.getValue("TUNE") - 0.5) * 10.0 / 12.0)
    private fun decayScale(m: Map<String, Float>) = Dsp.around(m.getValue("DECAY"), 0.5f, 1f, 2f).toDouble()
    private fun widthScale(m: Map<String, Float>) = 2.0.pow((0.5 - m.getValue("FORCE")) * 2.0)

    // ---------------------------------------------------------------- the strokes a voice plays

    /** The strokes [voice] sounds at [m], each with its weight and the per-voice adjustments (bend, partial gains, band gains). */
    private class Played(val stroke: Stroke, val weight: Double, val bendDepth: Double, val partialGain: (Partial) -> Double, val bandGain: (Band) -> Double)

    private fun played(voice: TerraVoice, m: Map<String, Float>): List<Played> = when (voice) {
        TerraVoice.DJEMBE -> {
            val s = m.getValue("STROKE").toDouble().coerceIn(0.0, 1.0)
            val thump = 10.0.pow((m.getValue("THUMP") - 0.5) * 18.0 / 20.0)
            val thumpPartial = { p: Partial -> if (p.rise == 0.0 && p.hz < 150.0) thump else 1.0 }
            val thudBand = { b: Band -> if (b.lo >= 80.0 && b.lo < 260.0) thump else 1.0 }   // the bands round 10 raised
            listOfNotNull(
                if (s < 1.0) Played(DJEMBE_BASS, 1.0 - s, DJEMBE_BASS.bendDepth, thumpPartial, thudBand) else null,
                if (s > 0.0) Played(DJEMBE_OPEN, s, 0.0, { 1.0 }, thudBand) else null,
            )
        }
        TerraVoice.DHOLAK -> {
            val treble = 10.0.pow((m.getValue("TREBLE") - 0.5) * 24.0 / 20.0)
            listOf(Played(DHOLAK_STROKE, 1.0, 1.1 * m.getValue("DROP"), { p -> if (!p.bends) treble else 1.0 }, { 1.0 }))
        }
        TerraVoice.BAYAN -> listOf(Played(BAYAN_STROKE, 1.0, 0.6 * m.getValue("BEND"), { 1.0 }, { 1.0 }))
        else -> error("$voice is not a fitted TERRA hand drum")
    }

    /** The voice's [Terra.Body] modes for HIT: every played stroke's partials, as ratios to its first stroke's first partial. */
    fun bodyModes(voice: TerraVoice, m: Map<String, Float>): Pair<List<Modes.Mode>, Float> {
        val tune = tuneRatio(m); val decay = decayScale(m)
        val strokes = played(voice, m)
        val fundamental = strokes.maxByOrNull { it.weight }!!.stroke.partials[0].hz * tune
        val modes = strokes.flatMap { pl ->
            pl.stroke.partials.map { p -> Modes.Mode((p.hz * tune / fundamental).toFloat(), (p.amp * pl.partialGain(p) * pl.weight).toFloat(), (p.t60 * decay).toFloat()) }
        }
        return modes to fundamental.toFloat()
    }

    // ---------------------------------------------------------------- render

    /**
     * [voice] at the render [rate], before the output stage ([TerraBox.finish]). [noiseSeed] draws
     * the residual's noise (each take its own); the skin's split pairs are each stroke's own.
     * [level] is HIT's curve per [bodyModes] mode; under HIT the partials are scaled by the
     * unstruck drum's peak, so the colouring reaches the residual in proportion.
     */
    fun render(voice: TerraVoice, m: Map<String, Float>, rate: Int, velocity: Float, noiseSeed: Int,
               level: Array<FloatArray>? = null, headOnly: Boolean = false): FloatArray {
        val v = StrikeVelocity.settled(velocity).toDouble()
        val frames = frames(rate)
        val tune = tuneRatio(m); val decay = decayScale(m); val wScale = widthScale(m)
        val strokes = played(voice, m)
        val nModes = strokes.sumOf { it.stroke.partials.size }
        if (level != null) require(level.size == nModes && level.all { it.isNotEmpty() }) { "a level curve per mode ($nModes), each at least one value long" }
        val out = DoubleArray(frames)
        var modeAt = 0
        for ((si, pl) in strokes.withIndex()) {
            val st = pl.stroke
            val width = st.widthMs * wScale + (1.0 - v) * 1.2
            val curves = level?.copyOfRange(modeAt, modeAt + st.partials.size)
            modeAt += st.partials.size
            val body = body(st, pl, width, tune, decay, rate, frames, curves)
            if (headOnly) { for (i in out.indices) out[i] += body[i] * pl.weight; continue }
            val bodyPeak = peakOf(if (curves == null) body else body(st, pl, width, tune, decay, rate, frames, null))
            val skin = skin(st, width, tune, rate, frames)
            val skinPeak = peakOf(skin)
            val p = DoubleArray(frames) {
                (if (bodyPeak > 1e-12) body[it] / bodyPeak * st.bodyGain else 0.0) + (if (skinPeak > 1e-12) skin[it] / skinPeak * st.skinLevel * v * v else 0.0)
            }
            // the residual's levels were fitted against the band-limited partials' peak
            val pLimited = TerraBox.lowpass(TerraBox.lowpass(FloatArray(frames) { p[it].toFloat() }, 20000f, rate), 20000f, rate)
            val pPeak = if (curves == null) pLimited.maxOf { abs(it) }.toDouble() else {
                val ref = body(st, pl, width, tune, decay, rate, frames, null)
                val refPeak = peakOf(ref)
                val unstruck = DoubleArray(frames) { (if (refPeak > 1e-12) ref[it] / refPeak * st.bodyGain else 0.0) + (if (skinPeak > 1e-12) skin[it] / skinPeak * st.skinLevel * v * v else 0.0) }
                TerraBox.lowpass(TerraBox.lowpass(FloatArray(frames) { unstruck[it].toFloat() }, 20000f, rate), 20000f, rate).maxOf { abs(it) }.toDouble()
            }
            val res = residual(st, pl, decay, rate, frames, noiseSeed * 31 + si * 977, v)
            // the velocity law (the full hit is the stroke as picked): a softer hand drives the head's
            // notes in proportion (v), the skin's short upper modes as v^2, and the residual's bands by
            // their own exponents (bandExponent): steeper the higher the band, so a soft hit is darker
            val pGain = v
            for (i in out.indices) out[i] += ((if (pPeak > 1e-12) p[i] / pPeak * pGain else 0.0) + res[i]) * pl.weight
        }
        val fade = (FADE_SECONDS * rate).toInt()
        return FloatArray(frames) {
            val left = frames - it
            val taper = if (left >= fade) 1.0 else 0.5 - 0.5 * cos(PI * left / fade)
            (out[it] * taper).toFloat()
        }
    }

    /** The partials, struck by the contact (see the class doc for the convolution), each times its HIT curve if any. */
    private fun body(st: Stroke, pl: Played, widthMs: Double, tune: Double, decay: Double, rate: Int, frames: Int, curves: Array<FloatArray>?): DoubleArray {
        val w = max(3, (widthMs * 1e-3 * rate).toInt())
        val pulse = DoubleArray(w) { sin(PI * it / w) }
        val area = pulse.sum()
        for (j in pulse.indices) pulse[j] /= area
        val out = DoubleArray(frames)
        val bendDecay = exp(-1.0 / (st.bendSeconds * rate))
        for ((k, pt) in st.partials.withIndex()) {
            val amp = pt.amp * pl.partialGain(pt)
            val d1 = 6.9078 / (pt.t60 * decay * rate)
            val d2 = if (pt.rise > 0.0) d1 + 1.0 / (pt.rise * rate) else -1.0
            val curve = curves?.get(k)
            var bend = pl.bendDepth                 // the bend's excess over 1, decaying
            var omega = 2.0 * PI * pt.hz * tune * (if (pt.bends) 1.0 + bend else 1.0) / rate
            // G for each decay at the current omega: sum_j p_j e^{d j} e^{-i omega j}. The weights p_j e^{d j}
            // do not depend on omega, and e^{-i omega j} advances by a rotation, so a bend's update is
            // w complex multiplies, not w exponentials and w pairs of trig calls.
            val wts1 = DoubleArray(w) { pulse[it] * exp(d1 * it) }
            val wts2 = if (d2 > 0) DoubleArray(w) { pulse[it] * exp(d2 * it) } else wts1
            fun gain(d: Double, om: Double, upTo: Int): DoubleArray {
                val wts = if (d == d1) wts1 else wts2
                val rc = cos(om); val rs = sin(om)
                var c = 1.0; var s = 0.0; var re = 0.0; var im = 0.0
                for (j in 0 until upTo) {
                    re += wts[j] * c; im -= wts[j] * s
                    val nc = c * rc - s * rs; s = s * rc + c * rs; c = nc
                }
                return doubleArrayOf(re, im)
            }
            val g1 = gain(d1, omega, w); val g2 = if (d2 > 0) gain(d2, omega, w) else doubleArrayOf(0.0, 0.0)
            // the partial sums for n < w, where only part of the contact has landed
            val part1Re = DoubleArray(w); val part1Im = DoubleArray(w); val part2Re = DoubleArray(w); val part2Im = DoubleArray(w)
            run {
                var r1 = 0.0; var i1 = 0.0; var r2 = 0.0; var i2 = 0.0
                for (j in 0 until w) {
                    val e1 = exp(d1 * j) * pulse[j]; r1 += e1 * cos(omega * j); i1 -= e1 * sin(omega * j)
                    if (d2 > 0) { val e2 = exp(d2 * j) * pulse[j]; r2 += e2 * cos(omega * j); i2 -= e2 * sin(omega * j) }
                    part1Re[j] = r1; part1Im[j] = i1; part2Re[j] = r2; part2Im[j] = i2
                }
            }
            var g1Re = g1[0]; var g1Im = g1[1]; var g2Re = g2[0]; var g2Im = g2[1]
            // e^{i phase} advanced by a rotation (cos omega, sin omega), the phase starting at omega
            var rotC = cos(omega); var rotS = sin(omega)
            var c = rotC; var s = rotS
            var env1 = 1.0; var env2 = 1.0
            val step1 = exp(-d1); val step2 = if (d2 > 0) exp(-d2) else 0.0
            val last = min(frames, (pt.t60 * decay * 140.0 / 60.0 * rate).toInt() + w + 1)   // decayed 140 dB
            for (n in 0 until last) {
                if (n > 0) {
                    if (pt.bends && n % 64 == 0 && bend > 1e-9) {   // a bend moves too little in 64 samples (0.36 ms) to hear
                        omega = 2.0 * PI * pt.hz * tune * (1.0 + bend) / rate
                        rotC = cos(omega); rotS = sin(omega)
                        if (n >= w) {
                            val a = gain(d1, omega, w); g1Re = a[0]; g1Im = a[1]
                            if (d2 > 0) { val b = gain(d2, omega, w); g2Re = b[0]; g2Im = b[1] }
                        }
                    }
                    val nc = c * rotC - s * rotS; s = s * rotC + c * rotS; c = nc
                    if (n % 4096 == 0) { val r = sqrt(c * c + s * s); c /= r; s /= r }   // hold the phasor on the unit circle
                }
                val (aRe, aIm) = if (n < w) part1Re[n] to part1Im[n] else g1Re to g1Im
                var y = env1 * (s * aRe + c * aIm)               // Im(e^{i phase} G1) * e^{-d1 n}
                if (d2 > 0) {
                    val (bRe, bIm) = if (n < w) part2Re[n] to part2Im[n] else g2Re to g2Im
                    y -= env2 * (s * bRe + c * bIm)
                    env2 *= step2
                }
                env1 *= step1
                if (pt.bends) bend *= bendDecay
                out[n] += amp * y * (if (curve == null) 1.0 else curve[min(n, curve.size - 1)].toDouble())
            }
        }
        return out
    }

    /** The skin's Bessel modes per (seed, f01, T60): the strip weights are the costly part and only TUNE moves them. */
    private val skinModes = java.util.concurrent.ConcurrentHashMap<Triple<Int, Double, Double>, List<TerraHands.HeadMode>>()

    /** The skin: the head's own modes above 1.3 kHz (Bessel, [TerraHands]), struck by the same contact. */
    private fun skin(st: Stroke, widthMs: Double, tune: Double, rate: Int, frames: Int): DoubleArray {
        val force = ((2.6 - widthMs) / 2.3).coerceIn(0.0, 1.0).toFloat()
        val modes = skinModes.getOrPut(Triple(st.skinSeed, st.skinF01 * tune, st.skinT60)) {
            TerraHands.headModes(st.skinF01 * tune, st.skinImpacts, st.skinT60, tilt = 0.5, floor = 0.004, seed = st.skinSeed).filter { it.hz > 1300.0 }
        }
        val y = TerraHands.ringHead(modes, st.skinImpacts, force, 1f, rate, frames)
        return DoubleArray(frames) { y[it].toDouble() }
    }

    /**
     * A band's velocity exponent: the palm's thud (bands under 250 Hz) falls as v^0.5, a soft palm
     * still thuds; above, the exponent climbs with the band's log frequency from v^0.75 to v^3 at
     * 8 kHz, past the head's notes' v^1, so a harder hit is brighter at its onset (the house test asks
     * for a 30% rise in the first 30 ms' centroid from v = 0.25 to 1) while the full hit stays the
     * stroke as picked.
     */
    private fun bandExponent(lo: Double): Double =
        if (lo < 250.0) 0.5 else 0.75 + 2.25 * (kotlin.math.ln(lo / 250.0) / kotlin.math.ln(8000.0 / 250.0)).coerceIn(0.0, 1.0)

    /**
     * The residual: per band, unit-RMS noise through the band's 8-pole Butterworth band-pass, times
     * its level and decay. Built at the output rate (its bands end at 8 kHz, and the fit itself ran
     * at 44.1 kHz), then linearly upsampled to [rate]; the output stage's band-limit removes the
     * upsampling's images. The noise's RMS through the filter is its expected one (the uniform
     * noise's variance, 1/3, times the filter's impulse energy, cached per band), so a band stops
     * once it has decayed 140 dB.
     */
    private fun residual(st: Stroke, pl: Played, decay: Double, rate: Int, frames: Int, seed: Int, velocity: Double): DoubleArray {
        val low = Dsp.RATE
        val lowFrames = frames / (rate / low) + 2
        val sum = DoubleArray(lowFrames)
        for ((i, b) in st.bands.withIndex()) {
            val sections = bandpass(b.lo, min(b.hi, low / 2.0 * 0.95), low)
            val rms = sqrt(impulseEnergy(b.lo, b.hi, low, sections) / 3.0)
            if (rms < 1e-12) continue
            val g = b.a * pl.bandGain(b) * velocity.pow(bandExponent(b.lo)) / rms
            val tSeconds = b.t * decay
            val n = min(lowFrames, (tSeconds * 140.0 / 60.0 * low).toInt() + 1)
            // the filter starts from rest, as in the fit: a narrow low band rings up over its first ~16 ms,
            // which is part of the bass strokes' soft, building start the owner picked
            val noise = Dsp.Noise(seed + i * 7919)
            var x = DoubleArray(n) { noise.next().toDouble() }
            for (sec in sections) x = biquad(x, sec)
            val step = exp(-6.9078 / (tSeconds * low))
            var env = 1.0
            for (k in 0 until n) { sum[k] += g * x[k] * env; env *= step }
        }
        val up = rate / low
        return DoubleArray(frames) { k ->
            val pos = k / up; val frac = (k % up).toDouble() / up
            sum[pos] + (sum[min(pos + 1, lowFrames - 1)] - sum[pos]) * frac
        }
    }

    private val impulseEnergies = java.util.concurrent.ConcurrentHashMap<Triple<Double, Double, Int>, Double>()

    /** The band-pass's impulse energy (sum of h^2), run until it has rung down, cached per band and rate. */
    private fun impulseEnergy(lo: Double, hi: Double, rate: Int, sections: List<DoubleArray>): Double =
        impulseEnergies.getOrPut(Triple(lo, hi, rate)) {
            // the narrowest band (63 Hz wide at 63 Hz) rings for well under a second
            var x = DoubleArray(rate) { if (it == 0) 1.0 else 0.0 }
            for (sec in sections) x = biquad(x, sec)
            x.sumOf { it * it }
        }

    // ---------------------------------------------------------------- the band-pass

    /**
     * A 4th-order Butterworth band-pass (8 poles) from [lo] to [hi] Hz at [rate], as four biquads
     * ([b0, b1, b2, a1, a2], a0 = 1): the analog prototype's poles, moved to a band-pass, then
     * bilinear with pre-warped edges. The same design as the fit's (scipy.signal.butter(4, band)).
     */
    internal fun bandpass(lo: Double, hi: Double, rate: Int): List<DoubleArray> {
        val fs2 = 2.0 * rate
        val wl = fs2 * tan(PI * lo / rate); val wh = fs2 * tan(PI * hi / rate)
        val bw = wh - wl; val w0sq = wl * wh
        val poles = ArrayList<DoubleArray>()          // analog band-pass poles, as (re, im)
        for (m in intArrayOf(-3, -1, 1, 3)) {
            val pr = -cos(PI * m / 8.0) * bw / 2.0; val pi = -sin(PI * m / 8.0) * bw / 2.0   // the prototype pole times bw/2
            // sqrt(p^2 - w0^2)
            val qr = pr * pr - pi * pi - w0sq; val qi = 2.0 * pr * pi
            val mag = sqrt(sqrt(qr * qr + qi * qi)); val ang = kotlin.math.atan2(qi, qr) / 2.0
            val sr = mag * cos(ang); val si = mag * sin(ang)
            poles += doubleArrayOf(pr + sr, pi + si); poles += doubleArrayOf(pr - sr, pi - si)
        }
        // bilinear: z = (fs2 + s) / (fs2 - s); the gain k = bw^4 fs2^4 / prod(fs2 - s)
        var kRe = bw.pow(4) * fs2.pow(4); var kIm = 0.0
        val zPoles = poles.map { (re, im) ->
            val nr = fs2 + re; val ni = im; val dr = fs2 - re; val di = -im
            val den = dr * dr + di * di
            // k /= (fs2 - s)
            val tr = (kRe * dr + kIm * di) / den; val ti = (kIm * dr - kRe * di) / den
            kRe = tr; kIm = ti
            doubleArrayOf((nr * dr + ni * di) / den, (ni * dr - nr * di) / den)
        }
        val upper = zPoles.filter { it[1] > 0.0 }.sortedBy { it[0] }
        require(upper.size == 4) { "a band-pass of 8 poles pairs into 4 sections" }
        val gainEach = kRe.pow(0.25)
        return upper.map { (re, im) -> doubleArrayOf(gainEach, 0.0, -gainEach, -2.0 * re, re * re + im * im) }
    }

    private fun biquad(x: DoubleArray, c: DoubleArray): DoubleArray {
        val y = DoubleArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val o = c[0] * x[i] + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = o; y[i] = o
        }
        return y
    }

    private fun peakOf(x: DoubleArray): Double { var p = 0.0; for (s in x) p = max(p, abs(s)); return p }
}
