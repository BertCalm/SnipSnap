package com.snipsnap.app.store

import com.snipsnap.shell.Personality
import com.snipsnap.shell.SchemeId
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {

    private fun file(): File = File(Files.createTempDirectory("settings").toFile(), "tape.properties")

    @Test
    fun `an absent file gives the defaults`() {
        val s = FileSettings(file())
        assertEquals(SchemeId.CHROME, s.schemeId)
        assertEquals(Personality.FULL, s.personality)
    }

    @Test
    fun `a chosen scheme survives a reload`() {
        val f = file()
        FileSettings(f).schemeId = SchemeId.OILSLICK
        assertEquals(SchemeId.OILSLICK, FileSettings(f).schemeId)
    }

    @Test
    fun `personality survives a reload`() {
        val f = file()
        FileSettings(f).personality = Personality.OFF
        assertEquals(Personality.OFF, FileSettings(f).personality)
    }

    @Test
    fun `both settings are independent`() {
        val f = file()
        val s = FileSettings(f)
        s.schemeId = SchemeId.SNACK_BAR
        s.personality = Personality.MILD
        val reloaded = FileSettings(f)
        assertEquals(SchemeId.SNACK_BAR, reloaded.schemeId)
        assertEquals(Personality.MILD, reloaded.personality)
    }

    @Test
    fun `a corrupt file falls back to defaults instead of crashing`() {
        val f = file()
        f.parentFile.mkdirs()
        f.writeText("scheme=NOT_A_SCHEME\npersonality=LOUD\ngarbage\n")
        val s = FileSettings(f)
        assertEquals(SchemeId.CHROME, s.schemeId)
        assertEquals(Personality.FULL, s.personality)
    }

    @Test
    fun `a write that cannot land loses the setting instead of crashing`() {
        // The "directory" is a plain file, so parentFile.mkdirs() no-ops
        // and the eventual writeText() throws — exercising write()'s catch
        // rather than read()'s.
        val blocker = Files.createTempFile("blocker", ".txt").toFile()
        val f = File(blocker, "tape.properties")
        val s = FileSettings(f)
        s.schemeId = SchemeId.METAL
        assertEquals(SchemeId.METAL, s.schemeId)
        assertEquals(SchemeId.CHROME, FileSettings(f).schemeId)
    }
}
