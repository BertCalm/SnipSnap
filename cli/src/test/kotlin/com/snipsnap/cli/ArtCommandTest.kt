package com.snipsnap.cli

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.KitArt
import com.snipsnap.shell.Palettes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtCommandTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("artcmd").toFile()

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

    private fun kit(): File {
        val dir = File(temp, "ArtTiles").apply { mkdirs() }
        fun tone(n: Int, hz: Double) = Snip(FloatArray(n) { i -> (0.5 * Math.sin(2.0 * Math.PI * hz * i / 44_100)).toFloat() }, 1, 44_100)
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone(8_000, 90.0))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), tone(5_000, 400.0))
        KitStore.save(
            Kit(
                "ArtTiles",
                listOf(
                    KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK),
                    KitPad(slot = 2, sampleFile = "A02_Snare_01.wav", drumClass = DrumClass.SNARE),
                ),
            ),
            dir,
        )
        return dir
    }

    @Test
    fun `art with no style writes all eight`() {
        val dir = kit()
        val out = File(temp, "all")
        val (code, stdout, stderr) = cli("art", dir.path, "--out", out.path, "--size", "128")
        assertEquals(0, code, stderr)
        assertEquals(8, KitArt.Style.entries.size)
        for (style in KitArt.Style.entries) {
            val f = File(out, "ArtTiles_${style.id}.png")
            assertTrue(f.isFile, "${style.id} written")
            assertContains(stdout, f.path)
        }
        assertContains(stdout, "--palette")
    }

    @Test
    fun `art takes a palette, a seed pad and a mark`() {
        val dir = kit()
        val out = File(temp, "knobs")
        val (code, stdout, stderr) = cli(
            "art", dir.path, "--style", "bloom", "--palette", "riso", "--seed-pad", "2", "--mark", "symbol",
            "--size", "256", "--out", out.path,
        )
        assertEquals(0, code, stderr)
        assertContains(stdout, "palette riso")
        assertContains(stdout, "seed pad A02")
        assertContains(stdout, "mark symbol")
        val img = ImageIO.read(File(out, "ArtTiles_bloom.png"))
        assertEquals(Palettes.RISO.ground, img.getRGB(1, 1) and 0xFFFFFF, "the palette's ground")
        var accent = 0
        for (y in 192 until 256) for (x in 192 until 256) if (img.getRGB(x, y) and 0xFFFFFF == Palettes.RISO.field1) accent++
        assertTrue(accent > 10, "the symbol's accent pads sit bottom-right ($accent pixels)")
        val other = File(temp, "seed1")
        assertEquals(0, cli("art", dir.path, "--style", "bloom", "--palette", "riso", "--seed-pad", "1", "--mark", "symbol", "--size", "256", "--out", other.path).first)
        assertTrue(!File(out, "ArtTiles_bloom.png").readBytes().contentEquals(File(other, "ArtTiles_bloom.png").readBytes()), "the seed pad draws the field")
    }

    @Test
    fun `art refuses an unknown palette or mark`() {
        val dir = kit()
        val (pCode, _, pErr) = cli("art", dir.path, "--palette", "mauve")
        assertEquals(2, pCode)
        assertContains(pErr, "unknown palette 'mauve'")
        assertContains(pErr, Palettes.ALL.joinToString(",") { it.id })
        val (mCode, _, mErr) = cli("art", dir.path, "--mark", "big")
        assertEquals(2, mCode)
        assertContains(mErr, "symbol,lockup,stacked")
    }
}
