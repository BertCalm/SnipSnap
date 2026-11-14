package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
import com.snipsnap.loop.OrbitSpan
import com.snipsnap.loop.OrbitStore
import com.snipsnap.shell.OrbitPanels
import com.snipsnap.synth.AquariumPerformanceSettings
import com.snipsnap.synth.AquariumRecipe
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

    @Test fun the_set_routes_saved_kit_FX_for_play_and_keeps_its_bounce_tail_without_rewriting_sources() {
        val original = kit()
        val sound = AquariumPerformanceSettings(AquariumRecipe(mapOf("aqua_tide_mix" to 1f)), true, .125f)
        val entry = original.copy(kit = original.kit.copy(aquariumPerformance = sound.toJsonValue()))
        val metadata = KitStore.save(entry.kit, entry.dir)
        val metadataBytes = metadata.readBytes()
        val wave = File(entry.dir, "kick.wav").readBytes()
        show(entry)
        invokeClick("OPEN THE SET")
        val toggle = compose.onNodeWithTag("orbit-aquarium-route")
        fun route(on: Boolean) {
            toggle.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            waitFor("ORBIT route $on saved, drawn and ready") {
                runCatching { OrbitStore.load(entry.dir).aquariumFromKit }.getOrNull() == on &&
                    toggle.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == on &&
                    toggle.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
            }
        }
        route(true)
        invokeClick("Lengthen ORBIT FX bounce tail")
        waitFor("the bounce tail saved") { runCatching { OrbitStore.load(entry.dir).aquariumTailSeconds }.getOrNull() == 5f }
        waitFor("PLAY ready") { runCatching { button("PLAY").assertIsEnabled() }.isSuccess }
        invokeClick("PLAY")
        waitFor("ORBIT playing") { present("STOP") }
        invokeClick("STOP")
        route(false)
        assertEquals(5f, OrbitStore.load(entry.dir).aquariumTailSeconds)
        org.junit.Assert.assertArrayEquals(metadataBytes, metadata.readBytes())
        org.junit.Assert.assertArrayEquals(wave, File(entry.dir, "kick.wav").readBytes())
    }

    /**
     * The one project tempo (2026-11-06): ORBIT has no BPM buttons, and a set
     * saved at another tempo than the project's (the kit says 92, the file
     * says 120) opens and plays without them. The entry is App's effective
     * kit, so its tempo is the project's.
     */
    @Test fun there_are_no_BPM_buttons_and_an_old_set_at_another_tempo_still_opens() {
        val entry = kit()
        OrbitStore.save(OrbitPresets.fromKit(entry.dir.name, entry.kit, 120f, 48_000), entry.dir)
        show(entry)
        assertTrue("no SLOWER button", !present("SLOWER"))
        assertTrue("no FASTER button", !present("FASTER"))
    }

    private val folders = mutableListOf<File>()
    // Written by the screen on the main thread, read by the test's own.
    private val toasts = CopyOnWriteArrayList<String>()
    private val groovesWritten = AtomicInteger(0)
    private val backs = AtomicInteger(0)

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
                backLabel = "◄ BEAT",
                onBack = { backs.incrementAndGet() },
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
    fun the_back_chip_is_spoken_from_the_label_it_is_given_and_closes_the_screen() {
        show(kit())
        // `show` passes "◄ BEAT": the chip's words come from the model's `backChip`, and
        // its spoken name from `spokenBack` of them, not from a name kept in this screen.
        assertTrue("the chip for \"◄ BEAT\" is spoken as BACK TO BEAT", present("BACK TO BEAT"))
        assertTrue("and no longer names the GROOVE tab it used to say", !present("BACK TO GROOVE"))
        invokeClick("BACK TO BEAT")
        assertEquals("one tap on the chip closes the screen once", 1, backs.get())
    }

    /** The span chip used to cycle on a tap and open its picker on a hold; a tap now opens the five as a sheet. */
    @Test
    fun the_span_chip_opens_a_sheet_of_the_five_spans_and_picking_one_saves_it() {
        val entry = kit()
        show(entry)
        invokeClick("SPAN: FREE")
        for (span in OrbitSpan.entries.filter { it != OrbitSpan.FREE }) assertTrue("${span.label} is a row", present("SPAN: ${span.label}"))
        invokeClick("SPAN: ${OrbitSpan.ONE.label}")
        waitFor("the span saved", timeoutMillis = 30_000) {
            runCatching { OrbitStore.load(entry.dir).orbits.first().span }.getOrNull() == OrbitSpan.ONE
        }
        assertTrue("the sheet closed on the pick", !present("SPAN: ${OrbitSpan.TWO.label}"))
    }

    /** The step count opens a sheet of ring sizes; a pick sets it and closes the sheet. */
    @Test
    fun the_step_readout_opens_a_sheet_of_ring_sizes_and_picking_one_saves_it() {
        val entry = kit()
        show(entry)
        invokeClick("EDIT STEPS")
        compose.onNodeWithText(OrbitPanels.stepsName(12), useUnmergedTree = true).performClick()
        pump()
        waitFor("the steps saved", timeoutMillis = 30_000) {
            runCatching { OrbitStore.load(entry.dir).orbits.first().steps }.getOrNull() == 12
        }
    }

    /** ARP, THE SET, SECTIONS and ONE TURN OUT fold open one at a time, and each closed line says what is in it. */
    @Test
    fun the_folds_open_one_at_a_time() {
        show(kit())
        assertTrue("OUT is folded shut", !present("CLIP TO KIT") && present("EXPAND ONE TURN OUT"))
        invokeClick("OUT")
        assertTrue("OUT opened", present("CLIP TO KIT") && present("COLLAPSE ONE TURN OUT"))
        invokeClick("EXPAND SECTIONS")
        assertTrue("SECTIONS replaced OUT", present("+ SECTION") && !present("CLIP TO KIT"))
        invokeClick("OPEN THE SET")
        assertTrue("THE SET replaced SECTIONS", present("COLLAPSE THE SET") && !present("+ SECTION"))
    }

    @Test
    fun clip_to_kit_writes_the_rings_into_the_kits_grooves() {
        val entry = kit()
        show(entry)
        invokeClick("OUT")
        invokeClick("CLIP TO KIT")
        waitFor("the groove written", timeoutMillis = 30_000) { groovesWritten.get() == 1 }
        assertTrue("a clip in groove.json", GrooveStore.load(entry.dir).isNotEmpty())
    }

    @Test
    fun groove_to_rings_reads_the_clip_back_as_more_rings() {
        val entry = kit()
        show(entry)
        val before = builtRings(entry)
        invokeClick("OUT")
        invokeClick("CLIP TO KIT")
        waitFor("the groove written", timeoutMillis = 30_000) { groovesWritten.get() == 1 }
        invokeClick("OUT")
        invokeClick("GROOVE TO RINGS")
        waitFor("the groove's rings saved beside the first", timeoutMillis = 30_000) { (ringsOnDisk(entry) ?: 0) > before }
        assertTrue("the screen said what it read: $toasts", toasts.any { " is on the rings" in it })
    }
}
