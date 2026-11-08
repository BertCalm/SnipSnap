package com.snipsnap.synth

import com.snipsnap.kit.ArrangedPad

/** Sixteen accepted roles. Room variants retain the same source recipe on every pad. */
object RivetKits {
    val velocities = listOf(.3f, .6f, 1f)

    fun patches(size: Float? = null): List<RivetPatch> {
        require(size == null || size.isFinite() && size in 0f..1f)
        fun p(name: String, voice: RivetVoice, vararg controls: Pair<String, Float>): RivetPatch =
            RivetPatch(name, voice, Rivet.defaults(voice) + mapOf("WALLS" to .75f) + controls.toMap() +
                (size?.let { mapOf("SIZE" to it) } ?: emptyMap()))
        return listOf(
            p("Workshop Kick", RivetVoice.KICK),
            p("Wire Snare", RivetVoice.SNARE),
            p("Closed Metal", RivetVoice.HAT_CLOSED, "TUNE" to .35f, "METAL" to .6f, "DRIVE" to .3f),
            p("Open Metal", RivetVoice.HAT_OPEN, "TUNE" to .35f, "METAL" to .6f, "DRIVE" to .3f),
            p("Sub Press", RivetVoice.KICK, "TUNE" to .05f, "METAL" to .2f, "DRIVE" to .6f),
            p("Hard Crack", RivetVoice.SNARE, "TUNE" to .65f, "STRIKE" to .9f, "METAL" to .25f, "DRIVE" to .65f),
            p("Press", RivetVoice.METAL, "TUNE" to .2f, "STRIKE" to .9f, "METAL" to .8f),
            p("Pipe", RivetVoice.METAL, "TUNE" to .65f, "METAL" to .4f, "DRIVE" to .7f),
            p("Low Tom", RivetVoice.TOM, "TUNE" to .15f, "METAL" to .35f),
            p("Mid Tom", RivetVoice.TOM, "TUNE" to .5f, "METAL" to .55f),
            p("High Tom", RivetVoice.TOM, "TUNE" to .85f, "METAL" to .85f),
            p("Sheet", RivetVoice.METAL, "TUNE" to .4f, "METAL" to 1f, "DRIVE" to .45f),
            p("Steel Kick", RivetVoice.KICK, "TUNE" to .55f, "METAL" to .9f, "DRIVE" to .65f),
            p("Loose Wires", RivetVoice.SNARE, "TUNE" to .35f, "METAL" to .9f, "DRIVE" to .55f),
            p("Closed Scrap", RivetVoice.HAT_CLOSED, "TUNE" to .6f, "METAL" to .95f, "DRIVE" to .65f),
            p("Open Scrap", RivetVoice.HAT_OPEN, "TUNE" to .6f, "METAL" to .95f, "DRIVE" to .65f),
        )
    }

    fun build(size: Float? = null): List<ArrangedPad> = patches(size).map { patch ->
        val hard = patch.render()
        val hats = patch.voice == RivetVoice.HAT_CLOSED || patch.voice == RivetVoice.HAT_OPEN
        ArrangedPad(hard, Rivet.drumClassFor(patch.voice, patch.macros), PadRecipe(patch).toJsonValue(),
            softVariants = velocities.dropLast(1).map { Velocity.peakMatch(hard, Velocity.atVelocity(patch, it)) },
            oneShot = true, displayName = patch.name, muteGroup = if (hats) 1 else 0,
            layerNames = listOf("Soft", "Middle", "Hard"), source = mapOf(
                "engine" to RivetPatch.ENGINE, "status" to "accepted-audition",
                "modelVersion" to patch.modelVersion.toString(), "velocities" to velocities.joinToString(",")))
    }
}
