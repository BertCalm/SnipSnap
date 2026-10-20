package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.Collections

/** The complete recipe: points and settings own their values; transient solver state is rebuilt. */
class ScannedChainPatch(
    override val name: String,
    val voice: ScannedChainVoice,
    macros: Map<String, Float> = ScannedChain.defaults(voice),
    val shape: ScannedChainShape = ScannedChainShape.factory(voice),
    val config: ScannedChainConfig = ScannedChainConfig.forVoice(voice),
    val midi: Int = 60,
    val velocity: Float = .8f,
    val model: Int = ScannedChain.MODEL_VERSION,
) : Patch {
    override val macros: Map<String, Float> = Collections.unmodifiableMap(LinkedHashMap(macros))

    init {
        Patches.validateMacros(this, ScannedChain.macrosFor(voice))
        require(midi in ScannedChain.MIDI_MIN..ScannedChain.MIDI_MAX) { "SCANNED CHAIN MIDI must be 36..96" }
        require(velocity.isFinite() && velocity in 0f..1f) { "SCANNED CHAIN velocity must be 0..1" }
        require(model == ScannedChain.MODEL_VERSION) { "unsupported scanned-chain model $model" }
        config.validateForVoice(voice)
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = ScannedChain.render(voice, macros, midi, velocity, shape, config)
    override fun withMacros(macros: Map<String, Float>): ScannedChainPatch = copy(macros = macros)

    fun copy(
        name: String = this.name, voice: ScannedChainVoice = this.voice, macros: Map<String, Float> = this.macros,
        shape: ScannedChainShape = this.shape, config: ScannedChainConfig = this.config,
        midi: Int = this.midi, velocity: Float = this.velocity, model: Int = this.model,
    ): ScannedChainPatch = ScannedChainPatch(name, voice, macros, shape, config, midi, velocity, model)

    override fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(LinkedHashMap(Patches.toJsonValue(this).entries).apply {
        put("model", JsonValue.Num(model.toDouble()))
        put("midi", JsonValue.Num(midi.toDouble()))
        put("velocity", JsonValue.Num(velocity.toDouble()))
        put("shape", shape.toJsonValue())
        put("config", JsonValue.Obj(linkedMapOf(
            "mechanicalHz" to JsonValue.Num(config.mechanicalHz.toDouble()),
            "restFraction" to JsonValue.Num(config.restFraction.toDouble()),
            "driveHz" to JsonValue.Num(config.driveHz.toDouble()),
            "seconds" to JsonValue.Num(config.seconds.toDouble()),
            "attackSeconds" to JsonValue.Num(config.attackSeconds.toDouble()),
            "releaseSeconds" to JsonValue.Num(config.releaseSeconds.toDouble()),
            "seed" to JsonValue.Num(config.seed.toDouble()),
        )))
    })

    override fun equals(other: Any?): Boolean = other is ScannedChainPatch && name == other.name && voice == other.voice &&
        macros == other.macros && shape == other.shape && config == other.config && midi == other.midi &&
        velocity.toBits() == other.velocity.toBits() && model == other.model
    override fun hashCode(): Int = listOf(name, voice, macros, shape, config, midi, velocity, model).hashCode()
    override fun toString(): String = "ScannedChainPatch($name, $voice, MIDI $midi, $shape)"

    companion object {
        const val ENGINE = "SCANNED_CHAIN"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): ScannedChainPatch {
            try {
                val obj = value.obj()
                fun r(key: String) = obj[key] ?: throw JsonException("scanned-chain patch has no $key")
                val model = r("model").int()
                if (model != ScannedChain.MODEL_VERSION) throw JsonException("unsupported scanned-chain model $model")
                return Patches.decode(value, ENGINE, { n -> ScannedChainVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                    val c = r("config").obj()
                    fun f(key: String) = c[key]?.num()?.toFloat() ?: throw JsonException("scanned-chain config has no $key")
                    val config = ScannedChainConfig(f("mechanicalHz"), f("restFraction"), f("driveHz"), f("seconds"),
                        f("attackSeconds"), f("releaseSeconds"), c["seed"]?.int() ?: throw JsonException("scanned-chain config has no seed"))
                    ScannedChainPatch(name, voice, macros, ScannedChainShape.fromJsonValue(r("shape")), config,
                        r("midi").int(), r("velocity").num().toFloat(), model)
                } as ScannedChainPatch
            } catch (e: IllegalArgumentException) {
                throw JsonException(e.message ?: "invalid scanned-chain patch")
            }
        }

        fun fromJsonText(text: String): ScannedChainPatch = fromJsonValue(Json.parse(text))
    }
}
