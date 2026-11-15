package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue
import kotlin.math.*

/** Audition recipes over the existing tape and oversampled amp, not a new engine. */
object PatioTreatments {
    const val VERSION = 1
    data class Recipe(val id: String, val title: String, val wear: Float, val wobble: Float,
                      val tapeInput: Float, val drive: Float, val sag: Float, val tone: Float,
                      val cab: Float, val dropout: Float = 0f) {
        init {
            require(listOf(wear, wobble, drive, sag, tone, cab, dropout).all { it.isFinite() && it in 0f..1f })
            require(tapeInput.isFinite() && tapeInput in 1f..4f)
        }
        fun json(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
            "id" to JsonValue.Str(id), "version" to JsonValue.Num(VERSION.toDouble()),
            "wear" to JsonValue.Num(wear.toDouble()), "wobble" to JsonValue.Num(wobble.toDouble()),
            "tapeInput" to JsonValue.Num(tapeInput.toDouble()), "drive" to JsonValue.Num(drive.toDouble()),
            "sag" to JsonValue.Num(sag.toDouble()), "tone" to JsonValue.Num(tone.toDouble()),
            "cab" to JsonValue.Num(cab.toDouble()), "dropout" to JsonValue.Num(dropout.toDouble())))
    }
    val ALL = listOf(
        Recipe("pushed", "Pushed", .55f, .25f, 1.7f, .72f, .28f, .62f, .35f),
        Recipe("chewed", "Chewed", .95f, .95f, 2f, .80f, .55f, .38f, .55f, .80f),
        Recipe("overloaded", "Overloaded", .70f, .35f, 2.4f, .94f, .85f, .52f, .60f),
    )
    fun process(input: Snip, recipe: Recipe, seed: Int = 729): Snip {
        // Drive tape before its level protection. Valve is peak-relative, so its
        // DRIVE control, not an input gain shortcut, sets the amp breakup.
        val driven = input.copy(samples = FloatArray(input.samples.size) { input.samples[it] * recipe.tapeInput })
        val tape = PatioWear.tape(driven, PatioWear.Settings(recipe.wear, recipe.wobble, recipe.drive), seed)
        if (recipe.dropout > 0f) {
            // Three smooth finite losses across the six-second audition. The
            // schedule is deliberate, repeatable, and not a mechanical tape model.
            for (time in doubleArrayOf(1.02, 2.73, 4.15)) {
                val centre = (time * tape.sampleRate).toInt()
                val radius = (.035 * tape.sampleRate).toInt()
                for (i in max(0, centre - radius)..min(tape.samples.lastIndex, centre + radius)) {
                    val envelope = .5 + .5 * cos(PI * (i - centre) / radius)
                    tape.samples[i] *= (1 - recipe.dropout * envelope).toFloat()
                }
            }
        }
        return PatioWear.finish(Valve.process(tape, mapOf("DRIVE" to recipe.drive, "SAG" to recipe.sag,
            "TONE" to recipe.tone, "CAB" to recipe.cab)))
    }
}
