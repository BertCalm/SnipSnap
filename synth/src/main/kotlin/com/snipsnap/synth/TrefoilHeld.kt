package com.snipsnap.synth

import com.snipsnap.audio.Snip
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.linear.LUDecomposition
import java.util.concurrent.CancellationException
import kotlin.math.*

/** Ordered, powered Trefoil. Finite acoustic models and their noise streams are untouched.
 *
 * Contact -> circulating modal energy -> power withdrawal -> natural decay.
 * Each sample rotates all three bodies' full (omega*q,v) states orthogonally,
 * then applies strictly positive loss. The affine period map therefore has one
 * fixed point. Solve that point per partial, then solve feedforward memory.
 * No limiter, state reset or wrap crossfade establishes recurrence.
 *
 * VERSION identifies the house LCG source, periodic cubic geometry and shared
 * output stage. The Python audition used PCG64; reference tests can supply its
 * exact force separately to distinguish source differences from DSP errors.
 */
object TrefoilHeld {
    const val VERSION = 1
    const val DEFAULT_RELEASE_SECONDS = .6f
    const val MIN_RELEASE_SECONDS = .1f
    const val MAX_RELEASE_SECONDS = 1.5f
    const val MIN_MOVING_WEAVE = .34f
    const val CACHE_MAX_BYTES = 8 * 1024 * 1024
    private const val IR = Dsp.RATE * Dsp.OVERSAMPLE
    private const val TAU = 2 * PI
    private val ratios = doubleArrayOf(1.0,2.0,3.0,4.0,5.0,6.0,8.0,10.0)
    private val colors = arrayOf(doubleArrayOf(.85,.90,.25,.12,.08,.04,.02,.01),
        doubleArrayOf(.75,.20,.95,.25,.65,.20,.10,.04),doubleArrayOf(.60,.15,.25,.95,.25,.85,.70,.50))
    private val weights = doubleArrayOf(.10,1.0,1.35,1.7,2.0,2.3,2.6,2.9)

    fun defaults(voice: TrefoilVoice) = Trefoil.defaults(voice) + mapOf("WEAVE" to .42f,"NUDGE" to 0f,"HOLD" to 1f)
    fun macrosFor(voice: TrefoilVoice): List<MacroSpec> {
        val held = defaults(voice)
        return Trefoil.macrosFor(voice).filter { it.name !in setOf("NUDGE","HOLD") }
            .map { it.copy(default = held.getValue(it.name), neutral = held.getValue(it.name)) }
    }
    fun supports(p: TrefoilPatch) = p.playback == TrefoilPlayback.HELD && p.model == 2 && p.heldVersion == VERSION
    internal fun validate(voice: TrefoilVoice, macros: Map<String,Float>) {
        val m = defaults(voice) + macros
        require(m.getValue("NUDGE") == 0f && m.getValue("HOLD") == 1f) { "TREFOIL held playback requires NUDGE=0 and HOLD=1" }
        val w = m.getValue("WEAVE")
        require(w == 0f || w in MIN_MOVING_WEAVE..1f) { "TREFOIL held WEAVE must be 0 (stationary) or $MIN_MOVING_WEAVE..1" }
    }

    data class Diagnostics(val periodFrames: Int, val sourceHz: Double, val referenceHz: Double,
        val stateRelativeError: Double, val precedingSeam: Double, val followingSeam: Double,
        val rawPeak: Double, val gain: Double, val coreContraction: Double,
        val planMillis: Double, val solveMillis: Double, val totalMillis: Double)
    data class Held(val snip: Snip, val loopStartFrame: Int, val loopEndExclusive: Int, val diagnostics: Diagnostics)
    data class Loop(val samples: FloatArray, val context: FloatArray, val diagnostics: Diagnostics)
    data class Note(val snip: Snip, val internalRaw: FloatArray, val noteOffFrame: Int,
        val driveStopsFrame: Int, val unforcedCoreRise: Double)

