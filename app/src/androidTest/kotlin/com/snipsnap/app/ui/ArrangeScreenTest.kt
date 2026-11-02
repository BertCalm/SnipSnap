package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.Exports
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalReduceMotion
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.Names
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
}
