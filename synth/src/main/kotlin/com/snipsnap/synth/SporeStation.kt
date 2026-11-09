package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.ArrangedPad
import kotlin.math.ln

/** A curated 170 BPM kit, not a new synthesis engine. All motion is saved in ordinary recipes. */
object SporeStation {
    const val BPM = 170
    const val VERSION = 1
    data class Part(val name: String, val patch: Patch, val kind: DrumClass, val level: Float = .7f)
    data class Controls(val body: Float = .5f, val bite: Float = .35f, val dust: Float = .15f,
                        val twist: Float = .1f, val space: Float = .2f) {
        init { require(listOf(body, bite, dust, twist, space).all { it.isFinite() && it in 0f..1f }) }
    }
    private fun m(vararg pairs: Pair<String, Float>) = mapOf(*pairs)
    private fun skin(name: String, voice: SkinVoice, kind: DrumClass, level: Float = .7f,
                     vararg values: Pair<String, Float>) = Part(name, SkinPatch(name, voice, Skin.defaults(voice) + values), kind, level)
    private fun onset(name: String, voice: OnsetVoice, kind: DrumClass, level: Float = .7f,
                      vararg values: Pair<String, Float>) = Part(name, OnsetPatch(name, voice, Onset.defaults(voice) + values, modelVersion = 4), kind, level)
    fun parts(): List<Part> = listOf(
        onset("Root Kick", OnsetVoice.KICK, DrumClass.KICK, .9f, "DECAY" to .14f, "BLEND" to .65f, "SNAP" to .38f),
        skin("Heel Kick", SkinVoice.KICK, DrumClass.KICK, .65f, "TUNE" to .55f, "DECAY" to .16f, "CLICK" to .4f),
        skin("Canopy Crack", SkinVoice.SNARE, DrumClass.SNARE, .85f, "TUNE" to .55f, "DECAY" to .28f, "SNAP" to .72f, "RATTLE" to .62f),
        skin("Understory", SkinVoice.SNARE, DrumClass.SNARE, .28f, "DECAY" to .12f, "SNAP" to .36f, "PUNCH" to .12f, "RATTLE" to .25f),
        onset("Fracture", OnsetVoice.SNARE, DrumClass.SNARE, .75f, "TUNE" to .7f, "DECAY" to .25f, "BLEND" to .35f, "SNAP" to .8f),
        skin("Twig", SkinVoice.STICK, DrumClass.PERC, .5f, "DECAY" to .14f, "BODY" to .25f),
        skin("Needle", SkinVoice.HAT_CLOSED, DrumClass.HAT_CLOSED, .42f, "DECAY" to .12f, "TONE" to .42f, "RING" to .2f),
        skin("Aperture", SkinVoice.HAT_OPEN, DrumClass.HAT_OPEN, .42f, "DECAY" to .24f, "TONE" to .42f, "RING" to .2f),
        skin("Dust", SkinVoice.SHAKER, DrumClass.PERC, .33f, "DECAY" to .2f, "TONE" to .4f),
        skin("Halo Ride", SkinVoice.RIDE, DrumClass.PERC, .4f, "DECAY" to .22f, "TONE" to .38f),
        onset("Root Tom", OnsetVoice.TOM, DrumClass.TOM, .65f, "TUNE" to .2f, "DECAY" to .25f),
        onset("Spore Knock", OnsetVoice.PERC_PLUCK, DrumClass.PERC, .55f, "DECAY" to .2f, "BLEND" to .7f),
        onset("Chrome Seed", OnsetVoice.PERC_METAL, DrumClass.PERC, .45f, "DECAY" to .32f, "BODY" to .5f),
        Part("Liquid Zap", TinesPatch("Liquid Zap", TinesVoice.ZAP, Tines.defaults(TinesVoice.ZAP) + m("DECAY" to .2f, "DROP" to .7f)), DrumClass.PERC, .45f),
        Part("Canopy Call", SirenPatch("Canopy Call", SirenVoice.BIRD, Siren.defaults(SirenVoice.BIRD) + m("HOLD" to .12f, "GRIT" to .15f)), DrumClass.PERC, .4f),
        skin("Event Horizon", SkinVoice.SNARE, DrumClass.PERC, .5f, "DECAY" to .3f, "SNAP" to .65f),
    )
    /** Invert ECHO's exponential time map; the baked delay is tied to this kit's documented tempo. */
    fun echoTime(beats: Float): Float = (ln((60f / BPM * beats) / .06f) / ln(.35f / .06f)).coerceIn(0f, 1f)

