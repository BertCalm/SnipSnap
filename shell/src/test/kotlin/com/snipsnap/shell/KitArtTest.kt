package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.isBanked
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.pow
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

    private fun knobs(style: KitArt.Style, palette: Palette = Palettes.VENT, seed: Int? = null, mark: Mark.Variant = Mark.Variant.STACKED) =
        ArtKnobs(style, palette, seed, mark)

    private fun rgb(img: BufferedImage, x: Int, y: Int) = img.getRGB(x, y) and 0xFFFFFF

    private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))

    /** WCAG relative luminance of a packed colour. */
    private fun luminance(rgb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch((rgb shr 16) and 0xFF) + 0.7152 * ch((rgb shr 8) and 0xFF) + 0.0722 * ch(rgb and 0xFF)
    }

    private fun contrast(l1: Double, l2: Double): Double = (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)

    /** Everything but the type: what the name zone holds under the name. */
    private class TextlessCanvas(private val inner: ArtCanvas) : ArtCanvas by inner {
        override fun drawText(text: String, font: ArtFont, sizePx: Float, x: Float, baselineY: Float, anchor: TextAnchor, colorRgb: Int) = Unit
    }

    private fun renderTextless(kit: Kit, dir: File, k: ArtKnobs, size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            KitArt.draw(TextlessCanvas(KitArt.AwtCanvas(g)), kit, dir, k, size, kit.name)
        } finally {
            g.dispose()
        }
        return img
    }

    private fun probeCanvas(): ArtCanvas = KitArt.AwtCanvas(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).createGraphics())

    @Test
    fun `every style renders on every palette, deterministic, right size, ground dominant`() {
        val dir = File(temp, "kit")
        val kit = buildKit(dir)
        for (style in KitArt.Style.entries) {
            for (palette in Palettes.ALL) {
                val k = knobs(style, palette)
                val a = KitArt.png(kit, dir, k, size = 200)
                val b = KitArt.png(kit, dir, k, size = 200)
                assertTrue(a.contentEquals(b), "same inputs, same bytes: ${style.id}/${palette.id}")
                val img = decode(a)
                assertEquals(200, img.width, "${style.id}: width")
                assertEquals(200, img.height, "${style.id}: height")
                val counts = HashMap<Int, Int>()
                for (y in 0 until 200 step 4) for (x in 0 until 200 step 4) counts.merge(rgb(img, x, y), 1, Int::plus)
                val top = counts.maxByOrNull { it.value }!!.key
                assertEquals(palette.ground, top, "${style.id}/${palette.id}: the ground is the most frequent colour (got ${top.toString(16)})")
                assertTrue(counts.size > 3, "${style.id}/${palette.id}: something was drawn")
            }
        }
    }

    @Test
    fun `the name stays legible in its zone on every style and palette`() {
        val dir = File(temp, "legible")
        val kit = buildKit(dir)
        for (style in KitArt.Style.entries) {
            for (palette in Palettes.ALL) {
                val img = renderTextless(kit, dir, knobs(style, palette), 128)
                val zone = KitArt.nameZone(probeCanvas(), style, Mark.Variant.STACKED, 128)
                var sum = 0.0
                var n = 0
                for (y in zone.y until zone.y + zone.h) for (x in zone.x until zone.x + zone.w) {
                    sum += luminance(rgb(img, x, y))
                    n++
                }
                val ratio = contrast(luminance(palette.ink), sum / n)
                assertTrue(ratio >= 4.5, "${style.id}/${palette.id}: ink over the zone reads at ${"%.1f".format(ratio)}:1, wants 4.5:1")
            }
        }
    }

    @Test
    fun `the mark sits bottom-right in the palette's two tones`() {
        val dir = File(temp, "marked")
        val kit = buildKit(dir)
        val palette = Palettes.VENT
        val size = 300
        for (style in KitArt.Style.entries) {
            val sym = decode(KitArt.png(kit, dir, knobs(style, palette, mark = Mark.Variant.SYMBOL), size = size))
            val (ox, oy) = KitArt.markOrigin(probeCanvas(), Mark.Variant.SYMBOL, size)
            val h = KitArt.markHeight(Mark.Variant.SYMBOL, size)
            val pads = Mark.symbol(ox, oy, h)
            val expected = listOf(palette.field1, palette.ink, palette.ink, palette.field1)
            pads.forEachIndexed { i, d ->
                assertEquals(expected[i], rgb(sym, d.cx.toInt(), d.cy.toInt()), "${style.id}: symbol pad $i at its centre")
            }
            assertTrue(pads.maxOf { it.cx + it.r } <= size - KitArt.margin(size) + 1, "${style.id}: the symbol ends at the right margin")
            for (variant in listOf(Mark.Variant.STACKED, Mark.Variant.LOCKUP)) {
                val img = decode(KitArt.png(kit, dir, knobs(style, palette, mark = variant), size = size))
                val (x, y) = KitArt.markOrigin(probeCanvas(), variant, size)
                var ink = 0
                var accent = 0
                for (yy in y.toInt() until size - KitArt.margin(size)) for (xx in x.toInt() until size - KitArt.margin(size)) {
                    when (rgb(img, xx, yy)) {
                        palette.ink -> ink++
                        palette.field1 -> accent++
                    }
                }
                assertTrue(ink > 20 && accent > 20, "${style.id}/${variant.id}: both tones in the mark box (ink $ink, accent $accent)")
            }
        }
    }

    @Test
    fun `an empty kit still gets a name and a mark`() {
        val dir = File(temp, "empty").apply { mkdirs() }
        val kit = Kit("Empty", emptyList())
        KitStore.save(kit, dir)
        val palette = Palettes.PHOSPHOR
        for (style in KitArt.Style.entries) {
            val size = 128
            val img = decode(KitArt.png(kit, dir, knobs(style, palette, mark = Mark.Variant.SYMBOL), size = size))
            val (ox, oy) = KitArt.markOrigin(probeCanvas(), Mark.Variant.SYMBOL, size)
            val pads = Mark.symbol(ox, oy, KitArt.markHeight(Mark.Variant.SYMBOL, size))
            assertEquals(palette.field1, rgb(img, pads[0].cx.toInt(), pads[0].cy.toInt()), "${style.id}: the mark is there")
            val zone = KitArt.nameZone(probeCanvas(), style, Mark.Variant.SYMBOL, size)
            var named = 0
            for (y in zone.y until zone.y + zone.h) for (x in zone.x until zone.x + zone.w) if (rgb(img, x, y) != palette.ground) named++
            assertTrue(named > 10, "${style.id}: the name was set in its zone ($named pixels)")
        }
    }

    @Test
    fun `a long name shrinks into its zone`() {
        val dir = File(temp, "longname")
        buildKit(dir)
        val kit = Kit("A Really Quite Long Kit Name Indeed 2026", KitStore.load(dir).pads)
        KitStore.save(kit, dir)
        val palette = Palettes.VENT
        val size = 128
        for (style in KitArt.Style.entries) {
            val img = decode(KitArt.png(kit, dir, knobs(style, palette, mark = Mark.Variant.LOCKUP), size = size))
            // The same tile with no name at all: the mark's own words stay, so the diff is the name alone.
            val bare = decode(KitArt.png(kit, dir, knobs(style, palette, mark = Mark.Variant.LOCKUP), size = size, label = ""))
            val zone = KitArt.nameZone(probeCanvas(), style, Mark.Variant.LOCKUP, size)
            // Pixels the name added must all lie inside the zone, plus one pixel of antialiasing.
            var outside = 0
            var inside = 0
            for (y in 0 until size) for (x in 0 until size) {
                if (rgb(img, x, y) == rgb(bare, x, y)) continue
                if (x in (zone.x - 1)..(zone.x + zone.w) && y in (zone.y - 1)..(zone.y + zone.h)) inside++ else outside++
            }
            assertTrue(inside > 10, "${style.id}: the name was set ($inside pixels)")
            assertEquals(0, outside, "${style.id}: name pixels outside the zone")
        }
    }

    @Test
    fun `bloom sets a two-word name on two lines, untrimmed`() {
        val dir = File(temp, "twolines")
        buildKit(dir)
        for (name in listOf("Vesper Bells", "Vesper_Bells", "Vesper-Bells")) {
        val kit = Kit(name, KitStore.load(dir).pads)
        KitStore.save(kit, dir)
        val palette = Palettes.VENT
        val size = 600
        val img = decode(KitArt.png(kit, dir, knobs(KitArt.Style.BLOOM, palette), size = size))
        val bare = decode(KitArt.png(kit, dir, knobs(KitArt.Style.BLOOM, palette), size = size, label = ""))
        val zone = KitArt.nameZone(probeCanvas(), KitArt.Style.BLOOM, Mark.Variant.STACKED, size)
        // Rows inside the zone that the name touched, as runs: two lines are two runs with a gap between.
        val rows = (zone.y until zone.y + zone.h).filter { y ->
            (zone.x until zone.x + zone.w).any { x -> rgb(img, x, y) != rgb(bare, x, y) }
        }
        var runs = 0
        var prev = -2
        for (y in rows) {
            if (y != prev + 1) runs++
            prev = y
        }
        assertEquals(2, runs, "$name: two lines of type (touched rows: ${rows.size})")
        // Untrimmed: the second line's ink spans most of the zone's width, which "VESPE…" on one line never does low in the hole.
        val lower = rows.filter { it > zone.y + zone.h / 2 }
        val litCols = (zone.x until zone.x + zone.w).filter { x -> lower.any { y -> rgb(img, x, y) != rgb(bare, x, y) } }
        assertTrue(litCols.isNotEmpty() && litCols.last() - litCols.first() > zone.w * 0.35, "$name: the second line is a word, not a dot")
        }
    }

    @Test
    fun `ridges and ribbon follow the seed's attack and field follows its envelope`() {
        val dir = File(temp, "family")
        val kit = buildKit(dir)
        for (style in listOf(KitArt.Style.RIDGES, KitArt.Style.FIELD, KitArt.Style.RIBBON)) {
            val a = KitArt.png(kit, dir, knobs(style, seed = 1), size = 200)
            val b = KitArt.png(kit, dir, knobs(style, seed = 3), size = 200)
            assertTrue(!a.contentEquals(b), "${style.id}: the seed pad draws the field")
        }
        // A silent seed: still a tile, still ground-dominant.
        val quiet = File(temp, "quiet").apply { mkdirs() }
        WavWriter.write(File(quiet, "A01_Silent_01.wav"), Snip(FloatArray(4_000), 1, 44_100))
        val silent = Kit("Hush", listOf(KitPad(slot = 1, sampleFile = "A01_Silent_01.wav")))
        KitStore.save(silent, quiet)
        for (style in listOf(KitArt.Style.RIDGES, KitArt.Style.FIELD, KitArt.Style.RIBBON)) {
            val img = decode(KitArt.png(silent, quiet, knobs(style), size = 200))
            assertEquals(Palettes.VENT.ground, rgb(img, 2, 2), "${style.id}: silence still grounds the tile")
        }
    }

    @Test
    fun `the redrawn four are no longer bars and boxes`() {
        val dir = File(temp, "redrawn")
        val kit = buildKit(dir)
        val palette = Palettes.VENT
        val size = 200
        fun tile(style: KitArt.Style) = decode(KitArt.png(kit, dir, knobs(style, palette), size = size))

        // GRID: a filled cell is a small bloom with a hole of ground at its centre, not a lit box.
        val grid = tile(KitArt.Style.GRID)
        val cell = KitArt.gridCell(size, slot = 1)
        assertEquals(palette.ground, rgb(grid, cell.x + cell.w / 2, cell.y + cell.h / 2), "the kick's cell has a hole")
        assertTrue(rgb(grid, cell.x + cell.w / 2, cell.y + cell.h / 5) != palette.ground, "and a petal above it")

        // SLICES: wedges radiate from a hole; a bar chart has neither the hole's ring nor the diagonal.
        val slices = tile(KitArt.Style.SLICES)
        val cx = size / 2
        val cy = Math.round(size * TileStyles.WHEEL_CY)
        assertEquals(palette.ground, rgb(slices, cx, cy), "the wheel's hub is open")
        val r = size * TileStyles.WHEEL_R * 0.55f
        val diagonal = (0 until 8).count { k ->
            val a = Math.PI / 4 + k * Math.PI / 2 / 8
            rgb(slices, (cx + r * Math.cos(a)).toInt(), (cy + r * Math.sin(a)).toInt()) != palette.ground
        }
        assertTrue(diagonal > 0, "something lies on the lower-right diagonal")

        // WAVEFORM: one flowing river — at the middle column the lit run is one contiguous span.
        val river = tile(KitArt.Style.WAVEFORM)
        fun litRows(x: Int) = (0 until Math.round(size * 0.78f)).filter { y -> rgb(river, x, y) != palette.ground }
        val mid = litRows(size / 2)
        assertTrue(mid.isNotEmpty() && mid.last() - mid.first() + 1 == mid.size, "one contiguous run at the middle column")
        val near = litRows(size / 2 - 1)
        assertTrue(kotlin.math.abs(near.size - mid.size) <= 3, "neighbouring columns flow (${near.size} vs ${mid.size})")

        // RINGS: each arc carries a glow — pixels of the class colour blended well into the ground, wider than the stroke.
        val rings = tile(KitArt.Style.RINGS)
        val glow = (0 until size).sumOf { y ->
            (0 until size).count { x ->
                val c = rgb(rings, x, y)
                c != palette.ground && TileStyles.distance(c, palette.ground) < 60
            }
        }
        assertTrue(glow > 200, "a faint glow surrounds the arcs ($glow pixels)")
    }

    @Test
    fun `a kit of empty WAVs still draws every style`() {
        // A capture killed the instant it started writes a WAV with no frames; it reads, and must not divide by zero.
        val dir = File(temp, "zeroframes").apply { mkdirs() }
        WavWriter.write(File(dir, "A01_Empty_01.wav"), Snip(FloatArray(0), 1, 44_100))
        WavWriter.write(File(dir, "A02_Empty_01.wav"), Snip(FloatArray(0), 1, 44_100))
        val kit = Kit(
            "Hollow",
            listOf(
                KitPad(slot = 1, sampleFile = "A01_Empty_01.wav", drumClass = DrumClass.KICK),
                KitPad(slot = 2, sampleFile = "A02_Empty_01.wav", drumClass = DrumClass.SNARE),
            ),
        )
        KitStore.save(kit, dir)
        assertEquals(emptyList(), TileSound.pads(kit, dir).map { it.slot }, "a pad with no frames has no sound to draw")
        for (style in KitArt.Style.entries) {
            val img = decode(KitArt.png(kit, dir, knobs(style), size = 128))
            assertEquals(Palettes.VENT.ground, rgb(img, 1, 1), "${style.id}: still a tile")
        }
    }

    @Test
    fun `drawing from pads already read lands the same bytes`() {
        // The phone's picker reads the kit's pads once and draws eight previews from them.
        val dir = File(temp, "preread")
        val kit = buildKit(dir)
        val pads = TileSound.pads(kit, dir)
        for (style in KitArt.Style.entries) {
            val k = knobs(style, seed = 2)
            val fromDisk = BufferedImage(160, 160, BufferedImage.TYPE_INT_RGB)
            val fromPads = BufferedImage(160, 160, BufferedImage.TYPE_INT_RGB)
            for ((img, read) in listOf(fromDisk to false, fromPads to true)) {
                val g = img.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (read) KitArt.draw(KitArt.AwtCanvas(g), kit, pads, k, 160, kit.name)
                else KitArt.draw(KitArt.AwtCanvas(g), kit, dir, k, 160, kit.name)
                g.dispose()
            }
            for (y in 0 until 160) for (x in 0 until 160) {
                assertEquals(rgb(fromDisk, x, y), rgb(fromPads, x, y), "${style.id}: pixel $x,$y")
            }
        }
    }

    @Test
    fun `the name is never set too small to read`() {
        // Every strip style under every mark variant, with a long name, at the export size: the
        // name's lit rows must span at least 3.5 percent of the tile, which is about four pixels
        // of cap height once the MPC browser scales the tile to 128.
        val dir = File(temp, "floor")
        buildKit(dir)
        val kit = Kit("DUST_BREAK_DELUXE_TAKE", KitStore.load(dir).pads)
        KitStore.save(kit, dir)
        val size = 600
        val strip = listOf(KitArt.Style.RIBBON, KitArt.Style.WAVEFORM, KitArt.Style.GRID, KitArt.Style.SLICES, KitArt.Style.RINGS)
        for (variant in Mark.Variant.entries) for (style in strip) {
            val img = decode(KitArt.png(kit, dir, knobs(style, mark = variant), size = size))
            val bare = decode(KitArt.png(kit, dir, knobs(style, mark = variant), size = size, label = ""))
            val rows = (0 until size).filter { y -> (0 until size).any { x -> rgb(img, x, y) != rgb(bare, x, y) } }
            val span = rows.last() - rows.first() + 1
            assertTrue(span >= size * 0.035, "${style.id}/${variant.id}: the name stands $span px tall, wants ${size * 0.035}")
        }
    }

    @Test
    fun `the name keeps a clear gap before the mark`() {
        // The strip styles share a row with the mark; a long name must stop a full margin short of it,
        // the same breathing room the tile keeps at its edges.
        val dir = File(temp, "gap")
        buildKit(dir)
        val kit = Kit("DUST_BREAK_DELUXE", KitStore.load(dir).pads)
        KitStore.save(kit, dir)
        val size = 600
        val strip = listOf(KitArt.Style.RIBBON, KitArt.Style.WAVEFORM, KitArt.Style.GRID, KitArt.Style.SLICES, KitArt.Style.RINGS)
        for (variant in Mark.Variant.entries) {
            val (markX, markY) = KitArt.markOrigin(probeCanvas(), variant, size)
            for (style in strip) {
                val img = decode(KitArt.png(kit, dir, knobs(style, mark = variant), size = size))
                val bare = decode(KitArt.png(kit, dir, knobs(style, mark = variant), size = size, label = ""))
                val zone = KitArt.nameZone(probeCanvas(), style, variant, size)
                // A name on its own row above the mark shares no row with it: no gap to keep.
                if (zone.y + zone.h <= markY) continue
                val right = (0 until size).lastOrNull { x ->
                    (zone.y until zone.y + zone.h).any { y -> rgb(img, x, y) != rgb(bare, x, y) }
                } ?: continue
                assertTrue(right <= markX - KitArt.margin(size), "${style.id}/${variant.id}: the name ends at $right, the mark starts at $markX")
            }
        }
    }

    @Test
    fun `the seed pad changes the field and the rings seed changes only rings`() {
        val dir = File(temp, "seeded")
        val kit = buildKit(dir)
        val bloomA = KitArt.png(kit, dir, knobs(KitArt.Style.BLOOM, seed = 1), size = 200)
        val bloomB = KitArt.png(kit, dir, knobs(KitArt.Style.BLOOM, seed = 3), size = 200)
        assertTrue(!bloomA.contentEquals(bloomB), "the seed pad draws the bloom")
        val bloom3 = KitArt.png(kit, dir, knobs(KitArt.Style.BLOOM), size = 200, ringsSeed = 3)
        val bloom4 = KitArt.png(kit, dir, knobs(KitArt.Style.BLOOM), size = 200, ringsSeed = 4)
        assertTrue(bloom3.contentEquals(bloom4), "the rings dice do not touch the bloom")
        val rings3 = KitArt.png(kit, dir, knobs(KitArt.Style.RINGS), size = 200, ringsSeed = 3)
        val rings4 = KitArt.png(kit, dir, knobs(KitArt.Style.RINGS), size = 200, ringsSeed = 4)
        assertTrue(!rings3.contentEquals(rings4), "rings answer the dice")
    }

    @Test
    fun `every starter kit renders every style without error`() {
        for (starter in StarterKits.ALL) {
            val dir = File(temp, "starter-${starter.id}")
            val kit = starter.render("Starter ${starter.id}", dir, seed = 1)
            // Law: a banked recipe (version 3, `layers` present) lists exactly one
            // layer per velocity layer of its pad. It rides this loop so no starter
            // is rendered twice. Vacuous until a starter is banked.
            for (pad in kit.pads) {
                val r = Breed.recipeOf(pad) ?: continue
                if (r.isBanked) {
                    assertEquals(pad.velocityLayers.size, r.layers!!.size, "${starter.id} slot ${pad.slot}")
                }
            }
            for (style in KitArt.Style.entries) {
                val png = KitArt.png(kit, dir, knobs(style), size = 128)
                assertTrue(png.isNotEmpty(), "${starter.id}/${style.id}")
            }
        }
    }
}
