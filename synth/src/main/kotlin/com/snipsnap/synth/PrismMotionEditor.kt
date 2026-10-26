package com.snipsnap.synth

/** Recipe edits only. Slider changes never render or re-analyze a source. */
object PrismMotionEditor {
    const val MIN_SECONDS = .1f
    const val MAX_SECONDS = 6f
    const val DEFAULT_SECONDS = .8f

    /** Existing authored A/B and controls stay exact; a plain PRISM patch gets a body-to-template opening. */
    fun ensureMotion(patch: PrismPatch): PrismPatch = withMotion(patch, motionFor(patch))

    /** AMOUNT changes the distance travelled from the saved morph position, not the starting spectrum. */
    fun withAmount(patch: PrismPatch, amount: Float): PrismPatch {
        val motion = motionFor(patch)
        return withMotion(patch, motion.copy(evolve = (amount.takeIf { it.isFinite() } ?: motion.evolve).coerceIn(0f, 1f)))
    }

    /** OPEN uses this duration once; REPEAT uses it for one complete A-to-B-to-A cycle. */
    fun withTime(patch: PrismPatch, seconds: Float): PrismPatch {
        val motion = motionFor(patch)
        return withMotion(patch, motion.copy(seconds = (seconds.takeIf { it.isFinite() } ?: motion.seconds)
            .coerceIn(MIN_SECONDS, MAX_SECONDS)))
    }

    /** Leaving REPEAT also leaves its loop-only HOLD setting, without changing either saved spectrum. */
    fun withRepeat(patch: PrismPatch, repeat: Boolean): PrismPatch =
        withMotion(patch, motionFor(patch).copy(repeat = repeat))

    /** A separate preview recipe. The editor's live recipe and its motion controls remain untouched. */
    fun staticDestination(patch: PrismPatch): PrismPatch =
        withMotion(patch, motionFor(patch).copy(morph = 1f, evolve = 0f, repeat = false))

    private fun motionFor(patch: PrismPatch): PrismMotion = patch.motion ?: run {
        val destination = Prism.amplitudeEndpoint(patch.template)
        PrismMotion(amplitudesB = destination, amplitudesA = PrismOpeningPresets.bodyAmplitudes(destination),
            morph = 0f, evolve = 1f, seconds = DEFAULT_SECONDS, groupLag = 0f)
    }

    internal fun withMotion(patch: PrismPatch, motion: PrismMotion): PrismPatch {
        val hold = Prism.settled(patch.voice, patch.macros).getValue("HOLD")
        val macros = if (!motion.repeat && Prism.isLoop(hold))
            patch.macros + ("HOLD" to Math.nextDown(Prism.LOOP_THRESHOLD)) else patch.macros
        return if (patch.motion == motion && patch.macros == macros) patch else patch.copy(macros = macros, motion = motion)
    }
}
