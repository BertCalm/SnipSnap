package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Saved app recipe. MIDI is the centre of TUNE; the standalone audition Recipe keeps absolute MIDI. */
data class BristlePatch(
    override val name: String,
    val voice: Bristle.Route,
    override val macros: Map<String, Float>,
    val midi: Int = 36,
    val velocity: Float = 1f,
    val model: Int = Bristle.MODEL_VERSION,
    val take: Int = 0,
    val effects: BristleEffects.Settings = BristleEffects.Settings(),
) : Patch {
    init {
        Patches.validateMacros(this, Bristle.macrosFor(voice))
        require(midi in Bristle.MIDI_MIN..Bristle.MIDI_MAX)
        require(velocity.isFinite() && velocity in 0f..1f)
        require(model == Bristle.MODEL_VERSION)
        require(take in 0..1) { "BRISTLE take must be 0 or 1" }
    }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Bristle.renderTake(Bristle.Recipe(voice, Bristle.rootMidi(this), velocity, macros, model, effects), take)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)
    override fun toJsonValue() = JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).apply {
        put("midi", JsonValue.Num(midi.toDouble()))
        put("velocity", JsonValue.Num(velocity.toDouble()))
        put("model", JsonValue.Num(model.toDouble()))
        if (take != 0) put("take", JsonValue.Num(take.toDouble()))
        if (!effects.isOff) put("effects", effects.toJsonValue())
    })
    companion object {
        const val ENGINE = "BRISTLE"
        const val VERSION = Patches.VERSION
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            if (obj.keys - setOf("take", "effects") != setOf("engine", "version", "name", "voice", "macros", "midi", "velocity", "model"))
                throw JsonException("Incomplete or unknown BRISTLE patch fields")
            val midi = obj.getValue("midi").int()
            val velocity = obj.getValue("velocity").num()
            val model = obj.getValue("model").int()
            val take = obj["take"]?.int() ?: 0
            val effects = obj["effects"]?.let(BristleEffects.Settings::fromJsonValue) ?: BristleEffects.Settings()
            if (take !in 0..1) throw JsonException("Unsupported BRISTLE take")
            if (midi !in Bristle.MIDI_MIN..Bristle.MIDI_MAX || !velocity.isFinite() || velocity !in 0.0..1.0 || model != Bristle.MODEL_VERSION)
                throw JsonException("Unsupported BRISTLE note, velocity or model")
            val savedMacros = obj.getValue("macros") as? JsonValue.Obj
                ?: throw JsonException("patch macros must be an object")
            savedMacros.entries.forEach { (key, raw) ->
                val v = raw.num()
                if (key !in Bristle.defaults || !v.isFinite() || v !in 0.0..1.0)
                    throw JsonException("Invalid BRISTLE macro $key")
            }
            return Patches.decode(value, ENGINE, { n -> Bristle.Route.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                BristlePatch(name, voice, macros, midi, velocity.toFloat(), model, take, effects)
            }
        }
        fun fromJsonText(text: String) = fromJsonValue(Json.parse(text)) as BristlePatch
    }
}
