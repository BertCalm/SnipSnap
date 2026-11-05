package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class ChitinVoice { SHELL, WING }

internal data class ChitinDiagnostics(val rawPeak: Double, val rawDc: Double, val filterPeak: Double,
    val energyExcess: Double, val finalStorage: Double, val renderMillis: Double)
internal data class ChitinRender(val snip: Snip, val raw: FloatArray, val beforePhase: FloatArray,
    val diagnostics: ChitinDiagnostics)

/** One shared excursion makes the reset harmonics and phase notches travel together.
 * Shape constants follow the 2026-10-31 handoff. HOLD is a finite gate; its top
 * step is reserved. No random modulation, samples or extra rack processing. */
object Chitin {
    const val MODEL_VERSION=1
    // A pitched attack precedes the shared sweep; a fast sync chirp at note-on
    // otherwise becomes broadband percussion in the classifier head window.
    internal const val MORPH_START_SECONDS=.08
    private val names=listOf("TUNE","BITE","COLOR","PHASE","MORPH","PACE","HOLD")
    fun macrosFor(voice: ChitinVoice): List<MacroSpec> {
        val values=if(voice==ChitinVoice.SHELL) listOf(.5f,.35f,.45f,.55f,.6f,.4f,.35f)
                   else listOf(.5f,.6f,.55f,.65f,.75f,.6f,.25f)
        return names.zip(values).map { MacroSpec(it.first,it.second) }
    }
    fun defaults(voice: ChitinVoice)=macrosFor(voice).associate { it.name to it.default }
    internal fun settled(voice: ChitinVoice, macros: Map<String,Float>)=defaults(voice).mapValues { (key,default)->
        val v=macros[key]; if(v==null || !v.isFinite()) default else if(v==0f) 0f else v.coerceIn(0f,1f)
    }
    fun midiFor(voice: ChitinVoice,tune: Float): Int = (if(voice==ChitinVoice.SHELL) 24 else 48)+floor(24*tune.coerceIn(0f,1f)+.5).toInt()
    fun rootMidi(voice: ChitinVoice)=midiFor(voice,.5f)
    fun frequencyFor(voice: ChitinVoice,tune: Float)=440.0*2.0.pow((midiFor(voice,tune)-69)/12.0)
    @Suppress("UNUSED_PARAMETER") fun isLoop(hold: Float)=false
    fun scramble(voice: ChitinVoice,random: Random,temperature: Float=.35f,near: Patch?=null): Map<String,Float> =
        Dsp.scrambleNear(if(near is ChitinPatch && near.voice==voice) settled(voice,near.macros) else defaults(voice),temperature,random)

