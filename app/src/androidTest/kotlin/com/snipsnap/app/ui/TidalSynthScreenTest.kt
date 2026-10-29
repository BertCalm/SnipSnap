package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.SynthWork
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Tidal
import com.snipsnap.synth.TidalPatch
import com.snipsnap.synth.TidalPresets
import com.snipsnap.synth.TidalVoice
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Complete finite recipes survive controls, navigation, the shelf and real pad delivery. */
@RunWith(AndroidJUnit4::class)
class TidalSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "tidal-synth-${System.nanoTime()}").apply { mkdirs() }
    private val settings = ConcurrentHashMap<String, String>()
    private var generation by mutableStateOf(0)
    private var entry by mutableStateOf<KitShelf.Entry?>(null)
    private var kitUpdates = 0

    @After
    fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(
        source: TidalPatch? = null,
        presetReader: ((File) -> Pair<List<UserPresets.Saved>, List<UserPresets.Binned>>)? = null,
    ) {
        settings["engine"] = "TIDAL"
        settings["voice"] = source?.voiceName ?: "PAIR"
        if (source != null) SynthWork.replace(settings,
            listOf(SynthWork.Working(PadRecipe(patch = source), null)))
        val dir = File(shelf, "kit").apply { mkdirs() }
        val kit = Kit(name = "TIDAL TEST", pads = emptyList())
        KitStore.save(kit, dir)
        entry = KitShelf.Entry(dir, kit)
        setPhoneContent {
            val reader = presetReader ?: LocalSynthPresetReader.current
            CompositionLocalProvider(LocalSynthPresetReader provides reader) {
                key(generation) {
                    SynthScreen(
                        entry = entry, shelfRoot = shelf, onToast = {},
                        onKitUpdated = { updated -> entry = entry?.copy(kit = updated); kitUpdates++ },
                        onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                        appScope = appScope, settings = settings,
                    )
                }
            }
        }
    }

    private fun activate(label: String) {
        button(label).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        pump(16)
    }

    private fun dial(label: String, value: Float) {
        button(label).performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }
        pump(16)
    }

    private fun progress(label: String): Float = button(label).fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun draft(voice: TidalVoice = TidalVoice.PAIR): TidalPatch? =
        SynthDrafts.read(settings).filterIsInstance<TidalPatch>().firstOrNull { it.voice == voice }

    private fun working(): TidalPatch = SynthWork.read(settings).single {
        it.recipe.patch?.voiceName == "PAIR"
    }.recipe.patch as TidalPatch

    @Test
    fun bothVoicesExposeFiniteControlsAndFactoryRecipes() {
        show()
        button("ENGINE TIDAL. CHOOSE ENGINE").assertExists()
        for (voice in TidalVoice.entries) {
            activate(voice.name)
            for (macro in listOf("APPROACH", "PASSAGE", "TILT", "BIND", "TAIL")) button(macro).assertExists()
            button("ROOT").assertExists()
            button("VELOCITY").assertExists()
            button("TIDAL VARIATION SEED").assertExists()
            button("TIDAL MATERIAL SOURCE").assertIsSelected()
            for (preset in TidalPresets.forVoice(voice)) button("PRESET ${preset.name}").assertExists()
            button("HOLD").assertDoesNotExist()
            button("MAKE INSTRUMENT").assertExists()
            button("TIDAL NOTE PLAYBACK").assertIsSelected()
            button("TIDAL HOLD PLAYBACK").assertExists()
        }
    }

    @Test
    fun heldPlaybackSurvivesPerformanceEditsAndNavigation() {
        show(TidalPatch("Held original", TidalVoice.PAIR, mapOf("HOLD" to 1f),
            midi = 55, velocity = .42f, seed = Long.MIN_VALUE, model = 1))
        button("TIDAL HOLD PLAYBACK").assertIsSelected()
        dial("TAIL", .8f)
        dial("VELOCITY", .3f)
        assertEquals(1f, working().macros["HOLD"])
        assertEquals(Long.MIN_VALUE, working().seed)
        assertEquals(1, working().model)
        compose.runOnIdle { generation++ }
        pump(32)
        button("TIDAL HOLD PLAYBACK").assertIsSelected()
        activate("TIDAL NOTE PLAYBACK")
        assertEquals(0f, working().macros["HOLD"])
        assertEquals(.8f, working().macros["TAIL"])
    }

    @Test
    fun legacyRecipeRetainsPerformanceThroughEditsSavingAndFinitePadDelivery() {
        show(TidalPatch("Saved old pair", TidalVoice.PAIR, emptyMap(),
            midi = 67, velocity = .42f, seed = 9_007_199_254_740_993L, model = 1))
        button("TIDAL ORIGINAL SOURCE").assertIsSelected()
        assertEquals(.35f, progress("PASSAGE"), 1e-6f)
        dial("ROOT", (64 - Tidal.MIN_MIDI).toFloat() / (Tidal.MAX_MIDI - Tidal.MIN_MIDI))
        dial("VELOCITY", .31f)
        dial("PASSAGE", .22f)
        button("TIDAL VARIATION SEED").performTextReplacement(Long.MIN_VALUE.toString())
        pump(16)
        assertEquals(Long.MIN_VALUE, working().seed)
        button("TIDAL VARIATION SEED").performTextReplacement("9223372036854775808")
        pump(16)
        compose.onNodeWithText("INVALID SEED · PREVIOUS VARIATION KEPT").assertExists()
        assertEquals(Long.MIN_VALUE, working().seed)
        button("TIDAL VARIATION SEED").performTextReplacement(Long.MIN_VALUE.toString())
        pump(16)

        activate("TIDAL MATERIAL SOURCE")
        assertEquals(2, working().model)
        assertEquals(Long.MIN_VALUE, working().seed)
        activate("TIDAL ORIGINAL SOURCE")
        activate("STREAM")
        activate("PAIR")
        compose.runOnIdle { generation++ }
        pump(16)
        button("TIDAL ORIGINAL SOURCE").assertIsSelected()
        assertEquals(.22f, progress("PASSAGE"), 1e-6f)
        activate("SCRAMBLE")
        val edited = working()
        assertEquals(64, edited.midi)
        assertEquals(.31f, edited.velocity)
        assertEquals(Long.MIN_VALUE, edited.seed)
        assertEquals(1, edited.model)
        assertEquals(0f, edited.macros.getValue("HOLD"))

        activate("SAVE PRESET")
        // The modal name field follows the still-composed seed field in the tree.
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[fields.fetchSemanticsNodes().lastIndex].performTextReplacement("MY TIDAL")
        pump(16)
        activate("SAVE")
        waitFor("saved complete TIDAL recipe") { UserPresets.read(shelf).isNotEmpty() }
        assertEquals(edited.copy(name = "MY TIDAL"), UserPresets.read(shelf).single().patch)

        activate("SEND TO PAD")
        activate("A01, EMPTY")
        waitFor("finite TIDAL pad delivery", timeoutMillis = 180_000) { kitUpdates > 0 }
        val pad = KitStore.load(requireNotNull(entry).dir).pad(1)!!
        assertTrue(pad.oneShot)
        assertEquals(DrumClass.LOOP, pad.drumClass)
        assertEquals("64", pad.source["rootMidi"])
        assertEquals("authored", pad.source["rootOrigin"])
        val delivered = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch as TidalPatch
        assertEquals(edited.copy(name = delivered.name), delivered)
        activate("REOPEN PAD RECIPE")
        activate("REOPEN A01 ${pad.displayName}")
        assertEquals(delivered, working())
        button("HOLD").assertDoesNotExist()
        button("MAKE INSTRUMENT").assertExists()
        button("TIDAL NOTE PLAYBACK").assertIsSelected()
        button("TIDAL HOLD PLAYBACK").assertExists()
    }

    @Test
    fun heldPadDeliveryReopensAndSwitchingToNoteClearsLooping() {
        show(TidalPatch("Held pad", TidalVoice.STREAM, mapOf("HOLD" to 1f), seed = Long.MAX_VALUE))
        activate("SEND TO PAD")
        activate("A01, EMPTY")
        waitFor("held TIDAL pad delivery", timeoutMillis = 180_000) { kitUpdates > 0 }
        var pad = KitStore.load(requireNotNull(entry).dir).pad(1)!!
        assertFalse(pad.oneShot)
        assertTrue(pad.loop)
        activate("REOPEN PAD RECIPE")
        activate("REOPEN A01 ${pad.displayName}")
        button("TIDAL HOLD PLAYBACK").assertIsSelected()
        activate("TIDAL NOTE PLAYBACK")
        val updates = kitUpdates
        activate("UPDATE PAD A01")
        waitFor("finite replacement clears TIDAL looping", timeoutMillis = 180_000) { kitUpdates > updates }
        pad = KitStore.load(requireNotNull(entry).dir).pad(1)!!
        assertTrue(pad.oneShot)
        assertFalse(pad.loop)
    }

    @Test
    fun queuedPerformanceEditsMergeAndStayWithTheirOriginalVoice() {
        show(TidalPatch("SAME FRAME", TidalVoice.PAIR, emptyMap(),
            midi = 67, velocity = .42f, seed = 9_007_199_254_740_993L, model = 1))
        val rootEdit = requireNotNull(button("ROOT").fetchSemanticsNode()
            .config[SemanticsActions.SetProgress].action)
        val velocityEdit = requireNotNull(button("VELOCITY").fetchSemanticsNode()
            .config[SemanticsActions.SetProgress].action)
        val materialSource = requireNotNull(button("TIDAL MATERIAL SOURCE").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        compose.runOnIdle {
            // All callbacks came from one composition. Each must edit the last
            // complete recipe rather than replacing it with its captured patch.
            assertTrue(rootEdit((64 - Tidal.MIN_MIDI).toFloat() / (Tidal.MAX_MIDI - Tidal.MIN_MIDI)))
            assertTrue(velocityEdit(.31f))
            assertTrue(materialSource())
        }
        pump(16)
        val edited = working()
        assertEquals(64, edited.midi)
        assertEquals(.31f, edited.velocity)
        assertEquals(2, edited.model)
        assertEquals(9_007_199_254_740_993L, edited.seed)

        val selectStream = requireNotNull(button("STREAM").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        compose.runOnIdle {
            assertTrue(selectStream())
            // A late PAIR event must not be filed under STREAM or overwrite PAIR.
            assertTrue(rootEdit((72 - Tidal.MIN_MIDI).toFloat() / (Tidal.MAX_MIDI - Tidal.MIN_MIDI)))
        }
        pump(16)
        assertEquals("STREAM", settings["voice"])
        assertEquals(edited, working())
        assertEquals(null, draft(TidalVoice.STREAM))
    }

    @Test
    fun sameFrameRootEditAndSaveCapturesTheNewestCompleteRecipe() {
        show(TidalPatch("FRAME SOURCE", TidalVoice.PAIR, emptyMap(),
            midi = 67, velocity = .42f, seed = Long.MIN_VALUE, model = 1))
        activate("SAVE PRESET")
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[fields.fetchSemanticsNodes().lastIndex].performTextReplacement("FRAME SAVE")
        pump(16)
        val rootEdit = requireNotNull(button("ROOT").fetchSemanticsNode()
            .config[SemanticsActions.SetProgress].action)
        val save = requireNotNull(button("SAVE").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        compose.runOnIdle {
            assertTrue(rootEdit((64 - Tidal.MIN_MIDI).toFloat() / (Tidal.MAX_MIDI - Tidal.MIN_MIDI)))
            assertTrue(save())
        }
        waitFor("same-frame TIDAL save") { UserPresets.read(shelf).isNotEmpty() }
        val saved = UserPresets.read(shelf).single().patch as TidalPatch
        assertEquals(64, saved.midi)
        assertEquals(.42f, saved.velocity)
        assertEquals(Long.MIN_VALUE, saved.seed)
        assertEquals(1, saved.model)
    }

    @Test
    fun firstMacroEditCreatesACompleteDraftBeforeLeavingTheBench() {
        show()
        dial("TAIL", .44f)
        compose.runOnIdle { generation++ }
        pump(16)
        waitFor("restored first TIDAL edit") { draft()?.macros?.get("TAIL") == .44f }
        assertEquals(2, draft()!!.model)
        assertEquals(Tidal.DEFAULT_MIDI, draft()!!.midi)
        assertEquals(Tidal.DEFAULT_SEED, draft()!!.seed)
        assertEquals(.44f, progress("TAIL"), 1e-6f)
        assertEquals(0f, draft()!!.macros.getValue("HOLD"))
    }

    @Test
    fun restoringASparseLegacyUserPresetUsesItsOriginalDefaults() {
        val source = TidalPatch("OLD PAIR", TidalVoice.PAIR, emptyMap(),
            midi = 53, velocity = .38f, seed = Long.MAX_VALUE, model = 1)
        UserPresets.save(shelf, source, 1L)
        settings["preset.TIDAL.PAIR"] = source.name
        show()
        waitFor("restored legacy TIDAL preset") { draft()?.model == 1 }
        button("TIDAL ORIGINAL SOURCE").assertIsSelected()
        assertEquals(.35f, progress("PASSAGE"), 1e-6f)
        assertEquals(53, draft()!!.midi)
        assertEquals(.38f, draft()!!.velocity)
        assertEquals(Long.MAX_VALUE, draft()!!.seed)
        assertFalse(draft()!!.macros.getValue("HOLD") > 0f)
    }

    @Test
    fun unreadLegacyRecipeCannotBeReplacedByTheFirstEditBeforeShelfRestore() {
        val source = TidalPatch("DELAYED PAIR", TidalVoice.PAIR, emptyMap(),
            midi = 53, velocity = .38f, seed = Long.MAX_VALUE, model = 1)
        UserPresets.save(shelf, source, 1L)
        settings["preset.TIDAL.PAIR"] = source.name
        settings["macro.TIDAL.PAIR.PASSAGE"] = ".35"
        val reads = AtomicInteger()
        val release = CountDownLatch(1)
        try {
            show(presetReader = { root ->
                reads.incrementAndGet()
                check(release.await(30, TimeUnit.SECONDS)) { "TIDAL preset-read latch timed out" }
                UserPresets.read(root) to UserPresets.bin(root)
            })
            compose.waitUntil(timeoutMillis = 5_000) { reads.get() >= 1 }
            button("ROOT").assertDoesNotExist()
            button("PASSAGE").assertDoesNotExist()
            button("TIDAL VARIATION SEED").assertDoesNotExist()
            for (action in listOf("SCRAMBLE", "SAVE PRESET", "SEND TO PAD", "SPREAD ACROSS PADS", "AUDITION")) {
                button(action).assertIsNotEnabled()
            }
            button("PRESET ${TidalPresets.forVoice(TidalVoice.PAIR).first().name}").assertIsEnabled()
            // Invoke the registered accessibility action directly as well: an
            // event queued before the disable must not bypass the restore gate.
            for (action in listOf("SCRAMBLE", "SAVE PRESET", "SEND TO PAD")) {
                button(action).performSemanticsAction(SemanticsActions.OnClick) { it() }
                pump(16)
            }
            compose.runOnIdle {
                assertEquals(null, draft())
                assertEquals(source.name, settings["preset.TIDAL.PAIR"])
                assertEquals(".35", settings["macro.TIDAL.PAIR.PASSAGE"])
            }
            release.countDown()
            waitFor("delayed original TIDAL recipe") { draft()?.model == 1 }
            button("TIDAL ORIGINAL SOURCE").assertIsSelected()
            dial("PASSAGE", .22f)
            assertEquals(53, draft()!!.midi)
            assertEquals(.38f, draft()!!.velocity)
            assertEquals(Long.MAX_VALUE, draft()!!.seed)
            assertEquals(1, draft()!!.model)
            assertEquals(.22f, draft()!!.macros.getValue("PASSAGE"), 1e-6f)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun missingNamedPresetReleasesTheRestoreGate() {
        settings["preset.TIDAL.PAIR"] = "NO LONGER ON SHELF"
        show()
        waitFor("missing TIDAL preset cleared") { !settings.containsKey("preset.TIDAL.PAIR") }
        button("ROOT").assertExists()
        button("TIDAL MATERIAL SOURCE").assertIsSelected()
        dial("PASSAGE", .28f)
        assertEquals(2, draft()!!.model)
        assertEquals(.28f, draft()!!.macros.getValue("PASSAGE"), 1e-6f)
    }

    @Test
    fun unreadableShelfKeepsTheRecipeProtectedAndFactoryChoiceAvailable() {
        val source = TidalPatch("UNREADABLE", TidalVoice.PAIR, emptyMap(),
            midi = 53, velocity = .38f, seed = Long.MAX_VALUE, model = 1)
        UserPresets.save(shelf, source, 1L)
        settings["preset.TIDAL.PAIR"] = source.name
        show(presetReader = { error("fixture shelf read failed") })
        // The test clock is manual: async shelf state needs a frame to reach semantics.
        waitFor("failed TIDAL shelf state", timeoutMillis = 5_000) {
            compose.onAllNodesWithText("SAVED TIDAL COULD NOT OPEN. CHOOSE A PRESET.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        button("ROOT").assertDoesNotExist()
        button("SAVE PRESET").assertIsNotEnabled()
        assertEquals(null, draft())
        assertEquals(source, UserPresets.read(shelf).single().patch)
        val factory = TidalPresets.forVoice(TidalVoice.PAIR).first()
        activate("PRESET ${factory.name}")
        waitFor("factory choice after failed shelf read") { draft()?.model == factory.model }
        button("ROOT").assertExists()
        assertEquals(factory, draft())
    }
}
