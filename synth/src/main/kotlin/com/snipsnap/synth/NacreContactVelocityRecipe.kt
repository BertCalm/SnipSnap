package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.util.Collections

/** One owned, complete model-4 source; its gain cancels the fixed native velocity/TOUCH effort factors. */
class NacreContactVelocityLayerRecipe(
    val name: String,
    val velocityStart: Int,
    val velocityEnd: Int,
    val samplingGain: Float,
    recipe: PadRecipe,
) {
    val recipe: PadRecipe

    init {
        require(name.isNotBlank()) { "NACRE contact velocity layer needs a name" }
        require(velocityStart in 1..127 && velocityEnd in velocityStart..127) {
            "NACRE contact velocity layer must answer notes within 1..127"
        }
        require(samplingGain.isFinite() && samplingGain > 0f && samplingGain <= 32f) {
            "NACRE contact sampling gain must be finite and within 0..32"
        }
        val supplied = requireNotNull(recipe.patch as? NacrePatch) { "NACRE contact layer needs a NacrePatch" }
        require(recipe == PadRecipe(patch = supplied)) { "NACRE contact layers contain ordinary dry source recipes" }
        // Copy before validating: the caller's original macro map may have changed since patch construction.
        val owned = supplied.copy(macros = Collections.unmodifiableMap(LinkedHashMap(supplied.macros)))
        require(owned.model == NacreContactVelocityRecipes.MODEL && owned.voice == NacreVoice.PEARL) {
            "NACRE contact layers require the explicit model-4 PEARL source"
        }
        require(owned.macros.keys == Nacre.defaults(owned.voice, NacreContactVelocityRecipes.MODEL).keys &&
            owned.macros.getValue("HOLD") == 0f) { "NACRE contact layers must save every finite source control" }
        // Never retain caller-owned PadRecipe state, even if an optional field currently equals its default.
        this.recipe = PadRecipe(patch = owned)
    }

    val patch: NacrePatch get() = recipe.patch as NacrePatch

    /** Complete native replay with fixed input gain; no normalization, truncation or cache-owned arrays. */
    fun render(render: (NacrePatch) -> Snip = { it.render() }): Snip {
        val native = render(patch)
        require(native.channels == 1 && native.sampleRate == Dsp.RATE &&
            native.frameCount == NacreContactVelocityRecipes.FRAME_COUNT) {
            "NACRE contact velocity source must remain a full six-second mono 44.1 kHz gesture"
        }
        require(native.samples.all { it.isFinite() }) { "NACRE contact native source contains nonfinite audio" }
        val samples = if (samplingGain == 1f) native.samples.copyOf()
            else FloatArray(native.samples.size) { native.samples[it] * samplingGain }
        require(samples.all { it.isFinite() }) { "NACRE contact sampler layer contains nonfinite audio" }
        val result = Snip(samples, native.channels, native.sampleRate)
        require(result.peak() <= .950001f) { "NACRE contact sampler layer exceeds the .95 delivery ceiling" }
        return result
    }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "name" to JsonValue.Str(name),
        "velocityStart" to JsonValue.Num(velocityStart.toDouble()),
        "velocityEnd" to JsonValue.Num(velocityEnd.toDouble()),
        "samplingGain" to JsonValue.Num(samplingGain.toDouble()),
        "recipe" to recipe.toJsonValue(),
    ))

    override fun equals(other: Any?): Boolean = other is NacreContactVelocityLayerRecipe &&
        name == other.name && velocityStart == other.velocityStart && velocityEnd == other.velocityEnd &&
        samplingGain == other.samplingGain && recipe == other.recipe
    override fun hashCode(): Int = listOf(name, velocityStart, velocityEnd, samplingGain, recipe).hashCode()
}

/**
 * Opt-in model-4 delivery policy. The established native velocity/TOUCH articulation changes three
 * source performances; CONTACT and all remaining controls stay fixed. One-source replay is not bank replay.
 */
class NacreContactVelocityRecipe(layers: List<NacreContactVelocityLayerRecipe>) {
    val layers: List<NacreContactVelocityLayerRecipe> = Collections.unmodifiableList(ArrayList(layers))

    init {
        require(this.layers.size == 3) { "NACRE contact velocity recipe needs exactly three native performances" }
        val hard = this.layers.last().patch
        require(hard.velocity == 1f && hard.macros.getValue("TOUCH") == NacreVelocityKits.HARD_TOUCH) {
            "NACRE contact hard reference must retain native velocity 1 and TOUCH .30"
        }
        for ((index, layer) in this.layers.withIndex()) {
            require(layer.name == NacreContactVelocityRecipes.LAYER_NAMES[index] &&
                layer.velocityStart == NacreContactVelocityRecipes.VELOCITY_STARTS[index] &&
                layer.velocityEnd == NacreContactVelocityRecipes.VELOCITY_ENDS[index]) {
                "NACRE contact velocity windows must cover 1..127 exactly, soft to hard"
            }
            require(layer.patch.velocity == NacreContactVelocityRecipes.VELOCITIES[index] &&
                layer.patch.macros.getValue("TOUCH") == NacreContactVelocityRecipes.TOUCHES[index]) {
                "NACRE contact layer does not retain its explicit native velocity/TOUCH articulation"
            }
            require(layer.patch.copy(velocity = 1f,
                macros = layer.patch.macros + ("TOUCH" to NacreVelocityKits.HARD_TOUCH)) == hard) {
                "NACRE contact layers must share model, note, seed, name, CONTACT and every remaining control"
            }
            require(layer.samplingGain == NacreVelocityKits.samplingGain(layer.patch.velocity, layer.patch.macros.getValue("TOUCH"))) {
                "NACRE contact sampling gain must match the fixed velocity/TOUCH input effort law"
            }
        }
    }

