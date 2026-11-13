package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.Osteria
import com.snipsnap.synth.OsteriaConfig
import com.snipsnap.synth.OsteriaStrum
import com.snipsnap.synth.OsteriaPresets
import com.snipsnap.synth.OsteriaPatch
import com.snipsnap.synth.OsteriaVoice
import java.io.File
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the actual SYNTH picker and its saved-recipe path. Slider and
 * button semantics are invoked directly, including below the fold, so the
 * frozen frame clock never waits on an animated scroll (ComposeScreenTest).
 * Listening and physical pad-release behavior remain phone checks.
 */
@RunWith(AndroidJUnit4::class)
class OsteriaSynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        appScope.cancel()
        roots.forEach { it.deleteRecursively() }
    }

    private fun shelfRoot(): File {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cache, "osteria-synth-test-${System.nanoTime()}").apply {
            mkdirs()
            roots += this
        }
    }

    private fun show(root: File) {
        setPhoneContent {
            SynthScreen(
                entry = null,
                shelfRoot = root,
                onToast = {},
                onKitUpdated = {},
                onShelfAssetWritten = {},
                onDroneToLoop = { _, _, _ -> },
                onOpenSurface = {},
                appScope = appScope,
            )
        }
    }

    private fun click(label: String) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick) { action ->
            assertTrue(action())
        }
        pump(16)
    }

    // The header's arrows step the engine ring; OSTERIA sits at its end, one step back from the first engine.
    private fun chooseOsteria() {
        repeat(128) {
            if (compose.onAllNodesWithContentDescription("ENGINE OSTERIA. CHOOSE ENGINE").fetchSemanticsNodes().isNotEmpty()) return
            click("PREVIOUS ENGINE")
        }
        error("the engine picker never reached OSTERIA")
    }

    private fun dial(label: String, target: Float) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertTrue(action(target))
        }
        pump(16)
    }

    private fun sliderValue(label: String): Float = compose.onNodeWithContentDescription(label)
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    @Test
    fun allSixFixedRootVoicesOfferFiniteLayeredInstrumentsWithoutReplacementRelease() {
        show(shelfRoot())
        chooseOsteria()
        for (label in listOf("NOTE", "BASS", "PLUCK", "REED", "PAD", "BOWED")) {
            click(label)
            click("MAKE INSTRUMENT")
            compose.onNodeWithText(com.snipsnap.shell.OsteriaPadMaker.INSTRUMENT_NOTE).assertExists()
            compose.onNodeWithText("SAVED SOURCE ENVELOPE · THREE TOUCH LAYERS").assertExists()
            val patch = OsteriaPresets.forVoice(OsteriaVoice.entries.first { it.name == (if (label == "NOTE") "QUARTET" else label) }).first()
            val spec = HeldSpec.Osteria(com.snipsnap.shell.OsteriaPadMaker.Spec(patch))
            assertTrue(!spec.hasAttack && !spec.hasRelease)
            assertTrue(!spec.shelfNote("Test").contains("holds while"))
            assertEquals(39, spec.zoneMidis.size * spec.progressPerZone)
            click("CANCEL")
        }
    }

    @Test
    fun pickerOffersAllSixVoicesAndReopensAnImportedBowedRecipe() {
        val root = shelfRoot()
        val original = OsteriaPatch(
            "IMPORTED BOW", OsteriaVoice.BOWED,
            Osteria.defaults(OsteriaVoice.BOWED) + ("MEMORY" to .87f),
            midi = 55, velocity = .43f,
            config = Osteria.configurationFor(OsteriaVoice.BOWED).copy(
                seed = 7901, seconds = 2.0, attackSeconds = .14, releaseSeconds = .25,
            ),
        )
        UserPresets.save(root, original, System.currentTimeMillis())
        show(root)
        chooseOsteria()
        for (label in listOf("NOTE", "BASS", "PLUCK", "REED", "PAD", "BOWED")) {
            button(label).assertExists()
            click(label)
            button(label).assertIsSelected()
        }
        waitFor("the imported bowed preset") {
            compose.onAllNodesWithContentDescription("YOUR PRESET IMPORTED BOW").fetchSemanticsNodes().isNotEmpty()
        }
        click("YOUR PRESET IMPORTED BOW")
        button("BOWED").assertIsSelected()
        assertEquals((55 - 36) / 48f, sliderValue("ROOT"), .0001f)
        assertEquals(.43f, sliderValue("VELOCITY"), .0001f)
        assertEquals(.87f, sliderValue("MEMORY"), .0001f)
        compose.onNodeWithContentDescription("ATTACK").assertExists()
        compose.onNodeWithContentDescription("RELEASE").assertExists()
    }

    @Test
    fun pickerOffersTheAcceptedNotePresetsAndSeparateArticulation() {
        show(shelfRoot())
        chooseOsteria()
        button("NOTE").assertIsSelected()
        assertReadsInFull("OSTERIA")
        compose.onNodeWithText("ONE NOTE · FOUR EVOLVING LAYERS").assertExists()
        for (name in listOf("Anchored Note", "Evolving Note", "Lingering Color")) {
            compose.onNodeWithContentDescription("PRESET $name").assertExists()
        }
        for (name in listOf("ROOT", "VELOCITY", "ATTACK", "RELEASE", "SHORE", "TENSION", "WANDER", "MEMORY", "ROOM", "HOLD")) {
            compose.onNodeWithContentDescription(name).assertExists()
        }
        dial("ROOT", 1f)
        assertEquals(1f, sliderValue("ROOT"), .0001f)
        dial("ROOT", 0f)
        assertEquals(0f, sliderValue("ROOT"), .0001f)
        compose.onNodeWithText("Rendered release · hold extends up to 8 s.").assertExists()
    }

    @Test
    fun editsAndEngineSwitchRetainTheSavedLayerAndMotionRecipe() {
        val root = shelfRoot()
        val original = OsteriaPatch(
            "CUSTOM NOTE", OsteriaVoice.QUARTET, Osteria.defaults(OsteriaVoice.QUARTET) + mapOf("ROOM" to .12f, "HOLD" to .3f),
            midi = 57, velocity = .65f,
            config = OsteriaConfig(
                initialShores = listOf(.2, 1.1, 2.3, 3.8),
                roleLevels = listOf(.8, .5, .3, .2),
                seed = 619, seconds = 1.0, tavernShore = 2.4, direction = -1,
                attackSeconds = .08, releaseSeconds = .55,
            ),
        )
        UserPresets.save(root, original, System.currentTimeMillis())
        show(root)
        chooseOsteria()
        waitFor("the saved Osteria preset") {
            compose.onAllNodesWithContentDescription("YOUR PRESET CUSTOM NOTE").fetchSemanticsNodes().isNotEmpty()
        }
        click("YOUR PRESET CUSTOM NOTE")
        dial("ROOT", .625f) // MIDI 66 within the explicit 36..84 register.
        dial("VELOCITY", .35f)
        dial("ATTACK", .75f)
        dial("RELEASE", 1f) // A one-second recipe must cap its release at .95s.
        assertEquals(1f, sliderValue("RELEASE"), .0001f)
        dial("RELEASE", .25f)
        click("RANDOMISE")
        assertEquals(.625f, sliderValue("ROOT"), .0001f)
        assertEquals(.35f, sliderValue("VELOCITY"), .0001f)
        assertEquals(.75f, sliderValue("ATTACK"), .0001f)
        assertEquals(.25f, sliderValue("RELEASE"), .0001f)
        dial("SHORE", .7f)
        click("NEXT ENGINE")
        chooseOsteria()
        assertEquals(.625f, sliderValue("ROOT"), .0001f)
        assertEquals(.35f, sliderValue("VELOCITY"), .0001f)
        assertEquals(.75f, sliderValue("ATTACK"), .0001f)
        assertEquals(.25f, sliderValue("RELEASE"), .0001f)
        click("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("TOUCH EDIT")
        click("SAVE")
        waitFor("the edited recipe on disk") { UserPresets.read(root).any { it.name == "TOUCH EDIT" } }
        val saved = UserPresets.read(root).single { it.name == "TOUCH EDIT" }.patch as OsteriaPatch
        assertEquals(66, saved.midi)
        assertEquals(.35f, saved.velocity, .0001f)
        assertEquals(.7f, saved.macros.getValue("SHORE"), .0001f)
        assertEquals(.12f, saved.macros.getValue("ROOM"), .0001f)
        assertEquals(.3f, saved.macros.getValue("HOLD"), .0001f)
        assertEquals(original.config.copy(attackSeconds = .005 * 200.0.pow(.75), releaseSeconds = .02 * 47.5.pow(.25)), saved.config)
        click("YOUR PRESET CUSTOM NOTE")
        assertEquals(.65f, sliderValue("VELOCITY"), .0001f)
        assertEquals((57 - 36) / 48f, sliderValue("ROOT"), .0001f)
    }

    @Test
    fun savedStrumReopensEditsHoldAndRootAndSavesWithoutOfferingChordKeygroups() {
        val root = shelfRoot()
        val original = OsteriaPresets.forVoice(OsteriaVoice.PLUCK).first().copy(
            name = "SAVED STRUM", midi = 60,
            strum = OsteriaStrum.Articulation(listOf(-5, 0, 3, 10), .17, OsteriaStrum.Direction.UP),
        )
        UserPresets.save(root, original, System.currentTimeMillis())
        show(root)
        chooseOsteria()
        click("PLUCK")
        waitFor("the saved strum") {
            compose.onAllNodesWithContentDescription("YOUR PRESET SAVED STRUM").fetchSemanticsNodes().isNotEmpty()
        }
        click("YOUR PRESET SAVED STRUM")
        compose.onNodeWithText("ONE STRIKE PER TONE · FINITE STRUM").assertExists()
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertDoesNotExist()
        dial("HOLD", .5f)
        dial("ROOT", 1f)
        click("SAVE PRESET")
        compose.onNode(hasSetTextAction()).performTextReplacement("EDITED STRUM")
        click("SAVE")
        waitFor("edited strum saved") { UserPresets.read(root).any { it.name == "EDITED STRUM" } }
        val saved = UserPresets.read(root).single { it.name == "EDITED STRUM" }.patch as OsteriaPatch
        assertEquals(original.strum, saved.strum)
        assertEquals(original.config, saved.config)
        assertEquals(74, saved.midi)
        assertEquals(.5f, saved.macros.getValue("HOLD"), .0001f)
        click("OSTERIA SINGLE NOTE")
        compose.onNodeWithContentDescription("MAKE INSTRUMENT").assertExists()
    }

    @Test
    fun legacyRecipesDoNotOfferUnsupportedArticulationEdits() {
        val root = shelfRoot()
        UserPresets.save(
            root,
            OsteriaPatch("PREVIOUS PHRASE", OsteriaVoice.QUARTET, Osteria.defaults(OsteriaVoice.QUARTET), config = OsteriaConfig(model = 1)),
            System.currentTimeMillis(),
        )
        show(root)
        chooseOsteria()
        waitFor("the legacy preset") {
            compose.onAllNodesWithContentDescription("YOUR PRESET PREVIOUS PHRASE").fetchSemanticsNodes().isNotEmpty()
        }
        click("YOUR PRESET PREVIOUS PHRASE")
        compose.onNodeWithText("LEGACY QUARTET PHRASE").assertExists()
        compose.onNodeWithText("Legacy articulation is fixed.").assertExists()
        compose.onNodeWithContentDescription("ATTACK").assertDoesNotExist()
        compose.onNodeWithContentDescription("RELEASE").assertDoesNotExist()
    }
}
