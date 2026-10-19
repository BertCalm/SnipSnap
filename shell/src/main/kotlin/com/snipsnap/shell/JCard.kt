package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPreview
import com.snipsnap.mpc3.Mpc3Clip
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * The J-card — every kit gets its cassette insert. Everything a card
 * needs is already tracked: the art (the kit's own waveform), the key,
 * the tempo, the classes and sources per pad, the groove, and the wear
 * ledger's mileage. One fold-ready PNG, three panels stacked the way a
 * card folds:
 *
 * - **front** — the waveform in class colours, the kit's name;
 * - **spine** — name, key, tempo, mileage on one strip;
 * - **back** — the pad list (class chip, pad, name, source) in up to
 *   two columns of sixteen, and the groove as a 16-step notation row.
 *
 * KitArt-family: same LCD surface, same pixel type, deterministic —
 * same kit, same bytes.
 *
 * [draw] is the card itself, expressed purely against [ArtCanvas] —
 * nothing it calls touches `java.awt`/`javax.imageio`. Those enter only
 * through [render]/[png]/[awtPng] below, the AWT adapter `:cli` and the
 * tests use. `:app` calls [draw] with its own `android.graphics` canvas
 * (`AndroidJCard`), because `java.awt` does not exist on Android at any
 * API level: `Inserts.write` reaching [png] from the phone crashed WRITE
 * KIT with `NoClassDefFoundError` on `BufferedImage`.
 */
object JCard {

    const val DEFAULT_WIDTH = 1200

    /** Panel heights as fractions of the card width — cassette proportions. */
    private const val FRONT = 0.64f
    private const val SPINE = 0.1475f
    private const val BACK = 0.64f

    /** The card's pixel height for a given [width] — three panels stacked. */
    fun heightFor(width: Int): Int {
        require(width in 300..2400) { "width wants 300..2400, got $width" }
        return (width * FRONT).toInt() + (width * SPINE).toInt() + (width * BACK).toInt()
    }

    /**
     * The platform-agnostic half: paint the whole card onto [canvas], which
     * must be [width] by [heightFor] pixels. Public, not internal — `:app`
     * is a separate module and implements [ArtCanvas] against
     * `android.graphics`.
     */
    fun draw(
        canvas: ArtCanvas,
        kit: Kit,
        kitDir: File,
        scheme: Scheme = Schemes.DEFAULT,
        width: Int = DEFAULT_WIDTH,
        /** The label's catalog number (`DF-001`), worn on the spine when set. */
        catalog: String? = null,
    ) {
        val height = heightFor(width)
        val frontH = (width * FRONT).toInt()
        val spineH = (width * SPINE).toInt()
        val backH = (width * BACK).toInt()
        canvas.fillRect(0, 0, width, height, scheme.lcd)

        val m = (width * 0.05f).toInt()
        front(canvas, kit, kitDir, scheme, width, frontH, m)
        spine(canvas, kit, scheme, width, frontH, spineH, m, catalog)
        back(canvas, kit, kitDir, scheme, width, frontH + spineH, backH, m)

        // Fold hairlines where the card creases.
        val hairline = max(1, width / 400)
        canvas.fillRect(0, frontH - hairline / 2, width, hairline, scheme.amber)
        canvas.fillRect(0, frontH + spineH - hairline / 2, width, hairline, scheme.amber)
    }

    // ---- the AWT adapter — the only place in this file java.awt appears ----

    fun render(
        kit: Kit,
        kitDir: File,
        scheme: Scheme = Schemes.DEFAULT,
        width: Int = DEFAULT_WIDTH,
        /** The label's catalog number (`DF-001`), worn on the spine when set. */
        catalog: String? = null,
    ): BufferedImage {
        val img = BufferedImage(width, heightFor(width), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            draw(KitArt.AwtCanvas(g), kit, kitDir, scheme, width, catalog)
        } finally {
            g.dispose()
        }
        return img
    }

    fun png(
        kit: Kit,
        kitDir: File,
        scheme: Scheme = Schemes.DEFAULT,
        width: Int = DEFAULT_WIDTH,
        catalog: String? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(render(kit, kitDir, scheme, width, catalog), "png", out)
        return out.toByteArray()
    }

    /**
     * [png] in the shape `Inserts.write` takes its renderer in — the
     * desktop default, so `Inserts` itself never names the AWT path.
     */
    fun awtPng(kit: Kit, kitDir: File, catalog: String?): ByteArray = png(kit, kitDir, catalog = catalog)

    // ---- panels ------------------------------------------------------------

    private fun front(c: ArtCanvas, kit: Kit, kitDir: File, scheme: Scheme, w: Int, h: Int, m: Int) {
        val nameBand = (h * 0.22f).toInt()
        KitArt.waveform(c, KitArt.Box(m, m, w - 2 * m, h - 2 * m - nameBand), kit, kitDir)
        val bandTop = h - m - nameBand + (nameBand * 0.1f).toInt()
        PixelType.draw(
            c, kit.name.uppercase(), w / 2, bandTop, (nameBand * 0.55f).toInt(), scheme.lcdInk,
            centered = true, maxWidthPx = w - 2 * m,
        )
        PixelType.draw(
            c, keyTempoLine(kit), w / 2, bandTop + (nameBand * 0.62f).toInt(), (nameBand * 0.3f).toInt(),
            scheme.amber, centered = true, maxWidthPx = w - 2 * m,
        )
    }

    private fun spine(c: ArtCanvas, kit: Kit, scheme: Scheme, w: Int, top: Int, h: Int, m: Int, catalog: String?) {
        // The catalog number leads the spine, the way real labels press it.
        val line = (catalog?.let { "$it  " } ?: "") +
            "${kit.name.uppercase()}  ${keyTempoLine(kit)}  ${mileageLine(kit)}"
        val px = (h * 0.4f).toInt()
        PixelType.draw(
            c, line, w / 2, top + (h - min(px, PixelType.ROWS * (px / PixelType.ROWS))) / 2,
            px, scheme.lcdInk, centered = true, maxWidthPx = w - 2 * m,
        )
    }

    private fun back(c: ArtCanvas, kit: Kit, kitDir: File, scheme: Scheme, w: Int, top: Int, h: Int, m: Int) {
        val header = (h * 0.09f).toInt()
        PixelType.draw(
            c, "SIDE A - ${kit.name.uppercase()}", m, top + m, (header * 0.8f).toInt(),
            scheme.amber, maxWidthPx = w - 2 * m,
        )

        // The pad list: up to two columns of sixteen — a full 32-pad kit fits.
        val pads = kit.pads.sortedBy { it.slot }.take(32)
        val listTop = top + m + header
        val grooveBand = (h * 0.16f).toInt()
        val listH = h - m - header - grooveBand - m
        val columns = if (pads.size > 16) 2 else 1
        val colW = (w - 2 * m - (columns - 1) * m) / columns
        val rows = if (columns == 2) 16 else max(1, pads.size)
        val rowH = max(4, listH / max(1, rows))
        val textPx = (rowH * 0.62f).toInt().coerceAtLeast(PixelType.ROWS)
        pads.forEachIndexed { i, pad ->
            val col = i / 16
            val row = i % 16
            val x = m + col * (colW + m)
            val y = listTop + row * rowH
            val chip = (rowH * 0.5f).toInt().coerceAtLeast(3)
            c.fillRect(x, y + (rowH - chip) / 2, chip, chip, Schemes.classColor(pad.drumClass))
            val bank = 'A' + (pad.slot - 1) / 16
            val num = (pad.slot - 1) % 16 + 1
            val source = pad.source["title"] ?: pad.source["app"]
            val text = buildString {
                append("%s%02d %s".format(java.util.Locale.ROOT, bank, num, pad.displayName.uppercase()))
                if (source != null) append("  ${source.uppercase()}")
            }
            PixelType.draw(
                c, text, x + chip + chip / 2, y + (rowH - textPx) / 2, textPx,
                scheme.lcdInk, maxWidthPx = colW - chip - chip / 2,
            )
        }

        // The groove as 16-step notation: one square per 16th of bar one,
        // lit where the pattern hits, brightness riding velocity.
        val clip = GrooveStore.load(kitDir).firstOrNull()
            ?: if (kit.pads.isNotEmpty()) KitPreview.defaultPattern(kit) else null
        val grooveTop = top + h - m - grooveBand
        PixelType.draw(
            c, "GROOVE ${clip?.name?.uppercase() ?: "NONE"}", m, grooveTop, (grooveBand * 0.28f).toInt(),
            scheme.amber, maxWidthPx = w - 2 * m,
        )
        if (clip != null) {
            val steps = stepVelocities(clip)
            val stripTop = grooveTop + (grooveBand * 0.4f).toInt()
            val stripH = (grooveBand * 0.45f).toInt()
            val gap = max(1, (w - 2 * m) / 120)
            val stepW = (w - 2 * m - 15 * gap) / 16
            for (s in 0 until 16) {
                val x = m + s * (stepW + gap)
                val v = steps[s]
                val color = if (v > 0f) {
                    blend(scheme.lcd, scheme.lcdInk, 0.35f + 0.65f * v)
                } else {
                    blend(scheme.lcd, scheme.lcdInk, 0.12f)
                }
                c.fillRect(x, stripTop, stepW, stripH, color)
            }
        }
    }

    // ---- the lines ---------------------------------------------------------

    private fun keyTempoLine(kit: Kit): String {
        val parts = mutableListOf<String>()
        kit.key?.let { parts += it.label.uppercase() }
        kit.tempoBpm?.let { parts += "%.0f BPM".format(java.util.Locale.ROOT, it) }
        return if (parts.isEmpty()) "UNMAPPED TAPE" else parts.joinToString(" - ")
    }

    private fun mileageLine(kit: Kit): String {
        val wear = kit.wear ?: return "NEW TAPE"
        return "%.0f MILES".format(java.util.Locale.ROOT, wear.mileage)
    }

    /** Max velocity per 16th, the whole pattern folded to one bar — the notation row's data. */
    internal fun stepVelocities(clip: Mpc3Clip): FloatArray {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val steps = FloatArray(16)
        for (n in clip.notes) {
            val pos = (((n.timePulses + s16 / 2) / s16) % 16).toInt()
            if (n.velocity > steps[pos]) steps[pos] = n.velocity
        }
        return steps
    }

    /** Packed 0xRRGGBB blend, truncating per channel as the AWT-era `Color` version did. */
    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t).toInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
