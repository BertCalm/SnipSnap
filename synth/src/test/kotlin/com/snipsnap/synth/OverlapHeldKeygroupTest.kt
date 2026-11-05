package com.snipsnap.synth

import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlapHeldKeygroupTest {
    @Test
    fun `unrelated nonempty output is refused before any rendering or replacement`() {
        val directory = createTempDirectory("overlap-unrelated-output").toFile()
        try {
            val userFile = File(directory, "my-recording.wav").apply { writeBytes(byteArrayOf(3, 1, 4, 1, 5)) }
            val original = userFile.readBytes()
            val rejection = assertFailsWith<IllegalArgumentException> { OverlapHeldKeygroupGenerator.generate(directory) }
            assertTrue(rejection.message.orEmpty().contains("Refusing to replace nonempty output"),
                "The output ownership guard must reject the directory before rendering")
            assertContentEquals(original, userFile.readBytes())
            assertEquals(listOf(userFile.name), directory.listFiles()!!.map { it.name })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `three reference zones tile a bounded keyboard and retain the heard steady settings`() {
        val zones = OverlapHeldKeygroupGenerator.zones()
        assertEquals(listOf(36, 48, 60), zones.map { it.root })
        assertEquals(listOf(30..42, 43..54, 55..66), zones.map { it.low..it.high })
        assertEquals((30..66).toList(), zones.flatMap { (it.low..it.high).toList() })
        for (zone in zones) assertTrue(zone.root - zone.low <= 6 && zone.high - zone.root <= 6)
        for (topology in OverlapTopology.entries) {
            assertEquals(mapOf("KNOT" to if (topology == OverlapTopology.SELF) 0f else 1f,
                "PULSE" to 0f, "LOCK" to 0f, "GLOW" to 0f, "RING" to .6f, "HOLD" to 1f),
                OverlapHeldKeygroupGenerator.controls(topology))
        }
        assertFailsWith<IllegalArgumentException> { OverlapHeldKeygroupGenerator.zones(listOf(36, 60)) }
    }

    @Test
    fun `actual steady bodies survive MPC two MPC three WAV and sidecar export with repeatable archive`() {
        val directory = createTempDirectory("overlap-held-keygroups").toFile()
        try {
            // Export every actual topology at the heard reference. The distribution
            // generator uses the same path with three independently rendered roots.
            val archive = OverlapHeldKeygroupGenerator.generate(File(directory, "trial"), listOf(48))
            val output = archive.parentFile
            val manifest = Json.parse(File(output, "manifest.json").readText()).obj()
            assertEquals(4, manifest.getValue("programCount").int())
            assertEquals(4, manifest.getValue("sampleCount").int())
            val gain = manifest.getValue("collectionGain").num()
            assertTrue(gain > 0.0 && gain <= 1.0)
            val programs = (manifest.getValue("programs") as JsonValue.Arr).items
            for (entry in programs) {
                val program = entry.obj()
                val sidecar = File(output, program.getValue("instrumentSidecar").str())
                val instrument = InstrumentStore.read(sidecar)
                assertEquals(.6f, instrument.release)
                // The single zone spans the whole keyboard: the packaging door stretches it to 0..127.
                assertEquals(0..127, instrument.zones.single().let { it.lowNote..it.highNote })
                val stored = requireNotNull(instrument.zoneFor(48))
                assertEquals(48, stored.rootNote)
                val zone = (program.getValue("zones") as JsonValue.Arr).items.single().obj()
                val frames = zone.getValue("frames").int()
                val loopStart = zone.getValue("loopStartFrame").int()
                assertEquals(2 * loopStart, frames)
                assertEquals(frames, zone.getValue("loopEndExclusive").int())
                val wav = File(output, zone.getValue("sample").str())
                val samples = WavReader.read(wav).samples
                assertEquals(frames, samples.size)
                assertEquals(0f, samples[0], "The sampler prefix must begin at silence")
                assertEquals(SmplChunk(48, SmplChunk.Loop(loopStart.toLong(), frames.toLong())),
                    WavReader.readSmplStrict(wav))
                assertEquals(loopStart, smplWireInt(wav, 44), "RIFF loop start is an absolute frame")
                assertEquals(frames - 1, smplWireInt(wav, 48), "RIFF loop end is inclusive on the wire")
                val ramp = (OverlapHeldKeygroupGenerator.ONSET_SECONDS * WavWriter.MPC_SAMPLE_RATE).toInt()
                assertContentEquals(samples.copyOfRange(ramp, loopStart), samples.copyOfRange(loopStart + ramp, frames),
                    "Only the onset prefix may change; the rest repeats the exact exported body")
                val metrics = zone.getValue("metrics").obj()
                for (name in listOf("pcm24PrecedingSeam", "pcm24FollowingSeam", "pcm24OnsetToLoopSeam")) {
                    assertTrue(metrics.getValue(name).num() < Keys.MAX_SEAM_ERROR, "$name exceeds the physical-neighbour seam bound")
                }
                assertEquals(OverlapHeldKeygroupGenerator.sha(wav), zone.getValue("wavSha256").str())
                assertEquals(OverlapHeldKeygroupGenerator.sha(File(output, program.getValue("mpc2Program").str())),
                    program.getValue("programSha256").str())
                val recipe = Json.parse(File(output, zone.getValue("recipe").str()).readText()).obj()
                assertEquals(program.getValue("topology").str(), recipe.getValue("topology").str())
                assertEquals(48, recipe.getValue("referenceMidi").int())
                assertEquals(.8f, recipe.getValue("velocity").num().toFloat())
                assertEquals(0.0, recipe.getValue("macros").obj().getValue("PULSE").num())
                assertEquals(0.0, recipe.getValue("macros").obj().getValue("GLOW").num())
            }

            // Removing the Note On declaration must make actual file verification
            // fail, rather than silently counting a looping one-shot as a sustain.
            val entry = programs.first().obj()
            val instrument = InstrumentStore.read(File(output, entry.getValue("instrumentSidecar").str()))
            val zone = instrument.zones.single()
            val stem = File(zone.sample).nameWithoutExtension
            val expected = KeygroupProgram(instrument.name, listOf(Keygroup(zone.lowNote, zone.highNote, zone.rootNote,
                listOf(VelocityLayer(stem, zone.frameCount, 0, 127, zone.loopStartFrame)))),
                volumeRelease = .6f, explicitNoteOn = true)
            val bad = File(directory, "one-shot.xpm")
            bad.writeText(File(output, entry.getValue("mpc2Program").str()).readText()
                .replace("<TriggerMode>2</TriggerMode>", "<TriggerMode>0</TriggerMode>"))
            assertFailsWith<IllegalStateException> {
                OverlapHeldKeygroupGenerator.verifyPrograms(expected, bad,
                    File(output, entry.getValue("mpc3Track").str()),
                    File(output, entry.getValue("instrumentSidecar").str()))
            }

            val checksums = File(output, "checksums.sha256").readLines()
            assertTrue(checksums.isNotEmpty())
            for (line in checksums) {
                assertEquals(line.take(64), OverlapHeldKeygroupGenerator.sha(File(output, line.drop(66))))
            }
            val firstBytes = archive.readBytes()
            OverlapHeldKeygroupGenerator.writeArchive(output, archive)
            assertContentEquals(firstBytes, archive.readBytes(), "Runtime measurements must not change the archive")
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertEquals(entries.map { it.name }.sorted(), entries.map { it.name })
                assertFalse(entries.any { it.name.endsWith("runtime.json") })
                assertEquals(4, entries.count { it.name.endsWith(".xpm") })
                assertEquals(4, entries.count { it.name.endsWith(".xty") })
                assertEquals(4, entries.count { it.name.endsWith(".wav") })
                assertTrue(entries.all { it.time == 0L })
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `prefix articulation leaves the approved independently measured body unchanged`() {
        val loop = OverlapHeldPrototype.renderLoopMeasured(OverlapTopology.TREFOIL,
            OverlapHeldKeygroupGenerator.controls(OverlapTopology.TREFOIL), midi = 48,
            velocity = .8f, periodSeconds = .5)
        val gain = .5
        val sample = OverlapHeldKeygroupGenerator.sample(loop, gain)
        val body = FloatArray(loop.snip.samples.size) { (loop.snip.samples[it] * gain).toFloat() }
        assertContentEquals(body, sample.samples.copyOfRange(body.size, sample.samples.size))
        val ramp = (OverlapHeldKeygroupGenerator.ONSET_SECONDS * WavWriter.MPC_SAMPLE_RATE).toInt()
        assertContentEquals(body.copyOfRange(ramp, body.size), sample.samples.copyOfRange(ramp, body.size))
        assertEquals(0f, sample.samples[0])
        // Match the packed PCM body against the independent physical successor,
        // rather than establishing recurrence by duplicating a buffer.
        val quantizedContext = ByteArrayOutputStream().let { stream ->
            val context = FloatArray(loop.seamContext.size) { (loop.seamContext[it] * gain).toFloat() }
            WavWriter.write(stream, com.snipsnap.audio.Snip(context, 1, WavWriter.MPC_SAMPLE_RATE))
            WavReader.read(stream.toByteArray()).samples
        }
        val length = body.size
        assertTrue(Keys.seamError(quantizedContext.copyOfRange(length, 3 * length), length) < Keys.MAX_SEAM_ERROR)
    }

    private fun smplWireInt(file: File, fieldOffset: Int): Int {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val tag = String(bytes, cursor, 4, Charsets.US_ASCII)
            val size = buffer.getInt(cursor + 4)
            if (tag == "smpl") return buffer.getInt(cursor + 8 + fieldOffset)
            cursor += 8 + size + (size and 1)
        }
        error("Missing smpl chunk")
    }
}
