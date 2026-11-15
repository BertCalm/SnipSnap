package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** Finite dance roles; ordered as the eighteen pads in each FOOTWORK kit. */
enum class FootworkVoice {
    STAMP, HEEL, TOE, BALL, BRUSH_FORWARD, BRUSH_BACK, SHUFFLE, FLAP,
    CLAP, THIGH, CHEST, CLICK, BUCKET, BROOM, METAL, BOARD, ROLL, RISER
}

/**
 * Full-dynamics FOOTWORK: contact -> tiny metallic response -> weighted sole/floor -> finite ending.
 * The owner selected the Python full-dynamics audition. This native port preserves its coefficients,
 * envelopes and fixed gain, but uses Dsp.Noise Gaussian draws and the house decimator. Native listening
 * is therefore a separate gate. Body/object voices and the three surfaces are new authored hypotheses.
 * No reference PCM, per-hit peak normalization, room convolution, or shared mutable noise state.
 */
object Footwork {
    const val VERSION = 1
    private const val FS = Dsp.RATE * 4
    private const val RATE = Dsp.RATE
    private const val N = 38808 // 220 ms at 4x
    private const val GAIN = 2.0
    private class Noise(seed: Int) {
        val source = Dsp.Noise(seed)
        fun uniform(a: Double, b: Double) = a + (source.next().toDouble()+1)*.5*(b-a)
        fun gaussian(): Double = sqrt(-2*ln(uniform(1e-9,1.0))) * cos(2*PI*uniform(0.0,1.0))
        fun samples(n: Int) = DoubleArray(n) { gaussian() }
    }
    private fun noise(tag: String, take: Int) = Noise(Dsp.seedFor("FOOTWORK", VERSION, tag, take))
    private fun filter(x: DoubleArray, sos: Array<DoubleArray>): DoubleArray {
        var y=x.copyOf()
        for (c in sos) {
            var z1=0.0; var z2=0.0
            for (i in y.indices) { val a=y[i]; val b=c[0]*a+z1; z1=c[1]*a-c[4]*b+z2; z2=c[2]*a-c[5]*b; y[i]=b }
        }
        return y
    }
    private fun band(x: DoubleArray, low: Int, high: Int) = filter(x,FootworkData.filters.getValue("${low}_${high}"))
    private fun down(x: DoubleArray): DoubleArray {
        val raw=FloatArray(x.size) { x[it].toFloat() }; Tide.bandLimit(raw,FS)
        return Dsp.decimate(raw,RATE).map { it.toDouble() }.toDoubleArray()
    }
    private fun finish(x: DoubleArray): DoubleArray {
        val mean=x.average(); var previous=0.0; var state=0.0; val pole=exp(-2*PI*20/RATE)
        for(i in x.indices) { val input=x[i]-mean; state=input-previous+pole*state; previous=input; x[i]=state }
        val fade=minOf(176,x.size)
        for(i in 0 until fade) x[x.lastIndex-i]*=i.toDouble()/(fade-1)
        return x
    }
    private fun add(a: DoubleArray,b: DoubleArray,gain: Double=1.0,delay: Double=0.0) {
        val start=(delay*RATE).roundToInt(); for(i in 0 until minOf(b.size,a.size-start)) a[start+i]+=gain*b[i]
    }
    // Only envelope coefficients are cached: no random draws or rendered takes enter this cache.
    private val envelopes: Array<DoubleArray> by lazy { Array(12) { band -> DoubleArray(N) { i ->
        val t=i.toDouble()/FS
        sqrt(FootworkData.decays.indices.sumOf { j -> exp(-2*t/FootworkData.decays[j])*FootworkData.powers[band][j] })
    } } }
    private fun contact(v: Double,take: Int): DoubleArray {
        val rng=noise("contact",take); val grains=rng.samples(N+(FS*.06).toInt()); val out=DoubleArray(N)
        for(b in 0 until 12) {
            val low=FootworkData.edges[b].toInt(); val high=FootworkData.edges[b+1].toInt()
            val filtered=band(grains,low,high); val offset=filtered.size-N
            val gain=(sqrt(low.toDouble()*high)/1500).pow((v-1)*.28)*(1+rng.uniform(-.05,.05))/sqrt(FootworkData.variance[b])
            for(i in out.indices) out[i]+=filtered[offset+i]*envelopes[b][i]*gain
        }
        for(i in out.indices) out[i]*=minOf(1.0,i.toDouble()/FS/.00035)*v.pow(1.15)*FootworkData.gain
        val y=down(out); for(i in y.indices) y[i]*=exp(-i.toDouble()/RATE/FootworkData.damping)
        return finish(y)
    }
    private fun peakBand(x: DoubleArray,f: Double,q: Double): DoubleArray {
        // scipy iirpeak's unity-peak biquad; broad Q keeps metal from becoming a pitched bell.
        val w=2*PI*f/FS; val beta=tan(w/(2*q)); val gain=1/(1+beta)
        return filter(x,arrayOf(doubleArrayOf(1-gain,0.0,-(1-gain),1.0,-2*gain*cos(w),2*gain-1)))
    }
    private fun metal(v: Double,take: Int,openness: Double=0.0): DoubleArray {
        val first=contact(v,take); val rng=noise("plate",take); val delay=(.012+.009*openness)*(1+rng.uniform(-.1,.1))
        val grains=rng.samples(N); val plate=DoubleArray(N); val ring=DoubleArray(N)
        for((freq,weight) in listOf(2350.0 to 1.0,3470.0 to .8,5130.0 to .6,7310.0 to .35)) {
            val y=peakBand(grains,freq*(1+rng.uniform(-.025,.025)),5.5)
            for(i in plate.indices) plate[i]+=weight*y[i]
        }
        for((freq,weight) in listOf(2910.0 to 1.0,4670.0 to .55,6830.0 to .3)) {
            val f=freq*(1+rng.uniform(-.018,.018))
            for(i in ring.indices) { val u=maxOf(0.0,i.toDouble()/FS-delay); ring[i]+=weight*sin(2*PI*f*u)*exp(-u/(.012+.01*openness)) }
        }
        val high=DoubleArray(N) { i ->
            val u=i.toDouble()/FS-delay
            if(u<0) 0.0 else {
                val env=(1-exp(-u/.0007))*exp(-u/(.018+.012*openness))
                val tick=if(u>=.004) exp(-(u-.004)/.0015)*grains[i] else 0.0
                (.28*plate[i]+.012*ring[i]+.014*tick)*env*v.pow(1.25)
            }
        }
        for(i in first.indices) first[i]*=.72
        add(first,down(high)); return finish(first)
    }
    private fun weight(x: DoubleArray,v: Double,take: Int,amount: Double,delay: Double=0.0): DoubleArray {
        val rng=noise("weight",take); val grains=band(rng.samples(N),120,1800)
        val low=155+rng.uniform(-8.0,8.0); val mid=390+rng.uniform(-22.0,22.0)
        val body=DoubleArray(N) { i -> val t=i.toDouble()/FS
            (.18*sin(2*PI*low*t)*exp(-t/.024)+.10*sin(2*PI*mid*t)*exp(-t/.009)+.42*grains[i]*exp(-t/.014))*minOf(1.0,t/.0011)*v.pow(1.3)*amount
        }
        add(x,down(body),delay=delay); return finish(x)
    }
    private fun brush(v: Double,take: Int,back: Boolean,broom: Boolean=false): DoubleArray {
        val rng=noise(if(back) "brush-back" else "brush-forward",take); val grains=band(rng.samples(N),if(back) 1000 else 650,if(back) 10500 else 7500)
        val duration=if(broom) .17 else if(back) .058 else .078; val phase=rng.uniform(0.0,6.28)
        val raw=DoubleArray(N) { i -> val t=i.toDouble()/FS; val u=(t/duration).coerceIn(0.0,1.0)
            val pressure=sin(PI*u).pow(if(back) .8 else 1.4)*(if(back) 1.15-.65*u else .45+.7*u)
            .15*grains[i]*pressure*(.6+.4*sin(2*PI*(130*t+1100*t*t)+phase).pow(2))*v.pow(1.2)
        }
        val y=down(raw); add(y,metal(v,take+200),.23,duration*.72)
        return weight(finish(y),v,take,.25,.04)
    }
    private fun skin(voice: FootworkVoice,v: Double,take: Int): DoubleArray {
        val rng=noise(voice.name,take); val bright=voice==FootworkVoice.CLAP || voice==FootworkVoice.CLICK
        val grains=band(rng.samples(N),if(bright) 600 else 70,if(bright) 13000 else 900)
        val duration=when(voice) { FootworkVoice.CLAP -> .018; FootworkVoice.CLICK -> .003; FootworkVoice.THIGH -> .014; else -> .025 }
        val center=when(voice) { FootworkVoice.CHEST -> 130.0; FootworkVoice.THIGH -> 310.0; FootworkVoice.CLICK -> 1750.0; else -> 540.0 }*(1+rng.uniform(-.05,.05))
        val raw=DoubleArray(N) { i -> val t=i.toDouble()/FS
            var env=exp(-t/duration)*minOf(1.0,t/.0004)
            if(voice==FootworkVoice.CLAP) for(d in listOf(.004,.009)) if(t>=d) env+=.6*exp(-(t-d)/.009)
            (.36*grains[i]*v.pow(1.35)+.13*sin(2*PI*center*t)*v.pow(1.1))*env
        }
        if(voice==FootworkVoice.CLAP) for(i in raw.indices) raw[i]*=.45
        return finish(down(raw))
    }
    private fun atom(voice: FootworkVoice,v: Double,take: Int): DoubleArray = when(voice) {
        FootworkVoice.TOE -> weight(metal(v,take),v,take,.7)
        FootworkVoice.HEEL,FootworkVoice.STAMP,FootworkVoice.BALL -> {
            val heel=filter(metal(v,take+400,.25),FootworkData.heelLowpass)
            for(i in heel.indices) heel[i]*=if(voice==FootworkVoice.BALL) .45 else .85
            val rng=noise("heel",take); val grains=band(rng.samples(N),100,1700); val f=210+rng.uniform(-12.0,12.0)
            val body=DoubleArray(N) { i -> val t=i.toDouble()/FS
                (.18*grains[i]+.07*sin(2*PI*f*t))*exp(-t/.012)*minOf(1.0,t/.0007)*v.pow(1.1)
            }
            add(heel,down(body)); weight(heel,v,take,when(voice) { FootworkVoice.STAMP -> 1.2; FootworkVoice.BALL -> .65; else -> 1.0 })
        }
        FootworkVoice.BRUSH_FORWARD,FootworkVoice.BRUSH_BACK,FootworkVoice.BROOM -> brush(v,take,voice==FootworkVoice.BRUSH_BACK,voice==FootworkVoice.BROOM)
        FootworkVoice.CLAP,FootworkVoice.THIGH,FootworkVoice.CHEST,FootworkVoice.CLICK -> skin(voice,v,take)
        else -> error("not an atomic foot/body voice: $voice")
    }
    private data class Event(val at: Double,val voice: FootworkVoice,val force: Double)
    private fun gesture(voice: FootworkVoice,v: Double,take: Int,kit: FootworkKits.Kit): DoubleArray {
        val events=mutableListOf<Event>(); val seconds=when(voice) {
            FootworkVoice.SHUFFLE,FootworkVoice.FLAP -> { events+=Event(0.0,FootworkVoice.BRUSH_FORWARD,.8); events+=Event(.095,if(voice==FootworkVoice.SHUFFLE) FootworkVoice.BRUSH_BACK else FootworkVoice.TOE,1.0); .42 }
            FootworkVoice.ROLL -> { for(i in 0..15) events+=Event(i*.115,if(i%2==0) FootworkVoice.TOE else FootworkVoice.HEEL,if(i%4==0) 1.0 else .72); events+=Event(2.0,FootworkVoice.HEEL,1.0); 2.4 }
            else -> {
                var at=0.0; var interval=.2; var i=0
                while(at<2.55) { events+=Event(at,if(i++%2==0) FootworkVoice.TOE else FootworkVoice.HEEL,.3+.65*at/2.55); at+=interval; interval=maxOf(.045,interval*.92) }
                events+=Event(2.65,FootworkVoice.STAMP,1.0); 3.1
            }
        }
        val y=DoubleArray((seconds*RATE).roundToInt())
        for((i,e) in events.withIndex()) add(y,surface(atom(e.voice,v*e.force,take*67+i),kit,e.voice),delay=e.at)
        return finish(y)
    }
    private fun surface(x: DoubleArray,kit: FootworkKits.Kit,voice: FootworkVoice): DoubleArray {
        if(kit==FootworkKits.Kit.TAP) return x
        // Different contact balance and floor decay, not a reverb or BPM-only genre change.
        val pole=exp(-2*PI*(if(kit==FootworkKits.Kit.STREET) 1600 else 4200)/RATE); var low=0.0
        val y=x.copyOf()
        for(i in y.indices) { low=(1-pole)*x[i]+pole*low
            y[i]=if(kit==FootworkKits.Kit.STREET) (.9*x[i]+.35*low)*exp(-i.toDouble()/RATE/.14)
                 else (1.1*x[i]-.25*low)*exp(-i.toDouble()/RATE/.095)
        }
        if(kit==FootworkKits.Kit.INDUSTRIAL && voice in listOf(FootworkVoice.STAMP,FootworkVoice.HEEL,FootworkVoice.BALL)) {
            val peak=x.maxOf { abs(it) }
            for(i in y.indices) { val t=i.toDouble()/RATE; y[i]+=.055*sin(2*PI*790*t)*exp(-t/.035)*minOf(1.0,t/.001)*peak }
        }
        return finish(y)
    }
    private val objectGains=ConcurrentHashMap<Pair<FootworkKits.Kit,FootworkVoice>,Double>()
    private fun objectSound(voice: FootworkVoice,v: Double,take: Int,kit: FootworkKits.Kit): DoubleArray {
        val source=when(voice) { FootworkVoice.BUCKET -> ClutterVoice.BUCKET; FootworkVoice.METAL -> ClutterVoice.BINLID; else -> ClutterVoice.BOARD }
        val macros=mapOf("TUNE" to (if(kit==FootworkKits.Kit.TAP) .65f else if(kit==FootworkKits.Kit.STREET) .35f else .5f),"TOOL" to (if(kit==FootworkKits.Kit.TAP) .15f else if(kit==FootworkKits.Kit.STREET) .55f else .9f),"DAMP" to .65f,"RATTLE" to (if(kit==FootworkKits.Kit.INDUSTRIAL) .65f else .2f),"MIC" to 0f)
        // One fixed calibration for all velocities AND takes. Never change CLUTTER's accepted renderer.
        val g=objectGains.getOrPut(kit to voice) { .30 / Clutter.renderRaw(source,macros,1f,0).maxOf { abs(it) }.coerceAtLeast(1e-9f) }
        return Clutter.renderRaw(source,macros,v.toFloat(),take%4).map { it*g*v.pow(1.15) }.toDoubleArray()
    }
    fun render(voice: FootworkVoice,velocity: Float=1f,take: Int=0,kit: FootworkKits.Kit=FootworkKits.Kit.TAP): Snip {
        require(velocity.isFinite() && velocity in 0f..1f && take in 0..3)
        val v=velocity.toDouble()
        val y=when(voice) {
            FootworkVoice.SHUFFLE,FootworkVoice.FLAP,FootworkVoice.ROLL,FootworkVoice.RISER -> gesture(voice,v,take,kit)
            FootworkVoice.BUCKET,FootworkVoice.METAL,FootworkVoice.BOARD -> objectSound(voice,v,take,kit)
            else -> surface(atom(voice,v,take),kit,voice)
        }
        val out=FloatArray(y.size) { if(velocity==0f) 0f else (y[it]*GAIN*(if(kit==FootworkKits.Kit.TAP) 1.0 else .85)).toFloat() }
        require(out.all { it.isFinite() && abs(it)<.98f }) { "FOOTWORK peak budget exceeded: $kit $voice $velocity $take peak=${out.maxOf { abs(it) }}" }
        return Snip(out,1,RATE)
    }
}
