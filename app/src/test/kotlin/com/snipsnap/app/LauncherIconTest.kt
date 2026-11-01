package com.snipsnap.app

import com.snipsnap.shell.Mark
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The launcher icon and the tile icon are vector drawables of the mark's
 * own geometry; this pins them to [Mark.symbol] so the icon and the drawn
 * mark cannot drift apart. Plain JVM: it reads the resource XML as text.
 */
class LauncherIconTest {

    /** The working directory of an Android unit test is the module directory. */
    private val res = File("src/main/res")

    private fun paths(resFile: String): List<List<Pair<Float, Float>>> {
        val xml = File(res, resFile).readText()
        return Regex("""android:pathData="([^"]+)"""").findAll(xml).map { m ->
            val d = m.groupValues[1].trim()
            assertTrue(d.startsWith("M") && d.endsWith("Z"), "a closed path: $d")
            Regex("""[ML]\s*([-\d.]+),([-\d.]+)""").findAll(d).map { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }.toList()
        }.toList()
    }

    private fun points(diamonds: List<Mark.Diamond>) =
        diamonds.map { d -> listOf(d.cx to d.cy - d.r, d.cx + d.r to d.cy, d.cx to d.cy + d.r, d.cx - d.r to d.cy) }

    private fun fills(resFile: String): List<String> =
        Regex("""android:fillColor="([^"]+)"""").findAll(File(res, resFile).readText()).map { it.groupValues[1] }.toList()

    private fun hex(rgb: Int) = "#%06X".format(rgb)

    @Test
    fun `the foreground is the mark's own geometry`() {
        assertEquals(points(Mark.symbol(30f, 30f, 48f)), paths("drawable/ic_launcher_foreground.xml"))
        val (ink, accent) = Mark.tones(com.snipsnap.shell.Schemes.DEFAULT)
        assertEquals(listOf(hex(accent), hex(ink), hex(ink), hex(accent)), fills("drawable/ic_launcher_foreground.xml"), "accent TL and BR, ink TR and BL")
    }

    @Test
    fun `the background is the default scheme's LCD`() {
        val xml = File(res, "values/ic_launcher_background.xml").readText()
        assertTrue(hex(com.snipsnap.shell.Schemes.DEFAULT.lcd) in xml, xml)
    }

    @Test
    fun `the tile icon is the mark at twenty dp`() {
        assertEquals(points(Mark.symbol(2f, 2f, 20f)), paths("drawable/ic_mark_symbol.xml"))
        assertEquals(List(4) { "#FFFFFF" }, fills("drawable/ic_mark_symbol.xml"), "quick-settings tints the tile icon")
    }

    @Test
    fun `the manifest points at the adaptive icon`() {
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue("android:icon=\"@mipmap/ic_launcher\"" in m && "android:roundIcon=\"@mipmap/ic_launcher_round\"" in m)
        assertTrue("android:icon=\"@drawable/ic_mark_symbol\"" in m, "the tile wears the symbol")
        assertTrue("@android:drawable/ic_btn_speak_now" !in m, "no stock glyph left")
        for (f in listOf("mipmap-anydpi-v26/ic_launcher.xml", "mipmap-anydpi-v26/ic_launcher_round.xml")) {
            val xml = File(res, f).readText()
            assertTrue("@drawable/ic_launcher_foreground" in xml && "@color/ic_launcher_background" in xml, f)
        }
    }
}
