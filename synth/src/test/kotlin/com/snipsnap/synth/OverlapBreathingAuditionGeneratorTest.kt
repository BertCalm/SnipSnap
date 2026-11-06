package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlapBreathingAuditionGeneratorTest {
    @Test
    fun `unrelated output is refused without replacing its contents or rendering`() {
        val directory = createTempDirectory("overlap-breathing-unrelated").toFile()
        try {
            val recording = File(directory, "my-recording.wav").apply { writeBytes(byteArrayOf(3, 1, 4, 1, 5)) }
            val refusal = assertFailsWith<IllegalArgumentException> {
                OverlapBreathingAuditionGenerator.generate(directory)
            }
            assertTrue(refusal.message.orEmpty().contains("Refusing to replace nonempty output"))
            assertContentEquals(byteArrayOf(3, 1, 4, 1, 5), recording.readBytes())
            assertEquals(listOf(recording.name), directory.listFiles()!!.map { it.name })
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `real comparison retains physical gain motion exact replays and reproducible evidence`() {
        val directory = createTempDirectory("overlap-breathing-artifact").toFile()
        try {
            val archive = OverlapBreathingAuditionGenerator.generate(File(directory, "audition"))
            val output = archive.parentFile.parentFile
            assertEquals(OverlapBreathingAuditionGenerator.ARCHIVE_NAME, archive.name)
            val manifest = readJson(File(output, "assets/manifest.json"))
            assertEquals(1, manifest.getValue("overlapBreathingAudition").int())
            assertEquals("OverlapBreathingPrototype", manifest.getValue("prototype").str())
            assertEquals(44100, manifest.getValue("sampleRate").int())
            assertEquals(1, manifest.getValue("channels").int())
            assertEquals(24, manifest.getValue("bitsPerSample").int())
            assertEquals("assets/${archive.name}", manifest.getValue("bundle").str())
            val source = manifest.getValue("source")
            verifySource(source)
            val policy = manifest.getValue("levelPolicy").obj()
            val collectionGain = policy.getValue("rawCollectionGain").num()
            val target = policy.getValue("commonMatchedRms").num()
            assertTrue(collectionGain > 0.0 && collectionGain <= 1.0)
            assertTrue(target > 0.0 && target <= OverlapBreathingAuditionGenerator.REQUESTED_RMS)
            assertEquals(.90, policy.getValue("peakCeiling").num())
            val clips = manifest.getValue("clips").arr().map { it.obj() }
            assertEquals(OverlapBreathingPrototype.Mode.entries.map { it.name }, clips.map { it.getValue("mode").str() })
            val expectedPaths = mutableSetOf("assets/manifest.json", "assets/README.txt", "assets/SHA256SUMS.txt",
                "assets/${archive.name}", "metrics-runtime.json")
            val physicalPeaks = mutableListOf<Double>()
            val rawPeaks = mutableListOf<Double>()
            val rawRms = mutableListOf<Double>()
            val physicalHashes = mutableSetOf<String>()
            val frameCounts = mutableSetOf<Int>()
            val recipes = mutableListOf<File>()
            for (clip in clips) {
                val mode = OverlapBreathingPrototype.Mode.valueOf(clip.getValue("mode").str())
                val paths = listOf("raw", "matched", "recipe", "diagnostics").associateWith { clip.getValue(it).str() }
                expectedPaths += paths.values
                val hashes = clip.getValue("hashes").obj()
                for ((kind, path) in paths) assertEquals(hashes.getValue(kind).str(), sha(File(output, path)), "$mode $kind hash")
                val recipeFile = File(output, paths.getValue("recipe"))
                recipes += recipeFile
                val recipe = readJson(recipeFile)
                assertEquals(1, recipe.getValue("overlapBreathingPrototypeRecipe").int())
                assertEquals(mode.name, recipe.getValue("mode").str())
                assertEquals(source, recipe.getValue("source"))
                assertTrue(recipe.getValue("status").str().contains("unsupported"))
                val parameters = recipe.getValue("parameters").obj()
                val coreRecipe = OverlapBreathingPrototype.Recipe(mode, .8f)
                val planned = OverlapBreathingPrototype.plan(coreRecipe)
                val repeats = if (mode == OverlapBreathingPrototype.Mode.STEADY) 12 else 3
                assertEquals(repeats, parameters.getValue("repeatCount").int())
                assertEquals(repeats, clip.getValue("repeatCount").int())
                assertEquals(.8f, parameters.getValue("velocity").num().toFloat())
                assertEquals("TREFOIL", parameters.getValue("topology").str())
                assertEquals(48, parameters.getValue("midi").int())
                assertEquals(1.0, parameters.getValue("knot").num())
                assertEquals(.6f, parameters.getValue("ring").num().toFloat())
                assertEquals(planned.periodFrames, parameters.getValue("periodFrames").int())
                assertEquals(planned.sourcePeriodFrames, parameters.getValue("sourcePeriodFrames").int())
                assertEquals(planned.counts, parameters.getValue("counts").arr().map { it.int() })
                assertEquals(planned.offsets, parameters.getValue("offsets").arr().map { it.num() })
                assertEquals(planned.ratesHz, parameters.getValue("ratesHz").arr().map { it.num() })
                when (mode) {
                    OverlapBreathingPrototype.Mode.STEADY -> assertEquals(List(6) { 0 }, planned.counts)
                    OverlapBreathingPrototype.Mode.INDEPENDENT -> {
                        assertEquals(listOf(3, 4, 4, 5, 3, 5), planned.counts)
                        assertEquals(6, planned.offsets.distinct().size)
                    }
                    OverlapBreathingPrototype.Mode.SYNCHRONIZED -> {
                        assertEquals(List(6) { 4 }, planned.counts)
                        assertEquals(List(6) { 0.0 }, planned.offsets)
                    }
                }
                val result = OverlapBreathingPrototype.renderLoopMeasured(coreRecipe)
                val physical = FloatArray(result.raw.size * repeats) { result.raw[it % result.raw.size] }
                physicalPeaks += peak(physical)
                val physicalHash = floatSha(physical)
                physicalHashes += physicalHash
                assertEquals(physicalHash, recipe.getValue("physicalFloatSha256").str(), "$mode must retain the physical solver output")
                val levels = recipe.getValue("levels").obj()
                assertEquals(clip.getValue("levels"), JsonValue.Obj(levels))
                assertEquals(collectionGain, levels.getValue("rawCollectionGain").num())
                assertEquals(target, levels.getValue("commonMatchedRms").num())
                assertEquals("complete-settled-cycle-rms", levels.getValue("meter").str())
                val matchedGain = levels.getValue("matchedClipGain").num()
                val raw = FloatArray(physical.size) { (physical[it] * collectionGain).toFloat() }
                val matched = FloatArray(raw.size) { (raw[it] * matchedGain).toFloat() }
                rawPeaks += peak(raw)
                rawRms += rms(raw)
                assertEquals(target / rms(raw), matchedGain, 1e-12)
                val recordedOutputs = recipe.getValue("outputs").obj()
                val recordedHashes = recordedOutputs.getValue("sha256").obj()
                for ((variant, samples) in mapOf("raw" to raw, "matched" to matched)) {
                    val wav = File(output, paths.getValue(variant))
                    assertEquals(paths.getValue(variant), recordedOutputs.getValue(variant).str())
                    assertEquals(sha(wav), recordedHashes.getValue(variant).str())
                    assertContentEquals(encode(samples), wav.readBytes(), "$mode $variant uses the recorded static gains, with no extra window gain or fade")
                    val info = WavInfo.read(wav)
                    assertEquals(44100, info.sampleRate)
                    assertEquals(1, info.channels)
                    assertEquals(24, info.bitsPerSample)
                    assertEquals(physical.size.toLong(), info.frameCount)
                    assertEquals(physical.size, clip.getValue("frames").int())
                    frameCounts += physical.size
                    val decoded = WavReader.read(wav)
                    assertTrue(decoded.samples.all(Float::isFinite))
                    assertTrue(decoded.peak() > 1e-8f && decoded.peak() <= .900001f)
                    assertEquals(null, WavReader.readSmplStrict(wav), "the browser preview is a finite sequence of complete cycles")
                    val metrics = clip.getValue("metrics").obj().getValue(variant).obj()
                    assertEquals(peak(decoded.samples), metrics.getValue("peak").num(), 2e-7)
                    assertEquals(rms(decoded.samples), metrics.getValue("rms").num(), 2e-7)
                    assertEquals(decoded.samples.sumOf { it.toDouble() } / decoded.samples.size, metrics.getValue("dc").num(), 2e-7)
                    if (variant == "matched") assertEquals(target, rms(decoded.samples), 2e-7)
                    val replayed = File(directory, "replays/${mode.name.lowercase(Locale.ROOT)}-$variant.wav")
                    assertEquals(replayed, OverlapBreathingAuditionGenerator.replay(recipeFile, replayed, variant))
                    assertContentEquals(wav.readBytes(), replayed.readBytes(), "$mode $variant exact saved recipe replay")
                }
                val diagnostics = readJson(File(output, paths.getValue("diagnostics")))
                assertEquals(mode.name, diagnostics.getValue("mode").str())
                val recurrence = diagnostics.getValue("recurrence").obj()
                assertTrue(recurrence.getValue("converged").bool())
                assertTrue(recurrence.getValue("precedingSeam").num() < Keys.MAX_SEAM_ERROR)
                assertTrue(recurrence.getValue("followingSeam").num() < Keys.MAX_SEAM_ERROR)
                assertTrue(recurrence.getValue("maxLoopGain").num() < 1.0)
                val trace = diagnostics.getValue("trace").obj()
                assertEquals(result.trace.gateMean, matrix(trace.getValue("gateMean")))
                assertEquals(result.trace.nodeEnergyShares, matrix(trace.getValue("nodeEnergyShares")))
                if (mode == OverlapBreathingPrototype.Mode.SYNCHRONIZED) assertTrue(result.trace.gateMean.all { it.distinct().size == 1 })
                if (mode == OverlapBreathingPrototype.Mode.INDEPENDENT) assertTrue(result.trace.gateMean.any { it.distinct().size > 1 })
                val cancellation = diagnostics.getValue("cancellation").obj()
                assertTrue(cancellation.getValue("immediateCancellationObserved").bool())
                assertEquals(3, cancellation.getValue("cooperativeCancellationCallbackCount").int())
            }
            assertEquals(1, frameCounts.size, "every comparison must have the same complete-cycle duration")
            assertEquals(3, physicalHashes.size, "the motion choices must not all render the same physical body")
            assertEquals(minOf(1.0, .90 / physicalPeaks.maxOrNull()!!), collectionGain, 1e-12)
            assertEquals(minOf(.10, rawRms.indices.minOf { .90 * rawRms[it] / rawPeaks[it] }), target, 1e-12)
            assertEquals(expectedPaths, output.walkTopDown().filter(File::isFile).map { it.relativeTo(output).invariantSeparatorsPath }.toSet())
            verifyChecksums(output, expectedPaths - setOf("assets/SHA256SUMS.txt", "assets/${archive.name}", "metrics-runtime.json"))
            verifyArchive(output, archive, expectedPaths - setOf("assets/${archive.name}", "metrics-runtime.json"))
            val runtime = File(output, "metrics-runtime.json")
            val runtimeJson = readJson(runtime)
            assertEquals(source, runtimeJson.getValue("source"))
            assertEquals(3, runtimeJson.getValue("timings").arr().size)
            val originalArchive = archive.readBytes()
            runtime.writeText(Json.write(JsonValue.Obj(runtimeJson + ("testRuntimeChanged" to JsonValue.Bool(true)))))
            OverlapBreathingAuditionGenerator.writeArchive(output, archive)
            assertContentEquals(originalArchive, archive.readBytes(), "host runtime measurements must never change deterministic archive bytes")
            verifyReplayRefusals(directory, recipes.first())
        } finally { directory.deleteRecursively() }
    }

    private fun verifyReplayRefusals(directory: File, recipeFile: File) {
        val original = readJson(recipeFile)
        fun rejected(label: String, changed: Map<String, JsonValue>, failure: String) {
            val recipe = File(directory, "tampered-$label.json").apply { writeText(Json.write(JsonValue.Obj(changed))) }
            val output = File(directory, "rejected-$label/replay.wav")
            val thrown = kotlin.test.assertFails { OverlapBreathingAuditionGenerator.replay(recipe, output, "raw") }
            assertTrue(thrown.message.orEmpty().contains(failure), "$label refused for the wrong reason: ${thrown.message}")
            assertFalse(output.exists())
            assertFalse(output.parentFile.exists(), "invalid replay must be checked before creating output")
        }
        rejected("version", original + ("overlapBreathingPrototypeRecipe" to JsonValue.Num(2.0)), "Unsupported breathing recipe version")
        rejected("fractional-version", original + ("overlapBreathingPrototypeRecipe" to JsonValue.Num(1.5)), "Unsupported breathing recipe version")
        val parameters = original.getValue("parameters").obj()
        val fractionalRepeats = parameters.getValue("repeatCount").num() + .5
        rejected("fractional-repeats", original + ("parameters" to JsonValue.Obj(parameters +
            ("repeatCount" to JsonValue.Num(fractionalRepeats)))), "Replay must retain the complete comparison duration")
        rejected("rounded-velocity", original + ("parameters" to JsonValue.Obj(parameters +
            ("velocity" to JsonValue.Num(.8f.toDouble() + 1e-12)))), "Breathing audition velocity is fixed at 0.8")
        val levels = original.getValue("levels").obj()
        rejected("gain", original + ("levels" to JsonValue.Obj(levels +
            ("rawCollectionGain" to JsonValue.Num(levels.getValue("rawCollectionGain").num() * .5)))), "Stored matched scalar")
        val source = original.getValue("source").obj()
        val files = source.getValue("files").obj()
        val wrongSourceFiles = JsonValue.Obj(files + (files.keys.first() to JsonValue.Str("0".repeat(64))))
        val wrongSource = JsonValue.Obj(source + ("files" to wrongSourceFiles))
        rejected("source", original + ("source" to wrongSource), "Replay source differs")
        val outputs = original.getValue("outputs").obj()
        val hashes = outputs.getValue("sha256").obj()
        val wrongHashes = JsonValue.Obj(hashes + ("matched" to JsonValue.Str("0".repeat(64))))
        val wrongOutputs = JsonValue.Obj(outputs + ("sha256" to wrongHashes))
        rejected("unselected-hash", original + ("outputs" to wrongOutputs), "matched replay differs")
        val existing = File(directory, "existing.wav").apply { writeBytes(byteArrayOf(4, 2)) }
        assertFailsWith<IllegalArgumentException> { OverlapBreathingAuditionGenerator.replay(recipeFile, existing) }
        assertContentEquals(byteArrayOf(4, 2), existing.readBytes())
        val unsupported = File(directory, "unsupported/replay.wav")
        assertFailsWith<IllegalArgumentException> { OverlapBreathingAuditionGenerator.replay(recipeFile, unsupported, "native") }
        assertFalse(unsupported.parentFile.exists())
    }

    private fun verifySource(value: JsonValue) {
        val source = value.obj()
        assertTrue(source.getValue("revision").str().matches(Regex("[0-9a-f]{40}")))
        source.getValue("treeDirty").bool()
        val repository = generateSequence(File(".").canonicalFile) { it.parentFile }.first {
            File(it, "synth/src/main/kotlin/com/snipsnap/synth/OverlapBreathingPrototype.kt").isFile
        }
        val files = source.getValue("files").obj()
        assertEquals(setOf("OverlapBreathingPrototype.kt", "OverlapHeldPrototype.kt", "OverlapV2.kt", "Overlap.kt",
            "Dsp.kt", "Tide.kt", "WavWriter.kt", "OverlapBreathingAuditionGenerator.kt"), files.keys.map { File(it).name }.toSet())
        for ((path, hash) in files) assertEquals(sha(File(repository, path)), hash.str(), "source hash for $path")
        assertEquals(digest(files.entries.joinToString("\n") { "${it.key} ${it.value.str()}" }.toByteArray()), source.getValue("sha256").str())
    }

    private fun verifyChecksums(output: File, expected: Set<String>) {
        val lines = File(output, "assets/SHA256SUMS.txt").readLines()
        val paths = lines.map { it.drop(66) }
        assertEquals(expected.sorted(), paths)
        for (line in lines) {
            assertTrue(line.matches(Regex("[0-9a-f]{64}  .+")))
            assertEquals(line.take(64), sha(File(output, line.drop(66))))
        }
    }

    private fun verifyArchive(output: File, archive: File, expected: Set<String>) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(expected.sorted(), entries.map { it.name })
            assertEquals(entries.size, entries.map { it.name }.distinct().size)
            for (entry in entries) {
                assertFalse(entry.isDirectory)
                assertFalse(entry.name.startsWith('/') || '\\' in entry.name || ".." in entry.name.split('/'))
                assertEquals(0L, entry.time)
                assertContentEquals(File(output, entry.name).readBytes(), zip.getInputStream(entry).use { it.readBytes() })
            }
        }
    }

    private fun readJson(file: File): Map<String, JsonValue> = Json.parse(file.readText()).obj()
    private fun matrix(value: JsonValue): List<List<Double>> = value.arr().map { row -> row.arr().map { it.num() } }
    private fun encode(samples: FloatArray): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, Snip(samples, 1, 44100), WavWriter.BitDepth.PCM_24)
    }.toByteArray()
    private fun peak(samples: FloatArray): Double = samples.maxOf { abs(it.toDouble()) }
    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    private fun floatSha(samples: FloatArray): String = digest(ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .apply { samples.forEach { putFloat(it) } }.array())
    private fun sha(file: File): String = digest(file.readBytes())
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
}
