package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.Collections

/** A complete finite performance. Editing controls retains score, source details, root and seed. */
class OtisPatch(
    override val name: String,
    val voice: OtisVoice,
    macros: Map<String, Float> = emptyMap(),
    val midi: Int = Otis.defaultMidi(voice),
    val velocity: Float = 1f,
    profile: OtisProfile = OtisProfile(),
    val seed: Int = Otis.DEFAULT_SEED,
    val model: Int = Otis.MODEL_VERSION,
) : Patch {
    val profile: OtisProfile = profile.snapshot()
    override val macros: Map<String, Float> = Collections.unmodifiableMap(LinkedHashMap(Otis.settled(voice, macros, this.profile)))
    init {
        val known = Otis.macrosFor(voice).map { it.name }.toSet()
        for ((key, value) in macros) require(key in known && value.isFinite() && value in 0f..1f) { "invalid OTIS control $key=$value" }
        Patches.validateMacros(this, Otis.macrosFor(voice))
        require(midi in Otis.MIDI_MIN..Otis.MIDI_MAX) { "OTIS anchor MIDI must be ${Otis.MIDI_MIN}..${Otis.MIDI_MAX}" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OTIS velocity must be finite and normalized" }
        require(model == Otis.MODEL_VERSION) { "unsupported Otis model $model" }
    }
    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Otis.render(this)
    override fun withMacros(macros: Map<String, Float>): OtisPatch = copy(macros = macros)
    fun copy(name: String = this.name, voice: OtisVoice = this.voice, macros: Map<String, Float> = this.macros,
        midi: Int = this.midi, velocity: Float = this.velocity, profile: OtisProfile = this.profile,
        seed: Int = this.seed, model: Int = this.model): OtisPatch = OtisPatch(name, voice, macros, midi, velocity, profile, seed, model)

    override fun equals(other: Any?): Boolean = other is OtisPatch && name == other.name && voice == other.voice &&
        macros == other.macros && midi == other.midi && velocity == other.velocity && profile == other.profile && seed == other.seed && model == other.model
    override fun hashCode(): Int = listOf(name, voice, macros, midi, velocity, profile, seed, model).hashCode()
    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).also {
        it["model"] = num(model); it["midi"] = num(midi); it["velocity"] = num(velocity); it["seed"] = num(seed)
        it["profile"] = profileJson(profile)
    })

    companion object {
        const val ENGINE = "OTIS"
        const val VERSION = Patches.VERSION
        fun fromJsonText(text: String): OtisPatch = fromJsonValue(Json.parse(text))
        fun fromJsonValue(value: JsonValue): OtisPatch = checked {
            val root = fields(value, "patch", setOf("engine", "version", "name", "voice", "macros", "model", "midi", "velocity", "seed", "profile"))
            Patches.decode(value, ENGINE, { n -> OtisVoice.entries.firstOrNull { it.name == n } }) { name, voice, _ ->
                // Validate JSON numbers before Float conversion can round a small range violation away.
                val controls = root.getValue("macros").obj().mapValues { (key, v) -> unit(v, key) }
                OtisPatch(name, voice, controls, root.getValue("midi").int(), unit(root.getValue("velocity"), "velocity"),
                    profile(root.getValue("profile")), root.getValue("seed").int(), root.getValue("model").int())
            } as OtisPatch
        }
        private fun num(n: Number) = JsonValue.Num(n.toDouble())
        private fun obj(vararg entries: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*entries))
        private fun str(s: String) = JsonValue.Str(s)
        private fun unit(value: JsonValue, name: String): Float {
            val x = value.num()
            if (!x.isFinite() || x !in 0.0..1.0) throw JsonException("OTIS $name must be finite and normalized")
            return x.toFloat()
        }
        private fun number(value: JsonValue, name: String): Double = value.num().also {
            if (!it.isFinite()) throw JsonException("OTIS $name must be finite")
        }
        private fun fields(value: JsonValue, name: String, keys: Set<String>): Map<String, JsonValue> {
            val fields = value.obj()
            if (fields.keys != keys) throw JsonException("OTIS $name fields mismatch: missing ${keys - fields.keys}; unknown ${fields.keys - keys}")
            return fields
        }
        private inline fun <reified E : Enum<E>> enum(value: JsonValue): E = enumValues<E>().firstOrNull { it.name == value.str() }
            ?: throw JsonException("unknown OTIS value ${value.str()}")
        private inline fun <T> checked(block: () -> T): T = try { block() }
            catch (e: IllegalArgumentException) { throw JsonException(e.message ?: "invalid OTIS recipe") }

        private fun profileJson(p: OtisProfile): JsonValue.Obj = obj(
            "events" to JsonValue.Arr(p.events.map { e -> obj("semitones" to num(e.semitones), "startSeconds" to num(e.startSeconds),
                "gateSeconds" to num(e.gateSeconds), "velocity" to num(e.velocity)) }),
            "sharedPressure" to num(p.sharedPressure),
            "air" to JsonValue.Obj(p.air.asMap().mapValues { num(it.value) }),
            "organ" to p.organ.let { s -> obj(
                "drawbars" to JsonValue.Arr(s.drawbars.map { num(it) }), "click" to num(s.click), "percussionLevel" to num(s.percussionLevel),
                "percussionHarmonic" to str(s.percussionHarmonic.name), "percussionDecaySeconds" to num(s.percussionDecaySeconds),
                "leakage" to num(s.leakage), "drive" to num(s.drive), "attackSeconds" to num(s.attackSeconds),
                "releaseSeconds" to num(s.releaseSeconds), "cabinetHz" to num(s.cabinetHz), "rotary" to JsonValue.Bool(s.rotary),
                "rotaryStart" to str(s.rotaryStart.name), "rotaryTarget" to str(s.rotaryTarget.name),
                "rotaryChangeSeconds" to num(s.rotaryChangeSeconds), "rotaryDepth" to num(s.rotaryDepth)) })

        private fun profile(value: JsonValue): OtisProfile {
            val p = fields(value, "profile", setOf("events", "sharedPressure", "air", "organ"))
            val a = fields(p.getValue("air"), "air", setOf("steamInstability", "harpBend", "breath", "harpVibrato", "musette", "bellowsPulse", "pressure", "release"))
            fun av(name: String) = unit(a.getValue(name), name)
            val o = fields(p.getValue("organ"), "organ", setOf("drawbars", "click", "percussionLevel", "percussionHarmonic", "percussionDecaySeconds",
                "leakage", "drive", "attackSeconds", "releaseSeconds", "cabinetHz", "rotary", "rotaryStart", "rotaryTarget", "rotaryChangeSeconds", "rotaryDepth"))
            fun ov(name: String) = unit(o.getValue(name), name)
            fun on(name: String) = number(o.getValue(name), name)
            return OtisProfile(
                p.getValue("events").arr().map { raw ->
                    val e = fields(raw, "event", setOf("semitones", "startSeconds", "gateSeconds", "velocity"))
                    OtisEvent(e.getValue("semitones").int(), number(e.getValue("startSeconds"), "startSeconds"),
                        number(e.getValue("gateSeconds"), "gateSeconds"), unit(e.getValue("velocity"), "event velocity"))
                },
                OtisAirProfile(av("steamInstability"), av("harpBend"), av("breath"), av("harpVibrato"), av("musette"), av("bellowsPulse"), av("pressure"), av("release")),
                OtisOrganProfile(o.getValue("drawbars").arr().map { unit(it, "drawbar") }, ov("click"), ov("percussionLevel"), enum(o.getValue("percussionHarmonic")),
                    on("percussionDecaySeconds"), ov("leakage"), ov("drive"), on("attackSeconds"), on("releaseSeconds"), on("cabinetHz"),
                    o.getValue("rotary").bool(), enum(o.getValue("rotaryStart")), enum(o.getValue("rotaryTarget")), on("rotaryChangeSeconds"), ov("rotaryDepth")),
                unit(p.getValue("sharedPressure"), "sharedPressure"))
        }
    }
}
