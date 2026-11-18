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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.Copy
import com.snipsnap.shell.EffectsMenu
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.PadSheet
import com.snipsnap.shell.PadSheetBoxes
import com.snipsnap.shell.PadSheetPage
import com.snipsnap.shell.PedalSlate
import com.snipsnap.shell.TryEffect
import com.snipsnap.synth.Pedals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Try before you keep on the real PadSheetScreen (docs/UX_PASS_2026_11.md, fix e). TryEffectTest
 * holds the rule (the preview is what KEEP gives, and the kit is not written); these check the
 * wiring: a list row's tap is heard and marked HEARING with the kit's files untouched and no
 * scratch copy left behind, KEEP applies it, and CANCEL lets it go. PedalSlateScreenTest's harness.
 */
@RunWith(AndroidJUnit4::class)
class TryEffectScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val kitDirs = mutableListOf<File>()
    private val toasts = CopyOnWriteArrayList<String>()
    private var currentSlot by mutableStateOf(1)
    private var openBoxState by mutableStateOf<String?>(PadSheetBoxes.Box.TREATMENT.name)
    private var pageState by mutableStateOf(PadSheetPage.EDITOR)
    private var pedalRow by mutableStateOf(PedalSlate.DEFAULT)
    private val cache get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @After
    fun cleanUp() {
        appScope.cancel()
        kitDirs.forEach { it.deleteRecursively() }
    }

    private fun kit(): KitShelf.Entry {
        val dir = File(cache, "try-effect-test-${System.nanoTime()}").apply { mkdirs() }
        kitDirs += dir
        val samples = FloatArray(4410) { i -> (.4 * sin(2.0 * PI * 240 * i / 44100.0) * exp(-i / 1400.0)).toFloat() }
        WavWriter.write(File(dir, "hit1.wav"), Snip(samples, channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE))
        val kit = Kit(name = "TRY EFFECT TEST", pads = listOf(KitPad(slot = 1, sampleFile = "hit1.wav", drumClass = DrumClass.SNARE)), tempoBpm = 92f)
        KitStore.save(kit, dir)
        return KitShelf.Entry(dir, kit)
    }

    private fun show(entry: KitShelf.Entry) {
        pageState = PadSheetPage.EDITOR
        setPhoneContent {
            PadSheetScreen(
                entry = entry,
                slot = currentSlot,
                backLabel = "◄ PADS",
                onSlotChange = { currentSlot = it },
                page = pageState,
                onOpenEffects = { pageState = PadSheetPage.EFFECTS },
                onOpenVariations = { pageState = PadSheetPage.VARIATIONS },
                onBack = { pageState = PadSheetPage.EDITOR },
                onToast = { toasts += it },
                onNavigateTape = {},
                onGrainField = {},
                onSplice = {},
                onStack = {},
                onKitUpdated = {},
                onShelfAssetWritten = {},
                appScope = appScope,
                openBox = openBoxState,
                onOpenBox = { openBoxState = it },
                pedalRow = pedalRow,
                onPedalRowChange = { pedalRow = it },
            )
        }
        waitFor("the pad sheet", timeoutMillis = 60_000) { hasText("PAD ${PadBanks.tag(1)}") }
        activate(Copy.PAD_EFFECTS_DOOR)
        waitFor("the effects list", timeoutMillis = 60_000) { tagged("effects-list") }
    }

    private fun nodes(description: String) =
        compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes()

    private fun hasText(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tagged(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun activate(description: String) {
        waitFor("\"$description\" to be live", timeoutMillis = 60_000) {
            val node = nodes(description).singleOrNull()
            node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null
        }
        compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue("\"$description\" refused its click", click()) }
        pump()
    }

    private fun activateTag(tag: String) {
        waitFor("$tag to be live", timeoutMillis = 60_000) {
            val node = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            node != null && node.config.getOrNull(SemanticsProperties.Disabled) == null
        }
        compose.onNodeWithTag(tag, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue("$tag refused its click", click()) }
        pump()
    }

    private fun files(dir: File): Map<String, List<Byte>> = dir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(dir).path to it.readBytes().toList() }

    private fun savedKeys(entry: KitShelf.Entry): List<String> =
        PedalSlate.boardOf(KitStore.load(entry.dir).pad(1)?.recipe)?.picks?.map { it.key }.orEmpty()

    @Test
    fun a_pedal_row_is_heard_without_writing_and_keep_puts_it_on() {
        val entry = kit()
        show(entry)
        val before = files(entry.dir)
        val hardClip = EffectsMenu.START_HERE.first { it.target == EffectsMenu.Target.Pedal("FORGE") }

        activateTag("effect-start-${hardClip.name}")
        waitFor("HARD CLIP to be heard", timeoutMillis = 60_000) { hasText(EffectsMenu.HEARING) && tagged("effect-keep-bar") }
        assertEquals("a preview writes nothing in the kit", before, files(entry.dir))
        assertFalse("the scratch copy is gone", File(cache, "${TryEffect.SCRATCH_DIR}/${entry.dir.name}").exists())
        assertTrue("no treated toast for a preview", toasts.none { it.contains(PadSheet.displayLabel("FORGE")) })

        activateTag("effect-keep")
        val key = Pedals.byWord("FORGE")!!.key
        waitFor("KEEP to put HARD CLIP on", timeoutMillis = 60_000) { savedKeys(entry) == listOf(key) }
        waitFor("the keep bar to go", timeoutMillis = 60_000) { !tagged("effect-keep-bar") }
    }

    @Test
    fun an_effect_row_is_heard_and_cancel_leaves_the_pad_as_it_was() {
        val entry = kit()
        show(entry)
        val before = files(entry.dir)
        val punch = EffectsMenu.START_HERE.first { it.target == EffectsMenu.Target.Segment("PUNCH") }

        activateTag("effect-start-${punch.name}")
        waitFor("PUNCH to be heard", timeoutMillis = 60_000) { hasText(EffectsMenu.HEARING) && tagged("effect-keep-bar") }
        assertEquals(before, files(entry.dir))

        activateTag("effect-cancel")
        waitFor("the preview to go", timeoutMillis = 60_000) { !hasText(EffectsMenu.HEARING) && !tagged("effect-keep-bar") }
        assertEquals("CANCEL writes nothing", before, files(entry.dir))
        assertEquals(null, KitStore.load(entry.dir).pad(1)?.recipe)
    }
}
