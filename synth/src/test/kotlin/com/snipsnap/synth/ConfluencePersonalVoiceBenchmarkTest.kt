package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Actual Kotlin production-call costs; timing is reported and never decides correctness. */
class ConfluencePersonalVoiceBenchmarkTest {
    @Test
    fun `report qualification cost and prepared cached render costs on licensed real vowel`() {
        val directory = listOf(File("../reference/fixtures/confluence"), File("reference/fixtures/confluence"))
            .firstOrNull { File(it, "vowel-male.wav").isFile } ?: error("real Confluence fixtures are required")
        val input = WavReader.read(File(directory, "vowel-male.wav"))
        val start = System.nanoTime()
        val prepared = ConfluencePersonalVoice.prepare(input)
        val prepareMs = (System.nanoTime() - start) / 1e6
        // Prepare already owns its source analysis. These production calls change the roster,
        // requested pitch and room without analyzing the capture again.
        ConfluencePersonalVoice.render(prepared, performers = 2)
        val cases = listOf(1 to 0, 2 to 0, 4 to 0, 1 to -2, 1 to 2).map { (count, semitones) ->
            val samples = List(3) {
                val before = System.nanoTime()
                val rendered = ConfluencePersonalVoice.render(prepared, count, semitones, room = .15f)
                val milliseconds = (System.nanoTime() - before) / 1e6
                assertTrue(rendered.snip.samples.all { it.isFinite() })
                milliseconds
            }
            JsonValue.Obj(mapOf(
                "performers" to JsonValue.Num(count.toDouble()), "semitones" to JsonValue.Num(semitones.toDouble()),
                "milliseconds" to JsonValue.Arr(samples.map { JsonValue.Num(it) }),
            ))
        }
        val report = JsonValue.Obj(mapOf(
            "scope" to JsonValue.Str("Actual Kotlin calls on this JVM; not a phone latency or listening result."),
            "javaVersion" to JsonValue.Str(System.getProperty("java.version")),
            "availableProcessors" to JsonValue.Num(Runtime.getRuntime().availableProcessors().toDouble()),
            "sourceSeconds" to JsonValue.Num(input.durationSeconds.toDouble()),
            "analysisKey" to JsonValue.Str(prepared.analysisKey),
            "prepareMilliseconds" to JsonValue.Num(prepareMs),
            "cachedAnalysisRenderCases" to JsonValue.Arr(cases),
        ))
        println("CONFLUENCE_PERSONAL_VOICE_BENCHMARK " + Json.write(report))
    }
}
