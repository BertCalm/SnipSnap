package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.synth.FiredBronzeDryKit.Pad as P

/** Stronger, independently controlled processor and amp recipes. Color v1 stays replayable.
 * Parameter shapes remain authored adaptations, not measured hardware calibration. */
object FiredBronzePush {
    const val VERSION = 1
    enum class Route { DRY, REFERENCE, PROCESSOR_PUSH, AMP_PUSH, BOTH, EDGE }
    data class Settings(val press:Float=.65f,val heat:Float=.72f,val space:Float=.45f,val bpm:Float=96f,val amp:Float=.70f) {
        init { require(listOf(press,heat,space,amp).all{it.isFinite() && it in 0f..1f});require(bpm.isFinite() && bpm in 60f..180f) }
    }
    data class Recipe(val source:FiredBronzeDryKit.Recipe,val route:Route=Route.BOTH,val settings:Settings=Settings()) {
        fun render():Snip = process(source.render(),source.pad,route,settings)
        fun toJsonText():String = Json.write(JsonValue.Obj(linkedMapOf(
            "study" to JsonValue.Str("FIRED_BRONZE_PUSH"),"version" to JsonValue.Num(VERSION.toDouble()),
            "source" to Json.parse(source.toJsonText()),"route" to JsonValue.Str(route.name),
            "press" to JsonValue.Num(settings.press.toDouble()),"heat" to JsonValue.Num(settings.heat.toDouble()),
            "space" to JsonValue.Num(settings.space.toDouble()),"bpm" to JsonValue.Num(settings.bpm.toDouble()),"amp" to JsonValue.Num(settings.amp.toDouble()))))
        companion object {
            fun fromJsonText(text:String):Recipe {
                val o=Json.parse(text).obj()
                require(o.getValue("study").str()=="FIRED_BRONZE_PUSH" && o.getValue("version").num()==VERSION.toDouble())
                fun f(k:String)=o.getValue(k).num().toFloat()
                return Recipe(FiredBronzeDryKit.Recipe.fromJsonText(Json.write(o.getValue("source"))),Route.valueOf(o.getValue("route").str()),Settings(f("press"),f("heat"),f("space"),f("bpm"),f("amp")))
            }
        }
    }
    private fun metal(p:P?) = p in listOf(P.CLOSED_HAT,P.OPEN_HAT,P.HAT_PEDAL,P.KETTLE_LOW,P.KETTLE_HIGH,P.GONG)
    private fun send(p:P?):Float = when(p) {
        P.KICK->.025f;P.SNARE->.45f;P.CLOSED_HAT,P.OPEN_HAT,P.HAT_PEDAL->.12f
        P.KETTLE_LOW,P.KETTLE_HIGH,P.GONG->.65f;null->.5f;else->.4f
    }
    /** Null pad means a shared bus: detector and amp state span the whole groove. */
    fun process(dry:Snip,pad:P?,route:Route,settings:Settings=Settings()):Snip {
        require(dry.channels==1 && dry.sampleRate==Dsp.RATE)
        val old=FiredBronzeColor.Settings(bpm=settings.bpm)
        return when(route) {
            Route.DRY -> dry.copy(samples=dry.samples.copyOf())
            Route.REFERENCE -> FiredBronzeColor.process(dry,pad,FiredBronzeColor.Stage.AMP,old)
            Route.PROCESSOR_PUSH -> FiredBronzeColor.amp(processor(dry,pad,settings),pad,old)
            Route.AMP_PUSH -> amp(FiredBronzeColor.processor(dry,pad,old),pad,settings)
            Route.BOTH -> amp(processor(dry,pad,settings),pad,settings)
            Route.EDGE -> {
                val edge=settings.copy(press=.85f,heat=.90f,amp=.88f)
                amp(processor(dry,pad,edge),pad,edge)
            }
        }
    }
    internal fun processor(dry:Snip,pad:P?,s:Settings):Snip {
        var x=Eq.process(dry,mapOf("BASS" to .5f,"MID" to .49f,"AIR" to .5f))
        // Long resonances and the shared bus bypass the input gate. Synth sources
        // have no mic spill, so the gate is only gentle cleanup for short drums.
        if(pad!=null && dry.durationSeconds<1.5f) x=FiredBronzeTreatment.gate(x,0f)
        if(s.press>0f) {
            val comp=Compressor.process(x,mapOf("AMOUNT" to s.press,"ATTACK" to .52f,"RECOVER" to .72f))
            x=x.copy(samples=FloatArray(x.frameCount){.25f*x.samples[it]+.75f*comp.samples[it]})
        }
        if(s.heat>0f) x=Valve.process(x,mapOf("DRIVE" to .32f+.48f*s.heat,"SAG" to 0f,"TONE" to .5f,"CAB" to 0f))
        val wet=s.space*send(pad)
        if(wet>0f) {
            x=Echo.process(x,mapOf("TIME" to FiredBronzeTreatment.delayControl(s.bpm),"REPEAT" to .25f,"TONE" to .22f,"MIX" to wet,"HEAD" to .35f))
            x=Spring.process(x,mapOf("SIZE" to .26f,"TONE" to .3f,"MIX" to wet*.65f))
        }
        return finish(x,dry.frameCount)
    }
    internal fun amp(x:Snip,pad:P?,s:Settings):Snip {
        val y=Valve.process(x,mapOf("DRIVE" to .40f+.40f*s.amp-(if(metal(pad)) .04f else 0f),
            "SAG" to if(pad==P.KICK) .08f else .25f,"TONE" to if(metal(pad)) .56f else .5f,
            "CAB" to if(metal(pad)) .23f else .34f))
        return finish(y,x.frameCount)
    }
    private fun finish(s:Snip,sourceFrames:Int):Snip {
        // Cap only added space. Eight-second source tails are never shortened.
        val frames=minOf(s.frameCount,sourceFrames+Dsp.RATE)
        val x=s.samples.copyOf(frames);val mean=x.average().toFloat();val hp=Dsp.FixedOnePole(20f)
        for(i in x.indices){val a=x[i]-mean;x[i]=a-hp.lp(a)}
        Dsp.fadeTailCosine(x,if(frames<s.frameCount).08f else .008f)
        // No fresh house leveling: preserve input velocity and gain relationships.
        Dsp.limitPeak(x,.99f)
        return Snip(x,1,Dsp.RATE)
    }
    fun study():List<ArrangedPad> = P.entries.map { pad ->
        val r=Recipe(FiredBronzeDryKit.Recipe(pad));val hard=r.render()
        ArrangedPad(hard,pad.kind,level=pad.gain,displayName=pad.label,muteGroup=pad.choke,
            softVariants=listOf(.3f,.65f).map{Velocity.peakMatch(hard,r.copy(source=r.source.copy(velocity=it)).render())},
            source=mapOf("study" to "FIRED_BRONZE_PUSH","version" to VERSION.toString(),"recipe" to "recipes/${pad.name.lowercase()}_both.json"))
    }
}
