package com.snipsnap.app.ui

import android.util.Log

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.KitBuilderModel
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Chamber
import com.snipsnap.synth.ChamberConfig
import com.snipsnap.synth.ChamberPatch
import com.snipsnap.synth.ChamberPresets
import com.snipsnap.synth.ChamberRenderer
import com.snipsnap.synth.ChamberVoice
import com.snipsnap.synth.PadRecipe
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
import kotlin.system.measureTimeMillis

/** The finite editor keeps the full authored room beside its note and controls. */
@RunWith(AndroidJUnit4::class)
class ChamberSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val directories = mutableListOf<File>()
    private var generation by mutableStateOf(0)

    @After
    fun cleanUp() {
        appScope.cancel()
        directories.forEach { it.deleteRecursively() }
    }

    private fun shelf(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "chamber-synth-test-${System.nanoTime()}",
    ).apply { mkdirs(); directories += this }

    private fun show(root: File, settings: MutableMap<String, String>, entry: KitShelf.Entry? = null) {
        setPhoneContent {
            key(generation) {
                SynthScreen(
                    entry = entry, shelfRoot = root,
                    onToast = {}, onKitUpdated = {}, onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                    appScope = appScope, settings = settings,
                )
            }
        }
    }

    private fun press(label: String) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("No click action for $label")
        compose.runOnIdle { assertTrue("Click refused for $label", action.invoke()) }
        pump()
    }

    private fun dial(label: String, value: Float) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("No slider action for $label")
        compose.runOnIdle { assertTrue("Slider refused for $label", action.invoke(value)) }
        pump()
    }

    private fun progress(label: String): Float = compose
        .onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun assertFiniteControls() {
        compose.onNodeWithText("FINITE STRIKE · ROOM TAIL INCLUDED. SPREAD TRANSPOSES THE ROOM TOO.").assertExists()
        compose.onNodeWithContentDescription("HOLD", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertDoesNotExist()
        compose.onNodeWithContentDescription("DRONE TO LOOP").assertDoesNotExist()
        for (macro in listOf("STRIKE", "BALANCE", "MATERIAL", "DIVIDE", "PLACEMENT")) {
            compose.onNodeWithContentDescription(macro, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun production_render_logs_android_cost_and_exact_cached_replay() {
        val patch = ChamberPatch("Android render probe", ChamberVoice.HYBRID,
            Chamber.defaults(ChamberVoice.HYBRID))
        val renderer = ChamberRenderer()
        lateinit var cold: Snip
        lateinit var warm: Snip
        lateinit var cached: Snip
        val coldMs = measureTimeMillis { cold = renderer.render(patch) }
        renderer.clear()
        val warmMs = measureTimeMillis { warm = renderer.render(patch) }
        val cachedMs = measureTimeMillis { cached = renderer.render(patch.copy(name = "Same recipe")) }
        assertTrue("warming must preserve every PCM sample", cold.samples.contentEquals(warm.samples))
        assertTrue("a cache hit must preserve every PCM sample", warm.samples.contentEquals(cached.samples))
        Log.i("ChamberPerformance", "Android production render: voice=${patch.voice}, model=${patch.model}, " +
            "midi=${patch.midi}, frames=${warm.samples.size}, rate=${warm.sampleRate}, " +
            "coldMs=$coldMs, warmFreshMs=$warmMs, cachedMs=$cachedMs; diagnostics and stems disabled")
    }

    @Test
    fun picker_reaches_both_finite_voices_and_their_factory_presets() {
        val settings = mutableMapOf("engine" to "FILAMENT", "voice" to "THREAD")
        show(shelf(), settings)
        compose.onNodeWithContentDescription("ENGINE FILAMENT. CHOOSE ENGINE").assertExists()
        press("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE APSIS. CHOOSE ENGINE").assertExists()
        press("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE TIDAL. CHOOSE ENGINE").assertExists()
        press("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE CHAMBER. CHOOSE ENGINE").assertExists()
        for (voice in ChamberVoice.entries) {
            press(voice.name)
            val preset = ChamberPresets.forVoice(voice).last()
            press("PRESET ${preset.name}")
            for ((macro, value) in Chamber.defaults(voice) + preset.macros) {
                if (macro != "HOLD") assertEquals(value, progress(macro), .00001f)
            }
            assertEquals((preset.midi - Chamber.MIDI_MIN) / 48f, progress("ROOT"), .00001f)
            assertEquals(preset.velocity, progress("VELOCITY"), .00001f)
            assertFiniteControls()
        }
        // The cycle continues through LIBRATION, MERIDIAN and RIME before wrapping to LATTICE.
        compose.onNodeWithContentDescription("ENGINE CHAMBER. CHOOSE ENGINE").assertExists()
        press("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE LIBRATION. CHOOSE ENGINE").assertExists()
        press("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE MERIDIAN. CHOOSE ENGINE").assertExists()
        press("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE RIME. CHOOSE ENGINE").assertExists()
        press("NEXT ENGINE")
        compose.onNodeWithContentDescription("ENGINE LATTICE. CHOOSE ENGINE").assertExists()
    }

    @Test
    fun fresh_rooms_keep_the_wider_response_through_note_edits_and_recreation() {
        val settings = mutableMapOf("engine" to "CHAMBER", "voice" to "HYBRID")
        show(shelf(), settings)
        for (voice in ChamberVoice.entries) {
            press(voice.name)
            compose.onNodeWithText("WIDER RESPONSE").assertExists()
            compose.onNodeWithContentDescription("USE WIDER RESPONSE").assertDoesNotExist()
            dial("ROOT", 1f)
            compose.runOnIdle {
                val draft = SynthDrafts.read(settings).filterIsInstance<ChamberPatch>().single { it.voice == voice }
                assertEquals(5, draft.model)
                assertEquals(84, draft.midi)
                assertEquals(Chamber.defaults(voice), draft.macros)
                assertEquals(ChamberConfig.forVoice(voice), draft.configuration)
            }
        }
        compose.runOnIdle { generation++ }
        pump()
        compose.onNodeWithText("WIDER RESPONSE").assertExists()
        assertEquals(1f, progress("ROOT"), .00001f)
        compose.runOnIdle {
            val drafts = SynthDrafts.read(settings).filterIsInstance<ChamberPatch>()
            assertEquals(ChamberVoice.entries.toSet(), drafts.map { it.voice }.toSet())
            assertTrue(drafts.all { it.model == 5 && it.midi == 84 })
        }
    }

    @Test
    fun saved_response_changes_only_by_choice_and_the_whole_wider_room_reaches_a_pad() {
        val root = shelf()
        val kitDir = File(root, "kit").apply { mkdirs() }
        val kit = Kit(name = "WIDER ROOM UI", pads = emptyList())
        KitStore.save(kit, kitDir)
        val original = ChamberPatch("Saved resonant room", ChamberVoice.AQUARIUM,
            Chamber.defaults(ChamberVoice.AQUARIUM) + mapOf("STRIKE" to .29f, "MATERIAL" to 1f),
            midi = 81, velocity = .53f, seed = Long.MIN_VALUE + 23, model = 3,
            configuration = ChamberConfig.aquarium().copy(splitHeight = .63f,
                targets = ChamberConfig.aquarium().targets.copy(floor = .19f),
                tuningRatios = ChamberConfig.aquarium().tuningRatios.copy(triangle = 3f)))
        val settings = mutableMapOf("engine" to "CHAMBER", "voice" to "AQUARIUM")
        SynthDrafts.write(settings, listOf(original))
        show(root, settings, KitShelf.Entry(kitDir, kit))
        compose.onNodeWithText("SAVED RESPONSE").assertExists()
        dial("ROOT", (82 - Chamber.MIDI_MIN) / 48f)
        val oldEdit = original.copy(midi = 82)
        compose.runOnIdle {
            assertEquals(oldEdit, SynthDrafts.read(settings).filterIsInstance<ChamberPatch>().single())
        }

        press("USE WIDER RESPONSE")
        val wider = oldEdit.copy(model = 5)
        compose.runOnIdle {
            assertEquals(wider, SynthDrafts.read(settings).filterIsInstance<ChamberPatch>().single())
        }
        compose.onNodeWithText("WIDER RESPONSE").assertExists()
        compose.onNodeWithContentDescription("USE WIDER RESPONSE").assertDoesNotExist()
        dial("ROOT", 1f)
        dial("VELOCITY", .62f)
        val expected = wider.copy(midi = 84, velocity = .62f)
        press("HYBRID")
        press("AQUARIUM")
        compose.runOnIdle { generation++ }
        pump()
        compose.onNodeWithText("WIDER RESPONSE").assertExists()
        compose.runOnIdle {
            assertEquals(expected, SynthDrafts.read(settings).filterIsInstance<ChamberPatch>()
                .single { it.voice == ChamberVoice.AQUARIUM })
        }

        press("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY WIDER ROOM")
        pump()
        press("SAVE")
        waitFor("saved wider CHAMBER room", timeoutMillis = 30_000) {
            UserPresets.read(root).any { it.name == "MY WIDER ROOM" }
        }
        val saved = UserPresets.read(root).single { it.name == "MY WIDER ROOM" }.patch as ChamberPatch
        assertEquals(expected.copy(name = "MY WIDER ROOM"), saved)
        press("SEND TO PAD")
        press("A01, EMPTY")
        waitFor("the wider CHAMBER pad", timeoutMillis = 180_000) {
            KitBuilderModel.open(kitDir).pad(1) != null
        }
        val pad = KitBuilderModel.open(kitDir).pad(1)!!
        val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        val placed = recipe.patch as ChamberPatch
        assertEquals(saved.copy(name = placed.name), placed)
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertTrue(pad.oneShot)
        assertEquals(null, recipe.fx)
        assertEquals("84", pad.source["rootMidi"])
        assertTrue(File(kitDir, pad.sampleFile).length() > 44)
    }

    @Test
    fun same_frame_note_response_and_save_keep_the_latest_complete_room() {
        val root = shelf()
        val original = ChamberPatch("Queued room", ChamberVoice.HYBRID,
            Chamber.defaults(ChamberVoice.HYBRID) + ("MATERIAL" to 1f),
            midi = 76, velocity = .41f, seed = Long.MAX_VALUE - 19, model = 3,
            configuration = ChamberConfig.hybrid().copy(splitHeight = .61f))
        val settings = mutableMapOf("engine" to "CHAMBER", "voice" to "HYBRID")
        SynthDrafts.write(settings, listOf(original))
        show(root, settings)
        press("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("QUEUED ROOM")
        pump()
        val rootEdit = requireNotNull(compose.onNodeWithContentDescription("ROOT", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.SetProgress].action)
        val velocityEdit = requireNotNull(compose.onNodeWithContentDescription("VELOCITY", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.SetProgress].action)
        val response = requireNotNull(compose.onNodeWithContentDescription("USE WIDER RESPONSE", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        // The semantics action fires whether or not the button is enabled, so a name the
        // sheet refuses (over the 14-letter limit) would otherwise fail 30 seconds later
        // with "save never came" instead of here.
        compose.onNodeWithContentDescription("SAVE", useUnmergedTree = true).assertIsEnabled()
        val save = requireNotNull(compose.onNodeWithContentDescription("SAVE", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val expected = original.copy(midi = 84, velocity = .63f, model = 5)
        compose.runOnIdle {
            // Every callback came from the same old composition. Exercise both
            // orders around the response choice, then save before any new frame.
            assertTrue(rootEdit((82 - Chamber.MIDI_MIN) / 48f))
            assertTrue(response())
            assertTrue(rootEdit(1f))
            assertTrue(velocityEdit(.63f))
            assertEquals(expected, SynthDrafts.read(settings).filterIsInstance<ChamberPatch>().single())
            assertTrue(save())
        }
        waitFor("same-frame complete CHAMBER save", timeoutMillis = 30_000) {
            UserPresets.read(root).any { it.name == "QUEUED ROOM" }
        }
        val saved = UserPresets.read(root).single { it.name == "QUEUED ROOM" }.patch as ChamberPatch
        assertEquals(expected.copy(name = "QUEUED ROOM"), saved)

        val immediateRoot = requireNotNull(compose.onNodeWithContentDescription("ROOT", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.SetProgress].action)
        compose.runOnIdle {
            assertTrue(immediateRoot((83 - Chamber.MIDI_MIN) / 48f))
            generation++
        }
        pump()
        compose.onNodeWithText("WIDER RESPONSE").assertExists()
        compose.runOnIdle {
            assertEquals(expected.copy(midi = 83), SynthDrafts.read(settings).filterIsInstance<ChamberPatch>().single())
        }
    }

    @Test
    fun a_name_only_yours_restore_adopts_the_complete_saved_room_and_controls() {
        val root = shelf()
        val voice = ChamberVoice.AQUARIUM
        val saved = ChamberPatch("MY WATER ROOM", voice,
            Chamber.defaults(voice) + mapOf("STRIKE" to .19f, "BALANCE" to .81f,
                "MATERIAL" to .12f, "DIVIDE" to .66f, "PLACEMENT" to .23f),
            midi = 48, velocity = .37f, seed = Long.MIN_VALUE + 17, model = 2,
            configuration = ChamberConfig.aquarium().copy(splitHeight = .64f,
                targets = ChamberConfig.aquarium().targets.copy(floor = .17f)))
        UserPresets.save(root, saved, System.currentTimeMillis())
        val settings = mutableMapOf("engine" to "CHAMBER", "voice" to voice.name,
            "preset.CHAMBER.${voice.name}" to saved.name)
        assertTrue("fixture has no full draft or macro entries", SynthDrafts.read(settings).isEmpty())
        show(root, settings)

        // The async shelf read must publish the saved controls together with
        // its room/model/root. Defaults must not rewrite this named recipe.
        waitFor("all controls from the named CHAMBER preset") {
            (Chamber.defaults(voice) + saved.macros).all { (macro, value) ->
                macro == "HOLD" || progress(macro) == value
            }
        }
        assertEquals((saved.midi - Chamber.MIDI_MIN) / 48f, progress("ROOT"), .00001f)
        assertEquals(saved.velocity, progress("VELOCITY"), .00001f)
        assertFiniteControls()
        compose.runOnIdle {
            assertEquals(saved, SynthDrafts.read(settings).filterIsInstance<ChamberPatch>().single())
        }
        compose.runOnIdle { generation++ }
        pump()
        for ((macro, value) in saved.macros) {
            if (macro != "HOLD") assertEquals(value, progress(macro), .00001f)
        }
        compose.runOnIdle {
            assertEquals(saved, SynthDrafts.read(settings).filterIsInstance<ChamberPatch>().single())
        }
    }

    @Test
    fun custom_room_model_and_exact_seed_survive_edits_recreation_save_and_delivery() {
        val root = shelf()
        val kitDir = File(root, "kit").apply { mkdirs() }
        val kit = Kit(name = "CHAMBER UI", pads = emptyList())
        KitStore.save(kit, kitDir)
        val room = ChamberConfig.aquarium().copy(splitHeight = .67f,
            targets = ChamberConfig.aquarium().targets.copy(floor = .15f, ceiling = .8f),
            tuningRatios = ChamberConfig.aquarium().tuningRatios.copy(triangle = 3f))
        val original = ChamberPatch("My authored room", ChamberVoice.HYBRID,
            Chamber.defaults(ChamberVoice.HYBRID) + ("STRIKE" to .31f),
            midi = 57, velocity = .43f, configuration = room, seed = Long.MAX_VALUE - 7, model = 2)
        val settings = mutableMapOf("engine" to "CHAMBER", "voice" to "HYBRID",
            "macro.CHAMBER.HYBRID.HOLD" to "1.0",
            "macro.CHAMBER.HYBRID.STRIKE" to ".99")
        SynthDrafts.write(settings, listOf(original))
        show(root, settings, KitShelf.Entry(kitDir, kit))
        assertEquals(.31f, progress("STRIKE"), .00001f)
        assertEquals(.43f, progress("VELOCITY"), .00001f)
        assertFiniteControls()
        dial("ROOT", (65 - Chamber.MIDI_MIN) / 48f)
        dial("VELOCITY", .61f)
        dial("STRIKE", .64f)
        dial("DIVIDE", .77f)
        val expected = original.copy(midi = 65, velocity = .61f,
            macros = original.macros + mapOf("STRIKE" to .64f, "DIVIDE" to .77f))

        // Voice changes and disposal must retain more than the selected preset name.
        press("AQUARIUM")
        press("HYBRID")
        compose.runOnIdle { generation++ }
        pump()
        assertEquals((65 - Chamber.MIDI_MIN) / 48f, progress("ROOT"), .00001f)
        assertEquals(.61f, progress("VELOCITY"), .00001f)
        assertEquals(.64f, progress("STRIKE"), .00001f)
        compose.runOnIdle {
            assertEquals(expected, SynthDrafts.read(settings).filterIsInstance<ChamberPatch>().single())
        }
        assertFiniteControls()

        press("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY CHAMBER")
        pump()
        press("SAVE")
        waitFor("saved CHAMBER room", timeoutMillis = 30_000) {
            UserPresets.read(root).any { it.name == "MY CHAMBER" }
        }
        val saved = UserPresets.read(root).single { it.name == "MY CHAMBER" }.patch as ChamberPatch
        assertEquals(expected.copy(name = "MY CHAMBER"), saved)

        press("SEND TO PAD")
        press("A01, EMPTY")
        waitFor("the finite CHAMBER pad", timeoutMillis = 180_000) {
            KitBuilderModel.open(kitDir).pad(1) != null
        }
        val pad = KitBuilderModel.open(kitDir).pad(1)!!
        val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        val placed = recipe.patch as ChamberPatch
        assertEquals(saved.copy(name = placed.name), placed)
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertTrue(pad.oneShot)
        assertEquals(null, recipe.fx)
        assertEquals("65", pad.source["rootMidi"])
        assertEquals("authored", pad.source["rootOrigin"])
        assertTrue(File(kitDir, pad.sampleFile).length() > 44)
    }
}
