package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.util.Collections

/** Native parameter units and defaults, loaded from the same metadata used to generate the C++ table. */
data class AquariumParameterSpec(
    val id: String,
    val minimum: Float,
    val maximum: Float,
    val default: Float,
    val step: Float = 0f,
    private val declaredMinimum: Double = minimum.toDouble(),
    private val declaredMaximum: Double = maximum.toDouble(),
) {
    /** Enumerations/counts are whole native units; frequency/time controls remain continuous. */
    val isDiscrete: Boolean get() = step > 0f

    init {
        require(minimum.isFinite() && maximum.isFinite() && default.isFinite() && minimum <= maximum && step.isFinite() && step >= 0f &&
            declaredMinimum.isFinite() && declaredMaximum.isFinite() && declaredMinimum.toFloat() == minimum && declaredMaximum.toFloat() == maximum) {
            "invalid Aquarium parameter specification: $id"
        }
        validate(default)
    }

    fun validate(value: Float) {
        require(value.isFinite() && value in minimum..maximum) {
            "Aquarium parameter $id must be finite and in $minimum..$maximum: $value"
        }
        if (isDiscrete) {
            val position = (value - minimum) / step
            require(position % 1f == 0f) { "Aquarium parameter $id must follow step $step from $minimum: $value" }
        }
    }

    /** Accept literal physical bounds and exact Float endpoints; reject overflow before narrowing. */
    internal fun decode(number: Double): Float {
        val lower = minOf(declaredMinimum, minimum.toDouble())
        val upper = maxOf(declaredMaximum, maximum.toDouble())
        require(number.isFinite() && number in lower..upper) {
            "Aquarium parameter $id must be finite and in $declaredMinimum..$declaredMaximum: $number"
        }
        if (isDiscrete) {
            val position = (number - minimum) / step
            require(position % 1.0 == 0.0) { "Aquarium parameter $id must follow step $step: $number" }
        }
        return number.toFloat()
    }
}

/**
 * Aquarium sound settings shared by future pad-print and master-bus adapters.
 *
 * This describes no placement and renders no audio. BPM, sample rate and clock origin belong to
 * the render context; a deterministic render needs the same context as well as this recipe.
 * Controls use native parameter IDs/units, not the rack's normalized macros. An absent control
 * uses the model's canonical default, so the empty descriptor bypasses all six stages. JSON
 * always saves the complete snapshot, not a preset name or an implicit dependency on defaults.
 * This is not a complete pad-render envelope: its caller must preserve source identity, tempo,
 * clock origin and tail policy alongside it. Sound-changing DSP laws require a new model.
 */
class AquariumRecipe(parameters: Map<String, Float> = emptyMap(), val seed: Long = DEFAULT_SEED, val model: Int = MODEL) {
    /** Complete immutable native snapshot, in canonical order, regardless of supplied map order. */
    val parameters: Map<String, Float>

    /** All six wet/dry controls are zero. No pad or bus renderer is registered by this descriptor. */
    val isBypass: Boolean get() = MIX_IDS.all { parameters.getValue(it) == 0f }

