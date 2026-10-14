package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.roundToInt

/** A complete deterministic bellows/wheel recipe; TUNE offsets the saved MIDI base. */
data class BellowsPatch(
    override val name: String,
    val voice: BellowsVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Bellows.ROOT_MIDI,
    val velocity: Float = 1f,
    val model: Int = Bellows.MODEL_VERSION,
    val gesture: BellowsGesture = Bellows.gestureFor(voice),
) : Patch {
    init {
        Patches.validateMacros(this, Bellows.macrosFor(voice))
        val range = Bellows.midiRange(voice)
        require(midi in range) { "BELLOWS $voice midi out of 48..72: $midi" }
        val tune = macros["TUNE"] ?: Bellows.defaults(voice).getValue("TUNE")
        val playedMidi = midi + (tune * Bellows.TUNE_SEMITONES).roundToInt()
        require(playedMidi in range) { "BELLOWS $voice played midi out of 48..72: $playedMidi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "BELLOWS velocity out of 0..1: $velocity" }
        require(Bellows.supportsModel(model)) { "unsupported bellows model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Bellows.render(voice, macros, midi, velocity, gesture, model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)
    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["gesture"] = JsonValue.Str(gesture.name)
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "BELLOWS"
        const val VERSION = Patches.VERSION
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: 1
            if (!Bellows.supportsModel(model)) throw JsonException("unsupported bellows model $model")
            return Patches.decode(value, ENGINE, { n -> BellowsVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Bellows.ROOT_MIDI
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                val gestureName = obj["gesture"]?.takeUnless { it is JsonValue.Null }?.str()
                val gesture = if (gestureName == null) Bellows.gestureFor(voice) else
                    BellowsGesture.entries.firstOrNull { it.name == gestureName }
                        ?: throw JsonException("unknown bellows gesture $gestureName")
                try { BellowsPatch(name, voice, macros, midi, velocity, model, gesture) }
                catch (e: IllegalArgumentException) { throw JsonException(e.message ?: "invalid BELLOWS patch") }
            }
        }
        fun fromJsonText(text: String): BellowsPatch = fromJsonValue(Json.parse(text)) as BellowsPatch
    }
}
