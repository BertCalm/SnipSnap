package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import java.util.Collections
import kotlin.math.pow

/** One complete native source and its explicit sampler delivery gain. */
data class NacreVelocityLayerRecipe(
    val name: String,
    val velocityStart: Int,
    val velocityEnd: Int,
    val samplingGain: Float,
    val recipe: PadRecipe,
) {
    init {
        require(name.isNotBlank()) { "NACRE velocity layer needs a name" }
        require(velocityStart in 1..127 && velocityEnd in velocityStart..127) {
            "NACRE velocity layer must answer notes within 1..127"
        }
        require(samplingGain.isFinite() && samplingGain > 0f && samplingGain <= 32f) {
            "NACRE velocity layer sampling gain must be finite and within 0..32"
        }
        val source = requireNotNull(recipe.patch as? NacrePatch) { "NACRE velocity layer needs a NacrePatch" }
        require(recipe == PadRecipe(patch = source)) { "NACRE velocity layers contain ordinary dry source recipes" }
        require(source.model == 3 && source.voice == NacreVoice.PEARL) {
            "NACRE velocity layers retain the finite PEARL revision 3 source"
        }
        require(source.macros.keys == NacreModels.defaults(source.voice, 3).keys && source.macros.getValue("HOLD") == 0f) {
            "NACRE velocity layer must save every finite source control"
        }
    }

    val patch: NacrePatch get() = recipe.patch as NacrePatch

    /** Never scale a renderer's array in place: a caller may reuse its native source. */
    fun render(render: (NacrePatch) -> Snip = { it.render() }): Snip {
        val native = render(patch)
        require(native.channels == 1 && native.sampleRate == Dsp.RATE && native.frameCount == 264_600) {
            "NACRE velocity source must remain a complete six-second mono 44.1 kHz gesture"
        }
        val samples = FloatArray(native.samples.size) { native.samples[it] * samplingGain }
        require(samples.all { it.isFinite() }) { "NACRE sampler layer contains nonfinite audio" }
        val result = Snip(samples, native.channels, native.sampleRate)
        require(result.peak() <= .950001f) { "NACRE sampler layer exceeds the .95 delivery ceiling" }
        return result
    }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "name" to JsonValue.Str(name),
        "velocityStart" to JsonValue.Num(velocityStart.toDouble()),
        "velocityEnd" to JsonValue.Num(velocityEnd.toDouble()),
        "samplingGain" to JsonValue.Num(samplingGain.toDouble()),
        "recipe" to recipe.toJsonValue(),
    ))
}

/**
 * Three independent source performances, rather than PadRecipe's finishing-chain layers.
 * The source string travels in ordinary kit history and is independent of sample filenames.
 * This provides explicit full-bank replay; it does not change the generic one-source editor.
 */
class NacreVelocityRecipe(layers: List<NacreVelocityLayerRecipe>) {
    val layers: List<NacreVelocityLayerRecipe> = Collections.unmodifiableList(layers.map { layer ->
        val patch = layer.patch.copy(macros = Collections.unmodifiableMap(LinkedHashMap(layer.patch.macros)))
        layer.copy(recipe = PadRecipe(patch = patch))
    })

    init {
        require(this.layers.size == 3) { "NACRE velocity recipe has exactly three source performances" }
        val hard = this.layers.last().patch
        require(hard.velocity == 1f && hard.macros.getValue("TOUCH") == NacreVelocityKits.HARD_TOUCH) {
            "NACRE hard layer retains the preferred full strike and TOUCH reference"
        }
        for ((index, layer) in this.layers.withIndex()) {
            require(layer.name == NacreVelocityKits.LAYER_NAMES[index] &&
                layer.velocityStart == NacreVelocityKits.VELOCITY_STARTS[index] &&
                layer.velocityEnd == NacreVelocityKits.VELOCITY_ENDS[index]) {
                "NACRE velocity windows must cover 1..127 exactly, soft to hard"
            }
            val patch = layer.patch
            require(patch.velocity == NacreVelocityKits.VELOCITIES[index] &&
                patch.macros.getValue("TOUCH") == NacreVelocityKits.TOUCHES[index]) {
                "NACRE layer source does not match its saved articulation policy"
            }
            require(patch.copy(velocity = hard.velocity,
                macros = patch.macros + ("TOUCH" to NacreVelocityKits.HARD_TOUCH)) == hard) {
                "NACRE layers must share the same saved note, seed and remaining source controls"
            }
            require(layer.samplingGain == NacreVelocityKits.samplingGain(patch.velocity, patch.macros.getValue("TOUCH"))) {
                "NACRE layer sampling gain does not match its fixed effort policy"
            }
        }
    }

    val hardPatch: NacrePatch get() = layers.last().patch

