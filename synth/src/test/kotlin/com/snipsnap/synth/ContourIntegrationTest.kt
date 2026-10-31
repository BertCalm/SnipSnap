package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.KeygroupWriter
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Complete structural recipes survive the same save/edit/velocity/export routes as other engines. */
class ContourIntegrationTest {
    private val temp = java.nio.file.Files.createTempDirectory("contour-integration").toFile()

    @AfterTest
    fun cleanUp() { temp.deleteRecursively() }

    @Test
    fun `all structural fields and excitation survive patch recipe and edit round trips`() {
        for (voice in ContourVoice.entries) {
            val config = ContourConfig.forVoice(voice).copy(
                bodyTable = ContourTable.SOFT_SAW, edgeTable = ContourTable.COPPER,
                edgeRatio = 1.5f, edgeLag = .31f, relation = ContourRelation.CURVE,
                bodyLevel = .63f, edgeLevel = .41f, subLevel = .03f,
                bodyHarmonics = 1.22f, edgeRootLevel = .35f,
                edgeAttack = .031f, edgeDecay = .28f, edgeSustain = .12f,
                bodyScanBias = -.13f, edgeScanBias = .16f, bodyTravel = .24f, edgeTravel = .69f,
                ampAttack = .027f, ampDecay = .51f, ampSustain = .43f,
                ampRelease = .76f, sustainSeconds = 1.2f,
                filterAttack = .06f, filterDecay = .42f, filterAmount = .39f,
                motionShape = ContourMotionShape.CYCLE, motionSeconds = 1.7f,
                filterType = ContourFilterType.LOW_PASS, loopSeconds = 2.6f,
            )
            val macros = ContourEngine.defaults(voice) + mapOf("TUNE" to .625f, "OFFSET" to .61f)
            val patch = ContourPatch("Saved $voice", voice, macros, config, .63f)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()), voice.name)
            assertEquals(patch, ContourPatch.fromJsonText(patch.toJsonText()), voice.name)
            val recipe = PadRecipe(patch, FxChain(tape = mapOf("WOBBLE" to .1f)))
            assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()), voice.name)
            val edited = Patches.edited(patch, "Edited $voice", mapOf("MOTION" to .15f))
            assertEquals(patch.copy(name = "Edited $voice", macros = mapOf("MOTION" to .15f)), edited)
            assertContentEquals(patch.render().samples, Patches.fromJsonText(patch.toJsonText()).render().samples, voice.name)
        }
    }

    @Test
    fun `old model one JSON uses literal neutral timbre controls when fields or config are absent`() {
        for (voice in ContourVoice.entries) {
            val source = ContourPatch("Legacy $voice", voice, ContourEngine.defaults(voice),
                config = ContourConfig.legacyForVoice(voice)).toJsonValue().entries
            val fullConfig = source.getValue("config").obj()
            val expected = ContourConfig.legacyForVoice(voice).copy(
                bodyHarmonics = 1f, edgeRootLevel = 1f,
                edgeAttack = .005f, edgeDecay = .5f, edgeSustain = 1f,
            )
            val legacyShapes = listOf(
                source - "config",
                source + ("config" to JsonValue.Null),
                source + ("config" to JsonValue.Obj(fullConfig - ADDITIVE_TIMBRE_FIELDS)),
                source + ("config" to JsonValue.Obj(emptyMap())),
                source + ("config" to JsonValue.Obj(fullConfig + ADDITIVE_TIMBRE_FIELDS.associateWith { JsonValue.Null })),
            )
            for ((shape, entries) in legacyShapes.withIndex()) {
                val patch = ContourPatch.fromJsonValue(JsonValue.Obj(entries)) as ContourPatch
                assertEquals(expected, patch.config, "$voice legacy JSON shape $shape")
                assertLegacyNeutral(patch.config, "$voice legacy JSON shape $shape")
                assertEquals(patch, ContourPatch.fromJsonText(patch.toJsonText()))
            }
        }
    }

    @Test
    fun `nine archived model one recipes reproduce original raw and matched WAV bytes`() {
        assertEquals(9, ARCHIVED_RECIPES.size)
        assertTrue(ARCHIVED_RECIPES.any { ContourPatch.fromJsonText(it.json).macros.getValue("HOLD") == 1f }, "the saved roster includes its original HOLD recipe")
        for (archive in ARCHIVED_RECIPES) assertArchivedAudio(archive)
    }

    @Test
    fun `archived settled HOLD JSON reproduces original loop audio for all six voices`() {
        assertEquals(ContourVoice.entries.toSet(), ARCHIVED_HOLD.map { ContourPatch.fromJsonText(it.json).voice }.toSet())
        for (archive in ARCHIVED_HOLD) {
            assertEquals(1f, ContourPatch.fromJsonText(archive.json).macros.getValue("HOLD"))
            assertArchivedAudio(archive)
        }
    }

    @Test
    fun `changing structural table relationships and envelopes changes regenerated audio`() {
        val patch = ContourPatch("Structure", ContourVoice.KEY, ContourEngine.defaults(ContourVoice.KEY))
        val original = patch.render().samples
        val alternatives = listOf(
            patch.copy(config = patch.config.copy(bodyTable = ContourTable.DIGITAL_BELL, edgeTable = ContourTable.CLUSTER)),
            patch.copy(config = patch.config.copy(edgeRatio = 1.5f, relation = ContourRelation.OPPOSED, edgeLag = .35f)),
            patch.copy(config = patch.config.copy(ampAttack = .15f, ampDecay = .25f, ampSustain = .20f, filterAmount = .8f)),
            patch.copy(config = patch.config.copy(bodyHarmonics = .55f, edgeRootLevel = .1f,
                edgeAttack = .03f, edgeDecay = .18f, edgeSustain = .05f)),
        )
        for ((i, changed) in alternatives.withIndex()) {
            val restored = assertIs<ContourPatch>(Patches.fromJsonText(changed.toJsonText()))
            val audio = restored.render().samples
            assertTrue(audio.size != original.size || ContourTestAudio.relativeDifference(original, audio) > .02, "structural variant $i did not change sound")
            assertContentEquals(changed.render().samples, audio, "structural variant $i is reproducible")
        }
    }

    @Test
    fun `invalid identities values and table IDs are rejected before synthesis`() {
        assertFailsWith<IllegalArgumentException> { ContourPatch(" ", ContourVoice.BASS, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { ContourPatch("x", ContourVoice.BASS, mapOf("WIDTH" to .5f)) }
        for (v in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { ContourPatch("x", ContourVoice.BASS, mapOf("SCAN" to v)) }
            assertFailsWith<IllegalArgumentException> { ContourPatch("x", ContourVoice.BASS, emptyMap(), velocity = v) }
        }
        val source = ContourPatch("x", ContourVoice.BASS, emptyMap())
        fun changed(key: String, value: JsonValue) = JsonValue.Obj(source.toJsonValue().entries + (key to value))
        assertFailsWith<JsonException> { ContourPatch.fromJsonValue(changed("engine", JsonValue.Str("GYRE"))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("version", JsonValue.Num(99.0))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("model", JsonValue.Num(99.0))) }
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("voice", JsonValue.Str("UNKNOWN"))) }
        val config = JsonValue.Obj(source.toJsonValue().entries.getValue("config").obj() + ("edgeTable" to JsonValue.Str("EXTERNAL_FACTORY")))
        assertFailsWith<JsonException> { Patches.fromJsonValue(changed("config", config)) }
    }

    @Test
    fun `expanded presets expose all six voices and distinct saved spectral structures`() {
        val presets = ContourPresets.all()
        assertEquals(36, presets.size, "the approved first-listen roster expands to the planned 36 presets")
        assertEquals(ContourVoice.entries.toSet(), presets.map { it.voice }.toSet())
        assertEquals(presets.size, presets.map { it.name }.toSet().size)
        assertTrue(presets.map { it.config }.toSet().size >= 6, "different table pairs and envelopes must establish the voices")
        assertEquals(presets, Presets.all().filter { it.engine == "CONTOUR" })
        for (patch in presets) {
            assertEquals(patch, Presets.byName("CONTOUR", patch.voiceName, patch.name))
            assertEquals(ContourPresets.forVoice(patch.voice), Presets.forVoice("CONTOUR", patch.voiceName))
            assertEquals(ContourEngine.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name))
        }
    }

    @Test
    fun `normal bass keys plucks leads hollow and swell remain tonal to both routing and classifier`() {
        for (voice in ContourVoice.entries) for (tune in listOf(.25f, .5f, .75f)) for (velocity in listOf(.25f, 1f)) {
            val macros = ContourEngine.defaults(voice) + ("TUNE" to tune)
            val filed = ContourEngine.drumClassFor(voice, macros)
            val heard = Classifier.classify(ContourEngine.render(voice, macros, velocity)).drumClass
            assertEquals(DrumClass.TONAL, filed, "$voice $tune $velocity")
            assertTrue(heard !in DRUMS, "$voice $tune $velocity classified $heard")
        }
    }

    @Test
    fun `the dry sixteen pad kit preserves editable recipes and exports as native MPC formats`() {
        val pads = SynthKits.contour()
        assertEquals(16, pads.size)
        val patches = pads.mapIndexed { i, nullable ->
            val pad = assertNotNull(nullable)
            val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
            val patch = assertIs<ContourPatch>(recipe.patch)
            assertNull(recipe.fx, "A${i + 1} is a dry audition")
            assertFalse(recipe.alias)
            assertTrue(pad.drumClass !in DRUMS)
            assertEquals(ContourEngine.drumClassFor(patch.voice, patch.macros), pad.drumClass)
            if (i == 0 || i == pads.lastIndex) assertContentEquals(pad.snip.samples, recipe.render().samples, "A${i + 1} regenerates")
            patch
        }
        assertEquals(ContourVoice.entries.toSet(), patches.map { it.voice }.toSet())
        assertEquals(DrumClass.LOOP, pads.last()!!.drumClass)
        val work = File(temp, "work")
        val kit = KitAssembler.assembleArranged("Contour Acceptance", pads, work)
        val mpc2 = KitExporter.exportProgramFolder(kit, work, File(temp, "mpc2"))
        val mpc3 = Mpc3Exporter.exportTrack(kit, work, File(temp, "mpc3"), mpc2Twin = true)
        assertEquals(16, mpc2.samples.size)
        assertEquals(16, mpc3.samples.size)
        assertTrue(Mpc3Project.read(mpc3.program).isTrack)
        for (wav in mpc2.samples + mpc3.samples) {
            val info = WavInfo.read(wav)
            assertEquals(44_100, info.sampleRate, wav.name)
            assertEquals(24, info.bitsPerSample, wav.name)
            assertEquals(1, info.channels, wav.name)
            assertTrue(info.frameCount > 256, wav.name)
        }
        val twin = File(mpc3.directory, Mpc3TrackWriter.trackDataDirName(kit.name) + "/${kit.name}.xpm")
        assertTrue(twin.isFile, "the native track's MPC 2 twin is packaged with its WAVs")
    }

    @Test
    fun `held keygroup zones retain exact pitch seam and sampler loop markers in both MPC writers`() {
        for (voice in ContourVoice.entries) {
            val midi = ContourEngine.rootMidi(voice)
            val note = Keys.contourPad(voice, mapOf("MOTION" to .75f, "OFFSET" to .8f, "DRIVE" to .75f), midi)
            assertEquals(note.snip.frameCount / 2L, note.loopStartFrame)
            assertTrue(Keys.seamError(note.snip.samples, note.loopStartFrame.toInt()) < 1e-3)
            assertTrue(abs(ContourTestAudio.pitch(note.snip, Keys.midiHz(midi), .1f, .9f)) < 5, voice.name)
            val programName = "Contour $voice"
            val data = File(temp, Mpc3TrackWriter.trackDataDirName(programName)).apply { mkdirs() }
            val wav = File(data, "Held $voice.wav")
            val sheet = SmplChunk(midi, SmplChunk.Loop(note.loopStartFrame, note.snip.frameCount.toLong()))
            WavWriter.write(wav, note.snip, smpl = sheet)
            val program = KeygroupProgram(programName, listOf(Keygroup(midi, midi, midi, listOf(
                VelocityLayer(wav.nameWithoutExtension, note.snip.frameCount.toLong(), 0, 127, note.loopStartFrame),
            ))))
            val xpm = KeygroupWriter().writeTo(data, program)
            val xty = Mpc3TrackWriter().writeKeygroupTo(temp, program)
            assertTrue(Mpc3Project.read(xty).isTrack)
            assertTrue("<SliceLoop>1</SliceLoop>" in xpm.readText())
            assertTrue("<SliceLoopStart>${note.loopStartFrame}</SliceLoopStart>" in xpm.readText())
            val native = Mpc3TrackWriter().keygroupPayloadText(program)
            assertTrue("\"LoopMode\": 1" in native)
            assertTrue("\"LoopStart\": ${note.loopStartFrame}" in native)
            assertEquals(sheet, WavReader.readSmpl(wav))
            assertEquals(note.snip.frameCount.toLong(), WavInfo.read(wav).frameCount)
        }
    }

    @Test
    fun `host velocity reaches native excitation without changing saved spectral structure`() {
        val patch = ContourPresets.forVoice(ContourVoice.KEY).first().copy(velocity = .7f)
        val original = patch.copy()
        val soft = Velocity.atVelocity(patch, .25f)
        assertContentEquals(ContourEngine.render(patch.voice, patch.macros, .25f, patch.config).samples, soft.samples)
        assertFalse(soft.samples.contentEquals(Velocity.atVelocity(patch, 1f).samples))
        assertEquals(original, patch)
    }

    private fun assertLegacyNeutral(config: ContourConfig, message: String) {
        assertEquals(1f, config.bodyHarmonics, "$message body harmonics")
        assertEquals(1f, config.edgeRootLevel, "$message edge root")
        assertEquals(.005f, config.edgeAttack, "$message edge attack")
        assertEquals(.5f, config.edgeDecay, "$message edge decay")
        assertEquals(1f, config.edgeSustain, "$message edge sustain")
    }

    private fun assertArchivedAudio(archive: ArchivedRecipe) {
        val patch = ContourPatch.fromJsonText(archive.json)
        assertLegacyNeutral(patch.config, patch.name)
        val raw = Snip(ContourEngine.renderRaw(patch.voice, patch.macros, patch.velocity, patch.config), 1, Dsp.RATE)
        val rawGain = minOf(1f, .99f / raw.peak())
        val exported = if (rawGain == 1f) raw else Snip(FloatArray(raw.samples.size) { raw.samples[it] * rawGain }, 1, Dsp.RATE)
        assertEquals(archive.rawSha256, wavSha256(exported), "${patch.name}: archived raw PCM16")
        val matched = Snip(raw.samples.copyOf().also { Dsp.levelTo(it, Dsp.RATE, .12f, ceiling = .90f) }, 1, Dsp.RATE)
        assertEquals(archive.matchedSha256, wavSha256(matched), "${patch.name}: archived matched PCM16")
        assertContentEquals(patch.render().samples, ContourPatch.fromJsonText(patch.toJsonText()).render().samples, patch.name)
    }

    private fun wavSha256(snip: Snip): String {
        val bytes = ByteArrayOutputStream().also { WavWriter.write(it, snip, WavWriter.BitDepth.PCM_16) }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private data class ArchivedRecipe(val json: String, val rawSha256: String, val matchedSha256: String)

    private companion object {
        val ADDITIVE_TIMBRE_FIELDS = setOf("bodyHarmonics", "edgeRootLevel", "edgeAttack", "edgeDecay", "edgeSustain")
        // Captured from listening round contour-ec4bca161f2c-d49e251b6013.
        // These are original saved recipes, independent of the evolving preset roster.
        // The published archive used PCM16, not the host export default of PCM24.
        val ARCHIVED_RECIPES = listOf(
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"Rounded Edge","voice":"BASS","macros":{"TUNE":0.5,"SCAN":0.25,"OFFSET":0.20000000298023224,"MOTION":0.25,"FILTER":0.4000000059604645,"DRIVE":0.3499999940395355,"HOLD":0},"model":1,"velocity":1,"config":{"bodyTable":"ROUND_PULSE","edgeTable":"COPPER","edgeRatio":1,"edgeLag":0.18000000715255737,"relation":"LAG","bodyLevel":0.800000011920929,"edgeLevel":0.3100000023841858,"subLevel":0.09000000357627869,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.23999999463558197,"edgeTravel":0.5,"ampAttack":0.006000000052154064,"ampDecay":0.550000011920929,"ampSustain":0.47999998927116394,"ampRelease":0.550000011920929,"sustainSeconds":0.75,"filterAttack":0.004999999888241291,"filterDecay":0.5,"filterAmount":0.6000000238418579,"motionShape":"FALL","motionSeconds":1.2000000476837158,"filterType":"LOW_PASS","loopSeconds":2}}""",
                "a53fc5ae9291e1d980471a82b3ae72e9ab1585adc15eaf800c936efab91075f3",
                "3f8723f2f62aa01b026456e14223e8c5a67a42a6af6eb9b6b785a63e58093ce6",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"Moving Corner","voice":"BASS","macros":{"TUNE":0.5,"SCAN":0.30000001192092896,"OFFSET":0.44999998807907104,"MOTION":0.47999998927116394,"FILTER":0.5799999833106995,"DRIVE":0.30000001192092896,"HOLD":0},"model":1,"velocity":1,"config":{"bodyTable":"SOFT_SAW","edgeTable":"COPPER","edgeRatio":1,"edgeLag":0.18000000715255737,"relation":"OPPOSED","bodyLevel":0.800000011920929,"edgeLevel":0.3100000023841858,"subLevel":0.09000000357627869,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.2199999988079071,"edgeTravel":0.6000000238418579,"ampAttack":0.006000000052154064,"ampDecay":0.44999998807907104,"ampSustain":0.47999998927116394,"ampRelease":0.41999998688697815,"sustainSeconds":0.75,"filterAttack":0.004999999888241291,"filterDecay":0.5,"filterAmount":0.6000000238418579,"motionShape":"FALL","motionSeconds":1.2000000476837158,"filterType":"LOW_PASS","loopSeconds":2}}""",
                "9e92db1c31fe9146d6f0fa42add4f1d3330a088289c011a1a6c1d14d0aa69e60",
                "cc00c08f52867c89661909399af4e63d4f6729e0cb010329bb6f19a46b05a2bb",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"Dark Table","voice":"BASS","macros":{"TUNE":0.5,"SCAN":0.18000000715255737,"OFFSET":0.3199999928474426,"MOTION":0.18000000715255737,"FILTER":0.25,"DRIVE":0.41999998688697815,"HOLD":0},"model":1,"velocity":1,"config":{"bodyTable":"ROUND_PULSE","edgeTable":"ODD_EVEN","edgeRatio":1,"edgeLag":0.18000000715255737,"relation":"CURVE","bodyLevel":0.800000011920929,"edgeLevel":0.3100000023841858,"subLevel":0.09000000357627869,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.23999999463558197,"edgeTravel":0.5,"ampAttack":0.006000000052154064,"ampDecay":0.550000011920929,"ampSustain":0.44999998807907104,"ampRelease":0.550000011920929,"sustainSeconds":0.75,"filterAttack":0.004999999888241291,"filterDecay":0.5,"filterAmount":0.3799999952316284,"motionShape":"FALL","motionSeconds":1.2000000476837158,"filterType":"LOW_PASS","loopSeconds":2}}""",
                "77bfe1a25fc1b88eb207ce88169389d3d6876331d074fa61176227cea9f9f8f0",
                "1a15c63f86698f64a2d44f53c3bdd8e8ddee014e1cfe1055bab2c6e36fad60e6",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"Copper Scan","voice":"KEY","macros":{"TUNE":0.5,"SCAN":0.36000001430511475,"OFFSET":0.4000000059604645,"MOTION":0.3799999952316284,"FILTER":0.6000000238418579,"DRIVE":0.23999999463558197,"HOLD":0},"model":1,"velocity":1,"config":{"bodyTable":"SOFT_SAW","edgeTable":"COPPER","edgeRatio":1,"edgeLag":0.23999999463558197,"relation":"CURVE","bodyLevel":0.7599999904632568,"edgeLevel":0.3700000047683716,"subLevel":0,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.25,"edgeTravel":0.5199999809265137,"ampAttack":0.00800000037997961,"ampDecay":0.800000011920929,"ampSustain":0.25999999046325684,"ampRelease":0.8999999761581421,"sustainSeconds":1.100000023841858,"filterAttack":0.004999999888241291,"filterDecay":0.7200000286102295,"filterAmount":0.699999988079071,"motionShape":"FALL","motionSeconds":1.2000000476837158,"filterType":"LOW_PASS","loopSeconds":2}}""",
                "d3d40aae0f00cb9dfdca796258d89fc713a166710990f30320e1dc149bc3923d",
                "7a1fe04a6c77d53d316fe626e15ab2f5ebbe8181b6bfb49bfd6da7cdb4d2f4be",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"Soft Digital","voice":"KEY","macros":{"TUNE":0.5,"SCAN":0.23999999463558197,"OFFSET":0.30000001192092896,"MOTION":0.23999999463558197,"FILTER":0.46000000834465027,"DRIVE":0.07999999821186066,"HOLD":0},"model":1,"velocity":1,"config":{"bodyTable":"ROUND_PULSE","edgeTable":"DIGITAL_BELL","edgeRatio":1,"edgeLag":0.18000000715255737,"relation":"CURVE","bodyLevel":0.7599999904632568,"edgeLevel":0.23999999463558197,"subLevel":0,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.30000001192092896,"edgeTravel":0.5799999833106995,"ampAttack":0.017999999225139618,"ampDecay":0.8500000238418579,"ampSustain":0.25999999046325684,"ampRelease":0.8999999761581421,"sustainSeconds":1.100000023841858,"filterAttack":0.004999999888241291,"filterDecay":0.7200000286102295,"filterAmount":0.41999998688697815,"motionShape":"FALL","motionSeconds":1.2000000476837158,"filterType":"LOW_PASS","loopSeconds":2}}""",
                "bbd026435e14fb6e2681a5e4b636ee25a055dd677601b49a1426260ef52ce783",
                "c106f559a3f12b6dba283a08f3a185e1d936ba20a2f1bd72b7817ed2a6aa0b1a",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"Bright Margin","voice":"PLUCK","macros":{"TUNE":0.5,"SCAN":0.4000000059604645,"OFFSET":0.41999998688697815,"MOTION":0.47999998927116394,"FILTER":0.7200000286102295,"DRIVE":0.2199999988079071,"HOLD":0},"model":1,"velocity":1,"config":{"bodyTable":"DIGITAL_BELL","edgeTable":"CLUSTER","edgeRatio":1,"edgeLag":0.11999999731779099,"relation":"CURVE","bodyLevel":0.7599999904632568,"edgeLevel":0.38999998569488525,"subLevel":0,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.30000001192092896,"edgeTravel":0.46000000834465027,"ampAttack":0.003000000026077032,"ampDecay":0.2800000011920929,"ampSustain":0.10000000149011612,"ampRelease":0.3499999940395355,"sustainSeconds":0.25,"filterAttack":0.004999999888241291,"filterDecay":0.2199999988079071,"filterAmount":0.8999999761581421,"motionShape":"FALL","motionSeconds":0.5,"filterType":"LOW_PASS","loopSeconds":2}}""",
                "a0e819de64d4c2146a8aa1e06c3429d0f003aee3d7617171a0aebbf0a48c13d0",
                "26fa2c0a6091abd72fb7143ce32a71d17a08c19891e48baa79fca3a1d4cf2c22",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"Twin Outline","voice":"LEAD","macros":{"TUNE":0.5,"SCAN":0.44999998807907104,"OFFSET":0.3499999940395355,"MOTION":0.4000000059604645,"FILTER":0.699999988079071,"DRIVE":0.30000001192092896,"HOLD":0},"model":1,"velocity":1,"config":{"bodyTable":"SAW_HOLLOW","edgeTable":"ODD_EVEN","edgeRatio":1,"edgeLag":0.11999999731779099,"relation":"OPPOSED","bodyLevel":0.7799999713897705,"edgeLevel":0.3700000047683716,"subLevel":0,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.27000001072883606,"edgeTravel":0.5,"ampAttack":0.014999999664723873,"ampDecay":0.30000001192092896,"ampSustain":0.7200000286102295,"ampRelease":0.6499999761581421,"sustainSeconds":1.399999976158142,"filterAttack":0.004999999888241291,"filterDecay":0.5,"filterAmount":0.699999988079071,"motionShape":"CYCLE","motionSeconds":1.100000023841858,"filterType":"LOW_PASS","loopSeconds":2}}""",
                "ffabb1ac52f2520af46b45be9164d2ade710ebfb03e39f73a4e4183abe8bf58b",
                "d576ee68a9951be06abc9d309f22bda7fce89459f8500416136df8544086a250",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"Hollow Step","voice":"HOLLOW","macros":{"TUNE":0.5,"SCAN":0.550000011920929,"OFFSET":0.5,"MOTION":0.30000001192092896,"FILTER":0.550000011920929,"DRIVE":0.20000000298023224,"HOLD":0},"model":1,"velocity":1,"config":{"bodyTable":"SAW_HOLLOW","edgeTable":"FORMANT","edgeRatio":1,"edgeLag":0.18000000715255737,"relation":"CURVE","bodyLevel":0.800000011920929,"edgeLevel":0.3499999940395355,"subLevel":0,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.25,"edgeTravel":0.36000001430511475,"ampAttack":0.02199999988079071,"ampDecay":0.6000000238418579,"ampSustain":0.5199999809265137,"ampRelease":0.8500000238418579,"sustainSeconds":1.2000000476837158,"filterAttack":0.004999999888241291,"filterDecay":0.5,"filterAmount":0.4000000059604645,"motionShape":"FALL","motionSeconds":1.2000000476837158,"filterType":"BAND_PASS","loopSeconds":2}}""",
                "c077df343445a8a72d83c5bc05990aeacdc7094a862665616500882669701cbd",
                "eca051836761226e14f6edc0783e426283b31734080bee8dea228bf99ef56ad3",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"Long Contour","voice":"SWELL","macros":{"TUNE":0.5,"SCAN":0.3499999940395355,"OFFSET":0.44999998807907104,"MOTION":0.550000011920929,"FILTER":0.6000000238418579,"DRIVE":0.15000000596046448,"HOLD":1},"model":1,"velocity":1,"config":{"bodyTable":"SOFT_SAW","edgeTable":"FORMANT","edgeRatio":1,"edgeLag":0.18000000715255737,"relation":"LAG","bodyLevel":0.800000011920929,"edgeLevel":0.3199999928474426,"subLevel":0,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.30000001192092896,"edgeTravel":0.5799999833106995,"ampAttack":0.6499999761581421,"ampDecay":0.6499999761581421,"ampSustain":0.7799999713897705,"ampRelease":1.5,"sustainSeconds":2.0999999046325684,"filterAttack":0.44999998807907104,"filterDecay":1.399999976158142,"filterAmount":0.5,"motionShape":"CYCLE","motionSeconds":2.4000000953674316,"filterType":"LOW_PASS","loopSeconds":2.4000000953674316}}""",
                "9a6238f858b8a62dad62389bc54e933ed20182a564a1f4986dd49d1ac684206c",
                "e9564ac82e201f9010eed8c06de0db0ff6fd29b941ffd0441a3246f3c6c83c3d",
            ),
        )
        // Original settled HOLD listening clips, one for each voice.
        val ARCHIVED_HOLD = listOf(
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"BASS \u00b7 normal HOLD","voice":"BASS","macros":{"TUNE":0.5,"SCAN":0.25,"OFFSET":0.20000000298023224,"MOTION":0.25,"FILTER":0.4000000059604645,"DRIVE":0.3499999940395355,"HOLD":1},"model":1,"velocity":1,"config":{"bodyTable":"ROUND_PULSE","edgeTable":"COPPER","edgeRatio":1,"edgeLag":0.18000000715255737,"relation":"LAG","bodyLevel":0.800000011920929,"edgeLevel":0.3100000023841858,"subLevel":0.09000000357627869,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.23999999463558197,"edgeTravel":0.5,"ampAttack":0.006000000052154064,"ampDecay":0.550000011920929,"ampSustain":0.47999998927116394,"ampRelease":0.550000011920929,"sustainSeconds":0.75,"filterAttack":0.004999999888241291,"filterDecay":0.5,"filterAmount":0.6000000238418579,"motionShape":"FALL","motionSeconds":1.2000000476837158,"filterType":"LOW_PASS","loopSeconds":2}}""",
                "47624b2b65dcdc3287aae540693323b20e56c27076ca9bf01b3703861b0d074c",
                "4532840a6d9e66ed293886f53000fdaf9873afe115390c797b68d95b010c1b18",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"KEY \u00b7 normal HOLD","voice":"KEY","macros":{"TUNE":0.5,"SCAN":0.3499999940395355,"OFFSET":0.3499999940395355,"MOTION":0.30000001192092896,"FILTER":0.550000011920929,"DRIVE":0.20000000298023224,"HOLD":1},"model":1,"velocity":1,"config":{"bodyTable":"SOFT_SAW","edgeTable":"DIGITAL_BELL","edgeRatio":1,"edgeLag":0.18000000715255737,"relation":"CURVE","bodyLevel":0.7599999904632568,"edgeLevel":0.3700000047683716,"subLevel":0,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.30000001192092896,"edgeTravel":0.5799999833106995,"ampAttack":0.00800000037997961,"ampDecay":0.800000011920929,"ampSustain":0.25999999046325684,"ampRelease":0.8999999761581421,"sustainSeconds":1.100000023841858,"filterAttack":0.004999999888241291,"filterDecay":0.7200000286102295,"filterAmount":0.699999988079071,"motionShape":"FALL","motionSeconds":1.2000000476837158,"filterType":"LOW_PASS","loopSeconds":2}}""",
                "4283edad0c777a1bb6d39639b4eaa8a81f02ebbbc0257cd49fa6368efdf27e05",
                "17892f17d51a94fba1085f347eabc37f27464a4c54cc1ab86b3322cf0556ed80",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"PLUCK \u00b7 normal HOLD","voice":"PLUCK","macros":{"TUNE":0.5,"SCAN":0.4000000059604645,"OFFSET":0.4000000059604645,"MOTION":0.44999998807907104,"FILTER":0.6499999761581421,"DRIVE":0.25,"HOLD":1},"model":1,"velocity":1,"config":{"bodyTable":"SAW_HOLLOW","edgeTable":"CLUSTER","edgeRatio":1,"edgeLag":0.11999999731779099,"relation":"LAG","bodyLevel":0.7599999904632568,"edgeLevel":0.38999998569488525,"subLevel":0,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.30000001192092896,"edgeTravel":0.46000000834465027,"ampAttack":0.003000000026077032,"ampDecay":0.44999998807907104,"ampSustain":0.07000000029802322,"ampRelease":0.3199999928474426,"sustainSeconds":0.44999998807907104,"filterAttack":0.004999999888241291,"filterDecay":0.2199999988079071,"filterAmount":0.8999999761581421,"motionShape":"FALL","motionSeconds":0.5,"filterType":"LOW_PASS","loopSeconds":2}}""",
                "1d4fd14da4c4799a4307e4db8f16c1a57424a36edb22845528722d717a91a8c5",
                "b38e837ce4d6e60a4ba6ba96bf5cbe65a56f7ddd897dbe5c7c73330bc812a6a4",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"LEAD \u00b7 normal HOLD","voice":"LEAD","macros":{"TUNE":0.5,"SCAN":0.44999998807907104,"OFFSET":0.3499999940395355,"MOTION":0.4000000059604645,"FILTER":0.699999988079071,"DRIVE":0.30000001192092896,"HOLD":1},"model":1,"velocity":1,"config":{"bodyTable":"COPPER","edgeTable":"ODD_EVEN","edgeRatio":1,"edgeLag":0.18000000715255737,"relation":"OPPOSED","bodyLevel":0.7799999713897705,"edgeLevel":0.3700000047683716,"subLevel":0,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.27000001072883606,"edgeTravel":0.5,"ampAttack":0.014999999664723873,"ampDecay":0.30000001192092896,"ampSustain":0.7200000286102295,"ampRelease":0.6499999761581421,"sustainSeconds":1.399999976158142,"filterAttack":0.004999999888241291,"filterDecay":0.5,"filterAmount":0.699999988079071,"motionShape":"CYCLE","motionSeconds":1.100000023841858,"filterType":"LOW_PASS","loopSeconds":2}}""",
                "00f5fd2e99a4901d14b6206e795473a3bd3908753cf2c5e43fb11fd32cabe3db",
                "2a873d7147dcb8bde6b1392445dce7b487bd2a874d8695e0c5cebfb3540da915",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"HOLLOW \u00b7 normal HOLD","voice":"HOLLOW","macros":{"TUNE":0.5,"SCAN":0.550000011920929,"OFFSET":0.5,"MOTION":0.30000001192092896,"FILTER":0.550000011920929,"DRIVE":0.20000000298023224,"HOLD":1},"model":1,"velocity":1,"config":{"bodyTable":"ODD_EVEN","edgeTable":"FORMANT","edgeRatio":1,"edgeLag":0.18000000715255737,"relation":"OPPOSED","bodyLevel":0.800000011920929,"edgeLevel":0.3499999940395355,"subLevel":0,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.25,"edgeTravel":0.36000001430511475,"ampAttack":0.02199999988079071,"ampDecay":0.6000000238418579,"ampSustain":0.5199999809265137,"ampRelease":0.8500000238418579,"sustainSeconds":1.2000000476837158,"filterAttack":0.004999999888241291,"filterDecay":0.5,"filterAmount":0.4000000059604645,"motionShape":"FALL","motionSeconds":1.2000000476837158,"filterType":"BAND_PASS","loopSeconds":2}}""",
                "8d0f1c9dd67e6e7c132b913627d4038e04610dfd253ff03bc400b08a575dc2c4",
                "fd26540832b3b1d8e55981cf5c40c6801c4297c94480b34eb808e5c39a4497a0",
            ),
            ArchivedRecipe(
                """{"engine":"CONTOUR","version":1,"name":"SWELL \u00b7 normal HOLD","voice":"SWELL","macros":{"TUNE":0.5,"SCAN":0.3499999940395355,"OFFSET":0.44999998807907104,"MOTION":0.550000011920929,"FILTER":0.6000000238418579,"DRIVE":0.15000000596046448,"HOLD":1},"model":1,"velocity":1,"config":{"bodyTable":"SOFT_SAW","edgeTable":"FORMANT","edgeRatio":1,"edgeLag":0.18000000715255737,"relation":"LAG","bodyLevel":0.800000011920929,"edgeLevel":0.3199999928474426,"subLevel":0,"bodyScanBias":0,"edgeScanBias":0,"bodyTravel":0.3199999928474426,"edgeTravel":0.6399999856948853,"ampAttack":0.6499999761581421,"ampDecay":0.6499999761581421,"ampSustain":0.7799999713897705,"ampRelease":1.5,"sustainSeconds":2.0999999046325684,"filterAttack":0.44999998807907104,"filterDecay":1.399999976158142,"filterAmount":0.5,"motionShape":"RISE","motionSeconds":2.200000047683716,"filterType":"LOW_PASS","loopSeconds":3}}""",
                "6f71972216651ea16c95b66e3b78f350449dc3bb2dbeb57d058a1a47b9d37d97",
                "df8884c9eaf03a95fac3bd8ef532ef9aae1c5bf0c529ca4bd48cbb86e8bfccf3",
            ),
        )
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
