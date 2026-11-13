package com.snipsnap.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.shell.SplayDelivery
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.shell.UserPresets
import com.snipsnap.synth.SplayFactoryPresets
import com.snipsnap.synth.SplayPatch
import com.snipsnap.synth.SplayVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose/shelf restore contracts; these require Android, not a JVM-only helper runner. */
@RunWith(AndroidJUnit4::class)
class SplaySynthScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "splay-restore-${System.nanoTime()}").apply { mkdirs() }
    private val settings = ConcurrentHashMap<String, String>()
    private var generation by mutableStateOf(0)
    private var entry by mutableStateOf<KitShelf.Entry?>(null)

    @After fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(reader: (File) -> Pair<List<UserPresets.Saved>, List<UserPresets.Binned>>) {
        settings["engine"] = "SPLAY"
        settings["voice"] = "TONGUE"
        val dir = File(shelf, "kit").apply { mkdirs() }
        val kit = Kit(name = "SPLAY TEST", pads = emptyList())
        KitStore.save(kit, dir)
        entry = KitShelf.Entry(dir, kit)
        setPhoneContent {
            CompositionLocalProvider(LocalSynthPresetReader provides reader) {
                key(generation) {
                    SynthScreen(
                        entry = entry, shelfRoot = shelf, onToast = {},
                        onKitUpdated = { updated -> entry = entry?.copy(kit = updated) },
                        onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> }, onOpenSurface = {},
                        appScope = appScope, settings = settings,
                    )
                }
            }
        }
    }

    private fun source() = SplayPatch("SAVED OLD TONGUE", SplayVoice.TONGUE,
        mapOf("CONTACT" to .29f, "ENERGY" to .43f), midi = 49, velocity = .37f,
        seed = Int.MIN_VALUE, model = 1)

    private fun nameOnly(patch: SplayPatch) {
        UserPresets.save(shelf, patch, 1L)
        settings["preset.SPLAY.TONGUE"] = patch.name
        // Deliberately stale sliders: the complete saved recipe must win on adoption.
        settings["macro.SPLAY.TONGUE.CONTACT"] = ".91"
        settings["macro.SPLAY.TONGUE.TRACE"] = ".99"
        assertTrue(SynthDrafts.read(settings).none { it is SplayPatch })
    }

    private fun draft(): SplayPatch? = SynthDrafts.read(settings).filterIsInstance<SplayPatch>().singleOrNull()
    private fun activate(label: String) {
        button(label).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        pump(16)
    }
    private fun progress(label: String): Float = button(label).fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo].current

    @Test fun namedOnlyDelayedReadBlocksEditsThenRestoresCompleteLegacyRecipeAndNavigation() {
        val saved = source()
        nameOnly(saved)
        val reads = AtomicInteger()
        val release = CountDownLatch(1)
        try {
            show { root ->
                reads.incrementAndGet()
                check(release.await(30, TimeUnit.SECONDS)) { "SPLAY preset-read latch timed out" }
                UserPresets.read(root) to UserPresets.bin(root)
            }
            compose.waitUntil(timeoutMillis = 5_000) { reads.get() >= 1 }
            assertNull(currentRootOrNull())
            button("CONTACT").assertDoesNotExist()
            button("MAKE INSTRUMENT").assertDoesNotExist()
            for (action in listOf("RANDOMISE", "SAVE PRESET", "SEND TO PAD", "SPREAD ACROSS PADS", "AUDITION")) {
                button(action).assertIsNotEnabled()
            }
            button("PRESET ${SplayFactoryPresets.all().first().name}").assertIsEnabled()
            // A queued accessibility callback must obey the same guard as a fresh tap.
            for (action in listOf("RANDOMISE", "SAVE PRESET", "SEND TO PAD")) {
                button(action).performSemanticsAction(SemanticsActions.OnClick) { it() }
                pump(16)
            }
            assertNull(draft())
            assertEquals(saved.name, settings["preset.SPLAY.TONGUE"])
            assertEquals(".91", settings["macro.SPLAY.TONGUE.CONTACT"])
            release.countDown()
            val restored = saved.copy(macros = SplayDelivery.controls(saved))
            waitFor("complete named-only SPLAY recipe") { draft() == restored }
            assertRoot(49)
            button("MAKE INSTRUMENT").assertIsEnabled()
            assertEquals(.29f, progress("CONTACT"), 1e-6f)
            assertEquals(.55f, progress("TRACE"), 1e-6f)
            button("CONTACT").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.31f)) }
            pump(16)
            val edited = restored.copy(macros = restored.macros + ("CONTACT" to .31f))
            assertEquals(edited, draft())
            compose.runOnIdle { generation++ }
            pump(32)
            waitFor("edited SPLAY after navigation") { draft() == edited }
            assertRoot(49)
            assertEquals(.31f, progress("CONTACT"), 1e-6f)
            assertEquals(saved, UserPresets.read(shelf).single().patch)
        } finally {
            release.countDown()
        }
    }

    @Test fun failedReadKeepsNamedRecipeProtectedUntilExplicitFactoryChoice() {
        val saved = source()
        nameOnly(saved)
        show { error("fixture shelf read failed") }
        waitFor("failed SPLAY restore status", timeoutMillis = 5_000) {
            compose.onAllNodesWithText("SAVED SPLAY COULD NOT OPEN. CHOOSE A PRESET.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(currentRootOrNull())
        assertNull(draft())
        button("SAVE PRESET").assertIsNotEnabled()
        button("CONTACT").assertDoesNotExist()
        button("MAKE INSTRUMENT").assertDoesNotExist()
        assertEquals(saved, UserPresets.read(shelf).single().patch)
        val factory = SplayFactoryPresets.all().first()
        activate("PRESET ${factory.name}")
        waitFor("explicit factory choice after failed read") { draft() == factory }
        assertRoot(factory.midi)
        button("SAVE PRESET").assertIsEnabled()
        button("MAKE INSTRUMENT").assertIsEnabled()
    }

    @Test fun factoryChoiceDuringDelayedReadIsNotOverwrittenByLateNamedRecipe() {
        nameOnly(source())
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        try {
            show { root ->
                reads.incrementAndGet()
                check(release.await(30, TimeUnit.SECONDS)) { "SPLAY preset-read latch timed out" }
                UserPresets.read(root) to UserPresets.bin(root)
            }
            compose.waitUntil(timeoutMillis = 5_000) { reads.get() >= 1 }
            val factory = SplayFactoryPresets.all().last()
            activate("PRESET ${factory.name}")
            waitFor("factory choice while read is delayed") { draft() == factory }
            release.countDown()
            // The saved chip appearing proves the async read has published its shelf.
            waitFor("late shelf read") {
                compose.onAllNodesWithText(source().name, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(factory, draft())
            assertEquals(factory.name, settings["preset.SPLAY.TONGUE"])
        } finally {
            release.countDown()
        }
    }
}
