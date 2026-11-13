package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.Keys
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurRoundRobinInstrumentSource
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Admission/controller integration with synthetic saved PCM; no native captures or Android credit. */
class MurmurRoundRobinShelfTest {
    @field:TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var temporary: Path
    private fun directory(name: String) = File(temporary.toFile(), name)
    private fun fixture(folder: String = "source", scale: Double = 1.0): File {
        val root = directory(folder)
        val spec = MurmurRoundRobinInstrumentBank.Spec(MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch())
        MurmurRoundRobinInstrumentBank.writeNewUsing(NAME, spec, root, { false }, { _, _ -> }, renderCell = { recipe ->
            val raw = FloatArray(512) { (scale * .01 * (recipe.take + 1) * (recipe.touchIndex + 1) * sin(2 * PI * it / 64)).toFloat() }
            val samples = raw.copyOf().also { it[it.lastIndex] = 0f }
            MurmurRoundRobinInstrumentSource.Capture(recipe, Snip(samples, 1, WavWriter.MPC_SAMPLE_RATE), 0, samples.size,
                MurmurRoundRobinInstrumentSource.floatSha256(raw), MurmurRoundRobinInstrumentSource.floatSha256(floatArrayOf()),
                MurmurRoundRobinInstrumentSource.floatSha256(samples), 0.0, Keys.midiHz(recipe.midi).toDouble())
        })
        return root
    }
    private fun entry(shelf: File) = File(File(shelf, MurmurRoundRobinShelf.DIRECTORY), NAME)
    private fun savedRoot(shelf: File) = File(entry(shelf), "bank")
    private fun firstWav(root: File): File = root.walkTopDown().first { it.isFile && it.extension == "wav" }
    private fun hashes(root: File) = root.walkTopDown().filter { it.isFile }.associate { file ->
        file.relativeTo(root).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    }
    private fun noStage(shelf: File) = assertTrue(File(shelf, MurmurRoundRobinShelf.DIRECTORY).listFiles().orEmpty().none { it.name.startsWith(".admitting-") })

    @Test fun savedAdmissionIsByteExactAndNeverBecomesAnOrdinarySingleProgramInstrument() {
        val source = fixture(); val before = hashes(source); val shelf = directory("shelf")
        val progress = mutableListOf<Pair<Int, Int>>()
        val saved = MurmurRoundRobinShelf.admitSaved(NAME, source, shelf, progress = { done, total -> progress += done to total })
        assertEquals(84, saved.cellCount)
        assertEquals(36..54, saved.supportedMidi)
        assertEquals(84L * 512 * Float.SIZE_BYTES, saved.decodedPcmBytes)
        assertEquals(before, hashes(savedRoot(shelf)))
        assertEquals(before, hashes(source))
        assertEquals((1..before.size).map { it to before.size }, progress)
        assertTrue(InstrumentStore.list(shelf).isEmpty())
        val listing = MurmurRoundRobinShelf.list(shelf)
        assertTrue(listing.refused.isEmpty())
        assertEquals(saved.identitySha256, listing.banks.single().identitySha256)
        noStage(shelf)
    }

