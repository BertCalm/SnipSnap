package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [Keys.contourPadMidis] has no caller yet: docs/CONTOUR.md says SYNTH MAKE
 * INSTRUMENT and `--instrument` do not call [Keys.contourPad]. Its KDoc names a
 * zone count and a range, so they are held here, ready for the route that uses them.
 */
class ContourPadZonesTest {
    @Test
    fun `seventeen minor-third zones cover each voice's four-octave TUNE range, and each one plays`() {
        assertEquals(4 * 12, 2 * ContourEngine.TUNE_SEMITONES, "TUNE travels four octaves around the root")
        for (voice in ContourVoice.entries) {
            val root = ContourEngine.rootMidi(voice)
            val low = root - ContourEngine.TUNE_SEMITONES
            val high = root + ContourEngine.TUNE_SEMITONES
            val zones = Keys.contourPadMidis(voice)
            assertEquals(17, zones.size, "$voice: the KDoc's seventeen zones, got $zones")
            assertEquals(low, zones.first(), "$voice: the lowest zone is the bottom of TUNE")
            assertEquals(high, zones.last(), "$voice: the highest zone is the top of TUNE")
            assertEquals(List(zones.size - 1) { 3 }, zones.zipWithNext { a, b -> b - a }, "$voice: zones a minor third apart")
            assertTrue(root in zones, "$voice: the voice root is a zone")
            for (midi in zones) {
                val tune = .5f + (midi - root) / (2f * ContourEngine.TUNE_SEMITONES)
                assertEquals(midi, ContourEngine.midiFor(voice, tune), "$voice: zone $midi is a note TUNE reaches")
                // Velocity zero plans the loop at this note without synthesizing it.
                val pad = Keys.contourPad(voice, emptyMap(), midi, velocity = 0f)
                assertTrue(pad.loopStartFrame > 0, "$voice at MIDI $midi: a held pad marks its loop")
                assertEquals(2 * pad.loopStartFrame, pad.snip.samples.size.toLong(), "$voice at MIDI $midi: two copies of the loop")
            }
            for (outside in listOf(low - 3, high + 3)) {
                val refused = assertFailsWith<IllegalArgumentException> { Keys.contourPad(voice, emptyMap(), outside, velocity = 0f) }
                assertEquals("CONTOUR $voice pads are MIDI $low..$high, got $outside", refused.message,
                    "$voice: the next minor third past the zones is outside TUNE")
            }
        }
    }
}
