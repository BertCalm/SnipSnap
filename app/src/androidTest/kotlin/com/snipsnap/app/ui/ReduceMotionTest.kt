package com.snipsnap.app.ui

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.app.theme.rememberReduceMotion
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reduced motion follows the system's "Remove animations" while the app is
 * up. The flag used to be read once at the root, so turning animations off
 * while the app lived left it saying "animations on" and the busy bar's sweep
 * - which Compose itself skips to its end when the scale is 0 - pinned at
 * its end and drew nothing.
 *
 * The setting is changed the way a person's change reaches the app: through
 * the system, by the shell, with the composition already up. The test puts it
 * back in a `finally`, whatever happened, because a device left at scale 0
 * would make every later suite on it a test of reduced motion.
 */
@RunWith(AndroidJUnit4::class)
class ReduceMotionTest : ComposeScreenTest() {

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    /** Runs [command] as the shell user and returns what it printed. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun setScale(scale: String) {
        shell("settings put global animator_duration_scale $scale")
    }

    private fun probe() {
        setPhoneContent {
            val off = rememberReduceMotion()
            Box(Modifier.size(8.dp).testTag(if (off) OFF else ON))
        }
    }

    @Test
    fun the_flag_follows_the_system_setting_while_the_app_is_up() {
        val original = shell("settings get global animator_duration_scale").trim()
        try {
            setScale("1")
            probe()
            compose.onNodeWithTag(ON).assertExists()
            compose.onNodeWithTag(OFF).assertDoesNotExist()

            // "Remove animations" turned on with the composition up.
            setScale("0")
            waitFor("the flag to follow the system to animations off") { exists(OFF) }
            compose.onNodeWithTag(ON).assertDoesNotExist()

            // And back off again.
            setScale("1")
            waitFor("the flag to follow the system back to animations on") { exists(ON) }
            compose.onNodeWithTag(OFF).assertDoesNotExist()
        } finally {
            setScale(original.takeIf { it.toFloatOrNull() != null } ?: "1")
        }
    }

    @Test
    fun a_scale_other_than_zero_is_not_a_request_for_stillness() {
        val original = shell("settings get global animator_duration_scale").trim()
        try {
            setScale("0.5")
            probe()
            compose.onNodeWithTag(ON).assertExists()

            setScale("10")
            // Give the observer the moments it needs, then look: it must not
            // have turned the flag on for a slower scale.
            repeat(20) { pump(50) }
            compose.onNodeWithTag(ON).assertExists()
            compose.onNodeWithTag(OFF).assertDoesNotExist()
        } finally {
            setScale(original.takeIf { it.toFloatOrNull() != null } ?: "1")
        }
    }

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val ON = "motion-on"
        const val OFF = "motion-off"
    }
}
