package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.concurrent.CancellationException

/**
 * Complete OPENSKY sample-authoring recipe. Source blend and articulation are
 * stored beside the four macros so a saved pad retains the sound that was heard.
 * The note, excitation velocity, gate, wet stage and render model are explicit.
 * Constructors and copies require a complete macro map; [withMacros] resolves
 * a partial replacement over the voice defaults before saving the new snapshot.
 */
data class OpenSkyPatch(
    override val name: String,
    val voice: OpenSkyVoice,
    override val macros: Map<String, Float>,
    val midi: Int = DEFAULT_MIDI,
    val velocity: Float = 1f,
    val gateSeconds: Float = DEFAULT_GATE_SECONDS,
    val wet: Boolean = true,
    val timbre: OpenSkyTimbre = OpenSkyTimbre(),
    val model: Int = MODEL_VERSION,
) : Patch {
    init {
        Patches.validateMacros(this, OpenSky.macrosFor(voice))
        require(macros.keys == MACRO_NAMES) { "OPENSKY recipe requires all four macros: $MACRO_NAMES" }
        require(midi in MIDI_MIN..MIDI_MAX) { "OPENSKY midi out of $MIDI_MIN..$MIDI_MAX: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OPENSKY velocity out of 0..1: $velocity" }
        require(gateSeconds.isFinite() && gateSeconds in GATE_SECONDS_MIN..GATE_SECONDS_MAX) {
            "OPENSKY gate out of $GATE_SECONDS_MIN..$GATE_SECONDS_MAX seconds: $gateSeconds"
        }
        require(model == MODEL_VERSION) { "unsupported OPENSKY model $model" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render(): Snip = render { false }
    fun render(cancelled: () -> Boolean): Snip = applyHeadroom(OpenSky.render(
        voice, macros, midi = midi, velocity = velocity, gateSeconds = gateSeconds,
        wet = wet, cancelled = cancelled, timbre = timbre,
    ), cancelled)
    override fun withMacros(macros: Map<String, Float>): OpenSkyPatch = copy(macros = OpenSky.defaults(voice) + macros)

    override fun toJsonValue(): JsonValue.Obj {
        val obj = LinkedHashMap(Patches.toJsonValue(this).entries)
        obj["model"] = JsonValue.Num(model.toDouble())
        obj["midi"] = JsonValue.Num(midi.toDouble())
        obj["velocity"] = number(velocity)
        obj["gateSeconds"] = number(gateSeconds)
        obj["wet"] = JsonValue.Bool(wet)
        obj["timbre"] = timbreJson(timbre)
        return JsonValue.Obj(obj)
    }

    companion object {
        const val ENGINE = "OPENSKY"
        const val VERSION = Patches.VERSION
        const val MODEL_VERSION = OpenSky.MODEL_VERSION
        const val MIDI_MIN = 0
        const val MIDI_MAX = 127
        const val DEFAULT_MIDI = 60
        const val GATE_SECONDS_MIN = .05f
        const val GATE_SECONDS_MAX = 8f
        const val DEFAULT_GATE_SECONDS = 1.5f
        const val OUTPUT_PEAK = .8f

        /** One uniform attenuation when wet summation needs headroom; no gain is added. */
        internal fun applyHeadroom(snip: Snip, cancelled: () -> Boolean): Snip {
            val peak = snip.peak()
            if (peak <= OUTPUT_PEAK) return snip
            val gain = OUTPUT_PEAK / peak
            for (i in snip.samples.indices) {
                if (i % 16_384 == 0 && (cancelled() || Thread.currentThread().isInterrupted)) {
                    throw CancellationException("OpenSky render no longer wanted")
                }
                snip.samples[i] *= gain
            }
            return snip
        }

        private val MACRO_NAMES = setOf("RISE", "WIDTH", "GLOW", "AIR")
        private val PATCH_FIELDS = setOf("engine", "version", "name", "voice", "macros", "model",
            "midi", "velocity", "gateSeconds", "wet", "timbre")
        private val TIMBRE_FIELDS = setOf("pulseMix", "pulseWidth", "sineMix", "octaveMix",
            "centerWeightScale", "detuneScale", "toneScale", "attackScale", "sustainLevel",
            "decaySeconds", "releaseScale", "bellBrightnessScale", "bellDecayScale")

        fun fromJsonValue(value: JsonValue): OpenSkyPatch = try {
            val obj = value.obj()
            exactFields(obj, PATCH_FIELDS, "patch")
            val rawMacroValue = required(obj, "macros")
            val rawMacros = (rawMacroValue as? JsonValue.Obj)?.entries
                ?: throw JsonException("patch macros must be an object, got ${rawMacroValue::class.simpleName}")
            exactFields(rawMacros, MACRO_NAMES, "macros")
            for ((name, raw) in rawMacros) {
                val value = raw.num()
                if (!value.isFinite() || value !in 0.0..1.0) throw JsonException("OPENSKY macro $name must be finite in 0..1")
            }
            Patches.decode(value, ENGINE, { name -> OpenSkyVoice.entries.firstOrNull { it.name == name } }) { name, voice, macros ->
                val model = required(obj, "model").int()
                if (model != MODEL_VERSION) throw JsonException("unsupported OPENSKY model $model")
                OpenSkyPatch(name, voice, macros,
                    midi = required(obj, "midi").int(),
                    velocity = boundedFloat(obj, "velocity", 0.0, 1.0),
                    gateSeconds = boundedFloat(obj, "gateSeconds", .05, 8.0),
                    wet = required(obj, "wet").bool(),
                    timbre = decodeTimbre(required(obj, "timbre")),
                    model = model,
                )
            } as OpenSkyPatch
        } catch (error: IllegalArgumentException) {
            throw JsonException(error.message ?: "invalid OPENSKY recipe")
        }

        fun fromJsonText(text: String): OpenSkyPatch = fromJsonValue(Json.parse(text))

        /** Used by the audition manifest as well as the complete saved recipe. */
        internal fun timbreJson(timbre: OpenSkyTimbre): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
            "pulseMix" to number(timbre.pulseMix),
            "pulseWidth" to number(timbre.pulseWidth),
            "sineMix" to number(timbre.sineMix),
            "octaveMix" to number(timbre.octaveMix),
            "centerWeightScale" to number(timbre.centerWeightScale),
            "detuneScale" to number(timbre.detuneScale),
            "toneScale" to number(timbre.toneScale),
            "attackScale" to number(timbre.attackScale),
            "sustainLevel" to number(timbre.sustainLevel),
            "decaySeconds" to number(timbre.decaySeconds),
            "releaseScale" to number(timbre.releaseScale),
            "bellBrightnessScale" to number(timbre.bellBrightnessScale),
            "bellDecayScale" to number(timbre.bellDecayScale),
        ))

        private fun number(value: Float) = JsonValue.Num(value.toDouble())

        private fun required(obj: Map<String, JsonValue>, key: String): JsonValue =
            obj[key] ?: throw JsonException("OPENSKY recipe has no $key")

        private fun float(obj: Map<String, JsonValue>, key: String): Float {
            val raw = required(obj, key).num()
            val value = raw.toFloat()
            if (!raw.isFinite() || !value.isFinite()) throw JsonException("OPENSKY $key must be finite")
            return value
        }

        private fun boundedFloat(obj: Map<String, JsonValue>, key: String, minimum: Double, maximum: Double): Float {
            val raw = required(obj, key).num()
            if (!raw.isFinite() || raw !in minimum..maximum) {
                throw JsonException("OPENSKY $key must be finite in $minimum..$maximum")
            }
            return float(obj, key)
        }

        private fun exactFields(obj: Map<String, JsonValue>, expected: Set<String>, label: String) {
            val missing = expected - obj.keys
            if (missing.isNotEmpty()) throw JsonException("OPENSKY $label has no ${missing.joinToString()}")
            val unknown = obj.keys - expected
            if (unknown.isNotEmpty()) throw JsonException("unknown OPENSKY $label fields: ${unknown.joinToString()}")
        }

        private fun decodeTimbre(value: JsonValue): OpenSkyTimbre {
            val obj = value.obj()
            exactFields(obj, TIMBRE_FIELDS, "timbre")
            return OpenSkyTimbre(
                pulseMix = boundedFloat(obj, "pulseMix", 0.0, 1.0), pulseWidth = boundedFloat(obj, "pulseWidth", .15, .85f.toDouble()),
                sineMix = boundedFloat(obj, "sineMix", 0.0, 1.0), octaveMix = boundedFloat(obj, "octaveMix", 0.0, .8f.toDouble()),
                centerWeightScale = boundedFloat(obj, "centerWeightScale", .3, 3.0), detuneScale = boundedFloat(obj, "detuneScale", .2, 2.0),
                toneScale = boundedFloat(obj, "toneScale", .3, 2.0), attackScale = boundedFloat(obj, "attackScale", .2, 5.0),
                sustainLevel = boundedFloat(obj, "sustainLevel", .2, 1.0), decaySeconds = boundedFloat(obj, "decaySeconds", .05, 2.0),
                releaseScale = boundedFloat(obj, "releaseScale", .2, 3.0), bellBrightnessScale = boundedFloat(obj, "bellBrightnessScale", .25, 2.0),
                bellDecayScale = boundedFloat(obj, "bellDecayScale", .25, 3.0),
            )
        }
    }
}
