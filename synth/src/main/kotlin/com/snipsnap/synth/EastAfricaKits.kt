package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import kotlin.math.*

/** Curated fitted-synthesis banks. Modal/noise percussion plus FM log bass, not sample playback.
 * SKIN/TERRA provide general physical drums; this bank retains measured per-zone resonances
 * and transient envelopes from the approved study instead of substituting their stock voices.
 * Finite printed phrases deliberately have no HOLD or melodic/keygroup contract.
 */
object EastAfricaKits {
    const val VERSION=1
    const val TAKES=3
    internal const val BANK_GAIN=.8094915028064269
    val velocities=listOf(.34f,.64f,1f)
    val voices=listOf("deep-kick","tight-kick","snare","rimshot","rim","closed-hat","open-hat","shaker",
        "low-bongo","high-bongo","conga","bell","low-log","high-log","hand-roll","shaker-rise",
        "muted-tap","open-conga","clave","wood-knock","metal-low","metal-high")
    enum class Genre(val slug:String,val title:String,val bpm:Int,val tune:Double,val damp:Double) {
        BONGO_FLAVA("bongo-flava","BONGO FLAVA",100,1.0,1.0),
        SINGELI("singeli","SINGELI",200,1.08,2.0),
        BONGOPIANO("bongopiano","BONGOPIANO",112,1.0,.65),
        GENGE("genge","GENGE",96,.97,1.4),
        KAPUKA("kapuka","KAPUKA",108,1.03,1.15);
        val id get()="east-africa-$slug"
    }
    fun kind(role:Int):DrumClass = when(role) {
        0,1->DrumClass.KICK; 2,3->DrumClass.SNARE; 5->DrumClass.HAT_CLOSED; 6->DrumClass.HAT_OPEN
        12,13->DrumClass.TONAL; 14,15->DrumClass.LOOP; else->DrumClass.PERC
    }
    /** The bank keeps the complete palette; shelf balance emphasizes each arrangement's roles. */
    fun level(g:Genre,role:Int):Float = when(g) {
        Genre.BONGO_FLAVA -> when(role) { 0,2,8,9,10->1f; else->.8f }
        Genre.SINGELI -> when(role) { 1,2,4,5,16,19,20,21->1f; else->.65f }
        Genre.BONGOPIANO -> when(role) { 0,7,10,12,13,17->1f; else->.75f }
        Genre.GENGE -> when(role) { 0,2,3,4->1f; else->.7f }
        Genre.KAPUKA -> when(role) { 1,2,7,8,9,18->1f; else->.8f }
    }
    private fun source(g:Genre,role:Int)=mapOf("engine" to "EAST_AFRICA","model" to VERSION.toString(),
        "genre" to g.name,"role" to role.toString(),"voice" to voices[role],
        "velocities" to velocities.joinToString(","),"takes" to TAKES.toString(),
        "bpm" to g.bpm.toString(),"sourceType" to "fitted-modal-noise-synthesis",
        "articulation" to when(role) { 14->"roll";15->"riser";else->"finite" })
    fun build(g:Genre):List<ArrangedPad> {
        val grids=mutableMapOf<Int,List<List<Snip>>>()
        for(r in voices.indices.filter { it !in 14..15 })
            grids[r]=velocities.map { v->List(TAKES) { t->render(g,r,v,t) } }
        for(r in 14..15) grids[r]=velocities.mapIndexed { z,_->List(TAKES) { t->
            phrase(g,r,t) { role,take->grids.getValue(role)[z][take] }
        } }
        return voices.mapIndexed { r,name->
            val grid=grids.getValue(r)
            ArrangedPad(grid.last().first(),kind(r),displayName=name.split('-').joinToString(" "){it.replaceFirstChar(Char::uppercaseChar)},
                level=level(g,r),velocityTakes=grid,muteGroup=if(r in 5..6)1 else 0,source=source(g,r))
        }
    }
    fun replay(source:Map<String,String>):List<List<Snip>> {
        require(source["engine"]=="EAST_AFRICA" && source["model"]==VERSION.toString())
        val g=Genre.valueOf(source.getValue("genre")); val r=source.getValue("role").toInt()
        require(r in voices.indices && source==source(g,r)) { "Unsupported or incomplete source identity" }
        return velocities.map { v->List(TAKES) { t->render(g,r,v,t) } }
    }
    fun render(g:Genre,role:Int,velocity:Float,take:Int):Snip {
        require(role in voices.indices && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        if(role in 14..15) return phrase(g,role,take) { r,t->render(g,r,velocity,t) }
        val zone=velocities.indices.minBy { abs(velocity-velocities[it]) }
        val x=EastAfricaModel.render(voices[role],zone,take,g.tune,g.damp)
        val gain=velocity/velocities[zone]
        if(gain!=1f) for(i in x.indices) x[i]*=gain
        return Snip(x,1,Dsp.RATE)
    }
    private fun phrase(g:Genre,role:Int,take:Int,hit:(Int,Int)->Snip):Snip {
        val duration=240.0/g.bpm; val x=FloatArray(((duration+1.2)*Dsp.RATE).roundToInt())
        val strikes=mutableMapOf<Pair<Int,Int>,Snip>()
        for(j in 0..15) {
            val r=if(role==14) listOf(8,9,10)[j%3] else 7
            val key=r to (take+j)%TAKES
            val a=strikes.getOrPut(key) { hit(key.first,key.second) }.samples
            val at=(duration*(j/16.0).pow(if(role==14).85 else 1.0)*Dsp.RATE).roundToInt()
            val gain=(.38+.48*j/15).toFloat()
            for(i in 0 until min(a.size,x.size-at)) x[at+i]+=a[i]*gain
        }
        for(i in 0..440) x[x.size-441+i]*=(440-i)/440f
        return Snip(x,1,Dsp.RATE)
    }
}
