package com.snipsnap.app

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Libration
import com.snipsnap.synth.LibrationPresets
import com.snipsnap.synth.LibrationRenderSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in CPU measurements on the actual Android runtime. No playback or disk IO is timed. */
@RunWith(AndroidJUnit4::class)
class LibrationRenderBenchmark {
    @Test fun record_cold_physics_reuse_and_audio_recall() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Enable with -e librationProfile true",
            InstrumentationRegistry.getArguments().getString("librationProfile") == "true")
        val runs = InstrumentationRegistry.getArguments().getString("librationRuns")
            ?.toIntOrNull()?.coerceIn(1, 10) ?: 3
        fun timing(t: LibrationRenderSession.Timing) = linkedMapOf(
            "totalMs" to t.totalMilliseconds, "physicsMs" to t.physicsMilliseconds,
            "audioMs" to t.audioMilliseconds, "audioCacheHit" to t.audioCacheHit,
            "populationCacheHit" to t.populationCacheHit,
        )
        val cases = listOf("ROUND KEYS", "FINE KEYS", "OPEN EXIT").map { name ->
            val patch = LibrationPresets.all().single { it.name == name }
            // Warm class loading/JIT separately. Each measured cold run then starts
            // with empty caches; "warm" still computes every audio sample afresh.
            LibrationRenderSession(maxAudioBytes = 0).render(patch)
            val measurements = (1..runs).map {
                val session = LibrationRenderSession(maxAudioBytes = 0)
                val cold = session.renderTimed(patch)
                val warm = session.renderTimed(patch)
                assertFalse(cold.timing.audioCacheHit)
                assertFalse(cold.timing.populationCacheHit)
                assertFalse(warm.timing.audioCacheHit)
                assertTrue(warm.timing.populationCacheHit)
                assertTrue(cold.snip.samples.contentEquals(warm.snip.samples))
                val recallSession = LibrationRenderSession()
                recallSession.render(patch)
                val recall = recallSession.renderTimed(patch)
                assertTrue(recall.timing.audioCacheHit)
                linkedMapOf("cold" to timing(cold.timing), "physicsReused" to timing(warm.timing),
                    "audioRecalled" to timing(recall.timing))
            }
            linkedMapOf("preset" to name, "patch" to patch.toJsonValue(), "runs" to measurements)
        }
        val report = linkedMapOf(
            "version" to 1, "rendererModel" to Libration.MODEL_VERSION,
            "runtime" to "Android", "manufacturer" to Build.MANUFACTURER,
            "device" to Build.DEVICE, "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT, "fingerprint" to Build.FINGERPRINT,
            "abis" to Build.SUPPORTED_ABIS.toList(),
            "method" to "One untimed warmup per case, then cold caches, physics reuse with fresh audio, and audio recall. Monotonic wall time; excludes debounce, playback and export.",
            "cases" to cases,
        )
        val text = Json.write(json(report))
        val file = File(instrumentation.targetContext.cacheDir, "libration-render-profile.json")
        file.writeText(text)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nLIBRATION PROFILE\n$text\n") })
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
