package com.snipsnap.app

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Skirl
import com.snipsnap.synth.SkirlPatch
import com.snipsnap.synth.SkirlVoice
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in render costs on Android's actual runtime. No activity, playback or
 * storage work is included in the timer. This is a debug-build diagnostic,
 * not a release benchmark or a measurement of UI-to-sound latency.
 * See docs/SKIRL_ANDROID_BENCHMARK.md for the exact invocation and limitations.
 */
@RunWith(AndroidJUnit4::class)
class SkirlRenderBenchmark {
    private data class Input(val midi: Int = Skirl.DEFAULT_MIDI,
        val edits: Map<String, Float> = emptyMap())
    private data class Workload(val name: String, val inputs: List<Input>)

    @Test
    fun measureRenderCosts() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e skirlBenchmark true", args.getString("skirlBenchmark") == "true")
        val source = args.getString("skirlSourceCommit") ?: "unspecified"
        require(source == "unspecified" || source.matches(Regex("[0-9a-fA-F]{7,40}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val started = Instant.now().toString()
        val thermalBefore = power.currentThermalStatus
        val results = mutableListOf<Map<String, Any?>>()

        for (voice in SkirlVoice.entries) for (workload in workloads()) {
            // The first observation is deliberately not called "cold": earlier
            // cases may already have populated mechanics or runtime caches.
            val first = renderAndConsume(voice, workload.inputs.first())
            repeat(WARMUP_RENDERS) { index ->
                renderAndConsume(voice, workload.inputs[index % workload.inputs.size])
            }
            val measured = (0 until MEASURED_RENDERS).map { index ->
                renderAndConsume(voice, workload.inputs[index % workload.inputs.size])
            }
            val times = measured.map { it.getValue("totalMillis") as Double }.sorted()
            results += linkedMapOf(
                "voice" to voice.name, "workload" to workload.name,
                "firstObserved" to first, "warmupRenders" to WARMUP_RENDERS,
                "measuredRenders" to measured, "medianMillis" to times[times.size / 2],
                "minMillis" to times.first(), "maxMillis" to times.last(),
                "targetMillis" to 50, "medianMeetsTarget" to (times[times.size / 2] <= 50.0),
                "thermalStatusAfter" to power.currentThermalStatus,
            )
        }

        val report = linkedMapOf(
            "schema" to 1, "sourceCommitSuppliedByRunner" to source,
            "startedAtUtc" to started, "finishedAtUtc" to Instant.now().toString(),
            "engineModel" to Skirl.MODEL_VERSION, "buildType" to "debug",
            "manufacturer" to Build.MANUFACTURER, "deviceModel" to Build.MODEL,
            "hardware" to Build.HARDWARE, "supportedAbis" to Build.SUPPORTED_ABIS.toList(),
            "androidRelease" to Build.VERSION.RELEASE, "androidApi" to Build.VERSION.SDK_INT,
            "runtime" to System.getProperty("java.vm.version"),
            "processors" to Runtime.getRuntime().availableProcessors(),
            "thermalStatusBefore" to thermalBefore, "thermalStatusAfter" to power.currentThermalStatus,
            "cachePolicy" to "Production caches are neither cleared nor bypassed. Edited inputs cycle through a bounded set; inspect each sample rather than assuming every edit is a cache miss.",
            "timer" to "System.nanoTime around Skirl.render only; fingerprint, scans, JSON, classification and file I/O follow the timer.",
            "limitations" to "Debug instrumentation, fixed case order, 3 warmups and 7 samples. No cold-process startup, UI, playback, release-build or physical-device claim follows from this file alone. Record whether the selected device is a real phone.",
            "results" to results,
        )
        val file = File(context.filesDir, REPORT_NAME)
        file.writeText(Json.write(jsonValue(report)) + "\n")
        Log.i("SkirlBenchmark", "Wrote ${file.name}; ${results.size} workloads. Retrieve with run-as; see docs/SKIRL_ANDROID_BENCHMARK.md")
    }

    private fun renderAndConsume(voice: SkirlVoice, input: Input): Map<String, Any?> {
        val macros = Skirl.defaults(voice) + input.edits
        val began = System.nanoTime()
        val snip = Skirl.render(voice, macros, midi = input.midi, velocity = 1f)
        val elapsedMillis = (System.nanoTime() - began) / 1e6
        // Consume all returned samples after the timer, retaining a comparable
        // fingerprint without charging validation or hashing to the renderer.
        check(snip.channels == 1 && snip.sampleRate == Skirl.RATE && snip.samples.isNotEmpty())
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4)
        var peak = 0f
        for (sample in snip.samples) {
            check(sample.isFinite()) { "Nonfinite SKIRL output" }
            peak = maxOf(peak, kotlin.math.abs(sample))
            val bits = sample.toRawBits()
            for (b in 0..3) bytes[b] = (bits ushr (8 * b)).toByte()
            digest.update(bytes)
        }
        check(peak <= 1f) { "SKIRL peak above full scale: $peak" }
        return linkedMapOf(
            "totalMillis" to elapsedMillis,
            "recipe" to SkirlPatch("Android benchmark", voice, macros, input.midi, 1f).toJsonValue(),
            "frameCount" to snip.frameCount, "sampleRate" to snip.sampleRate,
            "peak" to peak, "float32LittleEndianSha256" to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
            "declaredClass" to Skirl.drumClassFor(voice, macros).name,
        )
    }

    private fun workloads(): List<Workload> = listOf(
        Workload("same-default-c3", listOf(Input())),
        Workload("pitch-only-c2-c6", listOf(Input(midi = 36), Input(midi = 84))),
        Workload("edge-plate-edits", listOf(.1f, .3f, .5f, .7f, .9f).map {
            Input(edits = mapOf("EDGE" to it, "PLATE" to (1f - it)))
        }),
        Workload("mechanics-edits", listOf(.1f, .3f, .5f, .7f, .9f).map {
            Input(edits = mapOf("LEAN" to it, "BRAKE" to (1f - it), "COLLAPSE" to it))
        }),
        Workload("hold-half-c3", listOf(Input(edits = mapOf("HOLD" to .5f)))),
        Workload("hold-full-c3", listOf(Input(edits = mapOf("HOLD" to 1f)))),
    )

    private fun jsonValue(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Boolean -> JsonValue.Bool(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { (key, item) -> key.toString() to jsonValue(item) })
        is List<*> -> JsonValue.Arr(value.map(::jsonValue))
        else -> error("Unsupported benchmark JSON value: ${value.javaClass.name}")
    }

    companion object {
        private const val WARMUP_RENDERS = 3
        private const val MEASURED_RENDERS = 7
        private const val REPORT_NAME = "skirl-render-benchmark.json"
    }
}
