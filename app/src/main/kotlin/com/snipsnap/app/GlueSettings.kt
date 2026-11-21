package com.snipsnap.app

import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitGlue
import com.snipsnap.shell.PadHit

/**
 * The pads' whole-kit glue: an input trim, an optional tape-style saturator,
 * one compressor and one lookahead limiter across the kit's mix, the chain
 * BOROUGH's listening page previewed and the owner asked to have live
 * ("generally it helps", round 1.4; the saturator, round 3.1: "C for all
 * except rides and rims"). The DSP is native (`GlueBus.h`); this is the
 * complete setting the bridge carries.
 *
 * Off by default, and off is the original mix bit for bit; a 0 dB trim with
 * no saturation is the compressor and limiter exactly as they were before the
 * front end existed. While on, the mix is 3 ms late (the limiter's
 * lookahead): [NativePads.glueLatencyFrames]. Switching the saturator on or
 * off while the glue is on crossfades over 20 ms; every other change glides
 * over 20 ms (the trim and drives in dB).
 *
 * A kit asks for its own glue ([Kit.glue]); [forKit] is the mapping the
 * player publishes when a kit loads, [OFF] for every kit without one.
 */
data class GlueSettings(
    val enabled: Boolean = false,
    /** Where the compressor starts, in dBFS (-40..0). */
    val thresholdDbfs: Float = -10f,
    /** Compression above the threshold (1..20). */
    val ratio: Float = 4f,
    /** Gain after the compressor, in dB (0..24). */
    val makeupDb: Float = 0f,
    /** How hard the compressed mix is pushed into the limiter, in dB (0..24). */
    val driveDb: Float = 0f,
    /** The limiter's sample-peak ceiling in dBFS (-12..0); -1 is the kits' own 0.89 peak. */
    val ceilingDbfs: Float = -1f,
    /** Gain in front of everything, in dB (-24..+24): where the live mix's peak meets the saturator and threshold. */
    val inputTrimDb: Float = 0f,
    /** Saturate after the trim: tanh(g x) / tanh(g) on each channel, g = 10^([saturationDb]/20). */
    val saturate: Boolean = false,
    /** The saturator's drive g in dB (0..12). 0 dB is g = 1, already a curve (+2.4 dB on quiet material). */
    val saturationDb: Float = 0f,
) {
    init {
        require(thresholdDbfs.isFinite() && thresholdDbfs in -40f..0f) { "glue threshold must be -40..0 dBFS: $thresholdDbfs" }
        require(ratio.isFinite() && ratio in 1f..20f) { "glue ratio must be 1..20: $ratio" }
        require(makeupDb.isFinite() && makeupDb in 0f..24f) { "glue makeup must be 0..24 dB: $makeupDb" }
        require(driveDb.isFinite() && driveDb in 0f..24f) { "glue drive must be 0..24 dB: $driveDb" }
        require(ceilingDbfs.isFinite() && ceilingDbfs in -12f..0f) { "glue ceiling must be -12..0 dBFS: $ceilingDbfs" }
        require(inputTrimDb.isFinite() && inputTrimDb in -24f..24f) { "glue input trim must be -24..24 dB: $inputTrimDb" }
        require(saturationDb.isFinite() && saturationDb in 0f..12f) { "glue saturation must be 0..12 dB: $saturationDb" }
    }

    companion object {
        val OFF = GlueSettings()

        /** The round-2 listening page's chain, at the middle of its per-kit limiter drive (no trim, no saturator). */
        val BOROUGH_PREVIEW = GlueSettings(enabled = true, driveDb = 6f)

        /**
         * The live calibration added to a kit glue's input trim: the gain a pad's sample loses between its file and
         * the glue's input, taken back. A kit's trim (BOROUGH's, `recipes.json` `kits[].glue`) is set for the
         * exported samples at their own level, but in the pads' mix a pad hit at full velocity, at its default level
         * and centred, arrives at [PadHit.DEFAULT_HIT_GAIN] = 0.708 of it (-3.0 dB: the level; the balance law is
         * unity at centre). So the live trim is the recipe's plus 3.0 dB. Softer hits arrive at velocity x level, and a
         * pad turned up or down moves with its fader; the trim is calibrated to the hardest default hit, which is what
         * sets the demo groove's peak.
         */
        val LIVE_TRIM_CALIBRATION_DB: Float = (-PadHit.DEFAULT_HIT_GAIN_DB).toFloat()

        /** [glue] as the native bus plays it, on, with the live calibration in its trim (held to the bus's -24..24 dB). */
        fun of(glue: KitGlue): GlueSettings = GlueSettings(
            enabled = true,
            inputTrimDb = (glue.inputTrimDb + LIVE_TRIM_CALIBRATION_DB).coerceIn(-24f, 24f),
            saturate = glue.saturationDb != null,
            saturationDb = glue.saturationDb ?: 0f,
            thresholdDbfs = glue.thresholdDbfs,
            ratio = glue.ratio,
            makeupDb = glue.makeupDb,
            driveDb = glue.limiterDriveDb,
            ceilingDbfs = glue.ceilingDbfs,
        )

        /** What the pads' player publishes when [kit] loads: its glue, on, or [OFF] for a kit that asks for none. */
        fun forKit(kit: Kit): GlueSettings = kit.glue?.let(::of) ?: OFF
    }
}
