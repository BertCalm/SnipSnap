package com.snipsnap.app

import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.ThumpKits
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The architectural bet, made checkable: every algorithm this app calls is
 * pure Kotlin/JVM, so it must compile and link against `:app`'s own
 * classpath and render a full 16-pad factory kit from inside the Android
 * module's source set, exactly as it does in `:kit`'s own suite — and the
 * filenames it writes must stay ASCII under an Eastern-digit locale, the
 * one behavioral difference porting to Android actually exposes.
 *
 * This runs as a JVM unit test on the desktop JDK (Robolectric-free, no
 * device or emulator), so it cannot catch a call into a real Android-only
 * API — only that the module wires together and behaves correctly for the
 * pure-Kotlin surface it exercises.
 */
class CorePortTest {

    private val original: Locale = Locale.getDefault()

    @AfterTest
    fun restore() {
        Locale.setDefault(original)
    }

    private fun tempDir(): File = Files.createTempDirectory("snipsnap-port").toFile()

    @Test
    fun `the factory kit renders and assembles from inside the app module`() {
        val dir = tempDir()
        val kit = KitAssembler.assembleArranged("SNIPSNAP KIT 01", ThumpKits.classic(), dir)

        assertEquals(16, kit.pads.size)
        assertTrue(File(dir, "kit.json").isFile, "kit.json was not written")
        for (pad in kit.pads) {
            val wav = File(dir, pad.sampleFile)
            assertTrue(wav.isFile, "missing ${pad.sampleFile}")
            assertTrue(wav.length() > 44, "${pad.sampleFile} is header-only")
        }

        // The folder is the kit: reloading it must give the same pads back.
        assertEquals(kit.pads.map { it.slot }, KitStore.load(dir).pads.map { it.slot })
    }

    @Test
    fun `generated filenames stay ASCII under an eastern-digit locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val dir = tempDir()
        val kit = KitAssembler.assembleArranged("SNIPSNAP KIT 01", ThumpKits.classic(), dir)
        for (pad in kit.pads) {
            assertTrue(
                pad.sampleFile.all { it.code in 32..126 },
                "non-ASCII in generated filename: ${pad.sampleFile}",
            )
            assertTrue(File(dir, pad.sampleFile).isFile)
        }
    }
}
