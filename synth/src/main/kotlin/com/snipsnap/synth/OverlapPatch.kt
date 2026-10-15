package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/** A finite OVERLAP audition recipe; note and velocity are independent of its six macros. */
data class OverlapPatch(
    override val name: String,
    val topology: OverlapTopology,
    override val macros: Map<String, Float>,
    val midi: Int = Overlap.rootMidi(topology),
    val velocity: Float = 1f,
) : Patch {
    /** Shared engine introspection calls topology selection a voice. */
    val voice: OverlapTopology get() = topology

    init {
        Patches.validateMacros(this, Overlap.macrosFor(topology))
        require(midi in Overlap.MIN_MIDI..Overlap.MAX_MIDI) {
            "OVERLAP midi out of ${Overlap.MIN_MIDI}..${Overlap.MAX_MIDI}: $midi"
        }
        require(velocity.isFinite() && velocity in 0f..1f) { "OVERLAP velocity out of 0..1: $velocity" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = topology.name
    override fun render() = Overlap.render(topology, macros, midi, velocity)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = JsonValue.Num(velocity.toDouble())
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "OVERLAP"
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            return Patches.decode(value, ENGINE, { n -> OverlapTopology.entries.firstOrNull { it.name == n } }) { name, topology, macros ->
                val midi = obj["midi"]?.takeUnless { it is JsonValue.Null }?.int() ?: Overlap.rootMidi(topology)
                val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
                OverlapPatch(name, topology, macros, midi, velocity)
            }
        }
        fun fromJsonText(text: String): OverlapPatch = fromJsonValue(Json.parse(text)) as OverlapPatch
    }
}
