package com.snipsnap.synth

import kotlin.math.log2
import kotlin.math.pow

/**
 * SILK SCALES — where TUNE walks, once it leaves 12-EDO.
 *
 * Every other melodic engine's TUNE snaps to a semitone across two octaves
 * above the voice's root (`Pluck.TUNE_SEMITONES` and its siblings). SILK adds
 * a second snapped macro, SCALE, and TUNE walks SCALE's own degrees instead
 * (docs/superpowers/specs/2026-09-27-silk-string-engine-design.md, "TUNE and
 * SCALE"): fourteen rows, each either confirmed against an open research
 * dataset or an equal division — research §5
 * (docs/superpowers/plans/2026-09-27-silk-research.md) has every citation. A
 * row that is neither does not ship; there is no "computed" or "shape" row
 * here the way an instrument's own gain or Q can be.
 */
internal object SilkScales {

    /**
     * One scale: [cents] above the root for one period, and the period
     * itself in cents - 1200 for everything but [BOHLEN_PIERCE]'s tritave.
     * [cents] always starts at 0 (the root itself) and never reaches
     * [period] (the next period's root is degree 0 of the next octave up,
     * not a repeated final degree).
     */
    class Scale(val cents: List<Float>, val period: Float)

    // Twelve equal semitones - PLUCK's own TUNE, so a SILK voice left on
    // CHROMATIC at INFLECT's centre reproduces 12-EDO exactly.
    val CHROMATIC = Scale((0 until 12).map { it * 100f }, 1200f)

    // Pentatonic (gong) - Ho & Han 1982 via DaMuSc T0337; DaMuSc's own note
    // assumes shi-er-lu tuning, and its Pythagorean 1201-cent close rounds
    // to 1200 here.
    val PENTATONIC = Scale(listOf(0f, 204f, 408f, 702f, 906f), 1200f)

    // Maqam Rast - Rechberger 2018, 53-comma row, DaMuSc OT0441.
    val RAST = Scale(listOf(0f, 204f, 362f, 498f, 702f, 906f, 1064f), 1200f)

    // Maqam Bayati - DaMuSc OT0468.
    val BAYATI = Scale(listOf(0f, 136f, 294f, 498f, 702f, 838f, 996f), 1200f)

    // Maqam Hijaz - ascending 53-comma row (5;13;4;9;7;6;9), consistent with
    // the other Arabic rows here; only DaMuSc's descending row fails to
    // close (52 commas).
    val HIJAZ = Scale(listOf(0f, 113f, 408f, 498f, 702f, 860f, 996f), 1200f)

    // Maqam Sikah - ascending row. Corrected from a first draft that copied
    // the descending row's 634-cent fifth; Maqam World notates a perfect
    // fifth here and its page audio matches this row within 4 cents.
    val SIKAH = Scale(listOf(0f, 136f, 340f, 543f, 702f, 838f, 1042f), 1200f)

    // Dastgah Shur - Rechberger's 17-note gamut, DaMuSc OT0308.
    val SHUR = Scale(listOf(0f, 133f, 294f, 498f, 702f, 792f, 996f), 1200f)

    // Dastgah Mahur - DaMuSc OT0311.
    val MAHUR = Scale(listOf(0f, 204f, 408f, 498f, 702f, 906f, 1110f), 1200f)

    // Dastgah Chahargah - DaMuSc OT0318.
    val CHAHARGAH = Scale(listOf(0f, 133f, 408f, 498f, 702f, 835f, 1110f), 1200f)

    // Miyako-bushi (the five-note in scale) - Hewitt 2013, DaMuSc OT0103,
    // there labelled "kumoi joshi"; the set 1-b2-4-5-b6 is the miyako-bushi
    // scale in en/ja Wikipedia and the koto's hira-joshi on D. Named
    // MIYAKO_BUSHI here rather than "kumoi", which names a different shape
    // elsewhere in the literature (Malm).
    val MIYAKO_BUSHI = Scale(listOf(0f, 90f, 498f, 702f, 792f), 1200f)