    fun renderAllLayers(render: (NacrePatch) -> Snip = { it.render() }): List<Snip> = layers.map { it.render(render) }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "schemaVersion" to JsonValue.Num(1.0),
        "engine" to JsonValue.Str(NacrePatch.ENGINE),
        "policy" to JsonValue.Str(POLICY),
        "layers" to JsonValue.Arr(layers.map { it.toJsonValue() }),
    ))

    fun toJsonText(): String = Json.write(toJsonValue())

    override fun equals(other: Any?): Boolean = other is NacreVelocityRecipe && layers == other.layers
    override fun hashCode(): Int = layers.hashCode()

    companion object {
        const val SOURCE_KEY = "nacreVelocityLayers"
        const val POLICY = "native-velocity-and-touch-effort-v1"

        fun fromJsonText(text: String): NacreVelocityRecipe = fromJsonValue(Json.parse(text))

        fun fromJsonValue(value: JsonValue): NacreVelocityRecipe {
            val obj = value.obj()
            require(obj.keys == setOf("schemaVersion", "engine", "policy", "layers")) { "unsupported NACRE velocity recipe fields" }
            require(obj.getValue("schemaVersion").int() == 1 && obj.getValue("engine").str() == NacrePatch.ENGINE &&
                obj.getValue("policy").str() == POLICY) { "unsupported NACRE velocity recipe policy" }
            return NacreVelocityRecipe(obj.getValue("layers").arr().map { raw ->
                val layer = raw.obj()
                require(layer.keys == setOf("name", "velocityStart", "velocityEnd", "samplingGain", "recipe")) {
                    "unsupported NACRE velocity layer fields"
                }
                val gain = layer.getValue("samplingGain").num()
                require(gain.isFinite() && gain > 0.0 && gain <= 32.0) { "invalid NACRE layer sampling gain" }
                NacreVelocityLayerRecipe(layer.getValue("name").str(), layer.getValue("velocityStart").int(),
                    layer.getValue("velocityEnd").int(), gain.toFloat(), PadRecipe.fromJsonValue(layer.getValue("recipe")))
            })
        }
    }
}

/** A scale and two venue answers, with three explicit native articulations on every pad. */
object NacreVelocityKits {
    const val NAME = "SnipSnap Nacre Velocity Kit"
    const val SOURCE_KEY = NacreVelocityRecipe.SOURCE_KEY
    const val HARD_TOUCH = .30f
    val VELOCITIES: List<Float> = Collections.unmodifiableList(listOf(.30f, .65f, 1f))
    val TOUCHES: List<Float> = Collections.unmodifiableList(listOf(.05f, .18f, HARD_TOUCH))
    val LAYER_NAMES: List<String> = Collections.unmodifiableList(listOf("Soft", "Medium", "Hard"))
    val VELOCITY_STARTS: List<Int> = Collections.unmodifiableList(listOf(1, 42, 85))
    val VELOCITY_ENDS: List<Int> = Collections.unmodifiableList(listOf(41, 84, 127))

    private data class Entry(val preset: String, val midi: Int)
    private fun entries(): List<Entry> = listOf(48, 51, 53, 55, 58, 60, 63, 65).map { Entry("Pearl Close", it) } +
        listOf(48, 51, 55, 60).map { Entry("Coral Reply", it) } +
        listOf(48, 51, 55, 60).map { Entry("Kelp Passage", it) }

    fun patches(): List<NacrePatch> {
        val presets = NacrePresets.all().associateBy { it.name }
        return entries().map { entry -> presets.getValue(entry.preset).copy(
            name = "${entry.preset} ${Scales.nameOf(entry.midi)}", midi = entry.midi, velocity = 1f,
        ) }
    }

    /**
     * Remove only the source's explicit linear strike/effort amplitude factors. The sampler
     * supplies velocity volume; the different native excitation and TOUCH still change the
     * articulation. This is a fixed input law, never a measurement of the rendered audio.
     */
    fun samplingGain(velocity: Float, touch: Float): Float {
        require(velocity.isFinite() && velocity > 0f && velocity <= 1f && touch.isFinite() && touch in 0f..1f)
        if (velocity == 1f && touch == HARD_TOUCH) return 1f
        fun effort(t: Float) = .22 + .78 * t.toDouble().pow(.65)
        val gain = (effort(HARD_TOUCH) / (velocity * effort(touch))).toFloat()
        require(gain.isFinite() && gain <= 32f) { "NACRE input effort needs an unsupported sampling gain" }
        return gain
    }

    fun recipeFor(hardPatch: NacrePatch): NacreVelocityRecipe {
        require(hardPatch.model == 3 && hardPatch.velocity == 1f && hardPatch.macros["TOUCH"] == HARD_TOUCH) {
            "NACRE velocity bank needs a revision-3 hard reference at velocity 1 and TOUCH .30"
        }
        return NacreVelocityRecipe(VELOCITIES.indices.map { index ->
            val patch = hardPatch.copy(velocity = VELOCITIES[index], macros = hardPatch.macros + ("TOUCH" to TOUCHES[index]))
            NacreVelocityLayerRecipe(LAYER_NAMES[index], VELOCITY_STARTS[index], VELOCITY_ENDS[index],
                samplingGain(patch.velocity, TOUCHES[index]), PadRecipe(patch = patch))
        })
    }

    fun build(render: (NacrePatch) -> Snip = { it.render() }): List<ArrangedPad?> {
        val patches = patches()
        val banks = patches.map(::recipeFor)
        val audio = arrayOfNulls<List<Snip>>(patches.size)
        // All venue choices at one note reuse the same three source cores. Grouping
        // work keeps them inside Nacre's four-entry cache without retaining another
        // native-source cache here; the returned pad order remains the musical layout.
        for (indices in patches.indices.groupBy { patches[it].midi }.values) {
            for (index in indices) audio[index] = banks[index].renderAllLayers(render)
        }
        val entries = entries()
        return patches.mapIndexed { index, hard ->
            val samples = requireNotNull(audio[index])
            ArrangedPad(samples.last(), DrumClass.TONAL, recipe = PadRecipe(patch = banks[index].hardPatch).toJsonValue(),
                oneShot = true, softVariants = samples.dropLast(1), displayName = hard.name, muteGroup = 0,
                layerNames = LAYER_NAMES,
                source = linkedMapOf("engine" to NacrePatch.ENGINE, "voice" to hard.voice.name,
                    "preset" to entries[index].preset, "pitchMidi" to hard.midi.toString(), "seed" to hard.seed.toString(),
                    "model" to hard.model.toString(), SOURCE_KEY to banks[index].toJsonText()),
            )
        }
    }
}
