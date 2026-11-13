package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Keys
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurRoundRobinInstrumentSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

/** New import-boundary tests only; synthetic PCM proves transport/admission, not native sound. */
class MurmurRoundRobinBankImportTest {
    @field:TempDir(cleanup = CleanupMode.ON_SUCCESS) lateinit var temporary: Path
    private val name = "RR IMPORT TEST"
    private fun dir(n: String) = File(temporary.toFile(), n)
    private fun cache() = dir("cache").apply { mkdirs() }
    private fun shelf() = dir("shelf")
    private fun noStage() = assertTrue(cache().listFiles()!!.isEmpty())
    private fun noBank() { assertTrue(MurmurRoundRobinShelf.list(shelf()).banks.isEmpty()); noStage() }
    private fun fixture(): File {
        val root = dir("source")
        val spec = MurmurRoundRobinInstrumentBank.Spec(MurmurKeptSources.byId(MurmurKeptSources.STEADY_C2).factoryPatch())
        MurmurRoundRobinInstrumentBank.writeNewUsing(name, spec, root, { false }, { _, _ -> }, renderCell = { recipe ->
            val raw = FloatArray(512) { (.01 * (recipe.take + 1) * (recipe.touchIndex + 1) * sin(2 * PI * it / 64)).toFloat() }
            val samples = raw.copyOf().also { it[it.lastIndex] = 0f }
            MurmurRoundRobinInstrumentSource.Capture(recipe, Snip(samples, 1, WavWriter.MPC_SAMPLE_RATE), 0, samples.size,
                MurmurRoundRobinInstrumentSource.floatSha256(raw), MurmurRoundRobinInstrumentSource.floatSha256(floatArrayOf()),
                MurmurRoundRobinInstrumentSource.floatSha256(samples), 0.0, Keys.midiHz(recipe.midi).toDouble())
        })
        return root
    }
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z -> for ((path, data) in entries) { z.putNextEntry(ZipEntry(path)); z.write(data); z.closeEntry() } }
    }.toByteArray()
    private fun zip(root: File) = zip(root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }.toList())
    private fun hashes(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
    }
    private fun load(bytes: ByteArray) = MurmurRoundRobinBankImport.importZip(ByteArrayInputStream(bytes), cache(), shelf()).saved
    private fun limited(bytes: ByteArray, limits: MurmurRoundRobinBankImport.Limits) =
        MurmurRoundRobinBankImport.importUsing(ByteArrayInputStream(bytes), cache(), shelf(), { false }, { _, _ -> }, limits)

    @Test fun importsCanonicalBankBytesByManifestNameIntoSeparateShelf() {
        val source = fixture(); val before = hashes(source)
        var closed = false
        val input = object : ByteArrayInputStream(zip(source)) { override fun close() { closed = true; super.close() } }
        val progress = mutableListOf<Pair<Int, Int>>()
        val imported = MurmurRoundRobinBankImport.importZip(input, cache(), shelf(), progress = { done, total -> progress += done to total })
        assertTrue(imported.stagingCleaned); val saved = imported.saved
        assertFalse(closed); input.close(); assertTrue(closed)
        assertEquals(name, saved.name); assertEquals(84, saved.cellCount)
        val bank = File(File(File(shelf(), MurmurRoundRobinShelf.DIRECTORY), name), "bank")
        assertEquals(before, hashes(bank)); assertEquals(before, hashes(source)); assertTrue(InstrumentStore.list(shelf()).isEmpty())
        assertEquals(saved.identitySha256, MurmurRoundRobinShelf.list(shelf()).banks.single().identitySha256)
        assertEquals((1..before.size).map { it to before.size }, progress); noStage()
    }

    @Test fun duplicateImportPreservesThePreviouslyAdmittedBank() {
        val bytes = zip(fixture()); val old = load(bytes); val before = hashes(shelf())
        assertFailsWith<IllegalArgumentException> { load(bytes) }
        assertEquals(before, hashes(shelf())); assertEquals(old.identitySha256, MurmurRoundRobinShelf.list(shelf()).banks.single().identitySha256); noStage()
    }

    @Test fun modifiedSavedWavCannotBeAdmittedEvenWithOriginalManifests() {
        val source = fixture(); source.walkTopDown().first { it.extension == "wav" }.appendBytes(byteArrayOf(7))
        assertFailsWith<IllegalArgumentException> { load(zip(source)) }; noBank()
    }

    @Test fun traversalAbsoluteWindowsAndUnexpectedRootPathsAreRefused() {
        for (path in listOf("../outside", "/outside", "take-1/../outside", "take-1//outside", "take-1/./outside", "C:/outside", "take-1/a\\b", "wrapper/murmur-round-robin-bank.json")) {
            assertFailsWith<IllegalArgumentException> { load(zip(listOf(path to byteArrayOf(1)))) }; noBank()
        }
        assertFalse(dir("outside").exists())
    }

    @Test fun duplicateNamesAndFileDirectoryPrefixCollisionsAreRefused() {
        val distinct = zip(listOf("take-1/a" to byteArrayOf(1), "take-1/b" to byteArrayOf(1)))
        val duplicate = distinct.toString(Charsets.ISO_8859_1).replace("take-1/b", "take-1/a").toByteArray(Charsets.ISO_8859_1)
        assertFailsWith<IllegalArgumentException> { load(duplicate) }; noBank()
        assertFailsWith<IllegalArgumentException> { load(zip(listOf("take-1/a" to byteArrayOf(1), "take-1/a/b" to byteArrayOf(1)))) }; noBank()
    }

    @Test fun rawExpandedEntryAndFileBudgetsAreEnforcedWhileReading() {
        val bytes = zip(listOf("take-1/a" to ByteArray(256) { 1 }, "take-2/b" to byteArrayOf(1)))
        for (limits in listOf(MurmurRoundRobinBankImport.Limits(raw = 16), MurmurRoundRobinBankImport.Limits(expanded = 32),
            MurmurRoundRobinBankImport.Limits(files = 1), MurmurRoundRobinBankImport.Limits(entries = 1))) {
            assertFailsWith<IllegalArgumentException> { limited(bytes, limits) }; noBank()
        }
    }

    @Test fun directoryPayloadEmptyArchiveAndOversizedManifestAreRefused() {
        for (bytes in listOf(zip(listOf("take-1/" to byteArrayOf(1))), zip(emptyList()), zip(listOf(MurmurRoundRobinInstrumentBank.MANIFEST to ByteArray(65537) { 32 })))) {
            assertFailsWith<IllegalArgumentException> { load(bytes) }; noBank()
        }
    }

    @Test fun cancellationBeforeReadTouchesNeitherStreamNorShelf() {
        val input = object : InputStream() { override fun read(): Int = error("must not read") }
        assertFailsWith<CancellationException> { MurmurRoundRobinBankImport.importZip(input, cache(), shelf(), { true }) }; noBank()
    }

    @Test fun cancellationDuringExtractionCleansOnlyOwnedStaging() {
        val bytes = zip(listOf("take-1/a" to ByteArray(100000) { (it % 251).toByte() }))
        var read = false
        val input = object : ByteArrayInputStream(bytes) { override fun read(b: ByteArray, off: Int, len: Int): Int { read = true; return super.read(b, off, len) } }
        val neighbor = File(cache(), "unrelated").apply { writeText("retain") }
        assertFailsWith<CancellationException> { MurmurRoundRobinBankImport.importZip(input, cache(), shelf(), { read }) }
        assertEquals("retain", neighbor.readText()); assertEquals(listOf("unrelated"), cache().listFiles()!!.map { it.name }); assertFalse(shelf().exists())
    }

    @Test fun cancellationDuringAdmissionLeavesNoVisibleOrPartialBank() {
        var cancel = false
        assertFailsWith<CancellationException> {
            MurmurRoundRobinBankImport.importZip(ByteArrayInputStream(zip(fixture())), cache(), shelf(), { cancel }, { _, _ -> cancel = true })
        }
        noBank(); assertTrue(File(shelf(), MurmurRoundRobinShelf.DIRECTORY).listFiles().orEmpty().none { it.name.startsWith(".admitting-") })
    }

    @Test fun providerFailurePreservesCallerStreamOwnershipAndCleansStage() {
        var closed = false
        val input = object : InputStream() { override fun read(): Int = throw IOException("provider failed"); override fun close() { closed = true } }
        assertFailsWith<IOException> { MurmurRoundRobinBankImport.importZip(input, cache(), shelf()) }
        assertFalse(closed); noBank()
    }

    @Test fun linkedCacheOrShelfNamespaceCannotRedirectImport() {
        val bytes = zip(fixture()); val outside = dir("outside").apply { mkdir() }; val link = dir("linked-cache")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { MurmurRoundRobinBankImport.importZip(ByteArrayInputStream(bytes), link, shelf()) }
        assertTrue(outside.listFiles()!!.isEmpty())
        shelf().mkdir(); Files.createSymbolicLink(File(shelf(), MurmurRoundRobinShelf.DIRECTORY).toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { load(bytes) }; assertTrue(outside.listFiles()!!.isEmpty()); noStage()
    }

    @Test fun cancellationAfterAtomicAdmissionDoesNotUndoOrMisreportSuccess() {
        var cancel = false
        val saved = MurmurRoundRobinBankImport.importUsing(ByteArrayInputStream(zip(fixture())), cache(), shelf(),
            { cancel }, { _, _ -> }, MurmurRoundRobinBankImport.Limits(), cleanup = { cancel = true; it.deleteRecursively() })
        assertTrue(cancel); assertEquals(saved.saved.identitySha256, MurmurRoundRobinShelf.list(shelf()).banks.single().identitySha256); noStage()
    }

    @Test fun cleanupFailureAfterCommitReturnsSuccessWithExplicitResidualWarning() {
        val result = MurmurRoundRobinBankImport.importUsing(ByteArrayInputStream(zip(fixture())), cache(), shelf(),
            { false }, { _, _ -> }, MurmurRoundRobinBankImport.Limits(), cleanup = { false })
        assertFalse(result.stagingCleaned)
        assertEquals(result.saved.identitySha256, MurmurRoundRobinShelf.list(shelf()).banks.single().identitySha256)
        assertEquals(1, cache().listFiles()!!.size) // Retained private residual, never hidden or misreported as unimported.
    }

}
