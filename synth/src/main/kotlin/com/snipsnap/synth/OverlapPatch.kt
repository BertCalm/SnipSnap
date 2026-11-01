package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Playback is saved separately so every accepted finite HOLD recipe keeps its meaning. */
enum class OverlapPlayback { FINITE, HELD }

/** Saved sound and playback; note and velocity are independent of the six macros. */
data class OverlapPatch(
    override val name: String,
    val topology: OverlapTopology,
    override val macros: Map<String, Float>,
    val midi: Int = Overlap.rootMidi(topology),
    val velocity: Float = 1f,
    val model: Int = Overlap.MODEL_VERSION,
    val playback: OverlapPlayback = OverlapPlayback.FINITE,
    val releaseSeconds: Float = OverlapHeld.DEFAULT_RELEASE_SECONDS,
    val heldVersion: Int = OverlapHeld.VERSION,
) : Patch {
    /** Shared engine introspection calls topology selection a voice. */
    val voice: OverlapTopology get() = topology

    init {
        Patches.validateMacros(this, Overlap.macrosFor(topology, model))
        require(model in 1..Overlap.MODEL_VERSION) { "unsupported OVERLAP model $model" }
        require(midi in Overlap.MIN_MIDI..Overlap.MAX_MIDI) {
            "OVERLAP midi out of ${Overlap.MIN_MIDI}..${Overlap.MAX_MIDI}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "OVERLAP velocity out of 0..1: $velocity" }
        require(heldVersion == OverlapHeld.VERSION) { "unsupported OVERLAP held version $heldVersion" }
        require(releaseSeconds.isFinite() && releaseSeconds in OverlapHeld.MIN_RELEASE_SECONDS..OverlapHeld.MAX_RELEASE_SECONDS) {
            "OVERLAP release must be ${OverlapHeld.MIN_RELEASE_SECONDS}..${OverlapHeld.MAX_RELEASE_SECONDS} seconds"
        }
        if (playback == OverlapPlayback.HELD) {
            require(model == 2) { "OVERLAP held playback requires model 2" }
            val m = OverlapHeld.defaults(topology) + macros
            require(m.getValue("HOLD") == 1f && listOf("PULSE", "GLOW", "LOCK").all { m.getValue(it) == 0f }) {
                "OVERLAP steady held playback requires HOLD=1 and PULSE=GLOW=LOCK=0"
            }
        }
    }

    override val engine get() = ENGINE
    override val voiceName get() = topology.name
    override fun render() = when (playback) {
        OverlapPlayback.FINITE -> Overlap.render(topology, macros, midi, velocity, model = model)
        OverlapPlayback.HELD -> OverlapHeld.render(this).snip
    }
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        obj["model"] = JsonValue.Num(model.toDouble())
        // Omit defaults on finite recipes to retain their historical JSON bytes.
        if (playback == OverlapPlayback.HELD) {
            obj["playback"] = JsonValue.Str(playback.name)
            obj["heldVersion"] = JsonValue.Num(heldVersion.toDouble())
        }
        if (playback == OverlapPlayback.HELD || releaseSeconds != OverlapHeld.DEFAULT_RELEASE_SECONDS)
            obj["releaseSeconds"] = JsonValue.Num(releaseSeconds.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "OVERLAP"
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            fun required(key: String): JsonValue = obj[key]?.takeUnless { it is JsonValue.Null }
                ?: throw JsonException("OVERLAP patch has no $key")
            val playback = if ("playback" !in obj) OverlapPlayback.FINITE else {
                val text = required("playback").str()
                OverlapPlayback.entries.firstOrNull { it.name == text }
                    ?: throw JsonException("unsupported OVERLAP playback $text")
            }
            val release = if ("releaseSeconds" !in obj) OverlapHeld.DEFAULT_RELEASE_SECONDS else {
                val number = required("releaseSeconds").num()
                if (!number.isFinite() || number !in .1..1.5) throw JsonException("OVERLAP release must be .1..1.5 seconds")
                number.toFloat()
            }
            val heldVersion = if (playback == OverlapPlayback.HELD || "heldVersion" in obj) required("heldVersion").int()
                else OverlapHeld.VERSION
            if (heldVersion != OverlapHeld.VERSION) throw JsonException("unsupported OVERLAP held version $heldVersion")
            // Check the fixed steady controls before Float conversion can round a
            // small nonzero value to zero or a near-one value to one.
            if (playback == OverlapPlayback.HELD) {
                obj["velocity"]?.takeUnless { it is JsonValue.Null }?.let {
                    val number = it.num()
                    if (!number.isFinite() || number !in 0.0..1.0) throw JsonException("OVERLAP held velocity must be 0..1")
                }
                val raw = obj["macros"]
                if (raw is JsonValue.Obj) for ((key, value) in raw.entries) {
                    val number = value.num()
                    if (!number.isFinite() || number !in 0.0..1.0) throw JsonException("OVERLAP held $key must be 0..1")
                    if (key in listOf("PULSE", "GLOW", "LOCK", "HOLD")) {
                        val expected = if (key == "HOLD") 1.0 else 0.0
                        if (number != expected) throw JsonException("OVERLAP steady held $key must be $expected")
                    }
                }
            }
            return Patches.decode(value, ENGINE, { n -> OverlapTopology.entries.firstOrNull { it.name == n } }) { name, topology, macros ->
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Overlap.rootMidi(topology)
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: 1
                OverlapPatch(name, topology, macros, midi, velocity, model, playback, release, heldVersion)
            }
        }
        fun fromJsonText(text: String): OverlapPatch = fromJsonValue(Json.parse(text)) as OverlapPatch
    }
}
