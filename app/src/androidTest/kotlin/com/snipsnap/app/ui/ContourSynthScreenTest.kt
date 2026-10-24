package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.ContourPadMaker
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.synth.ContourEngine
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.ContourPresets
import com.snipsnap.synth.ContourTable
import com.snipsnap.synth.ContourVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** The actual phone MAKE path must export the edited recipe, rather than voice defaults. */
@RunWith(AndroidJUnit4::class)
class ContourSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val written = AtomicBoolean(false)
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "contour-synth-${System.nanoTime()}").apply { mkdirs() }

    @After
    fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(patch: ContourPatch) {
        settings["engine"] = "CONTOUR"
        settings["voice"] = patch.voiceName
        SynthDrafts.write(settings, listOf(patch))
        for ((macro, value) in patch.macros) settings["macro.CONTOUR.${patch.voiceName}.$macro"] = value.toString()
        setPhoneContent {
            SynthScreen(entry = null, shelfRoot = shelf, onToast = {}, onKitUpdated = {},
                onShelfAssetWritten = { written.set(true) }, onDroneToLoop = { _, _, _ -> },
                onOpenSurface = {}, appScope = appScope, settings = settings)
        }
    }

    private fun click(label: String) {
        val node = compose.onNodeWithContentDescription(label).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action ?: error("no click for $label")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    private fun slider(label: String) = compose.onNode(
        hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
    )

    private fun dial(label: String, value: Float) {
        val action = slider(label).fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("no slider for $label")
        compose.runOnIdle { assertTrue(action.invoke(value)) }
        pump()
    }

    @Test
    fun grouped_controls_and_make_export_the_complete_edited_recipe() {
        val factory = ContourPresets.forVoice(ContourVoice.KEY).first()
        val source = factory.copy(name = "CUSTOM CONTOUR", velocity = .63f,
            config = factory.config.copy(bodyTable = ContourTable.ROUND_PULSE,
                edgeTable = ContourTable.FORMANT, loopSeconds = 1f),
            macros = factory.macros + ("TUNE" to .875f))
        show(source)
        for (heading in listOf("TONE", "ENVELOPE", "MOVEMENT", "PLAY")) {
            compose.onNodeWithText(heading).assertExists()
        }
        for (control in ContourEngine.macrosFor(source.voice)) slider(control.name).assertExists()
        dial("BODY", .2f)
        click("MAKE INSTRUMENT")
        slider("RELEASE").assertExists()
        compose.onNodeWithText("FOUR OCTAVES OF SETTLED TONE.", substring = true).assertExists()
        dial("RELEASE", .5f)
        click("MAKE")
        // Real offline DSP is deliberately not tied to the Compose frame clock.
        waitFor("CONTOUR instrument package", timeoutMillis = 180_000) { written.get() }

        val (sidecar, instrument) = InstrumentStore.list(File(shelf, KitShelf.INSTRUMENTS_DIR)).single()
        val edited = source.copy(macros = source.macros + ("BODY" to .2f))
        val spec = ContourPadMaker.spec(edited, .5f)
        assertEquals(spec.releaseSeconds, instrument.release, 1e-6f)
        assertEquals(ContourPadMaker.zoneMidis(spec), instrument.zones.map { it.rootNote })
        val midi = ContourEngine.rootMidi(source.voice)
        val zone = instrument.zones.single { it.rootNote == midi }
        val expected = ContourPadMaker.renderZone(spec, midi)
        val actual = WavReader.read(File(sidecar.parentFile, zone.sample))
        assertEquals(expected.loopStartFrame, zone.loopStartFrame)
        assertEquals(expected.snip.frameCount, actual.frameCount)
        assertTrue("phone MAKE keeps table pair, velocity and edited BODY within PCM24 rounding",
            actual.samples.indices.all { abs(actual.samples[it] - expected.snip.samples[it]) < 2e-7f })
        assertEquals(.2f, settings.getValue("macro.CONTOUR.KEY.BODY").toFloat(), 1e-6f)
        assertEquals(source.config, (SynthDrafts.read(settings).single() as ContourPatch).config)
    }
}
