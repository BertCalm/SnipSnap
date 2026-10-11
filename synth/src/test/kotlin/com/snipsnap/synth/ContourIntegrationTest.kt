package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.SmplChunk
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
import java.io.File
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
    fun `changing structural table relationships and envelopes changes regenerated audio`() {
        val patch = ContourPatch("Structure", ContourVoice.KEY, ContourEngine.defaults(ContourVoice.KEY))
        val original = patch.render().samples
        val alternatives = listOf(
            patch.copy(config = patch.config.copy(bodyTable = ContourTable.DIGITAL_BELL, edgeTable = ContourTable.CLUSTER)),
            patch.copy(config = patch.config.copy(edgeRatio = 1.5f, relation = ContourRelation.OPPOSED, edgeLag = .35f)),
            patch.copy(config = patch.config.copy(ampAttack = .15f, ampDecay = .25f, ampSustain = .20f, filterAmount = .8f)),
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
    fun `first listen presets expose all six voices and distinct saved spectral structures`() {
        val presets = ContourPresets.all()
        assertEquals(9, presets.size, "first-listen roster precedes the proposed 36-preset expansion")
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

    private companion object {
        val DRUMS = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    }
}
