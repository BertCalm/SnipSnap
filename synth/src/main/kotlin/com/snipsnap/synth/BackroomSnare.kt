package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * The accepted More Snap recipe with four coordinated controls, saved as SKIN SNARE model 2.
 * The recording chain is part of this instrument: its dark saturation otherwise masks the
 * snare's source controls. Model 1 remains the original, unprocessed SKIN renderer.
 */
object BackroomSnare {
    val macros = listOf("ATTACK", "BODY", "WIRE", "BRIGHTNESS").map { MacroSpec(it, .5f) }
    fun defaults(): Map<String, Float> = macros.associate { it.name to it.default }
    fun scramble(random: Random): Map<String, Float> = macros.associate { it.name to random.nextFloat() }

    private fun bend(x: Float, low: Float, mid: Float, high: Float): Float =
        if (x == .5f) mid else if (x < .5f) low + (mid - low) * (x * 2f)
        else mid + (high - mid) * ((x - .5f) * 2f)

    /**
     * Compose two bounded responses in odds space. Unlike summing then clipping, both
     * knobs still move at the corners. Exact centre branches preserve the heard studies.
     */
    private fun combine(a: Float, b: Float, centre: Float): Float {
        if (a == centre) return b
        if (b == centre) return a
        val odds = (a.toDouble() / (1 - a)) * (b.toDouble() / (1 - b)) /
            (centre.toDouble() / (1 - centre))
        return (odds / (1 + odds)).toFloat()
    }

    /** Resolve afresh from all four controls; slider edit order never enters the recipe. */
    fun recipe(values: Map<String, Float>): PadRecipe {
        fun control(key: String) = values[key]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f
        val attack = control("ATTACK")
        val body = control("BODY")
        val wire = control("WIRE")
        val brightness = control("BRIGHTNESS")
        val base = BoomBapKits.parts().single { it.id == "snare" }.patch
        val fx = BoomBapKits.colorationFor("snare")
        val w = if (wire < .5f) .5f * (wire * 2f).pow(.25f) else wire
        var snap = combine(bend(body, .88f, .70f, .40f), bend(w, .38f, .70f, .86f), .70f)
        // Reveal short wires as the rack opens. Zero on both centre lines, so the
        // individual studies remain exact; long wires need no extra excitation.
        val open = (2f * brightness - 1f).coerceAtLeast(0f)
        val reveal = (1f - exp(-4f * open)) / (1f - exp(-4f))
        val lift = (1f - 2f * wire).coerceAtLeast(0f) * reveal * .55f
        snap = (snap + lift).coerceAtMost(1f)
        val patch = base.copy(macros = base.macros + mapOf(
            "TUNE" to bend(body, .80f, .47f, .10f),
            "SNAP" to snap,
            "DECAY" to combine(bend(body, .22f, .40f, .65f), bend(w, .30f, .40f, .75f), .40f),
            "RATTLE" to bend(w, 0f, .62f, 1f),
            "PUNCH" to bend(attack, 0f, .76f, 1f),
        ))
        return PadRecipe(patch, fx.copy(
            spike = fx.spike!! + mapOf("ATTACK" to bend(attack, 0f, .42f, 1f),
                "SUSTAIN" to bend(attack, .70f, .50f, .25f)),
            crunch = fx.crunch!! + ("TONE" to bend(brightness, .12f, .55f, 1f)),
            tape = fx.tape!! + mapOf("DRIVE" to bend(attack, .60f, .50f, .30f),
                "AGE" to bend(brightness, 1f, .60f, .05f)),
        ))
    }

    fun render(values: Map<String, Float>, velocity: Float = 1f, take: Int = 0): Snip {
        val recipe = recipe(values)
        val source = recipe.patch as SkinPatch
        val fx = requireNotNull(recipe.fx)
        val v = velocity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        if (v == 1f && take == 0) return recipe.render()
        val dry = Skin.render(source.voice, source.macros, velocity = v, take = take)
        val seen = StrikeVelocity.levelSeen(v)
        // Velocity.layerAt owns peak matching. Doing it here as well would round
        // twice and change the accepted PCM24 layers. Re-strike callers match too.
        return fx.process(dry.copy(samples = FloatArray(dry.samples.size) { dry.samples[it] * seen }))
    }
}