    @Test fun reopeningAnAdmittedBankLoadsItsBoundSavedPcmAndFixedTakeExportIsExplicit() {
        val source = fixture(); val shelf = directory("shelf")
        val saved = MurmurRoundRobinShelf.admitSaved(NAME, source, shelf)
        val reopened = MurmurRoundRobinShelf.list(shelf).banks.single()
        MurmurRoundRobinShelf.loadReference(reopened, shelf).use { player ->
            val attack = assertNotNull(player.noteOn(36, .35f))
            assertEquals(0, attack.take)
            val out = FloatArray(256)
            player.render(out, 128)
            assertTrue(out.all { it.isFinite() })
            assertTrue(out.any { it != 0f })
        }
        val instruments = directory("ordinary-instruments")
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.exportFixedTake(saved, shelf, 4, instruments) }
        assertFalse(instruments.exists())
        val packet = MurmurRoundRobinShelf.exportFixedTake(saved, shelf, 3, instruments)
        assertEquals(3, packet.spec.take)
        assertEquals("$NAME R4", packet.program.name)
        assertEquals(1, InstrumentStore.list(instruments).size)
        for (row in packet.rows) {
            val data = Mpc3TrackWriter.trackDataDirName(packet.program.name)
            val original = File(File(source, "take-4"), "$data/${row.sample}")
            assertTrue(original.readBytes().contentEquals(File(instruments, "$data/${row.sample}").readBytes()))
        }
        assertTrue(InstrumentStore.list(shelf).isEmpty())
    }

    @Test fun existingEntriesAndOrdinaryShelfSiblingsAreNeverReplaced() {
        val source = fixture(); val shelf = directory("shelf").apply { mkdir() }
        val sibling = File(shelf, "existing.instrument.json").apply { writeText("owned earlier") }
        val original = MurmurRoundRobinShelf.admitSaved(NAME, source, shelf)
        val before = hashes(entry(shelf))
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.admitSaved(NAME, source, shelf) }
        assertEquals(before, hashes(entry(shelf)))
        assertEquals("owned earlier", sibling.readText())
        assertEquals(original.identitySha256, MurmurRoundRobinShelf.list(shelf).banks.single().identitySha256)
        noStage(shelf)
    }

    @Test fun cancellationBeforeWorkAndBeforePublicationNeverLeavesAnEntry() {
        val source = fixture(); val shelf = directory("shelf")
        assertFailsWith<CancellationException> { MurmurRoundRobinShelf.admitSaved(NAME, source, shelf, { true }) }
        assertFalse(shelf.exists())
        var cancelled = false
        assertFailsWith<CancellationException> {
            MurmurRoundRobinShelf.admitSavedUsing(NAME, source, shelf, { cancelled }, { _, _ -> },
                MurmurRoundRobinShelf.Hooks(beforeMove = { cancelled = true }))
        }
        assertFalse(entry(shelf).exists())
        noStage(shelf)
        assertTrue(MurmurRoundRobinShelf.list(shelf).banks.isEmpty())
    }

    @Test fun sourceMutationAfterValidationAndCopiedFileMutationAreBothRejected() {
        val source = fixture(); val shelf = directory("shelf"); val wav = firstWav(source)
        val original = wav.readBytes()
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinShelf.admitSavedUsing(NAME, source, shelf, { false }, { _, _ -> },
                MurmurRoundRobinShelf.Hooks(sourceValidated = { wav.appendBytes(byteArrayOf(1)) }))
        }
        assertFalse(entry(shelf).exists()); noStage(shelf)
        wav.writeBytes(original)
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinShelf.admitSavedUsing(NAME, source, shelf, { false }, { _, _ -> },
                MurmurRoundRobinShelf.Hooks(copied = {
                    val stage = File(shelf, MurmurRoundRobinShelf.DIRECTORY).listFiles()!!.single { it.name.startsWith(".admitting-") }
                    firstWav(File(stage, "bank")).appendBytes(byteArrayOf(2))
                }))
        }
        assertFalse(entry(shelf).exists()); noStage(shelf)
        assertTrue(original.contentEquals(wav.readBytes()))
    }

    @Test fun sourceAndDestinationOverlapIsRefusedWithoutChangingTheSource() {
        val source = fixture(); val before = hashes(source)
        for (shelf in listOf(source, File(source, "nested"))) {
            assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.admitSaved(NAME, source, shelf) }
            assertEquals(before, hashes(source))
            assertFalse(File(shelf, MurmurRoundRobinShelf.DIRECTORY).exists())
        }
        assertFalse(File(source, "nested").exists())
    }

    @Test fun corruptionAtTheFinalMoveBoundaryCannotPublishAnEntry() {
        val source = fixture(); val shelf = directory("shelf")
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinShelf.admitSavedUsing(NAME, source, shelf, { false }, { _, _ -> },
                MurmurRoundRobinShelf.Hooks(beforeMove = {
                    val stage = File(shelf, MurmurRoundRobinShelf.DIRECTORY).listFiles()!!.single { it.name.startsWith(".admitting-") }
                    firstWav(File(stage, "bank")).appendBytes(byteArrayOf(3))
                }))
        }
        assertFalse(entry(shelf).exists()); noStage(shelf)
    }

    @Test fun failedAdmissionCleanupNeverFollowsAnIntroducedSymbolicLink() {
        val source = fixture(); val shelf = directory("shelf")
        val outside = directory("outside").apply { mkdir() }
        val retained = File(outside, "kept.txt").apply { writeText("not owned by admission") }
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinShelf.admitSavedUsing(NAME, source, shelf, { false }, { _, _ -> },
                MurmurRoundRobinShelf.Hooks(beforeMove = {
                    val stage = File(shelf, MurmurRoundRobinShelf.DIRECTORY).listFiles()!!.single { it.name.startsWith(".admitting-") }
                    Files.createSymbolicLink(File(stage, "bank/outside-link").toPath(), outside.toPath())
                }))
        }
        assertEquals("not owned by admission", retained.readText())
        assertFalse(entry(shelf).exists()); noStage(shelf)
    }

    @Test fun stagedOrMidPublicationChangesRollBackOnlyTheNewFixedTakeExport() {
        val source = fixture(); val shelf = directory("shelf")
        val saved = MurmurRoundRobinShelf.admitSaved(NAME, source, shelf)
        val destination = directory("ordinary").apply { mkdir() }
        val neighbor = File(destination, "previous.instrument.json").apply { writeText("keep this earlier instrument") }
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinShelf.exportFixedTakeUsing(saved, shelf, 0, destination,
                InstrumentPackageAdmission.Hooks(staged = { firstWav(it).appendBytes(byteArrayOf(1)) }))
        }
        assertEquals(listOf(neighbor.name), destination.listFiles()!!.map { it.name })
        val wav = firstWav(savedRoot(shelf)); val before = wav.readBytes()
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinShelf.exportFixedTakeUsing(saved, shelf, 0, destination,
                InstrumentPackageAdmission.Hooks(moved = { wav.appendBytes(byteArrayOf(2)) }))
        }
        assertEquals(listOf(neighbor.name), destination.listFiles()!!.map { it.name })
        assertEquals("keep this earlier instrument", neighbor.readText())
        wav.writeBytes(before)
        assertEquals(saved.identitySha256, MurmurRoundRobinShelf.list(shelf).banks.single().identitySha256)
        assertFailsWith<IllegalArgumentException> {
            MurmurRoundRobinShelf.exportFixedTake(saved, shelf, 0, savedRoot(shelf))
        }
        val linked = directory("linked-destination")
        Files.createSymbolicLink(linked.toPath(), destination.toPath())
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.exportFixedTake(saved, shelf, 0, linked) }
        assertEquals(listOf(neighbor.name), destination.listFiles()!!.map { it.name })
    }

    @Test fun changedChildBytesInvalidateListingLoadingAndExportEvenWithTheOriginalTopManifest() {
        val source = fixture(); val shelf = directory("shelf")
        val saved = MurmurRoundRobinShelf.admitSaved(NAME, source, shelf)
        val top = File(savedRoot(shelf), MurmurRoundRobinInstrumentBank.MANIFEST).readText()
        firstWav(savedRoot(shelf)).appendBytes(byteArrayOf(7))
        assertEquals(top, File(savedRoot(shelf), MurmurRoundRobinInstrumentBank.MANIFEST).readText())
        val listing = MurmurRoundRobinShelf.list(shelf)
        assertTrue(listing.banks.isEmpty())
        assertEquals(NAME, listing.refused.single().name)
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.loadReference(saved, shelf) }
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.exportFixedTake(saved, shelf, 0, directory("exports")) }
        assertFalse(directory("exports").exists())
    }

    @Test fun aReplacementAtTheSameNameCannotReuseAnOldHandle() {
        val source = fixture(); val shelf = directory("shelf")
        val old = MurmurRoundRobinShelf.admitSaved(NAME, source, shelf)
        assertTrue(entry(shelf).deleteRecursively())
        val different = fixture("replacement", .5)
        val replacement = MurmurRoundRobinShelf.admitSaved(NAME, different, shelf)
        assertNotEquals(old.identitySha256, replacement.identitySha256)
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.loadReference(old, shelf) }
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.exportFixedTake(old, shelf, 0, directory("exports")) }
        MurmurRoundRobinShelf.loadReference(replacement, shelf).close()
    }

    @Test fun heldAndOtherSourcesAreRefusedBeforeTheirGridOrDestinationIsTouched() {
        for (id in listOf(MurmurKeptSources.HELD_WIRE, MurmurKeptSources.FINITE_WIRE)) {
            val source = directory(id).apply { mkdir() }
            File(source, MurmurRoundRobinInstrumentBank.MANIFEST).writeText(Json.write(JsonValue.Obj(linkedMapOf(
                "sourcePatch" to MurmurKeptSources.byId(id).factoryPatch().toJsonValue(),
            ))))
            val shelf = directory("not-created")
            val failure = assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.admitSaved(NAME, source, shelf) }
            assertTrue(failure.message!!.contains("finite STEADY ROOT C2"))
            assertFalse(shelf.exists())
        }
    }

    @Test fun symbolicLinksAndTraversalCannotRedirectTheBankNamespace() {
        val source = fixture(); val shelf = directory("shelf").apply { mkdir() }
        val outside = directory("outside").apply { mkdir() }
        Files.createSymbolicLink(File(shelf, MurmurRoundRobinShelf.DIRECTORY).toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.admitSaved(NAME, source, shelf) }
        assertTrue(outside.listFiles()!!.isEmpty())
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.list(shelf) }
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.admitSaved("../ESCAPE", source, directory("other")) }
        val linked = directory("linked-source")
        Files.createSymbolicLink(linked.toPath(), source.toPath())
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.admitSaved(NAME, linked, directory("other")) }
        val wav = firstWav(source)
        val retained = File(outside, "kept.wav").apply { writeBytes(wav.readBytes()) }
        assertTrue(wav.delete())
        Files.createSymbolicLink(wav.toPath(), retained.toPath())
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.admitSaved(NAME, source, directory("other")) }
        assertTrue(retained.isFile)
        assertFalse(directory("other").exists())
    }

    @Test fun oversizedSavedInputIsRefusedBeforeDecodeOrCopy() {
        val source = fixture(); val shelf = directory("shelf")
        RandomAccessFile(firstWav(source), "rw").use { it.setLength(MurmurRoundRobinShelf.MAX_BANK_BYTES + 1) }
        val failure = assertFailsWith<IllegalArgumentException> { MurmurRoundRobinShelf.admitSaved(NAME, source, shelf) }
        assertTrue(failure.message!!.contains("byte budget"))
        assertFalse(shelf.exists())
    }

    companion object { private const val NAME = "MURMUR RR SHELF" }
}
