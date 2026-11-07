package com.snipsnap.shell

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.ConfluencePersonalVoice
import com.snipsnap.synth.ConfluenceSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfluencePersonalLibraryTest {
    private val rate = WavWriter.MPC_SAMPLE_RATE
    private val start: Int get() = rate / 2
    private val end: Int get() = rate * 2

    private fun take(): Snip {
        val directory = listOf(File("../reference/fixtures/confluence"), File("reference/fixtures/confluence"))
            .firstOrNull { File(it, "vowel-male.wav").isFile } ?: error("real Confluence voice fixture is required")
        return WavReader.read(File(directory, "vowel-male.wav"))
    }

    private fun withRoot(test: (File) -> Unit) {
        val root = createTempDirectory("confluence-personal-library-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    private fun withVoice(test: (File, File, ConfluencePersonalLibrary.StoredVoice) -> Unit) = withRoot { root ->
        val library = File(root, "library")
        val voice = ConfluencePersonalLibrary.save(library, "MY AH", take(), start, end)
        test(root, library, voice)
    }

    private fun encoded(snip: Snip): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, snip, WavWriter.BitDepth.PCM_24)
    }.toByteArray()

    private fun replace(voice: ConfluencePersonalLibrary.StoredVoice, key: String, value: JsonValue) {
        val metadata = Json.parse(voice.manifestText).obj()
        voice.manifestFile.writeText(Json.write(JsonValue.Obj(metadata + (key to value))) + "\n")
    }

    @Test
    fun `save preserves untreated raw PCM and qualifies the exact persisted crop with defensive playback buffers`() = withRoot { root ->
        val input = take()
        input.samples[0] = -0.0f
        val expectedRaw = input.samples.copyOf()
        val selected = Snip(input.samples.copyOfRange(start, end), 1, rate)
        val expectedBytes = encoded(selected)
        val expectedSource = WavReader.read(expectedBytes)
        val expectedRoot = ConfluencePersonalVoice.prepare(expectedSource).referenceMidi
        val library = File(root, "library")
        val voice = ConfluencePersonalLibrary.save(library, "  MY AH  ", input, start, end,
            referenceMidi = expectedRoot, origin = ConfluencePersonalLibrary.Origin.IMPORT)
        assertEquals("MY AH", voice.name)
        assertEquals(ConfluencePersonalLibrary.Origin.IMPORT, voice.origin)
        assertEquals(start, voice.selectionStartFrame)
        assertEquals(end, voice.selectionEndFrame)
        assertContentEquals(expectedRaw, WavReader.read(voice.rawFile).samples)
        assertEquals(expectedRaw[0].toRawBits(), voice.rawSnip.samples[0].toRawBits(), "raw float WAV preserves signed zero too")
        assertContentEquals(expectedBytes, voice.sourceFile.readBytes())
        assertEquals(ConfluenceSource.fromSnip(expectedSource).hash, voice.source.hash)
        assertEquals(0, voice.region.startFrame)
        assertEquals(end - start, voice.region.endFrame)
        assertEquals(expectedRoot, voice.region.referenceMidi)
        assertEquals(expectedRoot, voice.assessment.referenceMidi)
        assertEquals(voice.source.hash, voice.prepared.sourceHash)
        assertTrue(voice.assessment.usable)

        input.samples.fill(0f)
        voice.rawSnip.samples.fill(0f)
        voice.sourceSnip.samples.fill(0f)
        assertContentEquals(expectedRaw, voice.rawSnip.samples)
        assertContentEquals(expectedSource.samples, voice.sourceSnip.samples)
        assertEquals(listOf(voice.summary), ConfluencePersonalLibrary.list(library))
        val metadata = Json.parse(voice.manifestText).obj()
        val provenance = metadata.getValue("provenance").obj()
        assertEquals(JsonValue.Null, provenance.getValue("license"), "a personal recording is never silently relicensed as factory CC BY")
        assertFalse(provenance.getValue("factorySource").bool())
        assertTrue(provenance.getValue("rawPreserved").bool())
    }

    @Test
    fun `portable kit voice reopens and renders identically after its original library is removed`() = withVoice { root, library, voice ->
        val kit = File(root, "kit").apply { mkdirs() }
        val copied = ConfluencePersonalLibrary.copyToKit(voice, kit)
        assertEquals(voice.id, copied.id)
        assertEquals(voice.manifestText, copied.manifestText)
        assertContentEquals(voice.rawFile.readBytes(), copied.rawFile.readBytes())
        assertContentEquals(voice.sourceFile.readBytes(), copied.sourceFile.readBytes())
        val before = ConfluencePersonalVoice.render(voice.prepared, performers = 1).snip.samples
        assertEquals(copied.directory, ConfluencePersonalLibrary.copyToKit(voice, kit).directory, "the same immutable copy is idempotent")
        library.deleteRecursively()
        val moved = File(root, "moved-kit")
        assertTrue(kit.renameTo(moved))
        val reopened = ConfluencePersonalLibrary.loadFromKit(moved, voice.id)
        assertEquals(copied.prepared.analysisKey, reopened.prepared.analysisKey)
        assertContentEquals(before, ConfluencePersonalVoice.render(reopened.prepared, performers = 1).snip.samples)
        assertContentEquals(copied.sourceSnip.samples, reopened.sourceSnip.samples)
    }

    @Test
    fun `saved native root crop region and assessment are bound to persisted audio independently of file hashes`() = withVoice { _, library, voice ->
        val sourceBytes = voice.sourceFile.readBytes()
        val metadata = Json.parse(voice.manifestText).obj()
        replace(voice, "referenceMidi", JsonValue.Num((voice.referenceMidi + .01f).toDouble()))
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.load(library, voice.id) }
        voice.manifestFile.writeText(voice.manifestText)
        val crop = metadata.getValue("selection").obj()
        replace(voice, "selection", JsonValue.Obj(crop + mapOf("startFrame" to JsonValue.Num((start + 1).toDouble()), "endFrame" to JsonValue.Num((end + 1).toDouble()))))
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.load(library, voice.id) }
        voice.manifestFile.writeText(voice.manifestText)
        val region = metadata.getValue("region").obj()
        replace(voice, "region", JsonValue.Obj(region + ("startFrame" to JsonValue.Num(1.0))))
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.load(library, voice.id) }
        voice.manifestFile.writeText(voice.manifestText)
        val assessment = metadata.getValue("assessment").obj()
        replace(voice, "assessment", JsonValue.Obj(assessment + ("confidence" to JsonValue.Num(.01))))
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.load(library, voice.id) }
        assertContentEquals(sourceBytes, voice.sourceFile.readBytes(), "metadata tampering does not require changed WAV bytes to refuse")
    }

    @Test
    fun `raw clipping is checked across selected input channels before downmix and excluded clipping can be trimmed away`() = withRoot { root ->
        val mono = take()
        val stereo = Snip(FloatArray(mono.frameCount * 2) { mono.samples[it / 2] }, 2, rate)
        val at = (start + rate / 2) * 2
        stereo.samples[at] = 1f
        stereo.samples[at + 1] = -.6f
        val refusal = assertFailsWith<IllegalArgumentException> {
            ConfluencePersonalLibrary.save(File(root, "bad"), "clipped", stereo, start, end)
        }
        assertTrue(refusal.message.orEmpty().contains("clips"))
        assertTrue(ConfluencePersonalLibrary.list(File(root, "bad")).isEmpty())

        mono.samples[0] = -1f
        val voice = ConfluencePersonalLibrary.save(File(root, "good"), "clean selected vowel", mono, start, end)
        assertEquals(-1f, voice.rawSnip.samples[0])
        assertEquals(1, Json.parse(voice.manifestText).obj().getValue("rawClippedSamples").int())
        assertTrue(voice.assessment.usable)
    }

    @Test
    fun `format conversion retains the original stereo take and makes crop coordinates explicitly canonical`() = withRoot { root ->
        val mono = Resampler.resample(take(), 48_000)
        val stereo = Snip(FloatArray(mono.frameCount * 2) { mono.samples[it / 2] }, 2, mono.sampleRate)
        val voice = ConfluencePersonalLibrary.save(File(root, "library"), "48k take", stereo, start, end)
        assertEquals(48_000, voice.rawSnip.sampleRate)
        assertEquals(2, voice.rawSnip.channels)
        assertContentEquals(stereo.samples, voice.rawSnip.samples)
        assertEquals(rate, voice.canonicalRawSnip.sampleRate)
        assertEquals(1, voice.canonicalRawSnip.channels)
        val expected = voice.canonicalRawSnip.samples.copyOfRange(start, end)
        assertContentEquals(encoded(Snip(expected, 1, rate)), voice.sourceFile.readBytes())
    }

    @Test
    fun `failed qualification or confirmation publishes no incomplete entry and successful saves never replace earlier identities`() = withRoot { root ->
        val library = File(root, "library")
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.save(library, "", take(), start, end) }
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.save(library, "wrong reference", take(), start, end, 72f) }
        assertTrue(ConfluencePersonalLibrary.list(library).isEmpty())
        assertTrue(library.listFiles().orEmpty().none { it.name.startsWith(".pending-") })
        val first = ConfluencePersonalLibrary.save(library, "same name", take(), start, end)
        val firstManifest = first.manifestFile.readBytes()
        val second = ConfluencePersonalLibrary.save(library, "same name", take(), start, end)
        assertNotEquals(first.id, second.id)
        assertContentEquals(firstManifest, first.manifestFile.readBytes())
        assertEquals(2, ConfluencePersonalLibrary.list(library).size)
        val pending = File(library, ".pending-interrupted").apply { mkdirs() }
        File(pending, "source.wav").writeBytes(byteArrayOf(0))
        assertEquals(2, ConfluencePersonalLibrary.list(library).size, "interrupted staging is not a saved voice")
    }

    @Test
    fun `missing corrupt oversized or escaped asset bundles refuse instead of substituting a factory voice`() = withVoice { root, library, voice ->
        val originalRaw = voice.rawFile.readBytes()
        originalRaw.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte(); voice.rawFile.writeBytes(it) }
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.load(library, voice.id) }
        voice.rawFile.writeBytes(originalRaw)
        val sourceMeta = Json.parse(voice.manifestText).obj().getValue("source").obj()
        replace(voice, "source", JsonValue.Obj(sourceMeta + ("path" to JsonValue.Str("../outside.wav"))))
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.load(library, voice.id) }
        voice.manifestFile.writeText(voice.manifestText)
        voice.manifestFile.writeText(" ".repeat(ConfluencePersonalLibrary.MAX_MANIFEST_BYTES.toInt() + 1))
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.load(library, voice.id) }
        voice.manifestFile.writeText(voice.manifestText)
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.load(library, "../${voice.id}") }
        val savedSource = voice.sourceFile.readBytes()
        voice.sourceFile.delete()
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.load(library, voice.id) }
        voice.sourceFile.writeBytes(savedSource)
        val outside = File(root, "outside.wav").apply { writeBytes(savedSource) }
        voice.sourceFile.delete()
        Files.createSymbolicLink(voice.sourceFile.toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.load(library, voice.id) }
        assertContentEquals(savedSource, outside.readBytes())
    }

    @Test
    fun `kit copies refuse occupied changed identities and unsafe target folders without replacing their contents`() = withVoice { root, _, voice ->
        val kit = File(root, "kit").apply { mkdirs() }
        val copy = ConfluencePersonalLibrary.copyToKit(voice, kit)
        replace(copy, "name", JsonValue.Str("changed on disk"))
        val changed = copy.manifestFile.readBytes()
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.copyToKit(voice, kit) }
        assertContentEquals(changed, copy.manifestFile.readBytes())

        val otherKit = File(root, "other-kit").apply { mkdirs() }
        val outside = File(root, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(otherKit, ConfluencePersonalLibrary.KIT_DIR).toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.copyToKit(voice, otherKit) }
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `nonfinite overlong and out of bounds takes cannot allocate or publish a voice`() = withRoot { root ->
        val library = File(root, "library")
        val nonfinite = take().also { it.samples[0] = Float.NaN }
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.save(library, "bad", nonfinite, start, end) }
        val long = Snip(FloatArray(rate * 31), 1, rate)
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.save(library, "too long", long, start, end) }
        assertFailsWith<IllegalArgumentException> { ConfluencePersonalLibrary.save(library, "bad range", take(), -1, end) }
        assertTrue(ConfluencePersonalLibrary.list(library).isEmpty())
    }
}