    fun recipe(index: Int, mutation: Boolean = false, controls: Controls = Controls()): PadRecipe {
        require(index in 0..15)
        val part = parts()[index]
        val old = part.patch.macros
        val changes = mutableMapOf<String, Float>()
        // BODY is explicitly a source balance/decay control, not a physical simulation.
        val bodyKey = if ("BODY" in old) "BODY" else "DECAY"
        if (bodyKey in old) changes[bodyKey] = (old.getValue(bodyKey) + (controls.body - .5f) * .3f).coerceIn(0f, 1f)
        val biteKey = listOf("PUNCH", "SNAP", "BITE", "GRIT").firstOrNull { it in old }
        if (biteKey != null) changes[biteKey] = (old.getValue(biteKey) + (controls.bite - .35f) * .5f).coerceIn(0f, 1f)
        val patch = part.patch.withMacros(old + changes)
        val color = index >= 10
        val effect = mutation || color
        val space = if (effect) if (mutation) .28f + .72f * controls.space else .08f + .92f * controls.space else 0f
        val twist = if (effect) if (mutation) .2f + .8f * controls.twist else controls.twist else 0f
        val fx = FxChain(
            reverse = index == 15 || mutation && index == 5,
            crunch = if (controls.dust > 0f) m("BITS" to controls.dust * .35f, "RATE" to controls.dust * .3f,
                "TONE" to .8f, "GRIT" to controls.dust * .2f) else null,
            ring = if (effect && index !in listOf(0, 1, 6, 7)) m("FREQ" to .22f, "MIX" to twist * .45f) else null,
            phase = if (effect && index >= 6) m("RATE" to .18f, "DEPTH" to twist, "FEEDBACK" to .12f) else null,
            echo = if (space > 0f && index !in listOf(0, 1, 6, 7)) m("TIME" to echoTime(if (index % 2 == 0) .5f else .75f),
                "REPEAT" to .25f + space * .25f, "TONE" to .28f, "MIX" to space * .55f, "HEAD" to .8f) else null,
            tape = if (mutation) m("WOBBLE" to .12f, "DRIVE" to .12f, "AGE" to .22f) else null,
        )
        return PadRecipe(patch, fx, terminalFadeMs = 4f)
    }

    fun arranged(mutation: Boolean = false): List<ArrangedPad> = parts().mapIndexed { index, part ->
        val recipe = recipe(index, mutation)
        val hard = recipe.render()
        val soft = listOf(.3f, .6f).map { velocity ->
            // Match layer peaks once, retaining velocity-dependent timbre. The sampler applies velocity gain.
            Velocity.peakMatch(hard, recipe.process(Velocity.atVelocity(requireNotNull(recipe.patch), velocity)))
        }
        ArrangedPad(hard, part.kind, recipe.toJsonValue(), level = part.level, oneShot = true,
            softVariants = soft, displayName = part.name + if (mutation) " Mutation" else "",
            layerNames = listOf("Soft", "Middle", "Hard"), muteGroup = if (index in 6..7) 1 else 0,
            source = mapOf("kit" to "Spore Station", "version" to VERSION.toString(), "bpm" to BPM.toString(), "status" to "candidate-awaiting-listen"))
    }
}
