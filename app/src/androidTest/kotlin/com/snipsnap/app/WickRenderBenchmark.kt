package com.snipsnap.app

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPatch
import com.snipsnap.synth.WickVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in Android measurements through the production renderer; no playback or disk IO is timed. */
@RunWith(AndroidJUnit4::class)
class WickRenderBenchmark {
    @Test fun record_finite_and_held_voices_and_all_three_rattle_bodies() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Enable with -e wickProfile true", arguments.getString("wickProfile") == "true")
        val runs = arguments.getString("wickRuns")?.toIntOrNull()?.coerceIn(1, 5) ?: 3
        val sources = WickVoice.entries.map { voice ->
            WickPatch(voice.name, voice, Wick.defaults(voice))
        } + listOf(Wick.RattleSource.HOLLOW_REED, Wick.RattleSource.RUBBED_GLASS).map { body ->
            WickPatch(body.name, WickVoice.RATTLE, Wick.defaults(WickVoice.RATTLE), rattleSource = body)
        }
        val cases = sources.flatMap { source ->
            listOf(0f, 1f).map { hold -> source.copy(
                name = "${source.name} ${if (Wick.isLoop(hold)) "HOLD" else "FINITE"}",
                macros = source.macros + ("HOLD" to hold),
            ) }
        }.map { patch ->
            // Untimed class loading/JIT warmup also supplies the independent
            // deterministic reference; each timed call computes fresh audio.
            val reference = patch.render()
            assertTrue("${patch.name} has audio", reference.samples.any { it != 0f })
            assertTrue("${patch.name} is finite", reference.samples.all { it.isFinite() })
            val milliseconds = (1..runs).map {
                val start = System.nanoTime()
                val rendered = patch.render()
                val elapsed = (System.nanoTime() - start) / 1_000_000.0
                assertEquals(reference.frameCount, rendered.frameCount)
                assertTrue("${patch.name} deterministic recall", reference.samples.contentEquals(rendered.samples))
                elapsed
            }
            val ordered = milliseconds.sorted()
            val median = if (ordered.size % 2 == 0)
                (ordered[ordered.size / 2 - 1] + ordered[ordered.size / 2]) / 2
                else ordered[ordered.size / 2]
            linkedMapOf(
                "case" to patch.name, "patch" to patch.toJsonValue(),
                "held" to Wick.isLoop(patch.macros.getValue("HOLD")),
                "frames" to reference.frameCount, "sampleRate" to reference.sampleRate,
                "pcmHash" to reference.samples.contentHashCode(),
                "runsMs" to milliseconds, "medianMs" to median, "maxMs" to ordered.last(),
            )
        }
        val report = linkedMapOf(
            "version" to 1, "runtime" to "Android", "manufacturer" to Build.MANUFACTURER,
            "device" to Build.DEVICE, "model" to Build.MODEL, "sdk" to Build.VERSION.SDK_INT,
            "fingerprint" to Build.FINGERPRINT, "abis" to Build.SUPPORTED_ABIS.toList(),
            "method" to "One untimed warmup per case, followed by fresh production renders. Monotonic wall time excludes assertions, debounce, playback and disk IO. Each case has at most five measured runs. No device latency threshold is inferred.",
            "cases" to cases,
        )
        val text = Json.write(json(report))
        val file = File(instrumentation.targetContext.cacheDir, "wick-render-profile.json")
        file.writeText(text)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nWICK PROFILE\n$text\n") })
        assertTrue(file.length() > 0)
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Boolean -> JsonValue.Bool(value)
        is List<*> -> JsonValue.Arr(value.map(::json))
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        else -> error("unsupported profile value")
    }
}
