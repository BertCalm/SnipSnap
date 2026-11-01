package com.snipsnap.app.bench

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.json.Json
import com.snipsnap.shell.MeridianPadMaker
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.synth.MeridianPresets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Opt-in device diagnostic, separate from Compose correctness tests. Enable with
 * -Pandroid.testInstrumentationRunnerArguments.meridianBenchmark=true and select
 * this class. Emulator measurements are functional evidence, never phone timings.
 */
@RunWith(AndroidJUnit4::class)
class MeridianRenderBenchmark {
    @Test
    fun recordRenderAndKeyboardCosts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in device diagnostic: pass meridianBenchmark=true", args.getString("meridianBenchmark") == "true")
        val presets = MeridianPresets.all()
        val held = presets.single { it.name == "ROUND THREAD" }
        val highMemory = presets.single { it.name == "FINE STRAND" }.let {
            it.copy(name = "FINE STRAND MAX MEMORY", macros = it.macros + ("MEMORY" to 1f))
        }
        val cases = listOf(presets.first(), presets.single { it.name == "LONG TRAIL" }, held, highMemory)
        val rows = JSONArray()
        for (patch in cases) rows.put(profile(patch))

        val spec = MeridianPadMaker.Spec(held)
        val dest = File(instrumentation.targetContext.cacheDir, "meridian-benchmark-${System.nanoTime()}")
        var packageMillis = 0.0
        val instrumentMemory = sampleMemory {
            val packageStart = SystemClock.elapsedRealtimeNanos()
            val notes = MeridianPadMaker.zoneMidis(spec).map { MeridianPadMaker.renderZone(spec, it) }
            val program = MeridianPadMaker.export("MERIDIAN BENCH", spec, notes, dest)
            assertTrue("All thirteen native roots must be exported", program.keygroups.size == 13)
            packageMillis = millisSince(packageStart)
        }
        val packageBytes = dest.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        dest.deleteRecursively()

        val likelyEmulator = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk", ignoreCase = true)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("measuredAtEpochMillis", System.currentTimeMillis())
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("api", Build.VERSION.SDK_INT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("buildFingerprint", Build.FINGERPRINT).put("emulatorLikely", likelyEmulator))
            .put("executionContext", args.getString("executionContext") ?: "adb-selected device; inspect device identity")
            .put("timingMethod", "First call, then three warmups and seven unprofiled public renders; elapsedRealtimeNanos. First call includes any previously warmed shared process state. No latency assertion.")
            .put("memoryMethod", "Separate renders after requested GC; managed-heap occupancy sampled every 10 ms, plus PSS and native-heap snapshots. This is sampled process occupancy, not exact peak allocation. Other process activity can contribute.")
            .put("scope", "Engine render and complete keyboard package only. Excludes UI debounce, indicator delay, preview transfer and audio output latency. Emulator values must not be reported as phone performance.")
            .put("cases", rows)
            .put("keyboardPackage", JSONObject().put("roots", 13).put("velocityLayersPerRoot", 3)
                .put("rangeMidi", JSONArray(listOf(36, 72))).put("elapsedMsWithMemorySampling", packageMillis)
                .put("writtenBytes", packageBytes).put("memory", instrumentMemory))
        val folder = File(instrumentation.targetContext.filesDir, "benchmarks").apply { mkdirs() }
        val output = File(folder, "meridian-render.json")
        output.writeText(report.toString(2) + "\n")
        Log.i(TAG, "Saved ${output.absolutePath}; emulatorLikely=$likelyEmulator")
        Log.i(TAG, "Keyboard package: ${packageMillis}ms with memory sampling, ${packageBytes}bytes")
    }

    private fun profile(patch: MeridianPatch): JSONObject {
        fun renderMillis(): Double {
            val began = SystemClock.elapsedRealtimeNanos()
            val snip = patch.render()
            val elapsed = millisSince(began)
            assertTrue("${patch.name} must produce audio", snip.frameCount > 0)
            return elapsed
        }
        val first = renderMillis()
        repeat(3) { renderMillis() }
        val warm = List(7) { renderMillis() }
        val memory = sampleMemory {
            val snip = patch.render()
            assertTrue(snip.frameCount > 0)
        }
        val sorted = warm.sorted()
        Log.i(TAG, "${patch.name}: warm median=${sorted[3]}ms, max=${sorted.last()}ms")
        return JSONObject().put("name", patch.name).put("recipe", JSONObject(Json.write(patch.toJsonValue())))
            .put("firstCallMs", first).put("warmups", 3).put("warmMs", JSONArray(warm))
            .put("warmMedianMs", sorted[3]).put("warmMinMs", sorted.first()).put("warmMaxMs", sorted.last())
            .put("memory", memory)
    }

    private fun sampleMemory(action: () -> Unit): JSONObject {
        Runtime.getRuntime().gc()
        Thread.sleep(100)
        val baseline = managedBytes()
        val peak = AtomicLong(baseline)
        val running = AtomicBoolean(true)
        val pssBefore = Debug.getPss()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        val sampler = thread(name = "meridian-heap-sample", isDaemon = true) {
            while (running.get()) {
                peak.accumulateAndGet(managedBytes()) { a, b -> maxOf(a, b) }
                Thread.sleep(10)
            }
        }
        try {
            action()
            peak.accumulateAndGet(managedBytes()) { a, b -> maxOf(a, b) }
        } finally {
            running.set(false)
            sampler.join()
        }
        return JSONObject().put("managedBaselineBytes", baseline).put("managedPeakSampledBytes", peak.get())
            .put("managedSampledIncreaseBytes", peak.get() - baseline).put("managedAfterBytes", managedBytes())
            .put("pssBeforeKb", pssBefore).put("pssAfterKb", Debug.getPss())
            .put("nativeAllocatedBeforeBytes", nativeBefore).put("nativeAllocatedAfterBytes", Debug.getNativeHeapAllocatedSize())
    }

    private fun managedBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun millisSince(start: Long): Double = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

    companion object { private const val TAG = "MeridianBenchmark" }
}
