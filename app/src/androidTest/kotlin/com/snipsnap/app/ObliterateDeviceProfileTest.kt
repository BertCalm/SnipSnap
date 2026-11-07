package com.snipsnap.app

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.BoardSheet
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.json.Json
import com.snipsnap.synth.BoardRecipe
import com.snipsnap.synth.BoardType
import com.snipsnap.synth.Obliterate
import com.snipsnap.synth.ObliterateBoard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Reproducible, on-device public-call profiling on the exact edition-006 inputs.
 * No speed assertion: CI emulator timings cannot establish physical-phone performance.
 * The JSON records the device/build and retains every trial, not just a best time.
 */
@RunWith(AndroidJUnit4::class)
class ObliterateDeviceProfileTest {
    @Test fun record_complete_chain_and_repeated_board_calls_on_the_listening_inputs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val manifest = Json.parse(assets.open("obliterate/sources.json").bufferedReader().use { it.readText() }).obj()
        val cases = JSONArray()
        var sink = 0.0
        for (row in manifest.getValue("sources").arr()) {
            val s = row.obj()
            val bytes = assets.open("obliterate/" + s.getValue("inputFile").str()).use { it.readBytes() }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(s.getValue("inputSha256").str(), sha)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val samples = FloatArray(buffer.remaining()).also { buffer.get(it) }
            val originalSamples = samples.copyOf()
            val input = Snip(samples, s.getValue("inputChannels").int(), 44100)
            assertEquals(s.getValue("inputFrames").int(), input.frameCount)
            val amount = if (s.getValue("id").str() == "snare") .7f else .35f
            val state = ObliterateBoard.Settings()
            val recipe = BoardRecipe(BoardType.OBLITERATE, amount, state)
            fun times(block: () -> Snip): List<Double> = List(5) {
                val start = System.nanoTime(); val audio = block()
                val elapsed = (System.nanoTime() - start) / 1e6
                sink += audio.samples[0]; elapsed
            }
            val firstStart = System.nanoTime()
            val first = recipe.process(input)
            val firstMs = (System.nanoTime() - firstStart) / 1e6
            repeat(2) { Obliterate.render(input, state.at(amount)) }
            val uncached = times { Obliterate.render(input, state.at(amount)).audio }
            val cached = times { recipe.process(input) }
            assertArrayEquals(first.samples, recipe.process(input).samples, 0f)
            assertArrayEquals(first.samples, Obliterate.render(input, state.at(amount)).audio.samples, 0f)
            assertArrayEquals(originalSamples, input.samples, 0f)
            // Include the actual shell doors: source WAV reads, preflight, immutable capture,
            // PCM24 write and kit save. These are still not tap-to-audible UI measurements.
            val dir = File(instrumentation.targetContext.cacheDir, "obliterate-profile-${s.getValue("id").str()}-${System.nanoTime()}").apply { mkdirs() }
            val previewTimes: List<Double>
            val keepMs: Double
            try {
                WavWriter.write(File(dir, "input.wav"), input, WavWriter.BitDepth.PCM_24)
                KitStore.save(Kit("OBLITERATE PROFILE", listOf(KitPad(1, "input.wav"))), dir)
                val model = KitBuilderModel.open(dir)
                val storedInput = WavReader.read(File(dir, "input.wav"))
                val expected = recipe.process(storedInput)
                previewTimes = times { BoardSheet.preview(model, 1, recipe) }
                val start = System.nanoTime()
                val kept = BoardSheet.apply(model, 1, recipe)
                model.save()
                keepMs = (System.nanoTime() - start) / 1e6
                assertEquals(recipe, BoardSheet.read(kept.recipe)!!.recipe)
                assertArrayEquals(expected.samples, BoardSheet.preview(model, 1, recipe).samples, 0f)
                BoardSheet.remove(model, 1)
            } finally { dir.deleteRecursively() }
            cases.put(JSONObject().put("sourceId", s.getValue("id").str()).put("inputSha256", sha)
                .put("inputFrames", input.frameCount).put("recipe", JSONObject(recipe.toJsonText())).put("coreAmount", amount.toDouble())
                .put("shimmerMix", state.shimmerMix).put("firstBoardMs", firstMs)
                .put("warmUncachedMs", JSONArray(uncached)).put("repeatBoardMs", JSONArray(cached))
                .put("repeatShellPreviewMs", JSONArray(previewTimes)).put("keepAndSaveMs", keepMs))
            Log.i("ObliterateProfile", cases.getJSONObject(cases.length() - 1).toString())
        }
        check(sink.isFinite())
        // Hash installed APKs after timed work, binding a later phone report to its actual binaries.
        fun apkSha(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val block = ByteArray(64 * 1024)
                while (true) { val n = input.read(block); if (n < 0) break; hash.update(block, 0, n) }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val report = JSONObject().put("schema", 1).put("boardModel", ObliterateBoard.MODEL)
            .put("inputSetSourceCommit", manifest.getValue("sourceCommit").str())
            .put("appApkSha256", apkSha(File(instrumentation.targetContext.applicationInfo.sourceDir)))
            .put("testApkSha256", apkSha(File(instrumentation.context.applicationInfo.sourceDir)))
            .put("fingerprint", Build.FINGERPRINT).put("model", Build.MODEL).put("hardware", Build.HARDWARE)
            .put("sdkInt", Build.VERSION.SDK_INT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("build", "unshrunk debug instrumentation; emulator results are not physical-phone results")
            .put("trials", 5).put("uncachedWarmups", 2).put("cases", cases)
        File(instrumentation.targetContext.filesDir, "obliterate-profile.json").writeText(report.toString(2) + "\n")
        Log.i("ObliterateProfile", report.toString())
    }
}
