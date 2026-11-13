package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.CairnAcceptedSingContext
import com.snipsnap.synth.CairnAcceptedSingSources
import com.snipsnap.synth.CairnSamplerDelivery
import com.snipsnap.synth.ChordStabs
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CairnSingInstrumentMakerTest {
    private companion object {
        val captures = mutableMapOf<String, CairnSamplerDelivery.Capture>()
        var nativeFixtureCalls = 0
        @Synchronized fun capture(id: String): CairnSamplerDelivery.Capture = captures.getOrPut(id) {
            val source = requireNotNull(CairnAcceptedSingSources.byId(id))
            val temp = createTempDirectory("cairn-retained-capture").toFile()
            try {
                val retained = System.getProperty("snipsnap.cairn.retained.directory")
                val bytes = if (retained != null) {
                    File(retained, source.recordingId + ".full.f32le").readBytes()
                } else {
                    nativeFixtureCalls++
                    val full = CairnAcceptedSingContext.prepare(source).fullNativeContext().samples
                    ByteBuffer.allocate(full.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        .also { out -> full.forEach { out.putInt(it.toRawBits()) } }.array()
                }
                val raw = File(temp, "native.f32le").apply { writeBytes(bytes) }
                val metadata = File(temp, "native.json").apply {
                    writeBytes(checkNotNull(CairnSingInstrumentMakerTest::class.java.getResourceAsStream(
                        "/cairn-retained-sing/" + source.recordingId + ".full.json")).use { it.readBytes() })
                }
                println("CAIRN new maker fixture $id retained=" + (retained != null) + " nativeCalls=" + nativeFixtureCalls)
                CairnSingInstrumentMaker.readCapture(id, raw, metadata)
            } finally { temp.deleteRecursively() }
        }
    }

    private fun sourceSet(program: CairnSingInstrumentMaker.Program) =
        CairnSingInstrumentMaker.sourceIds(program).associateWith { capture(it) }
    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to CairnSamplerDelivery.sha(it.readBytes())
    }

    @Test fun cataloguePreservesFiveProgramsNineSourcesAndOneVoice() {
        assertEquals(5, CairnSingInstrumentMaker.Program.entries.size)
        assertEquals(1, CairnSingInstrumentMaker.NATIVE_VOICES)
        val expected = mapOf(
            CairnSingInstrumentMaker.Program.EARLIER to listOf("cairn-before-48", "cairn-before-60", "cairn-before-72"),
            CairnSingInstrumentMaker.Program.MOVING to listOf("cairn-after-48", "cairn-after-60", "cairn-after-72"),
            CairnSingInstrumentMaker.Program.GROOVE_0 to listOf("cairn-groove-0"),
            CairnSingInstrumentMaker.Program.GROOVE_50 to listOf("cairn-groove-50"),
            CairnSingInstrumentMaker.Program.GROOVE_100 to listOf("cairn-groove-100"))
        for ((program, sourceIds) in expected) assertEquals(sourceIds, CairnSingInstrumentMaker.sourceIds(program))
        val ids = CairnSingInstrumentMaker.Program.entries.flatMap(CairnSingInstrumentMaker::sourceIds)
        assertEquals(9, ids.distinct().size)
        for (program in CairnSingInstrumentMaker.Program.entries) {
            val spec = CairnSingInstrumentMaker.specification(program)
            assertEquals(program.id, spec.id)
            assertEquals(CairnSamplerDelivery.specs.single { it.id == program.id }, spec)
            val sources = spec.sourceIds.map { requireNotNull(CairnAcceptedSingSources.byId(it)) }
            assertEquals(if (program == CairnSingInstrumentMaker.Program.EARLIER) setOf(4) else setOf(5),
                sources.map { it.patch().model }.toSet())
        }
        // A caller can edit the old prototype catalogue. That cannot relabel MOVING as EARLIER.
        @Suppress("UNCHECKED_CAST")
        val legacy = CairnSamplerDelivery.specs as MutableList<CairnSamplerDelivery.Spec>
        val index = legacy.indexOfFirst { it.id == "earlier-c3-c5" }
        val original = legacy[index]
        try {
            legacy[index] = original.copy(sourceIds = expected.getValue(CairnSingInstrumentMaker.Program.MOVING))
            assertEquals(expected.getValue(CairnSingInstrumentMaker.Program.EARLIER),
                CairnSingInstrumentMaker.sourceIds(CairnSingInstrumentMaker.Program.EARLIER))
            assertEquals("CAIRN Earlier C3 C5",
                CairnSingInstrumentMaker.specification(CairnSingInstrumentMaker.Program.EARLIER).name)
        } finally { legacy[index] = original }
    }

    @Test fun plansKeepOnlyAuthoredC3ToC5OrFixedC4Coverage() {
        for (program in CairnSingInstrumentMaker.Program.entries) {
            val before = CairnSingInstrumentMaker.sourceIds(program).associateWith {
                requireNotNull(CairnAcceptedSingSources.byId(it)).originalRecipeJson()
            }
            val zones = CairnSingInstrumentMaker.zones(program)
            if (program in listOf(CairnSingInstrumentMaker.Program.EARLIER, CairnSingInstrumentMaker.Program.MOVING)) {
                assertEquals(listOf(48, 60, 72), zones.map { it.root })
                assertEquals((48..72).toList(), zones.flatMap { (it.low..it.high).toList() })
                assertTrue(zones.all { zone -> (zone.low..zone.high).all { kotlin.math.abs(it-zone.root) <= 6 } })
            } else assertEquals(listOf(CairnSamplerDelivery.NoteZone(60, 60, 60)), zones)
            assertEquals(before, before.keys.associateWith { requireNotNull(CairnAcceptedSingSources.byId(it)).originalRecipeJson() })
        }
    }

    @Test fun unknownTruncatedOrSubstitutedCapturesRefuseBeforeAnyExport() {
        val root = createTempDirectory("cairn-bad-capture").toFile()
        try {
            val raw = File(root, "bad.f32le").apply { writeBytes(ByteArray(16)) }
            val metadata = File(root, "bad.json").apply { writeText("{}") }
            assertFailsWith<IllegalArgumentException> { CairnSingInstrumentMaker.readCapture("unknown", raw, metadata) }
            assertFailsWith<IllegalArgumentException> { CairnSingInstrumentMaker.readCapture("cairn-groove-0", raw, metadata) }
            raw.writeBytes(ByteArray(CairnSamplerDelivery.FULL_FRAMES * 4))
            assertFailsWith<IllegalArgumentException> { CairnSingInstrumentMaker.readCapture("cairn-groove-0", raw, metadata) }
            assertEquals(setOf("bad.f32le", "bad.json"), root.listFiles()!!.map { it.name }.toSet())
            assertFailsWith<IllegalArgumentException> {
                CairnSingInstrumentMaker.assemble("Missing", CairnSingInstrumentMaker.Program.GROOVE_0, emptyMap())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun retainedFloatCopiesAndCandidateIntroLeaveNativeBodyUntouched() {
        val c = capture("cairn-groove-0")
        val original = c.originalFull().samples
        val delivered = c.delivered().samples
        assertEquals(CairnSamplerDelivery.FULL_FRAMES, delivered.size)
        for (i in 220 until original.size) assertEquals(original[i].toRawBits(), delivered[i].toRawBits())
        assertEquals(0f, delivered[0])
        val copy = c.originalFull().samples.apply { fill(0f) }
        assertFalse(original.contentEquals(copy))
        assertContentEquals(original, c.originalFull().samples)
        assertContentEquals(delivered, c.delivered().samples)
    }

    @Test fun allFivePortableProgramsPreserveOriginalRecipesPolicyAndBoundedMapping() {
        val root = createTempDirectory("cairn-all-keygroups").toFile()
        try {
            for ((index, program) in CairnSingInstrumentMaker.Program.entries.withIndex()) {
                val name = "Cairn " + index
                val captures = sourceSet(program)
                val destination = File(root, "package" + index)
                val made = CairnSingInstrumentMaker.writeNewPackage(name, program, captures, destination)
                assertEquals(made.program, CairnSingInstrumentMaker.validate(name, destination, program, captures).program)
                val (_, instrument) = InstrumentStore.list(destination).single()
                assertEquals(made.program, instrument.toProgram())
                assertEquals(.08f, instrument.release)
                assertTrue(instrument.toProgram().explicitNoteOn)
                assertFalse(instrument.toProgram().stackZones)
                val data = File(destination, Mpc3TrackWriter.trackDataDirName(name))
                val manifest = Json.parse(File(data, CairnSingInstrumentMaker.SOURCES).readText()).obj()
                assertEquals(1, manifest.getValue("nativeVoiceCount").int())
                assertFalse(manifest.getValue("automaticFactoryOrPickerAdmission").bool())
                assertEquals("pending", manifest.getValue("listeningAndDevice").str())
                for (id in CairnSingInstrumentMaker.sourceIds(program)) {
                    val source = captures.getValue(id).source
                    assertEquals(source.originalRecipeJson(), File(data,
                        CairnSingInstrumentMaker.RECIPES + "/" + source.recordingId + ".json").readText())
                }
                for (midi in 0..127) {
                    val hit = KeyHit.resolve(instrument, midi, 1f) { CairnSamplerDelivery.FULL_FRAMES.toLong() }
                    val expected = if (captures.size == 3) midi in 48..72 else midi == 60
                    assertEquals(expected, hit != null, "$program MIDI$midi")
                    if (hit != null) assertEquals(88200L, hit.loopStartFrame)
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun existingChordToolsPlayIndependentNotesOnlyInsideTheCapturedRange() {
        val root = createTempDirectory("cairn-keygroup-chords").toFile()
        try {
            val program = CairnSingInstrumentMaker.Program.MOVING
            CairnSingInstrumentMaker.exportFresh("Cairn Chords", program, sourceSet(program), root)
            val (sidecar, instrument) = InstrumentStore.list(root).single()
            val samples = instrument.sampleFiles.associateWith { WavReader.read(File(sidecar.parentFile, it)).samples }
            val chords = ChordStabs.stabs(KeySpec.parse("C")).filter { it.notes.all { note -> note in 48..72 } }
            assertTrue(chords.isNotEmpty())
            for (chord in chords) {
                val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, 44100, samples), 44100)
                assertTrue(chord.notes.all { player.noteOn(it, .65f) }, chord.name)
                assertEquals(chord.notes.toSet(), player.activeNotes.toSet())
                assertFalse(player.noteOn(47, 1f)); assertFalse(player.noteOn(73, 1f))
                val audio = FloatArray(1024 * 2); player.render(audio, 1024)
                assertTrue(audio.all { it.isFinite() } && audio.any { it != 0f })
                chord.notes.forEach { player.noteOff(it) }
                player.render(FloatArray(8192 * 2), 8192)
                val silence = FloatArray(1024 * 2); player.render(silence, 1024)
                assertTrue(silence.all { it == 0f })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptStagedBytesAndProvenanceNeverBecomeShelfEntries() {
        val root = createTempDirectory("cairn-staged-refusal").toFile()
        try {
            val program = CairnSingInstrumentMaker.Program.GROOVE_0
            val captures = sourceSet(program); val name = "Cairn Bound"
            val dataName = Mpc3TrackWriter.trackDataDirName(name)
            val changes: List<(File) -> Unit> = listOf(
                { p -> File(p, "$dataName/" + CairnSingInstrumentMaker.SOURCES).appendText(" ") },
                { p -> File(p, "$dataName/" + CairnSingInstrumentMaker.POLICY).writeText("{}") },
                { p -> File(p, "$dataName/" + CairnSingInstrumentMaker.RECIPES + "/groove-0.json").appendText(" ") },
                { p -> File(p, "$dataName/sing_groove_0.wav").appendBytes(byteArrayOf(0)) },
                { p -> File(p, "$name.xty").appendBytes(byteArrayOf(0)) },
                { p -> InstrumentStore.sidecar(p, name).appendText(" ") },
                { p -> File(p, "$dataName/extra.wav").writeText("unbound") },
                { p -> File(p, "$dataName/empty-unbound").mkdir() },
            )
            for (change in changes) {
                var moved = false
                assertFails { CairnSingInstrumentMaker.exportFresh(name, program, captures, root,
                    InstrumentPackageAdmission.Hooks(staged = change, moved = { moved = true })) }
                assertFalse(moved)
                assertTrue(InstrumentStore.list(root).isEmpty())
                assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedShelfMovesKeepPriorInstrumentAndUnrelatedBytes() {
        val root = createTempDirectory("cairn-admission-rollback").toFile()
        try {
            val program = CairnSingInstrumentMaker.Program.GROOVE_0
            val captures = sourceSet(program)
            CairnSingInstrumentMaker.exportFresh("Previous Cairn", program, captures, root)
            File(root, "unrelated.txt").writeText("keep")
            val before = snapshot(root); val name = "Next Cairn"
            for (entry in listOf(Mpc3TrackWriter.trackDataDirName(name), "$name.xty", "$name" + InstrumentStore.SUFFIX)) {
                assertFailsWith<IOException> { CairnSingInstrumentMaker.exportFresh(name, program, captures, root,
                    InstrumentPackageAdmission.Hooks(moved = { if (it == entry) throw IOException("injected move") })) }
                assertEquals(before, snapshot(root))
                assertEquals("Previous Cairn", InstrumentStore.list(root).single().second.name)
            }
            assertFailsWith<IllegalArgumentException> {
                CairnSingInstrumentMaker.exportFresh("Previous Cairn", program, captures, root)
            }
            assertEquals(before, snapshot(root))
        } finally { root.deleteRecursively() }
    }
}
