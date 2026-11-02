package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A replayable Libration performance, with independently versioned finite and held readouts. */
data class LibrationPatch(
    override val name: String,
    val voice: LibrationVoice,
    override val macros: Map<String, Float> = Libration.defaults(voice),
    val midi: Int = 60,
    val velocity: Float = .8f,
    val model: Int = Libration.MODEL_VERSION,
    val sourceSeconds: Double = 6.0,
    val heldModel: Int = 0,
) : Patch {
    init {
        Patches.validateMacros(this, Libration.macrosFor(voice))
        Libration.settled(voice, macros + ("HOLD" to 0f))
        val hold = macros["HOLD"] ?: 0f
        require(hold == 0f || hold == 1f) { "LIBRATION HOLD must be 0 or 1" }
        require(heldModel == 0 || heldModel == LibrationKeys.MODEL_VERSION) { "unsupported LIBRATION held model $heldModel" }
        require(hold == 0f || heldModel == LibrationKeys.MODEL_VERSION) { "LIBRATION HOLD needs an explicit held model" }
        require(heldModel == 0 || LibrationKeys.supports(this)) { "LIBRATION HOLD requires source model 3" }
        require(midi in Libration.MIN_MIDI..Libration.MAX_MIDI)
        require(velocity.isFinite() && velocity in 0f..1f)
        require(model in 1..Libration.MODEL_VERSION)
        require(sourceSeconds.isFinite() && sourceSeconds in Libration.MIN_SOURCE_SECONDS..Libration.MAX_SOURCE_SECONDS)
        require(model >= 3 || sourceSeconds == 6.0) { "saved models 1/2 use their original six-second note" }
    }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = if (LibrationKeys.isHeld(macros["HOLD"] ?: 0f)) LibrationKeys.loop(this)
        else Libration.render(voice, macros, midi, velocity, model, sourceSeconds)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)
    override fun toJsonValue() = JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).apply {
        put("midi", JsonValue.Num(midi.toDouble()))
        put("velocity", JsonValue.Num(velocity.toDouble()))
        put("model", JsonValue.Num(model.toDouble()))
        if (model >= 3) put("sourceSeconds", JsonValue.Num(sourceSeconds))
        if (heldModel != 0) put("heldModel", JsonValue.Num(heldModel.toDouble()))
    })
    companion object {
        const val ENGINE = "LIBRATION"
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.int() ?: throw JsonException("LIBRATION model is required")
            (obj["macros"] as? JsonValue.Obj)?.entries?.get("HOLD")?.let {
                val hold = it.num()
                if (hold != 0.0 && hold != 1.0) throw JsonException("LIBRATION HOLD must be exactly 0 or 1")
            }
            return Patches.decode(value, ENGINE, { n -> LibrationVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                LibrationPatch(name, voice, macros, obj["midi"]?.int() ?: 60,
                    obj["velocity"]?.num()?.toFloat() ?: .8f, model, obj["sourceSeconds"]?.num() ?: 6.0,
                    obj["heldModel"]?.int() ?: 0)
            }
        }
        fun fromJsonText(text: String) = fromJsonValue(Json.parse(text)) as LibrationPatch
    }
}
