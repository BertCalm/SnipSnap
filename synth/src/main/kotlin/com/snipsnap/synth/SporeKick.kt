package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.*
import kotlin.math.*

/** The owner-approved 2026-11-14 kick, ported from the reference-calibrated Python study.
 * A procedural oscillator/noise model: no recording, sampled impulse or spectrogram frame.
 * Version 1 preserves the accepted seed stream and Python conditioning rather than silently
 * substituting house Float filters. The dedicated parity test checks the resulting PCM.
 * This is a finite source and native bank, not a new one-voice engine/picker registration.
 */
object SporeKick {
    const val MODEL_VERSION = 1
    const val FRAMES = 19845
    const val MODEL_SHA256 = SporeKickData.MODEL_SHA256

    data class Controls(val tune: Double=.5, val strike: Double=.5, val body: Double=.5, val click: Double=.5) {
        init { require(listOf(tune,strike,body,click).all { it.isFinite() && it in 0.0..1.0 }) }
    }
    /** Explicit saved source contract; independent of the final drum-engine registration. */
    data class Recipe(val controls: Controls=Controls(), val velocity: Double=1.0, val take: Int=0, val model: Int=MODEL_VERSION) {
        init { require(model==MODEL_VERSION && velocity.isFinite() && velocity in 0.0..1.0 && take>=0) }
        fun render() = SporeKick.render(controls,velocity,take)
        fun toJsonText(): String = Json.write(JsonValue.Obj(mapOf(
            "source" to JsonValue.Str("SporeKick"), "model" to JsonValue.Num(model.toDouble()),
            "tune" to JsonValue.Num(controls.tune), "strike" to JsonValue.Num(controls.strike),
            "body" to JsonValue.Num(controls.body), "click" to JsonValue.Num(controls.click),
            "velocity" to JsonValue.Num(velocity), "take" to JsonValue.Num(take.toDouble()))))
        companion object {
            fun fromJsonText(text: String): Recipe {
                val v=Json.parse(text).obj()
                require(v.getValue("source").str()=="SporeKick")
                fun n(k:String)=(v.getValue(k) as JsonValue.Num).value
                require(n("take")==n("take").toInt().toDouble() && n("model")==MODEL_VERSION.toDouble())
                return Recipe(Controls(n("tune"),n("strike"),n("body"),n("click")),n("velocity"),n("take").toInt(),n("model").toInt())
            }
        }
    }

    private val source=SporeCore.Model("SPORE-KICK",
        doubleArrayOf(62.0,96.0,148.0,187.0,240.0),
        doubleArrayOf(80.0,150.0,300.0,600.0,1200.0,2500.0,5000.0,10000.0,16000.0),
        doubleArrayOf(.02,.06,.15,.35),doubleArrayOf(.20,.55),
        SporeKickData.SEEDS,SporeKickData.GAINS,SporeKickData.OUTPUT_GAIN,.0012,-.65,.012)
    fun render(c:Controls=Controls(),velocity:Double=1.0,take:Int=0):Snip =
        SporeCore.render(source,c.tune,c.strike,c.body,c.click,velocity,take)
}
