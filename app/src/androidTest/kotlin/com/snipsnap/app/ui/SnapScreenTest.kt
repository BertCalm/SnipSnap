package com.snipsnap.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.PadBanks
import com.snipsnap.synth.Photo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * SNAP on a device, from the photo on: what a picture lights, and each of
 * the four ways it lands on a pad (the line itself, FIELD's CLOUD,
 * SPECTRUM, a TELEPHONE generation) read back off the kit on disk.
 *
 * The camera is the one thing a test cannot press, so the screen gets a
 * registry of its own ([CameraRegistry]) that answers TAKE PHOTO with a
 * picture at once, the bitmap `TakePicturePreview` would have handed back.
 * The picture is a sine across its width, so HORIZON reads a line that is
 * not flat. Everything after the camera is the screen's own: the reading,
 * the line, the render loop, the build and the write.
 *
 * Buttons are pressed through their click actions, on the UI thread, as
 * the OVEN suite does: the button rows sit in a scrolling column, and a
 * scroll on the frozen clock is the hazard [ComposeScreenTest] names.
 */
@RunWith(AndroidJUnit4::class)
class SnapScreenTest : ComposeScreenTest() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val folders = mutableListOf<File>()
    private val toasts = mutableListOf<String>()
    private val updates = mutableListOf<Kit>()
    private val builtKits = mutableListOf<Photo>()
    private val builtChords = mutableListOf<Photo>()

    @After
    fun cleanUp() {
        appScope.cancel()
        folders.forEach { it.deleteRecursively() }
    }

    /**
     * Answers every launch with [picture] at once, as a camera app returning
     * would, and counts the launches.
     */
    private class CameraRegistry(private val picture: Bitmap) : ActivityResultRegistry() {
        var launches = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches++
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, picture as O)
        }
    }

    /** A 320x240 picture whose brightness is one sine period across its width. */
    private fun sinePicture(): Bitmap {
        val w = 320
        val h = 240
        val pixels = IntArray(w * h) { i ->
            val v = (255 * (0.5 + 0.45 * sin(2.0 * PI * (i % w) / w))).roundToInt()
            Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private val camera = CameraRegistry(sinePicture())

    /** An empty kit, saved where KitBuilderModel.open can read and write it. */
    private fun emptyKit(): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "snap-test-kit-${System.nanoTime()}").apply { mkdirs() }
        folders += dir
        val kit = Kit("SNAP UI TEST", emptyList())
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    private fun show(entry: KitShelf.Entry?) {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = camera
        }
        setPhoneContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                SnapScreen(
                    entry = entry,
                    busy = false,
                    onToast = { toasts += it },
                    onKitUpdated = { updates += it },
                    onBuildKit = { builtKits += it },
                    onBuildPathRing = { _, _ -> },
                    onBuildChord = { builtChords += it },
                    onFieldPrinted = {},
                    appScope = appScope,
                )
            }
        }
    }

    private fun invokeClick(label: String) {
        val node = compose.onNode(hasContentDescription(label) and hasClickAction()).fetchSemanticsNode("no button \"$label\"")
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action ?: error("no click for $label")
        var clicked = false
        compose.runOnUiThread { clicked = action.invoke() }
        assertTrue("$label refused its click", clicked)
        pump()
    }

    private fun showing(text: String): Boolean =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun present(label: String): Boolean =
        compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()

    /** TAKE PHOTO, and the wait for its reading and line to land. */
    private fun takePhoto() {
        invokeClick("TAKE PHOTO")
        assertEquals(1, camera.launches)
        waitFor("the photo's reading") { showing("LINE HORIZON") }
    }

    private fun padOnDisk(entry: KitShelf.Entry, slot: Int): KitPad? =
        runCatching { KitStore.load(entry.dir).pad(slot) }.getOrNull()

    private fun assertLandedAudio(entry: KitShelf.Entry, pad: KitPad) {
        assertTrue("the pad's audio is on disk", File(entry.dir, pad.sampleFile).length() > 44)
        assertTrue("the screen heard about the kit it wrote", updates.any { it.pad(1) != null })
    }

    @Test
    fun before_a_photo_only_the_camera_doors_are_open() {
        show(entry = emptyKit())
        assertTrue(showing("NO PHOTO"))
        button("TAKE PHOTO").assertIsEnabled()
        button("LIVE CAMERA").assertIsEnabled()
        for (needsAPhoto in listOf("AS SHOT", "SEND TO PAD", "PHOTO FIELD", "CLOUD TO PAD", "PHOTO KIT", "SPECTRUM TO PAD", "PHOTO CHORD", "TELEPHONE CHAIN")) {
            button(needsAPhoto).assertIsNotEnabled()
        }
    }

    @Test
    fun a_photo_is_read_into_a_line_and_lights_what_it_feeds() {
        show(entry = null)
        takePhoto()
        waitFor("AS SHOT to light") { runCatching { button("AS SHOT").assertIsEnabled() }.isSuccess }
        button("PHOTO FIELD").assertIsEnabled()
        button("PHOTO KIT").assertIsEnabled()
        button("PHOTO CHORD").assertIsEnabled()
        button("TELEPHONE CHAIN").assertIsEnabled()
        // With no kit open there is no pad to land on.
        button("SEND TO PAD").assertIsNotEnabled()
        button("SPECTRUM TO PAD").assertIsNotEnabled()
        assertTrue("a sine across the picture is a line, not a flat one: $toasts", toasts.isEmpty())
    }

    @Test
    fun kit_and_chord_hand_the_photo_to_the_app() {
        show(entry = null)
        takePhoto()
        invokeClick("PHOTO KIT")
        invokeClick("PHOTO CHORD")
        assertEquals(1, builtKits.size)
        assertEquals(1, builtChords.size)
        assertTrue("the photo handed on is the one taken", builtKits.single() === builtChords.single())
    }

    @Test
    fun send_to_pad_lands_the_line_on_the_picked_pad() {
        val entry = emptyKit()
        show(entry)
        takePhoto()
        waitFor("SEND TO PAD to light") { runCatching { button("SEND TO PAD").assertIsEnabled() }.isSuccess }
        invokeClick("SEND TO PAD")
        invokeClick("${PadBanks.tag(1)}, EMPTY")
        waitFor("the line on ${PadBanks.tag(1)}", timeoutMillis = 60_000) { padOnDisk(entry, 1)?.recipe != null }
        val pad = requireNotNull(padOnDisk(entry, 1))
        assertEquals("Snap Horizon", pad.displayName)
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertLandedAudio(entry, pad)
        waitFor("the chooser to close") { !present("CANCEL") }
    }

    @Test
    fun field_builds_over_the_picture_and_its_cloud_lands_on_a_pad() {
        val entry = emptyKit()
        show(entry)
        takePhoto()
        // CLOUD needs FIELD's grains, so it stays off until a field is built.
        button("CLOUD TO PAD").assertIsNotEnabled()
        invokeClick("PHOTO FIELD")
        waitFor("the field over the picture", timeoutMillis = 60_000) { present("◄ SNAP") }
        assertTrue(showing("PHOTO FIELD"))
        invokeClick("◄ SNAP")
        waitFor("back on SNAP") { !present("◄ SNAP") }
        waitFor("CLOUD to light") { runCatching { button("CLOUD TO PAD").assertIsEnabled() }.isSuccess }
        invokeClick("CLOUD TO PAD")
        invokeClick("${PadBanks.tag(1)}, EMPTY")
        waitFor("the cloud on ${PadBanks.tag(1)}", timeoutMillis = 60_000) { padOnDisk(entry, 1) != null }
        assertLandedAudio(entry, requireNotNull(padOnDisk(entry, 1)))
    }

    @Test
    fun spectrum_lands_the_picture_read_as_a_spectrogram() {
        val entry = emptyKit()
        show(entry)
        takePhoto()
        invokeClick("SPECTRUM TO PAD")
        invokeClick("${PadBanks.tag(1)}, EMPTY")
        waitFor("the spectrum on ${PadBanks.tag(1)}", timeoutMillis = 60_000) { padOnDisk(entry, 1) != null }
        val pad = requireNotNull(padOnDisk(entry, 1))
        // A texture, not a patch: no recipe to re-render it from.
        assertNull(pad.recipe)
        assertLandedAudio(entry, pad)
    }

    @Test
    fun telephone_whispers_the_picture_down_and_a_generation_lands_on_a_pad() {
        val entry = emptyKit()
        show(entry)
        takePhoto()
        invokeClick("TELEPHONE CHAIN")
        waitFor("the generations", timeoutMillis = 120_000) { present("GENERATION 1") }
        assertTrue(showing("TELEPHONE — TAP ONE TO HEAR IT"))
        invokeClick("GENERATION 2")
        invokeClick("SEND GENERATION TO PAD")
        invokeClick("${PadBanks.tag(1)}, EMPTY")
        waitFor("the generation on ${PadBanks.tag(1)}", timeoutMillis = 60_000) { padOnDisk(entry, 1) != null }
        val pad = requireNotNull(padOnDisk(entry, 1))
        assertLandedAudio(entry, pad)
    }
}