    val hardPatch: NacrePatch get() = layers.last().patch
    fun renderAllLayers(render: (NacrePatch) -> Snip = { it.render() }): List<Snip> = layers.map { it.render(render) }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "schemaVersion" to JsonValue.Num(SCHEMA_VERSION.toDouble()),
        "engine" to JsonValue.Str(NacrePatch.ENGINE),
        "policy" to JsonValue.Str(POLICY),
        "policyVersion" to JsonValue.Num(POLICY_VERSION.toDouble()),
        "layers" to JsonValue.Arr(layers.map { it.toJsonValue() }),
    ))
    fun toJsonText(): String = Json.write(toJsonValue())
    override fun equals(other: Any?): Boolean = other is NacreContactVelocityRecipe && layers == other.layers
    override fun hashCode(): Int = layers.hashCode()

    companion object {
        const val SOURCE_KEY = "nacreContactVelocityLayers"
        const val POLICY = "native-velocity-touch-contact-model4-v1"
        const val POLICY_VERSION = 1
        const val SCHEMA_VERSION = 1

        fun fromJsonText(text: String): NacreContactVelocityRecipe = fromJsonValue(Json.parse(text))

        fun fromJsonValue(value: JsonValue): NacreContactVelocityRecipe {
            val obj = value.obj()
            require(obj.keys == setOf("schemaVersion", "engine", "policy", "policyVersion", "layers")) {
                "unsupported NACRE contact velocity recipe fields"
            }
            require(obj.getValue("schemaVersion").int() == SCHEMA_VERSION &&
                obj.getValue("engine").str() == NacrePatch.ENGINE && obj.getValue("policy").str() == POLICY &&
                obj.getValue("policyVersion").int() == POLICY_VERSION) { "unsupported NACRE contact velocity policy" }
            return NacreContactVelocityRecipe(obj.getValue("layers").arr().map { raw ->
                val layer = raw.obj()
                require(layer.keys == setOf("name", "velocityStart", "velocityEnd", "samplingGain", "recipe")) {
                    "unsupported NACRE contact velocity layer fields"
                }
                val gain = layer.getValue("samplingGain").num()
                require(gain.isFinite() && gain > 0.0 && gain <= 32.0 && gain.toFloat().toDouble() == gain) {
                    "NACRE contact sampling gain must preserve its exact Float policy value"
                }
                val source = layer.getValue("recipe")
                val decoded = PadRecipe.fromJsonValue(source)
                // PadRecipe supports other workflows permissively. This policy requires a complete,
                // canonical dry source, refusing unknown fields and values rounded away by Float decoding.
                require(source == decoded.toJsonValue()) { "NACRE contact source recipe is not complete and canonical" }
                NacreContactVelocityLayerRecipe(layer.getValue("name").str(), layer.getValue("velocityStart").int(),
                    layer.getValue("velocityEnd").int(), gain.toFloat(), decoded)
            })
        }
    }
}

/** Recipe construction only: existing starters, kits, downloads and model-3 v1 remain unchanged. */
object NacreContactVelocityRecipes {
    const val MODEL = 4
    val FRAME_COUNT: Int = (Nacre.DURATION_SECONDS * Dsp.RATE).toInt()
    val VELOCITIES: List<Float> = Collections.unmodifiableList(listOf(.30f, .65f, 1f))
    val TOUCHES: List<Float> = Collections.unmodifiableList(listOf(.05f, .18f, NacreVelocityKits.HARD_TOUCH))
    val LAYER_NAMES: List<String> = Collections.unmodifiableList(listOf("Soft", "Medium", "Hard"))
    val VELOCITY_STARTS: List<Int> = Collections.unmodifiableList(listOf(1, 42, 85))
    val VELOCITY_ENDS: List<Int> = Collections.unmodifiableList(listOf(41, 84, 127))

    fun recipeFor(hardPatch: NacrePatch): NacreContactVelocityRecipe {
        require(hardPatch.model == MODEL && hardPatch.velocity == 1f &&
            hardPatch.macros["TOUCH"] == NacreVelocityKits.HARD_TOUCH) {
            "NACRE contact velocity recipe needs a model-4 hard reference at velocity 1 and TOUCH .30"
        }
        return NacreContactVelocityRecipe(VELOCITIES.indices.map { index ->
            NacreContactVelocityLayerRecipe(LAYER_NAMES[index], VELOCITY_STARTS[index], VELOCITY_ENDS[index],
                NacreVelocityKits.samplingGain(VELOCITIES[index], TOUCHES[index]),
                PadRecipe(patch = hardPatch.copy(velocity = VELOCITIES[index],
                    macros = hardPatch.macros + ("TOUCH" to TOUCHES[index]))))
        })
    }
}
