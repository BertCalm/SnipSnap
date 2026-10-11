package com.snipsnap.shell

import com.snipsnap.synth.RevelConfig
import com.snipsnap.synth.RevelPatch
import com.snipsnap.synth.RevelTrajectory
import com.snipsnap.synth.RevelVoice
import kotlin.test.Test
import kotlin.test.assertEquals

class RevelUserPresetsTest {
    @Test
    fun `the shelf and factory promotion preserve independent microphone and phrase state`() {
        val directory = java.nio.file.Files.createTempDirectory("revel-user-presets").toFile()
        try {
            val source = RevelPatch("MY LISTENERS", RevelVoice.CROSSING,
                linkedMapOf("TUNE" to .75f, "PLAY" to .33f),
                configuration = RevelConfig(
                    micCount = 2, phraseTempo = 118f, phraseBeats = 3,
                    trajectories = listOf(RevelTrajectory.ECCENTRIC, RevelTrajectory.SPIRO),
                    phaseOffsets = listOf(.17f, .69f), directions = listOf(-1, 1),
                    speedRatios = listOf(.5f, 1.5f), seed = Long.MIN_VALUE,
                ), velocity = .4f)
            UserPresets.save(directory, source, 817L)
            val saved = UserPresets.read(directory).single()
            assertEquals(source, saved.patch)
            assertEquals(817L, saved.savedAt)
            val line = "p(RevelVoice.CROSSING, \"MY LISTENERS\", \"TUNE\" to 0.75f, \"PLAY\" to 0.33f)" +
                ".copy(configuration = RevelConfig(micCount = 2, phraseTempo = 118.0f, phraseBeats = 3, " +
                "trajectories = listOf(RevelTrajectory.ECCENTRIC, RevelTrajectory.SPIRO), " +
                "phaseOffsets = listOf(0.17f, 0.69f), directions = listOf(-1, 1), " +
                "speedRatios = listOf(0.5f, 1.5f), seed = Long.MIN_VALUE), velocity = 0.4f),"
            assertEquals(line, UserPresets.rosterLine(saved.patch))
            assertEquals("RevelPresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
        } finally {
            directory.deleteRecursively()
        }
    }
}
