package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.sin

enum class BackwashTreatment { REFERENCE, SEASICK, CLAMPED, OVERLOADED, COMBINED }

/** Audition-only ranges beyond model 3, not a change to saved factory sounds. */
internal object BackwashProcessingStudy {
    /** Convex interpolation/filtering and feedback .74 bound the loop without clipping. */
    fun chorus(input: Snip, voice: BackwashVoice): Snip {
        val rate=input.sampleRate
        val out=FloatArray(input.samples.size+(rate*.9f).toInt())
        val delay=FloatArray((rate*.08f).toInt())
        val filter=Dsp.FixedOnePole(5500f,rate)
        val phase=Dsp.seedFor("BACKWASH",1,voice.name).toUInt().toDouble()/UInt.MAX_VALUE.toDouble()*2*PI
        var write=0
        for(i in out.indices){
            val seconds=i.toDouble()/rate
            val time=(.028+.014*sin(2*PI*1.15*seconds+phase))*rate
            val returning=Dsp.tap(delay,write,time.toFloat())
            val x=input.samples.getOrElse(i){0f}
            delay[write]=x+.74f*filter.lp(returning)
            out[i]=x+.95f*returning
            write=(write+1)%delay.size
        }
        return Snip(out,1,rate)
    }
    fun render(voice: BackwashVoice, macros: Map<String,Float>, velocity: Float, treatment: BackwashTreatment): Snip {
        if(treatment==BackwashTreatment.REFERENCE)return Backwash.render(voice,macros,velocity)
        val m=Backwash.settled(voice,macros)
        val dry=Backwash.source(voice,m,velocity)
        val moving=if(treatment in listOf(BackwashTreatment.SEASICK,BackwashTreatment.COMBINED)) chorus(dry,voice)
            else Backwash.chorus(dry,m.getValue("SWAY"),m.getValue("TRAIL"),voice)
        val pressed=if(treatment in listOf(BackwashTreatment.CLAMPED,BackwashTreatment.COMBINED)){
            val wet=Compressor.process(moving,mapOf("AMOUNT" to .88f,"ATTACK" to .58f,"RECOVER" to .84f))
            Snip(FloatArray(moving.samples.size){.1f*moving.samples[it]+.9f*wet.samples[it]},1,Dsp.RATE)
        }else Backwash.press(moving,m.getValue("PRESS"))
        val hot=treatment in listOf(BackwashTreatment.OVERLOADED,BackwashTreatment.COMBINED)
        val amp=Valve.process(pressed,mapOf("DRIVE" to if(hot).84f else Dsp.lin(m.getValue("DRIVE"),.32f,.65f),
            "SAG" to if(hot).35f else .2f,"TONE" to .48f,"CAB" to .55f))
        val out=amp.samples.copyOf()
        if(hot){
            // A quiet pre-amp low band supports kick/tom weight beneath the saturated output.
            val low=Dsp.FixedOnePole(160f,Dsp.RATE)
            for(i in out.indices)out[i]=.8f*out[i]+.2f*low.lp(pressed.samples.getOrElse(i){0f})
        }
        val mean=out.average().toFloat();val dc=Dsp.FixedOnePole(20f,Dsp.RATE)
        for(i in out.indices){val x=out[i]-mean;out[i]=x-dc.lp(x)}
        Dsp.levelTo(out,Dsp.RATE,Dsp.MELODIC_LOUDNESS_TARGET);Dsp.fadeTail(out)
        return Snip(out,1,Dsp.RATE)
    }
}