    internal data class Plan(val voice: ChitinVoice,val m: Map<String,Float>,val velocity: Double) {
        val h=min(m.getValue("HOLD").toDouble(),.98)/.98
        val gate=round(.65*(2.5/.65).pow(h)*Dsp.RATE).toInt()
        val release=round(((if(voice==ChitinVoice.SHELL) .18 else .28)+.08*h)*Dsp.RATE).toInt()
        val frames=gate+release+3528
        val root=frequencyFor(voice,m.getValue("TUNE"))
        val rise=1.8*(.12/1.8).pow(m.getValue("PACE").toDouble())
        fun morph(t: Double): Double {
            val amount=m.getValue("MORPH")*(.7+.3*velocity)
            val gateTime=gate.toDouble()/Dsp.RATE
            return if(t<gateTime) amount*smooth((t-MORPH_START_SECONDS)/rise)
            else amount*smooth((gateTime-MORPH_START_SECONDS)/rise)*(1-smooth((t-gateTime)/(release.toDouble()/Dsp.RATE)))
        }
        fun ratio(t: Double): Double {
            val wing=voice==ChitinVoice.WING
            val ra=if(wing) 2.0 else 1.5; val rb=if(wing) 6.0 else 4.5
            return 1+m.getValue("BITE")*(ra-1)+m.getValue("BITE")*morph(t)*(rb-ra)
        }
    }
    internal fun plan(voice: ChitinVoice,macros: Map<String,Float>,velocity: Float=1f)=
        Plan(voice,settled(voice,macros),if(velocity.isFinite()) velocity.coerceIn(0f,1f).toDouble() else 1.0)
    private data class Key(val voice: ChitinVoice,val macros: Map<String,Float>,val velocity: Float)
    private data class Cached(val snip: Snip,val drumClass: DrumClass)
    private val cache=object: LinkedHashMap<Key,Cached>(4,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key,Cached>?)=size>4
    }
    private fun result(voice: ChitinVoice,macros: Map<String,Float>,velocity: Float): Cached {
        val p=plan(voice,macros,velocity)
        val canonical=p.m+("HOLD" to min(p.m.getValue("HOLD"),.98f))
        val key=Key(voice,canonical,p.velocity.toFloat())
        synchronized(cache) { cache[key]?.let { return it } }
        val snip=inspect(voice,canonical,p.velocity.toFloat()).snip
        val result=Cached(snip,Classifier.classify(snip).drumClass)
        synchronized(cache) { cache[key]=result }
        return result
    }
    fun render(voice: ChitinVoice,macros: Map<String,Float> = emptyMap(),velocity: Float=1f): Snip =
        result(voice,macros,velocity).snip.let { Snip(it.samples.copyOf(),1,Dsp.RATE) }
    fun drumClassFor(voice: ChitinVoice,macros: Map<String,Float> = emptyMap()): DrumClass {
        if(plan(voice,macros).frames.toFloat()/Dsp.RATE>1.5f) return DrumClass.LOOP
        return result(voice,macros,1f).drumClass
    }
    internal fun inspect(voice: ChitinVoice,macros: Map<String,Float> = emptyMap(),velocity: Float=1f,
                         protected: Boolean=true, sourceMorph: Boolean=true, phaseMorph: Boolean=true): ChitinRender {
        val started=System.nanoTime(); val p=plan(voice,macros,velocity)
        val rate=Dsp.RATE*Dsp.OVERSAMPLE
        val wing=voice==ChitinVoice.WING
        val source=ChitinSource.render(p.frames*4,rate,p.root,!wing, ratio = { t -> p.ratio(if(sourceMorph) t else 0.0) })
        val filter=Dsp.TptSvf(rate)
        val phaser=ChitinPhaser(rate,p.root,p.m.getValue("PHASE").toDouble(),wing,protected)
        val raw=FloatArray(source.size); val before=FloatArray(source.size)
        var filterPeak=0.0
        for(i in raw.indices) {
            if(i%2048==0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            val t=i.toDouble()/rate; val morph=p.morph(t)
            val cutoff=(p.root*(2+18*p.m.getValue("COLOR"))*2.0.pow(1.4*morph+.6*exp(-t/.045)*(.25+.75*p.velocity))).coerceIn(60.0,14000.0)
            filter.process(source[i],cutoff.toFloat(),if(wing) 1.05f else 1.35f)
            filterPeak=max(filterPeak,abs(filter.low.toDouble()))
            val gate=p.gate.toDouble()/Dsp.RATE
            val amp=if(t<gate) smooth(t/(.012-.007*p.velocity)) else 1-smooth((t-gate)/(p.release.toDouble()/Dsp.RATE))
            val z=.65*filter.low*amp
            val drive=1+2.5*p.m.getValue("BITE")+1.5*morph
            // A brief post-preamp crest gives the shared sweep an audible downbeat.
            // Putting this before saturation would flatten the crest at high BITE.
            val onset=1+.75*exp(-t/.08)
            val x=if(p.velocity==0.0) 0.0 else onset*tanh(drive*z)/tanh(drive)
            before[i]=x.toFloat()
            val nextTime=(i/64*64+64).toDouble()/rate
            raw[i]=phaser.step(x,if(phaseMorph) morph else 0.0,if(phaseMorph) p.morph(nextTime) else 0.0).toFloat()
            check(raw[i].isFinite()) { "CHITIN nonfinite output at $i" }
        }
        val rawPeak=raw.maxOf { abs(it.toDouble()) }; val rawDc=raw.sumOf { it.toDouble() }/raw.size
        val conditioned=raw.copyOf()
        Tide.bandLimit(conditioned,rate)
        val out=Dsp.decimate(conditioned,Dsp.RATE)
        val mean=out.sumOf { it.toDouble() }/out.size
        val hp=Dsp.OnePole(Dsp.RATE)
        for(i in out.indices) { val x=(out[i]-mean).toFloat(); out[i]=x-hp.lp(x,20f) }
        Dsp.levelTo(out,Dsp.RATE,Dsp.MELODIC_LOUDNESS_TARGET); Dsp.fadeTail(out)
        return ChitinRender(Snip(out,1,Dsp.RATE),raw,before,ChitinDiagnostics(rawPeak,rawDc,filterPeak,
            phaser.energyExcess,phaser.storage,(System.nanoTime()-started)/1e6))
    }
    internal fun smooth(u: Double): Double { val x=u.coerceIn(0.0,1.0); return x*x*x*(10+x*(-15+6*x)) }
}
