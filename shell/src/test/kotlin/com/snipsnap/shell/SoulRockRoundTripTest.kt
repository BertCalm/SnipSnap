package com.snipsnap.shell

import com.snipsnap.kit.*
import com.snipsnap.synth.SoulRockKits
import com.snipsnap.audio.WavReader
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

class SoulRockRoundTripTest {
    @Test fun `all Soul Rock choices retain every strike through save SFZ import and playback`() {
        val root = createTempDirectory("soul-rock").toFile()
        try {
            for (t in SoulRockKits.Choice.entries) {
                val dir = root.resolve(t.id)
                val rendered = SoulRockKits.build(t)
                val starter = assertNotNull(StarterKits.byId(t.id))
                assertFalse(starter.seeded)
                assertEquals(t.bpm.toFloat(),starter.tempoBpm)
                assertEquals(1,StarterShelf.GROUPS.count { t.id in it.ids })
                val kit = starter.render(t.title,dir)
                assertEquals(kit, KitStore.load(dir))
                assertFalse(Preflight.check(kit, dir).blocked(), t.id)
                val sfz = SfzWriter.write(kit, dir, root.resolve("export-${t.id}"))
                val imported = SfzImporter.import(sfz, root.resolve("import-${t.id}"))
                val exported = Mpc3Exporter.exportTrack(kit,dir,root.resolve("mpc-${t.id}"),mpc2Twin=true)
                TriadKitProof.verifyTrack(exported.program,kit)
                assertTrue(ExportReadBack.verifyProgram(kit,exported.program,ExportFormat.MPC3_TRACK).none { it.severity == Severity.FAIL })
                for (pad in kit.pads) assertContentEquals(dir.resolve(pad.sampleFile).readBytes(),exported.samples.first().parentFile.resolve(pad.sampleFile).readBytes())
                assertTrue(imported.skipped.isEmpty())
                assertEquals(16, imported.kit.pads.size)
                for ((i, pad) in kit.pads.withIndex()) {
                    assertNull(pad.recipe)
                    assertEquals("soul-rock", pad.source["factory"])
                    if (t == SoulRockKits.Choice.ROADHOUSE && pad.slot == 2) {
                        assertEquals("coupled-snare-snap-rattle", pad.source["sourceModel"])
                        assertTrue(pad.source.keys.none { it.startsWith("synthesis.") })
                    }
                    if (t == SoulRockKits.Choice.ROADHOUSE && pad.slot == 12) {
                        assertEquals("Crash", pad.displayName)
                        assertEquals("statistical-modal-rock-crash-bloom", pad.source["sourceModel"])
                    }
                    assertEquals(SoulRockKits.VERSION.toString(), pad.source["bankVersion"])
                    assertNotNull(pad.source["kit"])
                    assertTrue(pad.oneShot)
                    val back = imported.kit.pads[i]
                    assertNull(pad.recipe, "Frozen audition assets must not claim an editable synth recipe")
                    assertEquals("soul-rock", pad.source["factory"])
                    assertEquals("frozen acoustic synthesis", pad.source["sourceKind"])
                    assertNotNull(pad.source["kit"])
                    val chain = assertNotNull(pad.chain)
                    assertEquals(3, chain.zones!!.size)
                    assertTrue(chain.zones!!.all { it.cycle == 3 })
                    assertEquals(chain, back.chain)
                    assertEquals(pad.muteGroup, back.muteGroup)
                    val wav = WavReader.read(dir.resolve(pad.sampleFile))
                    assertContentEquals(dir.resolve(pad.sampleFile).readBytes(), imported.directory.resolve(back.sampleFile).readBytes())
                    for ((n, take) in rendered[i].velocityTakes.flatten().withIndex()) {
                        val w = chain.window(n, wav.frameCount.toLong())
                        assertEquals(take.frameCount.toLong(), w.last + 1 - w.first)
                        assertTrue(take.samples.indices.all { abs(take.samples[it] - wav.samples[w.first.toInt()+it]) <= 2e-7f })
                    }
                    for (v in 1..127) for (hitIndex in 0..8) {
                        val before = assertNotNull(PadHit.resolve(pad, v/127f, hitIndex) { wav.frameCount.toLong() })
                        val after = assertNotNull(PadHit.resolve(back, v/127f, hitIndex) { wav.frameCount.toLong() })
                        val zone = assertNotNull(chain.zoneFor(v))
                        val expectedZone = if (v < 42) 0 else if (v < 85) 1 else 2
                        assertEquals(expectedZone * 3, zone.baseSlice)
                        assertEquals(3, zone.cycle)
                        val w = chain.window(zone.baseSlice + hitIndex%3, wav.frameCount.toLong())
                        assertEquals(w.first, after.startFrame)
                        assertEquals(w.last+1, after.endFrameExclusive)
                        // SFZ writes volume to 0.001 dB: half a step is < 0.000058 relative gain.
                        assertEquals(before.gainLeft, after.gainLeft, abs(before.gainLeft) * 0.000059f + 1e-7f)
                        assertEquals(before.gainRight, after.gainRight, abs(before.gainRight) * 0.000059f + 1e-7f)
                        assertEquals(before.pitchRatio, after.pitchRatio)
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }
}
