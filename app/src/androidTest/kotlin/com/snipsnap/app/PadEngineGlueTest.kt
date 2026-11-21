package com.snipsnap.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snipsnap.audio.Snip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole-kit glue goes with the bank, so a bank that is no kit's carries none:
 * SPLIT's stems and MIDI's click are loaded with [PadEngine.loadSnips], not [PadEngine.load].
 * This holds the published setting, not the audible output.
 */
@RunWith(AndroidJUnit4::class)
class PadEngineGlueTest {
    @Test fun a_stem_bank_does_not_inherit_the_last_kits_glue() {
        val engine = PadEngine(48000)
        try {
            assertTrue("the emulator/device must provide the same stream the app uses", engine.start())
            // As if a BOROUGH kit had just loaded: its glue is on.
            assertTrue(engine.configureGlue(GlueSettings.BOROUGH_PREVIEW))
            assertEquals(GlueSettings.BOROUGH_PREVIEW, engine.glueSettings())

            engine.loadSnips(listOf(Snip(FloatArray(4800), 1, 48000)))

            assertEquals(
                "the stems are no kit's: the last kit's glue must not stay on them",
                GlueSettings.OFF, engine.glueSettings(),
            )
        } finally {
            engine.close()
        }
    }
}
