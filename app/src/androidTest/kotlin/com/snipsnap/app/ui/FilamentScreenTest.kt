package com.snipsnap.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.Copy
import com.snipsnap.shell.PadBanks
import com.snipsnap.shell.UserPresets
import com.snipsnap.shell.FilamentPadMaker
import com.snipsnap.synth.Eq
import com.snipsnap.synth.Filament
import com.snipsnap.synth.FilamentConfig
import com.snipsnap.synth.FilamentPatch
import com.snipsnap.synth.FilamentPresets
import com.snipsnap.synth.FilamentProfile
import com.snipsnap.synth.FilamentVoice
import com.snipsnap.synth.FxChain
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

/** FILAMENT's phone adapter must edit the saved recipe without replacing its strand geometry. */
@RunWith(AndroidJUnit4::class)
class FilamentScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val roots = mutableListOf<File>()
    private val settings = mutableMapOf("engine" to "FILAMENT")
    private var currentEntry by mutableStateOf<KitShelf.Entry?>(null)
    private var screenVisible by mutableStateOf(true)
    private val updatedKit = AtomicReference<Kit?>(null)
    private val latestToast = AtomicReference<String?>(null)

    @After
    fun cleanUp() {
        appScope.cancel()
        roots.forEach { it.deleteRecursively() }
    }

    private fun shelfRoot(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cache, "filament-screen-test-${System.nanoTime()}").apply {
            check(mkdirs())
            roots += this
        }
    }

    private fun show(root: File, entry: KitShelf.Entry? = null) {
        currentEntry = entry
        setPhoneContent {
            if (screenVisible) SynthScreen(
                entry = currentEntry,
                shelfRoot = root,
                onToast = { latestToast.set(it) },
                onKitUpdated = { kit ->
                    updatedKit.set(kit)
                    currentEntry = currentEntry?.copy(kit = kit)
                },
                onShelfAssetWritten = {},
                onDroneToLoop = { _, _, _ -> },
                onOpenSurface = {},
                appScope = appScope,
                settings = settings,
            )
        }
    }

    private fun leaveAndReturn(entry: KitShelf.Entry? = currentEntry) {
        compose.runOnIdle { screenVisible = false }
        // One pump can return while a cancelled render still holds the last
        // frame. The control has to actually leave; a visible engine label
        // still fails this wait.
        waitFor("the hidden bench to drop ENGINE FILAMENT") {
            compose.onAllNodesWithContentDescription("ENGINE FILAMENT. CHOOSE ENGINE")
                .fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle {
            currentEntry = entry
            screenVisible = true
        }
        pump()
    }

    // Invoke the same accessibility actions as the other SYNTH suites: controls
    // below the fold must not wait for animated scrolling on the frozen clock.
    private fun activate(label: String) {
        val node = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
        assertFalse("$label is disabled", node.config.getOrNull(SemanticsProperties.Disabled) != null)
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action ?: error("$label has no click action")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump(16)
    }

    // A YOURS chip is a combinedClickable (tap adopts, hold forgets), so its
    // name is the label of its click action, not a content description: the
    // factory chips' tapeClick sets one and these do not. Find it by that label.
    private fun yours(name: String) = compose.onNode(
        SemanticsMatcher("click label YOUR PRESET $name") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "YOUR PRESET $name"
        },
        useUnmergedTree = true,
    )

    private fun activateYours(name: String) {
        val action = yours(name).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.action
            ?: error("YOUR PRESET $name has no click action")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump(16)
    }

    private fun slider(label: String) = compose.onNodeWithContentDescription(label, useUnmergedTree = true)

    private fun progress(label: String): Float = slider(label).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current ?: error("$label has no progress")

    private fun dial(label: String, value: Float) {
        val action = slider(label).fetchSemanticsNode().config.getOrNull(SemanticsActions.SetProgress)?.action
            ?: error("$label has no SetProgress action")
        compose.runOnIdle { assertTrue(action.invoke(value)) }
        pump(16)
    }

    /** FILAMENT stores its complete current recipe in the generic draft bag. */
    private fun currentPatch(voice: FilamentVoice): FilamentPatch? = runCatching {
        settings["recipe.FILAMENT.${voice.name}"]?.let(FilamentPatch::fromJsonText)
    }.getOrNull()

    private fun assertGeometry(expected: FilamentPatch, actual: FilamentPatch) {
        assertEquals(expected.voice, actual.voice)
        assertEquals(expected.midi, actual.midi)
        assertEquals(expected.velocity, actual.velocity, 0f)
        assertEquals(expected.config, actual.config)
        assertEquals(expected.model, actual.model)
    }

    private fun assertWav(recipe: PadRecipe, file: File) {
        val expected = ByteArrayOutputStream().also { WavWriter.write(it, recipe.render()) }.toByteArray()
        assertArrayEquals("the saved WAV must render the complete native recipe", expected, file.readBytes())
    }

    private fun authoredSource(midi: Int) = mapOf(
        "from" to "SYNTH", "rootMidi" to midi.toString(),
        "rootNote" to Scales.nameOf(midi), "rootOrigin" to "authored",
    )

    private fun awaitUpdatedKit(what: String) {
        waitFor(what, timeoutMillis = 180_000) {
            val toast = latestToast.get()
            assertFalse("$what failed: $toast", toast == Copy.SEND_FAILED || toast == Copy.SYNTH_PAD_REFUSED)
            updatedKit.get() != null
        }
    }

    @Test
    fun three_voices_expose_six_controls_and_all_twelve_model_three_presets() {
        val old = FilamentPatch(
            "OLDER STRAND", FilamentVoice.THREAD,
            Filament.defaults(FilamentVoice.THREAD) + ("INJECT" to .81f),
            midi = 84, velocity = .18f,
            config = FilamentConfig(seed = 123, profile = FilamentProfile.EARLY),
            model = Filament.ORIGINAL_MODEL,
        )
        settings["recipe.FILAMENT.THREAD"] = old.toJsonText()
        settings["macro.FILAMENT.THREAD.INJECT"] = ".01"
        show(shelfRoot())
        button("ENGINE FILAMENT. CHOOSE ENGINE").assertExists()
        // A complete current recipe wins over stale slider keys, then tapping a
        // factory sound must replace every field of that older draft.
        assertEquals(old, currentPatch(FilamentVoice.THREAD))
        assertEquals(.81f, progress("INJECT"), 0f)
        assertEquals(12, FilamentPresets.all().size)
        for (voice in FilamentVoice.entries) {
            activate(voice.name)
            for (macro in Filament.macrosFor(voice)) slider(macro.name).assertExists()
            slider("ROOT").assertExists()
            slider("VELOCITY").assertExists()
            for (preset in FilamentPresets.forVoice(voice)) {
                activate("PRESET ${preset.name}")
                waitFor("${preset.name} complete recipe") { currentPatch(voice) == preset }
                assertEquals(Filament.MODEL_VERSION, preset.model)
                assertEquals(preset.velocity, progress("VELOCITY"), 0f)
                assertEquals((preset.midi - Filament.MIN_MIDI) / 48f, progress("ROOT"), 0f)
            }
        }
        button("MAKE INSTRUMENT").assertExists()
        button("DRONE TO LOOP").assertDoesNotExist()
    }

    @Test
    fun model_one_yours_recipe_survives_edit_return_save_and_send() = yoursRecipeSurvives(Filament.ORIGINAL_MODEL)

    @Test
    fun model_two_yours_recipe_survives_edit_return_save_and_send() = yoursRecipeSurvives(Filament.CHARACTER_MODEL)

    @Test
    fun model_three_yours_recipe_survives_edit_return_save_and_send() = yoursRecipeSurvives(Filament.MODEL_VERSION)

    @Test
    fun name_only_factory_restore_and_tapping_yours_adopt_each_complete_recipe() {
        val root = shelfRoot()
        val factory = FilamentPresets.forVoice(FilamentVoice.PULSE).first {
            !Filament.isLoop(it.macros.getValue("HOLD")) && it.config.seed != 0
        }
        val mine = FilamentPatch(
            "MY REPLACEMENT", factory.voice,
            Filament.defaults(factory.voice) + mapOf("INJECT" to .11f, "LENGTH" to .12f),
            midi = 67, velocity = .23f,
            config = FilamentConfig(seed = Int.MAX_VALUE, profile = FilamentProfile.COIL, perfectRetention = true),
            model = Filament.ORIGINAL_MODEL,
        )
        UserPresets.save(root, mine, System.currentTimeMillis())
        settings["voice"] = factory.voiceName
        settings["preset.FILAMENT.${factory.voiceName}"] = factory.name
        show(root)
        waitFor("the name-only factory recipe") { currentPatch(factory.voice) == factory }
        for ((macro, value) in factory.macros) assertEquals(value, progress(macro), 0f)
        waitFor("the YOURS strip") {
            runCatching { yours(mine.name).fetchSemanticsNode() }.isSuccess
        }
        activateYours(mine.name)
        waitFor("YOURS replacing the factory draft") { currentPatch(mine.voice) == mine }
        for ((macro, value) in mine.macros) assertEquals(value, progress(macro), 0f)
        assertEquals(31f / 48f, progress("ROOT"), 0f)
        assertEquals(mine.velocity, progress("VELOCITY"), 0f)
        leaveAndReturn()
        assertEquals(mine, currentPatch(mine.voice))
    }

    @Test
    fun held_model_one_lands_as_a_loop_with_authored_root_without_tonal_assignment() {
        val root = shelfRoot()
        val dir = File(root, "FILAMENT LOOP").apply { check(mkdirs()) }
        val kit = Kit("FILAMENT LOOP", emptyList(), key = KeySpec.parse("Cmin"))
        KitStore.save(kit, dir)
        val patch = FilamentPresets.original().first { Filament.isLoop(it.macros.getValue("HOLD")) }.copy(
            name = "MY HELD STRAND", midi = 60, velocity = .43f,
            config = FilamentConfig(seed = 17, profile = FilamentProfile.EARLY),
        )
        settings["voice"] = patch.voiceName
        settings["recipe.FILAMENT.${patch.voiceName}"] = patch.toJsonText()
        show(root, KitShelf.Entry(dir, kit))
        assertEquals(patch, currentPatch(patch.voice))
        button("MAKE INSTRUMENT").assertDoesNotExist()
        activate("SEND TO PAD")
        activate("A01, EMPTY")
        awaitUpdatedKit("the held FILAMENT loop")
        val pad = checkNotNull(KitStore.load(dir).pad(1))
        val recipe = PadRecipe.fromJsonValue(checkNotNull(pad.recipe))
        val landed = recipe.patch as FilamentPatch
        assertEquals(patch.copy(name = landed.name), landed)
        assertEquals(DrumClass.LOOP, pad.drumClass)
        assertEquals(authoredSource(patch.midi), pad.source)
        assertEquals(0, pad.tuneCoarse)
        assertEquals(0, pad.tuneFine)
        assertTrue(File(dir, pad.sampleFile).length() > 44)
    }

    @Test
    fun current_thread_makes_a_three_root_instrument_with_its_attack_and_saved_source() = makeInstrument(FilamentVoice.THREAD)

    @Test
    fun current_pulse_makes_a_three_root_instrument_with_its_attack_and_saved_source() = makeInstrument(FilamentVoice.PULSE)

    @Test
    fun current_curl_makes_a_three_root_instrument_with_its_attack_and_saved_source() = makeInstrument(FilamentVoice.CURL)

    private fun makeInstrument(voice: FilamentVoice) {
        val root = shelfRoot()
        val source = FilamentPresets.forVoice(voice).first().copy(
            midi = 57, velocity = .7f,
            config = FilamentConfig(seed = 73, profile = FilamentProfile.LATE),
        )
        settings["voice"] = source.voiceName
        settings["recipe.FILAMENT.${source.voiceName}"] = source.toJsonText()
        show(root)
        activate("MAKE INSTRUMENT")
        slider("RELEASE").assertExists()
        slider("ATTACK").assertDoesNotExist()
        compose.onNodeWithText("C3–C5 · KEEPS THE ATTACK. HOLD A KEY TO SUSTAIN; LET GO TO FADE.").assertExists()
        dial("RELEASE", 1f)
        compose.onNodeWithText("RELEASE 1.50 s").assertExists()
        activate("MAKE")
        val instruments = File(root, KitShelf.INSTRUMENTS_DIR)
        waitFor("the three $voice roots and their source recipe", timeoutMillis = 180_000) {
            instruments.isDirectory && instruments.walkTopDown().any { it.name == FilamentPadMaker.PATCH_FILE }
        }
        val (_, instrument) = InstrumentStore.list(instruments).single()
        assertEquals(listOf(48, 60, 72), instrument.zones.map { it.rootNote })
        assertEquals(1.5f, instrument.release, 0f)
        assertTrue(instrument.zones.all { it.loopStartFrame > 0 })
        val recipe = instruments.walkTopDown().single { it.name == FilamentPadMaker.PATCH_FILE }
        val kept = FilamentPatch.fromJsonText(recipe.readText())
        assertEquals(source.copy(name = kept.name), kept)
        assertEquals(0f, kept.macros.getValue("HOLD"), 0f)
    }

    @Test
    fun same_frame_note_velocity_macro_and_navigation_keep_the_complete_final_recipe() {
        val source = FilamentPatch(
            "FAST EDIT", FilamentVoice.THREAD,
            Filament.defaults(FilamentVoice.THREAD) + ("LENGTH" to .12f),
            midi = 69, velocity = .63f,
            config = FilamentConfig(seed = Int.MIN_VALUE, profile = FilamentProfile.LATE, perfectRetention = true),
            model = Filament.CHARACTER_MODEL,
        )
        settings["voice"] = source.voiceName
        settings["recipe.FILAMENT.${source.voiceName}"] = source.toJsonText()
        show(shelfRoot())
        val root = slider("ROOT").fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        val velocity = slider("VELOCITY").fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        val bend = slider("BEND").fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        // No intervening recomposition or state effect: these are one burst of
        // edits followed immediately by navigation away from the bench.
        compose.runOnIdle {
            assertTrue(root.invoke(0f))
            assertTrue(velocity.invoke(.27f))
            assertTrue(bend.invoke(.92f))
            screenVisible = false
        }
        pump()
        val expected = source.copy(midi = 36, velocity = .27f, macros = source.macros + ("BEND" to .92f))
        assertEquals(expected, currentPatch(source.voice))
        compose.runOnIdle { screenVisible = true }
        pump()
        assertEquals(expected, currentPatch(source.voice))
        assertEquals(0f, progress("ROOT"), 0f)
        assertEquals(.27f, progress("VELOCITY"), 0f)
        assertEquals(.92f, progress("BEND"), 0f)
    }

    @Test
    fun fresh_default_macro_edit_and_same_frame_navigation_preserve_a_complete_recipe() {
        // Only the engine selector exists: no named preset or loaded source can
        // supply a draft for this first edit on the factory-default voice.
        show(shelfRoot())
        val bend = slider("BEND").fetchSemanticsNode().config[SemanticsActions.SetProgress].action!!
        compose.runOnIdle {
            assertTrue(bend.invoke(.92f))
            screenVisible = false
        }
        pump()
        val expected = FilamentPatch(
            "Thread Filament", FilamentVoice.THREAD,
            Filament.defaults(FilamentVoice.THREAD) + ("BEND" to .92f),
        )
        assertEquals(expected, currentPatch(FilamentVoice.THREAD))
        compose.runOnIdle { screenVisible = true }
        pump()
        assertEquals(expected, currentPatch(FilamentVoice.THREAD))
        for ((macro, value) in expected.macros) assertEquals(value, progress(macro), 0f)
        assertEquals(.25f, progress("ROOT"), 0f)
        assertEquals(1f, progress("VELOCITY"), 0f)
    }

    @Test
    fun fresh_default_scramble_and_same_frame_navigation_preserve_all_controls() {
        show(shelfRoot())
        val scramble = button("SCRAMBLE").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            assertTrue(scramble.invoke())
            screenVisible = false
        }
        pump()
        val scrambled = checkNotNull(currentPatch(FilamentVoice.THREAD))
        assertGeometry(FilamentPatch("Thread Filament", FilamentVoice.THREAD, emptyMap()), scrambled)
        assertFalse(Filament.defaults(FilamentVoice.THREAD) == scrambled.macros)
        assertTrue(scrambled.macros.values.all { it.isFinite() && it in 0f..1f })
        assertEquals(0f, scrambled.macros.getValue("HOLD"), 0f)
        compose.runOnIdle { screenVisible = true }
        pump()
        assertEquals(scrambled, currentPatch(FilamentVoice.THREAD))
        for ((macro, value) in scrambled.macros) assertEquals(value, progress(macro), 0f)
    }

    private fun yoursRecipeSurvives(model: Int) {
        val root = shelfRoot()
        val dir = File(root, "FILAMENT KIT").apply { check(mkdirs()) }
        val kit = Kit("FILAMENT KIT", emptyList(), key = KeySpec.parse("Cmin"))
        KitStore.save(kit, dir)
        val source = FilamentPatch(
            "MY STRAND", FilamentVoice.CURL,
            Filament.defaults(FilamentVoice.CURL) + mapOf("INJECT" to .21f, "LENGTH" to .12f, "HOLD" to 0f),
            midi = 67, velocity = .41f,
            config = FilamentConfig(seed = Int.MIN_VALUE + model, profile = FilamentProfile.COIL, perfectRetention = true),
            model = model,
        )
        UserPresets.save(root, source, System.currentTimeMillis())
        // Recall a YOURS name with no in-memory patch or macro bag. The shelf's
        // asynchronous adoption must supply both the geometry and the controls.
        settings["voice"] = source.voiceName
        settings["preset.FILAMENT.${source.voiceName}"] = source.name
        show(root, KitShelf.Entry(dir, kit))
        waitFor("the complete YOURS strand") { currentPatch(source.voice) == source }
        assertEquals(31f / 48f, progress("ROOT"), 0f)
        assertEquals(source.velocity, progress("VELOCITY"), 0f)

        activate("SCRAMBLE")
        waitFor("scrambled FILAMENT controls") { currentPatch(source.voice)?.macros != source.macros }
        assertGeometry(source, checkNotNull(currentPatch(source.voice)))
        dial("INJECT", .83f)
        dial("LENGTH", .16f)
        dial("ROOT", 0f)
        // These assertions read the external navigation bag, which is written
        // by a LaunchedEffect after the live edit and its recomposition. One
        // fixed frame pump does not establish that this publication completed.
        waitFor("the low ROOT edit in the complete FILAMENT draft") {
            currentPatch(source.voice)?.midi == Filament.MIN_MIDI
        }
        assertEquals(Filament.MIN_MIDI, checkNotNull(currentPatch(source.voice)).midi)
        dial("ROOT", 1f)
        waitFor("the high ROOT edit in the complete FILAMENT draft") {
            currentPatch(source.voice)?.midi == Filament.MAX_MIDI
        }
        assertEquals(Filament.MAX_MIDI, checkNotNull(currentPatch(source.voice)).midi)
        dial("ROOT", .75f) // MIDI 72; changing the note cannot replace the saved strand roster.
        waitFor("the middle ROOT edit in the complete FILAMENT draft") {
            currentPatch(source.voice)?.midi == 72
        }
        dial("VELOCITY", .37f)
        waitFor("the final performance and controls in the complete FILAMENT draft") {
            currentPatch(source.voice)?.let { patch ->
                patch.midi == 72 && patch.velocity == .37f &&
                    patch.macros["INJECT"] == .83f && patch.macros["LENGTH"] == .16f
            } == true
        }
        val edited = checkNotNull(currentPatch(source.voice))
        assertGeometry(source.copy(midi = 72, velocity = .37f), edited)
        assertEquals(.83f, edited.macros.getValue("INJECT"), 0f)
        assertEquals(.16f, edited.macros.getValue("LENGTH"), 0f)
        assertEquals(0f, edited.macros.getValue("HOLD"), 0f)
        assertFalse(settings.containsKey("preset.FILAMENT.${source.voiceName}"))

        // A voice switch and a real unmount each rebuild the current recipe from
        // the production navigation bag, with no preset highlight to help it.
        activate("THREAD")
        activate("CURL")
        leaveAndReturn()
        button("ENGINE FILAMENT. CHOOSE ENGINE").assertExists()
        assertEquals(edited, currentPatch(source.voice))
        assertEquals(.75f, progress("ROOT"), 0f)
        assertEquals(.37f, progress("VELOCITY"), 0f)
        for ((macro, value) in edited.macros) assertEquals(value, progress(macro), 0f)

        activate("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("MY KEPT STRAND")
        pump()
        activate("SAVE")
        waitFor("the edited strand on the preset shelf") {
            runCatching { UserPresets.read(root).any { it.name == "MY KEPT STRAND" } }.getOrDefault(false)
        }
        val saved = UserPresets.read(root).single { it.name == "MY KEPT STRAND" }.patch
        assertEquals(edited.copy(name = "MY KEPT STRAND"), saved)

        activate("SEND TO PAD")
        activate("A01, EMPTY")
        awaitUpdatedKit("the native FILAMENT pad")
        val landedKit = KitStore.load(dir)
        val pad = checkNotNull(landedKit.pad(1))
        val recipe = PadRecipe.fromJsonValue(checkNotNull(pad.recipe))
        val landed = recipe.patch as FilamentPatch
        assertEquals(edited.copy(name = landed.name), landed)
        assertEquals(DrumClass.TONAL, pad.drumClass)
        assertEquals(authoredSource(72), pad.source)
        assertEquals(0, pad.tuneCoarse)
        assertEquals(0, pad.tuneFine)
        assertEquals(null, recipe.fx)
        assertWav(recipe, File(dir, pad.sampleFile))
    }

    @Test
    fun reopen_edit_and_update_keep_the_saved_model_processing_and_source_pad() {
        val root = shelfRoot()
        val dir = File(root, "KEPT FILAMENT").apply { check(mkdirs()) }
        val patch = FilamentPatch(
            "KEPT STRAND", FilamentVoice.PULSE,
            Filament.defaults(FilamentVoice.PULSE) + mapOf("INJECT" to .23f, "LENGTH" to .1f),
            midi = 57, velocity = .43f,
            config = FilamentConfig(seed = Int.MAX_VALUE, profile = FilamentProfile.LATE, perfectRetention = true),
            model = Filament.CHARACTER_MODEL,
        )
        val recipe = PadRecipe(
            patch = patch,
            fx = FxChain(reverse = true, eq = Eq.defaults() + ("AIR" to .7f)),
            treatment = "KEPT RACK", amount = .42f, alias = true,
        )
        val pad = KitPad(
            slot = 17, sampleFile = "kept.wav", displayName = "MY FILAMENT",
            drumClass = DrumClass.TONAL, colorHex = "#123456", level = .63f,
            pan = .2f, tuneCoarse = 3, tuneFine = -7, muteGroup = 7, oneShot = false,
            source = mapOf("origin" to "imported strand", "rawSha256" to "previous-wave-fingerprint"),
            recipe = recipe.toJsonValue(), attack = .13f, decay = .7f,
        )
        val kit = Kit("KEPT FILAMENT", listOf(pad))
        val sample = WavWriter.write(File(dir, pad.sampleFile), recipe.render())
        val before = sample.readBytes()
        KitStore.save(kit, dir)
        val stale = patch.copy(
            name = "OLD DRAFT", midi = 84, velocity = .1f,
            macros = Filament.defaults(patch.voice) + ("INJECT" to .01f),
            config = FilamentConfig(seed = -7, profile = FilamentProfile.EARLY),
            model = Filament.ORIGINAL_MODEL,
        )
        settings["recipe.FILAMENT.${patch.voiceName}"] = stale.toJsonText()
        settings["macro.FILAMENT.${patch.voiceName}.INJECT"] = ".01"
        settings["engine"] = "THUMP"
        show(root, KitShelf.Entry(dir, kit))
        activate("REOPEN PAD RECIPE")
        activate("REOPEN ${PadBanks.tag(pad.slot)} ${pad.displayName}")
        button("ENGINE FILAMENT. CHOOSE ENGINE").assertExists()
        assertEquals(patch, currentPatch(patch.voice))
        for ((macro, value) in patch.macros) assertEquals(value, progress(macro), 0f)
        dial("INJECT", .79f)
        dial("ROOT", .5f)
        dial("VELOCITY", .61f)
        val expectedPatch = patch.copy(
            macros = patch.macros + ("INJECT" to .79f), midi = 60, velocity = .61f,
        )
        val expectedRecipe = recipe.copy(patch = expectedPatch)
        val updateLabel = "UPDATE PAD ${PadBanks.tag(pad.slot)}"

        leaveAndReturn()
        button(updateLabel).assertExists()
        assertEquals(.5f, progress("ROOT"), 0f)
        assertEquals(.61f, progress("VELOCITY"), 0f)
        activate(updateLabel)
        awaitUpdatedKit("the updated FILAMENT source pad")
        val savedKit = KitStore.load(dir)
        val savedPad = checkNotNull(savedKit.pad(pad.slot))
        assertEquals(expectedRecipe, PadRecipe.fromJsonValue(checkNotNull(savedPad.recipe)))
        assertEquals(pad.copy(
            recipe = expectedRecipe.toJsonValue(),
            source = (pad.source - "rawSha256") + authoredSource(60),
        ), savedPad)
        assertFalse("the control edit must regenerate the WAV", before.contentEquals(sample.readBytes()))
        assertWav(expectedRecipe, sample)

        // The saved source identity is relative to its kit, never just a slot.
        val otherDir = File(root, "OTHER KIT").apply { check(mkdirs()) }
        val otherKit = Kit("OTHER KIT", emptyList())
        KitStore.save(otherKit, otherDir)
        leaveAndReturn(KitShelf.Entry(otherDir, otherKit))
        button(updateLabel).assertDoesNotExist()
        assertEquals(otherKit, KitStore.load(otherDir))
        assertEquals(savedKit, KitStore.load(dir))
    }
}
