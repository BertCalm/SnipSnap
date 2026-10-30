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
import com.snipsnap.synth.Tremor
import com.snipsnap.synth.TremorPatch
import com.snipsnap.synth.TremorPresets
import com.snipsnap.synth.TremorVoice
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

/** Real editor migration: an old sound never becomes the new renderer through a macro edit. */
@RunWith(AndroidJUnit4::class)
class TremorSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "tremor-editor-${System.nanoTime()}").apply { mkdirs() }
    private var generation by mutableIntStateOf(0)
    private var currentEntry by mutableStateOf<KitShelf.Entry?>(null)

    @After fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(
        voice: TremorVoice? = null,
        patch: TremorPatch? = null,
        kit: Boolean = false,
        reader: ((File) -> Pair<List<UserPresets.Saved>, List<UserPresets.Binned>>)? = null,
    ) {
        if (voice != null) {
            settings["engine"] = "TREMOR"
            settings["voice"] = voice.name
        }
        patch?.let { SynthDrafts.write(settings, listOf(it)) }
        if (kit) {
            val dir = File(shelf, "kit").apply { mkdirs() }
            val empty = Kit("TREMOR EDITOR", emptyList())
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

    private fun draft(voice: TremorVoice): TremorPatch? = SynthDrafts.read(settings)
        .filterIsInstance<TremorPatch>().firstOrNull { it.voice == voice }

    @Test fun opening_another_engine_does_not_create_tremor_work() {
        show()
        button("ENGINE THUMP. CHOOSE ENGINE").assertExists()
        assertEquals(emptyList<TremorPatch>(), SynthDrafts.read(settings).filterIsInstance<TremorPatch>())
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(emptyList<TremorPatch>(), SynthDrafts.read(settings).filterIsInstance<TremorPatch>())
    }

    @Test fun fresh_work_and_explicit_factory_choices_use_the_current_renderer() {
        show(TremorVoice.WIRE)
        waitFor("current Tremor draft") { draft(TremorVoice.WIRE)?.model == Tremor.MODEL }
        assertEquals(listOf(TremorVoice.WIRE), SynthDrafts.read(settings).filterIsInstance<TremorPatch>().map { it.voice })
        dial("CAGE", .73f)
        press("ROLL")
        waitFor("later visited Tremor default") { draft(TremorVoice.ROLL)?.model == Tremor.MODEL }
        assertEquals(setOf(TremorVoice.WIRE, TremorVoice.ROLL),
            SynthDrafts.read(settings).filterIsInstance<TremorPatch>().map { it.voice }.toSet())
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(Tremor.MODEL, draft(TremorVoice.ROLL)!!.model)
        assertEquals(Tremor.defaults(TremorVoice.ROLL), draft(TremorVoice.ROLL)!!.macros)
        press("WIRE")
        assertEquals(Tremor.MODEL, draft(TremorVoice.WIRE)!!.model)
        assertEquals(.73f, progress("CAGE"), 1e-6f)
        press("PRESET DRY CAGE")
        assertEquals(Tremor.MODEL, draft(TremorVoice.WIRE)!!.model)
        assertEquals(TremorPresets.forVoice(TremorVoice.WIRE).first().macros,
            draft(TremorVoice.WIRE)!!.macros)
    }

    @Test fun a_pre_model_macro_bag_stays_legacy_until_a_factory_is_explicitly_chosen() {
        settings["macro.TREMOR.ROLL.BEADS"] = ".72"
        show(TremorVoice.ROLL)
        waitFor("migrated legacy Tremor controls") { draft(TremorVoice.ROLL)?.model == Tremor.LEGACY_MODEL }
        assertEquals(.72f, progress("BEADS"), 1e-6f)
        dial("STRIKE", .61f)
        press("WIRE")
        press("ROLL")
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(Tremor.LEGACY_MODEL, draft(TremorVoice.ROLL)!!.model)
        assertEquals(.61f, progress("STRIKE"), 1e-6f)
        assertEquals(.72f, progress("BEADS"), 1e-6f)
        press("PRESET LIGHT BED")
        assertEquals(Tremor.MODEL, draft(TremorVoice.ROLL)!!.model)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(Tremor.MODEL, draft(TremorVoice.ROLL)!!.model)
    }

    @Test fun a_complete_recipe_beats_stale_macro_keys_and_keeps_its_saved_model() {
        val source = TremorPatch("SAVED WIRE", TremorVoice.WIRE,
            Tremor.defaults(TremorVoice.WIRE) + ("CAGE" to .31f), model = Tremor.MODEL)
        settings["macro.TREMOR.WIRE.CAGE"] = ".89"
        show(TremorVoice.WIRE, source)
        assertEquals(.31f, progress("CAGE"), 1e-6f)
        dial("CAGE", .47f)
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(Tremor.MODEL, draft(TremorVoice.WIRE)!!.model)
        assertEquals(.47f, progress("CAGE"), 1e-6f)
    }

    @Test fun a_pre_model_named_factory_keeps_its_renderer_until_it_is_tapped_again() {
        settings["preset.TREMOR.HIDE"] = "SOFT STRIKE"
        show(TremorVoice.HIDE)
        waitFor("named legacy Tremor factory") { draft(TremorVoice.HIDE)?.model == Tremor.LEGACY_MODEL }
        assertEquals(.12f, progress("STRIKE"), 1e-6f)
        assertEquals(.12f, progress("CAGE"), 1e-6f)
        press("PRESET SOFT STRIKE")
        assertEquals(Tremor.MODEL, draft(TremorVoice.HIDE)!!.model)
    }

    @Test fun legacy_sound_survives_edit_recreation_save_send_and_reopen() {
        val source = TremorPatch("OLD WIRE", TremorVoice.WIRE,
            Tremor.defaults(TremorVoice.WIRE), model = Tremor.LEGACY_MODEL)
        show(TremorVoice.WIRE, source, kit = true)
        dial("CAGE", .73f)
        dial("TUNE", .75f)
        press("HIDE")
        press("WIRE")
        compose.runOnIdle { generation++ }
        pump()
        assertEquals(Tremor.LEGACY_MODEL, draft(TremorVoice.WIRE)!!.model)
        assertEquals(.73f, progress("CAGE"), 1e-6f)
        assertEquals(.75f, progress("TUNE"), 1e-6f)
        press("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY OLD WIRE")
        pump()
        press("SAVE")
        waitFor("saved legacy Tremor") { UserPresets.read(shelf).any { it.name == "MY OLD WIRE" } }
        val saved = UserPresets.read(shelf).single { it.name == "MY OLD WIRE" }.patch as TremorPatch
        assertEquals(Tremor.LEGACY_MODEL, saved.model)
        assertEquals(.73f, saved.macros.getValue("CAGE"), 1e-6f)
        press("SEND TO PAD")
        press("A01, EMPTY")
        val dir = requireNotNull(currentEntry).dir
        waitFor("legacy Tremor pad") { KitStore.load(dir).pad(1)?.recipe != null }
        val pad = requireNotNull(KitStore.load(dir).pad(1))
        val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
        val landed = recipe.patch as TremorPatch
        assertEquals(saved.copy(name = landed.name), landed)
        val expected = ByteArrayOutputStream().also { WavWriter.write(it, recipe.render()) }.toByteArray()
        assertArrayEquals(expected, File(dir, pad.sampleFile).readBytes())
        press("REOPEN PAD RECIPE")
        press("REOPEN A01 ${pad.displayName}")
        assertEquals(Tremor.LEGACY_MODEL, draft(TremorVoice.WIRE)!!.model)
        dial("STRIKE", .44f)
        assertEquals(Tremor.LEGACY_MODEL, draft(TremorVoice.WIRE)!!.model)
        assertEquals(.44f, draft(TremorVoice.WIRE)!!.macros.getValue("STRIKE"), 1e-6f)
    }

    @Test fun delayed_yours_restore_cannot_be_replaced_with_a_fresh_renderer() {
        val source = TremorPatch("DELAYED OLD WIRE", TremorVoice.WIRE,
            Tremor.defaults(TremorVoice.WIRE) + ("CAGE" to .23f), model = Tremor.LEGACY_MODEL)
        UserPresets.save(shelf, source, 1L)
        settings["preset.TREMOR.WIRE"] = source.name
        settings["macro.TREMOR.WIRE.CAGE"] = ".23"
        val reads = AtomicInteger()
        val release = CountDownLatch(1)
        try {
            show(TremorVoice.WIRE, reader = { root ->
                reads.incrementAndGet()
                check(release.await(30, TimeUnit.SECONDS)) { "Tremor preset-read latch timed out" }
                UserPresets.read(root) to UserPresets.bin(root)
            })
            compose.waitUntil(timeoutMillis = 5_000) { reads.get() >= 1 }
            button("CAGE").assertDoesNotExist()
            button("SCRAMBLE").assertIsNotEnabled()
            button("SAVE PRESET").assertIsNotEnabled()
            button("AUDITION").assertIsNotEnabled()
            button("PRESET DRY CAGE").assertIsEnabled()
            for (action in listOf("SCRAMBLE", "SAVE PRESET")) press(action)
            compose.runOnIdle {
                assertEquals(null, draft(TremorVoice.WIRE))
                assertEquals(source.name, settings["preset.TREMOR.WIRE"])
                assertEquals(".23", settings["macro.TREMOR.WIRE.CAGE"])
            }
            release.countDown()
            waitFor("delayed legacy Tremor") { draft(TremorVoice.WIRE)?.model == Tremor.LEGACY_MODEL }
            dial("CAGE", .83f)
            assertEquals(Tremor.LEGACY_MODEL, draft(TremorVoice.WIRE)!!.model)
            assertEquals(.83f, draft(TremorVoice.WIRE)!!.macros.getValue("CAGE"), 1e-6f)
            assertFalse(settings.containsKey("preset.TREMOR.WIRE"))
        } finally {
            release.countDown()
        }
    }
}
