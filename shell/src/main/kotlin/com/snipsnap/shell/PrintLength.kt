package com.snipsnap.shell

/**
 * How long a tempo-locked print runs: a whole number of bars at the
 * kit's tempo, so a print drops onto the groove grid instead of being a
 * free length to trim afterwards. Bars = 0 is FREE (the ceiling applies).
 */
object PrintLength {

    /** The choices the surface cycles through; 0 is FREE. */
    val BARS: List<Int> = listOf(0, 1, 2, 4, 8)

    fun seconds(bars: Int, bpm: Float, beatsPerBar: Int = 4): Float {
        require(bars > 0) { "bars is a count, got $bars (0 is FREE: no length)" }
        require(bpm.isFinite() && bpm > 0f) { "tempo must be positive, got $bpm" }
        require(beatsPerBar > 0) { "beats per bar must be positive, got $beatsPerBar" }
        return bars * beatsPerBar * 60f / bpm
    }

    fun label(bars: Int): String = when (bars) {
        0 -> "FREE"
        1 -> "1 BAR"
        else -> "$bars BARS"
    }
}