    private data class Geometry(val values: Array<DoubleArray>)
    private val geometry: Geometry by lazy {
        val trace = TrefoilTrajectory.solve(TrefoilTrajectory.reference(),TrefoilTrajectory.PERIOD,8193)
        check(trace.diagnostics.outcome == "COMPLETE") { "TREFOIL held reference orbit failed" }
        check(trace.states.last().indices.maxOf { abs(trace.states.last()[it]-trace.states.first()[it]) } < 1e-6)
        Geometry(Array(8192) { at ->
            val y=trace.states[at]; val d=DoubleArray(9); val distance=DoubleArray(3)
            for(k in 0..2) {
                val (i,j)=TrefoilTrajectory.pairs[k]; val dx=y[2*j]-y[2*i]; val dy=y[2*j+1]-y[2*i+1]; val r=hypot(dx,dy)
                val radial=(dx*(y[6+2*j]-y[6+2*i])+dy*(y[7+2*j]-y[7+2*i]))/r
                d[k]=1/(1+(r/1.15).pow(4));d[k+3]=tanh(-radial/1.8);distance[i]+=r*.5;distance[j]+=r*.5
            }
            for(b in 0..2)d[6+b]=1/(1+(distance[b]/3.5).pow(2))
            d
        })
    }
    private fun descriptor(phase: Double, out: DoubleArray) {
        val table=geometry.values;val at=phase*table.size;val k=at.toInt();val t=at-k
        val a=table[(k-1+table.size)%table.size];val b=table[k%table.size]
        val c=table[(k+1)%table.size];val d=table[(k+2)%table.size]
        for(j in out.indices)out[j]=b[j]+.5*t*(c[j]-a[j]+t*(2*a[j]-5*b[j]+4*c[j]-d[j]+t*(3*(b[j]-c[j])+d[j]-a[j])))
    }
    internal data class Plan(val frames: Int,val sourceHz: Double,val root: Double,
        val rotations: DoubleArray,val force: DoubleArray,val excite: DoubleArray,val pickup: DoubleArray,
        val co: DoubleArray,val si: DoubleArray,val memoryCo: DoubleArray,val memorySi: DoubleArray,
        val memoryT60: DoubleArray,val send: Double,val ret: Double,val contraction: Double) {
        val count get()=frames*Dsp.OVERSAMPLE
    }
    private fun stop(cancelled: () -> Boolean) {
        if(Thread.currentThread().isInterrupted || cancelled())throw CancellationException("TREFOIL held render cancelled")
    }
    internal fun plan(p: TrefoilPatch, noiseEnabled: Boolean=true, cancelled: () -> Boolean = { false }): Plan {
        require(supports(p));validate(p.voice,p.macros);stop(cancelled)
        val m=defaults(p.voice)+p.macros;val weave=m.getValue("WEAVE");val thread=m.getValue("THREAD").toDouble()
        val memory=m.getValue("MEMORY").toDouble();val exchange=m.getValue("EXCHANGE").toDouble()
        val root=440.0*2.0.pow((p.midi-69)/12.0)
        val seconds=1/TrefoilTrajectory.cyclesPerSecond(if(weave==0f).42f else weave)
        require(seconds<=6.0) { "TREFOIL held period exceeds six seconds" }
        val cycles=round(root*seconds).toInt().coerceAtLeast(1);val frames=(round(cycles/root*Dsp.RATE/8)*8).toInt()
        val count=frames*Dsp.OVERSAMPLE;val sourceHz=cycles*Dsp.RATE.toDouble()/frames
        val rotations=DoubleArray(count/32*72);val force=DoubleArray(count*3)
        val excite=DoubleArray(24);val pickup=DoubleArray(24);val co=DoubleArray(24);val si=DoubleArray(24)
        var contraction=0.0
        for(b in 0..2) {
            val norm=sqrt((0..7).sumOf { k -> (colors[b][k]*(if(k==0).8 else .8+1.5*thread)).pow(2) })
            for(k in 0..7) {
                val i=k*3+b;excite[i]=colors[b][k]*(if(k==0).8 else .8+1.5*thread)/norm
                pickup[i]=colors[b][k]*(if(k==0).45+.20*(1-min(1.0,root/261.625565)) else .8+1.2*thread)*.8/sqrt(3.0)
                val hz=root*ratios[k]*(1+if(k>=4).003*thread.pow(3)*(b-1) else 0.0)
                val t60=if(k==0)1.4 else (.65+1.1*thread)/ratios[k].pow(.18)
                val r=exp(-ln(1000.0)/t60/IR);co[i]=r*cos(TAU*hz/IR);si[i]=r*sin(TAU*hz/IR);contraction=max(contraction,r)
            }
        }
        val h=DoubleArray(10) { if(it==0)1.0 else (.8+1.4*thread)/(it+1.0).pow(1.2-.9*thread) }
        val hn=sqrt(h.sumOf { it*it });for(i in h.indices)h[i]/=hn
        val noiseSeed=Dsp.seedFor("TREFOIL-HELD-1",p.seed,p.midi)
        val alpha=1-exp(-TAU*(900+6500*thread)/IR);val noiseState=DoubleArray(3)
        var noise=Dsp.Noise(noiseSeed)
        if(noiseEnabled)for(f in 0 until count){if(f and 8191==0)stop(cancelled);for(b in 0..2)noiseState[b]+=alpha*(noise.next()-noiseState[b])}
        noise=Dsp.Noise(noiseSeed)
        val cx=doubleArrayOf(1.0,cos(.61),cos(1.73));val cy=doubleArrayOf(0.0,sin(.61),sin(1.73))
        val oscCo=cos(TAU*sourceHz/IR);val oscSi=sin(TAU*sourceHz/IR);val desc=DoubleArray(9)
        for(f in 0 until count) {
            if(f and 8191==0)stop(cancelled)
            descriptor(if(weave==0f)0.0 else f.toDouble()/count,desc)
            if(f%32==0)for(k in 0..7) {
                val rate=18*min(1.0,root/261.625565)*exchange.pow(1.1)*weights[k]/IR
                val r=Trefoil.rotation(rate*desc[1]*(.85+.15*desc[4]),rate*desc[2]*(.85+.15*desc[5]),rate*desc[0]*(.85+.15*desc[3]))
                r.copyInto(rotations,(f/32*8+k)*9)
            }
            for(b in 0..2) {
                var hx=1.0;var hy=0.0;var carrier=0.0
                for(k in h.indices){val next=hx*cx[b]-hy*cy[b];hy=hx*cy[b]+hy*cx[b];hx=next;carrier+=h[k]*hy}
                if(noiseEnabled)noiseState[b]+=alpha*(noise.next()-noiseState[b])
                val left=if(b==0)0 else b;val right=if(b==0)2 else b-1
                val pressure=.10+1.8*(desc[left].pow(3)+desc[right].pow(3))
                val source=tanh((.6+3*p.velocity)*(.65*carrier+if(noiseEnabled)(.06+.18*thread)*noiseState[b] else 0.0))
                force[f*3+b]=.8*sqrt(max(1.0,130/root))*p.velocity*desc[6+b]*pressure*source/IR
                val old=cx[b];cx[b]=oscCo*old-oscSi*cy[b];cy[b]=oscSi*old+oscCo*cy[b]
                if(f and 4095==4095){val n=hypot(cx[b],cy[b]);cx[b]/=n;cy[b]/=n}
            }
        }
        val mt=DoubleArray(7) { (2+4.5*memory)/(1+.1*it) }
        val mc=DoubleArray(7) { exp(-ln(1000.0)/mt[it]/IR)*cos(TAU*root*ratios[it+1]/IR) }
        val ms=DoubleArray(7) { exp(-ln(1000.0)/mt[it]/IR)*sin(TAU*root*ratios[it+1]/IR) }
        return Plan(frames,sourceHz,root,rotations,force,excite,pickup,co,si,mc,ms,mt,120*sqrt(memory)/IR,.45*sqrt(memory),contraction)
    }

