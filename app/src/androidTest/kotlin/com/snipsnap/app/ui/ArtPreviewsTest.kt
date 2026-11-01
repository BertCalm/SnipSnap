package com.snipsnap.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.shell.ArtKnobs
import com.snipsnap.shell.KitArt
import com.snipsnap.shell.Mark
import com.snipsnap.shell.Palettes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** The picker's preview cache: one render per knob set, and a render that throws fails one cell, not the screen. */
@RunWith(AndroidJUnit4::class)
class ArtPreviewsTest {
    private val knobs = ArtKnobs(KitArt.Style.BLOOM, Palettes.VENT, null, Mark.Variant.STACKED)

    @Test
    fun aRenderThatThrowsIsAnEmptyCell() = runBlocking {
        val previews = ArtPreviews { error("a kit that cannot be drawn") }
        assertNull(previews.bitmap(knobs))
    }

    @Test
    fun eachKnobSetRendersOnce() = runBlocking {
        var renders = 0
        val image = ImageBitmap(4, 4)
        val previews = ArtPreviews { renders++; image }
        assertSame(image, previews.bitmap(knobs))
        assertSame(image, previews.bitmap(knobs))
        assertSame(image, previews.cached(knobs))
        assertEquals(1, renders)
        previews.bitmap(knobs.copy(palette = Palettes.RISO))
        assertEquals(2, renders)
    }
}
