package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Relay
import com.snipsnap.synth.RelayPatch
import com.snipsnap.synth.RelayPresets
import com.snipsnap.synth.RelayVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Saved RELAY sounds keep their pulse until an explicit gesture choice changes the recipe. */
@RunWith(AndroidJUnit4::class)
class RelaySynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "relay-editor-${System.nanoTime()}").apply { mkdirs() }
    private var generation by mutableIntStateOf(0)
    private var currentEntry by mutableStateOf<KitShelf.Entry?>(null)

    @After fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(
        voice: RelayVoice,
        patch: RelayPatch? = null,
        kit: Boolean = false,
        reader: ((File) -> Pair<List<UserPresets.Saved>, List<UserPresets.Binned>>)? = null,
    ) {
        settings["engine"] = "RELAY"
        settings["voice"] = voice.name
        patch?.let { SynthDrafts.write(settings, listOf(it)) }
        if (kit) {
            val dir = File(shelf, "kit").apply { mkdirs() }
            val empty = Kit("RELAY EDITOR", emptyList())
            KitStore.save(empty, dir)
            currentEntry = KitShelf.Entry(dir, empty)
        }
        setPhoneContent {
            val readShelf = reader ?: LocalSynthPresetReader.current
            CompositionLocalProvider(LocalSynthPresetReader provides readShelf) {
                key(generation) {
                    SynthScreen(currentEntry, shelf, onToast = {}, onKitUpdated = { updated ->
                        currentEntry = currentEntry?.let { KitShelf.Entry(it.dir, updated) }
                    }, onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> },
                        onOpenSurface = {}, appScope = appScope, settings = settings)
                }
            }
        }
    }

    private fun press(label: String) {
        button(label).performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
    }

    private fun dial(label: String, value: Float) {
        button(label).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        pump()
    }

    private fun progress(label: String): Float = button(label).fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun draft(voice: RelayVoice): RelayPatch? = SynthDrafts.read(settings)
        .filterIsInstance<RelayPatch>().firstOrNull { it.voice == voice }

    @Test fun fresh_pulse_and_last_macro_edit_survive_voice_switch_and_recreation() {
        show(RelayVoice.KEY)
        waitFor("fresh repeating Relay") { draft(RelayVoice.KEY)?.model == RelayPatch.MODEL_VERSION }
        assertEquals(listOf(RelayVoice.KEY), SynthDrafts.read(settings).filterIsInstance<RelayPatch>().map { it.voice })
        dial("PULSE", .81f)
        press("BASS")
        waitFor("fresh repeating bass") { draft(RelayVoice.BASS)?.model == RelayPatch.MODEL_VERSION }
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(RelayPatch.MODEL_VERSION, draft(RelayVoice.BASS)!!.model)
        press("KEY")
        assertEquals(RelayPatch.MODEL_VERSION, draft(RelayVoice.KEY)!!.model)
        assertEquals(.81f, progress("PULSE"), 1e-6f)
        assertEquals(.81f, draft(RelayVoice.KEY)!!.macros.getValue("PULSE"), 1e-6f)
    }

    @Test fun old_macro_only_work_keeps_original_pulse_until_explicit_choice() {
        settings["macro.RELAY.FLOW.PULSE"] = ".72"
        settings["macro.RELAY.FLOW.BLEND"] = ".91"
        show(RelayVoice.FLOW)
        waitFor("legacy Relay macro restore") { draft(RelayVoice.FLOW)?.model == RelayPatch.LEGACY_MODEL_VERSION }
        assertEquals(.72f, progress("PULSE"), 1e-6f)
        dial("COLOR", .43f)
        press("KEY")
        press("FLOW")
        compose.runOnIdle { generation++ }
        pump()
        val before = requireNotNull(draft(RelayVoice.FLOW))
        assertEquals(RelayPatch.LEGACY_MODEL_VERSION, before.model)
        assertEquals(.72f, before.macros.getValue("PULSE"), 1e-6f)
        assertEquals(.43f, before.macros.getValue("COLOR"), 1e-6f)
        press("RELAY REPEATING PULSE")
        assertEquals(before.copy(model = RelayPatch.MODEL_VERSION), draft(RelayVoice.FLOW))
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(before.copy(model = RelayPatch.MODEL_VERSION), draft(RelayVoice.FLOW))
        press("RELAY ORIGINAL ACCENTS")
        assertEquals(before, draft(RelayVoice.FLOW))
    }

    @Test fun complete_phrase_and_explicit_pulse_choice_survive_save_send_and_reopen() {
        val source = RelayPresets.forVoice(RelayVoice.FLOW).single { it.name == "WARM DRIFT" }
            .copy(name = "SAVED FLOW", velocity = .43f, model = RelayPatch.LEGACY_MODEL_VERSION)
        // A typed recipe is newer than these old navigation-only controls.
        settings["macro.RELAY.FLOW.PULSE"] = ".99"
        show(RelayVoice.FLOW, source, kit = true)
        assertEquals(source.macros.getValue("PULSE"), progress("PULSE"), 1e-6f)
        dial("PULSE", .73f)
        dial("VELOCITY", .58f)
        val before = requireNotNull(draft(RelayVoice.FLOW))
        assertEquals(source.phrase, before.phrase)
        assertEquals(.58f, before.velocity, 1e-6f)
        press("RELAY REPEATING PULSE")
        val chosen = requireNotNull(draft(RelayVoice.FLOW))
        assertEquals(before.copy(model = RelayPatch.MODEL_VERSION), chosen)
        assertFalse(settings.containsKey("preset.RELAY.FLOW"))
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(chosen, draft(RelayVoice.FLOW))
        press("SAVE PRESET")
        // Within UserPresets.MAX_NAME: a longer name dims SAVE and nothing is written.
        val presetName = "MY PULSE FLOW"
        compose.onNode(hasSetTextAction()).performTextReplacement(presetName)
        pump()
        button("SAVE").assertIsEnabled()
        press("SAVE")
        waitFor("saved repeating Relay") { UserPresets.read(shelf).any { it.name == presetName } }
        val saved = UserPresets.read(shelf).single { it.name == presetName }.patch as RelayPatch
        assertEquals(chosen.copy(name = saved.name), saved)
        press("SEND TO PAD")
        press("A01, EMPTY")
        val dir = requireNotNull(currentEntry).dir
        waitFor("repeating Relay pad", timeoutMillis = 60_000) { KitStore.load(dir).pad(1)?.recipe != null }
        val pad = requireNotNull(KitStore.load(dir).pad(1))
        val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        val landed = recipe.patch as RelayPatch
        assertEquals(saved.copy(name = landed.name), landed)
        val expected = ByteArrayOutputStream().also { WavWriter.write(it, recipe.render()) }.toByteArray()
        assertArrayEquals(expected, File(dir, pad.sampleFile).readBytes())
        press("REOPEN PAD RECIPE")
        press("REOPEN A01 ${pad.displayName}")
        assertEquals(RelayPatch.MODEL_VERSION, draft(RelayVoice.FLOW)!!.model)
        assertEquals(source.phrase, draft(RelayVoice.FLOW)!!.phrase)
        dial("VELOCITY", .37f)
        assertEquals(RelayPatch.MODEL_VERSION, draft(RelayVoice.FLOW)!!.model)
        assertEquals(.37f, draft(RelayVoice.FLOW)!!.velocity, 1e-6f)
    }

    @Test fun named_yours_recipe_arrives_before_default_sliders_can_replace_it() {
        val source = RelayPatch("DELAYED RELAY", RelayVoice.KEY,
            Relay.defaults(RelayVoice.KEY) + ("PULSE" to .23f),
            velocity = .61f, model = RelayPatch.LEGACY_MODEL_VERSION)
        UserPresets.save(shelf, source, 1L)
        settings["preset.RELAY.KEY"] = source.name
        settings["macro.RELAY.KEY.PULSE"] = ".23"
        val reads = AtomicInteger()
        val release = CountDownLatch(1)
        try {
            show(RelayVoice.KEY, reader = { root ->
                reads.incrementAndGet()
                check(release.await(30, TimeUnit.SECONDS)) { "Relay preset-read latch timed out" }
                UserPresets.read(root) to UserPresets.bin(root)
            })
            compose.waitUntil(timeoutMillis = 5_000) { reads.get() >= 1 }
            button("PULSE").assertDoesNotExist()
            button("VELOCITY").assertDoesNotExist()
            button("RELAY REPEATING PULSE").assertDoesNotExist()
            button("RANDOMISE").assertIsNotEnabled()
            button("SAVE PRESET").assertIsNotEnabled()
            button("AUDITION").assertIsNotEnabled()
            button("PRESET FIRST HANDOFF").assertIsEnabled()
            compose.runOnIdle {
                assertEquals(null, draft(RelayVoice.KEY))
                assertEquals(source.name, settings["preset.RELAY.KEY"])
                assertEquals(".23", settings["macro.RELAY.KEY.PULSE"])
            }
            release.countDown()
            waitFor("complete delayed Relay") { draft(RelayVoice.KEY)?.model == RelayPatch.LEGACY_MODEL_VERSION }
            val restored = requireNotNull(draft(RelayVoice.KEY))
            assertEquals(source, restored)
            dial("PULSE", .83f)
            assertEquals(RelayPatch.LEGACY_MODEL_VERSION, draft(RelayVoice.KEY)!!.model)
            assertEquals(.83f, draft(RelayVoice.KEY)!!.macros.getValue("PULSE"), 1e-6f)
        } finally {
            release.countDown()
        }
    }
}