    init {
        require(seed in 0L..MAX_SEED) { "Aquarium seed must be an unsigned 32-bit integer: $seed" }
        require(model == MODEL) { "unsupported Aquarium model $model" }
        val supplied = LinkedHashMap(parameters)
        for ((id, value) in supplied) {
            val spec = SPECS_BY_ID[id] ?: throw IllegalArgumentException("unknown Aquarium parameter $id")
            spec.validate(value)
        }
        this.parameters = immutable(PARAMETERS.associateTo(LinkedHashMap()) {
            val value = supplied[it.id] ?: it.default
            it.id to if (value == 0f) 0f else value
        })
    }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "aquarium" to JsonValue.Num(VERSION.toDouble()),
        "model" to JsonValue.Num(model.toDouble()),
        "seed" to JsonValue.Num(seed.toDouble()),
        "parameters" to JsonValue.Obj(parameters.mapValues { JsonValue.Num(it.value.toDouble()) }),
    ))

    fun toJsonText(): String = Json.write(toJsonValue())

    override fun equals(other: Any?): Boolean = other is AquariumRecipe && model == other.model && seed == other.seed && parameters == other.parameters
    override fun hashCode(): Int = 31 * (31 * parameters.hashCode() + seed.hashCode()) + model
    override fun toString(): String = "AquariumRecipe(parameters=$parameters, seed=$seed, model=$model)"

    companion object {
        const val VERSION = 1
        const val MODEL = 1
        const val DEFAULT_SEED = 1337L
        const val MAX_SEED = 0xffff_ffffL
        private const val RESOURCE = "/com/snipsnap/synth/aquarium/parameters.json"

        /** Metadata is packaged from app/src/main/cpp/aquarium/parameters.json, never hand-transcribed. */
        val PARAMETERS: List<AquariumParameterSpec> = Collections.unmodifiableList(loadParameters())
        private val SPECS_BY_ID = PARAMETERS.associateBy { it.id }
        private val MIX_IDS = PARAMETERS.map { it.id }.filter { it.endsWith("_mix") }

        fun fromJsonValue(value: JsonValue): AquariumRecipe {
            val obj = value.obj()
            val unknown = obj.keys - setOf("aquarium", "model", "seed", "parameters")
            if (unknown.isNotEmpty()) throw JsonException("unknown Aquarium recipe fields: ${unknown.sorted().joinToString()}")
            val version = obj["aquarium"]?.int() ?: throw JsonException("not an Aquarium recipe: no aquarium version")
            if (version != VERSION) throw JsonException("unsupported Aquarium recipe version $version")
            val model = obj["model"]?.int() ?: throw JsonException("Aquarium recipe requires a model")
            val seed = obj["seed"]?.long() ?: throw JsonException("Aquarium recipe requires a seed")
            val snapshot = obj["parameters"]?.obj() ?: throw JsonException("Aquarium recipe requires a complete parameter snapshot")
            val missing = SPECS_BY_ID.keys - snapshot.keys
            if (missing.isNotEmpty()) throw JsonException("incomplete Aquarium parameter snapshot: missing ${missing.sorted().joinToString()}")
            try {
                return AquariumRecipe(
                    parameters = snapshot.mapValues { (id, v) ->
                        val spec = SPECS_BY_ID[id] ?: throw IllegalArgumentException("unknown Aquarium parameter $id")
                        spec.decode(v.num())
                    },
                    seed = seed,
                    model = model,
                )
            } catch (e: IllegalArgumentException) {
                throw JsonException(e.message ?: "invalid Aquarium recipe")
            }
        }

        fun fromJsonText(text: String): AquariumRecipe = fromJsonValue(Json.parse(text))

        private fun loadParameters(): List<AquariumParameterSpec> {
            val text = AquariumRecipe::class.java.getResourceAsStream(RESOURCE)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("missing Aquarium parameter metadata: $RESOURCE")
            val entries = Json.parse(text).obj()
            val result = entries.map { (id, value) ->
                val spec = value.obj()
                require(spec.keys.containsAll(setOf("min", "max", "default")) && (spec.keys - setOf("min", "max", "default", "step")).isEmpty()) {
                    "invalid Aquarium metadata fields for $id"
                }
                val minimum = spec.getValue("min").num()
                val maximum = spec.getValue("max").num()
                AquariumParameterSpec(id, minimum.toFloat(), maximum.toFloat(), spec.getValue("default").num().toFloat(), spec["step"]?.num()?.toFloat() ?: 0f, minimum, maximum)
            }
            val stages = setOf("fathom", "drift", "tide", "reef", "surface", "biolume")
            require(result.map { it.id }.filter { it.endsWith("_mix") }.toSet() == stages.map { "aqua_${it}_mix" }.toSet()) {
                "Aquarium metadata must contain exactly the six stage mix controls"
            }
            require(result.all { spec -> stages.any { spec.id.startsWith("aqua_${it}_") } }) { "unknown Aquarium stage in metadata" }
            return result
        }

        private fun immutable(values: Map<String, Float>): Map<String, Float> = Collections.unmodifiableMap(LinkedHashMap(values))
    }
}
