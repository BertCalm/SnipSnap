package com.snipsnap.app

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.ArtFont
import com.snipsnap.shell.ArtKnobs
import com.snipsnap.shell.KitArt
import com.snipsnap.shell.Mark
import com.snipsnap.shell.Palettes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Every tile style through the phone's own canvas: the bytes the EXPORT screen writes. */
@RunWith(AndroidJUnit4::class)
class TileArtDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.filesDir, "tile-test")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun kit(): Kit {
        dir.mkdirs()
        fun tone(n: Int, hz: Double) = Snip(FloatArray(n) { i -> (0.5 * Math.sin(2.0 * Math.PI * hz * i / 44_100)).toFloat() }, 1, 44_100)
        WavWriter.write(File(dir, "A01_Kick_01.wav"), tone(8_000, 90.0))
        WavWriter.write(File(dir, "A02_Snare_01.wav"), tone(5_000, 400.0))
        WavWriter.write(File(dir, "A03_HatClosed_01.wav"), tone(2_000, 4_000.0))
        val kit = Kit(
            "Phone Tiles",
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
    fun everyFaceIsPacked() {
        for (font in ArtFont.entries) context.assets.open(font.resourcePath).use { assertTrue(font.name, it.read() >= 0) }
    }

    @Test
    fun everyStyleRendersDeterministicBytesOnThePhone() {
        val kit = kit()
        for (style in KitArt.Style.entries) {
            val knobs = ArtKnobs(style, Palettes.VENT, null, Mark.Variant.STACKED)
            val a = AndroidKitArt.png(kit, dir, knobs, size = 128, assets = context.assets)
            val b = AndroidKitArt.png(kit, dir, knobs, size = 128, assets = context.assets)
            assertTrue("${style.id}: same inputs, same bytes", a.contentEquals(b))
            val bitmap = BitmapFactory.decodeByteArray(a, 0, a.size)
            assertEquals(128, bitmap.width)
            assertEquals(128, bitmap.height)
            assertEquals("${style.id}: the ground", Palettes.VENT.ground, bitmap.getPixel(1, 1) and 0xFFFFFF)
            bitmap.recycle()
        }
    }
}
