package com.snipsnap.synth

/** BALLAST's first listening roster: two deliberately separated states per voice. */
object BallastPresets {
    private fun p(voice: BallastVoice, name: String, vararg macros: Pair<String, Float>) =
        BallastPatch(name, voice, macros.toMap())

    private fun at(
        voice: BallastVoice,
        name: String,
        midi: Int,
        vararg macros: Pair<String, Float>,
    ) = BallastPatch(name, voice, macros.toMap(), midi)

    fun forVoice(voice: BallastVoice): List<BallastPatch> = when (voice) {
        BallastVoice.ROOT -> listOf(
            p(voice, "TIGHT ROOT", "DRIVE" to .35f, "SYMPATHY" to .18f, "SPAN" to .22f, "GLASS" to .06f, "FRAME" to .28f, "HOLD" to 0f),
            at(voice, "DEEP FRAME", 32, "DRIVE" to .52f, "SYMPATHY" to .42f, "SPAN" to .62f, "GLASS" to .12f, "FRAME" to .82f, "HOLD" to .25f),
        )
        BallastVoice.WIRE -> listOf(
            p(voice, "LONG WIRE", "DRIVE" to .44f, "SYMPATHY" to .88f, "SPAN" to .48f, "GLASS" to .12f, "FRAME" to .55f, "HOLD" to .42f),
            at(voice, "WIDE OCTAVES", 40, "DRIVE" to .50f, "SYMPATHY" to .72f, "SPAN" to .92f, "GLASS" to .18f, "FRAME" to .46f, "HOLD" to .18f),
        )
        BallastVoice.GLINT -> listOf(
            at(voice, "QUIET GLASS", 38, "DRIVE" to .34f, "SYMPATHY" to .32f, "SPAN" to .46f, "GLASS" to .34f, "FRAME" to .38f, "HOLD" to .08f),
            at(voice, "UPPER HALO", 43, "DRIVE" to .46f, "SYMPATHY" to .56f, "SPAN" to .82f, "GLASS" to .72f, "FRAME" to .52f, "HOLD" to .30f),
        )
        BallastVoice.DEEP -> listOf(
            at(voice, "LOWER ECHO", 28, "DRIVE" to .48f, "SYMPATHY" to .58f, "SPAN" to .90f, "GLASS" to .08f, "FRAME" to .72f, "HOLD" to .32f),
            at(voice, "LOOSE MOUNT", 31, "DRIVE" to .58f, "SYMPATHY" to .50f, "SPAN" to .70f, "GLASS" to .28f, "FRAME" to .94f, "HOLD" to .48f),
        )
        BallastVoice.BLOOM -> listOf(
            p(voice, "DELAYED BLOOM", "DRIVE" to .56f, "SYMPATHY" to .76f, "SPAN" to .52f, "GLASS" to .46f, "FRAME" to .78f, "HOLD" to .58f),
            at(voice, "GLASS WAKE", 39, "DRIVE" to .62f, "SYMPATHY" to .58f, "SPAN" to .62f, "GLASS" to .78f, "FRAME" to .74f, "HOLD" to .22f),
        )
        BallastVoice.SWARM -> listOf(
            at(voice, "DENSE TILES", 35, "DRIVE" to .82f, "SYMPATHY" to .64f, "SPAN" to .76f, "GLASS" to .94f, "FRAME" to .68f, "HOLD" to .38f),
            p(voice, "HELD FRAME", "DRIVE" to .68f, "SYMPATHY" to .70f, "SPAN" to .72f, "GLASS" to .58f, "FRAME" to .76f, "HOLD" to 1f),
        )
    }

    fun all(): List<BallastPatch> = BallastVoice.entries.flatMap(::forVoice)
}
