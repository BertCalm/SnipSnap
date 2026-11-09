package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.Exports
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.shell.Copy
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.Names
import com.snipsnap.kit.SongPlanStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.shell.Arranger
import com.snipsnap.shell.KitBuilderModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * SONG's back chip on a device: it reads the label the app hands it
 * (`Navigation.backChip`, so it names the screen Back lands on), says that
 * label aloud as "BACK TO ...", and a tap calls `onBack`. The kit has no
 * groove on disk, so the screen plans nothing and draws its refusal under the
 * header: the header, and so the chip, is there either way.
 *
 * EXPORT is tested on a kit that does have a groove: it presses EXPORT SONG
 * and finds the project, its data folder and the WAV under `exports/<stem>/song/`.
 *
 * Shown with [LocalReduceMotion] on, as `ComposeScreenTest`'s KDoc asks.
 */
@RunWith(AndroidJUnit4::class)
class ArrangeScreenTest : ComposeScreenTest() {

    private val backs = AtomicInteger(0)
    private val folders = mutableListOf<File>()
    private val exportedStems = mutableListOf<String>()

    @After
    fun cleanUp() {
        folders.forEach { it.deleteRecursively() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Exports.dir(context)?.let { root -> exportedStems.forEach { File(root, it).deleteRecursively() } }
    }

    /** A three-pad kit with a one-bar groove, on disk where the screen's plan reads it. */
    private fun grooveKit(name: String): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "arrange-export-${System.nanoTime()}").apply { mkdirs() }
        folders += dir
        val m = KitBuilderModel.create(name, File(dir, name))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.save()
        val s16 = Mpc3Clip.PULSES_PER_16TH
        GrooveStore.save(
            m.kitDir,
            listOf(Mpc3Clip("Groove", 1, listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(37, 4 * s16, 0.85f), Mpc3Note(36, 8 * s16, 0.9f), Mpc3Note(37, 12 * s16, 0.9f)))),
        )
        return KitShelf.Entry(m.kitDir, m.kit)
    }

    private fun show(backLabel: String) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val entry = KitShelf.Entry(File(cache, "arrange-test-kit"), Kit(name = "SONG UI TEST", pads = emptyList()))
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                ArrangeScreen(
                    entry = entry,
                    backLabel = backLabel,
                    onBack = { backs.incrementAndGet() },
                    onToast = {},
                )
            }
        }
    }

    @Test
    fun the_back_chip_reads_the_label_it_is_given_in_full() {
        show("◄ BEAT")
        assertReadsInFull("◄ BEAT")
    }

    @Test
    fun the_back_chip_is_spoken_as_back_to_that_screen_and_a_tap_calls_onBack() {
        show("◄ BEAT")

        button("BACK TO BEAT").assertIsEnabled()
        assertEquals("nothing has closed it yet", 0, backs.get())
        button("BACK TO BEAT").performClick()
        pump()
        assertEquals("one tap closes it once", 1, backs.get())
    }

    @Test
    fun export_writes_the_project_and_the_wav_under_the_kits_song_folder() {
        val entry = grooveKit("SONG EXPORT TEST")
        val stem = Names.sanitizeStem(entry.kit.name)
        exportedStems += stem
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val songDir = File(File(Exports.dir(context)!!, stem), "song")
        songDir.deleteRecursively()
        val plan = Arranger.arrange(entry.kit, entry.dir, seed = 0)

        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                ArrangeScreen(entry = entry, backLabel = "◄ BEAT", onBack = {}, onToast = {})
            }
        }
        // The plan loads off the main thread; EXPORT is there once it has.
        waitFor("the plan and its EXPORT button", timeoutMillis = 30_000) {
            runCatching { button("EXPORT SONG").assertIsEnabled() }.isSuccess
        }
        button("EXPORT SONG").performClick()

        // Mix, write, then the share sheet: the WAV is the last file down.
        val xpj = File(songDir, "${plan.name}.xpj")
        val data = File(songDir, "${plan.name}_[ProjectData]")
        val wav = File(songDir, "${plan.name}.wav")
        // Read back, not just seen: the file is there before its last byte is.
        waitFor("the song's WAV", timeoutMillis = 30_000) {
            wav.isFile && runCatching { WavReader.read(wav).frameCount > 0 }.getOrDefault(false)
        }

        assertTrue("the .xpj is under exports/$stem/song", xpj.isFile && xpj.length() > 0)
        assertTrue("its _[ProjectData] folder beside it", data.isDirectory && !data.list().isNullOrEmpty())
        assertTrue("the WAV holds the song", WavReader.read(wav).frameCount > 0)
    }

    @Test
    fun an_edit_is_kept_in_the_project_and_comes_back_when_the_song_is_opened_again() {
        val entry = grooveKit("SONG KEEP TEST")
        val projectDir = File(entry.dir.parentFile, "project").apply { mkdirs() }
        var open by mutableStateOf(true)

        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                // Closing is leaving the bench; opening again is a new visit,
                // with nothing in memory from the first.
                if (open) {
                    ArrangeScreen(entry = entry, backLabel = "◄ BEAT", onBack = {}, onToast = {}, projectDir = projectDir)
                }
            }
        }
        waitFor("the plan and its edit buttons", timeoutMillis = 30_000) {
            runCatching { button("MORE REPEATS").assertIsEnabled() }.isSuccess
        }
        val drafted = Arranger.arrange(entry.kit, entry.dir, seed = 0)
        val first = drafted.sections[0]
        // The first open keeps the arranger's own draft (seed 0) ...
        waitFor("the first plan to be kept", timeoutMillis = 30_000) { SongPlanStore.load(projectDir, entry.dir.name) != null }
        // ... and an edit changes it: the selected (first) section plays once more.
        button("MORE REPEATS").performClick()
        waitFor("the edit to be kept", timeoutMillis = 30_000) {
            SongPlanStore.load(projectDir, entry.dir.name)?.sections?.get(0)?.repeats == first.repeats + 1
        }
        button("MOVE SECTION DOWN").performClick()
        waitFor("the reorder to be kept", timeoutMillis = 30_000) {
            SongPlanStore.load(projectDir, entry.dir.name)?.sections?.get(1)?.name == first.name
        }

        open = false
        pump()
        open = true
        waitFor("the kept song to come back", timeoutMillis = 30_000) {
            runCatching { button("MORE REPEATS").assertIsEnabled() }.isSuccess
        }
        // The song on screen is the kept one, not a fresh draft: the section
        // that moved down is second, and it still plays one time more.
        val kept = SongPlanStore.load(projectDir, entry.dir.name)!!
        assertEquals(drafted.sections[1].name, kept.sections[0].name)
        assertEquals(first.name, kept.sections[1].name)
        assertEquals(first.repeats + 1, kept.sections[1].repeats)
        // And the screen shows it: the first row is the section that was second.
        val row1 = "SECTION 1: ${drafted.sections[1].name.uppercase()}"
        val row2 = "SECTION 2: ${first.name.uppercase()}"
        compose.onNodeWithContentDescription(row1, substring = true).assertExists()
        compose.onNodeWithContentDescription(row2, substring = true).assertExists()
    }
    @Test
    fun bass_and_pull_up_are_the_selected_sections_own_and_are_kept() {
        val entry = grooveKit("SONG FLAGS TEST")
        val projectDir = File(entry.dir.parentFile, "project").apply { mkdirs() }
        setPhoneContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                ArrangeScreen(entry = entry, backLabel = "◄ BEAT", onBack = {}, onToast = {}, projectDir = projectDir)
            }
        }
        waitFor("the plan and its edit buttons", timeoutMillis = 30_000) {
            runCatching { button(Copy.SONG_BASS_OFF).assertIsEnabled() }.isSuccess
        }
        waitFor("the first plan to be kept", timeoutMillis = 30_000) { SongPlanStore.load(projectDir, entry.dir.name) != null }
        // The intro is selected: no bass, and no pull-up to offer (nothing plays before it).
        button(Copy.SONG_PULL_UP_OFF).assertIsNotEnabled()
        button(Copy.SONG_BASS_OFF).performClick()
        waitFor("the intro's bass to be kept", timeoutMillis = 30_000) {
            SongPlanStore.load(projectDir, entry.dir.name)?.sections?.get(0)?.bass == true
        }
        // The rows and buttons read the song as laid out again in the background.
        waitFor("the button to say BASS ON", timeoutMillis = 30_000) { runCatching { button(Copy.SONG_BASS_ON).assertExists() }.isSuccess }
        compose.onNodeWithContentDescription("SECTION 1:", substring = true).assertContentDescriptionContains(Copy.SONG_ROW_BASS, substring = true)

        // The second section takes a pull-up, kept as its own.
        compose.onNodeWithContentDescription("SECTION 2:", substring = true).performClick()
        pump()
        button(Copy.SONG_PULL_UP_OFF).assertIsEnabled().performClick()
        waitFor("the pull-up to be kept", timeoutMillis = 30_000) {
            SongPlanStore.load(projectDir, entry.dir.name)?.sections?.get(1)?.pullUp == true
        }
        waitFor("the row to say PULL-UP IN", timeoutMillis = 30_000) {
            runCatching { compose.onNodeWithContentDescription("SECTION 2:", substring = true).assertContentDescriptionContains(Copy.SONG_ROW_PULL_UP, substring = true) }.isSuccess
        }
    }
}
