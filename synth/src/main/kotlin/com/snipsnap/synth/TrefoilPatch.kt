package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Complete finite recipe. Model 1 fixes solver/reference/time-map and excitation laws. */
data class TrefoilPatch(
    override val name: String,
    val voice: TrefoilVoice,
    override val macros: Map<String,Float> = Trefoil.defaults(voice),
    val midi: Int=Trefoil.DEFAULT_MIDI,
    val velocity: Float=.8f,
    val seed: Long=43L,
    val seconds: Float=6f,
    val activeSeconds: Float=3.5f,
    val model: Int=Trefoil.MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this,Trefoil.macrosFor(voice))
        require(midi in Trefoil.MIN_MIDI..Trefoil.MAX_MIDI)
        require(velocity.isFinite() && velocity in 0f..1f)
        require(seconds.isFinite() && seconds in .25f..8f)
        require(activeSeconds.isFinite() && activeSeconds in .05f..seconds-.1f)
        require((macros["HOLD"] ?: 0f)==0f) { "TREFOIL model 1 supports finite gestures only; HOLD must be 0" }
        require(model==Trefoil.MODEL_VERSION) { "unsupported TREFOIL model $model" }
    }
    override val engine get()=ENGINE
    override val voiceName get()=voice.name
    override fun render()=Trefoil.render(voice,macros,midi,velocity,seed,seconds,activeSeconds)
    override fun withMacros(macros: Map<String,Float>)=copy(macros=macros)
    override fun toJsonValue()=JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).also {
        it["model"]=JsonValue.Num(model.toDouble()); it["midi"]=JsonValue.Num(midi.toDouble())
        it["velocity"]=JsonValue.Num(velocity.toDouble()); it["seed"]=JsonValue.Str(seed.toString())
        it["seconds"]=JsonValue.Num(seconds.toDouble()); it["activeSeconds"]=JsonValue.Num(activeSeconds.toDouble())
    })
    companion object {
        const val ENGINE="TREFOIL"
        fun fromJsonText(text: String)=fromJsonValue(Json.parse(text))
        fun fromJsonValue(value: JsonValue): TrefoilPatch = try {
            val obj=value.obj()
            fun r(k: String)=obj[k]?.takeUnless { it is JsonValue.Null } ?: throw JsonException("TREFOIL patch has no $k")
            val seedText=r("seed").str(); val seed=seedText.toLongOrNull() ?: throw JsonException("invalid TREFOIL seed")
            if(seed.toString()!=seedText) throw JsonException("TREFOIL seed must be canonical decimal text")
            // Reject out-of-bounds Doubles before conversion can round them onto a Float bound.
            for(k in listOf("velocity","seconds","activeSeconds")) {
                val n=r(k).num(); val range=if(k=="velocity") 0.0..1.0 else if(k=="seconds") .25..8.0 else .05..8.0
                if(!n.isFinite() || n !in range) throw JsonException("invalid TREFOIL $k")
            }
            (obj["macros"] as? JsonValue.Obj)?.entries?.forEach { (k,v) ->
                val n=v.num(); if(!n.isFinite() || n !in 0.0..1.0 || (k=="HOLD" && n!=0.0)) throw JsonException("invalid TREFOIL $k")
            }
            Patches.decode(value,ENGINE,{ n -> TrefoilVoice.entries.firstOrNull { it.name==n } }) { n,v,m ->
                TrefoilPatch(n,v,m,r("midi").int(),r("velocity").num().toFloat(),seed,r("seconds").num().toFloat(),
                    r("activeSeconds").num().toFloat(),r("model").int())
            } as TrefoilPatch
        } catch(e: IllegalArgumentException) { throw JsonException(e.message ?: "invalid TREFOIL recipe") }
    }
}
