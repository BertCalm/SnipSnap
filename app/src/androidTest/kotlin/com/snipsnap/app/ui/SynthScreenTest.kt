package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.PadBanks
import com.snipsnap.synth.Eq
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.Onset
import com.snipsnap.synth.OnsetPatch
import com.snipsnap.synth.OnsetVoice
import com.snipsnap.synth.PadRecipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The ONSET adapter and the real REOPEN → UPDATE path, driven through Compose.
 * The update fixture carries an older renderer, a rack and pad metadata so a
 * fresh-patch reconstruction or a plain SEND replacement cannot pass unnoticed.
 * JVM suites cover the codecs; these tests require a device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class SynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val roots = mutableListOf<File>()
    private var currentEntry by mutableStateOf<KitShelf.Entry?>(null)
    private val updatedKit = AtomicReference<Kit?>(null)

    @After
    fun cleanUp() {
        appScope.cancel()
        roots.forEach { it.deleteRecursively() }
    }

    private fun shelfRoot(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cache, "synth-screen-test-${System.nanoTime()}").apply {
            check(mkdirs())
            roots += this
        }
    }

    private fun show(root: File, entry: KitShelf.Entry? = null) {
        currentEntry = entry
        setPhoneContent {
            SynthScreen(
                entry = currentEntry,
                shelfRoot = root,
                onToast = {},
                onKitUpdated = { kit ->
                    updatedKit.set(kit)
                    currentEntry = currentEntry?.copy(kit = kit)
                },
                onShelfAssetWritten = {},
                onDroneToLoop = { _, _, _ -> },
                onOpenSurface = {},
                appScope = appScope,
            )
        }
    }

    private fun macroNode(label: String) =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true)

    private fun macroValue(label: String): Float =
        macroNode(label).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current
            ?: error("$label has no adjustable progress")

    private fun dialMacro(label: String, value: Float) {
        val action = macroNode(label).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("$label has no SetProgress action")
        compose.runOnIdle { assertTrue(action.invoke(value)) }
        pump()
    }

    /**
     * REOPEN and UPDATE can sit below the phone's fold. Invoke their real
     * accessibility actions directly, like PadSheetScreenTest's steppers:
     * no animated ScrollBy can wait forever on the harness's frozen clock.
     */
    private fun activate(label: String) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        assertFalse("$label is disabled", node.config.getOrNull(SemanticsProperties.Disabled) != null)
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("$label has no click action")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    @Test
    fun onset_follows_skin_and_all_four_voices_expose_their_six_live_controls() {
        show(shelfRoot())
        tap("ENGINE THUMP · TAP FOR NEXT")
        button("ENGINE SKIN · TAP FOR NEXT").assertExists()
        tap("ENGINE SKIN · TAP FOR NEXT")
        button("ENGINE ONSET · TAP FOR NEXT").assertExists()

        val voices = listOf(
            "KICK" to OnsetVoice.KICK,
            "SNARE" to OnsetVoice.SNARE,
            "HAT CL" to OnsetVoice.HAT_CLOSED,
            "HAT OP" to OnsetVoice.HAT_OPEN,
        )
        for ((label, voice) in voices) {
            tap(label)
            button(label).assertIsSelected()
            val controls = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertEquals("$voice control count", 6, controls.size)
            for ((macro, value) in Onset.defaults(voice)) {
                assertEquals("$voice $macro default", value, macroValue(macro), 0f)
            }
        }

        // Changing a knob reaches the adapter's per-voice state, and returning
        // from another voice restores the edit rather than reseeding defaults.
        tap("KICK")
        dialMacro("BLEND", .73f)
        assertEquals(.73f, macroValue("BLEND"), 0f)
        tap("SNARE")
        assertEquals(.5f, macroValue("BLEND"), 0f)
        tap("KICK")
        assertEquals(.73f, macroValue("BLEND"), 0f)
    }

    @Test
    fun reopening_and_updating_a_saved_onset_pad_keeps_its_renderer_rack_and_pad_settings() {
        val root = shelfRoot()
        val dir = File(root, "KEPT KIT").apply { check(mkdirs()) }
        val patch = OnsetPatch(
            "KEPT KICK",
            OnsetVoice.KICK,
            Onset.defaults(OnsetVoice.KICK) + ("BLEND" to .25f) + ("TUNE" to .61f),
            modelVersion = 2,
        )
        val recipe = PadRecipe(
            patch = patch,
            fx = FxChain(reverse = true, eq = Eq.defaults() + ("AIR" to .7f)),
            treatment = "KEPT RACK",
            amount = .42f,
            alias = true,
        )
        val pad = KitPad(
            slot = 17,
            sampleFile = "kept.wav",
            displayName = "MY KICK",
            drumClass = DrumClass.KICK,
            colorHex = "#123456",
            level = .63f,
            pan = .2f,
            tuneCoarse = 3,
            tuneFine = -7,
            muteGroup = 7,
            oneShot = false,
            source = mapOf("origin" to "imported audition", "rawSha256" to "previous-wave-fingerprint"),
            recipe = recipe.toJsonValue(),
            attack = .13f,
            decay = .7f,
            cutoff = .6f,
            resonance = .2f,
            humanize = .17f,
        )
        val kit = Kit("KEPT KIT", listOf(pad), tempoBpm = 104f)
        val sample = WavWriter.write(File(dir, pad.sampleFile), recipe.render())
        val before = sample.readBytes()
        KitStore.save(kit, dir)
        show(root, KitShelf.Entry(dir, kit))

        // Start on THUMP and let the imported recipe select ONSET itself.
        activate("REOPEN PAD RECIPE")
        activate("REOPEN ${PadBanks.tag(pad.slot)} ${pad.displayName}")
        button("ENGINE ONSET · TAP FOR NEXT").assertExists()
        button("KICK").assertIsSelected()
        for ((macro, value) in patch.macros) {
            assertEquals("reopened $macro", value, macroValue(macro), 0f)
        }
        dialMacro("BLEND", .85f)
        dialMacro("DECAY", .6f)
        activate("UPDATE PAD ${PadBanks.tag(pad.slot)}")
        waitFor("the edited ONSET pad to be saved", timeoutMillis = 30_000) { updatedKit.get() != null }

        val saved = checkNotNull(updatedKit.get())
        val savedPad = checkNotNull(saved.pad(pad.slot))
        val expectedPatch = patch.copy(macros = patch.macros + ("BLEND" to .85f) + ("DECAY" to .6f))
        val expectedRecipe = recipe.copy(patch = expectedPatch)
        assertEquals(expectedRecipe, PadRecipe.fromJsonValue(checkNotNull(savedPad.recipe)))
        assertEquals(
            pad.copy(recipe = expectedRecipe.toJsonValue(), source = pad.source - "rawSha256"),
            savedPad,
        )
        assertEquals(saved, KitStore.load(dir))
        val written = sample.readBytes()
        assertFalse("the macro edit must regenerate the WAV", before.contentEquals(written))
        val expectedWav = ByteArrayOutputStream().also { WavWriter.write(it, expectedRecipe.render()) }.toByteArray()
        assertArrayEquals("updated audio must include the saved rack and model-2 renderer", expectedWav, written)
    }
}
