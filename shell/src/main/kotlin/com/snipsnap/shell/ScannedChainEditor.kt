package com.snipsnap.shell

import kotlin.math.sqrt

/**
 * Editor travel, separate from SCANNED CHAIN's saved physical values. Gentle damping
 * and short envelopes get more room on a phone slider. Merely displaying a recalled
 * value never writes the inverse back into the patch or changes its renderer model.
 */
object ScannedChainEditor {
    fun macroPosition(name: String, value: Float): Float =
        if (name == "DAMP") position(value, 1f) else unit(value)

    fun macroValue(name: String, position: Float): Float =
        if (name == "DAMP") seconds(position, 1f) else unit(position)

    fun attackPosition(seconds: Float): Float = position(seconds, 1f)
    fun attackSeconds(position: Float): Float = seconds(position, 1f)
    fun releasePosition(seconds: Float): Float = position(seconds, 2f)
    fun releaseSeconds(position: Float): Float = seconds(position, 2f)

    private fun position(value: Float, maximum: Float): Float {
        require(value.isFinite() && value in 0f..maximum) { "SCANNED CHAIN editor value must be 0..$maximum" }
        return sqrt(value / maximum)
    }

    private fun seconds(position: Float, maximum: Float): Float {
        val travel = unit(position)
        return maximum * travel * travel
    }

    private fun unit(value: Float): Float {
        require(value.isFinite() && value in 0f..1f) { "SCANNED CHAIN editor travel must be 0..1" }
        return value
    }
}
