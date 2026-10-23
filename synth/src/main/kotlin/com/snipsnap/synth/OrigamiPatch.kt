package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A self-contained first-listen recipe: generated source, spectral geometry and finite envelope.
 * An explicit MIDI note overrides TUNE; null lets TUNE choose the note. The decimal seed is stored
 * as text so even a 64-bit seed survives JSON without floating-point rounding.
 */
data class OrigamiPatch(
    override val name: String,
    val voice: OrigamiVoice,
    override val macros: Map<String, Float>,
    val midi: Int? = null,
    val velocity: Float = 1f,
    val seed: Long = 0L,
    val model: Int = Origami.MODEL_VERSION,
    val gesture: OrigamiGesture = OrigamiGesture.STEADY,
) : Patch {
    init {
        Patches.validateMacros(this, Origami.macrosFor(voice))
        require(midi == null || midi in Origami.MIDI_MIN..Origami.MIDI_MAX) {
            "ORIGAMI midi must be ${Origami.MIDI_MIN}..${Origami.MIDI_MAX}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "ORIGAMI velocity must be 0..1: $velocity" }
        require(model in LEGACY_MODEL..Origami.MODEL_VERSION) { "unsupported origami model $model" }
        require(gesture == OrigamiGesture.STEADY || model == OrigamiGestures.GEOMETRY_MODEL) {
            "ORIGAMI fold gestures require model 4; older recipes retain their original motion"
        }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = if (gesture == OrigamiGesture.STEADY) Origami.render(voice, macros, velocity, midi, seed, model)
        else OrigamiGestureRenderer.render(this)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    /** Fill an editor's controls without replacing sparse legacy recipes with newer defaults. */
    fun resolvedMacros(): Map<String, Float> = when (model) {
        1 -> OrigamiV1.defaults(voice)
        2 -> OrigamiV2.defaults(voice)
        3 -> OrigamiV3.defaults(voice)
        else -> Origami.defaults(voice)
    } + macros

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = midi?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["seed"] = JsonValue.Str(seed.toString())
        // Omission keeps every existing canonical recipe byte-for-byte unchanged.
        if (gesture != OrigamiGesture.STEADY) obj["gesture"] = JsonValue.Obj(linkedMapOf(
            "model" to JsonValue.Num(OrigamiGestures.MODEL_VERSION.toDouble()),
            "name" to JsonValue.Str(gesture.name),
        ))
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "ORIGAMI"
        const val LEGACY_MODEL = 1

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: LEGACY_MODEL
            if (model !in LEGACY_MODEL..Origami.MODEL_VERSION) throw JsonException("unsupported origami model $model")
            val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int()
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
            val seed = when (val saved = obj["seed"]) {
                null, JsonValue.Null -> 0L
                is JsonValue.Str -> saved.value.toLongOrNull() ?: throw JsonException("invalid origami seed")
                else -> throw JsonException("origami seed must be decimal text")
            }
            val gesture = obj["gesture"]?.takeUnless { it is JsonValue.Null }?.let { saved ->
                val fields = saved.obj()
                val gestureModel = fields["model"]?.int() ?: throw JsonException("origami gesture has no model")
                if (gestureModel != OrigamiGestures.MODEL_VERSION) throw JsonException("unsupported origami gesture model $gestureModel")
                val name = fields["name"]?.str() ?: throw JsonException("origami gesture has no name")
                OrigamiGesture.entries.firstOrNull { it.name == name } ?: throw JsonException("unknown origami gesture $name")
            } ?: OrigamiGesture.STEADY
            return Patches.decode(value, ENGINE, { n -> OrigamiVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                OrigamiPatch(name, voice, macros, midi, velocity, seed, model, gesture)
            }
        }

        fun fromJsonText(text: String): OrigamiPatch = fromJsonValue(Json.parse(text)) as OrigamiPatch
    }
}
