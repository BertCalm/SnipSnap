package com.snipsnap.cli

import com.snipsnap.shell.Mark
import com.snipsnap.shell.Schemes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MarkCommandTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("mark").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun cli(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli.run(arrayOf(*args), PrintStream(out, true), PrintStream(err, true))
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun `mark writes three PNGs sized to the mark`() {
        val dir = File(temp, "marks")
        val (code, stdout, stderr) = cli("mark", "--out", dir.path, "--size", "64")
        assertEquals(0, code, stderr)
        for (v in listOf("symbol", "lockup", "stacked")) {
            val file = File(dir, "mark_$v.png")
            assertTrue(file.isFile, "$v written")
            assertContains(stdout, file.path)
            val img = ImageIO.read(file)
            assertEquals(64 + 32, img.height, "$v: mark plus a quarter margin each side")
            assertTrue(img.width >= 96, "$v: at least as wide as tall")
        }
        assertEquals(64 + 32, ImageIO.read(File(dir, "mark_symbol.png")).width, "the symbol is square")
        assertTrue(ImageIO.read(File(dir, "mark_lockup.png")).width > ImageIO.read(File(dir, "mark_stacked.png")).width, "one line is wider than two")
    }

    @Test
    fun `mark defaults to 120 px and takes a scheme`() {
        val dir = File(temp, "default")
        val (code, _, stderr) = cli("mark", "--out", dir.path, "--scheme", "opensky")
        assertEquals(0, code, stderr)
        val img = ImageIO.read(File(dir, "mark_symbol.png"))
        assertEquals(120 + 60, img.height)
        // A light scheme on its LCD ground: both pads must be there.
        val (ink, accent) = Mark.lcdTones(Schemes.OPENSKY)
        val seen = HashSet<Int>()
        for (y in 0 until img.height) for (x in 0 until img.width) seen += img.getRGB(x, y) and 0xFFFFFF
        assertTrue(ink in seen, "ink pads on the LCD")
        assertTrue(accent in seen, "accent pads on the LCD")
    }

    @Test
    fun `mark refuses a size outside 16 to 1024`() {
        val (code, _, stderr) = cli("mark", "--out", File(temp, "x").path, "--size", "8")
        assertNotEquals(0, code)
        assertContains(stderr, "16..1024")
        val (big, _, bigErr) = cli("mark", "--out", File(temp, "y").path, "--size", "2000")
        assertNotEquals(0, big)
        assertContains(bigErr, "16..1024")
    }
}
