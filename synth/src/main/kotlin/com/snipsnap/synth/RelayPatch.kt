package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A complete Relay recipe. Sources, handoff schedules, accents and the phrase clock travel
 * with the sound so a later voice-default change cannot silently change a saved phrase.
 * Velocity is excitation energy, separate from the timbral macros. TUNE selects the root
 * within the voice's two-octave range; sequence slots never select another note.
 */
data class RelayPatch(
    override val name: String,
    val voice: RelayVoice,
    override val macros: Map<String, Float>,
    val phrase: RelayPhrase = Relay.phraseFor(voice),
    val velocity: Float = 1f,
    val model: Int = MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, Relay.macrosFor(voice))
        require(velocity.isFinite() && velocity in 0f..1f) { "RELAY velocity out of 0..1: $velocity" }
        require(model == MODEL_VERSION) { "unsupported relay model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Relay.render(voice, macros, velocity = velocity, phrase = phrase)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = number(model)
        obj["velocity"] = number(velocity)
        obj["phrase"] = JsonValue.Obj(linkedMapOf(
            "sources" to JsonValue.Arr(phrase.sources.map { source ->
                JsonValue.Obj(linkedMapOf(
                    "role" to JsonValue.Str(source.role.name),
                    "gain" to number(source.gain),
                    "brightness" to number(source.brightness),
                    "decaySeconds" to number(source.decaySeconds),
                ))
            }),
            "slots" to JsonValue.Arr(phrase.slots.map { slot ->
                JsonValue.Obj(linkedMapOf(
                    "source" to number(slot.source),
                    "beats" to number(slot.beats),
                    "transitionBeats" to number(slot.transitionBeats),
                ))
            }),
            "orderRoster" to JsonValue.Arr(phrase.orderRoster.map { order ->
                JsonValue.Arr(order.map(::number))
            }),
            "accents" to JsonValue.Arr(phrase.accents.map { accent ->
                JsonValue.Obj(linkedMapOf(
                    "beat" to number(accent.beat),
                    "strength" to number(accent.strength),
                ))
            }),
            "tempoBpm" to number(phrase.tempoBpm),
            "loop" to JsonValue.Obj(linkedMapOf(
                "prerollCycles" to number(phrase.loop.prerollCycles),
                "wrapFrames" to number(phrase.loop.wrapFrames),
            )),
        ))
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "RELAY"
        const val VERSION = Patches.VERSION
        const val MODEL_VERSION = 1

        private fun number(value: Number) = JsonValue.Num(value.toDouble())

        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val model = obj["model"]?.takeUnless { it is JsonValue.Null }?.int() ?: MODEL_VERSION
            if (model != MODEL_VERSION) throw JsonException("unsupported relay model $model")
            val velocity = obj["velocity"]?.takeUnless { it is JsonValue.Null }?.num()?.toFloat() ?: 1f
            val rawPhrase = obj["phrase"]?.takeUnless { it is JsonValue.Null }
            return Patches.decode(value, ENGINE, { n -> RelayVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                checked {
                    RelayPatch(name, voice, macros, rawPhrase?.let(::readPhrase) ?: Relay.phraseFor(voice), velocity, model)
                }
            }
        }

        fun fromJsonText(text: String): RelayPatch = fromJsonValue(Json.parse(text)) as RelayPatch

        private fun readPhrase(value: JsonValue): RelayPhrase = checked {
            val obj = value.obj()
            val sources = required(obj, "sources").arr().map { value ->
                val source = value.obj()
                val roleName = required(source, "role").str()
                val role = RelayRole.entries.firstOrNull { it.name == roleName }
                    ?: throw JsonException("unknown relay source role $roleName")
                RelaySource(
                    role,
                    required(source, "gain").num().toFloat(),
                    required(source, "brightness").num().toFloat(),
                    required(source, "decaySeconds").num().toFloat(),
                )
            }
            val slots = required(obj, "slots").arr().map { value ->
                val slot = value.obj()
                RelaySlot(
                    required(slot, "source").int(),
                    required(slot, "beats").num().toFloat(),
                    required(slot, "transitionBeats").num().toFloat(),
                )
            }
            val orderRoster = required(obj, "orderRoster").arr().map { order -> order.arr().map { it.int() } }
            val accents = required(obj, "accents").arr().map { value ->
                val accent = value.obj()
                RelayAccent(required(accent, "beat").num().toFloat(), required(accent, "strength").num().toFloat())
            }
            val loop = required(obj, "loop").obj()
            RelayPhrase(
                sources, slots, orderRoster, accents,
                tempoBpm = required(obj, "tempoBpm").num().toFloat(),
                loop = RelayLoop(required(loop, "prerollCycles").int(), required(loop, "wrapFrames").int()),
            )
        }

        private fun required(obj: Map<String, JsonValue>, key: String): JsonValue =
            obj[key] ?: throw JsonException("relay phrase has no $key")

        /** Invalid saved configuration reports a file-format error, never a partially usable patch. */
        private inline fun <T> checked(build: () -> T): T = try {
            build()
        } catch (e: IllegalArgumentException) {
            throw JsonException(e.message ?: "invalid relay patch")
        }
    }
}
