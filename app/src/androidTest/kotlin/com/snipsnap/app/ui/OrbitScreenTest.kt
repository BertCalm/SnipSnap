package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.loop.OrbitPresets
import com.snipsnap.loop.OrbitStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * ORBIT on a device, over a two-pad kit with no rings saved yet: the rings
 * it builds from the kit, an edit that saves them, PLAY and STOP, and the
 * two doors to the kit's grooves (CLIP TO KIT writing one, GROOVE TO RINGS
 * reading it back), each read back off the disk. What only an ear can
 * judge, the rings drifting against each other, stays with the player.
 *
 * Buttons are pressed through their click actions on the UI thread, as
 * the OVEN, SNAP and CHOP suites do. ORBIT runs a frame loop while it
 * plays, which [ComposeScreenTest]'s frozen clock is there for.
 */
@RunWith(AndroidJUnit4::class)
class OrbitScreenTest : ComposeScreenTest() {

    private val folders = mutableListOf<File>()
    // Written by the screen on the main thread, read by the test's own.
    private val toasts = CopyOnWriteArrayList<String>()
    private val groovesWritten = AtomicInteger(0)

    @After
    fun cleanUp() {
        folders.forEach { it.deleteRecursively() }
    }

    /** A kick on A01 and a snare on A02, on a shelf of their own, with a tempo and no orbits.json. */
    private fun kit(): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val shelf = File(cache, "orbit-test-shelf-${System.nanoTime()}").apply { mkdirs() }
        folders += shelf
        val dir = File(shelf, "ORBIT UI TEST").apply { mkdirs() }
        WavWriter.write(File(dir, "kick.wav"), DrumSynth.kick())
        WavWriter.write(File(dir, "snare.wav"), DrumSynth.snare())
        val kit = Kit(
            name = "ORBIT UI TEST",
            pads = listOf(
                KitPad(slot = 1, sampleFile = "kick.wav", drumClass = DrumClass.KICK),
                KitPad(slot = 2, sampleFile = "snare.wav", drumClass = DrumClass.SNARE),
            ),
            tempoBpm = 92f,
        )
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    /** The rings ORBIT builds for [entry] when none are saved: what the screen should open on. */
    private fun builtRings(entry: KitShelf.Entry): Int =
        OrbitPresets.fromKit(entry.dir.name, entry.kit, 92f, 48_000).orbits.size

    private fun show(entry: KitShelf.Entry) {
        setPhoneContent {
            OrbitScreen(
                entry = entry,
                kitsRoot = entry.dir.parentFile!!,
                onBack = {},
                onToast = { toasts += it },
                onGrooveWritten = { groovesWritten.incrementAndGet() },
            )
        }
        // Open: the set read (here, built from the kit) and its bank
        // prepared, which is when PLAY lights.
        waitFor("the rings", timeoutMillis = 60_000) { present("OPEN THE SET") }
        waitFor("PLAY to light", timeoutMillis = 60_000) { runCatching { button("PLAY").assertIsEnabled() }.isSuccess }
    }

    private fun present(label: String): Boolean =
        compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()

    private fun invokeClick(label: String) {
        val node = compose.onNode(hasContentDescription(label) and hasClickAction()).fetchSemanticsNode("no button \"$label\"")
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action ?: error("no click for $label")
        var clicked = false
        compose.runOnUiThread { clicked = action.invoke() }
        assertTrue("$label refused its click", clicked)
        pump()
    }

    private fun ringsOnDisk(entry: KitShelf.Entry): Int? =
        runCatching { if (OrbitStore.exists(entry.dir)) OrbitStore.load(entry.dir).orbits.size else null }.getOrNull()

    @Test
    fun a_kit_with_no_rings_opens_on_rings_built_from_its_pads() {
        val entry = kit()
        show(entry)
        assertTrue("a kick and a snare make at least a ring each", builtRings(entry) >= 2)
        // Opening reads; it does not write.
        assertEquals(null, ringsOnDisk(entry))
        assertTrue("nothing went wrong opening it: $toasts", toasts.isEmpty())
    }

    @Test
    fun dup_copies_the_picked_ring_and_saves_the_set() {
        val entry = kit()
        show(entry)
        val before = builtRings(entry)
        invokeClick("COPY THIS RING BESIDE IT")
        waitFor("the set saved with one ring more", timeoutMillis = 30_000) { ringsOnDisk(entry) == before + 1 }
        button("UNDO THE LAST EDIT").assertIsEnabled()
    }

    @Test
    fun play_starts_the_rings_and_stop_stops_them() {
        show(kit())
        invokeClick("PLAY")
        waitFor("STOP in PLAY's place", timeoutMillis = 30_000) { present("STOP") }
        pump(1_000)
        invokeClick("STOP")
        waitFor("PLAY back") { present("PLAY") }
        assertTrue("nothing went wrong playing: $toasts", toasts.isEmpty())
    }

    @Test
    fun clip_to_kit_writes_the_rings_into_the_kits_grooves() {
        val entry = kit()
        show(entry)
        invokeClick("OUT ▸")
        invokeClick("CLIP TO KIT")
        waitFor("the groove written", timeoutMillis = 30_000) { groovesWritten.get() == 1 }
        assertTrue("a clip in groove.json", GrooveStore.load(entry.dir).isNotEmpty())
    }

    @Test
    fun groove_to_rings_reads_the_clip_back_as_more_rings() {
        val entry = kit()
        show(entry)
        val before = builtRings(entry)
        invokeClick("OUT ▸")
        invokeClick("CLIP TO KIT")
        waitFor("the groove written", timeoutMillis = 30_000) { groovesWritten.get() == 1 }
        invokeClick("OUT ▸")
        invokeClick("GROOVE TO RINGS")
        waitFor("the groove's rings saved beside the first", timeoutMillis = 30_000) { (ringsOnDisk(entry) ?: 0) > before }
        assertTrue("the screen said what it read: $toasts", toasts.any { "IS ON THE RINGS" in it || "RING" in it })
    }
}
