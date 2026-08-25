package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KitArtTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("kitart").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun buildKit(dir: File): Kit {
        dir.mkdirs()
        fun tone(n: Int, hz: Double) = Snip(
            FloatArray(n) { i -> (0.5 * Math.sin(2.0 * Math.PI * hz * i / 44_100)).toFloat() },
            1, 44_100,
        )
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone(8_000, 90.0))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), tone(5_000, 400.0))
        WavWriter.write(File(dir, "A03_HatClosed_01.wav"), tone(2_000, 4_000.0))
        val kit = Kit(
            "Art Kit 92",
            listOf(
                KitPad(slot = 1, sampleFile = "A01_Kick_01.wav", drumClass = DrumClass.KICK),
                KitPad(slot = 2, sampleFile = "A02_Snare_01.wav", drumClass = DrumClass.SNARE),
                KitPad(slot = 3, sampleFile = "A03_HatClosed_01.wav", drumClass = DrumClass.HAT_CLOSED),
            ),
        )
        KitStore.save(kit, dir)
        return kit
    }

    @Test
    fun `every style renders on every scheme, deterministic, right size`() {
        val dir = File(temp, "kit")
        val kit = buildKit(dir)
        for (style in KitArt.Style.entries) {
            for (scheme in Schemes.ALL) {
                val a = KitArt.png(kit, dir, style, scheme, seed = 3, size = 200)
                val b = KitArt.png(kit, dir, style, scheme, seed = 3, size = 200)
                assertTrue(a.contentEquals(b), "same inputs, same bytes: ${style.id}/${scheme.id}")
                val img = ImageIO.read(ByteArrayInputStream(a))
                assertEquals(200, img.width, "${style.id}: width")
                assertEquals(200, img.height, "${style.id}: height")
                // Something got drawn on the LCD ground.
                val ground = img.getRGB(0, 0)
                var drawn = 0
                for (y in 0 until img.height step 4) {
                    for (x in 0 until img.width step 4) {
                        if (img.getRGB(x, y) != ground) drawn++
                    }
                }
                assertTrue(drawn > 20, "${style.id}/${scheme.id}: the tile isn't blank")
            }
        }
    }

    @Test
    fun `the seed reshuffles rings and nothing else`() {
        val dir = File(temp, "seeded")
        val kit = buildKit(dir)
        val rings3 = KitArt.png(kit, dir, KitArt.Style.RINGS, seed = 3, size = 200)
        val rings4 = KitArt.png(kit, dir, KitArt.Style.RINGS, seed = 4, size = 200)
        assertTrue(!rings3.contentEquals(rings4), "rings answer the dice")

        val grid3 = KitArt.png(kit, dir, KitArt.Style.GRID, seed = 3, size = 200)
        val grid4 = KitArt.png(kit, dir, KitArt.Style.GRID, seed = 4, size = 200)
        assertTrue(grid3.contentEquals(grid4), "the grid is what the kit is, not what the dice say")
    }

    @Test
    fun `every starter kit renders every style without error`() {
        for (starter in StarterKits.ALL) {
            val dir = File(temp, "starter-${starter.id}")
            val kit = starter.render("Starter ${starter.id}", dir, seed = 1)
            for (style in KitArt.Style.entries) {
                val png = KitArt.png(kit, dir, style, size = 128)
                assertTrue(png.isNotEmpty(), "${starter.id}/${style.id}")
            }
        }
    }

    @Test
    fun `pixel type fits long names instead of overflowing`() {
        // Every char the MPC-safe alphabet allows, plus one it doesn't:
        // the box glyph renders visibly rather than crashing.
        val name = "The Quick-Brown_Fox & 0123456789.é"
        val px = PixelType.fit(name, maxWidthPx = 300, maxHeightPx = 70)
        assertTrue(PixelType.width(name, px) <= 300, "shrunk to fit")

        // Every glyph in the face draws without error, box fallback included.
        val img = java.awt.image.BufferedImage(600, 40, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        PixelType.draw(
            g, "ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789-_.'&é", 0, 0, 14,
            java.awt.Color.WHITE, maxWidthPx = 600,
        )
        g.dispose()

        val dir = File(temp, "longname")
        dir.mkdirs()
        WavWriter.write(
            File(dir, "A01_Snip_01.wav"),
            Snip(FloatArray(1_000) { 0.3f }, 1, 44_100),
        )
        val kit = Kit(
            "A Really Quite Long Kit Name Indeed 2026",
            listOf(KitPad(slot = 1, sampleFile = "A01_Snip_01.wav")),
        )
        KitStore.save(kit, dir)
        assertTrue(KitArt.png(kit, dir, KitArt.Style.GRID, size = 200).isNotEmpty())
    }
}