    // Hirajoshi - DaMuSc OT0102: the same pitch set as MIYAKO_BUSHI, as a
    // mode on its fourth degree.
    val HIRAJOSHI = Scale(listOf(0f, 204f, 294f, 702f, 792f), 1200f)

    // Equal divisions of the octave - the RATIO precedent in TINES for a
    // snapped table, applied to cents instead of a ratio.
    val EDO_24 = Scale((0 until 24).map { it * (1200f / 24f) }, 1200f)
    val EDO_19 = Scale((0 until 19).map { it * (1200f / 19f) }, 1200f)
    val EDO_31 = Scale((0 until 31).map { it * (1200f / 31f) }, 1200f)

    // Bohlen-Pierce: 13 equal steps of a tritave (3/1), not an octave. The
    // tritave's own size in cents, 1200*log2(3) (~1901.955), is computed
    // rather than the spec's rounded "1902"/"146.304" labels, so the period
    // and its thirteen steps stay exactly consistent with each other.
    private val TRITAVE_CENTS = (1200.0 * log2(3.0)).toFloat()
    val BOHLEN_PIERCE = Scale((0 until 13).map { it * (TRITAVE_CENTS / 13f) }, TRITAVE_CENTS)

    /** Every shipped row, in the spec's own order - what SCALE snaps across. */
    val TABLE: List<Scale> = listOf(
        CHROMATIC, PENTATONIC, RAST, BAYATI, HIJAZ, SIKAH, SHUR, MAHUR,
        CHAHARGAH, MIYAKO_BUSHI, HIRAJOSHI, EDO_24, EDO_19, EDO_31, BOHLEN_PIERCE,
    )

    /** SCALE's own snap: the TINES `RATIO` precedent, `TABLE[(macro * (size - 1)).toInt()]`. */
    fun snap(macro: Float): Scale = TABLE[(macro.coerceIn(0f, 1f) * (TABLE.size - 1)).toInt()]

    /**
     * The snapped note frequency TUNE lands on: [tune] spans two periods of
     * [scale] above [root], [inflect] bends that degree by up to ±50 cents
     * (default 0.5, the degree exactly - see the three-arg overload).
     * "Snapped notes, never a mistuning" holds regardless of [inflect]: the
     * degree is chosen first, and only then nudged.
     *
     * [Scale.cents] and [Scale.period] are already standard cents - the
     * fixed logarithmic unit where 1200 is one octave, a factor of 2 -
     * whatever [scale]'s own period is (1200 for every row but
     * [BOHLEN_PIERCE]'s ~1901.955-cent tritave). So the octave multiplier
     * scales by [Scale.period] (that is what "one more period" means for
     * this scale), but the final cents-to-ratio conversion always divides
     * by the constant 1200, never by [Scale.period] - dividing by the
     * period there would treat BOHLEN_PIERCE's tritave as if it were an
     * octave and land every note at the wrong pitch.
     */
    fun frequencyFor(root: Float, scale: Scale, tune: Float, inflect: Float = 0.5f): Float {
        val size = scale.cents.size
        val degree = Math.round(tune.coerceIn(0f, 1f) * (2 * size))
        val octave = degree / size
        val idx = degree % size
        val degreeCents = octave * scale.period + scale.cents[idx]

        // INFLECT: ±50 cents, linear, with an exact detent within ±0.02 of
        // centre so a knob nudged back to the middle lands on the degree,
        // not a cent off it (spec, "INFLECT"). The tiny epsilon on the
        // boundary is for the float literal, not the physics: 0.48f is
        // itself stored as ~0.47999999, so `0.5f - 0.48f` alone already
        // reads a hair over 0.02f - without the margin, the detent would
        // silently miss the exact 0.48/0.52 a UI slider is likely to send.
        val inflectCents = if (kotlin.math.abs(inflect - 0.5f) <= 0.02f + 1e-4f) 0f else (inflect - 0.5f) * 100f

        return root * 2f.pow((degreeCents + inflectCents) / 1200f)
    }

    /** [frequencyFor] with INFLECT at its centre - the degree exactly. */
    fun frequencyFor(root: Float, scale: Scale, tune: Float): Float = frequencyFor(root, scale, tune, 0.5f)
}
