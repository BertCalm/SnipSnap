package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.*
import kotlin.math.*

/** The owner-approved 2026-11-14 snare, ported from the reference-calibrated Python study.
 * A procedural oscillator/noise model: no recording, sampled impulse or spectrogram frame.
 * Version 1 preserves the accepted seed stream and Python conditioning rather than silently
 * substituting house Float filters. The dedicated parity test checks the resulting PCM.
 * This is a finite source and native bank, not a new one-voice engine/picker registration.
 */
object SporeSnare {
    const val MODEL_VERSION = 1
    const val FRAMES = 19845
    const val MODEL_SHA256 = SporeSnareData.MODEL_SHA256

    data class Controls(val tune: Double=.5, val strike: Double=.5, val body: Double=.5, val wires: Double=.5) {
        init { require(listOf(tune,strike,body,wires).all { it.isFinite() && it in 0.0..1.0 }) }
    }
    /** Explicit saved source contract; independent of the final drum-engine registration. */
    data class Recipe(val controls: Controls=Controls(), val velocity: Double=1.0, val take: Int=0, val model: Int=MODEL_VERSION) {
        init { require(model==MODEL_VERSION && velocity.isFinite() && velocity in 0.0..1.0 && take>=0) }
        fun render() = SporeSnare.render(controls,velocity,take)
        fun toJsonText(): String = Json.write(JsonValue.Obj(mapOf(
            "source" to JsonValue.Str("SporeSnare"), "model" to JsonValue.Num(model.toDouble()),
            "tune" to JsonValue.Num(controls.tune), "strike" to JsonValue.Num(controls.strike),
            "body" to JsonValue.Num(controls.body), "wires" to JsonValue.Num(controls.wires),
            "velocity" to JsonValue.Num(velocity), "take" to JsonValue.Num(take.toDouble()))))
        companion object {
            fun fromJsonText(text: String): Recipe {
                val v=Json.parse(text).obj()
                require(v.getValue("source").str()=="SporeSnare")
                fun n(k:String)=(v.getValue(k) as JsonValue.Num).value
                require(n("take")==n("take").toInt().toDouble() && n("model")==MODEL_VERSION.toDouble())
                return Recipe(Controls(n("tune"),n("strike"),n("body"),n("wires")),n("velocity"),n("take").toInt(),n("model").toInt())
            }
        }
    }

    private val source=SporeCore.Model("SPORE-SNARE",
        doubleArrayOf(300.0,520.0,780.0,1285.0,1650.0),
        doubleArrayOf(140.0,260.0,400.0,700.0,1100.0,1800.0,3000.0,5000.0,8500.0,15000.0),
        doubleArrayOf(.035,.10,.26,.55),doubleArrayOf(.10,.30),
        SporeSnareData.SEEDS,SporeSnareData.GAINS,SporeSnareData.OUTPUT_GAIN,.00065,.055,.005)
    fun render(c:Controls=Controls(),velocity:Double=1.0,take:Int=0):Snip =
        SporeCore.render(source,c.tune,c.strike,c.body,c.wires,velocity,take)
}
