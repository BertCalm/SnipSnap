package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Listening Desk round nacre-strike-1: for each saved model-4 CONTACT (.35 and .80) and each policy
 * (v1, v2 variant A, v2 variant B), one ladder WAV of C3 Soft, Medium, Hard at the recipes' own
 * delivered levels (fixed sampling gains, no normalisation), plus measurements.json.
 *
 * Each rung is the first [RUNG_SECONDS] of the six-second layer with a [FADE_SECONDS] cosine fade at
 * its cut (the cut would otherwise click), followed by [GAP_SECONDS] of silence. Measurements are of
 * the complete, unfaded layers.
 */
object NacreStrikeAuditionGenerator {
    private const val RUNG_SECONDS = 2.0
    private const val GAP_SECONDS = .3
    private const val FADE_SECONDS = .03

    private data class Version(val tag: String, val label: String, val render: (NacrePatch) -> List<Snip>,
        val shapes: List<NacreStrikeShape>)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one output directory." }
        val root = File(args.firstOrNull() ?: "testkit/nacre-strike-audition").absoluteFile
        root.mkdirs()
        val versions = listOf(
            Version("v1", "v1 (${NacreContactVelocityRecipe.POLICY})",
                { NacreContactVelocityRecipes.recipeFor(it).renderAllLayers() }, List(3) { NacreStrikeShape.DEFAULT }),
        ) + NacreStrikeVariant.entries.map { variant ->
            Version(variant.id.lowercase(), "${variant.id} (${NacreContactStrikeRecipe.POLICY}, ${variant.name.lowercase()})",
                { NacreContactStrikeRecipes.recipeFor(it, variant).renderAllLayers() }, variant.shapes)
        }
        val results = ArrayList<JsonValue>()
        for (hard in NacrePresets.all().filter { it.model == NacreContactVelocityRecipes.MODEL }) {
            require(hard.midi == 48 && hard.velocity == 1f) { "the audition plays the saved C3 hard reference" }
            val contact = hard.macros.getValue(NacreModels.CONTACT_MACRO)
            val contactTag = "%03d".format(java.util.Locale.ROOT, (contact * 100).roundToInt())
            for (version in versions) {
                val layers = version.render(hard)
                val hardSnip = layers.last()
                val hardMeasure = NacreStrikeMeasure.measure(hardSnip, hardSnip)
                val file = "nacre-$contactTag-${version.tag}-ladder.wav"
                WavWriter.write(File(root, file), ladder(layers), WavWriter.BitDepth.PCM_16)
                for ((index, snip) in layers.withIndex()) {
                    val m = NacreStrikeMeasure.measure(snip, hardSnip)
                    val shape = version.shapes[index]
                    results += JsonValue.Obj(linkedMapOf(
                        "contact" to num(contact.toDouble()), "version" to JsonValue.Str(version.tag),
                        "policy" to JsonValue.Str(version.label),
                        "layer" to JsonValue.Str(NacreContactVelocityRecipes.LAYER_NAMES[index]),
                        "ladderFile" to JsonValue.Str(file),
                        "ladderStartSeconds" to num(index * (RUNG_SECONDS + GAP_SECONDS)),
                        "strikeWidthMs" to num(shape.widthMs), "strikePickBrightness" to num(shape.pickBrightness),
                        "strikeToneShift" to num(shape.toneShift),
                        "highBandAbove1kHz0to300msDb" to num(m.highBandDb),
                        "highBandChangeVsHardDb" to num(m.highBandDb - hardMeasure.highBandDb),
                        "centroid0to300msHz" to num(m.centroidHz),
                        "residualVsHardDb" to num(if (index == 2) Double.NEGATIVE_INFINITY else m.residualVsHardDb),
                        "residualVsHard0to300msDb" to num(if (index == 2) Double.NEGATIVE_INFINITY else m.residualVsHard300msDb),
                        "pitchHz" to num(m.pitchHz),
                        "pitchCentsVsHard" to num(NacreStrikeMeasure.cents(m.pitchHz, hardMeasure.pitchHz)),
                        "peak" to num(m.peak), "rmsDb" to num(m.rmsDb),
                    ))
                }
            }
        }
        File(root, "measurements.json").writeText(Json.write(JsonValue.Arr(results)) + "\n")
        println("Wrote ${versions.size * 2} ladders and measurements.json to $root")
    }

    /** Soft, Medium, Hard rungs at their delivered levels; only the cut is faded. */
    private fun ladder(layers: List<Snip>): Snip {
        val rate = layers.first().sampleRate
        val rung = (RUNG_SECONDS * rate).roundToInt()
        val gap = (GAP_SECONDS * rate).roundToInt()
        val fade = (FADE_SECONDS * rate).roundToInt()
        val out = FloatArray(layers.size * (rung + gap))
        for ((index, snip) in layers.withIndex()) {
            require(snip.channels == 1 && snip.sampleRate == rate && snip.frameCount >= rung)
            val offset = index * (rung + gap)
            for (i in 0 until rung) {
                val tail = rung - 1 - i
                val gain = if (tail >= fade) 1.0 else .5 - .5 * cos(PI * tail / fade)
                out[offset + i] = (snip.samples[i] * gain).toFloat()
            }
        }
        return Snip(out, 1, rate)
    }

    /** JSON numbers rounded for reading; a removed residual (Hard against itself) is written as null. */
    private fun num(v: Double): JsonValue =
        if (v.isInfinite()) JsonValue.Null else JsonValue.Num(Math.round(v * 10000.0) / 10000.0)
}
