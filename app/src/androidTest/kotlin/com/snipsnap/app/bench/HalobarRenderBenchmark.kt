package com.snipsnap.app.bench

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.synth.Halobar
import com.snipsnap.synth.HalobarVoice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in production-render timings. This test never plays audio or establishes a listening gate. */
@RunWith(AndroidJUnit4::class)
class HalobarRenderBenchmark {
    @Test
    fun recordFreshRecallAndControlEdits() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Enable with -e halobarProfile true", args.getString("halobarProfile") == "true")
        val runs = args.getString("halobarRuns")?.toIntOrNull()?.coerceIn(1, 9) ?: 3
        val runId = args.getString("halobarRunId") ?: "manual"
        require(runId.matches(Regex("[A-Za-z0-9-]{1,64}")))
        // A diagnostic can call the existing test hook without adding a public
        // cache-management API to the instrument. The debug build is unshrunk.
        val clearCache = Halobar::class.java.getDeclaredMethod("clearCache\$synth")
        fun clear() { clearCache.invoke(Halobar) }
        val rows = JSONArray()
        try {
            for (voice in HalobarVoice.entries) {
                for (case in listOf("FRESH", "LONG", "DRY", "RECALL", "RATE", "RETURN", "MOTION", "SPACE")) {
                    fun controls(index: Int): Map<String, Float> = when (case) {
                        "LONG" -> mapOf("HOLD" to 1f, "RETURN" to 1f)
                        "DRY" -> mapOf("SPACE" to 0f)
                        "RATE" -> mapOf("RATE" to ((index % 6 + .5f) / 6 + index * .00001f))
                        "RETURN", "MOTION", "SPACE" -> mapOf(case to (.15f + index * .017f))
                        else -> emptyMap()
                    }
                    val fresh = case in listOf("FRESH", "LONG", "DRY")
                    clear()
                    if (!fresh) Halobar.render(voice)
                    repeat(WARMUPS) { index ->
                        if (fresh) clear()
                        Halobar.render(voice, controls(index))
                    }
                    // Keep results until this edit sequence ends. Clearing for
                    // an oracle between edits would destroy the reuse being timed.
                    val rendered = ArrayList<FloatArray>()
                    val milliseconds = ArrayList<Double>()
                    repeat(runs) { offset ->
                        if (fresh) clear()
                        val macros = controls(offset + WARMUPS)
                        val began = SystemClock.elapsedRealtimeNanos()
                        val snip = Halobar.render(voice, macros)
                        milliseconds += (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0
                        assertEquals(Halobar.RATE, snip.sampleRate)
                        assertEquals(1, snip.channels)
                        assertTrue("$voice/$case produced finite audio", snip.samples.isNotEmpty() &&
                            snip.samples.all { it.isFinite() } && snip.samples.any { it != 0f })
                        rendered += snip.samples
                    }
                    val samples = JSONArray()
                    for ((offset, audio) in rendered.withIndex()) {
                        clear()
                        val macros = controls(offset + WARMUPS)
                        val reference = Halobar.render(voice, macros).samples
                        val actualHash = sha256(audio)
                        assertEquals("$voice/$case independent fresh replay", sha256(reference), actualHash)
                        samples.put(JSONObject().put("elapsedMs", milliseconds[offset])
                            .put("frames", audio.size).put("float32LeSha256", actualHash)
                            .put("controls", JSONObject(macros)))
                    }
                    val ordered = milliseconds.sorted()
                    val middle = ordered.size / 2
                    val median = if (ordered.size % 2 == 0) (ordered[middle - 1] + ordered[middle]) / 2 else ordered[middle]
                    rows.put(JSONObject().put("voice", voice.name).put("case", case)
                        .put("rootMidi", Halobar.midiFor(voice, .5f)).put("velocity", 1.0)
                        .put("warmups", WARMUPS).put("runs", samples).put("medianMs", median)
                        .put("maxMs", ordered.last()).put("medianWithin50Ms", median <= 50.0))
                }
            }
        } finally {
            clear()
        }
        val emulatorLikely = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk", ignoreCase = true)
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId).put("status", "completed")
            .put("measuredAtEpochMillis", System.currentTimeMillis()).put("audioModel", Halobar.MODEL_VERSION)
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("api", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("emulatorLikely", emulatorLikely))
            .put("method", "Three untimed warmups per case; monotonic render-only wall time. FRESH/LONG/DRY clear all four audio caches before every call, outside timing. Immutable halo spectra and runtime/JIT may be warm. RECALL seeds the completed render. Control edits seed defaults, then use unique values without clearing. RATE revisits six canonical divisions. Every measured result matches an independently cleared fresh replay by Float32 SHA-256 on this device, outside timing.")
            .put("scope", "Debug build, reference take at full velocity. No UI debounce, preview transfer, audio-output latency, physical listening, MPC loading, velocity/RR export or memory-peak claim. Emulator values are not phone measurements. The 50 ms column is descriptive, never a test pass condition. Small samples are not p95 estimates.")
            .put("cases", rows)
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "benchmarks")
        check(folder.isDirectory || folder.mkdirs())
        File(folder, "halobar-$runId.json").writeText(report.toString(2) + "\n")
    }

    private fun sha256(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(4096)
        var used = 0
        for (sample in samples) {
            val bits = sample.toRawBits()
            for (shift in 0..24 step 8) chunk[used++] = (bits ushr shift).toByte()
            if (used == chunk.size) {
                digest.update(chunk)
                used = 0
            }
        }
        digest.update(chunk, 0, used)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object { private const val WARMUPS = 3 }
}
