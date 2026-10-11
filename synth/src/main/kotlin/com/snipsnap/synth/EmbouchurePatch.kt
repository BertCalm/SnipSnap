package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.roundToInt

/**
 * A saved virtual player's note. The renderer model pins driver/resonator calibration,
 * gesture segments, envelope and loop policy; MIDI, velocity and gesture travel beside
 * the macros. Driver selection is fixed by voice and model, and is explicit in JSON.
 * TUNE offsets the stored MIDI base over 24 semitones, as in TESSERA. A resulting note
 * outside the voice's supported register is refused rather than silently clamped.
 */
data class EmbouchurePatch(
    override val name: String,
    val voice: EmbouchureVoice,
    override val macros: Map<String, Float>,
    val midi: Int = Embouchure.rootMidi(voice),
    val velocity: Float = 1f,
    val model: Int = Embouchure.MODEL_VERSION,
    val gesture: EmbouchureGesture = Embouchure.gestureFor(voice),
) : Patch {
    init {
        Patches.validateMacros(this, Embouchure.macrosFor(voice))
        val range = Embouchure.midiRange(voice)
        require(midi in range) { "EMBOUCHURE $voice midi out of ${range.first}..${range.last}: $midi" }
        val tune = macros["TUNE"] ?: Embouchure.defaults(voice).getValue("TUNE")
        val playedMidi = midi + (tune * Embouchure.TUNE_SEMITONES).roundToInt()
        require(playedMidi in range) { "EMBOUCHURE $voice played midi out of ${range.first}..${range.last}: $playedMidi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "EMBOUCHURE velocity out of 0..1: $velocity" }
        require(Embouchure.supportsModel(model)) { "unsupported embouchure model $model" }
    }

    val driver get() = Embouchure.driverFor(voice)
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Embouchure.render(voice, macros, midi, velocity, gesture, model)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["driver"] = JsonValue.Str(driver.name)
        obj["gesture"] = JsonValue.Str(gesture.name)
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "EMBOUCHURE"
        const val VERSION = Patches.VERSION
        private const val INITIAL_MODEL = 1

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: INITIAL_MODEL
            if (!Embouchure.supportsModel(model)) throw JsonException("unsupported embouchure model $model")
            return Patches.decode(value, ENGINE, { n -> EmbouchureVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Embouchure.rootMidi(voice)
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                val driver = obj["driver"]?.takeUnless { it is JsonValue.Null }?.str()
                if (driver != null && driver != Embouchure.driverFor(voice).name) {
                    throw JsonException("EMBOUCHURE $voice uses ${Embouchure.driverFor(voice)}, got driver $driver")
                }
                val gestureName = obj["gesture"]?.takeUnless { it is JsonValue.Null }?.str()
                val gesture = if (gestureName == null) Embouchure.gestureFor(voice) else {
                    EmbouchureGesture.entries.firstOrNull { it.name == gestureName }
                        ?: throw JsonException("unknown embouchure gesture $gestureName")
                }
                EmbouchurePatch(name, voice, macros, midi, velocity, model, gesture)
            }
        }

        fun fromJsonText(text: String): EmbouchurePatch = fromJsonValue(Json.parse(text)) as EmbouchurePatch
    }
}
