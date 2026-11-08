package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.DrumClass
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import kotlin.math.*

/** Dry kit recipes around the accepted R2 sources. New drum layers are authored
 * adaptations, not measured traditional instruments. R2 profiles retain their CC BY credit.
 * Separate schema preserves both earlier study renderers byte for byte. */
object FiredBronzeDryKit {
    const val VERSION = 1
    enum class Pad(val label: String, val kind: DrumClass, val gain: Float, val source: FiredBronzeR2.Voice? = null) {
        KICK("Bass Skin Kick", DrumClass.KICK, 1f), SNARE("Rim Rattle Snare", DrumClass.SNARE, .8f),
        CLOSED_HAT("Closed Bronze", DrumClass.HAT_CLOSED, .42f), OPEN_HAT("Open Bronze", DrumClass.HAT_OPEN, .42f),
        TIN("Edge Ring", DrumClass.PERC, .7f, FiredBronzeR2.Voice.TIN),
        TUN("Center Ring", DrumClass.PERC, .75f, FiredBronzeR2.Voice.TUN),
        NA("Rim Ring", DrumClass.PERC, .65f, FiredBronzeR2.Voice.NA),
        TE("Pressed Tap", DrumClass.PERC, .65f, FiredBronzeR2.Voice.TE),
        GA("Bass Stroke", DrumClass.PERC, .85f, FiredBronzeR2.Voice.GA),
        PRESSURE("Moving Bass", DrumClass.LOOP, .7f, FiredBronzeR2.Voice.PRESSURE),
        CLAY_OPEN("Open Clay", DrumClass.PERC, .65f, FiredBronzeR2.Voice.CLAY_OPEN),
        CLAY_CLOSED("Covered Clay", DrumClass.PERC, .65f, FiredBronzeR2.Voice.CLAY_CLOSED),
        KETTLE_LOW("Low Kettle", DrumClass.LOOP, .55f, FiredBronzeR2.Voice.KETTLE),
        KETTLE_HIGH("High Kettle", DrumClass.LOOP, .5f, FiredBronzeR2.Voice.KETTLE),
        HAT_PEDAL("Bronze Foot", DrumClass.HAT_CLOSED, .35f),
        GONG("Long Gong", DrumClass.LOOP, .55f, FiredBronzeR2.Voice.GONG);
        val choke get() = if (this in listOf(CLOSED_HAT, OPEN_HAT, HAT_PEDAL)) 1 else 0
    }
    data class Recipe(val pad: Pad, val tune: Float = .5f, val touch: Float = .5f,
                      val damp: Float = 0f, val velocity: Float = 1f, val take: Int = 0) {
        init { require(listOf(tune,touch,damp,velocity).all { it.isFinite() && it in 0f..1f }) }
        fun render() = FiredBronzeDryKit.render(this)
        fun toJsonText() = Json.write(JsonValue.Obj(linkedMapOf(
            "study" to JsonValue.Str("FIRED_BRONZE_DRY_KIT"), "version" to JsonValue.Num(VERSION.toDouble()),
            "pad" to JsonValue.Str(pad.name), "tune" to JsonValue.Num(tune.toDouble()),
            "touch" to JsonValue.Num(touch.toDouble()), "damp" to JsonValue.Num(damp.toDouble()),
            "velocity" to JsonValue.Num(velocity.toDouble()), "take" to JsonValue.Num(take.toDouble()))))
        companion object {
            fun fromJsonText(text: String): Recipe {
                val o=Json.parse(text).obj()
                require(o.getValue("study").str()=="FIRED_BRONZE_DRY_KIT" && o.getValue("version").num()==VERSION.toDouble())
                fun f(k:String)=o.getValue(k).num().toFloat()
                return Recipe(Pad.valueOf(o.getValue("pad").str()),f("tune"),f("touch"),f("damp"),f("velocity"),o.getValue("take").int())
            }
        }
    }
    fun render(r: Recipe): Snip {
        r.pad.source?.let { source ->
            val tune=if(r.pad==Pad.KETTLE_HIGH) (r.tune+.25f).coerceAtMost(1f) else r.tune
            return FiredBronzeR2.Recipe(source,tune,r.touch,r.damp,velocity=r.velocity,take=r.take).render()
        }
        val rate=Dsp.RATE*Dsp.OVERSAMPLE
        val touch=r.touch*(.35f+.65f*r.velocity)
        val pitch=2.0.pow((r.tune-.5)*2)
        val seconds=when(r.pad){Pad.KICK->.8;Pad.SNARE->.5;Pad.OPEN_HAT->1.2;else->.22}
        val out=FloatArray((seconds*rate).toInt())
        fun source(v:FiredBronzeR2.Voice,tune:Float,gain:Float) {
            val x=FiredBronzeR2.raw(FiredBronzeR2.Recipe(v,tune,touch,r.damp,take=r.take),rate)
            val peak=x.maxOf{abs(it)}.coerceAtLeast(1e-8f)
            for(i in 0 until minOf(x.size,out.size)) out[i]+=x[i]/peak*gain
        }
        if(r.pad==Pad.KICK) {
            source(FiredBronzeR2.Voice.GA,(r.tune-.5f).coerceAtLeast(0f),.72f)
            // A long low membrane support follows the short recorded articulation.
            // No electronic pitch dive: the measured attack carries the transient.
            var phase=0.0
            for(i in out.indices) {
                val t=i.toDouble()/rate;phase+=2*PI*60.4*pitch/rate
                out[i]+=(.42*(1-exp(-t/.006))*exp(-t*(12+24*r.damp))*sin(phase)).toFloat()
            }
        } else if(r.pad==Pad.SNARE) {
            source(FiredBronzeR2.Voice.NA,(r.tune-.28f).coerceAtLeast(0f),.75f)
            source(FiredBronzeR2.Voice.TE,r.tune,.28f)
            val follower=Dsp.FixedOnePole(160f,rate)
            val lo=Dsp.FixedOnePole(1400f,rate);val hi=Dsp.FixedOnePole(10000f,rate)
            val noise=Dsp.Noise(Dsp.seedFor("BRONZE_DRY_RATTLE",r.take))
            var release=0f;val loss=exp(-(38+40*r.damp)/rate)
            for(i in out.indices) {
                // One-way vibration readout drives the rattle, without feedback or
                // independent hiss sustain. Lower velocity reduces wire activity.
                release=max(abs(out[i]),(release*loss).toFloat())
                val activity=follower.lp(release)
                val n=noise.next();out[i]+=(hi.lp(n)-lo.lp(n))*activity*(1.1f+2.5f*touch)
            }
        } else {
            // Same proposed small-bronze spectrum for open/closed/foot articulations.
            // Kettle ratios supply family resemblance; this is not a cymbal measurement.
            val closed=r.pad!=Pad.OPEN_HAT
            val decay=if(r.pad==Pad.HAT_PEDAL) 65.0 else if(closed) 42.0 else 7.0
            for(j in 0 until 24) {
                val hz=FiredBronzeR2.kettleHz[j%4]*2.6*(1+j/4*.173)*pitch
                if(hz>17000) continue
                val step=2*PI*hz/rate;val c=cos(step);val s=sin(step)
                var re=cos(j*2.399);var im=sin(j*2.399);var env=1.0
                val loss=exp(-(decay+18*r.damp+hz/4200)/rate)
                val gain=(.6+touch*hz/5000)/(24*sqrt(1+j/4.0))
                for(i in out.indices) {
                    out[i]+=(im*env*gain).toFloat()
                    val next=re*c-im*s;im=im*c+re*s;re=next;env*=loss
                }
            }
            val noise=Dsp.Noise(Dsp.seedFor("BRONZE_DRY_CONTACT",r.take))
            val lp=Dsp.FixedOnePole(5000f,rate)
            for(i in out.indices) {val n=noise.next();out[i]+=((n-lp.lp(n))*(.06+.12*touch)*exp(-i.toDouble()/rate/(if(closed) .008 else .024))).toFloat()}
        }
        Tide.bandLimit(out,rate)
        val x=Dsp.decimate(out,Dsp.RATE);val mean=x.average().toFloat();val hp=Dsp.FixedOnePole(20f)
        for(i in x.indices){val y=x[i]-mean;x[i]=y-hp.lp(y)}
        Dsp.levelTo(x,Dsp.RATE,Dsp.MELODIC_LOUDNESS_TARGET);Dsp.fadeTailCosine(x,.008f)
        for(i in x.indices) x[i]*=r.velocity*min(1f,i/(Dsp.RATE*.0005f))
        return Snip(x,1,Dsp.RATE)
    }
    fun study():List<ArrangedPad> = Pad.entries.map { pad ->
        val r=Recipe(pad);val hard=r.render()
        ArrangedPad(hard,pad.kind,level=pad.gain,displayName=pad.label,muteGroup=pad.choke,
            softVariants=listOf(.3f,.65f).map { Velocity.peakMatch(hard,r.copy(velocity=it).render()) },
            source=mapOf("study" to "FIRED_BRONZE_DRY_KIT","version" to VERSION.toString(),"recipe" to "recipes/${pad.name.lowercase()}.json"))
    }
}
