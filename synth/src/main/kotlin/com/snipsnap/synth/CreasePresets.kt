package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** A complete native listening recipe. CREASE is not registered with the production Patch dispatcher yet. */
data class CreasePreset(
    val id: String,
    val name: String,
    val voice: CreaseVoice,
    val macros: Map<String, Float>,
    val family: String,
    val description: String,
    val midi: Int = 60,
    val velocity: Float = .8f,
    val model: Int = 3,
) {
    init {
        require(id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Invalid CREASE preset id: $id" }
        require(name.isNotBlank() && family.isNotBlank() && description.isNotBlank()) { "Incomplete CREASE preset description" }
        require(model in 1..3) { "Unsupported CREASE preset model: $model" }
        require(macros.keys == Crease.defaults(voice, model).keys) { "CREASE preset must store all six controls" }
        require(macros.values.all { it.isFinite() && it in 0f..1f }) { "CREASE preset control outside 0..1" }
        require(midi in Crease.MIDI_MIN..Crease.MIDI_MAX) { "CREASE preset MIDI outside the supported range" }
        require(velocity.isFinite() && velocity in 0f..1f) { "CREASE preset velocity outside 0..1" }
    }

    fun render(): Snip = Crease.render(voice, macros, midi, velocity, model)
}

/** Six original listening positions: a fast and a slower evolving role for each voice. */
object CreasePresets {
    const val MODEL_VERSION = 3

    private fun p(
        id: String, name: String, voice: CreaseVoice, family: String, description: String,
        fold: Float, pressure: Float, drag: Float, split: Float, relax: Float, hold: Float,
        midi: Int = 60,
    ) = CreasePreset(id, name, voice, Crease.defaults(voice, MODEL_VERSION) + mapOf(
        "FOLD" to fold, "PRESSURE" to pressure, "DRAG" to drag,
        "SPLIT" to split, "RELAX" to relax, "HOLD" to hold,
    ), family, description, midi = midi, model = MODEL_VERSION)

    private val roster = listOf(
        p("corner-bass", "Corner Bass", CreaseVoice.ROUND, "Bass",
            "A low, rounded note with a quick pressure accent and a short recovery.",
            .10f, .65f, .05f, .08f, .08f, 0f, midi = 36),
        p("paper-keys", "Paper Keys", CreaseVoice.ROUND, "Keys",
            "A richer round key tone with a slower bloom and a longer unfolding tail.",
            .60f, .70f, .65f, .25f, .55f, .25f),
        p("reed-mark", "Reed Mark", CreaseVoice.PINCH, "Accent",
            "A focused nasal accent that follows the pressure gesture quickly.",
            .72f, .88f, .05f, .25f, .10f, 0f),
        p("return-stroke", "Return Stroke", CreaseVoice.PINCH, "Lead",
            "A more open reed lead with a slower rise and a long afterimage between pressure pushes.",
            .24f, .42f, .55f, .60f, .65f, .45f),
        p("late-mark", "Late Mark", CreaseVoice.RUBBER, "Elastic",
            "A bright elastic mark: the note starts first, and the layers catch up later.",
            .40f, .85f, .85f, .80f, .30f, 0f),
        p("ink-ribbon", "Ink Ribbon", CreaseVoice.RUBBER, "Texture",
            "A sustained vowel-like shape with a long, gently clearing afterimage.",
            .68f, .55f, .30f, .35f, .90f, .60f),
    )

    fun all(): List<CreasePreset> = roster
    fun forVoice(voice: CreaseVoice): List<CreasePreset> = roster.filter { it.voice == voice }
}
