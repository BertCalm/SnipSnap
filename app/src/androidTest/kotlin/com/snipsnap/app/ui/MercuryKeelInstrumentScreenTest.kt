package com.snipsnap.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.synth.Arco
import com.snipsnap.synth.ArcoVoice
import com.snipsnap.synth.Bore
import com.snipsnap.synth.BoreVoice
import com.snipsnap.synth.Keel
import com.snipsnap.synth.KeelVoice
import com.snipsnap.synth.Mercury
import com.snipsnap.synth.MercuryVoice
import com.snipsnap.synth.Thaw
import com.snipsnap.synth.ThawVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * MERCURY, KEEL, ARCO, BORE and the shared loop maker's engines reach MAKE INSTRUMENT. Their makers' zones,
 * layout and export are pinned by the :shell tests; this proves the phone opens each one's sheet
 * with its own note, without rendering every zone on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class MercuryKeelInstrumentScreenTest : ComposeScreenTest() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings = ConcurrentHashMap<String, String>()
    private val shelf = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "mercury-keel-instrument-${System.nanoTime()}").apply { mkdirs() }

    @After
    fun cleanUp() {
        appScope.cancel()
        shelf.deleteRecursively()
    }

    private fun show(engine: String, voice: String, macros: Map<String, Float>) {
        settings["engine"] = engine
        settings["voice"] = voice
        for ((macro, value) in macros) settings["macro.$engine.$voice.$macro"] = value.toString()
        setPhoneContent {
            SynthScreen(entry = null, shelfRoot = shelf, onToast = {}, onKitUpdated = {},
                onShelfAssetWritten = {}, onDroneToLoop = { _, _, _ -> },
                onOpenSurface = {}, appScope = appScope, settings = settings)
        }
    }

    private fun click(label: String) {
        val node = compose.onNodeWithContentDescription(label).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action ?: error("no click for $label")
        compose.runOnIdle { assertTrue(action.invoke()) }
        pump()
    }

    @Test
    fun mercury_opens_its_held_instrument_sheet() {
        show("MERCURY", MercuryVoice.SING.name, Mercury.defaults(MercuryVoice.SING))
        click("MAKE INSTRUMENT")
        compose.onNodeWithText("THE GLASS'S HOLD LOOP", substring = true).assertExists()
        compose.onNodeWithText("RELEASE", substring = true).assertExists()
    }

    @Test
    fun keel_opens_its_held_instrument_sheet() {
        val voice = KeelVoice.entries.first()
        show("KEEL", voice.name, Keel.defaults(voice))
        click("MAKE INSTRUMENT")
        compose.onNodeWithText("THEN HOLDS THE SETTLED BASS.", substring = true).assertExists()
        compose.onNodeWithText("RELEASE", substring = true).assertExists()
    }

    @Test
    fun arco_opens_its_own_held_instrument_sheet() {
        show("ARCO", ArcoVoice.CELLO.name, Arco.defaults(ArcoVoice.CELLO))
        click("MAKE INSTRUMENT")
        compose.onNodeWithText("THE BOW'S OWN STROKE", substring = true).assertExists()
        compose.onNodeWithText("RELEASE", substring = true).assertExists()
    }

    @Test
    fun bore_opens_its_own_held_instrument_sheet() {
        show("BORE", BoreVoice.FLUTE.name, Bore.defaults(BoreVoice.FLUTE))
        click("MAKE INSTRUMENT")
        compose.onNodeWithText("THE NOTE'S OWN BREATH", substring = true).assertExists()
        compose.onNodeWithText("RELEASE", substring = true).assertExists()
    }

    @Test
    fun a_shared_loop_engine_opens_its_held_instrument_sheet() {
        show("THAW", ThawVoice.BRITTLE.name, Thaw.defaults(ThawVoice.BRITTLE))
        click("MAKE INSTRUMENT")
        compose.onNodeWithText("THIS SOUND'S HOLD LOOP", substring = true).assertExists()
        compose.onNodeWithText("RELEASE", substring = true).assertExists()
    }
}
