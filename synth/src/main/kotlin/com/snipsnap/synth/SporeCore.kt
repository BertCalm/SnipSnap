package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** Shared procedural renderer for the two approved sources. Model constants select the
 * measured dictionaries; seed streams and conditioning remain pinned by Python PCM oracles.
 */
internal object SporeCore {
    private const val RATE=176400
    private const val FRAMES=19845
    data class Model(val id:String, val modes:DoubleArray, val edges:DoubleArray,
        val decays:DoubleArray, val bodyDecays:DoubleArray, val seeds:LongArray,
        val gains:DoubleArray, val outputGain:Double, val contactWidth:Double,
        val recoveryAmount:Double, val recoverySeconds:Double)
    private class Noise(seed:Long) {
        private var state=seed*6364136223846793005L+1442695040888963407L
        fun next():Double {
            state=state*6364136223846793005L+1442695040888963407L
            return (state ushr 11).toDouble()/9007199254740992.0*2-1
        }
    }
    private class Section(private val c:DoubleArray) {
        private var s1=0.0; private var s2=0.0
        fun next(x:Double):Double {
            val y=c[0]*x+s1
            s1=c[1]*x-c[4]*y+s2; s2=c[2]*x-c[5]*y
            return y
        }
    }
    /** Bilinear transform of the order-two Butterworth bandpass prototype.
     * Two stable conjugate pole pairs; zeros at ±1. No iterative solver or coefficient grid.
     */
    private fun bandpass(lo:Double,hi:Double):Array<Section> {
        val fs2=2.0*RATE
        val low=fs2*tan(PI*lo/RATE); val high=fs2*tan(PI*hi/RATE)
        val bandwidth=high-low; val center=sqrt(low*high)
        val r=-sqrt(.5)*bandwidth/2; val im=sqrt(.5)*bandwidth/2
        val zr=r*r-im*im-center*center; val zi=2*r*im
        val magnitude=hypot(zr,zi)
        val sr=sqrt((magnitude+zr)/2); val si=-sqrt((magnitude-zr)/2)
        val denoms=(0..1).map { i ->
            val ar=r+(if(i==0)sr else -sr); val ai=im+(if(i==0)si else -si)
            val denom=(fs2-ar).pow(2)+ai*ai
            val pr=(fs2*fs2-ar*ar-ai*ai)/denom; val pi=2*fs2*ai/denom
            doubleArrayOf(1.0,-2*pr,pr*pr+pi*pi)
        }
        val omega=2*atan(center/fs2)
        fun response(d:DoubleArray)=hypot(1+d[1]*cos(omega)+d[2]*cos(2*omega),
            -d[1]*sin(omega)-d[2]*sin(2*omega))
        val gain=sqrt(response(denoms[0])*response(denoms[1])/(4*sin(omega).pow(2)))
        return denoms.map { Section(doubleArrayOf(gain,0.0,-gain,1.0,it[1],it[2])) }.toTypedArray()
    }

    fun render(model:Model, tune:Double, strike:Double, body:Double, texture:Double, velocity:Double, take:Int):Snip {
        require(velocity.isFinite() && velocity in 0.0..1.0 && take>=0)
        if(velocity==0.0) return Snip(FloatArray(FRAMES),1,Dsp.RATE)
        val seed=model.seeds.getOrNull(take) ?: Dsp.seedFor(model.id,1,take).toLong()
        val rng=Noise(seed); val n=FRAMES*4
        val pitch=2.0.pow((tune-.5)*8/12)
        val contact=model.contactWidth*(1.35-.35*velocity)*2.0.pow(1-2*strike)*(1+.15*rng.next())
        val noise=DoubleArray(n) { rng.next() }; val warm=DoubleArray((.04*RATE).roundToInt()) { rng.next() }
        val bands=Array(model.edges.size-1) { b ->
            val filters=bandpass(model.edges[b]*pitch,model.edges[b+1]*pitch)
            for(x in warm) filters[1].next(filters[0].next(x))
            val scale=sqrt(3*RATE/(2*(model.edges[b+1]-model.edges[b])*pitch))
            DoubleArray(n) { filters[1].next(filters[0].next(noise[it]))*scale }
        }
        val attack=DoubleArray(n) { 1-exp(-it/(RATE*contact)) }
        val recovery=DoubleArray(n) { model.recoveryAmount*velocity*model.recoverySeconds*(1-exp(-it/(RATE*model.recoverySeconds))) }
        val raw=DoubleArray(n); val force=velocity.pow(1.15)*model.outputGain
        var index=0
        for(hz in model.modes) for(decay in model.bodyDecays) {
            val phase=.20*rng.next(); val gain=(1+.12*rng.next())*force*model.gains[index++]
            val factor=exp(-ln(1000.0)/(RATE*decay*2.0.pow((body-.5)*1.5)))
            var envelope=1.0
            for(i in raw.indices) {
                raw[i]+=sin(2*PI*hz*pitch*(i.toDouble()/RATE-recovery[i])+phase)*attack[i]*envelope*gain
                envelope*=factor
            }
        }
        for(b in bands.indices) for(decay in model.decays) {
            val brightness=sqrt(model.edges[b]*model.edges[b+1])/1500
            val gain=brightness.pow((velocity-1)*.28+(strike-.5)*.3)*(1+.10*rng.next())*force*model.gains[index++]
            val factor=exp(-ln(1000.0)/(RATE*decay*2.0.pow((texture-.5)*1.2)))
            var envelope=1.0
            for(i in raw.indices) { raw[i]+=bands[b][i]*attack[i]*envelope*gain; envelope*=factor }
        }
        return finish(raw)
    }

    private fun finish(raw:DoubleArray):Snip {
        val sections=SporeSnareData.LOWPASS.map(::Section)
        for(i in raw.indices) { var x=raw[i]; for(s in sections)x=s.next(x); raw[i]=x }
        fun half(x:DoubleArray):DoubleArray = DoubleArray((x.size+1)/2) { j ->
            var value=0.0; val at=j*2
            for(k in 0..min(at,SporeSnareData.FIR.lastIndex)) value+=SporeSnareData.FIR[k]*x[at-k]
            value
        }
        val out=half(half(raw)); val mean=out.average(); val rc=1/(2*PI*20)
        val alpha=rc/(rc+1.0/Dsp.RATE); var oldX=0.0; var oldY=0.0
        val fade=(.004*Dsp.RATE).toInt()
        for(i in out.indices) {
            val x=out[i]-mean; oldY=alpha*(oldY+x-oldX); oldX=x; out[i]=oldY
            if(i>=out.size-fade)out[i]*=(out.lastIndex-i).toDouble()/(fade-1)
        }
        check(out.all { it.isFinite() } && out.maxOf { abs(it) }<1) { "Spore core exceeds fixed headroom" }
        return Snip(FloatArray(out.size) { out[it].toFloat() },1,Dsp.RATE)
    }
}