    internal data class State(val x: DoubleArray=DoubleArray(24),val v: DoubleArray=DoubleArray(24),
        val mx: DoubleArray=DoubleArray(7),val mv: DoubleArray=DoubleArray(7)) {
        fun owned()=State(x.copyOf(),v.copyOf(),mx.copyOf(),mv.copyOf())
        fun difference(other: State): Double {
            val arrays=listOf(x to other.x,v to other.v,mx to other.mx,mv to other.mv)
            val peak=arrays.maxOf { (a,_) -> a.maxOf(::abs) }.coerceAtLeast(1e-20)
            return arrays.maxOf { (a,b) -> a.indices.maxOf { abs(a[it]-b[it]) } }/peak
        }
    }
    internal data class Run(val raw: FloatArray,val state: State,val unforcedRise: Double)
    internal fun run(p: Plan, initial: State=State(), count: Int=p.count, off: Int=-1,
        onset: Int=0, withdrawal: Int=0, collect: Boolean=true, cancelled: () -> Boolean={false}): Run {
        require(count in 1..IR*60);val s=initial.owned();val raw=if(collect)FloatArray(count) else FloatArray(0)
        var rise=0.0;var previous=0.0
        for(frame in 0 until count) {
            if(frame and 8191==0)stop(cancelled)
            val f=frame%p.count;var gate=1.0
            if(onset>0 && frame<onset)gate=.5-.5*cos(PI*frame/onset)
            if(off>=0 && frame>=off)gate=if(withdrawal==0 || frame>=off+withdrawal)0.0 else .5+.5*cos(PI*(frame-off)/withdrawal)
            var mix=0.0;var energy=0.0
            for(k in 0..7) {
                val at=k*3;val r=(f/32*8+k)*9
                val ax=s.x[at];val bx=s.x[at+1];val cx=s.x[at+2]
                val av=s.v[at]+p.excite[at]*p.force[f*3]*gate
                val bv=s.v[at+1]+p.excite[at+1]*p.force[f*3+1]*gate
                val cv=s.v[at+2]+p.excite[at+2]*p.force[f*3+2]*gate
                var source=0.0
                for(b in 0..2) {
                    val j=r+b*3;val x=p.rotations[j]*ax+p.rotations[j+1]*bx+p.rotations[j+2]*cx
                    val v=p.rotations[j]*av+p.rotations[j+1]*bv+p.rotations[j+2]*cv;val i=at+b
                    s.x[i]=p.co[i]*x+p.si[i]*v;s.v[i]=p.co[i]*v-p.si[i]*x
                    source+=s.v[i]*p.pickup[i]
                    if(off>=0)energy+=.5*(s.x[i]*s.x[i]+s.v[i]*s.v[i])
                }
                mix+=source
                if(k>0) {
                    val j=k-1;val x=s.mx[j];val v=s.mv[j]+source*p.send
                    s.mx[j]=p.memoryCo[j]*x+p.memorySi[j]*v;s.mv[j]=p.memoryCo[j]*v-p.memorySi[j]*x
                    mix+=s.mv[j]*p.ret/sqrt(k.toDouble())
                }
            }
            if(collect)raw[frame]=mix.toFloat()
            if(off>=0 && frame>off+withdrawal)rise=max(rise,energy-previous)
            previous=energy
        }
        return Run(raw,s,rise)
    }

