package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Complete, deliberately standalone research recipe; never accepted by Patch or PadRecipe. */
object OtisPrototypeRecipe {
    private const val FORMAT = "snipsnap-otis-prototype"

    fun toJsonText(request: OtisPrototypeRequest): String = Json.write(toJsonValue(request))

    fun toJsonValue(request: OtisPrototypeRequest): JsonValue.Obj {
        val root = obj(
            "format" to str(FORMAT), "model" to num(request.model),
            "voice" to str(request.voice.name), "seed" to num(request.seed),
            "notes" to JsonValue.Arr(request.notes.map { note -> obj(
                "midi" to num(note.midi), "startSeconds" to num(note.startSeconds),
                "gateSeconds" to num(note.gateSeconds), "velocity" to num(note.velocity),
            ) }),
            "organ" to request.organ.let { s -> obj(
                "drawbars" to JsonValue.Arr(s.drawbars.map { num(it) }),
                "click" to num(s.click), "percussionLevel" to num(s.percussionLevel),
                "percussionHarmonic" to str(s.percussionHarmonic.name),
                "percussionDecaySeconds" to num(s.percussionDecaySeconds),
                "leakage" to num(s.leakage), "drive" to num(s.drive),
                "attackSeconds" to num(s.attackSeconds), "releaseSeconds" to num(s.releaseSeconds),
                "cabinetHz" to num(s.cabinetHz), "rotary" to JsonValue.Bool(s.rotary),
                "rotaryStart" to str(s.rotaryStart.name), "rotaryTarget" to str(s.rotaryTarget.name),
                "rotaryChangeSeconds" to num(s.rotaryChangeSeconds), "rotaryDepth" to num(s.rotaryDepth),
            ) },
            "air" to request.air.let { s -> obj(
                "steamInstability" to num(s.steamInstability), "harpBend" to num(s.harpBend),
                "breath" to num(s.breath), "harpVibrato" to num(s.harpVibrato),
                "musette" to num(s.musette), "bellowsPulse" to num(s.bellowsPulse),
                "pressure" to num(s.pressure), "release" to num(s.release),
            ) },
        )

        return if (request.model < 5) root else JsonValue.Obj(LinkedHashMap(root.entries).also {
            it["sharedPressure"] = num(request.sharedPressure)
        })
    }

    fun fromJsonText(text: String): OtisPrototypeRequest = fromJsonValue(Json.parse(text))

    fun fromJsonValue(value: JsonValue): OtisPrototypeRequest {
        val raw = value.obj()
        val model = (raw["model"] ?: throw JsonException("missing recipe fields: model")).int()
        if (raw["format"]?.str() != FORMAT) throw JsonException("not an Otis prototype recipe")
        if (model !in 1..OtisPrototype.MODEL_VERSION) throw JsonException("unsupported Otis prototype model $model")
        val keys = setOf("format", "model", "voice", "seed", "notes", "organ", "air") +
            if (model >= 5) setOf("sharedPressure") else emptySet()
        val root = fields(value, "recipe", keys)
        val voice = enumeration<OtisPrototypeVoice>(root.getValue("voice"), "voice")
        val organ = fields(root.getValue("organ"), "organ", setOf(
            "drawbars", "click", "percussionLevel", "percussionHarmonic", "percussionDecaySeconds",
            "leakage", "drive", "attackSeconds", "releaseSeconds", "cabinetHz", "rotary",
            "rotaryStart", "rotaryTarget", "rotaryChangeSeconds", "rotaryDepth",
        ))
        val air = fields(root.getValue("air"), "air", setOf(
            "steamInstability", "harpBend", "breath", "harpVibrato", "musette", "bellowsPulse", "pressure", "release",
        ))
        // Use the same constructors as callers of the renderer: decoded JSON cannot bypass ranges.
        return OtisPrototypeRequest(
            voice = voice,
            notes = root.getValue("notes").arr().mapIndexed { index, raw ->
                val note = fields(raw, "notes[$index]", setOf("midi", "startSeconds", "gateSeconds", "velocity"))
                OtisPrototypeNote(note.getValue("midi").int(), number(note, "startSeconds"),
                    number(note, "gateSeconds"), scalar(note, "velocity"))
            },
            seed = root.getValue("seed").int(),
            model = model,
            sharedPressure = if (model >= 5) scalar(root, "sharedPressure") else .85f,
            organ = OtisOrganSettings(
                drawbars = organ.getValue("drawbars").arr().mapIndexed { index, bar -> normalized(bar.num(), "drawbars[$index]") },
                click = scalar(organ, "click"), percussionLevel = scalar(organ, "percussionLevel"),
                percussionHarmonic = enumeration(organ.getValue("percussionHarmonic"), "percussionHarmonic"),
                percussionDecaySeconds = number(organ, "percussionDecaySeconds"),
                leakage = scalar(organ, "leakage"), drive = scalar(organ, "drive"),
                attackSeconds = number(organ, "attackSeconds"), releaseSeconds = number(organ, "releaseSeconds"),
                cabinetHz = number(organ, "cabinetHz"), rotary = organ.getValue("rotary").bool(),
                rotaryStart = enumeration(organ.getValue("rotaryStart"), "rotaryStart"),
                rotaryTarget = enumeration(organ.getValue("rotaryTarget"), "rotaryTarget"),
                rotaryChangeSeconds = number(organ, "rotaryChangeSeconds"), rotaryDepth = scalar(organ, "rotaryDepth"),
            ),
            air = OtisAirSettings(
                steamInstability = scalar(air, "steamInstability"), harpBend = scalar(air, "harpBend"),
                breath = scalar(air, "breath"), harpVibrato = scalar(air, "harpVibrato"),
                musette = scalar(air, "musette"), bellowsPulse = scalar(air, "bellowsPulse"),
                pressure = scalar(air, "pressure"), release = scalar(air, "release"),
            ),
        )
    }

    private fun fields(value: JsonValue, label: String, expected: Set<String>): Map<String, JsonValue> {
        val fields = value.obj()
        val unknown = fields.keys - expected
        if (unknown.isNotEmpty()) throw JsonException("unknown $label fields: ${unknown.joinToString()}")
        val missing = expected - fields.keys
        if (missing.isNotEmpty()) throw JsonException("missing $label fields: ${missing.joinToString()}")
        return fields
    }

    private fun finite(value: Double, field: String): Double {
        if (!value.isFinite()) throw JsonException("$field must be finite")
        return value
    }
    private fun number(fields: Map<String, JsonValue>, field: String) = finite(fields.getValue(field).num(), field)
    private fun scalar(fields: Map<String, JsonValue>, field: String): Float {
        return normalized(number(fields, field), field)
    }
    private fun normalized(value: Double, field: String): Float {
        if (finite(value, field) !in 0.0..1.0) throw JsonException("$field must be 0..1")
        return value.toFloat()
    }
    private inline fun <reified E : Enum<E>> enumeration(value: JsonValue, field: String): E {
        val name = value.str()
        return enumValues<E>().firstOrNull { it.name == name } ?: throw JsonException("unknown $field $name")
    }
    private fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*fields))
    private fun str(value: String) = JsonValue.Str(value)
    private fun num(value: Number) = JsonValue.Num(value.toDouble())
}
