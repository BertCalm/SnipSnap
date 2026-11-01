package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.synth.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class RimePadMakerTest {
    @Test fun `native attack and periodic loops survive both MPC formats and phone recall`() {
        val root = createTempDirectory("rime-instrument-test").toFile()
        try {
            val p = RimePresets.starter(RimeVoice.LOOP).copy(model = 5, midi = 43, velocity = .6f, seed = 2718)
            val spec = RimePadMaker.Spec(p, .4f)
            val midis = RimePadMaker.zoneMidis(spec)
            assertEquals((31..55 step 3).toList(), midis)
            val notes = midis.map { RimePadMaker.renderZone(spec, it) }
            val program = RimePadMaker.export("RIME KEYS", spec, notes, root)
            val (sidecar, instrument) = InstrumentStore.list(root).single()
            val data = File(root, "RIME KEYS_[TrackData]")
            val xpm = File(data, "RIME KEYS.xpm").readText()
            val xty = Acvs.read(File(root, "RIME KEYS.xty")).payloadText
            assertEquals(spec.heldPatch, RimePatch.fromJsonText(File(data, "rime-patch.json").readText()))
            val savedZones = com.snipsnap.json.Json.parse(File(data, "rime-zones.json").readText()).arr().map { RimePatch.fromJsonValue(it) }
            assertEquals(midis, savedZones.map { it.midi })
            assertTrue(savedZones.all { it.model == 5 && it.seed == p.seed && it.velocity == p.velocity && Rime.isLoop(it.macros) })
            assertEquals(.4f, instrument.release)
            assertEquals(9, instrument.zones.size)
            program.keygroups.zipWithNext().forEach { (a,b) -> assertEquals(a.highNote + 1, b.lowNote) }
            instrument.zones.forEachIndexed { i, zone ->
                assertEquals(midis[i], zone.rootNote)
                assertEquals(notes[i].loopStartFrame, zone.loopStartFrame)
                val file = File(sidecar.parentFile, zone.sample)
                val sheet = assertNotNull(WavReader.readSmpl(file))
                assertEquals(zone.rootNote, sheet.rootNote)
                assertEquals(zone.loopStartFrame, assertNotNull(sheet.loop).startFrame)
                assertEquals(zone.frameCount, sheet.loop!!.endFrameExclusive)
                assertTrue("<SliceLoopStart>${zone.loopStartFrame}</SliceLoopStart>" in xpm)
                assertTrue("\"LoopStart\": ${zone.loopStartFrame}" in xty)
                val wav = WavReader.read(file)
                assertTrue(Keys.seamError(wav.samples, zone.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
            }
            assertFailsWith<java.util.concurrent.CancellationException> { RimePadMaker.renderZone(spec, 43) { true } }
            assertFailsWith<IllegalArgumentException> { RimePadMaker.Spec(p.copy(model = 4)) }
        } finally { root.deleteRecursively() }
    }
}