    private fun multiply(a: DoubleArray,b: DoubleArray,out: DoubleArray) {
        for(i in 0..5)for(j in 0..5){var x=0.0;for(k in 0..5)x+=a[i*6+k]*b[k*6+j];out[i*6+j]=x}
    }
    internal fun solve(p: Plan,cancelled: () -> Boolean={false}): State {
        val b=run(p,collect=false,cancelled=cancelled).state;val fixed=State()
        val rho=p.contraction.pow(p.count)
        if(rho<1e-3) {
            // The exact damped rotations give this global 2-norm bound. Long
            // periods need only a few core passes, avoiding a dense period
            // matrix. Short periods retain the affine solve below.
            var core=b;var bound=rho*(1+rho)/(1-rho)
            while(bound>1e-10){core=run(p,State(core.x,core.v),collect=false,cancelled=cancelled).state;bound*=rho}
            core.x.copyInto(fixed.x);core.v.copyInto(fixed.v)
        } else for(mode in 0..7) {
            var m=DoubleArray(36) { if(it/6==it%6)1.0 else 0.0 }
            var work=DoubleArray(36);var a=DoubleArray(36);var squared=DoubleArray(36)
            for(block in 0 until p.count/32) {
                if(block and 255==0)stop(cancelled)
                for(i in 0..2)for(j in 0..2) {
                    val r=p.rotations[(block*8+mode)*9+i*3+j];val co=p.co[mode*3+i];val si=p.si[mode*3+i]
                    a[i*6+j]=co*r;a[i*6+j+3]=si*r;a[(i+3)*6+j]=-si*r;a[(i+3)*6+j+3]=co*r
                }
                repeat(5){multiply(a,a,squared);val swap=a;a=squared;squared=swap}
                multiply(a,m,work);val swap=m;m=work;work=swap
            }
            val lhs=Array(6){i -> DoubleArray(6){j -> (if(i==j)1.0 else 0.0)-m[i*6+j]}}
            val rhs=DoubleArray(6){i -> if(i<3)b.x[mode*3+i] else b.v[mode*3+i-3]}
            val answer=LUDecomposition(Array2DRowRealMatrix(lhs,false),1e-14).solver.solve(ArrayRealVector(rhs,false)).toArray()
            for(i in 0..2){fixed.x[mode*3+i]=answer[i];fixed.v[mode*3+i]=answer[i+3]}
        }
        val memory=run(p,fixed,collect=false,cancelled=cancelled).state
        for(k in 0..6) {
            val r=exp(-ln(1000.0)*p.count/p.memoryT60[k]/IR);val angle=TAU*p.root*ratios[k+1]*p.count/IR
            val co=r*cos(angle);val si=r*sin(angle);val d=1-co;val det=d*d+si*si
            fixed.mx[k]=(d*memory.mx[k]+si*memory.mv[k])/det;fixed.mv[k]=(-si*memory.mx[k]+d*memory.mv[k])/det
        }
        return fixed
    }

