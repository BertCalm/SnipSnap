package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Playback is explicit so older finite HOLD recipes retain their original meaning. */
enum class ObbligatoPlayback { FINITE, HELD }

/** The complete duet recipe, including renderer, articulation and sampler release. */
data class ObbligatoPatch(
    override val name: String,
    override val macros: Map<String, Float>,
    val midi: Int = Obbligato.DEFAULT_MIDI,
    val velocity: Float = Obbligato.DEFAULT_VELOCITY,
    val seed: Int = 0,
    val model: Int = Obbligato.MODEL,
    val playback: ObbligatoPlayback = ObbligatoPlayback.FINITE,
    val releaseSeconds: Float = DEFAULT_RELEASE_SECONDS,
    val heldVersion: Int = ObbligatoHeld.VERSION,
) : Patch {
    init {
        require(name.isNotBlank()) { "OBBLIGATO patch name must not be blank" }
        Obbligato.settled(macros)
        require(model in 1..Obbligato.MAX_MODEL) { "unsupported OBBLIGATO model: $model" }
        Obbligato.validateNote(midi, velocity, model)
        require(releaseSeconds.isFinite() && releaseSeconds in MIN_RELEASE_SECONDS..MAX_RELEASE_SECONDS) {
            "OBBLIGATO release must be $MIN_RELEASE_SECONDS..$MAX_RELEASE_SECONDS seconds"
        }
        require(heldVersion == ObbligatoHeld.VERSION) { "unsupported OBBLIGATO held version: $heldVersion" }
        if (playback == ObbligatoPlayback.HELD) {
            require(model == Obbligato.PERFORMANCE_MODEL) { "OBBLIGATO held playback requires the expanded renderer" }
            require(Obbligato.settled(macros).getValue("HOLD") == 1f) { "OBBLIGATO held playback requires HOLD=1" }
        }
    }

    override val engine get() = ENGINE
    override val voiceName get() = VOICE
    override fun render() = when (playback) {
        ObbligatoPlayback.FINITE -> Obbligato.render(macros, midi, velocity, seed, model)
        ObbligatoPlayback.HELD -> ObbligatoHeld.render(this).snip
    }
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(
        LinkedHashMap(Patches.toJsonValue(this).entries).apply {
            this["midi"] = JsonValue.Num(midi.toDouble())
            this["velocity"] = JsonValue.Num(velocity.toDouble())
            this["seed"] = JsonValue.Num(seed.toDouble())
            this["model"] = JsonValue.Num(model.toDouble())
            // Keep historical finite JSON byte-for-byte when the extra fields are defaults.
            if (playback == ObbligatoPlayback.HELD) {
                this["playback"] = JsonValue.Str(playback.name)
                this["heldVersion"] = JsonValue.Num(heldVersion.toDouble())
            }
            if (playback == ObbligatoPlayback.HELD || releaseSeconds != DEFAULT_RELEASE_SECONDS)
                this["releaseSeconds"] = JsonValue.Num(releaseSeconds.toDouble())
        },
    )

    companion object {
        const val ENGINE = "OBBLIGATO"
        const val VOICE = "AIR_REED"
        const val DEFAULT_RELEASE_SECONDS = .6f
        const val MIN_RELEASE_SECONDS = .1f
        const val MAX_RELEASE_SECONDS = 1.5f

        fun fromJsonValue(value: JsonValue): ObbligatoPatch {
            val obj = value.obj()
            val model = obj["model"]?.int() ?: Obbligato.LEGACY_MODEL
            if (model !in 1..Obbligato.MAX_MODEL) throw JsonException("unsupported OBBLIGATO model: $model")
            val midi = obj["midi"]?.int() ?: Obbligato.DEFAULT_MIDI
            val velocity = obj["velocity"]?.num()?.toFloat() ?: Obbligato.DEFAULT_VELOCITY
            val seed = obj["seed"]?.int() ?: 0
            fun present(key: String): JsonValue = obj[key]?.takeUnless { it is JsonValue.Null }
                ?: throw JsonException("OBBLIGATO patch has no $key")
            val playback = if ("playback" !in obj) ObbligatoPlayback.FINITE else {
                val text = present("playback").str()
                ObbligatoPlayback.entries.firstOrNull { it.name == text }
                    ?: throw JsonException("unsupported OBBLIGATO playback: $text")
            }
            val release = if ("releaseSeconds" !in obj) DEFAULT_RELEASE_SECONDS else {
                val number = present("releaseSeconds").num()
                if (!number.isFinite() || number !in .1..1.5) throw JsonException("OBBLIGATO release must be .1..1.5 seconds")
                number.toFloat()
            }
            val heldVersion = if (playback == ObbligatoPlayback.HELD || "heldVersion" in obj)
                present("heldVersion").int() else ObbligatoHeld.VERSION
            if (heldVersion != ObbligatoHeld.VERSION) throw JsonException("unsupported OBBLIGATO held version: $heldVersion")
            if (playback == ObbligatoPlayback.HELD) {
                val controls = obj["macros"]?.obj() ?: throw JsonException("OBBLIGATO held patch has no macros")
                if (controls["HOLD"]?.num() != 1.0) throw JsonException("OBBLIGATO held playback requires HOLD=1")
            }
            return Patches.decode(value, ENGINE, { it.takeIf { n -> n == VOICE } }) { name, _, macros ->
                ObbligatoPatch(name, macros, midi, velocity, seed, model, playback, release, heldVersion)
            } as ObbligatoPatch
        }

        fun fromJsonText(text: String): ObbligatoPatch = fromJsonValue(Json.parse(text))
    }
}
