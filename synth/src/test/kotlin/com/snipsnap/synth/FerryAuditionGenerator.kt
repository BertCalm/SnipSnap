package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Actual engine renders, approved references, complete recipes and measured timing. */
object FerryAuditionGenerator {
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
    private fun peak(samples: FloatArray) = samples.maxOf { abs(it) }
    private fun matched(snip: Snip): Snip {
        val out = snip.samples.copyOf()
        Dsp.levelTo(out, snip.sampleRate, Dsp.MELODIC_LOUDNESS_TARGET, ceiling = .95f)
        return Snip(out, 1, Dsp.RATE)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val repository = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val output = File(args.firstOrNull() ?: File(repository, "testkit/ferry-audition").path).absoluteFile
        require(output.canonicalFile != repository.canonicalFile)
        output.mkdirs()
        val entries = mutableListOf<Map<String, Any?>>()
        val registers = linkedMapOf("low" to 36, "middle" to 48, "high" to 72)
        val speeds = linkedMapOf("slow" to .25f, "standard" to .5f, "fast" to 1f)
        for ((register, midi) in registers) for ((speed, handoff) in speeds) {
            val id = "$register-$speed"
            val patch = FerryPatch("Passage $register $speed", macros = Ferry.defaults(model = 1) + ("HANDOFF" to handoff), midi = midi, model = 1)
            Ferry.clearCache()
            val coldStart = System.nanoTime()
            val native = patch.render()
            val coldMs = (System.nanoTime() - coldStart) / 1e6
            val warmTimes = List(3) {
                val start = System.nanoTime(); patch.render(); (System.nanoTime() - start) / 1e6
            }
            val referenceFile = File(repository, "reference/ferry/approved-round2/$id.wav")
            check(referenceFile.isFile) { "Missing approved reference $referenceFile" }
            val reference = WavReader.read(referenceFile)
            val recipe = File(output, "recipes/$id.json").also { it.parentFile.mkdirs(); it.writeText(patch.toJsonText()) }
            check(Patches.fromJsonText(recipe.readText()).render().samples.contentEquals(native.samples)) { "Recipe replay differs: $id" }
            val nativeFile = File(output, "native/$id.wav")
            WavWriter.write(nativeFile, native, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(output, "comparison/kotlin-$id.wav"), matched(native), WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(output, "comparison/reference-$id.wav"), matched(reference), WavWriter.BitDepth.PCM_16)
            val plan = Ferry.plan(midi, handoff)
            val hz = 440 * 2.0.pow((midi - 69) / 12.0)
            val pitches = listOf(.05f, 1.3f, 3.2f).map { start ->
                val result = Pitch.detect(native, start)
                mapOf("start_seconds" to start, "hz" to result?.hz, "confidence" to result?.confidence,
                    "cents" to result?.hz?.let { 1200 * ln(it / hz) / ln(2.0) })
            }
            entries += linkedMapOf(
                "id" to id, "register" to register, "speed" to speed, "midi" to midi, "handoff" to handoff,
                "native" to "native/$id.wav", "kotlin_comparison" to "comparison/kotlin-$id.wav",
                "approved_comparison" to "comparison/reference-$id.wav", "recipe" to "recipes/$id.json",
                "approved_source_sha256" to sha(referenceFile), "native_wav_sha256" to sha(nativeFile),
                "cold_render_ms" to coldMs, "warm_render_ms" to warmTimes.sorted()[1],
                "peak" to peak(native.samples), "dc" to native.samples.sumOf { it.toDouble() } / native.samples.size,
                "loudness" to Loudness.of(native), "classification" to Classifier.classify(native).drumClass.name,
                "fundamental_cents" to plan.eigenvalues.take(4).map { 1200 * ln(it / (2 * PI * hz)) / ln(2.0) },
                "cap_scale" to plan.model.capScale, "pitch_windows" to pitches,
                "eigen_residual" to plan.eigenResidual, "orthogonality_error" to plan.orthogonalityError,
            )
            println("FERRY $id: cold %.1f ms, warm %.1f ms".format(coldMs, warmTimes.sorted()[1]))
        }
        File(output, "manifest.json").writeText(Json.write(json(linkedMapOf(
            "title" to "Ferry: Kotlin port", "model" to 1, "sample_rate" to Dsp.RATE,
            "entries" to entries, "heard" to listOf("Python first listen: Yes, clearly.", "Python second listen: Clear throughout.", "Kotlin listen: Same character. (2026-10-30)"),
            "pending" to listOf("Phone render timings", "MATCH listening", "SPAN/LOSS/BODY", "HOLD", "Production tail cutoff"),
            "timing_scope" to "Complete render including house output conditioning; warmed desktop JVM, not phone timing.",
        ))))
        val template = File(repository, "design/ferry-audition/index.html")
        if (template.isFile) template.copyTo(File(output, "index.html"), overwrite = true)
        println("Wrote ${entries.size} Kotlin renders, matched reference comparisons and recipes to $output")
    }
}
