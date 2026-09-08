package com.snipsnap.shell

import java.io.File
import java.nio.file.Files
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnipStoreTest {

    private fun tone(seconds: Float, rate: Int = 44_100): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n) { (0.4f * sin(2.0 * Math.PI * 220.0 * it / rate)).toFloat() }
    }

    @Test
    fun `a commit lands one readable WAV in the snips dir`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val f = SnipStore.commit(tone(1f), 44_100, root, nowMillis = 1_000_000L)
            assertTrue(f.isFile && f.parentFile.name == SnipStore.DIR)
            val back = com.snipsnap.audio.WavReader.read(f)
            assertEquals(44_100, back.sampleRate)
            assertTrue(back.frameCount > 0)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `leading dead air is trimmed by the commit chain`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val silence = FloatArray(44_100)                 // 1s of nothing
            val f = SnipStore.commit(silence + tone(0.5f), 44_100, root, 2_000_000L)
            val back = com.snipsnap.audio.WavReader.read(f)
            assertTrue(
                back.frameCount < 44_100,
                "the silent first second should not survive the commit chain: ${back.frameCount}",
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `an import lands as the newest snip, mono, at the MPC rate, untouched otherwise`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            // Stereo at 48 k, with a second of leading silence a commit would trim - an import keeps it.
            val rate = 48_000
            val frames = 2 * rate
            val stereo = FloatArray(frames * 2) { i ->
                val f = i / 2
                if (f < rate) 0f else (0.4f * sin(2.0 * Math.PI * 220.0 * f / rate)).toFloat()
            }
            val got = SnipStore.import(com.snipsnap.audio.Snip(stereo, 2, rate), root, 5_000L)
            assertTrue(got.file.isFile && got.file.parentFile.name == SnipStore.DIR)
            assertEquals(false, got.truncated)
            assertEquals(2f, got.seconds, 0.01f)
            val back = com.snipsnap.audio.WavReader.read(got.file)
            assertEquals(1, back.channels, "mono, as the deck reads")
            assertEquals(44_100, back.sampleRate, "the MPC rate")
            assertTrue(kotlin.math.abs(back.frameCount - 2 * 44_100) <= 50, "two seconds at 44.1 k: ${back.frameCount}")
            var head = 0f
            for (i in 0 until 40_000) head = maxOf(head, kotlin.math.abs(back.samples[i]))
            assertTrue(head < 1e-3f, "the leading silence survives: an import is not a commit")
            assertEquals(got.file, SnipStore.newest(root), "TAPE finds it first")
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `a long import keeps its head and says so, and an empty one is refused`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val long = tone(SnipStore.IMPORT_MAX_SEC + 5f)
            val got = SnipStore.import(com.snipsnap.audio.Snip(long, 1, 44_100), root, 6_000L)
            assertTrue(got.truncated)
            assertEquals(SnipStore.IMPORT_MAX_SEC, got.seconds, 0.01f)
            assertEquals((SnipStore.IMPORT_MAX_SEC * 44_100).toInt(), com.snipsnap.audio.WavReader.read(got.file).frameCount)
            val e = kotlin.test.assertFailsWith<IllegalArgumentException> {
                SnipStore.import(com.snipsnap.audio.Snip(FloatArray(0), 1, 44_100), root, 7_000L)
            }
            assertTrue(e.message!!.contains("no audio"))
            assertEquals("TAPED FROM OUTSIDE. 3 MIN ON THE DECK.", Copy.imported(180f, false))
            assertEquals("TAPED FROM OUTSIDE. FIRST 3 MIN KEPT - THE TAPE IS ONLY SO LONG.", Copy.imported(180f, true))
            assertEquals("TAPED FROM OUTSIDE. 8s ON THE DECK.", Copy.imported(8.2f, false))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `two snips in one millisecond land on two paths, the later one newest`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val a = SnipStore.commit(tone(0.2f), 44_100, root, 9_000L)
            val b = SnipStore.import(com.snipsnap.audio.Snip(tone(0.3f), 1, 44_100), root, 9_000L).file
            assertTrue(a != b, "the second never overwrites the first")
            assertTrue(a.isFile && b.isFile)
            assertEquals(b, SnipStore.newest(root), "the later arrival is the newer name")
            assertEquals(listOf(b, a), SnipStore.list(root))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `concurrent commits at the identical millisecond never collide, even under a hard race`() {
        // The sequential test above (two calls, one thread) can't exercise the
        // actual defect: two REAL threads racing `freshFile`'s claim for the
        // same `nowMillis`, the shape a fast double-press of SNIP (or the
        // notification action firing alongside the in-app button) produces.
        // A plain `file.exists()` check-then-write has a window where both
        // threads observe "nothing here yet" for the same candidate name and
        // one silently clobbers the other; `File.createNewFile()`'s
        // create-if-absent is what closes it.
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val threadCount = 8
            val barrier = java.util.concurrent.CyclicBarrier(threadCount)
            val results = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, java.io.File>>()
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads = (0 until threadCount).map { i ->
                Thread {
                    try {
                        barrier.await()
                        // A distinct, recognizable duration per thread - a
                        // collision surfaces as one file reading back with
                        // ANOTHER thread's frame count, not just a missing file.
                        val seconds = 0.3f + i * 0.15f
                        val file = SnipStore.commit(tone(seconds), 44_100, root, nowMillis = 42_000L)
                        results.add(i to file)
                    } catch (e: Throwable) {
                        errors.add(e)
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(10_000) }

            assertTrue(errors.isEmpty(), "no thread should throw: $errors")
            assertEquals(threadCount, results.size, "every thread must finish and report a file")

            val files = results.map { it.second }
            assertEquals(files.toSet().size, files.size, "no two threads may land on the same path")
            files.forEach { assertTrue(it.isFile, "every claimed path must actually hold a file: $it") }

            // Each file's own payload survives at its own path, intact -
            // the actual failure mode this guards against is a same-millis
            // race silently overwriting one thread's bytes with another's.
            results.forEach { (i, file) ->
                val expectedSeconds = 0.3f + i * 0.15f
                val expectedFrames = (expectedSeconds * 44_100).toInt()
                val back = com.snipsnap.audio.WavReader.read(file)
                assertTrue(
                    back.frameCount in (expectedFrames - 3_000)..(expectedFrames + 500),
                    "thread $i's file (${file.name}) should hold ~$expectedSeconds s of ITS OWN tone, " +
                        "got ${back.frameCount} frames - a same-millis collision shows up here as another " +
                        "thread's duration or a truncated/torn read",
                )
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `list is newest first and newest agrees`() {
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val a = SnipStore.commit(tone(0.2f), 44_100, root, 1_000L)
            val b = SnipStore.commit(tone(0.2f), 44_100, root, 2_000L)
            assertEquals(listOf(b, a), SnipStore.list(root))
            assertEquals(b, SnipStore.newest(root))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `an all-silent snip still commits rather than throwing`() {
        // The user pressed SNIP; the honest outcome of a silent minute is a
        // short (possibly minimal) file, not an exception in a service.
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val f = SnipStore.commit(FloatArray(44_100), 44_100, root, 3_000L)
            assertTrue(f.isFile)
            // Small, not the whole silent minute: a real armed session
            // rings 60s, and writing that out as literal zeros every quiet
            // SNIP would be dishonest with "commits small" and wasteful of
            // flash either way.
            val back = com.snipsnap.audio.WavReader.read(f)
            assertTrue(
                back.frameCount in 1 until 44_100 / 4,
                "expected a short fallback slice, got ${back.frameCount} frames",
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `a quiet room does not get normalised into a screech`() {
        // Below Cleanup's default -60dB threshold (linear ~0.001) but NOT
        // exact zero, unlike the all-silence test above — this is the
        // buffer shape that actually exercises the fallback's normalize
        // step: real noise-floor hiss, not a clean no-op on true silence.
        val root = kotlin.io.path.createTempDirectory("snips").toFile()
        try {
            val rand = kotlin.random.Random(42)
            val noise = FloatArray(44_100) { (rand.nextFloat() * 2f - 1f) * 0.0003f }
            var inputPeak = 0f
            for (s in noise) inputPeak = maxOf(inputPeak, kotlin.math.abs(s))

            val f = SnipStore.commit(noise, 44_100, root, 4_000L)
            val back = com.snipsnap.audio.WavReader.read(f)
            var outputPeak = 0f
            for (s in back.samples) outputPeak = maxOf(outputPeak, kotlin.math.abs(s))

            assertTrue(
                outputPeak <= inputPeak * 4f,
                "quiet noise should stay quiet, not get normalised toward full scale: " +
                    "input peak $inputPeak, output peak $outputPeak",
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `delete removes the file and reports success`() {
        val dir = Files.createTempDirectory("snips").toFile()
        try {
            val f = SnipStore.commit(FloatArray(4_410) { 0.1f }, 44_100, dir, 1_000L)
            assertTrue(f.exists())
            assertTrue(SnipStore.delete(f))
            assertFalse(f.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `delete on an already-gone file returns false, not a throw`() {
        val dir = Files.createTempDirectory("snips").toFile()
        try {
            val ghost = File(dir, "snip_999.wav")
            assertFalse(SnipStore.delete(ghost))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `listWithInfo carries size and the captured timestamp, newest first, agreeing with list`() {
        val dir = Files.createTempDirectory("snips").toFile()
        try {
            val a = SnipStore.commit(FloatArray(4_410) { 0.1f }, 44_100, dir, 1_000L)
            val b = SnipStore.commit(FloatArray(8_820) { 0.1f }, 44_100, dir, 2_000L)
            val info = SnipStore.listWithInfo(dir)
            assertEquals(listOf(b, a), info.map { it.file })
            assertTrue(info.all { it.sizeBytes > 0 })
            assertEquals(2_000L, info.first().capturedAtMillis)
            assertEquals(SnipStore.list(dir), info.map { it.file }, "listWithInfo must never disagree with list's own order")
        } finally { dir.deleteRecursively() }
    }
}
