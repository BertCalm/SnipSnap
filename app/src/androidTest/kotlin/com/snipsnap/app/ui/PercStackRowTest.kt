package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.BoroughStackSwap
import com.snipsnap.shell.Copy
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.synth.BoroughKits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The PERC STACK row (docs/BOROUGH.md, round 3.2), wired the way App wires it: a pick runs
 * `BoroughStackSwap.swap` off the main thread, an answer that names changed pads becomes the ask, REPLACE swaps
 * with replaceChanges and CANCEL leaves the kit alone. The rules are `BoroughStackSwapTest`'s, on the JVM; these
 * check that the row is drawn only for a BOROUGH kit, that a swap lands, and that changed pads are asked about
 * first. The fixture is one BOROUGH drum pad and the real TAMBOURINE stack for STOOP DUST, rendered on the device:
 * the stack is what the swap compares and replaces, and rendering twelve drums would only cost time.
 */
@RunWith(AndroidJUnit4::class)
class PercStackRowTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dirs = mutableListOf<File>()
    private var kitState by mutableStateOf<Kit?>(null)
    private var busyState by mutableStateOf(false)
    private var askState by mutableStateOf<PercStackAsk?>(null)
    @Volatile private var failure: Throwable? = null

    @After
    fun cleanUp() {
        appScope.cancel()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun sine(hz: Double) = Snip(
        FloatArray(4410) { i -> (.4 * sin(2.0 * PI * hz * i / 44100.0) * exp(-i / 1400.0)).toFloat() },
        channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE,
    )

    private val boroughDrum = mapOf("engine" to "BOROUGH", "kit" to "STOOP", "slot" to "1", "kitVersion" to BoroughKits.VERSION.toString())

    /** A01 a BOROUGH kick (a stand-in sine), A13-A16 STOOP DUST's TAMBOURINE stack as the starter writes it. */
    private fun boroughKit(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val dir = File(cache, "perc-stack-test-${System.nanoTime()}")
        dirs += dir
        val stack = BoroughKits.stackPads(BoroughKits.Kit.STOOP, BoroughKits.Stack.TAMBOURINE)
        val arranged: List<ArrangedPad?> = listOf(ArrangedPad(sine(60.0), DrumClass.KICK, source = boroughDrum, displayName = "Kick")) +
            List(BoroughKits.DRUM_PADS - 1) { null } + stack
        KitAssembler.assembleArranged("PERC STACK TEST", arranged, dir, tempoBpm = 90f, glue = BoroughKits.glueOf(BoroughKits.Kit.STOOP))
        return dir
    }

    private fun show(dir: File?, kit: Kit) {
        kitState = kit
        setPhoneContent {
            PercStackRow(
                kit = kitState!!, busy = busyState, ask = askState,
                onPick = { stack, replace -> if (dir != null) pick(dir, stack, replace) },
                onKeep = { askState = null },
            )
        }
    }

    /** App's percStack, minus the dock: the swap off the main thread, then the kit or the ask. */
    private fun pick(dir: File, stack: BoroughKits.Stack, replace: Boolean) {
        askState = null
        busyState = true
        appScope.launch {
            try {
                when (val result = BoroughStackSwap.swap(dir, stack, replace)) {
                    is BoroughStackSwap.Result.NeedsConfirm -> askState = PercStackAsk(result.stack, result.changedSlots)
                    is BoroughStackSwap.Result.Swapped -> kitState = result.kit
                    else -> Unit
                }
            } catch (e: Throwable) {
                failure = e
            } finally {
                busyState = false
            }
        }
    }

    private fun nodes(description: String) =
        compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes()

    private fun selected(label: String): Boolean =
        nodes(label).single().config.getOrNull(SemanticsProperties.Selected) == true

    private fun activate(description: String) {
        waitFor("\"$description\" to be live", timeoutMillis = 60_000) {
            val node = nodes(description).singleOrNull()
            node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null
        }
        compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue("\"$description\" refused its click", click()) }
        pump()
    }

    private fun settled() {
        // A stack renders in a few seconds on a phone; the emulator is slower, and the swap may render twice.
        waitFor("the swap to finish", timeoutMillis = 300_000) { !busyState }
        failure?.let { throw AssertionError("the swap failed", it) }
        pump()
    }

    private fun stackOnDisk(dir: File): Set<String?> =
        KitStore.load(dir).pads.filter { it.slot in BoroughStackSwap.STACK_SLOTS }.map { it.source["stack"] }.toSet()

    private fun asking(): Boolean =
        compose.onAllNodesWithText(Copy.PERC_STACK_ASK, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theRowIsDrawnOnlyForABoroughKit() {
        // Drawing needs only the pads' provenance, so neither kit here has files behind it.
        val plain = Kit("PLAIN", listOf(KitPad(slot = 13, sampleFile = "a13.wav", drumClass = DrumClass.PERC)))
        show(null, plain)
        assertTrue("no PERC STACK on a kit that is not BOROUGH", compose.onAllNodesWithTag("perc-stack-row").fetchSemanticsNodes().isEmpty())
        assertTrue(nodes("HANDS").isEmpty())

        val stackSource = { slot: Int -> boroughDrum + mapOf("slot" to slot.toString(), "stack" to "BELLS") }
        kitState = Kit("BOROUGH WAX", listOf(KitPad(1, "a01.wav", source = boroughDrum)) +
            (13..16).map { KitPad(it, "a$it.wav", drumClass = DrumClass.PERC, source = stackSource(it)) })
        pump()
        assertTrue(compose.onAllNodesWithTag("perc-stack-row").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText(Copy.PERC_STACK, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        for (label in listOf("HANDS", "TAMBOURINE", "BELLS", "TOPS")) assertEquals(label, 1, nodes(label).size)
        assertTrue("the stack on A13-A16 is the selected one", selected("BELLS"))
        assertTrue(listOf("HANDS", "TAMBOURINE", "TOPS").none { selected(it) })
        assertNotCutShort("TAMBOURINE")
    }

    @Test
    fun untouchedStackPadsSwapStraightAway() {
        val dir = boroughKit()
        val kickBytes = File(dir, KitStore.load(dir).pad(1)!!.sampleFile).readBytes()
        show(dir, KitStore.load(dir))
        assertTrue(selected("TAMBOURINE"))

        activate("HANDS")
        settled()
        assertTrue("no ask for pads nobody changed", !asking())
        assertEquals(setOf("HANDS"), stackOnDisk(dir))
        assertTrue("the row follows the kit", selected("HANDS"))
        val saved = KitStore.load(dir)
        assertArrayEquals("A01 did not move", kickBytes, File(dir, saved.pad(1)!!.sampleFile).readBytes())
        assertEquals(BoroughKits.glueOf(BoroughKits.Kit.STOOP), saved.glue)
        assertTrue(saved.pads.filter { it.slot in BoroughStackSwap.STACK_SLOTS }.all { it.chain?.zones?.size == 3 })
    }

    @Test
    fun changedStackPadsAreAskedAboutFirst() {
        val dir = boroughKit()
        KitBuilderModel.open(dir).apply {
            update(14) { it.copy(level = .4f) }
            save()
        }
        show(dir, KitStore.load(dir))
        val before = File(dir, KitStore.FILE_NAME).readBytes()

        activate("BELLS")
        settled()
        waitFor("the ask") { asking() }
        assertTrue(compose.onAllNodesWithText(Copy.percStackChanged(listOf(14)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertArrayEquals("nothing written before the answer", before, File(dir, KitStore.FILE_NAME).readBytes())

        activate("CANCEL")
        waitFor("the ask to close") { !asking() }
        assertEquals(setOf("TAMBOURINE"), stackOnDisk(dir))
        assertTrue(selected("TAMBOURINE"))

        activate("BELLS")
        settled()
        waitFor("the ask again") { asking() }
        activate(Copy.PERC_STACK_REPLACE)
        settled()
        assertTrue(!asking())
        assertEquals(setOf("BELLS"), stackOnDisk(dir))
        assertEquals("A14 is the stack's pad again, at its own level", 0.707946f, KitStore.load(dir).pad(14)!!.level, 0f)
        assertTrue(selected("BELLS"))
    }
}
