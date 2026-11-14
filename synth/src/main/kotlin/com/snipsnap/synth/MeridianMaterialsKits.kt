package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlin.math.abs

/** One independent source performance and its exact ordinary kit-WAV binding. */
data class MeridianMaterialsLayer(
    val name: String,
    val velocityStart: Int,
    val velocityEnd: Int,
    val nativeVelocity: Float,
    val recipe: PadRecipe,
    val wavSha256: String,
)

/**
 * Complete native-layer replay, carried by ordinary kit source history. This
 * does not replace PadRecipe with a generic independent-source layer editor.
 */
class MeridianMaterialsVelocityRecipe(
    val slot: Int,
    factoryPatch: MeridianPatch,
    layers: List<MeridianMaterialsLayer>,
) {
    val factoryPatch: MeridianPatch = freeze(factoryPatch)
    val layers: List<MeridianMaterialsLayer> = Collections.unmodifiableList(layers.map { layer ->
        val patch = requireNotNull(layer.recipe.patch as? MeridianPatch)
        layer.copy(recipe = layer.recipe.copy(patch = freeze(patch)))
    })

    init {
        require(slot in 1..16)
        val entry = MeridianMaterialsKits.entries()[slot - 1]
        val expectedFactory = MeridianMaterialsFactoryV1.forName(entry.factoryPreset)
        require(this.factoryPatch == expectedFactory) { "MERIDIAN materials source must retain the original complete factory patch" }
        require(this.layers.size == 3)
        val hard = expectedFactory.copy(midi = entry.midi)
        for ((index, layer) in this.layers.withIndex()) {
            require(layer.name == MeridianMaterialsKits.LAYER_NAMES[index] &&
                layer.velocityStart == MeridianMaterialsKits.VELOCITY_STARTS[index] &&
                layer.velocityEnd == MeridianMaterialsKits.VELOCITY_ENDS[index] &&
                layer.nativeVelocity == MeridianMaterialsKits.VELOCITIES[index]) { "MERIDIAN materials touch policy changed" }
            require(layer.recipe == MeridianKits.sourceRecipe(hard.copy(velocity = layer.nativeVelocity))) {
                "MERIDIAN materials layer changed its source, register, model, seed, touch or capture ending"
            }
            require(layer.wavSha256.matches(Regex("[0-9a-f]{64}"))) { "MERIDIAN materials layer needs its exact WAV hash" }
        }
        require((this.layers.last().recipe.patch as MeridianPatch) == hard)
    }

    val hardPatch: MeridianPatch get() = layers.last().recipe.patch as MeridianPatch

    fun toJsonValue(): JsonValue.Obj {
        val entry = MeridianMaterialsKits.entries()[slot - 1]
        return JsonValue.Obj(linkedMapOf(
            "schema" to num(1), "engine" to JsonValue.Str(MeridianPatch.ENGINE), "policy" to JsonValue.Str(POLICY),
            "slot" to num(slot), "factoryPatch" to factoryPatch.toJsonValue(),
            "factoryPatchSha256" to JsonValue.Str(hashText(factoryPatch.toJsonText())),
            "nativeMidi" to num(entry.midi), "registerRole" to JsonValue.Str(entry.registerRole),
            "layers" to JsonValue.Arr(layers.map { layer -> JsonValue.Obj(linkedMapOf(
                "name" to JsonValue.Str(layer.name), "velocityStart" to num(layer.velocityStart),
                "velocityEnd" to num(layer.velocityEnd), "nativeVelocity" to num(layer.nativeVelocity),
                "recipe" to layer.recipe.toJsonValue(),
                "recipeSha256" to JsonValue.Str(hashText(layer.recipe.toJsonText())),
                "wavSha256" to JsonValue.Str(layer.wavSha256),
            )) }),
        ))
    }

    fun toJsonText(): String = Json.write(toJsonValue())

    /** Rebuild all three independent performances; generic pad editing still opens the main recipe. */
    fun replay(render: (PadRecipe) -> Snip = { it.render() }): List<Snip> = layers.map { layer ->
        val snip = MeridianMaterialsKits.renderChecked(layer.recipe, render)
        require(MeridianMaterialsKits.wavHash(snip) == layer.wavSha256) { "MERIDIAN materials saved layer no longer reproduces its WAV" }
        snip
    }

    companion object {
        const val SOURCE_KEY = "meridianNativeVelocityLayers"
        const val POLICY = "absolute-native-touch-materials-v1"
        private fun num(n: Number) = JsonValue.Num(n.toDouble())
        private fun freeze(patch: MeridianPatch) = patch.copy(macros = Collections.unmodifiableMap(LinkedHashMap(patch.macros)))
        private fun hashText(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun fromJsonText(text: String): MeridianMaterialsVelocityRecipe = fromJsonValue(Json.parse(text))
        fun fromJsonValue(value: JsonValue): MeridianMaterialsVelocityRecipe {
            val o = value.obj()
            require(o.keys == setOf("schema", "engine", "policy", "slot", "factoryPatch", "factoryPatchSha256", "nativeMidi", "registerRole", "layers"))
            require(o.getValue("schema").int() == 1 && o.getValue("engine").str() == MeridianPatch.ENGINE &&
                o.getValue("policy").str() == POLICY)
            val source = MeridianPatch.fromJsonValue(o.getValue("factoryPatch")) as MeridianPatch
            require(o.getValue("factoryPatch") == source.toJsonValue()) { "Unsupported MERIDIAN materials factory fields" }
            require(o.getValue("factoryPatchSha256").str() == hashText(source.toJsonText()))
            val layers = o.getValue("layers").arr().map { raw ->
                val row = raw.obj()
                require(row.keys == setOf("name", "velocityStart", "velocityEnd", "nativeVelocity", "recipe", "recipeSha256", "wavSha256"))
                val recipe = PadRecipe.fromJsonValue(row.getValue("recipe"))
                require(row.getValue("recipe") == recipe.toJsonValue()) { "Unsupported MERIDIAN materials layer fields" }
                require(row.getValue("recipeSha256").str() == hashText(recipe.toJsonText()))
                MeridianMaterialsLayer(row.getValue("name").str(), row.getValue("velocityStart").int(),
                    row.getValue("velocityEnd").int(), row.getValue("nativeVelocity").num().toFloat(), recipe,
                    row.getValue("wavSha256").str())
            }
            val result = MeridianMaterialsVelocityRecipe(o.getValue("slot").int(), source, layers)
            require(value == result.toJsonValue()) { "Noncanonical or unsupported MERIDIAN materials metadata" }
            return result
        }
    }
}

/** Four existing voices/fourteen existing factories, with two explicit pad-only octave roles. */
object MeridianMaterialsKits {
    const val NAME = "SnipSnap Meridian Materials"
    const val STARTER_ID_V1 = "meridian-materials"
    const val BANK_VERSION = 1
    val VELOCITIES: List<Float> = Collections.unmodifiableList(listOf(.30f, .65f, 1f))
    val LAYER_NAMES: List<String> = Collections.unmodifiableList(listOf("Soft", "Medium", "Hard"))
    // KitAssembler's three drum-kit zones, not the separate held-keyboard windows.
    val VELOCITY_STARTS: List<Int> = Collections.unmodifiableList(listOf(1, 42, 85))
    val VELOCITY_ENDS: List<Int> = Collections.unmodifiableList(listOf(41, 84, 127))
    data class Entry(val factoryPreset: String, val midi: Int = 57, val registerRole: String = "FACTORY")

    private val VERSION_1_LAYOUT = Collections.unmodifiableList(listOf(
        Entry("FIRST ARC"), Entry("BACK ARC"), Entry("CLOSE GRAIN"), Entry("DEEP KNOCK"),
        Entry("STILL PAIR"), Entry("LONG TRAIL"), Entry("HARD GRAIN"), Entry("HOLLOW TONE"),
        Entry("BRIGHT EDGE"), Entry("HARD GRAIN", 45, "A2_OCTAVE"), Entry("HOLLOW TONE", 69, "A4_OCTAVE"),
        Entry("ROUND THREAD"), Entry("FINE STRAND"), Entry("GRAIN THREAD"), Entry("LANTERN TONE"), Entry("EDGE THREAD"),
    ))
    /** Permanent v1 slots. A future six-voice bank requires a separate ID/version. */
    fun entries(): List<Entry> = VERSION_1_LAYOUT
    fun factoriesV1(): List<MeridianPatch> = MeridianMaterialsFactoryV1.patches
    fun patches(): List<MeridianPatch> {
        return VERSION_1_LAYOUT.map { entry -> MeridianMaterialsFactoryV1.forName(entry.factoryPreset).copy(midi = entry.midi) }
    }

    /** Build one pad independently so bounded native proof need not render the other fifteen. */
    fun buildPad(slot: Int, render: (PadRecipe) -> Snip = { it.render() }): ArrangedPad {
        require(slot in 1..16)
        val entry = VERSION_1_LAYOUT[slot - 1]
        val factory = MeridianMaterialsFactoryV1.forName(entry.factoryPreset)
        val hard = factory.copy(midi = entry.midi)
        val recipes = VELOCITIES.map { v -> MeridianKits.sourceRecipe(hard.copy(velocity = v)) }
        val samples = recipes.map { renderChecked(it, render) }
        val bank = MeridianMaterialsVelocityRecipe(slot, factory, samples.mapIndexed { index, snip ->
            MeridianMaterialsLayer(LAYER_NAMES[index], VELOCITY_STARTS[index], VELOCITY_ENDS[index], VELOCITIES[index],
                recipes[index], wavHash(snip))
        })
        val held = Meridian.isLoop(hard.macros, hard.model)
        return ArrangedPad(samples.last(), Meridian.drumClassFor(hard.voice, hard.macros, hard.model),
            recipe = recipes.last().toJsonValue(), oneShot = true, softVariants = samples.dropLast(1),
            source = linkedMapOf("engine" to MeridianPatch.ENGINE, "voice" to hard.voice.name,
                "factoryPreset" to factory.name, "model" to hard.model.toString(), "seedContext" to hard.seedContext.toString(),
                "rootMidi" to entry.midi.toString(), "registerRole" to entry.registerRole,
                "nativeVelocityPolicy" to "ABSOLUTE_030_065_100", "playbackVelocityPolicy" to "KIT_PLAYER_GAIN_ONCE",
                "playback" to if (held) "FINITE_HELD_CAPTURE" else "FINITE_ONE_SHOT",
                "terminalFadeMs" to (recipes.last().terminalFadeMs?.toString() ?: "0"),
                MeridianMaterialsVelocityRecipe.SOURCE_KEY to bank.toJsonText()),
            displayName = if (entry.registerRole == "FACTORY") hard.name else "${hard.name} ${if (entry.midi == 45) "A2" else "A4"}",
            layerNames = LAYER_NAMES, muteGroup = 0)
    }

    fun build(render: (PadRecipe) -> Snip = { it.render() }): List<ArrangedPad?> =
        VERSION_1_LAYOUT.indices.map { index -> buildPad(index + 1, render) }

    internal fun renderChecked(recipe: PadRecipe, render: (PadRecipe) -> Snip): Snip {
        fun stop() { if (Thread.currentThread().isInterrupted) throw CancellationException("MERIDIAN materials kit cancelled") }
        stop()
        val snip = render(recipe)
        stop()
        require(snip.channels == 1 && snip.sampleRate == WavWriter.MPC_SAMPLE_RATE && snip.frameCount > 0 &&
            snip.samples.all { it.isFinite() && abs(it) <= 1f }) { "MERIDIAN materials sources must be complete finite mono 44.1kHz samples" }
        return snip
    }

    /** Same PCM24/no-smpl/no-extra-gain settings as KitAssembler; no extra native render or WAV array. */
    internal fun wavHash(snip: Snip): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(object : OutputStream() { override fun write(value: Int) {} }, digest).use {
            WavWriter.write(it, snip)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
