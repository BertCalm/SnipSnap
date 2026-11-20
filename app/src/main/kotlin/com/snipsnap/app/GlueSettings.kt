package com.snipsnap.app

/**
 * The pads' whole-kit glue: one compressor and one lookahead limiter across
 * the kit's mix, the chain BOROUGH's listening page previewed and the owner
 * asked to have live ("generally it helps", round 1.4). The DSP is native
 * (`GlueBus.h`); this is the complete setting the bridge carries.
 *
 * Off by default, and off is the original mix bit for bit. While on, the mix
 * is 3 ms late (the limiter's lookahead): [NativePads.glueLatencyFrames].
 *
 * The preview drove its limiter 5.0 to 7.5 dB per kit with a gentle
 * compressor in front; [BOROUGH_PREVIEW] is the middle of that range.
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
) {
    init {
        require(thresholdDbfs.isFinite() && thresholdDbfs in -40f..0f) { "glue threshold must be -40..0 dBFS: $thresholdDbfs" }
        require(ratio.isFinite() && ratio in 1f..20f) { "glue ratio must be 1..20: $ratio" }
        require(makeupDb.isFinite() && makeupDb in 0f..24f) { "glue makeup must be 0..24 dB: $makeupDb" }
        require(driveDb.isFinite() && driveDb in 0f..24f) { "glue drive must be 0..24 dB: $driveDb" }
        require(ceilingDbfs.isFinite() && ceilingDbfs in -12f..0f) { "glue ceiling must be -12..0 dBFS: $ceilingDbfs" }
    }

    companion object {
        val OFF = GlueSettings()

        /** The listening page's chain, at the middle of its per-kit limiter drive. */
        val BOROUGH_PREVIEW = GlueSettings(enabled = true, driveDb = 6f)
    }
}
