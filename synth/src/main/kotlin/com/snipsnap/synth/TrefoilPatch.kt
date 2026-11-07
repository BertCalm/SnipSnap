package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

enum class TrefoilPlayback { FINITE, HELD }

/** Complete recipe. Held playback is versioned separately from both finite models. */
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
    val playback: TrefoilPlayback=TrefoilPlayback.FINITE,
    val heldVersion: Int=TrefoilHeld.VERSION,
    val releaseSeconds: Float=TrefoilHeld.DEFAULT_RELEASE_SECONDS,
) : Patch {
    init {
        Patches.validateMacros(this,Trefoil.macrosFor(voice))
        require(midi in Trefoil.MIN_MIDI..Trefoil.MAX_MIDI)
        require(velocity.isFinite() && velocity in 0f..1f)
        require(seconds.isFinite() && seconds in .25f..8f)
        require(activeSeconds.isFinite() && activeSeconds in .05f..seconds-.1f)
        if(playback==TrefoilPlayback.FINITE)
            require((macros["HOLD"] ?: 0f)==0f) { "TREFOIL finite gestures require HOLD=0" }
        else {
            require(model==2) { "TREFOIL held playback requires model 2" }
            TrefoilHeld.validate(voice,macros)
        }
        require(heldVersion==TrefoilHeld.VERSION) { "unsupported TREFOIL held version $heldVersion" }
        require(releaseSeconds.isFinite() && releaseSeconds in TrefoilHeld.MIN_RELEASE_SECONDS..TrefoilHeld.MAX_RELEASE_SECONDS) { "TREFOIL release must be .1..1.5 seconds" }
        require(model in 1..Trefoil.MODEL_VERSION) { "unsupported TREFOIL model $model" }
    }
    override val engine get()=ENGINE
    override val voiceName get()=voice.name
    override fun render()=if(playback==TrefoilPlayback.HELD)TrefoilHeld.render(this).snip
        else Trefoil.render(voice,macros,midi,velocity,seed,seconds,activeSeconds,model)
    override fun withMacros(macros: Map<String,Float>)=copy(macros=macros)
    override fun toJsonValue()=JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).also {
        it["model"]=JsonValue.Num(model.toDouble()); it["midi"]=JsonValue.Num(midi.toDouble())
        it["velocity"]=JsonValue.Num(velocity.toDouble()); it["seed"]=JsonValue.Str(seed.toString())
        it["seconds"]=JsonValue.Num(seconds.toDouble()); it["activeSeconds"]=JsonValue.Num(activeSeconds.toDouble())
        if(playback==TrefoilPlayback.HELD) {
            it["playback"]=JsonValue.Str(playback.name);it["heldVersion"]=JsonValue.Num(heldVersion.toDouble())
        }
        if(playback==TrefoilPlayback.HELD || releaseSeconds!=TrefoilHeld.DEFAULT_RELEASE_SECONDS)
            it["releaseSeconds"]=JsonValue.Num(releaseSeconds.toDouble())
    })
    companion object {
        const val ENGINE="TREFOIL"
        fun fromJsonText(text: String)=fromJsonValue(Json.parse(text))
        fun fromJsonValue(value: JsonValue): TrefoilPatch = try {
            val obj=value.obj()
            fun r(k: String)=obj[k]?.takeUnless { it is JsonValue.Null } ?: throw JsonException("TREFOIL patch has no $k")
            val playback=if("playback" !in obj)TrefoilPlayback.FINITE else {
                val text=r("playback").str();TrefoilPlayback.entries.firstOrNull { it.name==text }
                    ?: throw JsonException("unsupported TREFOIL playback $text")
            }
            val heldVersion=if(playback==TrefoilPlayback.HELD || "heldVersion" in obj)r("heldVersion").int() else TrefoilHeld.VERSION
            val release=if(playback==TrefoilPlayback.HELD || "releaseSeconds" in obj)r("releaseSeconds").num().also {
                if(!it.isFinite() || it !in .1..1.5)throw JsonException("invalid TREFOIL releaseSeconds")
            }.toFloat() else TrefoilHeld.DEFAULT_RELEASE_SECONDS
            val seedText=r("seed").str(); val seed=seedText.toLongOrNull() ?: throw JsonException("invalid TREFOIL seed")
            if(seed.toString()!=seedText) throw JsonException("TREFOIL seed must be canonical decimal text")
            // Reject out-of-bounds Doubles before conversion can round them onto a Float bound.
            for(k in listOf("velocity","seconds","activeSeconds")) {
                val n=r(k).num(); val range=if(k=="velocity") 0.0..1.0 else if(k=="seconds") .25..8.0 else .05..8.0
                if(!n.isFinite() || n !in range) throw JsonException("invalid TREFOIL $k")
            }
            (obj["macros"] as? JsonValue.Obj)?.entries?.forEach { (k,v) ->
                val n=v.num();if(!n.isFinite() || n !in 0.0..1.0)throw JsonException("invalid TREFOIL $k")
                if(k=="HOLD" && n!=(if(playback==TrefoilPlayback.HELD)1.0 else 0.0))throw JsonException("invalid TREFOIL HOLD")
                if(playback==TrefoilPlayback.HELD && k=="NUDGE" && n!=0.0)throw JsonException("invalid TREFOIL held NUDGE")
                if(playback==TrefoilPlayback.HELD && k=="WEAVE" && n!=0.0 && n !in TrefoilHeld.MIN_MOVING_WEAVE.toString().toDouble()..1.0)
                    throw JsonException("invalid TREFOIL held WEAVE")
            }
            Patches.decode(value,ENGINE,{ n -> TrefoilVoice.entries.firstOrNull { it.name==n } }) { n,v,m ->
                TrefoilPatch(n,v,m,r("midi").int(),r("velocity").num().toFloat(),seed,r("seconds").num().toFloat(),
                    r("activeSeconds").num().toFloat(),r("model").int(),playback,heldVersion,release)
            } as TrefoilPatch
        } catch(e: IllegalArgumentException) { throw JsonException(e.message ?: "invalid TREFOIL recipe") }
    }
}
