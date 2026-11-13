package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.shell.SynthDrafts
import com.snipsnap.synth.TrefoilHeld
import com.snipsnap.synth.TrefoilPatch
import com.snipsnap.synth.TrefoilPlayback
import com.snipsnap.synth.TrefoilVoice
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The instrument sheet must offer the same key-lift audition as the held source. */
@RunWith(AndroidJUnit4::class)
class TrefoilHeldInstrumentSheetTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val directories = mutableListOf<File>()

    @After
    fun cleanUp() {
        appScope.cancel()
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun heldInstrumentSheetOffersThePreparedPressAndReleaseAudition() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = File(cache, "trefoil-held-sheet-${System.nanoTime()}").apply {
            mkdirs()
            directories += this
        }
        val patch = TrefoilPatch(
            "HELD SHEET", TrefoilVoice.WEAVE, TrefoilHeld.defaults(TrefoilVoice.WEAVE),
            model = 2, playback = TrefoilPlayback.HELD,
        )
        val settings = mutableMapOf("engine" to "TREFOIL", "voice" to "WEAVE")
        SynthDrafts.write(settings, listOf(patch))
        setPhoneContent {
            SynthScreen(
                entry = null, shelfRoot = root, onToast = {}, onKitUpdated = {},
                onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> },
                onOpenSurface = {}, appScope = appScope, settings = settings,
            )
        }
        val make = requireNotNull(button("MAKE INSTRUMENT").fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.OnClick)?.action)
        compose.runOnIdle { assertTrue(make()) }
        pump(16)

        // Its controller may still be preparing. The gesture surface must exist
        // immediately, so this regression does not wait on sound-render timing.
        button("INSTRUMENT AUDITION").assertExists()
        button("PREVIEW").assertDoesNotExist()
    }
}
