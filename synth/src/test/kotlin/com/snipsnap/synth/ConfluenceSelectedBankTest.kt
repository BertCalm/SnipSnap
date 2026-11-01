package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfluenceSelectedBankTest {
    private val bank by lazy { ConfluenceSelectedBank.bundled() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun changedVoice(key: String, value: JsonValue): String {
        val root = Json.parse(bank.manifestText).obj()
        val voices = root.getValue("voices").arr().toMutableList()
        voices[0] = JsonValue.Obj(voices[0].obj() + (key to value))
        return Json.write(JsonValue.Obj(root + ("voices" to JsonValue.Arr(voices))))
    }

    @Test
    fun `actual licensed canonical PCM hashes match Kotlin and raw selected voices stay untreated`() {
        assertEquals(9, bank.voices.size)
        assertEquals(7, bank.presets.size)
        assertEquals(13, bank.assetPaths.size)
        assertEquals(sha(bank.assetBytes("bank.json")), bank.manifestSha256)
        assertEquals(setOf("CC-BY-4.0", "CC0-1.0"), bank.voices.map { it.licenseId }.toSet())
        assertTrue(bank.attributionText.contains("Kaustuv Kanti Ganguli"))
        assertTrue(bank.attributionText.contains("Anna Moraeus"))
        assertTrue(bank.attributionText.contains("performer", ignoreCase = true))
        assertFalse(bank.manifestText.contains("/workspace/"))
        for (voice in bank.voices) {
            val bytes = bank.assetBytes(voice.assetPath)
            assertEquals(voice.fileSha256, sha(bytes))
            val decoded = WavReader.read(bytes)
            assertEquals(voice.canonicalPcmSha256, ConfluenceSource.fromSnip(decoded).hash, voice.id)
            assertEquals(voice.frames, decoded.frameCount)
            assertContentEquals(decoded.samples, bank.rawSource(voice.id).samples)
            assertEquals(ConfluenceSourceMode.TEXTURE, voice.region.mode)
            assertEquals(null, voice.region.referenceMidi)
            assertEquals(null, voice.region.sustainStartFrame)
            assertEquals("UNKNOWN", voice.textContent)
            assertEquals(voice.frames, voice.region.endFrame)
            bank.rawSource(voice.id).samples.fill(0f)
            bank.assetBytes(voice.assetPath).fill(0)
            assertContentEquals(decoded.samples, bank.rawSource(voice.id).samples)
        }
    }

    @Test
    fun `prepared source identities match independent StrictMath reference hashes for every approved passage`() {
        // Pinned by a separate Java reference over raw PCM24, declared Float gains and half-cosine
        // envelopes. These are not calculated by calling the production conditioning function.
        // They also matched all pre-change Math.cos outputs on the preparation JVM (Java17).
        val expected = mapOf(
            "yaman-01" to "7428e455a1f471052f748976c42e41c739299705c76a87502b8163a2b12c4c3c",
            "yaman-02" to "96ec518a10641c233ff76880449c4d0ea588f1d8f5ecd02be95493f73a3cd401",
            "yaman-03" to "79c8043b7b66d90078fe92aca62ee8dc1109b81f00ceafaa4032cc307f1a68b6",
            "yaman-04" to "b080750626a642230e13bb37787ac4bd4f7773bc55e573e1687eafeb2ad1ccb8",
            "kulning-01" to "ff9a036622cf322f678c131fa0d96b84135c96a55cc8891102456d31d52d9f75",
            "kulning-02" to "5fffe92a28ca241fa3f1e7d65fa4f8b2cbe5bb65a2ea7d2ceabeb16c88e1a08c",
            "kulning-03" to "e50cb1949aa33f6059e0b6850a5cf60c005446df3a7f7183e6ef47840a77546a",
            "khoomei-01" to "d05e20e8d9a614292b95b2fb4305e2d0862dc5585cca041844850948fc366c0c",
            "khoomei-02" to "b1b8b4c4cbffbc3db4b50a158c37ba612fc7247eeb220aa6c06fb8afbfaa9520",
        )
        assertEquals(expected.keys, bank.voices.map { it.id }.toSet())
        for ((id, hash) in expected) {
            assertEquals(hash, bank.voice(id).region.assetHash, id)
            assertEquals(hash, ConfluenceSource.fromSnip(bank.reference(id)).hash, id)
        }
    }

    @Test
    fun `reference conditioning is explicit reproducible and preserves raw crop with disclosed hot release`() {
        for (voice in bank.voices) {
            val raw = bank.rawSource(voice.id)
            val expected = FloatArray(raw.frameCount) { i ->
                var envelope = 1f
                if (i < voice.fadeInFrames) envelope *= (.5 - .5 * StrictMath.cos(PI * i / voice.fadeInFrames)).toFloat()
                if (i >= raw.frameCount - voice.fadeOutFrames) envelope *= (.5 - .5 * StrictMath.cos(PI * (raw.frameCount - 1 - i) / voice.fadeOutFrames)).toFloat()
                raw.samples[i] * voice.referenceGain * envelope
            }
            val reference = bank.reference(voice.id)
            assertContentEquals(expected, reference.samples, voice.id)
            assertEquals(voice.region.assetHash, ConfluenceSource.fromSnip(reference).hash)
            assertNotEquals(voice.rawRegion.assetHash, voice.region.assetHash)
            assertEquals(0f, abs(reference.samples.first()))
            assertEquals(0f, abs(reference.samples.last()))
            assertTrue(reference.peak() <= 1f)
            reference.samples.fill(0f)
            assertContentEquals(expected, bank.reference(voice.id).samples)
        }
        val hot = bank.voice("yaman-04")
        assertEquals(4410, hot.fadeOutFrames, "the selected active ending needs its disclosed100ms playback release")
        assertTrue(bank.rawSource(hot.id).samples.takeLast(441).sumOf { it.toDouble() * it } > 1e-4,
            "this regression must exercise the actual active raw ending")
        val tail = bank.reference(hot.id).samples.takeLast(441)
        val tailRms = sqrt(tail.sumOf { it.toDouble() * it } / tail.size)
        assertTrue(tailRms < .003, "the explicit release must reach a quiet ending")
    }

    @Test
    fun `every factory arrangement admits complete parts finite quiet tails and no inferred pitch`() {
        for (preset in bank.presets) {
            val report = bank.render(preset.id)
            assertEquals(preset.parts, report.diagnostics.activePerformers, preset.id)
            assertEquals((0 until preset.parts).toSet(), report.events.filter { it.state == "ENTER" }.map { it.performerId }.toSet())
            assertTrue(report.snip.samples.all { it.isFinite() })
            assertTrue(report.snip.peak() in .01f..1f)
            assertTrue(abs(report.diagnostics.dc) < .005f)
            assertFalse(report.diagnostics.holdSupported)
            assertEquals(0f, report.diagnostics.voicedShare, "cached TEXTURE has no pitch confidence evidence")
            assertTrue(report.diagnostics.processor.contains("varispeed"))
            assertTrue(report.performers.all { it.formantScale == 1f && it.pitchCents == 0f && it.vibratoCents == 0f })
            assertTrue(report.events.all { it.endFrame <= report.snip.frameCount && it.pitchSemitones == 0f && it.formantScale == 1f })
            for (part in report.performers) {
                val events = report.events.filter { it.performerId == part.id && it.state in setOf("ENTER", "SING", "RELEASE") }
                for (eventIndex in events.map { it.eventIndex }.distinct()) {
                    val body = events.filter { it.eventIndex == eventIndex }
                    val voice = bank.voices.first { it.region.assetHash == body.first().sourceHash }
                    val fullFrames = (voice.frames * part.durationRatio).toInt()
                    assertTrue(abs((body.maxOf { it.endFrame } - body.minOf { it.startFrame }) - fullFrames) <= 1,
                        "${preset.id} must complete each selected region, including its final note")
                }
            }
            assertTrue(report.raw.takeLast(Confluence.RATE / 5).maxOf { abs(it) } < .02f, "room must retain finite ending margin")
        }
    }

    @Test
    fun `cached chronological material preserves existing Confluence texture PCM exactly`() {
        val recipe = bank.preset("kulning-duo").recipe
        val voice = bank.voice("kulning-01")
        val source = ConfluenceSource.fromSnip(bank.reference(voice.id))
        val expected = Confluence.render(recipe, mapOf(source.hash to source))
        val actual = bank.render(recipe)
        assertContentEquals(expected.snip.samples, actual.snip.samples)
        assertContentEquals(expected.raw, actual.raw)
        assertContentEquals(expected.dry.samples, actual.dry.samples)
        assertEquals(expected.events.map { it.copy(formantScale = 1f) }, actual.events)
    }

    @Test
    fun `arbitrary verified recipes replay with bounded cache and callers cannot poison completed audio`() {
        val recipe = bank.preset("khoomei-duo").recipe.copy(name = "EDITED", seed = 884,
            macros = bank.preset("khoomei-duo").recipe.macros + ("SPACE" to .33f))
        val first = bank.render(recipe)
        val expected = first.snip.samples.copyOf()
        first.snip.samples.fill(0f); first.raw.fill(0f); first.dry.samples.fill(0f)
        val hit = bank.render(ConfluenceRecipe.fromJsonText(recipe.toJsonText()))
        assertContentEquals(expected, hit.snip.samples)
        assertEquals(1L, bank.cacheStats().hits)
        val changed = bank.render(recipe.copy(macros = recipe.macros + ("SPACE" to 0f)))
        assertFalse(changed.snip.samples.contentEquals(expected), "a room change must miss the exact render key")
        bank.render(recipe.copy(seed = recipe.seed + 1))
        assertEquals(2, bank.cacheStats().renderEntries)
        assertEquals(9, bank.cacheStats().preparedMaterials)
        assertEquals(3L, bank.cacheStats().misses)
    }

    @Test
    fun `root mode crop register variation and unsupported long response cannot bypass bank qualification`() {
        val recipe = bank.preset("yaman-duo").recipe
        val region = recipe.regions.first()
        for (bad in listOf(
            recipe.copy(midi = 49),
            recipe.copy(regions = listOf(region.copy(mode = ConfluenceSourceMode.PITCHED, referenceMidi = 49f))),
            recipe.copy(regions = listOf(region.copy(endFrame = region.endFrame - 1000))),
            recipe.copy(regions = listOf(region.copy(assetHash = "0".repeat(64)))),
            recipe.copy(macros = recipe.macros + ("REGISTER" to .01f)),
            recipe.copy(macros = recipe.macros + ("DIVERGENCE" to .2f)),
        )) assertFailsWith<IllegalArgumentException> { bank.render(bad) }
        val longest = bank.voice("yaman-04")
        assertFailsWith<IllegalArgumentException> {
            bank.render(recipe.copy(profile = ConfluenceProfile.RESPONSE, regions = listOf(longest.region), seconds = 30f))
        }
        assertFailsWith<IllegalArgumentException> { recipe.copy(hold = true) }
    }

    @Test
    fun `changed raw PCM identity bytes chronology or text claims refuse before they become voices`() {
        for ((key, value) in listOf(
            "canonicalPcmSha256" to JsonValue.Str("0".repeat(64)),
            "frames" to JsonValue.Num(100.0),
            "textContent" to JsonValue.Str("WORDLESS"),
            "referenceMidi" to JsonValue.Num(49.0),
            "mode" to JsonValue.Str("PITCHED"),
        )) assertFailsWith<IllegalArgumentException>(key) { ConfluenceSelectedBank.load(changedVoice(key, value), bank::assetBytes) }
        val path = bank.voices.first().assetPath
        assertFailsWith<IllegalArgumentException> {
            ConfluenceSelectedBank.load(bank.manifestText) { if (it == path) bank.assetBytes(it).also { b -> b[b.lastIndex] = (b.last().toInt() xor 1).toByte() } else bank.assetBytes(it) }
        }
        val badProvenance = bank.voices.first().provenance.entries.toMutableMap()
        val native = badProvenance.getValue("nativeDecodedSelection").obj()
        badProvenance["nativeDecodedSelection"] = JsonValue.Obj(native + ("startFrame" to JsonValue.Num(0.0)))
        assertFailsWith<IllegalArgumentException> { ConfluenceSelectedBank.load(changedVoice("provenance", JsonValue.Obj(badProvenance)), bank::assetBytes) }
    }

    @Test
    fun `portable bank reopens identically after moving and rejects symbolic links and unsafe provider paths`() {
        val root = createTempDirectory("selected-bank-").toFile()
        try {
            val original = File(root, "first")
            bank.writePortableBank(original)
            val moved = File(root, "moved")
            assertTrue(original.renameTo(moved))
            val reopened = ConfluenceSelectedBank.fromDirectory(moved)
            assertEquals(bank.manifestSha256, reopened.manifestSha256)
            assertContentEquals(bank.render("kulning-response").snip.samples, reopened.render("kulning-response").snip.samples)
            assertFailsWith<IllegalArgumentException> { bank.writePortableBank(moved) }
            val link = File(root, "link")
            Files.createSymbolicLink(link.toPath(), moved.toPath())
            assertFailsWith<IllegalArgumentException> { ConfluenceSelectedBank.fromDirectory(link) }
            assertFailsWith<IllegalArgumentException> { bank.writePortableBank(File(link, "child")) }
            val asset = File(moved, bank.voices.first().assetPath)
            val real = File(root, "real.wav"); assertTrue(asset.renameTo(real))
            Files.createSymbolicLink(asset.toPath(), real.toPath())
            assertFailsWith<IllegalArgumentException> { ConfluenceSelectedBank.fromDirectory(moved) }
        } finally { root.deleteRecursively() }
        val rootJson = Json.parse(bank.manifestText).obj()
        val declarations = rootJson.getValue("assets").arr().toMutableList()
        declarations[0] = JsonValue.Obj(declarations[0].obj() + ("path" to JsonValue.Str("../escape.txt")))
        val unsafe = Json.write(JsonValue.Obj(rootJson + ("assets" to JsonValue.Arr(declarations))))
        val reads = ArrayList<String>()
        assertFailsWith<IllegalArgumentException> { ConfluenceSelectedBank.load(unsafe) { reads += it; bank.assetBytes(it) } }
        assertTrue(reads.isEmpty(), "unsafe first path must refuse before any provider read")
    }

    @Test
    fun `sixteen starter sounds carry portable exact bank bindings and complete mode recipes`() {
        val pads = bank.arrangedPads().map { requireNotNull(it) }
        assertEquals(16, pads.size)
        assertEquals(List(9) { "REFERENCE" } + List(7) { "PARTS" }, pads.map { it.source["mode"] })
        for (pad in pads) {
            assertEquals("CONFLUENCE_SOURCE_BANK", pad.source["engine"])
            assertEquals(bank.manifestSha256, pad.source["bankManifestSha256"])
            assertTrue(pad.source.getValue("usedVoiceIds").split(',').all { id -> bank.voice(id).textContent == "UNKNOWN" })
            Json.parse(pad.source.getValue("bankRecipe"))
            assertTrue(pad.source.getValue("attribution").isNotBlank())
            assertTrue(pad.source.getValue("sourceUrl").startsWith("https://"))
        }
    }
}
