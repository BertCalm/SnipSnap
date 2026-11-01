package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Real saved MATCH patches, with the accepted aligned render pinned independently. */
object FerryMatchAuditionGenerator {
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
        val output = File(args.firstOrNull() ?: File(repository, "testkit/ferry-match-audition").path).absoluteFile
        require(output.canonicalFile != repository.canonicalFile)
        output.mkdirs()
        val previous = Json.parse(File(repository, "reference/ferry/kotlin-port-measurements.json").readText()).obj()
            .getValue("entries").arr().associate { entry ->
                val row = entry.obj()
                row.getValue("id").str() to row.getValue("native_wav_sha256").str()
            }
        val entries = mutableListOf<Map<String, Any?>>()
        for ((register, midi) in linkedMapOf("low" to 36, "middle" to 48, "high" to 72)) {
            for ((speed, handoff) in linkedMapOf("slow" to .25f, "standard" to .5f, "fast" to 1f)) {
                for ((step, match) in listOf(1f, .75f, .5f, .25f, 0f).withIndex()) {
                    val id = "$register-$speed-$step"
                    val patch = FerryPatch("Passage $register $speed match $match", midi = midi,
                        macros = Ferry.defaults() + mapOf("HANDOFF" to handoff, "MATCH" to match))
                    Ferry.clearCache()
                    val coldStart = System.nanoTime()
                    val snip = patch.render()
                    val coldMs = (System.nanoTime() - coldStart) / 1e6
                    val warm = List(3) { val start = System.nanoTime(); patch.render(); (System.nanoTime() - start) / 1e6 }.sorted()[1]
                    val audio = File(output, "audio/$id.wav")
                    WavWriter.write(audio, snip, WavWriter.BitDepth.PCM_16)
                    if (match == 1f) check(sha(audio) == previous.getValue("$register-$speed")) {
                        "Approved aligned PCM changed: $id"
                    }
                    val recipe = File(output, "recipes/$id.json").also { it.parentFile.mkdirs(); it.writeText(patch.toJsonText()) }
                    check(Patches.fromJsonText(recipe.readText()).render().samples.contentEquals(snip.samples)) { "Replay changed: $id" }
                    val plan = Ferry.plan(midi, handoff, match)
                    val hz = 440 * 2.0.pow((midi - 69) / 12.0)
                    val pitches = listOf(.05f, 1.3f, 3.2f).map { time ->
                        val pitch = checkNotNull(Pitch.detect(snip, time)) { "$id missing pitch at $time" }
                        mapOf("seconds" to time, "cents" to 1200 * ln(pitch.hz / hz) / ln(2.0), "confidence" to pitch.confidence)
                    }
                    val initial = Ferry.initial()
                    val peakB = (1..40).maxOf { tenth ->
                        val state = plan.state(initial, tenth * Ferry.INTERNAL_RATE / 10)
                        (5..7).sumOf { state.first[it].pow(2) + state.second[it].pow(2) }
                    }
                    entries += linkedMapOf(
                        "id" to id, "register" to register, "midi" to midi, "speed" to speed,
                        "handoff" to handoff, "match" to match, "upper_B_detune_cents" to Ferry.mistuneCents(match),
                        "model" to patch.model, "audio" to "audio/$id.wav", "recipe" to "recipes/$id.json",
                        "audio_sha256" to sha(audio), "approved_aligned_pcm_unchanged" to if (match == 1f) true else null,
                        "cold_ms" to coldMs, "warm_ms" to warm, "peak" to snip.samples.maxOf { abs(it) },
                        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size,
                        "loudness" to Loudness.of(snip), "pitch_windows" to pitches,
                        "coupling_root_shifts_cents" to plan.eigenvalues.take(4).map { omega ->
                            1200 * ln(omega / (2 * PI * hz)) / ln(2.0)
                        }, "root_cap_scale" to plan.model.capScale, "peak_acquired_upper_B_energy" to peakB,
                    )
                    println("FERRY MATCH $id: %.1f ms warm, B upper energy %.4f".format(warm, peakB))
                }
            }
        }
        File(output, "manifest.json").writeText(Json.write(json(linkedMapOf(
            "title" to "Ferry MATCH listening", "model" to Ferry.MODEL_VERSION,
            "base_commit" to "a5caac7fd6c8a2e6d09ec740c60fb995c6bef368", "entries" to entries,
            "heard" to listOf("Ferry first listen: Yes, clearly.", "Ferry second listen: Clear throughout.",
                "Ferry Kotlin listen: Same character. (2026-10-30)"),
            "pending" to listOf("MATCH listening approval", "Phone timing", "SPAN/LOSS/BODY", "HOLD", "Production tail"),
            "timing_scope" to "Complete four-second renders including house output stage; server JVM, not phone timing.",
            "why" to listOf("MATCH changes B's upper-mode tuning, so the graph determines how much energy those modes acquire.",
                "The aligned setting preserves all nine accepted native PCM renders exactly.",
                "All fundamentals retain the played note; their full-network coupling shifts remain bounded."),
        ))))
        File(repository, "design/ferry-match-audition/index.html").copyTo(File(output, "index.html"), overwrite = true)
        println("Wrote ${entries.size} actual-engine MATCH comparisons and recipes to $output")
    }
}
