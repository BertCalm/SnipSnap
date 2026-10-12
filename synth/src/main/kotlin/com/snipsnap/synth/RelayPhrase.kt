package com.snipsnap.synth

/** Original generated source roles. All share the requested root; no PCM or melody data. */
enum class RelayRole { PLUCK, ORGAN, BELL, SOFT }

data class RelaySource(
    val role: RelayRole,
    val gain: Float,
    val brightness: Float,
    val decaySeconds: Float,
) {
    init {
        require(gain.isFinite() && gain in 0f..1f) { "RELAY source gain must be in 0..1" }
        require(brightness.isFinite() && brightness in 0f..1f) { "RELAY source brightness must be in 0..1" }
        require(decaySeconds.isFinite() && decaySeconds in .04f..4f) { "RELAY source decay must be in .04..4 seconds" }
    }
}

/** A dwell and its smooth handoff, both measured on the fixed phrase clock. */
data class RelaySlot(val source: Int, val beats: Float, val transitionBeats: Float) {
    init {
        require(source in 0..3) { "RELAY source ID must be in 0..3" }
        require(beats.isFinite() && beats in .1f..4f) { "RELAY dwell must be positive (.1..4 beats)" }
        require(transitionBeats.isFinite() && transitionBeats in .01f..2f) { "RELAY transition must be in .01..2 beats" }
    }
}

/** PULSE scales this finite roster's emphasis, never its timing or event count. */
data class RelayAccent(val beat: Float, val strength: Float) {
    init {
        require(beat.isFinite() && beat >= 0f) { "RELAY accent beat must be finite and nonnegative" }
        require(strength.isFinite() && strength in 0f..1f) { "RELAY accent strength must be in 0..1" }
    }
}

data class RelayLoop(val prerollCycles: Int = 2, val wrapFrames: Int = 1024) {
    init {
        require(prerollCycles in 1..8) { "RELAY preroll must be bounded to 1..8 phrases" }
        require(wrapFrames in 256..2048) { "RELAY wrap must be in 256..2048 frames" }
    }
}

/**
 * Complete serializable phrase recipe. The order roster contains permutations of the same
 * source IDs, so morphing cannot add events or change the phrase clock. Source states run
 * continuously even when their scheduled contribution is zero.
 *
 * The four-beat/100 BPM default lasts 2.4 seconds. Eight slots and eight beats are deliberate
 * longer configurations; V1 bounds the entire clock to eight seconds at the slowest tempo.
 */
data class RelayPhrase(
    val sources: List<RelaySource>,
    val slots: List<RelaySlot>,
    val orderRoster: List<List<Int>>,
    val accents: List<RelayAccent>,
    val tempoBpm: Float = 100f,
    val loop: RelayLoop = RelayLoop(),
) {
    val phraseBeats: Double get() = slots.sumOf { it.beats.toDouble() }
    val phraseSeconds: Double get() = phraseBeats * 60.0 / tempoBpm

    init {
        require(sources.size in 1..4) { "RELAY needs 1..4 generated source states" }
        require(sources.map { it.role }.distinct().size == sources.size) { "RELAY source roles must be distinct" }
        require(slots.size in 4..8) { "RELAY needs 4..8 phrase slots" }
        require(slots.all { it.source in sources.indices }) { "RELAY slot references an absent source" }
        require(phraseBeats in 1.0..8.0) { "RELAY phrase must be bounded to 1..8 beats" }
        require(tempoBpm.isFinite() && tempoBpm in 60f..180f) { "RELAY tempo must be in 60..180 BPM" }
        require(orderRoster.size in 1..4) { "RELAY needs 1..4 order alternatives" }
        val sourceIds = slots.map { it.source }.sorted()
        require(orderRoster.all { it.size == slots.size && it.sorted() == sourceIds }) {
            "RELAY orders must preserve the slot source IDs and event count"
        }
        require(accents.size <= 16) { "RELAY accent roster is bounded to 16 events" }
        require(accents.all { it.beat < phraseBeats }) { "RELAY accents must lie within the phrase" }
        require(accents.map { it.beat }.distinct().size == accents.size) { "RELAY accents must have distinct times" }
    }

    /** Freeze caller-owned lists before rendering or retaining the recipe in a patch. */
    fun snapshot() = copy(sources = sources.toList(), slots = slots.toList(),
        orderRoster = orderRoster.map { it.toList() }, accents = accents.toList())
}
