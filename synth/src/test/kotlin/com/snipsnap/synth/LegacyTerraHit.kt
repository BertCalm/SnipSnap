package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R1's HIT colouring, frozen at 58757f10 before R1b's floor touched it:
 * `Terra.hitLevel`, `runningMagnitudes` and `peakOf` as R1 committed them,
 * copied verbatim. `a floor of 0 is R1's HIT bit for bit` compares the
 * floored code against this, the colouring the owner heard on R1's page,
 * and not against itself. It calls the live `Terra.strikeAndModalBank`,
 * which TerraFrozenTest's forty-case guard already freezes. Never edit it
 * to follow Terra.kt.
 */
internal object LegacyTerraHit {

    private const val T60_NEPERS_DOUBLE = 6.9078

    fun level(body: Terra.Body, x: FloatArray, c: Float): Array<FloatArray>? {
        if (!(c > 0f) || x.isEmpty() || body.modes.isEmpty()) return null
        val run = runningMagnitudes(body, x)
        val m = run[0].size
        val ringFrames = body.frames - body.onsetSamples
        val periods = (3f * body.rate / body.fundamentalHz).toInt()
        val reference = Terra.strikeAndModalBank(body.modes, body.fundamentalHz, body.droopDepth, minOf(ringFrames, periods + 16), body.rate, { 0f })
        val magnitudes = Array(run.size) { k -> FloatArray(m) { n -> run[k][n].toFloat() } }
        val coloured = Terra.strikeAndModalBank(
            body.modes, body.fundamentalHz, body.droopDepth, minOf(ringFrames, maxOf(periods, m) + 64), body.rate, { 0f },
            level = magnitudes,
        )
        val colouredPeak = peakOf(coloured)
        if (!(colouredPeak > 0f)) return null
        val s = (peakOf(reference) / colouredPeak).toDouble()
        if (!s.isFinite()) return null
        val cd = c.coerceAtMost(1f).toDouble()
        return Array(run.size) { k -> FloatArray(m) { n -> ((1.0 - cd) + cd * s * run[k][n]).toFloat() } }
    }

    private fun runningMagnitudes(body: Terra.Body, x: FloatArray): Array<DoubleArray> {
        var last = 0
        for (i in x.indices) if (x[i] != 0f) last = i
        val m = last + 1
        return Array(body.modes.size) { k ->
            val mode = body.modes[k]
            val hz = body.fundamentalHz * mode.ratio
            val out = DoubleArray(m)
            if (hz <= 0f || hz >= body.rate / 2f || mode.t60 <= 0f) return@Array out
            val invR = 1.0 / exp(-T60_NEPERS_DOUBLE / (mode.t60.toDouble() * body.rate))
            val theta = 2.0 * Math.PI * hz / body.rate
            var re = 0.0
            var im = 0.0
            var w = 1.0
            for (n in 0 until m) {
                if (x[n] != 0f) {
                    val phi = theta * n.toDouble()
                    re += x[n] * w * cos(phi)
                    im -= x[n] * w * sin(phi)
                }
                w *= invR
                out[n] = sqrt(re * re + im * im)
            }
            out
        }
    }

    private fun peakOf(x: FloatArray): Float {
        var p = 0f
        for (v in x) p = maxOf(p, abs(v))
        return p
    }
}
