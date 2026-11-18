package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.WavWriter
import kotlin.math.*

/** Model 4 Tap-only port of the reference-guided Python studies.
 * Fresh Dsp.Noise excitation, coarse magnitude fits and authored movement; no recording PCM.
 * House RNG/decimation differ from Python, so listening and measured parity remain separate.
 */
internal object FootworkTap {
    private const val FS=176400
    private const val RATE=WavWriter.MPC_SAMPLE_RATE
    private class Noise(role: Int,take: Int) {
        private val source=Dsp.Noise(Dsp.seedFor("FOOTWORK-TAP",4,role,take))
        fun uniform(a: Double,b: Double)=a+(source.next().toDouble()+1)*.5*(b-a)
        fun gaussian()=sqrt(-2*ln(uniform(1e-9,1.0)))*cos(2*PI*uniform(0.0,1.0))
    }
    private fun interpolate(x: Double,xs: DoubleArray,ys: DoubleArray): Double {
        if(x<=xs.first()) return ys.first()
        for(i in 1 until xs.size) if(x<=xs[i]) return ys[i-1]+(ys[i]-ys[i-1])*(x-xs[i-1])/(xs[i]-xs[i-1])
        return ys.last()
    }
    private fun response(voice: Int,warp: Double): FloatArray {
        val size=16384
        val hz=(doubleArrayOf(0.0,30.0)+FootworkTapData.frequencies+doubleArrayOf(22000.0,FS/2.0)).map { it*warp }.toDoubleArray()
        val db=doubleArrayOf(-100.0,-45.0)+FootworkTapData.spectra[voice]+doubleArrayOf(-80.0,-100.0)
        val re=FloatArray(size) { i -> (interpolate(minOf(i,size-i)*FS.toDouble()/size,hz,db)*ln(10.0)/20).toFloat() }
        val im=FloatArray(size)
        Fft.inverse(re,im)
        for(i in 1 until size/2) re[i]*=2f
        for(i in size/2+1 until size) re[i]=0f
        im.fill(0f);Fft.forward(re,im)
        for(i in re.indices) { val magnitude=exp(re[i].toDouble());val phase=im[i].toDouble();re[i]=(magnitude*cos(phase)).toFloat();im[i]=(magnitude*sin(phase)).toFloat() }
        Fft.inverse(re,im)
        val h=re.copyOf(8192)
        for(i in 0 until 512) h[h.size-512+i]*=(1-i/511.0).toFloat()
        val norm=sqrt(h.sumOf { it.toDouble()*it })
        for(i in h.indices) h[i]=(h[i]/norm).toFloat()
        return h
    }
    private fun convolve(x: FloatArray,h: FloatArray): FloatArray {
        var n=1;while(n<x.size+h.size-1)n*=2
        val a=x.copyOf(n);val ai=FloatArray(n);val b=h.copyOf(n);val bi=FloatArray(n)
        Fft.forward(a,ai);Fft.forward(b,bi)
        for(i in a.indices) { val ar=a[i];a[i]=ar*b[i]-ai[i]*bi[i];ai[i]=ar*bi[i]+ai[i]*b[i] }
        Fft.inverse(a,ai);return a
    }
    private fun filter(x: FloatArray) {
        for(c in FootworkTapData.brushFilter) {
            var z1=0.0;var z2=0.0
            for(i in x.indices) { val a=x[i].toDouble();val b=c[0]*a+z1;z1=c[1]*a-c[4]*b+z2;z2=c[2]*a-c[5]*b;x[i]=b.toFloat() }
        }
    }
    private fun finish(raw: FloatArray): FloatArray {
        Tide.bandLimit(raw,FS);val y=Dsp.decimate(raw,RATE)
        val mean=y.average();val pole=1/(1+2*PI*20/RATE);var state=0.0;var previous=0.0
        for(i in y.indices) { val x=y[i]-mean;state=pole*(state+x-previous);previous=x;y[i]=state.toFloat() }
        for(i in 0 until 176)y[y.lastIndex-i]*=i/175f
        return y
    }
    private data class Key(val role: Int,val velocity: Double,val take: Int,val travel: Double)
    // Caller-owned mixing never modifies a source. Bounded cache: at most ~3 MiB
    // of immutable PCM, keyed by every excitation/envelope parameter, no global RNG.
    private val cache=object: LinkedHashMap<Key,FloatArray>(64,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key,FloatArray>?)=size>64
    }
    internal fun clearCache()=synchronized(cache) { cache.clear() }
    private fun contact(role: Int,v: Double,take: Int,travel: Double=1.0): FloatArray {
        val key=Key(role,v,take,travel)
        synchronized(cache) { cache[key]?.let { return it } }
        val y=synthesize(role,v,take,travel)
        synchronized(cache) { cache[key]=y }
        return y
    }
    private fun synthesize(role: Int,v: Double,take: Int,travel: Double): FloatArray {
        val rng=Noise(role,take);val strike=role<2;val smooth=role>=4;val forward=role==2 || role==4
        val n=if(strike) 42336 else 28224
        // Softer brush pressure travels a little longer and excites a darker response.
        // Hard-strike settings retain the study's values; levels are never normalized.
        val duration=if(strike) 0.0 else ((if(smooth) { if(forward) .110 else .090 } else { if(forward) .062 else .043 })*(1+rng.uniform(-.07,.07))*travel*(1.08-.08*v))
        val warp=if(strike) (.965+.035*v)*(1+rng.uniform(-.012,.012)) else (if(forward) 1.55 else 1.85)*(1+rng.uniform(-.025,.025))*(.965+.035*v)
        val h=response(if(role==1) 1 else 0,warp)
        val noise=FloatArray(n+h.size) { rng.gaussian().toFloat() }
        val convolution=convolve(noise,h)
        val carrier=FloatArray(n) { convolution[h.size+it] }
        val raw=FloatArray(n)
        if(strike) {
            val p=FootworkTapData.envelopes[role].copyOf();val time=(1.04-.04*v)*(1+rng.uniform(-.04,.04))
            for(i in 0..2)p[i]*=time
            p[3]*=1+rng.uniform(-.08,.08)
            for(i in raw.indices) {
                val t=i.toDouble()/FS;val u=maxOf(0.0,t-p[1])
                val env=(1-exp(-t/.00035))*exp(-t/p[0])+p[3]*(u/p[2])*exp(1-u/p[2])+p[4]*(1-exp(-t/.002))*exp(-t/.032)
                val direct=if(i<h.size) h[i].toDouble() else 0.0
                raw[i]=((.92*carrier[i]+.08*sqrt(FS*.008)*direct)*sqrt(maxOf(0.0,env))*v.pow(1.35)*FootworkTapData.GAIN).toFloat()
            }
        } else {
            filter(carrier);val grains=DoubleArray(16) { rng.uniform(.55,1.3) }
            for(i in raw.indices) {
                val t=i.toDouble()/FS;val phase=(t/duration).coerceIn(0.0,1.0)
                val env=sin(PI*phase).pow(if(smooth) 2.0 else if(forward) 1.3 else .8)
                val pos=i.toDouble()/n*15;val k=pos.toInt().coerceAtMost(14);val grain=grains[k]+(grains[k+1]-grains[k])*(pos-k)
                raw[i]=(carrier[i]*env*grain*v.pow(1.35)*(if(forward) .30 else .36)*(if(smooth) .70 else 1.0)).toFloat()
            }
        }
        return finish(raw)
    }
    internal fun plan(name: String)=FootworkTapData.plans.getValue(name)
    internal fun sequence(name: String,v: Double,take: Int): FloatArray {
        val p=plan(name);val y=FloatArray((p.seconds*RATE).roundToInt());val counts=mutableMapOf<Pair<Int,Boolean>,Int>()
        for(e in p.events) {
            val key=e.role to e.right;val count=counts.getOrDefault(key,0);counts[key]=count+1
            val rr=(count+take+if(e.right) 2 else 0)%4
            val x=contact(e.role,e.force*v,rr,e.travel);val at=(e.seconds*RATE).roundToInt()
            for(i in 0 until minOf(x.size,y.size-at))y[at+i]+=.82f*x[i]
        }
        for(i in 0 until 176)y[y.lastIndex-i]*=i/175f
        return y
    }
    fun render(voice: FootworkVoice,v: Double,take: Int): FloatArray?=when(voice) {
        FootworkVoice.TOE -> contact(0,v,take).copyOf()
        FootworkVoice.HEEL -> contact(1,v,take).copyOf()
        FootworkVoice.BRUSH_FORWARD -> contact(4,v,take).copyOf()
        FootworkVoice.BRUSH_BACK -> contact(5,v,take).copyOf()
        FootworkVoice.SHUFFLE -> sequence("shuffle",v,take)
        FootworkVoice.FLAP -> sequence("flap",v,take)
        FootworkVoice.ROLL -> sequence("roll",v,take)
        FootworkVoice.RISER -> sequence("build",v,take)
        else -> null
    }
}
