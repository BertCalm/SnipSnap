package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.synth.FiredBronzeDryKit.Pad as P

/** Playing-response recipes around the accepted BOTH treatment.
 * Full velocity is exactly the accepted render; lower drive at softer strikes is
 * an authored control law, not a claim about electrical input-level behavior. */
object FiredBronzePerformance {
    const val VERSION=1
    val layerVelocities=listOf(.2f,.45f,.7f,1f)
    fun settings(velocity:Float):FiredBronzePush.Settings {
        require(velocity.isFinite() && velocity in 0f..1f)
        val base=FiredBronzePush.Settings()
        if(velocity==1f)return base
        // Preserve space and timing. Pull back nonlinear excitation, not merely
        // the already-applied source gain, to give quiet strokes a different edge.
        return base.copy(press=base.press*(.8f+.2f*velocity),
            heat=base.heat*(.65f+.35f*velocity),amp=base.amp*(.72f+.28f*velocity))
    }
    fun process(dry:Snip,pad:P,velocity:Float):Snip =
        FiredBronzePush.process(dry,pad,FiredBronzePush.Route.BOTH,settings(velocity))
    data class Recipe(val source:FiredBronzeDryKit.Recipe) {
        fun render()=process(source.render(),source.pad,source.velocity)
        fun toJsonText()=Json.write(JsonValue.Obj(linkedMapOf(
            "study" to JsonValue.Str("FIRED_BRONZE_PERFORMANCE"),"version" to JsonValue.Num(VERSION.toDouble()),
            "source" to Json.parse(source.toJsonText()))))
        companion object {
            fun fromJsonText(text:String):Recipe {
                val o=Json.parse(text).obj()
                require(o.getValue("study").str()=="FIRED_BRONZE_PERFORMANCE" && o.getValue("version").num()==VERSION.toDouble())
                return Recipe(FiredBronzeDryKit.Recipe.fromJsonText(Json.write(o.getValue("source"))))
            }
        }
    }
    fun study():List<ArrangedPad> = P.entries.map { p ->
        val r=Recipe(FiredBronzeDryKit.Recipe(p));val hard=r.render()
        ArrangedPad(hard,p.kind,level=p.gain,displayName=p.label,muteGroup=p.choke,
            softVariants=layerVelocities.dropLast(1).map{Velocity.peakMatch(hard,r.copy(source=r.source.copy(velocity=it)).render())},
            layerNames=listOf("LIGHT","MEDIUM","FIRM","FULL"),
            source=mapOf("study" to "FIRED_BRONZE_PERFORMANCE","version" to VERSION.toString(),"recipe" to "recipes/${p.name.lowercase()}_100.json"))
    }
}
