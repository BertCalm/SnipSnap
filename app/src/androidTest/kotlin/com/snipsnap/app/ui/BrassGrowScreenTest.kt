package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.synth.Embouchure
import com.snipsnap.synth.EmbouchurePatch
import com.snipsnap.synth.EmbouchureVoice
import com.snipsnap.shell.UserPresets
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The actual four-control panel, including leaving SYNTH after editing its preset. */
@RunWith(AndroidJUnit4::class)
class BrassGrowScreenTest {
    @get:Rule val compose = createComposeRule()
    private val visible = mutableStateOf(true)
    private lateinit var shelf: File

    @Before fun prepareShelf() {
        shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "brass-grow-ui-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun removeShelf() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        shelf.deleteRecursively()
    }

    private fun open(
        settings: MutableMap<String, String>,
        presetReader: ((File) -> Pair<List<UserPresets.Saved>, List<UserPresets.Binned>>)? = null,
    ) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val reader = presetReader ?: LocalSynthPresetReader.current
            CompositionLocalProvider(LocalSynthPresetReader provides reader) {
                if (visible.value) SynthScreen(
                    entry = null,
                    shelfRoot = shelf,
                    onToast = {},
                    onKitUpdated = {},
                    onShelfAssetWritten = {},
                    onDroneToLoop = { _, _, _ -> },
                    onOpenSurface = {},
                    appScope = scope,
                    settings = settings,
                )
            }
        }
    }

    private fun reenter() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
    }

    @Test fun editedGrowRetainsItsModelControlsAndPlayerRecipeOnReentry() {
        val settings = mutableMapOf(
            "engine" to "EMBOUCHURE", "voice" to "BRASS",
            "preset.EMBOUCHURE.BRASS" to "Brass GROW Held",
        )
        open(settings)
        compose.onNodeWithContentDescription("GROW").assertExists()
        compose.onNodeWithContentDescription("EFFORT").assertDoesNotExist()
        compose.onNodeWithContentDescription("GROW").performSemanticsAction(SemanticsActions.SetProgress) {
            it(.25f)
        }
        compose.runOnIdle {
            val patch = EmbouchurePatch.fromJsonText(settings.getValue("patch.EMBOUCHURE.BRASS"))
            assertEquals(Embouchure.BRASS_GROW_MODEL, patch.model)
            assertEquals(.25f, patch.macros.getValue("GROW"), 0f)
            assertEquals(.75f, patch.velocity, 0f)
            assertEquals(1f, patch.macros.getValue("HOLD"), 0f)
            assertEquals(setOf("TUNE", "GROW", "LENGTH", "HOLD"), patch.macros.keys)
            assertNull(settings["preset.EMBOUCHURE.BRASS"])
        }
        reenter()
        compose.onNodeWithContentDescription("GROW").assertExists()
        compose.onNodeWithContentDescription("EFFORT").assertDoesNotExist()
        compose.runOnIdle {
            val patch = EmbouchurePatch.fromJsonText(settings.getValue("patch.EMBOUCHURE.BRASS"))
            assertEquals(Embouchure.BRASS_GROW_MODEL, patch.model)
            assertEquals(.25f, patch.macros.getValue("GROW"), 0f)
            assertEquals(.75f, patch.velocity, 0f)
        }
    }

    @Test fun legacyPresetReplacesGrowControlsAndAnInvalidSavedRecipeIsIgnored() {
        val settings = mutableMapOf(
            "engine" to "EMBOUCHURE", "voice" to "BRASS",
            "preset.EMBOUCHURE.BRASS" to "Brass GROW",
            "patch.EMBOUCHURE.BRASS" to "{invalid saved patch",
        )
        open(settings)
        compose.onNodeWithContentDescription("GROW").assertExists()
        compose.onNodeWithContentDescription("PRESET Muted Bell").performSemanticsAction(SemanticsActions.OnClick) {
            it()
        }
        compose.onNodeWithContentDescription("GROW").assertDoesNotExist()
        compose.onNodeWithContentDescription("EFFORT").assertExists()
        compose.runOnIdle {
            val patch = EmbouchurePatch.fromJsonText(settings.getValue("patch.EMBOUCHURE.BRASS"))
            assertEquals(2, patch.model)
            assertFalse("GROW" in patch.macros)
            assertFalse("macro.EMBOUCHURE.BRASS.GROW" in settings)
            assertEquals(1f, patch.velocity, 0f)
        }
        reenter()
        compose.onNodeWithContentDescription("GROW").assertDoesNotExist()
        compose.onNodeWithContentDescription("EFFORT").assertExists()
    }

    @Test fun restoredTuneCannotExceedTheSavedMidiBasesRemainingRange() {
        val highest = Embouchure.midiRange(EmbouchureVoice.BRASS).last
        val patch = EmbouchurePatch(
            "HIGH GROW", EmbouchureVoice.BRASS,
            mapOf("TUNE" to 0f, "GROW" to 1f, "LENGTH" to .5f, "HOLD" to 0f),
            midi = highest, velocity = .75f, model = Embouchure.BRASS_GROW_MODEL,
        )
        val settings = mutableMapOf(
            "engine" to "EMBOUCHURE", "voice" to "BRASS",
            "patch.EMBOUCHURE.BRASS" to patch.toJsonText(),
            "macro.EMBOUCHURE.BRASS.TUNE" to ".5",
        )
        open(settings)
        compose.onNodeWithContentDescription("GROW").assertExists()
        compose.runOnIdle {
            val restored = EmbouchurePatch.fromJsonText(settings.getValue("patch.EMBOUCHURE.BRASS"))
            assertEquals(Embouchure.BRASS_GROW_MODEL, restored.model)
            assertEquals(highest, restored.midi)
            assertEquals(0f, restored.macros.getValue("TUNE"), 0f)
            assertEquals(0f, settings.getValue("macro.EMBOUCHURE.BRASS.TUNE").toFloat(), 0f)
        }
    }

    @Test fun asyncYoursRestoreKeepsGrowFromTheOriginalMacroBag() {
        val patch = EmbouchurePatch(
            "MY GROW", EmbouchureVoice.BRASS,
            mapOf("TUNE" to .5f, "GROW" to .2f, "LENGTH" to .5f, "HOLD" to 0f),
            velocity = .75f, model = Embouchure.BRASS_GROW_MODEL,
        )
        UserPresets.save(shelf, patch, System.currentTimeMillis())
        val settings = mutableMapOf(
            "engine" to "EMBOUCHURE", "voice" to "BRASS",
            "preset.EMBOUCHURE.BRASS" to patch.name,
            "macro.EMBOUCHURE.BRASS.GROW" to ".8",
        )
        open(settings)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("GROW").fetchSemanticsNodes().size == 1
        }
        compose.runOnIdle {
            val restored = EmbouchurePatch.fromJsonText(settings.getValue("patch.EMBOUCHURE.BRASS"))
            assertEquals(Embouchure.BRASS_GROW_MODEL, restored.model)
            assertEquals(.8f, restored.macros.getValue("GROW"), 0f)
            assertEquals(.75f, restored.velocity, 0f)
        }
    }

    @Test fun leavingDuringAnUnreadYoursPresetCannotPersistTemporaryLegacyState() {
        val patch = EmbouchurePatch(
            "MY WAITING GROW", EmbouchureVoice.BRASS,
            mapOf("TUNE" to .5f, "GROW" to .2f, "LENGTH" to .5f, "HOLD" to 0f),
            velocity = .75f, model = Embouchure.BRASS_GROW_MODEL,
        )
        UserPresets.save(shelf, patch, System.currentTimeMillis())
        val settings = mutableMapOf(
            "engine" to "EMBOUCHURE", "voice" to "BRASS",
            "preset.EMBOUCHURE.BRASS" to patch.name,
            "macro.EMBOUCHURE.BRASS.GROW" to ".8",
        )
        val originalSettings = settings.toMap()
        val reads = AtomicInteger()
        val releaseRead = CountDownLatch(1)
        try {
            open(settings) { root ->
                reads.incrementAndGet()
                check(releaseRead.await(30, TimeUnit.SECONDS)) { "preset-read test latch timed out" }
                UserPresets.read(root) to UserPresets.bin(root)
            }
            compose.waitUntil(timeoutMillis = 5_000) { reads.get() >= 1 }
            compose.runOnIdle {
                assertEquals(originalSettings, settings.toMap())
                assertFalse("patch.EMBOUCHURE.BRASS" in settings)
            }
            reenter()
            compose.waitUntil(timeoutMillis = 5_000) { reads.get() >= 2 }
            compose.runOnIdle {
                assertEquals(originalSettings, settings.toMap())
                assertFalse("patch.EMBOUCHURE.BRASS" in settings)
            }
            releaseRead.countDown()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithContentDescription("GROW").fetchSemanticsNodes().size == 1
            }
            compose.runOnIdle {
                val restored = EmbouchurePatch.fromJsonText(settings.getValue("patch.EMBOUCHURE.BRASS"))
                assertEquals(Embouchure.BRASS_GROW_MODEL, restored.model)
                assertEquals(.8f, restored.macros.getValue("GROW"), 0f)
                assertEquals(.75f, restored.velocity, 0f)
            }
        } finally {
            releaseRead.countDown()
        }
    }
}
