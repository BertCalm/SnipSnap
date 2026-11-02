package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A replayable finite ensemble. Mechanics, acoustic profile and the selected
 * passage are saved independently, so changing touch or the root cannot choose
 * another gathering history. The fixed reference initializer keeps its original
 * seed; arbitrary saved seeds explicitly select the project initializer.
 */
data class AccordPatch(
    override val name: String,
    val voice: AccordVoice = AccordVoice.BRUSH,
    override val macros: Map<String, Float> = emptyMap(),
    val midi: Int = 48,
    val velocity: Float = .75f,
    val seed: Long = Accord.DEFAULT_SEED,
    val model: Int = Accord.MODEL_VERSION,
    val acousticProfile: AccordAcousticProfile = Accord.defaultProfile(voice),
    val initializer: AccordInitializer = AccordInitializer.REFERENCE_SINE_20261022,
    val sourceStartCycles: Double = 8.0,
    val sourceSeconds: Double = 8.0,
) : Patch {
    init {
        Patches.validateMacros(this, Accord.macrosFor(voice))
        require((macros["HOLD"] ?: 0f) == 0f) { "ACCORD is finite; HOLD must be zero" }
        require(midi in Accord.MIN_MIDI..Accord.MAX_MIDI) {
            "ACCORD midi must be ${Accord.MIN_MIDI}..${Accord.MAX_MIDI}"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "ACCORD velocity must be 0..1" }
        require(Accord.supportsModel(model)) { "unsupported ACCORD model $model" }
        require(if (voice == AccordVoice.TINE) acousticProfile != AccordAcousticProfile.BASE
            else acousticProfile == AccordAcousticProfile.BASE) {
            "ACCORD $voice does not support acoustic profile $acousticProfile"
        }
        require(initializer != AccordInitializer.REFERENCE_SINE_20261022 || seed == Accord.DEFAULT_SEED) {
            "ACCORD reference initializer requires seed ${Accord.DEFAULT_SEED}"
        }
        require(sourceStartCycles.isFinite() && sourceStartCycles in 0.0..8.0) {
            "ACCORD sourceStartCycles must be 0..8"
        }
        require(sourceSeconds.isFinite() && sourceSeconds in Accord.MIN_SOURCE_SECONDS..Accord.MAX_SOURCE_SECONDS) {
            "ACCORD sourceSeconds must be ${Accord.MIN_SOURCE_SECONDS}..${Accord.MAX_SOURCE_SECONDS}"
        }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Accord.render(voice, macros, midi, velocity, seed, model,
        acousticProfile, initializer, sourceStartCycles, sourceSeconds)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).apply {
            put("midi", JsonValue.Num(midi.toDouble()))
            put("velocity", JsonValue.Num(velocity.toDouble()))
            // JSON numbers are Doubles and cannot preserve every signed Long.
            put("seed", JsonValue.Str(seed.toString()))
            put("model", JsonValue.Num(model.toDouble()))
            put("acousticProfile", JsonValue.Str(acousticProfile.name))
            put("initializer", JsonValue.Str(initializer.name))
            put("sourceStartCycles", JsonValue.Num(sourceStartCycles))
            put("sourceSeconds", JsonValue.Num(sourceSeconds))
        },
    )

    companion object {
        const val ENGINE = "ACCORD"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): AccordPatch = try {
            val obj = value.obj()
            fun required(key: String): JsonValue = obj[key]?.takeUnless { it is JsonValue.Null }
                ?: throw JsonException("ACCORD patch has no $key")
            val voiceName = required("voice").str()
            val voice = AccordVoice.entries.firstOrNull { it.name == voiceName }
                ?: throw JsonException("unknown ACCORD voice $voiceName")
            val model = required("model").int()
            if (!Accord.supportsModel(model)) throw JsonException("unsupported ACCORD model $model")
            val midi = required("midi").int()
            val velocity = required("velocity").num()
            if (!velocity.isFinite() || velocity !in 0.0..1.0) throw JsonException("ACCORD velocity must be 0..1")
            val seedText = required("seed").str()
            val seed = seedText.toLongOrNull()
                ?: throw JsonException("ACCORD seed must be a decimal 64-bit integer string")
            if (seed.toString() != seedText) throw JsonException("ACCORD seed must be a canonical decimal integer string")
            val profileName = required("acousticProfile").str()
            val profile = AccordAcousticProfile.entries.firstOrNull { it.name == profileName }
                ?: throw JsonException("unknown ACCORD acoustic profile $profileName")
            val initializerName = required("initializer").str()
            val initializer = AccordInitializer.entries.firstOrNull { it.name == initializerName }
                ?: throw JsonException("unknown ACCORD initializer $initializerName")
            val sourceStartCycles = required("sourceStartCycles").num()
            val sourceSeconds = required("sourceSeconds").num()

            // The common decoder converts controls to Float. Check the stored
            // precision first: a tiny positive HOLD must not become zero, and
            // a number just outside a bound must not round onto that bound.
            val knownMacros = Accord.macrosFor(voice).map { it.name }.toSet()
            val rawMacros = obj["macros"]
            if (rawMacros is JsonValue.Obj) for ((key, raw) in rawMacros.entries) {
                if (key !in knownMacros) throw JsonException("unknown ACCORD macro $key")
                val number = raw.num()
                if (!number.isFinite() || number !in 0.0..1.0) throw JsonException("ACCORD $key must be 0..1")
                if (key == "HOLD" && number != 0.0) throw JsonException("ACCORD is finite; HOLD must be zero")
            }
            Patches.decode(value, ENGINE, { name -> AccordVoice.entries.firstOrNull { it.name == name } }) { name, decodedVoice, macros ->
                AccordPatch(name, decodedVoice, macros, midi, velocity.toFloat(), seed, model,
                    profile, initializer, sourceStartCycles, sourceSeconds)
            } as AccordPatch
        } catch (e: IllegalArgumentException) {
            throw JsonException(e.message ?: "invalid ACCORD patch")
        }

        fun fromJsonText(text: String): AccordPatch = fromJsonValue(Json.parse(text))
    }
}
