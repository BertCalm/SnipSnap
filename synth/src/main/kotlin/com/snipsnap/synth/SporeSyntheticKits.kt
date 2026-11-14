package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.*
import com.snipsnap.kit.ArrangedPad
import kotlin.math.*

/** Sample-free genre arrangements built around the owner-approved kick/snare pair.
 * The original acoustic Spore shelves remain independent. Version 1 recipes describe
 * the entire source grid, including gestures, rather than masquerading as a single Patch.
 */
object SporeSyntheticKits {
    const val VERSION=1
    enum class Genre(val id:String,val title:String,val bpm:Int) {
        JUNGLE("spore-synth-jungle","Spore Dry Jungle",170),
        DUB("spore-synth-dub","Spore Dub Breaks",85),
        BROKEN("spore-synth-broken","Spore Orbital Breaks",155)
    }
    data class Part(val name:String,val kind:DrumClass,val level:Float,val muteGroup:Int=0)
    val velocities:List<Double> get()=listOf(.3,.65,1.0)
    fun layout(g:Genre):List<Part> {
        val names=when(g) {
            Genre.JUNGLE->listOf("Approved Kick","Heel Kick","Approved Snare","Ghost Snare","Rim Snare","Dry Stick","Tight Hat","Open Hat","Seed Shaker","Dry Ride","Floor Tom","Rack Tom","Hollow Knock","Liquid Zap","Orbit Bell","Snare Echo","Break Roll","Canopy Riser")
            Genre.DUB->listOf("Low Kick","Muted Kick","Low Snare","Cross Stick","Rim Accent","Wood Block","Dark Hat","Loose Hat","Bead Shaker","Bell Accent","Deep Tom","Hand Pluck","Echo Wood","Bubble Drop","Dub Bell","Tape Answer","Triplet Roll","Dub Ascent")
            Genre.BROKEN->listOf("Tight Kick","Pickup Kick","Bright Snare","Wire Clap","High Rim","Click Wood","Sharp Hat","Short Open","Metal Shaker","Ride Tick","High Tom","Metal Knock","Pluck Accent","Laser Drop","Split Bell","Reverse Metal","Broken Roll","Orbit Riser")
        }
        val kinds=listOf(DrumClass.KICK,DrumClass.KICK,DrumClass.SNARE,DrumClass.SNARE,DrumClass.SNARE,DrumClass.PERC,
            DrumClass.HAT_CLOSED,DrumClass.HAT_OPEN,DrumClass.PERC,DrumClass.PERC,DrumClass.TOM,DrumClass.TOM,
            DrumClass.PERC,DrumClass.PERC,DrumClass.PERC,DrumClass.PERC,DrumClass.LOOP,DrumClass.LOOP)
        val levels=if(g==Genre.DUB) listOf(1f,.6f,.85f,.55f,.6f,.5f,.32f,.35f,.30f,.35f,.6f,.5f,.45f,.4f,.35f,.4f,.55f,.5f)
            else listOf(1f,.6f,.9f,.4f,.65f,.5f,.34f,.37f,.28f,.32f,.6f,.55f,.45f,.4f,.35f,.4f,.55f,.5f)
        return names.mapIndexed { i,n -> Part(n,if(g!=Genre.JUNGLE && i in listOf(3,11))DrumClass.PERC else kinds[i],levels[i],if(i in 6..7)1 else 0) }
    }
    data class Recipe(val genre:Genre,val pad:Int,val velocity:Double=1.0,val take:Int=0,val version:Int=VERSION) {
        init { require(pad in 0..17 && take>=0 && velocity.isFinite() && velocity in 0.0..1.0 && version==VERSION) }
        fun render():Snip=renderPad(genre,pad,velocity,take)
        fun toJsonText():String=Json.write(JsonValue.Obj(mapOf("source" to JsonValue.Str("SporeSyntheticKits"),
            "version" to JsonValue.Num(version.toDouble()),"genre" to JsonValue.Str(genre.name),"pad" to JsonValue.Num(pad.toDouble()),
            "velocity" to JsonValue.Num(velocity),"take" to JsonValue.Num(take.toDouble()))))
        companion object {
            fun fromJsonText(s:String):Recipe {
                val m=Json.parse(s).obj();require(m.getValue("source").str()=="SporeSyntheticKits")
                fun number(k:String)=(m.getValue(k) as JsonValue.Num).value
                fun integer(k:String):Int { val n=number(k);require(n==n.toInt().toDouble());return n.toInt() }
                return Recipe(Genre.valueOf(m.getValue("genre").str()),integer("pad"),number("velocity"),integer("take"),integer("version"))
            }
        }
    }
    private fun scale(s:Snip,gain:Double)=s.copy(samples=FloatArray(s.samples.size) { (s.samples[it]*gain).toFloat() })
    private fun bounded(s:Snip,seconds:Double=1.35):Snip {
        val n=min(s.frameCount,(seconds*Dsp.RATE).roundToInt());val a=s.samples.copyOf(n)
        Dsp.fadeTail(a);check(a.all { it.isFinite() } && a.maxOf { abs(it) }<1.001f)
        return Snip(a,1,Dsp.RATE)
    }
    private fun kickControls(g:Genre)=when(g) {
        Genre.JUNGLE->SporeKick.Controls()
        Genre.DUB->SporeKick.Controls(tune=.25,strike=.35,body=.8,click=.35)
        Genre.BROKEN->SporeKick.Controls(tune=.62,strike=.8,body=.30,click=.55)
    }
    private fun snareControls(g:Genre)=when(g) {
        Genre.JUNGLE->SporeSnare.Controls()
        Genre.DUB->SporeSnare.Controls(tune=.3,strike=.4,body=.7,wires=.35)
        Genre.BROKEN->SporeSnare.Controls(tune=.62,strike=.7,body=.32,wires=.7)
    }
    private fun skin(voice:SkinVoice,g:Genre,v:Double,t:Int,overrides:Map<String,Float>):Snip {
        val m=(Skin.defaults(voice)+overrides).toMutableMap()
        // Metal/bead excitation reads a fresh seed; velocity also changes its filter/envelope.
        val tone=listOf("TONE","CLICK","BODY").first { it in m }
        m[tone]=(m.getValue(tone)-.25f*(1-v.toFloat())+listOf(0f,-.018f,.018f)[t%3]).coerceIn(0f,1f)
        if(voice !in Skin.STRUCK)m["DECAY"]=(m.getValue("DECAY")*(.65f+.35f*v.toFloat())).coerceIn(0f,1f)
        val raw=Skin.render(voice,m,v.toFloat(),take=t,reseedUnstruck=true)
        return scale(raw, .65*v.pow(1.1))
    }
    private fun onset(voice:OnsetVoice,v:Double,t:Int,overrides:Map<String,Float>):Snip {
        val m=(Onset.defaults(voice)+overrides).toMutableMap()
        m["SNAP"]=(m.getValue("SNAP")-.3f*(1-v.toFloat())+listOf(0f,-.04f,.04f)[t%3]).coerceIn(0f,1f)
        m["BODY"]=(m.getValue("BODY")+listOf(0f,.035f,-.025f)[t%3]).coerceIn(0f,1f)
        return scale(OnsetPatch("Spore percussion",voice,m).render(),.60*v.pow(1.1))
    }
    private fun color(g:Genre,pad:Int,s:Snip):Snip {
        val time=(ln((60.0/g.bpm*(if(g==Genre.DUB) { if(pad==15).375 else .25 } else { if(pad==15).75 else .5 }))/.06)/ln(.35/.06)).toFloat().coerceIn(0f,1f)
        val fx=FxChain(reverse=pad==15 && g==Genre.BROKEN,
            echo=mapOf("TIME" to time,"REPEAT" to if(g==Genre.DUB).5f else .28f,"TONE" to if(g==Genre.DUB).22f else .5f,"MIX" to .34f,"HEAD" to .8f),
            ring=if(g==Genre.BROKEN)mapOf("FREQ" to .28f,"MIX" to .22f)else null,
            phase=if(pad==14)mapOf("RATE" to .2f,"DEPTH" to .45f,"FEEDBACK" to .12f)else null)
        return bounded(scale(fx.process(s),.70))
    }
    fun renderPad(g:Genre,pad:Int,velocity:Double=1.0,take:Int=0):Snip {
        require(pad in 0..17 && velocity.isFinite() && velocity in 0.0..1.0 && take>=0)
        if(pad>=16)return gesture(g,pad==17,velocity,take)
        val v=velocity;val t=take;val dark=g==Genre.DUB;val bright=g==Genre.BROKEN
        val kc=kickControls(g);val sc=snareControls(g)
        // The two Jungle anchor pads are exact accepted sources; no extra gain, EQ or fade.
        if(pad==0)return SporeKick.render(kc,v,t)
        if(pad==2)return SporeSnare.render(sc,v,t)
        val sound=when(pad) {
            1->SporeKick.render(kc.copy(body=.22,strike=.3,click=.3),v,t)
            3->when(g) {
                Genre.JUNGLE->SporeSnare.render(sc.copy(strike=.25,body=.25,wires=.35),v,t)
                Genre.DUB->skin(SkinVoice.STICK,g,v,t,mapOf("BODY" to .45f,"CLICK" to .3f,"DECAY" to .1f,"TUNE" to .4f))
                Genre.BROKEN->onset(OnsetVoice.SNARE,v,t,mapOf("BLEND" to .1f,"DECAY" to .15f,"SNAP" to .8f))
            }
            4->SporeSnare.render(sc.copy(tune=.72,strike=.8,body=.3,wires=.45),v,t)
            5->if(dark)onset(OnsetVoice.PERC_WOOD,v,t,mapOf("TUNE" to .3f,"DECAY" to .18f,"BLEND" to .8f))
                else skin(SkinVoice.STICK,g,v,t,mapOf("DECAY" to .08f,"BODY" to .3f,"CLICK" to .45f,"PUNCH" to .15f))
            6,7->skin(if(pad==6)SkinVoice.HAT_CLOSED else SkinVoice.HAT_OPEN,g,v,t,mapOf(
                "DECAY" to (if(pad==6).035f else if(bright).13f else .22f),"TONE" to (if(dark).28f else if(bright).72f else .52f),
                "RING" to (if(bright).3f else .06f),"PUNCH" to .1f))
            8->skin(SkinVoice.SHAKER,g,v,t,mapOf("DECAY" to (if(dark).25f else .1f),"TONE" to (if(bright).75f else .42f),"GRAIN" to .35f))
            9->if(dark)onset(OnsetVoice.PERC_METAL,v,t,mapOf("TUNE" to .35f,"DECAY" to .25f,"BLEND" to .8f))
                else skin(SkinVoice.RIDE,g,v,t,mapOf("TUNE" to (if(bright).7f else .45f),"DECAY" to (if(bright).12f else .3f),"TONE" to .4f,"SIZZLE" to .2f))
            10->skin(SkinVoice.TOM,g,v,t,mapOf("TUNE" to (if(dark).14f else if(bright).55f else .28f),"DECAY" to (if(dark).4f else .2f),"TONE" to .5f,"PUNCH" to .25f))
            11->if(g==Genre.JUNGLE)skin(SkinVoice.TOM,g,v,t,mapOf("TUNE" to .65f,"DECAY" to .15f,"TONE" to .55f))
                else onset(if(dark)OnsetVoice.PERC_PLUCK else OnsetVoice.PERC_METAL,v,t,mapOf("TUNE" to .6f,"DECAY" to .18f,"BLEND" to .65f))
            12->onset(if(dark)OnsetVoice.PERC_WOOD else OnsetVoice.PERC_PLUCK,v,t,mapOf("TUNE" to .5f,"DECAY" to .2f,"BLEND" to .75f))
            13-> {
                val m=Tines.defaults(TinesVoice.ZAP)+mapOf("DECAY" to .2f,"DROP" to (.6f+listOf(0f,-.035f,.035f)[t%3]),"TUNE" to if(dark).2f else .55f)
                scale(Velocity.atVelocity(TinesPatch("Spore Zap",TinesVoice.ZAP,m),v.toFloat()),.6*v.pow(1.1))
            }
            14->onset(OnsetVoice.PERC_METAL,v,t,mapOf("TUNE" to if(dark).25f else .7f,"DECAY" to .45f,"BLEND" to if(bright).2f else .75f))
            15->if(bright)onset(OnsetVoice.PERC_METAL,v,t,mapOf("TUNE" to .6f,"DECAY" to .4f,"BLEND" to .6f))else SporeSnare.render(sc,v,t)
            else->error("pad")
        }
        return if(pad in 14..15 || dark && pad==12)color(g,pad,sound)else bounded(sound)
    }
    data class Event(val beat:Double,val force:Double,val tune:Double)
    fun gestureEvents(g:Genre,rising:Boolean):List<Event> {
        val beats=if(rising)(0..7).map { it*.25 }+(0..15).map { 2.0+it*.125 }
            else if(g==Genre.DUB)(0..11).map { it/3.0 } else (0..15).map { it*.25 }
        return beats.mapIndexed { i,b -> Event(b,if(rising).3+.65*b/4 else if(i%4==0).9 else .5,
            if(rising).3+.5*b/4 else snareControls(g).tune) }
    }
    private fun gesture(g:Genre,rising:Boolean,v:Double,t:Int):Snip {
        val events=gestureEvents(g,rising)
        val out=FloatArray((4.0*60/g.bpm*Dsp.RATE).roundToInt()+SporeSnare.FRAMES)
        for((i,e)in events.withIndex()) {
            val c=snareControls(g).copy(tune=e.tune,body=if(rising).35 else .3,wires=if(rising).7 else .4)
            val hit=SporeSnare.render(c,v*e.force,t*events.size+i)
            val at=(e.beat*60/g.bpm*Dsp.RATE).roundToInt()
            for(j in hit.samples.indices)out[at+j]+=hit.samples[j]*.55f
        }
        Dsp.fadeTail(out);check(out.all { it.isFinite() } && out.maxOf { abs(it) }<1)
        return Snip(out,1,Dsp.RATE)
    }
    fun arranged(g:Genre):List<ArrangedPad> =layout(g).mapIndexed { i,p ->
        val grid=velocities.map { v -> (0..2).map { t -> Recipe(g,i,v,t).render() } }
        ArrangedPad(grid[2][0],p.kind,level=p.level,oneShot=true,displayName=p.name,muteGroup=p.muteGroup,velocityTakes=grid,
            source=mapOf("sourceBank" to "SporeSyntheticKits/v$VERSION","sourceRecipe" to Recipe(g,i).toJsonText(),
                "velocities" to ".3,.65,1.0","takes" to "3","kickModelSha256" to SporeKick.MODEL_SHA256,
                "snareModelSha256" to SporeSnare.MODEL_SHA256,"status" to "approved core; expanded arrangement awaiting listen"))
    }
}
