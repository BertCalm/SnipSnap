package com.snipsnap.synth

import com.snipsnap.kit.ConfluenceBankFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfluenceSelectedBankPathTest {
    private val temp = Files.createTempDirectory("selected-bank-path-").toFile()
    private val bank by lazy { ConfluenceSelectedBank.bundled() }
    @AfterTest fun cleanUp() { temp.deleteRecursively() }

    @Test
    fun `portable bank saves and reopens through an aliased platform storage ancestor`() {
        val actualStorage = File(temp, "actual-app-data").apply { mkdirs() }
        val storageAlias = File(temp, "platform-app-data")
        Files.createSymbolicLink(storageAlias.toPath(), actualStorage.toPath())
        // Android exposes aliases above files/cache and the app-owned kit directory. Those
        // ancestors are outside the controlled kit/container/bank portion of the path.
        val kitDir = File(storageAlias, "files/kits/Voices").apply { mkdirs() }
        val destination = ConfluenceBankFiles.bankDirectory(kitDir, bank.manifestSha256)
        bank.writePortableBank(destination)

        val reopened = ConfluenceSelectedBank.fromDirectory(destination)
        assertEquals(bank.manifestSha256, reopened.manifestSha256)
        assertEquals(File(actualStorage, "files/kits/Voices/${ConfluenceBankFiles.DIRECTORY}/${bank.manifestSha256}").canonicalFile,
            destination.canonicalFile)
        for (path in bank.assetPaths) assertContentEquals(bank.assetBytes(path), reopened.assetBytes(path), path)
        assertTrue(Files.isSymbolicLink(storageAlias.toPath()), "saving must not replace or remove the platform alias")
    }

    @Test
    fun `bank root immediate container and kit root links remain refused`() {
        val actualBank = File(temp, "actual-bank")
        bank.writePortableBank(actualBank)
        val rootLink = File(temp, "bank-link")
        Files.createSymbolicLink(rootLink.toPath(), actualBank.toPath())
        assertSymlinkRefused { ConfluenceSelectedBank.fromDirectory(rootLink) }
        assertSymlinkRefused { bank.writePortableBank(rootLink, overwrite = true) }
        assertSymlinkRefused { bank.writePortableBank(File(rootLink, "child")) }
        assertFalse(File(actualBank, "child").exists())

        val kit = File(temp, "kit").apply { mkdirs() }
        val outsideContainer = File(temp, "outside-container").apply { mkdirs() }
        val container = File(kit, ConfluenceBankFiles.DIRECTORY)
        Files.createSymbolicLink(container.toPath(), outsideContainer.toPath())
        assertSymlinkRefused { bank.writePortableBank(File(container, bank.manifestSha256)) }
        assertSymlinkRefused { ConfluenceBankFiles.bankDirectory(kit, bank.manifestSha256) }
        assertTrue(outsideContainer.listFiles()!!.isEmpty(), "refusal must precede any source writes")

        val kitLink = File(temp, "kit-link")
        Files.createSymbolicLink(kitLink.toPath(), kit.toPath())
        assertSymlinkRefused { ConfluenceBankFiles.bankDirectory(kitLink, bank.manifestSha256) }
    }

    @Test
    fun `asset and nested directory links are refused before reading or overwriting their targets`() {
        val directory = File(temp, "bank")
        bank.writePortableBank(directory)
        val asset = File(directory, bank.voices.first().assetPath)
        val outsideAsset = File(temp, "outside.wav")
        assertTrue(asset.renameTo(outsideAsset))
        val original = outsideAsset.readBytes()
        Files.createSymbolicLink(asset.toPath(), outsideAsset.toPath())
        assertSymlinkRefused { ConfluenceSelectedBank.fromDirectory(directory) }
        assertSymlinkRefused { bank.writePortableBank(directory, overwrite = true) }
        assertContentEquals(original, outsideAsset.readBytes())

        assertTrue(asset.delete())
        assertTrue(outsideAsset.renameTo(asset))
        val voices = File(directory, "voices")
        val outsideVoices = File(temp, "outside-voices")
        assertTrue(voices.renameTo(outsideVoices))
        Files.createSymbolicLink(voices.toPath(), outsideVoices.toPath())
        assertSymlinkRefused { ConfluenceSelectedBank.fromDirectory(directory) }
        assertSymlinkRefused { bank.writePortableBank(directory, overwrite = true) }
        assertContentEquals(original, File(outsideVoices, asset.name).readBytes())
    }

    private fun assertSymlinkRefused(action: () -> Unit) {
        val error = assertFailsWith<IllegalArgumentException> { action() }
        assertTrue(error.message!!.contains("symbolic link"), error.message)
    }
}
