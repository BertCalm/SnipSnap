package com.snipsnap.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snipsnap.audio.Snip
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.MeridianHeldKeygroupPackage
import com.snipsnap.shell.MeridianPadMaker
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.MeridianPresets
import com.snipsnap.synth.MeridianVoice
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual sheet export adapter with synthetic cycle fixtures; no native audio render or device playback claim. */
@RunWith(AndroidJUnit4::class)
class MeridianHeldSourceInstrumentScreenTest {
    @Test fun heldSheetPublishesBoundSourceAndKeepsTheExistingReleaseAndRange() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "meridian-held-source-${System.nanoTime()}").apply { mkdirs() }
        try {
            val preset = MeridianPresets.forVoice(MeridianVoice.CHIME).first { it.macros.getValue("HOLD") == 1f }
            val patch = preset.copy(midi = 55, velocity = .37f, seedContext = -41,
                macros = preset.macros + ("TUNE" to .75f))
            val spec = MeridianPadMaker.Spec(patch, .73f)
            val adapter = HeldSpec.Meridian(spec)
            val notes = MeridianPadMaker.zoneMidis(spec).map {
                List(3) { KeyNote(Snip(FloatArray(1024) { i ->
                    (.1 * sin(2 * PI * (i % 16) / 16)).toFloat()
                }, 1, 44_100), 512L) }
            }
            assertEquals((36..72 step 3).toList(), adapter.zoneMidis)
            assertTrue(adapter.hasRelease)
            val program = adapter.export("Meridian Held Source", notes, root)
            assertEquals(.73f, program.volumeRelease)
            assertEquals(program, InstrumentStore.list(root).single().second.toProgram())
            assertEquals(program, MeridianHeldKeygroupPackage.validate("Meridian Held Source", root, spec).program)
            assertTrue(File(root, "Meridian Held Source_[TrackData]/${MeridianHeldKeygroupPackage.SOURCES}").isFile)
            assertEquals(.37f, patch.velocity)
            assertEquals(.75f, patch.macros.getValue("TUNE"))
        } finally { root.deleteRecursively() }
    }
}
