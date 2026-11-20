package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.*
import kotlin.math.*

/** Native listening candidate based on parade study revision 8. Native RNG/output differ from Python.
 * One bass drummer, one snare drummer and cymbal. Versioned patches preserve the selected native renderer.
 */
object ParadeSecondLine {
    const val MODEL = 4
    private const val R = Dsp.RATE * Dsp.OVERSAMPLE
    private const val CRASH_SECONDS = 4.5
    enum class Voice { BASS_OPEN, BASS_MUTED, SNARE_CENTER, SNARE_GHOST, RIMSHOT, CROSS_STICK,
        SNARE_DRAG, SNARE_FLAM, PRESS_BUZZ, SNARE_ROLL, CYMBAL_CRASH, CYMBAL_CHOKE, CYMBAL_TOUCH, BELL, STICK_CLICK, TURNAROUND, TRIANGLE, TRIANGLE_MUTED }
    fun defaults() = mapOf("TUNE" to .5f,"STRIKE" to .5f,"DAMP" to .5f)
    fun settled(m: Map<String,Float>) = defaults().mapValues { (k,v)->m[k]?.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: v }
    data class Recipe(val voice: Voice,val macros: Map<String,Float> = defaults(),val velocity: Float = 1f,val take: Int = 0,val model: Int = MODEL) {
        init { require(model in 1..MODEL && take>=0 && velocity.isFinite() && velocity in .05f..1f) }
        fun render() = ParadeSecondLine.render(voice,macros,velocity,take,model)
        fun json() = JsonValue.Obj(mapOf("voice" to JsonValue.Str(voice.name),"model" to JsonValue.Num(model.toDouble()),
            "velocity" to JsonValue.Num(velocity.toDouble()),"take" to JsonValue.Num(take.toDouble()),
            "macros" to JsonValue.Obj(settled(macros).mapValues { JsonValue.Num(it.value.toDouble()) })))
        companion object {
            fun fromJson(v: JsonValue): Recipe { val d=v.obj();return Recipe(Voice.valueOf(d.getValue("voice").str()),
                d.getValue("macros").obj().mapValues { it.value.num().toFloat() },d.getValue("velocity").num().toFloat(),d.getValue("take").int(),d.getValue("model").int()) }
        }
    }
    private fun table(name: String) = Json.parse(checkNotNull(javaClass.getResourceAsStream("/parade/$name")).bufferedReader().use { it.readText() }).obj()
    private fun doubles(v: JsonValue)=v.arr().map { it.num() }.toDoubleArray()
    private fun contactBank(file: String): Map<String,CalabashFits.HandData> {
        val root=table(file);val bands=doubles(root.getValue("bands"));val times=doubles(root.getValue("times"))
        return root.getValue("voices").obj().mapValues { (_,value)->
            val d=value.obj();val modes=d.getValue("modes").arr().map { doubles(it) };val glide=doubles(d.getValue("glide"))
            fun col(k: Int)=DoubleArray(modes.size) { modes[it][k] }
            CalabashFits.HandData(CalabashFits.Hand(col(0),col(1),col(2),col(3),col(4),glide[0],glide[1],
                d.getValue("residual").arr().map { doubles(it) }.toTypedArray(),d.getValue("residualSoft").arr().map { doubles(it) }.toTypedArray()),bands,times,root.getValue("early").num())
        }
    }
    private val contacts by lazy { contactBank("measured-contacts.json") }
    private val triangle by lazy { contactBank("triangle-fit.json").getValue("triangle") }
    private val settledTriangle by lazy {
        val d=triangle.hand
        // The weak fitted neighbours otherwise outlive the principal measured mode.
        val late=DoubleArray(d.late.size) { if(d.amp[it]<.15)min(d.late[it],-12.0) else d.late[it] }
        CalabashFits.HandData(CalabashFits.Hand(d.hz,d.amp,d.early,late,d.tilt,d.glide,d.glideTau,d.residual,d.residualSoft),triangle.bands,triangle.times,triangle.early)
    }
    private val wash by lazy { table("crash-wash.json") }
    /** Immutable interpolation addresses shared by all measured crash bands. Same arithmetic as interp(). */
    private class CurveGrid(times: DoubleArray,n: Int) {
        private val index=IntArray(n)
        private val weight=DoubleArray(n)
        init {
            for(i in 0 until n) {
                val t=i.toDouble()/Dsp.RATE
                if(t<=times[0])index[i]=-1
                else if(t>=times.last())index[i]=times.lastIndex
                else {
                    var lo=0;var hi=times.lastIndex
                    while(hi-lo>1) { val mid=(lo+hi) ushr 1;if(times[mid]<=t)lo=mid else hi=mid }
                    index[i]=lo;weight[i]=(t-times[lo])/(times[hi]-times[lo])
                }
            }
        }
        fun value(points: DoubleArray,i: Int): Double {
            val j=index[i]
            return when { j<0->points[0];j>=points.lastIndex->points.last();else->points[j]+weight[i]*(points[j+1]-points[j]) }
        }
    }
    private val crashCurves by lazy {
        val h=doubles(wash.getValue("hard").obj().getValue("times"))
        val s=doubles(wash.getValue("soft").obj().getValue("times"))
        val hard=CurveGrid(h,(CRASH_SECONDS*Dsp.RATE).roundToInt())
        hard to if(h.contentEquals(s))hard else CurveGrid(s,(CRASH_SECONDS*Dsp.RATE).roundToInt())
    }
    private fun loss(t: Double,start: Double,width: Double): Double {
        val z=(t-start).coerceIn(0.0,width)
        return z.pow(3)/width.pow(2)-.5*z.pow(4)/width.pow(3)+max(t-start-width,0.0)
    }
    private fun filter(x: DoubleArray,lo: Double,hi: Double,rate: Int = R,order: Int = 2) =
        CalabashDsp.butter(order,CalabashDsp.Kind.BAND,lo,hi,fs=rate).run(x)
    /** scipy uniform_filter1d's half-sample symmetric reflect boundary, even-size centered window. */
    private fun rms(x: DoubleArray): DoubleArray {
        val width=(.012*Dsp.RATE).roundToInt();val left=width/2
        fun reflected(i: Int): Int = when { i<0 -> -i-1;i>=x.size -> 2*x.size-i-1;else->i }
        val sums=DoubleArray(x.size+width)
        for(i in 0 until sums.lastIndex) { val v=x[reflected(i-left)];sums[i+1]=sums[i]+v*v }
        return DoubleArray(x.size) { sqrt(max((sums[it+width]-sums[it])/width,1e-20)) }
    }
    private data class CrashKey(val velocity: Double,val take: Int)
    private val crashCache=object: LinkedHashMap<CrashKey,DoubleArray>(3,.75f,true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<CrashKey,DoubleArray>?)=size>2
    }
    @Synchronized internal fun clearCrashCache() { crashCache.clear() }
    @Synchronized internal fun crashCacheEntries() = crashCache.size
    /** The core source is independent of controls/model; callers receive isolated working buffers. */
    @Synchronized internal fun crashSource(v: Double,take: Int): DoubleArray =
        crashCache.getOrPut(CrashKey(v,take)) { freshCrash(v,take) }.copyOf()
    internal fun freshCrash(v: Double,take: Int): DoubleArray {
        val rng=CalabashDsp.Rng(Dsp.seedFor("PARADE_CRASH_WASH",take))
        val modes=CalabashFits.fittedModes("breaker_crash",.5,.3,v,rng,CRASH_SECONDS);val n=modes.size
        val hard=wash.getValue("hard").obj();val soft=wash.getValue("soft").obj();val bands=doubles(wash.getValue("bands"))
        val w=((1-v)/.65).coerceIn(0.0,1.0)
        val out=DoubleArray(n)
        val (hardCurve,softCurve)=crashCurves
        for((k,fc) in bands.withIndex()) {
            val he=doubles(hard.getValue("envelope").arr()[k]);val se=doubles(soft.getValue("envelope").arr()[k])
            val hf=doubles(hard.getValue("diffuse_fraction").arr()[k]);val sf=doubles(soft.getValue("diffuse_fraction").arr()[k])
            val lo=fc/2.0.pow(1.0/6);val hi=min(fc*2.0.pow(1.0/6),21000.0)
            val tonal=filter(modes,lo,hi,Dsp.RATE,3);val normal=rms(tonal)
            val noise=filter(rng.normals(n+4096),lo,hi,Dsp.RATE,3).copyOfRange(4096,n+4096)
            val norm=max(CalabashDsp.rms(noise),1e-12)
            for(i in 0 until n) {
                val env=(1-w)*hardCurve.value(he,i)+w*softCurve.value(se,i)
                val fraction=((1-w)*hardCurve.value(hf,i)+w*softCurve.value(sf,i)).coerceIn(0.0,1.0)
                out[i]+=sqrt(1-fraction)*tonal[i]*min(env/max(normal[i],1e-8),100.0)+sqrt(fraction)*noise[i]/norm*env
            }
        }
        for(i in out.indices)out[i]*=min(i.toDouble()/Dsp.RATE/.0004,1.0)*v
        return CalabashDsp.upsample4(out,(CRASH_SECONDS*R).roundToInt())
    }
    fun render(voice: Voice,macros: Map<String,Float> = defaults(),velocity: Float = 1f,take: Int = 0,model: Int = MODEL): Snip {
        val x=renderRaw(voice,macros,velocity,take,model)
        Tide.bandLimit(x,R);val out=Dsp.decimate(x,Dsp.RATE);val mean=out.average().toFloat();val hp=Dsp.FixedOnePole(20f,Dsp.RATE)
        for(i in out.indices) { val a=out[i]-mean;out[i]=a-hp.lp(a) }
        Dsp.levelTo(out,Dsp.RATE,Dsp.MELODIC_LOUDNESS_TARGET);Dsp.fadeTail(out)
        return Snip(out,1,Dsp.RATE)
    }
    internal fun renderRaw(voice: Voice,macros: Map<String,Float> = defaults(),velocity: Float = 1f,take: Int = 0,model: Int = MODEL): FloatArray {
        require(take>=0 && model in 1..MODEL);val m=settled(macros);val v=velocity.takeIf { it.isFinite() }?.coerceIn(.05f,1f)?.toDouble() ?: 1.0
        val raw=raw(voice,m,v,take,model);return FloatArray(raw.size) { raw[it].toFloat() }
    }
    private fun raw(voice: Voice,m: Map<String,Float>,v: Double,take: Int,model: Int): DoubleArray {
        val times=when(voice) {
            Voice.SNARE_DRAG->listOf(0.0,.024,.065);Voice.SNARE_FLAM->listOf(0.0,.025)
            Voice.PRESS_BUZZ->buildList { var t=0.0;add(t);for(i in 0..14) { t+=.012+.0015*i;add(t) } }
            Voice.SNARE_ROLL->List(16) { it*.065 };Voice.TURNAROUND->listOf(0.0,.17,.25,.34,.5,.67,.75);else->null
        }
        if(times!=null) {
            val out=DoubleArray(((times.last()+1.2)*R).roundToInt())
            for((j,at) in times.withIndex()) {
                val strength=if(voice==Voice.SNARE_DRAG || voice==Voice.SNARE_FLAM) { if(j<times.lastIndex).35 else 1.0 } else if(j%4==0).95 else .55
                // Retain each figure's life, contact and deterministic body identity from the study.
                val life=when(voice) { Voice.SNARE_DRAG->.23;Voice.PRESS_BUZZ->.24;else->.25 }
                val hit=skin(voice.name.lowercase(),false,255.0,life,.5,m,v*strength,take*32+j)
                val i=(at*R).roundToInt();for(k in hit.indices)out[i+k]+=hit[k]
            };return out
        }
        val base=when(voice) {
            Voice.CYMBAL_CRASH,Voice.CYMBAL_CHOKE,Voice.CYMBAL_TOUCH -> {
                var y=crashSource(if(voice==Voice.CYMBAL_TOUCH)v*.5 else v,take)
                if(voice!=Voice.CYMBAL_CRASH) {
                    y=y.copyOf(((if(voice==Voice.CYMBAL_TOUCH).8 else 1.4)*R).roundToInt())
                    val lo=CalabashDsp.butter(2,CalabashDsp.Kind.LOW,1800.0,fs=R).run(y)
                    val start=if(voice==Voice.CYMBAL_TOUCH).045 else .18;val width=if(voice==Voice.CYMBAL_TOUCH).06 else .14
                    for(i in y.indices) { val t=i.toDouble()/R;if(t>=start) { val e=loss(t,start,width);y[i]=lo[i]*exp(-20*e)+(y[i]-lo[i])*exp(-55*e) } }
                };y
            }
            Voice.TRIANGLE,Voice.TRIANGLE_MUTED -> {
                val rng=CalabashDsp.Rng(Dsp.seedFor("PARADE_TRIANGLE",take))
                val refined=voice==Voice.TRIANGLE && model>=2
                val (a,b)=if(refined) CalabashFits.strike("triangle",v,rng,5.0,contactMs=.32,fit=settledTriangle,
                    detuneSd=0.0,modeGain={ hz -> 1/sqrt(1+(hz/6500).pow(4)) },residualDb=-8.0,attackDb=-3.0,residUntil=.08)
                    else CalabashFits.strike("triangle",v,rng,5.0,contactMs=.18,fit=triangle)
                var y=CalabashDsp.upsample4(DoubleArray(a.size) { (a[it]+b[it])*v },5*R)
                if(voice==Voice.TRIANGLE_MUTED) {
                    y=y.copyOf((1.2*R).roundToInt())
                    val lo=CalabashDsp.butter(2,CalabashDsp.Kind.LOW,1800.0,fs=R).run(y)
                    for(i in y.indices) {
                        val e=loss(i.toDouble()/R,.035,.055)
                        y[i]=lo[i]*exp(-28*e)+(y[i]-lo[i])*exp(-65*e)
                    }
                };y
            }
            Voice.RIMSHOT,Voice.CROSS_STICK,Voice.STICK_CLICK -> {
                val name=voice.name.lowercase();val rng=CalabashDsp.Rng(Dsp.seedFor("PARADE_MEASURED_CONTACT",name,0,take))
                val (a,b)=CalabashFits.strike(name,v,rng,1.2,contactMs=.25,fit=contacts.getValue(name))
                val hit=DoubleArray(a.size) { (a[it]+b[it])*v }
                // A gentler wooden contact has less high-frequency energy. The only
                // source capture is hard, so this soft-contact law is modeled.
                val shaped=if(voice==Voice.STICK_CLICK && model>=3 && v<1.0)
                    CalabashDsp.butter(2,CalabashDsp.Kind.LOW,2500+9500*v,fs=Dsp.RATE).run(hit) else hit
                CalabashDsp.upsample4(shaped,(1.2*R).roundToInt())
            }
            Voice.BASS_OPEN->skin("bass_open",true,58.0,1.35,3.2,m,v,take)
            Voice.BASS_MUTED->skin("bass_muted",true,58.0,.32,3.2,m,v,take)
            Voice.SNARE_CENTER->skin("snare_center",false,255.0,.26,.5,m,v,take)
            Voice.SNARE_GHOST->skin("snare_ghost",false,255.0,.16,.9,m,v,take)
            Voice.BELL->skin("bell",false,780.0,.35,.4,m,v,take)
            else->error("Unscored figure")
        }
        // Contact controls are applied within skin(), and to fitted sources here.
        return if(voice in listOf(Voice.BASS_OPEN,Voice.BASS_MUTED,Voice.SNARE_CENTER,Voice.SNARE_GHOST,Voice.BELL))base else controlled(base,m,model)
    }
    private fun controlled(x: DoubleArray,m: Map<String,Float>,model: Int): DoubleArray {
        val tune=2.0.pow((m.getValue("TUNE")-.5));val damp=2.0.pow((m.getValue("DAMP")-.5)*2)
        val bright=2.0.pow((m.getValue("STRIKE")-.5)*2)
        val lo=CalabashDsp.butter(2,CalabashDsp.Kind.LOW,1800.0,fs=R).run(x)
        if(model>=4 && tune!=1.0) {
            val shifted=ParadeRetune.sample(x,tune);val low=ParadeRetune.sample(lo,tune)
            return DoubleArray(x.size) { i -> (low[i]+(shifted[i]-low[i])*bright)*exp(-(damp-1)*i/R*2) }
        }
        return DoubleArray(x.size) { i->
            val at=i*tune;val a=at.toInt();val f=at-a
            if(a>=x.lastIndex)0.0 else {
                val y=(x[a]*(1-f)+x[a+1]*f);val low=lo[a]*(1-f)+lo[a+1]*f
                (low+(y-low)*bright)*exp(-(damp-1)*i/R*2)
            }
        }
    }
    private fun skin(name: String,bass: Boolean,root: Double,life: Double,width: Double,m: Map<String,Float>,v: Double,take: Int): DoubleArray {
        val id=CalabashDsp.Rng(Dsp.seedFor("PARADE_ID",name,0));val rng=CalabashDsp.Rng(Dsp.seedFor("PARADE_HIT",name,0,take))
        val n=(1.2*R).roundToInt();val f=root*2.0.pow(id.uniform(-7.0,7.0)/1200+(m.getValue("TUNE")-.5))
        val touch=v*rng.uniform(.95,1.03);val brightness=touch*2.0.pow((m.getValue("STRIKE")-.5)*1.2)
        val damp=2.0.pow((m.getValue("DAMP")-.5)*2);var y=DoubleArray(n)
        val bell=name=="bell";val ratios=if(bell)doubleArrayOf(1.0,1.47,2.03,2.71,3.9,5.2,7.3) else doubleArrayOf(1.0,1.594,2.136,2.296,2.653,2.918,3.156,3.50)
        for((k,a) in ratios.withIndex()) {
            val hz=f*a*id.uniform(.995,1.005);if(hz>=18000)continue
            var g=exp(-a*(.12+.8*(1-brightness)))/a.pow(.65)*rng.uniform(.93,1.07)
            if((bass || bell) && take>0)g*=exp((if(take%2==1).18 else -.18)*(a-1))
            if(!bell && k==0)g*=1.5
            if(bass)g*=if(k<2)2.0 else .65
            val r=exp(-6.9078*damp/(R*(life/sqrt(a))));var env=g
            for(i in y.indices) { y[i]+=env*sin(2*PI*hz*i/R);env*=r }
        }
        val size=max(4,(width*.001*(1.5-.65*touch)*R).roundToInt());val force=DoubleArray(size) { .5-.5*cos(2*PI*it/(size-1)) };val total=force.sum()
        for(i in force.indices)force[i]/=total;y=CalabashDsp.conv(y,force,n)
        val noise=rng.uniform(-1.0,1.0,n)
        if(!bass && !bell) {
            val cord=filter(noise,1700.0,9500.0);val pop=filter(noise,700.0,4200.0)
            for(i in y.indices) { val t=i.toDouble()/R
                y[i]+=1.8*touch.pow(.7)*cord[i]*min(t/.0002,1.0)*exp(-6.9078*damp*t/life)
                y[i]+=5.5*touch.pow(1.2)*pop[i]*(1-exp(-t/.0003))*exp(-damp*t/.006)
                y[i]+=.85*touch*sin(2*PI*f*2.136*t)*exp(-damp*t/.012)
            }
        }
        val gain=touch*id.uniform(.95,1.05)
        for(i in y.indices)y[i]*=min(i.toDouble()/R/.00015,1.0)*gain
        return y
    }
}
