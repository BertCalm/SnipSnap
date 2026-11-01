package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.BellowsPresets
import com.snipsnap.synth.BellowsVoice
import com.snipsnap.synth.KeyNote
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BellowsTonicBedTest {
    @Test
    fun `the exported tonic bed stays one key wide in the sidecar and the xpm`() {
        val dest = createTempDirectory("bellows-bed").toFile()
        try {
            val rate = WavWriter.MPC_SAMPLE_RATE
            val note = KeyNote(Snip(FloatArray(rate / 4) { 0.1f }, 1, rate), loopStartFrame = 100L)
            val spec = BellowsPadMaker.Spec(BellowsPresets.forVoice(BellowsVoice.WHEEL).first())
            BellowsPadMaker.exportTonicBed("Bed", spec, note, dest)
            val back = InstrumentStore.read(InstrumentStore.sidecar(dest, "Bed"))
            assertEquals(listOf(48 to 48), back.zones.map { it.lowNote to it.highNote })
            val xpm = File(dest, "Bed_[TrackData]/Bed.xpm").readText()
            assertTrue("<LowNote>48</LowNote>" in xpm && "<HighNote>48</HighNote>" in xpm)
            assertTrue("<LowNote>0</LowNote>" !in xpm && "<HighNote>127</HighNote>" !in xpm)
        } finally {
            dest.deleteRecursively()
        }
    }
}
