package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Frozen pre-optimization PCM, plus real-render state isolation. Timing belongs to the benchmark. */
class ScannedChainPerformancePreservationTest {
    private data class Baseline(
        val cases: List<ScannedChainRenderBenchmark.Case>,
        val expected: Map<String, Map<String, JsonValue>>,
    )

    @BeforeTest
    fun clearCompletedAudio() = ScannedChainRenderBenchmark.clearCompletedCache()

    @AfterTest
    fun releaseCompletedAudio() {
        Thread.interrupted()
        ScannedChainRenderBenchmark.clearCompletedCache()
    }

    // Independent of the benchmark's hash helper: retain IEEE-754 signed-zero bits too.
    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(it.toRawBits()) }
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    private fun baseline(): Baseline {
        val resource = "/scanned-chain-performance/baseline-307f125.json"
        val text = requireNotNull(javaClass.getResourceAsStream(resource)) {
            "missing untouched pre-optimization capture $resource"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val manifest = Json.parse(text).obj()
        assertEquals(1, manifest.getValue("schemaVersion").int())
        assertEquals("307f125ef2d9ef0c4d6e6c816bb008b12a235392", manifest.getValue("baselineCommit").str())
        assertEquals("preservation", manifest.getValue("caseGroup").str())
        val rows = manifest.getValue("cases").arr().map { it.obj() }
        val expected = rows.associateBy { it.getValue("id").str() }
        assertEquals(rows.size, expected.size, "baseline case IDs must be unique")
        val cases = ScannedChainRenderBenchmark.cases("preservation")
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
        assertEquals(expected.keys, cases.map { it.id }.toSet(), "the frozen coverage cannot silently shrink")
        for (case in cases) {
            val row = expected.getValue(case.id)
            assertEquals(2, case.patch.model)
            assertEquals(row.getValue("patchJson"), case.patch.toJsonValue(),
                "${case.id}: compare the same saved source, controls, note and configuration")
            assertEquals(row.getValue("inputShapeSha256").str(), hash(case.patch.shape.points), case.id)
        }
        return Baseline(cases, expected)
    }

    private fun assertAudio(expected: Map<String, JsonValue>, actual: Snip, context: String) {
        assertEquals(expected.getValue("frames").int(), actual.frameCount, "$context frames")
        assertEquals(expected.getValue("channels").int(), actual.channels, "$context channels")
        assertEquals(expected.getValue("sampleRate").int(), actual.sampleRate, "$context rate")
        assertTrue(actual.samples.all { it.isFinite() }, "$context finite PCM")
        assertEquals(expected.getValue("sha256").str(), hash(actual.samples), "$context float32-LE PCM")
    }

    private fun freshFinished(case: ScannedChainRenderBenchmark.Case): Snip {
        ScannedChainRenderBenchmark.clearCompletedCache()
        return ScannedChainRenderBenchmark.finished(case)
    }

    private fun properties(value: Any): JsonValue.Obj = JsonValue.Obj(value.javaClass.methods.filter {
        it.name.startsWith("get") && it.name != "getClass" && it.parameterCount == 0
    }.sortedBy { it.name }.associate { getter ->
        val name = getter.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
        val property = when (val result = getter.invoke(value)) {
            is Number -> JsonValue.Num(result.toDouble())
            is Boolean -> JsonValue.Bool(result)
            else -> error("unexpected $name diagnostic type ${result?.javaClass}")
        }
        name to property
    })

    @Test
    fun `model two raw and finished output retain the untouched baseline at every preservation case`() {
        val baseline = baseline()
        for (case in baseline.cases) {
            val expected = baseline.expected.getValue(case.id)
            ScannedChainRenderBenchmark.clearCompletedCache()
            val patch = case.patch
            val result = ScannedChain.probeV2(patch.voice, patch.macros, patch.midi, patch.velocity, patch.shape, patch.config)
            assertAudio(expected.getValue("raw").obj(), result.raw, "${case.id} raw")
            assertEquals(expected.getValue("initialShapeSha256").str(), hash(result.initialShape), "${case.id} initial state")
            assertEquals(expected.getValue("finalShapeSha256").str(), hash(result.finalShape), "${case.id} final state")
            assertEquals(expected.getValue("diagnostics"), properties(result.diagnostics), "${case.id} physical ledger")
            assertEquals(expected.getValue("headroom"), properties(result.headroom), "${case.id} initial headroom")
            assertAudio(expected.getValue("finished").obj(), freshFinished(case), "${case.id} finished")
            assertAudio(expected.getValue("raw").obj(), result.raw, "${case.id} retained raw")
            assertEquals(expected.getValue("initialShapeSha256").str(), hash(result.initialShape), "${case.id} retained initial state")
            assertEquals(expected.getValue("finalShapeSha256").str(), hash(result.finalShape), "${case.id} retained final state")
        }
    }

    @Test
    fun `changed controls and returning to a previous recipe leave earlier real renders untouched`() {
        val baseline = baseline()
        val a = baseline.cases.first { case ->
            case.patch.voice == ScannedChainVoice.BLOOM && case.patch.config.seconds <= .5f &&
                case.patch.velocity > 0f && case.patch.macros["MOTION"] == 1f && case.patch.macros["BEND"] == 1f
        }
        val savedPatch = a.patch.toJsonText()
        val b = a.copy(id = "${a.id}-changed-bend", patch = a.patch.copy(macros = a.patch.macros + ("BEND" to .125f)))
        val first = freshFinished(a)
        val firstSnapshot = first.samples.copyOf()
        val changed = freshFinished(b)
        val changedSnapshot = changed.samples.copyOf()
        val repeated = freshFinished(a)

        assertAudio(baseline.expected.getValue(a.id).getValue("finished").obj(), repeated, "returned recipe")
        assertNotEquals(hash(firstSnapshot), hash(changedSnapshot), "the intervening real render exercises changed state")
        assertContentEquals(firstSnapshot, first.samples, "later solver/readout work cannot reuse a returned buffer")
        assertContentEquals(changedSnapshot, changed.samples, "returning to A cannot overwrite B")
        assertContentEquals(firstSnapshot, repeated.samples)
        assertNotSame(first.samples, repeated.samples)
        assertEquals(savedPatch, a.patch.toJsonText(), "working state cannot overwrite the saved contour")
    }

    @Test
    fun `parallel actual model two solvers keep separate state and match their baseline PCM`() {
        val baseline = baseline()
        val focused = baseline.cases.single { it.id.contains("focus", ignoreCase = true) }
        val bloom = baseline.cases.first { case ->
            case.patch.voice == ScannedChainVoice.BLOOM && case.patch.config.seconds <= .5f &&
                case.patch.velocity > 0f && case.patch.macros["MOTION"] == 1f
        }
        val cases = listOf(focused, bloom)
        val savedPatches = cases.map { it.patch.toJsonText() }
        val workers = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val pending = cases.map { case -> workers.submit(Callable {
                ready.countDown()
                check(start.await(30, TimeUnit.SECONDS))
                ScannedChainRenderBenchmark.raw(case)
            }) }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "both workers reach the common start")
            start.countDown()
            val results = pending.map { it.get(60, TimeUnit.SECONDS) }
            cases.zip(results).forEach { (case, audio) ->
                assertAudio(baseline.expected.getValue(case.id).getValue("raw").obj(), audio, "${case.id} concurrent raw")
            }
            assertNotSame(results[0].samples, results[1].samples)
            assertEquals(savedPatches, cases.map { it.patch.toJsonText() })
        } finally {
            start.countDown()
            workers.shutdownNow()
        }
    }
}
