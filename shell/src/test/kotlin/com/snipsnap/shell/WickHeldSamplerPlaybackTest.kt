package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reference JVM sampler behavior, not native Android/device or WICK entry capture.
 * The ordinary test owns a tiny in-memory control with a distinguishable prefix.
 * An explicit sidecar property additionally qualifies already-saved WICK PCM by
 * reference; it never captures, copies, exports or modifies an audio file.
 */
class WickHeldSamplerPlaybackTest {
    @Test
    fun heldSamplerLoopsAndReleases() {
        val reports = mutableListOf<JsonValue>()
        reports += exercise(control(), "independent-control")
        System.getProperty("snipsnap.wick.playbackFixture")?.let { path ->
            require(path.isNotBlank()) { "an explicitly selected fixture cannot be blank" }
            reports += exercise(saved(File(path)), "retained-saved-wick")
        }
        System.getProperty("snipsnap.wick.playbackReport")?.let { path ->
            File(path).writeText(Json.write(JsonValue.Obj(linkedMapOf(
                "kind" to JsonValue.Str("WICK_REFERENCE_SAMPLER_PLAYBACK"),
                "cases" to JsonValue.Arr(reports),
                "audioFilesWritten" to JsonValue.Num(0.0),
                "nativeAndroidPlayback" to JsonValue.Bool(false),
                "nativeReleaseProven" to JsonValue.Bool(false),
                "physicalDeviceAcceptance" to JsonValue.Bool(false),
                "naturalEntryProven" to JsonValue.Bool(false),
            ))))
        }
    }

    private data class Fixture(
        val instrument: InstrumentStore.Instrument,
        val source: FloatArray,
        val rate: Int,
        val folder: File? = null,
        val beforeFiles: Map<String, String> = emptyMap(),
    )

    private fun control(): Fixture {
        val source = floatArrayOf(-0.6f, -0.4f, -0.2f, 0.1f, 0.3f, 0.5f, 0.7f, 0.2f,
            0.15f, 0.25f, 0.35f, 0.45f, 0.55f, 0.65f, 0.75f, 0.85f)
        val zone = InstrumentStore.Zone(48, 48, 48, "independent-control.wav", 16, 8)
        return Fixture(InstrumentStore.Instrument("Independent held control", 0.35f, listOf(zone)), source, 44_100)
    }

    private fun saved(sidecar: File): Fixture {
        require(sidecar.isFile) { "selected saved sidecar is absent: $sidecar" }
        val folder = sidecar.absoluteFile.parentFile
        val before = snapshot(folder)
        val instrument = InstrumentStore.read(sidecar)
        assertEquals(1, instrument.zones.size)
        assertEquals(1, instrument.sampleFiles.size)
        val zone = instrument.zones.single()
        assertEquals(48, zone.rootNote)
        assertEquals(48, zone.lowNote)
        assertEquals(48, zone.highNote)
        assertEquals(211_714L, zone.frameCount, "this qualification is the sealed retained WICK specimen")
        assertEquals(105_857L, zone.loopStartFrame)
        assertEquals(0.35f, instrument.releaseFor(zone), "preserve the saved host release")
        val layer = zone.playableLayers.single()
        assertEquals(zone.sample, layer.sample)
        assertEquals(0, layer.velStart)
        assertEquals(127, layer.velEnd)
        val decoded = WavReader.read(File(folder, layer.sample))
        assertEquals(1, decoded.channels)
        assertEquals(44_100, decoded.sampleRate)
        assertEquals(layer.frameCount, decoded.frameCount.toLong())
        return Fixture(instrument, decoded.samples, decoded.sampleRate, folder, before)
    }

