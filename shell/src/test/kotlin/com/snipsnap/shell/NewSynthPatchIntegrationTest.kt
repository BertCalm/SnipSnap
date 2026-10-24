package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.synth.AerostatPatch
import com.snipsnap.synth.AerostatPresets
import com.snipsnap.synth.Ballast
import com.snipsnap.synth.BallastPatch
import com.snipsnap.synth.BallastVoice
import com.snipsnap.synth.Patches
import com.snipsnap.synth.TremorPatch
import com.snipsnap.synth.TremorPresets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The new picker entries use the same saved-patch desk as every existing engine. */
class NewSynthPatchIntegrationTest {
    @Test
    fun `saved patches keep their identity and edited controls through the shared desk`() {
        val shelf = Files.createTempDirectory("new-synth-presets").toFile()
        try {
            val originals = listOf(
                AerostatPresets.all().first().copy(name = "MY FLOAT"),
                TremorPresets.all().first().copy(name = "MY HIDE"),
                BallastPatch("MY TILES", BallastVoice.GLINT, Ballast.defaults(BallastVoice.GLINT)),
            )
            originals.forEachIndexed { index, patch -> UserPresets.save(shelf, patch, index.toLong()) }
            val restored = UserPresets.read(shelf).map { it.patch }
            assertEquals(originals.toSet(), restored.toSet())
            for (patch in restored) {
                val moved = patch.macros + ("TUNE" to .25f)
                val edited = Patches.edited(patch, "EDITED", moved)
                assertEquals(patch.engine, edited.engine)
                assertEquals(patch.voiceName, edited.voiceName)
                assertEquals(moved, edited.macros)
                assertEquals(edited, Patches.fromJsonText(edited.toJsonText()))
            }
            assertTrue(restored.any { it is AerostatPatch })
            assertTrue(restored.any { it is TremorPatch })
            assertTrue(restored.any { it is BallastPatch })
        } finally {
            shelf.deleteRecursively()
        }
    }

    @Test
    fun `TILES is a label while saved preset lookup and promotion retain GLINT`() {
        assertEquals("TILES 1", UserPresets.suggest("BALLAST", "GLINT", emptyList()))
        val patch = BallastPatch("MY TILES", BallastVoice.GLINT, Ballast.defaults(BallastVoice.GLINT))
        val line = UserPresets.rosterLine(patch)
        assertTrue(line.startsWith("p(BallastVoice.GLINT,"), line)
        assertEquals("BallastPresets.kt", UserPresets.rosterFile(patch))
    }

    @Test
    fun `Ballast spread uses the bass root when stronger octave strings obscure detection`() {
        val patch = BallastPatch("WIDE", BallastVoice.WIRE,
            Ballast.defaults(BallastVoice.WIRE) + mapOf("TUNE" to 1f, "SPAN" to 1f))
        // A missing or octave-dominated detector answer must not replace an authored bass root.
        val detectorHasNoNote = Snip(FloatArray(2048), channels = 1, sampleRate = 44100)
        assertEquals(Ballast.midiFor(patch.voice, 1f).toFloat(), Spread.detect(detectorHasNoNote, patch))
    }
}