    private fun condition(raw: FloatArray): FloatArray {
        Tide.bandLimit(raw,IR);val out=Dsp.decimate(raw,Dsp.RATE);val pole=Dsp.FixedOnePole(16f,Dsp.RATE)
        for(i in out.indices)out[i]-=pole.lp(out[i])
        return out
    }
    internal fun loop(patch: TrefoilPatch,noiseEnabled: Boolean=true,cancelled: () -> Boolean={false}): Loop {
        val start=System.nanoTime();val p=plan(patch,noiseEnabled,cancelled);val planned=System.nanoTime()
        val state=solve(p,cancelled);val solved=System.nanoTime()
        val warm=ceil(.5/(p.frames.toDouble()/Dsp.RATE)).toInt().coerceAtLeast(1)
        val evolved=run(p,state,(warm+2)*p.count+2048,cancelled=cancelled)
        // End state includes a 2048-frame guard, so compare a full independently advanced period.
        val residual=run(p,state,collect=false,cancelled=cancelled).state.difference(state)
        check(residual<1e-8) { "TREFOIL held recurrence failed: $residual" }
        val conditioned=condition(evolved.raw);val from=warm*p.frames
        val body=conditioned.copyOfRange(from,from+p.frames);val nativePeak=Dsp.finitePeak(body).toDouble()
        val leveled=body.copyOf();Dsp.levelTo(leveled,Dsp.RATE,Dsp.MELODIC_LOUDNESS_TARGET,.90f)
        val gain=if(nativePeak>1e-12)Dsp.finitePeak(leveled)/nativePeak else 1.0
        val context=FloatArray(3*p.frames){(conditioned[from-p.frames+it]*gain).toFloat()}
        val kept=context.copyOfRange(p.frames,2*p.frames)
        val preceding=if(nativePeak==0.0)0.0 else Keys.seamError(context.copyOfRange(0,2*p.frames),p.frames)
        val following=if(nativePeak==0.0)0.0 else Keys.seamError(context.copyOfRange(p.frames,3*p.frames),p.frames)
        check(preceding<Keys.MAX_SEAM_ERROR && following<Keys.MAX_SEAM_ERROR) { "TREFOIL held output does not close: $preceding / $following" }
        check(kept.all { it.isFinite() })
        val now=System.nanoTime()
        return Loop(kept,context,Diagnostics(p.frames,p.sourceHz,p.root,residual,preceding,following,nativePeak,gain,
            p.contraction,(planned-start)/1e6,(solved-planned)/1e6,(now-start)/1e6))
    }

