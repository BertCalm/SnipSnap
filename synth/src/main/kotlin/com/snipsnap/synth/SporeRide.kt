package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** Jungle bow strike: a finite excitation of inharmonic plate modes, not rivet noise.
 * Shape constants, awaiting listening: sparse lower ping with a dense, faster-damped
 * upper wash. No cymbal recording, ongoing noise drive or per-hit normalization.
 * Fixed 36 modes and 1.35 seconds bound work; poles all have radius below one.
 */
internal object SporeRide {
    fun render(velocity:Double,take:Int):Snip {
        require(velocity.isFinite() && velocity in 0.0..1.0 && take>=0)
        val rate=Dsp.RATE*Dsp.OVERSAMPLE
        val raw=FloatArray((1.35*rate).roundToInt())
        val rng=Dsp.Noise(Dsp.seedFor("SPORE-BOW-RIDE",1,take))
        for(mode in 0 until 36) {
            val nominal=when(mode) { 0->2350.0;1->3187.0;2->4673.0;else->5100.0+(mode-3)*310.0+110*rng.next() }
            val hz=nominal*(1+.0025*rng.next())
            val t60=(if(mode<3)1.05-.14*mode else .58-.007*(mode-3))*(.9+.1*velocity)
            val radius=exp(-ln(1000.0)/(rate*t60));val angle=2*PI*hz/rate
            val co=radius*cos(angle);val si=radius*sin(angle)
            val gain=(if(mode==0).21 else if(mode<3).10 else .026)*velocity.pow(1.15)*
                (hz/2350.0).pow((velocity-1)*.8)*(1+.15*rng.next())
            val phase=if(mode<3).25*rng.next() else PI*rng.next()
            var re=cos(phase)*gain;var im=sin(phase)*gain
            for(i in raw.indices) {
                // A short contact onset avoids a discontinuity without erasing the ping.
                raw[i]+=(im*min(1.0,i/(rate*.00045))).toFloat()
                val next=re*co-im*si;im=re*si+im*co;re=next
            }
        }
        val hp=Dsp.FixedOnePole(1800f,rate);val lp=Dsp.FixedOnePole(10500f,rate)
        for(i in 0 until (rate*.009).toInt()) {
            val n=rng.next();val stick=lp.lp(n-hp.lp(n))
            raw[i]+=(stick*.14*velocity.pow(1.4)*exp(-i/(rate*.0012))*min(1.0,i/(rate*.0002))).toFloat()
        }
        Tide.bandLimit(raw,rate)
        val out=Dsp.decimate(raw,Dsp.RATE)
        val mean=out.average().toFloat();val dc=Dsp.FixedOnePole(20f)
        for(i in out.indices){val x=out[i]-mean;out[i]=x-dc.lp(x)}
        Dsp.fadeTail(out)
        check(out.all{it.isFinite()} && out.maxOf{abs(it)}<1)
        return Snip(out,1,Dsp.RATE)
    }
}
