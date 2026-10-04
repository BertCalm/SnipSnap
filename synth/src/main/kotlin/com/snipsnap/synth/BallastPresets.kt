package com.snipsnap.synth

/** BALLAST's first listening roster: two deliberately separated states per voice. */
object BallastPresets {
    private fun p(
        voice: BallastVoice,
        name: String,
        midi: Int = Ballast.DEFAULT_MIDI,
        vararg macros: Pair<String, Float>,
    ) = BallastPatch(name, voice, macros.toMap(), midi)

    fun forVoice(voice: BallastVoice): List<BallastPatch> = when (voice) {
        BallastVoice.ROOT -> listOf(
            p(voice, "TIGHT ROOT", 36, "DRIVE" to .35f, "SYMPATHY" to .18f, "SPAN" to .22f, "GLASS" to .06f, "FRAME" to .28f, "HOLD" to 0f),
            p(voice, "DEEP FRAME", 32, "DRIVE" to .52f, "SYMPATHY" to .42f, "SPAN" to .62f, "GLASS" to .12f, "FRAME" to .82f, "HOLD" to .25f),
        )
        BallastVoice.WIRE -> listOf(
            p(voice, "LONG WIRE", 36, "DRIVE" to .44f, "SYMPATHY" to .88f, "SPAN" to .48f, "GLASS" to .12f, "FRAME" to .55f, "HOLD" to .42f),
            p(voice, "WIDE OCTAVES", 40, "DRIVE" to .50f, "SYMPATHY" to .72f, "SPAN" to .92f, "GLASS" to .18f, "FRAME" to .46f, "HOLD" to .18f),
        )
        BallastVoice.GLINT -> listOf(
            p(voice, "QUIET GLASS", 38, "DRIVE" to .34f, "SYMPATHY" to .32f, "SPAN" to .46f, "GLASS" to .34f, "FRAME" to .38f, "HOLD" to .08f),
            p(voice, "UPPER HALO", 43, "DRIVE" to .46f, "SYMPATHY" to .56f, "SPAN" to .82f, "GLASS" to .72f, "FRAME" to .52f, "HOLD" to .30f),
        )
        BallastVoice.DEEP -> listOf(
            p(voice, "LOWER ECHO", 28, "DRIVE" to .48f, "SYMPATHY" to .58f, "SPAN" to .90f, "GLASS" to .08f, "FRAME" to .72f, "HOLD" to .32f),
            p(voice, "LOOSE MOUNT", 31, "DRIVE" to .58f, "SYMPATHY" to .50f, "SPAN" to .70f, "GLASS" to .28f, "FRAME" to .94f, "HOLD" to .48f),
        )
        BallastVoice.BLOOM -> listOf(
            p(voice, "DELAYED BLOOM", 36, "DRIVE" to .56f, "SYMPATHY" to .76f, "SPAN" to .52f, "GLASS" to .46f, "FRAME" to .78f, "HOLD" to .58f),
            p(voice, "GLASS WAKE", 39, "DRIVE" to .62f, "SYMPATHY" to .58f, "SPAN" to .62f, "GLASS" to .78f, "FRAME" to .74f, "HOLD" to .22f),
        )
        BallastVoice.SWARM -> listOf(
            p(voice, "DENSE TILES", 35, "DRIVE" to .82f, "SYMPATHY" to .64f, "SPAN" to .76f, "GLASS" to .94f, "FRAME" to .68f, "HOLD" to .38f),
            p(voice, "HELD FRAME", 36, "DRIVE" to .68f, "SYMPATHY" to .70f, "SPAN" to .72f, "GLASS" to .58f, "FRAME" to .76f, "HOLD" to 1f),
        )
    }

    fun all(): List<BallastPatch> = BallastVoice.entries.flatMap(::forVoice)
}
