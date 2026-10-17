package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.Collections

/**
 * A reproducible finite Facet prototype recipe. The six controls keep pitch and
 * playing effort separate; geometry tables and resonator memories are rebuilt.
 *
 * MACRO creates every beam from the voice and controls and requires an empty
 * override list. OVERRIDE replaces the listed stable beam IDs after macro
 * generation; missing IDs continue to follow the macros. An override's four
 * controls are absolute normalized values, not additional macro offsets.
 */
class FacetPatch(
    override val name: String,
    val voice: FacetVoice,
    macros: Map<String, Float>,
    val midi: Int = 48,
    val velocity: Float = 1f,
    val seed: Int = 17,
    val initialAlignment: Float = 0f,
    beams: List<FacetBeam> = emptyList(),
    val beamMode: String = "MACRO",
    val modelVersion: Int = MODEL_VERSION,
) : Patch {
    // Snapshot collection inputs so later caller edits cannot change a saved recipe.
    override val macros: Map<String, Float> = Collections.unmodifiableMap(LinkedHashMap(macros))
    val beams: List<FacetBeam> = Collections.unmodifiableList(ArrayList(beams))

    init {
        Patches.validateMacros(this, Facet.macrosFor(voice))
        require(midi in 24..84) { "FACET MIDI must be 24..84" }
        require(velocity.isFinite() && velocity in 0f..1f) { "FACET velocity must be 0..1" }
        require(initialAlignment.isFinite() && initialAlignment in 0f..1f) {
            "FACET initialAlignment must be 0..1 turns"
        }
        require(modelVersion == MODEL_VERSION) { "unsupported FACET model $modelVersion" }
        require(beamMode == "MACRO" || beamMode == "OVERRIDE") { "FACET beamMode must be MACRO or OVERRIDE" }
        require(beamMode != "MACRO" || this.beams.isEmpty()) { "FACET MACRO mode cannot discard beam overrides; select OVERRIDE" }
        require(this.beams.map { it.id }.distinct().size == this.beams.size) { "FACET beam IDs must be unique" }
        for (beam in this.beams) {
            require(beam.id in 0..11) { "FACET beam ID must be 0..11: ${beam.id}" }
            for ((field, value) in listOf("intensity" to beam.intensity, "elevation" to beam.elevation,
                "color" to beam.color, "width" to beam.width)) {
                require(value.isFinite() && value in 0f..1f) { "FACET beam ${beam.id} $field must be 0..1" }
            }
        }
        require((this.macros["HOLD"] ?: Facet.defaults(voice).getValue("HOLD")) < .99f) {
            "FACET finite prototype only: HOLD loops are not implemented"
        }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Facet.render(voice, macros, midi = midi, velocity = velocity, seed = seed,
        initialAlignment = initialAlignment, beams = beams, beamMode = beamMode)
    override fun withMacros(macros: Map<String, Float>): FacetPatch = copy(macros = macros)

    fun copy(
        name: String = this.name,
        voice: FacetVoice = this.voice,
        macros: Map<String, Float> = this.macros,
        midi: Int = this.midi,
        velocity: Float = this.velocity,
        seed: Int = this.seed,
        initialAlignment: Float = this.initialAlignment,
        beams: List<FacetBeam> = this.beams,
        beamMode: String = this.beamMode,
        modelVersion: Int = this.modelVersion,
    ): FacetPatch = FacetPatch(name, voice, macros, midi, velocity, seed, initialAlignment, beams, beamMode, modelVersion)

    override fun equals(other: Any?): Boolean = other is FacetPatch && name == other.name && voice == other.voice &&
        macros == other.macros && midi == other.midi && velocity.compareTo(other.velocity) == 0 && seed == other.seed &&
        initialAlignment.compareTo(other.initialAlignment) == 0 && beams == other.beams && beamMode == other.beamMode &&
        modelVersion == other.modelVersion

    override fun hashCode(): Int {
        var result = name.hashCode()
        for (value in listOf(voice, macros, midi, velocity, seed, initialAlignment, beams, beamMode, modelVersion)) {
            result = 31 * result + value.hashCode()
        }
        return result
    }

    override fun toString(): String = "FacetPatch(name=$name, voice=$voice, macros=$macros, midi=$midi, " +
        "velocity=$velocity, seed=$seed, initialAlignment=$initialAlignment, beams=$beams, beamMode=$beamMode, modelVersion=$modelVersion)"

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).also { obj ->
            obj["midi"] = number(midi)
            obj["velocity"] = number(velocity)
            obj["seed"] = number(seed)
            obj["initialAlignment"] = number(initialAlignment)
            obj["beamMode"] = JsonValue.Str(beamMode)
            obj["modelVersion"] = number(modelVersion)
            obj["beams"] = JsonValue.Arr(beams.map { beam ->
                JsonValue.Obj(linkedMapOf("id" to number(beam.id), "intensity" to number(beam.intensity),
                    "elevation" to number(beam.elevation), "color" to number(beam.color), "width" to number(beam.width)))
            })
        },
    )

    companion object {
        const val ENGINE = "FACET"
        const val VERSION = Patches.VERSION
        const val MODEL_VERSION = 1

        private fun number(value: Number) = JsonValue.Num(value.toDouble())

        fun fromJsonValue(value: JsonValue): FacetPatch {
            val obj = value.obj()
            return Patches.decode(value, ENGINE, { name -> FacetVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                try {
                    val beams = obj["beams"]?.arr()?.map { raw ->
                        val beam = raw.obj()
                        FacetBeam(
                            id = beam["id"]?.int() ?: throw JsonException("FACET beam needs an id"),
                            intensity = beam["intensity"]?.num()?.toFloat() ?: 1f,
                            elevation = beam["elevation"]?.num()?.toFloat() ?: .5f,
                            color = beam["color"]?.num()?.toFloat() ?: .5f,
                            width = beam["width"]?.num()?.toFloat() ?: .5f,
                        )
                    } ?: emptyList()
                    FacetPatch(name, voice, macros,
                        midi = obj["midi"]?.int() ?: 48,
                        velocity = obj["velocity"]?.num()?.toFloat() ?: 1f,
                        seed = obj["seed"]?.int() ?: 17,
                        initialAlignment = obj["initialAlignment"]?.num()?.toFloat() ?: 0f,
                        beams = beams,
                        beamMode = obj["beamMode"]?.str() ?: "MACRO",
                        modelVersion = obj["modelVersion"]?.int() ?: MODEL_VERSION,
                    )
                } catch (e: IllegalArgumentException) {
                    throw JsonException(e.message ?: "invalid FACET patch")
                }
            } as FacetPatch
        }

        fun fromJsonText(text: String): FacetPatch = fromJsonValue(Json.parse(text))
    }
}
