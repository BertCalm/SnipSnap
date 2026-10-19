package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfluenceVoiceBankTest {
    companion object {
        private val bank by lazy { ConfluenceVoiceBank.bundled() }
        private val reports by lazy { bank.presets.associate { it.id to bank.render(it.id) } }

        // The old lyrical demonstration and its full originals are deliberately outside the bank.
        private val rowFileHashes = setOf(
            "433963079fbb6ce12b74d5d67bb73575aac8cf282edaedb429f9fcf556d87783",
            "d8a78ddc6dcfa60535107f5b7a5b081d6a16d20593c38f3965249069b0a25f4c",
            "601f875d9f550b1ac2e6ea95814b0634fcb6d8567b258f0f8522f704500f0911",
            "1f813de45396a2e8671aa3ad93248378668f14648a6a564fa4765abe07e59309",
        )

        private fun packaged(path: String): ByteArray = assertNotNull(
            ConfluenceVoiceBank::class.java.classLoader.getResourceAsStream("${ConfluenceVoiceBank.RESOURCE_ROOT}/$path"),
            "missing packaged bank asset $path",
        ).use { it.readBytes() }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun wavBytes(report: ConfluenceAssemblyReport): ByteArray = ByteArrayOutputStream().also {
            WavWriter.write(it, report.mix)
        }.toByteArray()
    }

    private fun changeManifest(edit: (MutableMap<String, JsonValue>) -> Unit): String {
        val root = Json.parse(bank.manifestText).obj().toMutableMap()
        edit(root)
        return Json.write(JsonValue.Obj(root))
    }

    private fun changeFirstVoice(field: String, value: JsonValue): String = changeManifest { root ->
        val voices = root.getValue("voices").arr().toMutableList()
        voices[0] = JsonValue.Obj(voices[0].obj() + (field to value))
        root["voices"] = JsonValue.Arr(voices)
    }

    @Test
    fun `packaged bank contains four audited wordless recordings and three source bound presets`() {
        assertEquals("confluence-wordless-starter", bank.id)
        assertEquals(1, bank.version)
        assertEquals(ConfluenceVoiceBank.DEFAULT_PRESET_ID, bank.mainPresetId)
        assertEquals(setOf("low-ah", "breathy-arc", "clear-arc", "round-oo"), bank.voices.map { it.id }.toSet())
        assertEquals(setOf("first-gathering", "breathing-room", "round-response"), bank.presets.map { it.id }.toSet())
        assertEquals(setOf("low-ah", "round-oo"), bank.voicesForRole(ConfluenceRole.FOUNDATION).map { it.id }.toSet())
        assertEquals(listOf("breathy-arc"), bank.voicesForRole(ConfluenceRole.AIRY).map { it.id })
        assertEquals(listOf("clear-arc"), bank.voicesForRole(ConfluenceRole.ANSWER).map { it.id })
        assertEquals(setOf(ConfluenceVoiceBank.MANIFEST_FILE, "LICENSE-CC-BY-4.0.txt") + bank.voices.map { it.assetPath }, bank.assetPaths.toSet())
        assertEquals(bank.assetPaths.size, bank.assetPaths.distinct().size)

        for (voice in bank.voices) {
            val bytes = packaged(voice.assetPath)
            val audio = WavReader.read(bytes)
            val provenance = voice.provenance.entries
            assertContentEquals(bytes, bank.assetBytes(voice.assetPath), voice.id)
            assertEquals(voice.fileSha256, sha256(bytes), voice.id)
            assertEquals(voice.region.assetHash, ConfluenceSource.fromSnip(audio).hash, voice.id)
            assertEquals(1, audio.channels)
            assertEquals(Confluence.RATE, audio.sampleRate)
            assertEquals(voice.frames, audio.frameCount)
            assertEquals(0, voice.region.startFrame)
            assertEquals(audio.frameCount, voice.region.endFrame, "${voice.id} retains its complete selected gesture")
            assertTrue(audio.samples.all { it.isFinite() })
            assertTrue(audio.peak() > .01f)
            assertTrue(voice.wordless)
            assertTrue(provenance.getValue("wordless").bool())
            assertTrue(provenance.getValue("usageFactoryCandidate").bool())
            assertFalse(provenance.getValue("demonstrationOnly").bool())
            assertEquals("CC BY 4.0", provenance.getValue("license").str())
            assertEquals("https://creativecommons.org/licenses/by/4.0/", provenance.getValue("licenseUrl").str())
            assertTrue(provenance.getValue("attribution").str().contains("VocalSet"))
            assertTrue(provenance.getValue("modifications").str().isNotBlank())
            assertFalse(provenance.getValue("originalArchiveMember").str().contains("_row_", ignoreCase = true))
            assertFalse(voice.fileSha256 in rowFileHashes)
            assertFalse(provenance.getValue("originalSha256").str() in rowFileHashes)
        }
        assertTrue(bank.voice("breathy-arc").frames >= 7 * Confluence.RATE, "the call remains a complete moving phrase")
        assertTrue(bank.voice("clear-arc").frames >= 8 * Confluence.RATE, "the answer remains a complete wordless phrase")
        for (path in bank.assetPaths) {
            assertContentEquals(packaged(path), bank.assetBytes(path), path)
            assertFalse(sha256(bank.assetBytes(path)) in rowFileHashes, "no lyrical fixture or full original is bundled")
        }
    }

    @Test
    fun `missing assets changed files and canonical PCM references refuse before rendering`() {
        val voice = bank.voices.first()
        for (missing in listOf("LICENSE-CC-BY-4.0.txt", voice.assetPath)) {
            val failure = assertFailsWith<IllegalStateException> {
                ConfluenceVoiceBank.load(bank.manifestText) { path ->
                    check(path != missing) { "missing test asset $path" }
                    bank.assetBytes(path)
                }
            }
            assertTrue(failure.message.orEmpty().contains(missing))
        }
        val changed = bank.assetBytes(voice.assetPath).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val badFile = assertFailsWith<IllegalArgumentException> {
            ConfluenceVoiceBank.load(bank.manifestText) { if (it == voice.assetPath) changed else bank.assetBytes(it) }
        }
        assertTrue(badFile.message.orEmpty().contains("WAV hash changed"))
        val wrongRegion = Json.parse(bank.manifestText).obj().getValue("voices").arr().first().obj().getValue("region").obj()
        val badPcm = assertFailsWith<IllegalArgumentException> {
            ConfluenceVoiceBank.load(changeFirstVoice("region", JsonValue.Obj(wrongRegion + ("assetHash" to JsonValue.Str("0".repeat(64))))), bank::assetBytes)
        }
        assertTrue(badPcm.message.orEmpty().contains("canonical PCM hash"))
        val badFrames = assertFailsWith<IllegalArgumentException> {
            ConfluenceVoiceBank.load(changeFirstVoice("frames", JsonValue.Num((voice.frames - 1).toDouble())), bank::assetBytes)
        }
        assertTrue(badFrames.message.orEmpty().contains("frame count"))
        val lyrical = assertFailsWith<IllegalArgumentException> {
            ConfluenceVoiceBank.load(changeFirstVoice("wordless", JsonValue.Bool(false)), bank::assetBytes)
        }
        assertTrue(lyrical.message.orEmpty().contains("not declared wordless"))
    }

    @Test
    fun `unsafe manifest paths never reach the asset provider`() {
        for (unsafe in listOf("../outside.wav", "/tmp/outside.wav", "voices/../../outside.wav", "voices\\outside.wav", "C:/outside.wav", "voices//outside.wav", ".", "")) {
            val licenseManifest = changeManifest { root ->
                root["license"] = JsonValue.Obj(root.getValue("license").obj() + ("path" to JsonValue.Str(unsafe)))
            }
            for (manifest in listOf(licenseManifest, changeFirstVoice("assetPath", JsonValue.Str(unsafe)))) {
                val reads = ArrayList<String>()
                val failure = assertFailsWith<IllegalArgumentException>(unsafe) {
                    ConfluenceVoiceBank.load(manifest) { path ->
                        reads += path
                        bank.assetBytes(path)
                    }
                }
                assertTrue(failure.message.orEmpty().contains("relative"), unsafe)
                assertFalse(unsafe in reads, "unsafe path must be rejected before provider access: $unsafe")
            }
        }
    }

    @Test
    fun `wordless eligibility and recorded role provenance cannot be relabeled independently`() {
        val provenance = bank.voices.first().provenance.entries
        for ((field, value) in mapOf(
            "wordless" to JsonValue.Bool(false),
            "usageFactoryCandidate" to JsonValue.Bool(false),
            "demonstrationOnly" to JsonValue.Bool(true),
            "sourceTechnique" to JsonValue.Str("breathy"),
            "roleEligibility" to JsonValue.Arr(listOf(JsonValue.Str("ANSWER"))),
            "id" to JsonValue.Str("different-fixture"),
        )) {
            assertFailsWith<IllegalArgumentException>(field) {
                ConfluenceVoiceBank.load(changeFirstVoice("provenance", JsonValue.Obj(provenance + (field to value))), bank::assetBytes)
            }
        }
    }

    @Test
    fun `directory resolution rejects symlinks that escape the portable bank`() {
        val temp = Files.createTempDirectory("confluence-bank-symlink-").toFile()
        try {
            val directory = File(temp, "bank")
            bank.writePortableBank(directory)
            val voice = bank.voices.first()
            val outside = File(temp, "outside.wav").also { it.writeBytes(bank.assetBytes(voice.assetPath)) }
            val inside = File(directory, voice.assetPath)
            assertTrue(inside.delete())
            Files.createSymbolicLink(inside.toPath(), outside.toPath())
            val failure = assertFailsWith<IllegalArgumentException> { ConfluenceVoiceBank.fromDirectory(directory) }
            assertTrue(failure.message.orEmpty().contains("escapes its directory"))
            assertContentEquals(bank.assetBytes(voice.assetPath), outside.readBytes())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `resolved asset bytes are independent of provider arrays and returned payload mutations`() {
        val payloads = bank.assetPaths.filter { it != ConfluenceVoiceBank.MANIFEST_FILE }.associateWith { bank.assetBytes(it) }
        val loaded = ConfluenceVoiceBank.load(bank.manifestText) { payloads.getValue(it) }
        for ((path, bytes) in payloads) {
            bytes.fill(0)
            val returned = loaded.assetBytes(path)
            assertContentEquals(bank.assetBytes(path), returned, path)
            returned.fill(0)
            assertContentEquals(bank.assetBytes(path), loaded.assetBytes(path), path)
        }
        assertFailsWith<IllegalArgumentException> { loaded.assetBytes("../outside.wav") }
        assertFailsWith<IllegalArgumentException> { loaded.voice("missing-voice") }
        assertFailsWith<IllegalArgumentException> { loaded.preset("missing-preset") }
    }

    @Test
    fun `all factory recipes serialize stable native source references and audited role bindings`() {
        for (preset in bank.presets) {
            val serialized = preset.recipe.toJsonText()
            val decoded = ConfluenceAssemblyRecipe.fromJsonText(serialized)
            assertEquals(preset.recipe, decoded, preset.id)
            assertEquals(serialized, decoded.toJsonText(), preset.id)
            assertEquals(ConfluenceRole.entries.toSet(), preset.roleVoiceIds.keys)
            for (role in decoded.roles) {
                val voice = bank.voice(preset.roleVoiceIds.getValue(role.role))
                assertTrue(role.role in voice.roles)
                assertEquals(voice.region, role.region)
            }
            assertFalse(serialized.contains("/workspace/"))
            assertFalse(serialized.contains("/tmp/"))
            assertFalse(serialized.contains("\"samples\""), "PCM stays outside the recipe")
        }
        assertTrue(bank.presets.map { it.recipe.toJsonText() }.distinct().size == 3)
    }

    @Test
    fun `portable bank moved to another directory reproduces identical audio bytes and traces`() {
        val temp = Files.createTempDirectory("confluence-bank-portable-").toFile()
        try {
            val original = File(temp, "original")
            bank.writePortableBank(original)
            val moved = File(temp, "moved-bank")
            Files.move(original.toPath(), moved.toPath())
            assertFalse(original.exists())
            val loaded = ConfluenceVoiceBank.fromDirectory(moved)
            assertEquals(bank.manifestText, loaded.manifestText)
            assertEquals(bank.attributionText, File(moved, "SOURCE-ATTRIBUTION.txt").readText())
            for (path in bank.assetPaths) assertContentEquals(bank.assetBytes(path), loaded.assetBytes(path), path)
            val before = reports.getValue(bank.mainPresetId)
            val after = loaded.render()
            assertContentEquals(before.mix.samples, after.mix.samples)
            assertContentEquals(wavBytes(before), wavBytes(after), "ordinary 24-bit WAVs reproduce byte for byte")
            assertEquals(before.events, after.events)
            assertEquals(before.performers, after.performers)
            assertEquals(before.diagnosticsText(), after.diagnosticsText())
            assertFailsWith<IllegalArgumentException> { bank.writePortableBank(moved) }
            val asset = File(moved, loaded.voices.first().assetPath)
            assertTrue(asset.delete())
            val missing = assertFailsWith<IllegalArgumentException> { ConfluenceVoiceBank.fromDirectory(moved) }
            assertTrue(missing.message.orEmpty().contains("missing Confluence bank asset"))
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `factory arrangements admit complete selected gestures and responses follow full calls`() {
        for (preset in bank.presets) {
            val report = reports.getValue(preset.id)
            assertTrue(report.exchanges > 0)
            assertEquals((preset.recipe.seconds * Confluence.RATE).roundToInt(), report.mix.frameCount)
            assertTrue(report.mix.samples.all { it.isFinite() })
            assertTrue(report.mix.peak() in .01f..1f)
            val specs = preset.recipe.roles.associateBy { it.role }
            for (event in report.events) {
                val spec = specs.getValue(event.role)
                val region = spec.region
                assertEquals(region.assetHash, event.sourceHash)
                assertEquals(if (event.role == ConfluenceRole.FOUNDATION) region.sustainStartFrame else region.startFrame, event.sourceStartFrame)
                assertEquals(if (event.role == ConfluenceRole.FOUNDATION) region.sustainEndFrame else region.endFrame, event.sourceEndFrame)
                assertTrue(event.performerId in spec.performerIds)
                assertTrue(event.startFrame >= 0 && event.endFrame <= report.mix.frameCount && event.endFrame > event.startFrame)
            }
            for (performer in report.performers) {
                val states = report.events.filter { it.performerId == performer.id }.sortedBy { it.startFrame }
                assertEquals(0, states.first().startFrame)
                assertEquals(report.mix.frameCount, states.last().endFrame)
                for ((previous, next) in states.zipWithNext()) assertEquals(previous.endFrame, next.startFrame, "${preset.id} performer ${performer.id} has exclusive contiguous states")
                for (phrase in states.filter { it.state in setOf("ENTER", "SING", "RELEASE") }.groupBy { it.eventIndex }.values) {
                    assertEquals(listOf("ENTER", "SING", "RELEASE"), phrase.map { it.state })
                    val enter = phrase.first()
                    assertEquals(((enter.sourceEndFrame - enter.sourceStartFrame) * enter.durationRatio).roundToInt(), phrase.last().endFrame - enter.startFrame,
                        "${preset.id} performer ${performer.id} must finish the selected source region")
                }
            }
            for (exchange in 0 until report.exchanges) {
                val callEnd = report.events.filter { it.role == ConfluenceRole.AIRY && it.eventIndex == exchange && it.state == "RELEASE" }.maxOf { it.endFrame }
                val answerStart = report.events.filter { it.role == ConfluenceRole.ANSWER && it.eventIndex == exchange && it.state == "ENTER" }.minOf { it.startFrame }
                assertTrue(answerStart - callEnd >= (preset.recipe.answerDelaySeconds * Confluence.RATE).roundToInt() - 1)
            }
        }
    }

    @Test
    fun `eight render only pads retain source attribution recipes and original contribution gains`() {
        val pads = bank.arrangedPads().map { assertNotNull(it) }
        assertEquals(ConfluenceVoiceBank.STARTER_PAD_COUNT, pads.size)
        for (pad in pads) {
            assertEquals(null, pad.recipe, "asset dependent arrangements are not asset free Patch recipes")
            assertEquals(1, pad.snip.channels)
            assertEquals(Confluence.RATE, pad.snip.sampleRate)
            assertEquals(bank.id, pad.source["bankId"])
            assertEquals("CC-BY-4.0", pad.source["license"])
            assertEquals("https://creativecommons.org/licenses/by/4.0/", pad.source["licenseUrl"])
            assertTrue(pad.source.getValue("attribution").contains("VocalSet"))
            assertTrue(pad.source.getValue("sourceUrl").contains("zenodo.org/records/1193957"))
            assertTrue(pad.source.getValue("modifications").isNotBlank())
        }
        for (preset in bank.presets) {
            val pad = pads.single { it.displayName == preset.title }
            assertEquals(preset.recipe.toJsonText(), pad.source["assemblyRecipe"])
            assertContentEquals(reports.getValue(preset.id).mix.samples, pad.snip.samples)
        }
        val report = reports.getValue(bank.mainPresetId)
        val low = report.stems.getValue(ConfluenceRole.FOUNDATION).samples
        val air = report.stems.getValue(ConfluenceRole.AIRY).samples
        val contribution = pads.single { it.displayName == "LOW AND AIR" }
        assertContentEquals(FloatArray(low.size) { low[it] + air[it] }, contribution.snip.samples,
            "the isolated contribution retains the full gathering's normalization gain")
        assertEquals("FOUNDATION,AIRY", contribution.source["roles"])
        assertTrue(bank.attributionText.contains("Original:"))
        assertTrue(bank.attributionText.contains("Source preparation:"))
        assertTrue(bank.attributionText.contains("creativecommons.org/licenses/by/4.0/"))
    }
}
