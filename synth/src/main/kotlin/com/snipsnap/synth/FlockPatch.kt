package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Playback is saved independently of HOLD, whose finite 2–6 second meaning never changes. */
enum class FlockPlayback { FINITE, HELD }

/** Note, energy, exact seed and playback travel with the six macros. */
data class FlockPatch(
    override val name: String,
    val voice: FlockVoice,
    override val macros: Map<String, Float>,
    val midi: Int = 60,
    val velocity: Float = 1f,
    val seed: Long = Flock.DEFAULT_SEED,
    val model: Int = Flock.MODEL_VERSION,
    val playback: FlockPlayback = FlockPlayback.FINITE,
    val releaseSeconds: Float = FlockHeld.DEFAULT_RELEASE_SECONDS,
    val heldVersion: Int = FlockHeld.VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Flock.macrosFor(voice))
        require(midi in Flock.MIN_MIDI..Flock.MAX_MIDI) { "FLOCK midi must be ${Flock.MIN_MIDI}..${Flock.MAX_MIDI}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "FLOCK velocity must be 0..1" }
        require(Flock.supportsModel(model)) { "unsupported flock model $model" }
        require(playback != FlockPlayback.HELD || voice == FlockVoice.RIBBON) { "FLOCK held playback requires RIBBON" }
        require(heldVersion == FlockHeld.VERSION) { "unsupported FLOCK held version $heldVersion" }
        require(releaseSeconds.isFinite() && releaseSeconds in FlockHeld.MIN_RELEASE_SECONDS..FlockHeld.MAX_RELEASE_SECONDS) {
            "FLOCK release must be ${FlockHeld.MIN_RELEASE_SECONDS}..${FlockHeld.MAX_RELEASE_SECONDS} seconds"
        }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = when (playback) {
        FlockPlayback.FINITE -> Flock.render(voice, macros, midi, velocity, seed, model = model)
        FlockPlayback.HELD -> FlockHeld.render(this).snip
    }
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        // JsonValue.Num is a Double. A decimal string preserves every possible Long exactly.
        obj["seed"] = JsonValue.Str(seed.toString())
        // Legacy finite recipes retain their exact JSON shape. A release edited while held
        // survives a later toggle back to finite, ready for the next held performance.
        if (playback != FlockPlayback.FINITE) obj["playback"] = JsonValue.Str(playback.name)
        if (playback == FlockPlayback.HELD) obj["heldVersion"] = JsonValue.Num(heldVersion.toDouble())
        if (playback == FlockPlayback.HELD || releaseSeconds != FlockHeld.DEFAULT_RELEASE_SECONDS)
            obj["releaseSeconds"] = JsonValue.Num(releaseSeconds.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "FLOCK"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            fun required(key: String): JsonValue = obj[key]?.takeUnless { it is JsonValue.Null }
                ?: throw JsonException("FLOCK patch has no $key")
            val model = required("model").int()
            if (!Flock.supportsModel(model)) throw JsonException("unsupported flock model $model")
            val midi = required("midi").int()
            val velocityDouble = required("velocity").num()
            if (!velocityDouble.isFinite() || velocityDouble !in 0.0..1.0) throw JsonException("FLOCK velocity must be 0..1")
            val seedText = required("seed").str()
            val seed = seedText.toLongOrNull() ?: throw JsonException("FLOCK seed must be a decimal 64-bit integer string")
            if (seed.toString() != seedText) throw JsonException("FLOCK seed must be a canonical decimal integer string")
            val playback = if ("playback" !in obj) FlockPlayback.FINITE else {
                val text = required("playback").str()
                FlockPlayback.entries.firstOrNull { it.name == text }
                    ?: throw JsonException("unsupported FLOCK playback $text")
            }
            val release = if ("releaseSeconds" !in obj) FlockHeld.DEFAULT_RELEASE_SECONDS else {
                val number = required("releaseSeconds").num()
                if (!number.isFinite() || number !in .1..1.5)
                    throw JsonException("FLOCK release must be ${FlockHeld.MIN_RELEASE_SECONDS}..${FlockHeld.MAX_RELEASE_SECONDS} seconds")
                number.toFloat()
            }
            val heldVersion = if (playback == FlockPlayback.HELD || "heldVersion" in obj) required("heldVersion").int()
                else FlockHeld.VERSION
            if (heldVersion != FlockHeld.VERSION) throw JsonException("unsupported FLOCK held version $heldVersion")
            // Check at JSON precision before the common decoder converts macro values to Float.
            // Values just outside a bound must not become valid merely by rounding to 0f or 1f.
            val rawMacros = obj["macros"]
            if (rawMacros is JsonValue.Obj) for ((key, raw) in rawMacros.entries) {
                val number = raw.num()
                if (!number.isFinite() || number !in 0.0..1.0) throw JsonException("FLOCK $key must be 0..1")
            }
            return Patches.decode(value, ENGINE, { n -> FlockVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                FlockPatch(name, voice, macros, midi, velocityDouble.toFloat(), seed, model, playback, release, heldVersion)
            }
        }

        fun fromJsonText(text: String): FlockPatch = fromJsonValue(Json.parse(text)) as FlockPatch
    }
}
