package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.WearLedger
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JCardTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("jcard").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun model(name: String): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name.replace(' ', '_')))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.save()
        return m
    }

    @Test
    fun `the card is deterministic and shaped like a J-card`() {
        val m = model("Chamber")
        val a = JCard.png(m.kit, m.kitDir)
        val b = JCard.png(m.kit, m.kitDir)
        assertTrue(a.contentEquals(b), "same kit, same card, same bytes")

        val img = ImageIO.read(ByteArrayInputStream(a))
        assertEquals(JCard.DEFAULT_WIDTH, img.width)
        val expected = (JCard.DEFAULT_WIDTH * 0.64f).toInt() * 2 + (JCard.DEFAULT_WIDTH * 0.1475f).toInt()
        assertEquals(expected, img.height, "front + spine + back")

        // The card reflects the kit: a worn kit's card differs (the spine
        // carries the mileage), and so does a renamed one.
        val worn = m.kit.copy(wear = WearLedger(mileage = 500.0))
        assertTrue(!JCard.png(worn, m.kitDir).contentEquals(a), "mileage shows on the card")
    }

    @Test
    fun `the spine carries the mark`() {
        // The symbol leads the spine: 0.6 of the band tall, vertically
        // centred, a margin in from the left end, in the scheme's two tones.
        val m = model("Spined")
        val width = 600
        val img = JCard.render(m.kit, m.kitDir, width = width, catalog = "SHT-001")
        val top = (width * 0.64f).toInt()
        val h = (width * 0.1475f).toInt()
        val margin = (width * 0.05f).toInt()
        val size = h * 0.6f
        val (ink, accent) = Mark.tones(Schemes.DEFAULT)
        val pads = Mark.symbol(margin.toFloat(), top + (h - size) / 2f, size)
        val expected = listOf(accent, ink, ink, accent)
        pads.forEachIndexed { i, d ->
            assertEquals(expected[i], img.getRGB(d.cx.toInt(), d.cy.toInt()) and 0xFFFFFF, "pad $i at its centre")
        }
        assertTrue(pads.maxOf { it.cx + it.r } < h, "the symbol sits inside the spine's left end")
    }

    @Test
    fun `long names and a full 32-pad kit still fit`() {
        val big = model("The Longest Kit Name Anyone Ever Typed Onto A Cassette")
        for (slot in 4..32) {
            big.assign(slot, DrumSynth.closedHat(seed = slot), DrumClass.PERC)
        }
        big.save()
        assertEquals(32, big.kit.pads.size)
        val png = JCard.png(big.kit, big.kitDir)
        assertTrue(png.isNotEmpty(), "the 32-pad card renders")
        val img = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(JCard.DEFAULT_WIDTH, img.width)
    }

    @Test
    fun `every starter kit renders its insert`() {
        StarterKits.ALL.forEachIndexed { i, starter ->
            val dir = File(temp, "starter-$i")
            val kit = starter.render("Starter ${starter.id}", dir)
            val png = JCard.png(kit, dir)
            assertTrue(png.isNotEmpty(), "${starter.id} renders a card")
        }
    }

    @Test
    fun `the groove notation folds the pattern to sixteen steps`() {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val clip = Mpc3Clip(
            "G", 2,
            listOf(
                Mpc3Note(36, 0, 0.9f),
                Mpc3Note(38, 4 * s16, 0.6f),
                // Bar two lands on the same folded step as the kick.
                Mpc3Note(36, Mpc3Clip.PULSES_PER_BAR, 0.5f),
            ),
        )
        val steps = JCard.stepVelocities(clip)
        assertEquals(16, steps.size)
        assertEquals(0.9f, steps[0], "the louder pass wins the folded step")
        assertEquals(0.6f, steps[4])
        assertEquals(0f, steps[1])
    }
    /** A canvas of plain ints — no AWT anywhere, the way the phone's canvas is. */
    private class IntCanvas(val w: Int, val h: Int) : ArtCanvas {
        val px = IntArray(w * h)

        override fun fillRect(x: Int, y: Int, w: Int, h: Int, colorRgb: Int) {
            for (yy in maxOf(0, y) until minOf(this.h, y + h)) {
                for (xx in maxOf(0, x) until minOf(this.w, x + w)) px[yy * this.w + xx] = colorRgb
            }
        }

        override fun drawArc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, strokeWidth: Float, colorRgb: Int) =
            error("the J-card draws no arcs")

        /** A plain even-odd scanline fill, no antialiasing: the parity test skips the box the symbol sits in. */
        override fun fillPolygon(xs: FloatArray, ys: FloatArray, colorRgb: Int) {
            val n = xs.size
            for (yy in maxOf(0, ys.min().toInt()) until minOf(h, ys.max().toInt() + 1)) {
                val sy = yy + 0.5f
                val crossings = ArrayList<Float>()
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    if ((ys[i] <= sy) != (ys[j] <= sy)) crossings += xs[i] + (sy - ys[i]) / (ys[j] - ys[i]) * (xs[j] - xs[i])
                }
                crossings.sort()
                for (k in 0 until crossings.size - 1 step 2) {
                    for (xx in maxOf(0, crossings[k].toInt()) until minOf(w, crossings[k + 1].toInt())) px[yy * w + xx] = colorRgb
                }
            }
        }

        override fun drawText(text: String, font: ArtFont, sizePx: Float, x: Float, baselineY: Float, anchor: TextAnchor, colorRgb: Int) =
            error("the J-card sets no type")

        override fun textWidth(text: String, font: ArtFont, sizePx: Float): Float =
            error("the J-card measures no type")
    }

    @Test
    fun `drawing through ArtCanvas lands the same pixels as the AWT card`() {
        // The phone paints JCard.draw with its own canvas; the desktop card is
        // JCard.draw through AwtCanvas. Same layout code, so same pixels.
        val m = model("Parity")
        val width = 600
        val canvas = IntCanvas(width, JCard.heightFor(width))
        JCard.draw(canvas, m.kit, m.kitDir, width = width, catalog = "SHT-001")
        val awt = JCard.render(m.kit, m.kitDir, width = width, catalog = "SHT-001")
        assertEquals(canvas.h, awt.height)
        // The spine's symbol is the card's one antialiased element; AWT's
        // edge coverage is its own, so that box is the one place the plain
        // canvas is allowed to differ. Everything else must match exactly.
        val mark = JCard.markBox((width * 0.64f).toInt(), (width * 0.1475f).toInt(), (width * 0.05f).toInt())
        var compared = 0
        for (y in 0 until awt.height) {
            for (x in 0 until awt.width) {
                if (x in mark.x..(mark.x + mark.w) && y in mark.y..(mark.y + mark.h)) continue
                assertEquals(awt.getRGB(x, y) and 0xFFFFFF, canvas.px[y * width + x], "pixel $x,$y")
                compared++
            }
        }
        assertTrue(compared > awt.width * awt.height * 0.99, "the skipped box is a sliver of the card")
    }

    @Test
    fun `Inserts write paints the J-card with the renderer it is handed`() {
        val m = model("Handed")
        val into = File(temp, "expansion")
        val seen = mutableListOf<String?>()
        val written = Inserts.write(m.kit, m.kitDir, into) { _, _, catalog ->
            seen += catalog
            byteArrayOf(1, 2, 3)
        }
        assertEquals(listOf<String?>(null), seen, "a kit outside a crate has no catalog number")
        assertTrue(File(into, Inserts.J_CARD_NAME).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(2, written.size)
    }

}
