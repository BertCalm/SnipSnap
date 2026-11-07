package com.snipsnap.synth

import kotlin.math.*

/** Fractional reset events corrected with an offline, symmetric bandlimited step.
 * Source guards supply look-ahead without delaying either phaser path. */
internal object ChitinSource {
    private const val HALF = 16
    private const val RESOLUTION = 256
    private fun i0(x: Double): Double {
        var term = 1.0; var sum = 1.0
        for (k in 1..40) { term *= x*x/(4.0*k*k); sum += term }
        return sum
    }
    private val step by lazy {
        val n = 2*HALF*RESOLUTION
        val impulse = DoubleArray(n+1) { i ->
            val t = i.toDouble()/RESOLUTION-HALF
            val sinc = if (abs(t)<1e-12) .9 else sin(.9*PI*t)/(PI*t)
            sinc*i0(8.6*sqrt(max(0.0,1-t*t/(HALF*HALF))))/i0(8.6)
        }
        val integrated = DoubleArray(n+1)
        for (i in 1..n) integrated[i] = integrated[i-1]+(impulse[i-1]+impulse[i])/(2*RESOLUTION)
        val total = integrated.last()
        DoubleArray(n+1) { integrated[it]/total }
    }
    private fun residual(t: Double): Double {
        if (t <= -HALF || t >= HALF) return 0.0
        val position = (t+HALF)*RESOLUTION
        val i = floor(position).toInt().coerceIn(0,step.size-2); val f = (position-i).coerceIn(0.0,1.0)
        return step[i]*(1-f)+step[i+1]*f-if(t>=0) 1.0 else 0.0
    }
    internal fun render(frames: Int, rate: Int, root: Double, pulse: Boolean,
                        ratio: (Double)->Double, corrected: Boolean = true): FloatArray {
        val n = frames+2*HALF
        val master = DoubleArray(n); val slave = DoubleArray(n); val support = DoubleArray(n)
        val da = root/rate
        var pa = ((-HALF*da)%1+1)%1
        var pb = (ratio(-HALF.toDouble()/rate)*pa)%1
        fun jump(buffer: DoubleArray, event: Double, delta: Double) {
            if (!corrected || abs(delta)<1e-14) return
            for (j in max(0,ceil(event-HALF).toInt())..min(n-1,floor(event+HALF).toInt()))
                buffer[j] += delta*residual(j-event)
        }
        for (i in 0 until n) {
            if (i%2048==0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            master[i] += if(pulse) { if(pa<.5) 1.0 else -1.0 } else 2*pa-1
            slave[i] += 2*pb-1; support[i] = sin(2*PI*pa)
            val db = da*ratio((i-HALF+.5)/rate)
            var remaining = 1.0; var elapsed = 0.0
            // Event order matters when the slave wraps immediately before master sync.
            while (remaining>1e-12) {
                val ma = (1-pa)/da
                val sb = (1-pb)/db
                val edge = if(pulse && pa<.5-1e-12) (.5-pa)/da else Double.POSITIVE_INFINITY
                val dt = minOf(remaining, ma, sb, edge)
                pa += da*dt; pb += db*dt; elapsed += dt; remaining -= dt
                val sync = abs(dt-ma)<1e-10
                val wrap = abs(dt-sb)<1e-10
                if(sync) {
                    jump(master,i+elapsed,if(pulse) 2.0 else -2.0)
                    // One effective jump when natural wrap and sync coincide.
                    jump(slave,i+elapsed,-2*pb)
                    pa=0.0; pb=0.0
                } else {
                    if(wrap) { jump(slave,i+elapsed,-2.0); pb=0.0 }
                    if(abs(dt-edge)<1e-10) jump(master,i+elapsed,-2.0)
                }
                if (dt<1e-14 && !sync && !wrap) break
            }
        }
        return FloatArray(frames) { j ->
            val i=j+HALF
            ((if(pulse) .60 else .35)*master[i]+(if(pulse) .32 else .60)*slave[i]+(if(pulse) .08 else .05)*support[i]).toFloat()
        }
    }
}
