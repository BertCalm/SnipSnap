package com.snipsnap.shell

import com.snipsnap.synth.Stoop
import com.snipsnap.synth.StoopPatch
import com.snipsnap.synth.StoopPresets
import com.snipsnap.synth.StoopVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoopUserPresetsTest {
    @Test
    fun `normalized factory labels remain protected for every groove family`() {
        for (voice in StoopVoice.entries) {
            val patch = StoopPresets.forVoice(voice).first()
            assertEquals(
                UserPresets.Check.Factory(UserPresets.normalize(patch.name)),
                UserPresets.check("  ${patch.name.lowercase()}  ", patch.engine, patch.voiceName, emptyList()),
            )
        }
    }

    @Test
    fun `saving and promotion retain note source answer and explicit chord recipe`() {
        val shelf = kotlin.io.path.createTempDirectory("stoop-presets").toFile()
        try {
            val patch = StoopPatch(
                "MY GROOVE", StoopVoice.CHORD, Stoop.defaults(StoopVoice.CHORD), midi = 57, velocity = .4f,
                source = Stoop.defaultSource(StoopVoice.CHORD).copy(shape = .8f, sub = .1f, fmRatio = 3f,
                    fmIndex = .6f, harmonicTilt = .3f, detuneCents = 4f),
                shadowInterval = 7, shadowDelayMs = 175f, shadowArticulation = .7f, shadowReplies = 2,
                chordIntervals = listOf(0, 3, 7),
            )
            val saved = UserPresets.save(shelf, patch, nowMillis = 12L)
            assertEquals(listOf(saved), UserPresets.read(shelf))
            assertEquals(patch, UserPresets.read(shelf).single().patch)
            val line = UserPresets.rosterLine(patch)
            assertTrue(line.startsWith("p(StoopVoice.CHORD, \"MY GROOVE\", "))
            for (state in listOf(
                "midi = 57", "velocity = 0.4f", "source = StoopSource(shape = 0.8f, sub = 0.1f",
                "fmRatio = 3.0f", "fmIndex = 0.6f", "harmonicTilt = 0.3f", "detuneCents = 4.0f",
                "shadowInterval = 7", "shadowDelayMs = 175.0f", "shadowArticulation = 0.7f",
                "shadowReplies = 2", "chordIntervals = listOf(0, 3, 7)", "model = ${patch.model}",
            )) assertTrue(state in line, "the roster line must retain $state: $line")
            assertEquals("StoopPresets.kt", UserPresets.rosterFile(patch.engine))
        } finally {
            shelf.deleteRecursively()
        }
    }
}