    private fun exercise(f: Fixture, name: String): JsonValue {
        val zone = f.instrument.zones.single()
        val source = f.source
        val sourceBefore = floatHash(source)
        val loop = zone.loopStartFrame.toInt()
        val period = source.size - loop
        assertEquals(44_100, f.rate)
        assertEquals(48, zone.rootNote)
        assertEquals(0.35f, InstrumentEngine.VOICE_LEVEL)
        assertEquals(source.size.toLong(), zone.frameCount)
        assertEquals(period, loop, "the specimen is a prefix followed by one retained cycle")
        assertTrue(period > 1)
        assertTrue(source.all { it.isFinite() && abs(it) <= 1f })
        val distinctAfterGain = (0 until period).count { source[it] * 0.35f != source[loop + it] * 0.35f }
        assertTrue(distinctAfterGain > 0, "a wrong wrap to frame zero must be observable after actual gain")
        assertTrue(source.any { abs(it * 0.35f) > 0.001f }, "the fixture must contain audible nonzero energy")

        // Exact saved layer paths, not Loaded's legacy per-zone fallback.
        val loaded = InstrumentEngine.Loaded(f.instrument, f.rate, mapOf(zone.sample to source))
        val engine = InstrumentEngine(loaded, f.rate)
        val block = FloatArray(4096 * 2) { 123f }
        engine.render(block, 19)
        assertTrue((0 until 38).all { block[it] == 0f })
        assertTrue(engine.activeNotes.isEmpty())
        assertFalse(engine.noteOn(47))
        assertFalse(engine.noteOn(49))
        engine.render(block, 19)
        assertTrue((0 until 38).all { block[it] == 0f })
        assertTrue(engine.noteOn(48))

        // Root and source/output rates agree, so the independent integer index
        // and decoded PCM determine every sample exactly, including both ears.
        fun reference(frame: Long): Float {
            val index = if (frame < source.size) frame.toInt()
                else loop + ((frame - source.size) % period).toInt()
            return source[index] * 0.35f
        }
        var frame = 0L
        var heldNonzero = 0L
        val partition = intArrayOf(1, 17, 4096, 257, 1024, 31)
        var part = 0
        fun hold(count: Int) {
            var remaining = count
            while (remaining > 0) {
                val n = minOf(partition[part++ % partition.size], remaining)
                engine.render(block, n)
                for (i in 0 until n) {
                    val expected = reference(frame)
                    val left = block[i * 2]
                    val right = block[i * 2 + 1]
                    if (left != expected || right != expected) {
                        throw AssertionError("$name held frame $frame: expected $expected, got $left / $right")
                    }
                    if (expected != 0f) heldNonzero++
                    frame++
                }
                remaining -= n
            }
        }
        // The first file end plus three further complete periods crosses the
        // declared wrap three times; awkward block sizes expose state resets.
        val holdFrames = source.size + 3 * period
        hold(holdFrames)
        assertEquals(listOf(48), engine.activeNotes)
        engine.noteOff(49)
        hold(17)
        assertEquals(listOf(48), engine.activeNotes, "a different key-up cannot release this voice")
        assertTrue(heldNonzero > 0)

        val releaseSeconds = f.instrument.releaseFor(zone).toDouble()
        val declaredFrames = releaseSeconds * f.rate
        val frameSlack = ceil(0.001 * f.rate).toInt()
        val latestFrame = ceil(declaredFrames).toInt() + frameSlack
        // Independent linear-envelope law, with a conservative bound for a
        // Float implementation accumulating at most half an ulp(1) per step.
        val envelopeBound = latestFrame * Math.ulp(1f).toDouble() / 2 + 0.000002
        val checkpoints = BooleanArray(4)
        var previousEnvelope = 1.0
        var maxEnvelopeError = 0.0
        var inferredSamples = 0
        var releaseFrames = 0
        val one = FloatArray(2)
        engine.noteOff(48)
        assertEquals(listOf(48), engine.activeNotes, "key-up begins a fade rather than deleting the voice")
        while (engine.activeNotes.isNotEmpty() && releaseFrames <= latestFrame) {
            val dry = reference(frame)
            engine.render(one, 1)
            frame++
            releaseFrames++
            assertEquals(one[0], one[1], "both ears share the same mono release")
            assertTrue(one[0].isFinite())
            if (engine.activeNotes.isNotEmpty() && abs(dry) > 0.000001f) {
                val inferred = one[0].toDouble() / dry
                val ideal = maxOf(0.0, 1.0 - releaseFrames / declaredFrames)
                val error = abs(inferred - ideal)
                maxEnvelopeError = maxOf(maxEnvelopeError, error)
                assertTrue(error <= envelopeBound, "$name release frame $releaseFrames envelope $inferred vs $ideal")
                assertTrue(inferred <= previousEnvelope + 0.000003, "inferred amplitude must decrease after key-up")
                assertTrue(inferred >= 0.0 && inferred < 1.0, "the first release sample is already attenuated")
                previousEnvelope = inferred
                checkpoints[minOf(3, (releaseFrames / declaredFrames * 4).toInt())] = true
                inferredSamples++
            }
        }
        assertTrue(engine.activeNotes.isEmpty(), "saved release must reap the held voice by its public deadline")
        assertTrue(abs(releaseFrames.toDouble() / f.rate - releaseSeconds) <= 0.001,
            "$name release lasted $releaseFrames frames, declared $releaseSeconds seconds")
        assertTrue(checkpoints.all { it }, "nonzero samples independently check all four release quarters")
        assertTrue(inferredSamples > 100)
        assertEquals(0f, one[0], "the reap frame emits no tail")
        repeat(3) {
            block.fill(123f)
            engine.render(block, 4096)
            assertTrue(block.all { it == 0f }, "no held voice reappears after key-up")
            assertTrue(engine.activeNotes.isEmpty())
        }
        assertEquals(sourceBefore, floatHash(source), "playback must not alter decoded PCM")
        if (f.folder != null) assertEquals(f.beforeFiles, snapshot(f.folder), "saved package inputs must remain byte-identical")
        return JsonValue.Obj(linkedMapOf(
            "fixture" to JsonValue.Str(name),
            "savedFileRead" to JsonValue.Bool(f.folder != null),
            "sourceFrames" to JsonValue.Num(source.size.toDouble()),
            "loopStart" to JsonValue.Num(loop.toDouble()),
            "loopPeriod" to JsonValue.Num(period.toDouble()),
            "heldFrames" to JsonValue.Num((holdFrames + 17).toDouble()),
            "heldWrapCrossings" to JsonValue.Num(((holdFrames + 17 - 1 - source.size) / period + 1).toDouble()),
            "heldMaximumAbsoluteError" to JsonValue.Num(0.0),
            "prefixCycleDifferencesAfterGain" to JsonValue.Num(distinctAfterGain.toDouble()),
            "releaseDeclaredSeconds" to JsonValue.Num(releaseSeconds),
            "releaseObservedFrames" to JsonValue.Num(releaseFrames.toDouble()),
            "releaseInferredSamples" to JsonValue.Num(inferredSamples.toDouble()),
            "releaseMaximumEnvelopeError" to JsonValue.Num(maxEnvelopeError),
            "releaseEnvelopeErrorBound" to JsonValue.Num(envelopeBound),
            "postReleaseSilentFrames" to JsonValue.Num((3 * 4096).toDouble()),
            "sourceFloatSha256" to JsonValue.Str(sourceBefore),
            "inputFilesSha256" to JsonValue.Obj(f.beforeFiles.mapValues { JsonValue.Str(it.value) }),
        ))
    }

    private fun snapshot(folder: File): Map<String, String> {
        val files = folder.walkTopDown().filter { it.isFile }.toList()
        require(files.size in 1..16 && files.sumOf { it.length() } <= 2_097_152) { "bounded playback fixture only" }
        return files.associate { it.relativeTo(folder).invariantSeparatorsPath to sha256(it) }.toSortedMap()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun floatHash(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4)
        for (sample in samples) {
            val bits = sample.toRawBits()
            for (i in 0..3) bytes[i] = (bits ushr (i * 8)).toByte()
            digest.update(bytes)
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
