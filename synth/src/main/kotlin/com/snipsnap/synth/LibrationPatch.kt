package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A finite, replayable Libration performance, including its saved rendering model. */
data class LibrationPatch(
    override val name: String,
    val voice: LibrationVoice,
    override val macros: Map<String, Float> = Libration.defaults(voice),
    val midi: Int = 60,
    val velocity: Float = .8f,
    val model: Int = Libration.MODEL_VERSION,
    val sourceSeconds: Double = 6.0,
) : Patch {
    init {
        Patches.validateMacros(this, Libration.macrosFor(voice))
        Libration.settled(voice, macros)
        require(midi in Libration.MIN_MIDI..Libration.MAX_MIDI)
        require(velocity.isFinite() && velocity in 0f..1f)
        require(model in 1..Libration.MODEL_VERSION)
        require(sourceSeconds.isFinite() && sourceSeconds in Libration.MIN_SOURCE_SECONDS..Libration.MAX_SOURCE_SECONDS)
        require(model >= 3 || sourceSeconds == 6.0) { "saved models 1/2 use their original six-second note" }
    }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Libration.render(voice, macros, midi, velocity, model, sourceSeconds)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)
    override fun toJsonValue() = JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).apply {
        put("midi", JsonValue.Num(midi.toDouble()))
        put("velocity", JsonValue.Num(velocity.toDouble()))
        put("model", JsonValue.Num(model.toDouble()))
        if (model >= 3) put("sourceSeconds", JsonValue.Num(sourceSeconds))
    })
    companion object {
        const val ENGINE = "LIBRATION"
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.int() ?: throw JsonException("LIBRATION model is required")
            return Patches.decode(value, ENGINE, { n -> LibrationVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                LibrationPatch(name, voice, macros, obj["midi"]?.int() ?: 60,
                    obj["velocity"]?.num()?.toFloat() ?: .8f, model, obj["sourceSeconds"]?.num() ?: 6.0)
            }
        }
        fun fromJsonText(text: String) = fromJsonValue(Json.parse(text)) as LibrationPatch
    }
}
