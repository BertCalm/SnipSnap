package com.snipsnap.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.KitShelf
import com.snipsnap.app.theme.LocalScheme
import com.snipsnap.app.theme.tape
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.PadBanks
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmCapture
import com.snipsnap.synth.OhmExciter
import com.snipsnap.synth.OhmGesture
import com.snipsnap.synth.OhmOptions
import com.snipsnap.synth.OhmPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** The source editor binds whole recipes; these tests intentionally do not run synthesis. */
@RunWith(AndroidJUnit4::class)
class OhmControlsTest : ComposeScreenTest() {
    private var patch by mutableStateOf(OhmPatch("Source controls", Ohm.defaults(),
        midi = 57, velocity = .65f, seed = -905, gesture = OhmGesture.GATHER, model = 1))
    private var generation by mutableStateOf(0)

    private fun show(entry: KitShelf.Entry? = null) = setPhoneContent {
        key(generation) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OhmControls(patch, entry, LocalScheme.current.amber.tape) { patch = it }
            }
        }
    }

    private fun adjust(label: String, value: Float) {
        button(label).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        pump()
    }

    private fun click(label: String) {
        button(label).performSemanticsAction(SemanticsActions.OnClick) { it() }
        pump()
    }

    @Test
    fun extendedControlsRequireAnExplicitUpgradeAndPreserveTheCompletePerformance() {
        val original = patch
        show()
        adjust("VELOCITY", .4f)
        compose.runOnIdle { assertEquals(1, patch.model) }
        click("OHM EXTEND")
        click("OHM EXCITER BOW")
        adjust("HOLD", .6f)
        adjust("ELDER", .7f)
        adjust("FM RATIO", 1.75f)
        adjust("FM INDEX", 2.2f)
        adjust("GRAINS", .4f)
        adjust("GRAIN SIZE", .12f)
        adjust("FREEZE", .5f)
        val expected = original.copy(model = 3, velocity = .4f,
            macros = Ohm.defaults(3) + original.macros + ("HOLD" to .6f),
            options = OhmOptions(exciter = OhmExciter.BOW, elder = .7f, grains = .4f, freeze = .5f,
                fmRatio = 1.75f, fmIndex = 2.2f, grainSeconds = .12f))
        compose.runOnIdle {
            assertEquals(expected, patch)
            patch = OhmPatch.fromJsonText(patch.toJsonText())
            generation++
        }
        pump()
        button("OHM EXCITER BOW").assertIsSelected()
        button("OHM GATHER").assertIsSelected()
        compose.runOnIdle { assertEquals(expected, patch) }
    }

    @Test
    fun exciterSwitchesKeepTheEmbeddedCaptureUntilThePlayerClearsIt() {
        val capture = OhmCapture(FloatArray(11_025) { (.25 * sin(2 * PI * 731 * it / 44_100)).toFloat() }, "OWN HIT")
        patch = OhmPatch("Captured family", Ohm.defaults(3), model = 3,
            options = OhmOptions(exciter = OhmExciter.CAPTURED, capture = capture))
        show()
        click("OHM EXCITER PLUCK")
        adjust("ELDER", .35f)
        click("OHM EXCITER BOW")
        click("OHM EXCITER CAPTURED")
        compose.runOnIdle {
            assertEquals(capture, patch.options.capture)
            assertEquals(OhmExciter.CAPTURED, patch.options.exciter)
            assertEquals(patch, OhmPatch.fromJsonText(patch.toJsonText()))
        }
        click("CLEAR EXCITER")
        compose.runOnIdle {
            assertNull(patch.options.capture)
            assertEquals(OhmExciter.PLUCK, patch.options.exciter)
            assertEquals(.35f, patch.options.elder)
        }
        button("OHM EXCITER CAPTURED").assertIsNotEnabled()
    }

    @Test
    fun fromPadEmbedsAnAttackThatSurvivesDeletingItsSourceFile() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "ohm-capture-ui-${System.nanoTime()}").apply { check(mkdirs()) }
        try {
            val sample = File(root, "OwnHit.wav")
            sample.outputStream().use { out ->
                WavWriter.write(out, Snip(FloatArray(22_050) { i ->
                    (.3 * sin(2 * PI * 731 * i / 44_100) * (1.0 - i / 22_050.0)).toFloat()
                }, 1, 44_100))
            }
            val entry = KitShelf.Entry(root, Kit("Capture source", listOf(KitPad(1, sample.name, "OWN HIT"))))
            patch = OhmPatch("Captured source", Ohm.defaults(3), model = 3, midi = 57, seed = -905)
            show(entry)
            click("OHM EXCITER FROM PAD")
            click("${PadBanks.tag(1)} · OWN HIT")
            waitFor("the captured pad attack") { patch.options.capture != null }
            compose.runOnIdle {
                assertEquals(OhmExciter.CAPTURED, patch.options.exciter)
                assertEquals("OWN HIT", patch.options.capture?.label)
                assertEquals(57, patch.midi)
                assertEquals(-905, patch.seed)
                val saved = patch.toJsonText()
                assertEquals(true, sample.delete())
                assertEquals(patch, OhmPatch.fromJsonText(saved))
                assertEquals(11_025, patch.options.capture?.frameCount)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
