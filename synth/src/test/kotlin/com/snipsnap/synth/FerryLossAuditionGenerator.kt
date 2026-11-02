package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Saved Kotlin LOSS examples; physical damping changes, not a scheduled output fade. */
object FerryLossAuditionGenerator {
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported manifest value ${value.javaClass}")
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @JvmStatic
    fun main(args: Array<String>) {
        val repository = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val output = File(args.firstOrNull() ?: File(repository, "testkit/ferry-loss-audition").path).absoluteFile
        require(output.canonicalFile != repository.canonicalFile)
        output.mkdirs()
        val prior = Json.parse(File(repository, "reference/ferry/kotlin-port-measurements.json").readText()).obj()
            .getValue("entries").arr().associate { raw -> raw.obj().let { it.getValue("id").str() to it.getValue("native_wav_sha256").str() } }
        val entries = mutableListOf<Map<String, Any?>>()
        for ((register, midi) in linkedMapOf("low" to 36, "middle" to 48, "high" to 72)) {
            for ((character, loss) in linkedMapOf("reference" to .5f, "dry" to 1f, "bloom" to 0f)) {
                val id = "$register-$character"
                val patch = FerryPatch("Passage $register $character", midi = midi, macros = Ferry.defaults() + ("LOSS" to loss))
                Ferry.clearCache()
                val start = System.nanoTime()
                val snip = patch.render()
                val cold = (System.nanoTime() - start) / 1e6
                val warm = List(3) { val t = System.nanoTime(); patch.render(); (System.nanoTime() - t) / 1e6 }.sorted()[1]
                val audio = File(output, "loss-audio/$id.wav")
                WavWriter.write(audio, snip, WavWriter.BitDepth.PCM_16)
                if (loss == .5f) check(sha(audio) == prior.getValue("$register-standard")) { "Accepted default PCM changed: $id" }
                val recipe = File(output, "loss-recipes/$id.json").also { it.parentFile.mkdirs(); it.writeText(patch.toJsonText()) }
                check(Patches.fromJsonText(recipe.readText()).render().samples.contentEquals(snip.samples)) { "Recipe replay changed: $id" }
                val seconds = snip.frameCount.toDouble() / Dsp.RATE
                val root = 440 * 2.0.pow((midi - 69) / 12.0)
                val pitches = listOf(.05f, (seconds * .325).toFloat(), (seconds * .8).toFloat()).map { from ->
                    val p = checkNotNull(Pitch.detect(snip, from)) { "Missing root at $id/$from" }
                    mapOf("from_seconds" to from, "cents" to 1200 * ln(p.hz / root) / ln(2.0), "confidence" to p.confidence)
                }
                val plan = Ferry.plan(midi, .5f, 1f, loss)
                val state = plan.state(Ferry.initial(), Ferry.INTERNAL_RATE)
                val e0 = Ferry.initial().sumOf { it * it }
                val e1 = state.first.indices.sumOf { state.first[it].pow(2) + state.second[it].pow(2) }
                entries += linkedMapOf(
                    "id" to id, "register" to register, "midi" to midi, "loss" to loss,
                    "loss_scale" to Ferry.lossScale(loss), "seconds" to seconds, "frames" to snip.frameCount,
                    "model" to patch.model, "audio" to "loss-audio/$id.wav", "recipe" to "loss-recipes/$id.json",
                    "audio_sha256" to sha(audio), "approved_default_unchanged" to if (loss == .5f) true else null,
                    "cold_ms" to cold, "warm_ms" to warm, "peak" to snip.peak(),
                    "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size,
                    "loudness" to Loudness.of(snip), "classifier" to Classifier.classify(snip).drumClass.name,
                    "pitch_windows" to pitches, "raw_energy_fraction_after_one_second" to e1 / e0,
                )
                println("FERRY LOSS $id: %.2f seconds, %.1f ms warm, E(1)/E(0) %.4f".format(seconds, warm, e1 / e0))
            }
        }
        File(output, "manifest.json").writeText(Json.write(json(linkedMapOf(
            "title" to "Ferry LOSS listening", "model" to Ferry.MODEL_VERSION,
            "base_commit" to "e4e7b29320da3af052715268319aeff1c2b1514d", "entries" to entries,
            "heard" to listOf("Ferry first listen: Yes, clearly.", "Ferry second listen: Clear throughout.",
                "Ferry Kotlin listen: Same character. (2026-10-30)", "Ferry MATCH listen: Clear change, note stays clear. (2026-11-01)"),
            "pending" to listOf("LOSS listening approval", "Natural tail termination", "SPAN/BODY", "HOLD", "Phone/MPC acceptance"),
            "tail_scope" to "Bounded 1–8 second observation windows with the existing 4 ms house fade; not a claim of physical silence.",
            "timing_scope" to "Complete server-JVM render including output conditioning; phone timings unmeasured.",
        ))))
        File(repository, "design/ferry-loss-audition/index.html").copyTo(File(output, "index.html"), overwrite = true)
        println("Wrote ${entries.size} actual-engine LOSS clips and replayable recipes to $output")
    }
}
