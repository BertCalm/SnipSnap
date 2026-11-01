package com.snipsnap.synth.otis

internal data class OtisPressureTrace(
    val pressure: DoubleArray,
    val demand: DoubleArray,
    val target: DoubleArray,
    val stride: Int,
    val rate: Int,
) {
    val controlRateHz: Double get() = rate.toDouble() / stride

    fun pressureAt(seconds: Double): Double = pressureAtFrame(seconds * rate)

    internal fun pressureAtFrame(frame: Double): Double {
        val position = (frame / stride).coerceIn(0.0, pressure.lastIndex.toDouble())
        val index = position.toInt().coerceAtMost(pressure.lastIndex - 1)
        val fraction = position - index
        return pressure[index] + (pressure[index + 1] - pressure[index]) * fraction
    }
}

