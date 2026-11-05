package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitRobin
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.isRobinChain
import com.snipsnap.shell.RobinKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The kit-wide ROUND ROBIN row, wired the way App wires it: KitShelf.robin does the work off the
 * main thread and the row's callbacks hand it RobinKit.on/set/off. The rules are RobinKitTest's;
 * these check the row's wiring, UNDO, a refused change putting the dial back, and that a stop
 * part way hands back a kit that matches its files.
 */
@RunWith(AndroidJUnit4::class)
class KitScreenRobinTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kitDirs = mutableListOf<File>()
    private var entryState by mutableStateOf<KitShelf.Entry?>(null)
    private var busyState by mutableStateOf(false)
    @Volatile private var failure: Throwable? = null
    @Volatile private var refuse = false

    @After
    fun cleanUp() {
        appScope.cancel()
        kitDirs.forEach { it.deleteRecursively() }
    }

    private fun sine(hz: Double): Snip {
        val samples = FloatArray(4410) { i -> (.4 * sin(2.0 * PI * hz * i / 44100.0) * exp(-i / 1400.0)).toFloat() }
        return Snip(samples, channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE)
    }

    /** Pad 1 a drum hit, pad 2 a loop. */
    private fun kit(): KitShelf.Entry {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "kit-robin-test-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += dir
        WavWriter.write(File(dir, "hit1.wav"), sine(240.0))
        WavWriter.write(File(dir, "loop2.wav"), sine(180.0))
        val kit = Kit(
            name = "KIT ROBIN TEST",
            pads = listOf(
                KitPad(slot = 1, sampleFile = "hit1.wav", drumClass = DrumClass.KICK),
                KitPad(slot = 2, sampleFile = "loop2.wav", drumClass = DrumClass.KICK, loop = true),
            ),
            tempoBpm = 92f,
        )
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    private fun run(shelf: KitShelf, work: (File) -> RobinKit.Result) {
        val source = entryState ?: return
        busyState = true
        appScope.launch {
            try {
                val (entry, _) = shelf.robin(source, work)
                entryState = entry
            } catch (e: KitShelf.RobinStopped) {
                entryState = e.entry
                failure = e
            } catch (e: Throwable) {
                failure = e
            } finally {
                busyState = false
            }
        }
    }

    private fun show(entry: KitShelf.Entry) {
        entryState = entry
        val shelf = KitShelf(entry.dir.parentFile!!)
        setPhoneContent {
            val e = entryState!!
            KitRobinRow(
                robin = e.kit.robin, kitKey = e.dir, busy = busyState, scheme = LocalScheme.current,
                onOn = { run(shelf) { RobinKit.onSaving(it) } },
                onSet = { s -> run(shelf) { if (refuse) error("refused") else RobinKit.setSaving(it, s) } },
                onOff = { run(shelf) { RobinKit.offSaving(it) } },
            )
        }
    }

    private fun nodes(description: String) =
        compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes()

    private fun live(description: String) {
        waitFor("\"$description\" to be live", timeoutMillis = 60_000) {
            val node = nodes(description).singleOrNull()
            node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null
        }
    }

    private fun activate(description: String) {
        live(description)
        compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue("\"$description\" refused its click", click()) }
        pump()
    }

    private fun dial(description: String, target: Float) {
        live(description)
        val node = compose.onNodeWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNode("no stepper \"$description\"")
        val action = node.config.getOrNull(SemanticsActions.SetProgress)?.action ?: error("\"$description\" has no SetProgress action")
        assertTrue(action.invoke(target))
        pump()
    }

    private fun settled() {
        waitFor("the kit work to finish", timeoutMillis = 60_000) { !busyState }
        if (!refuse) failure?.let { if (it !is KitShelf.RobinStopped) throw AssertionError("the kit work failed", it) }
    }

    private fun saved(entry: KitShelf.Entry): Kit = KitStore.load(entry.dir)
    private fun bytesOf(entry: KitShelf.Entry, slot: Int): ByteArray {
        val pad = saved(entry).pad(slot) ?: error("pad $slot missing")
        return File(entry.dir, pad.sampleFile).readBytes()
    }

    @Test
    fun kitSwitchSkipsTheLoopAndUndoRestoresTheDial() {
        val entry = kit()
        show(entry)

        assertTrue("UNDO waits for a dial change", nodes("KIT ROUND ROBIN UNDO").isEmpty())
        activate("KIT ROUND ROBIN, OFF")
        settled()
        waitFor("the kit switched on") { entryState?.kit?.robin != null }
        val on = saved(entry)
        assertTrue("the drum pad is chained", on.pad(1)!!.isRobinChain)
        assertFalse("the loop is left alone", on.pad(2)!!.isRobinChain)
        assertEquals(KitRobin(), on.robin)
        val at60 = bytesOf(entry, 1)

        dial("KIT ROUND ROBIN AMOUNT", 1f)
        settled()
        waitFor("amount 100 stored") { entryState?.kit?.robin?.amount == 1f }
        assertFalse("the follower was re-rendered", at60.contentEquals(bytesOf(entry, 1)))

        activate("KIT ROUND ROBIN UNDO")
        settled()
        waitFor("amount back") { entryState?.kit?.robin?.amount == KitRobin.DEFAULT_AMOUNT }
        assertArrayEquals("UNDO restores the 60 render byte for byte", at60, bytesOf(entry, 1))
        assertTrue("UNDO is spent", nodes("KIT ROUND ROBIN UNDO").isEmpty())
    }

    @Test
    fun kitOffBringsEveryOriginalBack() {
        val entry = kit()
        val original1 = File(entry.dir, "hit1.wav").readBytes()
        val original2 = File(entry.dir, "loop2.wav").readBytes()
        show(entry)

        activate("KIT ROUND ROBIN, OFF")
        settled()
        waitFor("on") { entryState?.kit?.robin != null }
        activate("KIT ROUND ROBIN, ON")
        settled()
        waitFor("off") { entryState?.kit?.robin == null }
        val off = saved(entry)
        assertNull(off.robin)
        assertFalse(off.pad(1)!!.isRobinChain)
        assertArrayEquals(original1, bytesOf(entry, 1))
        assertArrayEquals(original2, bytesOf(entry, 2))
    }

    @Test
    fun aStopPartWayHandsBackAKitThatMatchesItsFiles() {
        val entry = kit()
        val shelf = KitShelf(entry.dir.parentFile!!)
        // Pad 2 is a loop (skipped); a third pad with no file stops the deal after pad 1.
        val broken = KitPad(slot = 3, sampleFile = "gone3.wav", drumClass = DrumClass.KICK)
        val withBroken = entry.kit.copy(pads = entry.kit.pads + broken)
        KitStore.save(withBroken, entry.dir)
        val stopped = try {
            shelf.robin(KitShelf.Entry(entry.dir, withBroken)) { RobinKit.onSaving(it) }
            null
        } catch (e: KitShelf.RobinStopped) {
            e
        }
        assertNotNull("the deal should stop at pad 3", stopped)
        val kit = stopped!!.entry.kit
        assertNotNull("the switch reads ON so OFF can undo what was dealt", kit.robin)
        assertTrue(kit.pad(1)!!.isRobinChain)
        assertEquals("disk and the handed-back entry agree", KitStore.load(entry.dir), kit)
        val (afterOff, _) = shelf.robin(stopped.entry) { RobinKit.offSaving(it) }
        assertNull(afterOff.kit.robin)
        assertFalse(afterOff.kit.pad(1)!!.isRobinChain)
    }

    @Test
    fun aRefusedDialChangePutsTheDialBackAndKeepsUndoHidden() {
        val entry = kit()
        show(entry)
        activate("KIT ROUND ROBIN, OFF")
        settled()
        waitFor("on") { entryState?.kit?.robin != null }
        // Wire only the dial to a change that fails before touching anything.
        val shelf = KitShelf(entry.dir.parentFile!!)
        refuse = true
        dial("KIT ROUND ROBIN AMOUNT", 1f)
        settled()
        waitFor("the dial back at 60") {
            nodes("KIT ROUND ROBIN AMOUNT").single().config.getOrNull(SemanticsProperties.StateDescription) == "60"
        }
        assertTrue("no UNDO for a change that never landed", nodes("KIT ROUND ROBIN UNDO").isEmpty())
        assertNotNull(shelf)
    }
}
