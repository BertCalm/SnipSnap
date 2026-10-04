package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * `Strings.Bow` as it was before GYRE R2 gave it a bridge port, an excitation input and a contact
 * amount: a frozen copy, so the port's "no audio change" claim has something to be checked against
 * (`StringsBowPortTest`). Its loops are [LegacyLoop], `Strings.Loop` frozen at the same commit, because
 * R2a adds to `Loop`: an accidental change there would otherwise reach the copy and the live bow alike
 * and every comparison would still pass. Only `Strings.tune`, which R2a does not touch, is shared.
 * Never edit either to make a test pass.
 */
internal class LegacyBow(
    f: Float,
    val beta: Float,
    private val bridgeHz: Float = BRIDGE_HZ,
    private val share: Float = SHARE,
    private val rate: Int,
    private val rhoMax: Float = RHO_MAX,
) {
    private val nutHz = rate * 0.45f
    private val bridge: LegacyLoop
    private val nut: LegacyLoop
    private val bridgeCapacity: Int
    private val nutCapacity: Int
    private var bowDown = true
    var bowPoint = 0f
        private set

    init {
        require(beta > 0f && beta < 1f) { "the bow sits strictly between the bridge and the nut: beta must be in (0, 1), got $beta" }
        require(share in 0f..1f) { "share is a fraction of the filter's phase delay, in [0, 1], got $share" }
        val tb = Strings.tune(bridgeFreq(f), bridgeHz, rate, roundTrip = beta.toDouble())
        val tn = Strings.tune(f, nutHz, rate, roundTrip = 1.0 - beta)
        bridge = LegacyLoop(tb.n, tb.a, -REFLECTION, bridgeHz, rate, roundTrip = beta.toDouble())
        nut = LegacyLoop(tn.n, tn.a, -1f, nutHz, rate, roundTrip = 1.0 - beta)
        bridgeCapacity = tb.n
        nutCapacity = tn.n
    }
    private fun bridgeFreq(f: Float): Float {
        val first = Strings.tune(f, bridgeHz, rate, roundTrip = beta.toDouble())
        val tau = (rate / f) * beta.toDouble() - 0.5 - first.exact
        val extra = (1.0 - share) * tau
        return (f / (1.0 + extra * f / (beta.toDouble() * rate))).toFloat()
    }
    fun lift() {
        bowDown = false
    }
    fun next(vBow: Float, slope: Float): Float {
        val fromBridge = bridge.reflected()
        val fromNut = nut.reflected()
        val v = fromBridge + fromNut
        val dv = vBow - v
        val push = if (bowDown) dv * rho(dv, slope, rhoMax) else 0f
        nut.inject(fromBridge + push)
        bridge.inject(fromNut + push)
        bowPoint = v + push
        return fromNut + push
    }
    fun retune(f: Float) {
        val bridgeNeeds = Strings.tune(bridgeFreq(f), bridgeHz, rate, roundTrip = beta.toDouble()).n
        val nutNeeds = Strings.tune(f, nutHz, rate, roundTrip = 1.0 - beta).n
        require(bridgeNeeds <= bridgeCapacity && nutNeeds <= nutCapacity) {
            "retune($f) needs segments of $bridgeNeeds (bridge) and $nutNeeds (nut) samples, past the $bridgeCapacity and " +
                "$nutCapacity this Bow was built for - construct it at the lowest note it will play, not the one it is moving toward"
        }
        bridge.retune(bridgeFreq(f))
        nut.retune(f)
    }
    fun gain(scale: Float) {
        bridge.gain(scale)
    }

    companion object {
        const val REFLECTION = 0.95f
        const val OFFSET = 0.001f
        const val RHO_MIN = 0.01f
        const val RHO_MAX = 0.98f
        const val BRIDGE_HZ = 3023.6f
        const val SHARE = 0.85f
        const val RAW_PEAK_CEILING = 1.25f
        fun rho(dv: Float, slope: Float, rhoMax: Float = RHO_MAX): Float {
            val s = abs((dv + OFFSET) * slope) + 0.75f
            val r = s.toDouble().pow(-4.0).toFloat()
            return r.coerceIn(RHO_MIN, rhoMax)
        }
    }
}

/** `Strings.Loop` as it was at `0ba9b05`, frozen with [LegacyBow] (see its KDoc). Never edit it to make a test pass. */
internal class LegacyLoop(
    private var n: Int,
    private var a: Float,
    fb: Float,
    private val loopHz: Float,
    private val rate: Int,
    private val stiffness: Float = 0f,
    private val jawari: Float = 0f,
    private val jawariP0: Float = 1e-6f,
    private val dispersion: Strings.Dispersion? = null,
    private val dcBlock: Boolean = jawari > 0f,
    private val dcHz: Float = Strings.DC_BLOCK_HZ,
    private val roundTrip: Double = 1.0,
) {
    private val baseFb = fb
    private var fb = fb
    private val maxN = n
    private val size = maxN + 2
    private val history = FloatArray(size)
    private var i = 0
    private var apX1 = 0f
    private var apY1 = 0f
    private var stX1 = 0f
    private var stY1 = 0f
    private val loopLp = Dsp.OnePole(rate)
    private val dcA = if (dcBlock) legacyDcBlockerA(rate, dcHz).toFloat() else 0f
    private var dc = 0f
    private var pending = false
    private var pendingValue = 0f
    private val dispX1 = FloatArray(dispersion?.count ?: 0)
    private val dispY1 = FloatArray(dispersion?.count ?: 0)
    fun reflected(): Float {
        if (pending) return pendingValue
        val r = if (i <= n) {
            0f
        } else {
            val d = 0.5f * (history[(i - n) % size] + history[(i - n - 1) % size])
            val tuned = a * (d - apY1) + apX1
            apX1 = d
            apY1 = tuned
            val stiff = if (stiffness != 0f) {
                val s = stiffness * (tuned - stY1) + stX1
                stX1 = tuned
                stY1 = s
                s
            } else tuned
            var yy = loopLp.lp(stiff, loopHz)
            if (dispersion != null) {
                val da = dispersion.a
                for (k in 0 until dispersion.count) {
                    val s = da * (yy - dispY1[k]) + dispX1[k]
                    dispX1[k] = yy
                    dispY1[k] = s
                    yy = s
                }
            }
            if (jawari > 0f) {
                if (yy > 0f) yy -= jawari * min(yy, jawariP0) * yy / jawariP0
            }
            if (dcBlock) {
                dc += dcA * (yy - dc)
                yy -= dc
            }
            fb * yy
        }
        pending = true
        pendingValue = r
        return r
    }
    fun inject(y: Float): Float {
        history[i % size] = y
        i++
        pending = false
        return y
    }
    fun next(x: Float): Float {
        val r = reflected()
        return inject(if (i <= n) x else x + r)
    }
    fun retune(freq: Float) {
        val t = Strings.tune(freq, loopHz, rate, stiffness, jawari, dispersion, dcBlock, dcHz, roundTrip)
        require(t.n <= maxN) {
            "retune($freq) needs a loop of ${t.n} samples, past the $maxN this Loop was built for - " +
                "construct it at the glide's lowest note, not the target it's moving toward"
        }
        n = t.n
        a = t.a
    }
    fun gain(scale: Float) {
        fb = baseFb * scale
    }
}

private fun legacyDcBlockerA(rate: Int, hz: Float): Double {
    require(hz > 0f && hz < rate / 2f) { "the DC blocker's corner must be a frequency above 0 Hz and under Nyquist ($rate Hz rate), got $hz Hz" }
    return 1.0 - exp(-2.0 * PI * hz / rate)
}