    private data class CacheKey(val voice: TrefoilVoice,val macros: List<Int>,val midi: Int,val velocity: Int,val seed: Long,val version: Int)
    private val cache=LinkedHashMap<CacheKey,Held>(12,.75f,true)
    private var cacheBytes=0;private var generation=0L
    fun clearCache()=synchronized(cache){cache.clear();cacheBytes=0;generation++}
    internal fun cacheBytes()=synchronized(cache){cacheBytes}
    fun render(patch: TrefoilPatch,cancelled: () -> Boolean={false}): Held {
        require(supports(patch));stop(cancelled)
        val m=defaults(patch.voice)+patch.macros
        val key=CacheKey(patch.voice,listOf("WEAVE","EXCHANGE","THREAD","MEMORY").map { m.getValue(it).toBits() },patch.midi,patch.velocity.toBits(),patch.seed,patch.heldVersion)
        val (version,hit)=synchronized(cache){generation to cache[key]}
        if(hit!=null){stop(cancelled);val owned=hit.copy(snip=hit.snip.copy(samples=hit.snip.samples.copyOf()));stop(cancelled);return owned}
        val loop=loop(patch,cancelled=cancelled)
        // Even a fast motion cycle remains a loop-class sample. More whole
        // sustain periods extend delivery, without changing its measured cycle.
        val periods=ceil(1.6*Dsp.RATE/loop.samples.size).toInt().coerceAtLeast(2)
        val samples=FloatArray(periods*loop.samples.size){loop.samples[it%loop.samples.size]}
        val ramp=(.020*Dsp.RATE).toInt()
        for(i in 0 until min(ramp,samples.size)){val t=i.toDouble()/(ramp-1);samples[i]=(samples[i]*t*t*(3-2*t)).toFloat()}
        val capture=Held(Snip(samples,1,Dsp.RATE),loop.samples.size,samples.size,loop.diagnostics)
        stop(cancelled)
        synchronized(cache){stop(cancelled);if(version==generation){cache.put(key,capture)?.let { cacheBytes-=it.snip.samples.size*4 };cacheBytes+=samples.size*4
            while(cache.size>12 || cacheBytes>CACHE_MAX_BYTES){val old=cache.entries.iterator();cacheBytes-=old.next().value.snip.samples.size*4;old.remove()}}}
        try{stop(cancelled)}catch(e: CancellationException){synchronized(cache){if(cache[key]===capture){cache.remove(key);cacheBytes-=samples.size*4}};throw e}
        return capture.copy(snip=capture.snip.copy(samples=samples.copyOf()))
    }
    /** Causal contact withdrawal, separately from the sampler's release envelope. */
    fun renderNote(patch: TrefoilPatch,noteOffSeconds: Double,tailSeconds: Double=8.0,cancelled: () -> Boolean={false}): Note {
        require(noteOffSeconds.isFinite() && noteOffSeconds in .05..20.0)
        require(tailSeconds.isFinite() && tailSeconds in .1..12.0)
        val gain=render(patch,cancelled).diagnostics.gain;val p=plan(patch,cancelled=cancelled)
        val off=(noteOffSeconds*IR).roundToInt();val withdrawal=(.025*IR).roundToInt()
        val run=run(p,count=off+(tailSeconds*IR).roundToInt(),off=off,onset=(.035*IR).roundToInt(),withdrawal=withdrawal,cancelled=cancelled)
        val internal=run.raw.copyOf();val out=condition(run.raw)
        for(i in out.indices)out[i]=(out[i]*gain).toFloat()
        check(out.all { it.isFinite() } && Dsp.finitePeak(out)<.99f) { "TREFOIL held note exceeded output headroom" }
        return Note(Snip(out,1,Dsp.RATE),internal,(noteOffSeconds*Dsp.RATE).roundToInt(),((off+withdrawal).toDouble()/Dsp.OVERSAMPLE).roundToInt(),run.unforcedRise)
    }
}
