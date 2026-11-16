package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc2Limits
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Acvs
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.synth.SynthKits
import com.snipsnap.synth.Tempest
import com.snipsnap.synth.TempestPatch
import com.snipsnap.synth.TempestPresets
import com.snipsnap.synth.TempestRoundRobinKits
import com.snipsnap.synth.TempestVoice
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

/**
 * The round-robin gate, in software, for the opt-in TEMPEST round robin: the kit through the shelf,
 * saving, reloading, the app's pad resolver, the groove preview (hat choke included) and both MPC
 * exports, read back from the files each step wrote. TEMPEST has one touch, so each pad is a plain
 * single-zone chain: MPC 3 deals every take by Slice Motion, and MPC 2, which has no Slice Motion,
 * windows the pad to its first take, the accepted hit.
 */
class TempestRoundRobinStarterTest {
    private val lsb = 3f / 8388608

    @Test fun `the round robin is on the shelf once, right after the accepted TEMPEST starter`() {
        val starter = assertNotNull(StarterKits.byId(TempestRoundRobinKits.ID))
        assertEquals("TEMPEST ROUND ROBIN", starter.displayName)
        assertFalse(starter.seeded)
        assertNull(starter.key, "a drum kit claims no musical key")
        val group = StarterShelf.GROUPS.single { TempestRoundRobinKits.ID in it.ids }
        assertEquals(1, StarterShelf.GROUPS.sumOf { g -> g.ids.count { it == TempestRoundRobinKits.ID } })
        assertEquals(group.ids.indexOf("tempest") + 1, group.ids.indexOf(TempestRoundRobinKits.ID))
        // Stored starter order only grows at the end: TEMPEST ROUND ROBIN was appended after TERRA's genre kits.
        val ids = StarterKits.ALL.map { it.id }
        assertEquals("terra-punjab", ids[ids.indexOf(TempestRoundRobinKits.ID) - 1], "the stored starter order only grows at the end")
    }

    @Test fun `every take survives saving reloading playback the preview and both MPC exports`() {
        val root = createTempDirectory("tempest-rr").toFile()
        try {
            val pads = TempestRoundRobinKits.build()
            val patches = TempestPresets.fullKitPatches()
            val accepted = SynthKits.tempest()
            val dir = root.resolve("kit")
            val kit = assertNotNull(StarterKits.byId(TempestRoundRobinKits.ID)).render("Tempest Round Robin", dir)
            val saved = KitStore.load(dir)
            assertEquals(kit, saved)
            assertNull(saved.key)
            assertFalse(Preflight.check(saved, dir).blocked())
            assertEquals((1..16).toList(), saved.pads.map { it.slot })
            assertEquals(patches.map { it.name }, saved.pads.map { it.displayName })

            for ((i, pad) in saved.pads.withIndex()) {
                val patch = patches[i]
                assertEquals(if (patch.voice == TempestVoice.HAT) 1 else 0, pad.muteGroup, "${pad.displayName}: hats choke, nothing else does")
                assertEquals(accepted[i]!!.drumClass, pad.drumClass)
                assertTrue(pad.oneShot && !pad.loop && pad.velocityLayers.isEmpty())
                assertNull(pad.recipe, "a single-hit recipe cannot regenerate the takes")
                assertEquals(pads[i].source, pad.source)
                assertEquals(patch, TempestPatch.fromJsonText(pad.source.getValue("recipe")))
                val chain = assertNotNull(pad.chain)
                assertNull(chain.zones, "one touch: a single-zone chain")
                assertEquals(TempestRoundRobinKits.TAKES, chain.cycle)
                val takes = listOf(pads[i].snip) + pads[i].takes
                val wav = WavReader.read(dir.resolve(pad.sampleFile))
                val hits = List(8) { assertNotNull(PadHit.resolve(pad, .8f, it) { wav.frameCount.toLong() }) }
                val starts = hits.map { it.startFrame }
                assertEquals(4, starts.take(4).toSet().size, "${pad.displayName}: a fresh take per hit")
                assertEquals(starts.take(4), starts.drop(4), "${pad.displayName}: the cycle repeats")
                for ((t, hit) in hits.take(4).withIndex()) {
                    val x = takes[t].samples
                    assertEquals(x.size.toLong(), hit.endFrameExclusive - hit.startFrame)
                    val at = hit.startFrame.toInt()
                    assertTrue(x.indices.all { abs(x[it] - wav.samples[at + it]) <= lsb }, "${pad.displayName} take $t on disk")
                }
                // The first hit plays the accepted pad.
                val reference = accepted[i]!!.snip.samples
                val at = hits.first().startFrame.toInt()
                assertTrue(reference.indices.all { abs(reference[it] - wav.samples[at + it]) <= lsb }, "${pad.displayName} take 0 is the accepted pad")
            }

            preview(saved, dir, pads)

            // MPC 3 native deals every take: one layer per pad, slice 0 first, stepping through four.
            val track = Mpc3Exporter.exportTrack(saved, dir, root.resolve("mpc3"))
            val data = track.samples.first().parentFile
            assertFalse(ExportReadBack.verify(saved, ExportOutcome(ExportFormat.MPC3_TRACK, track.program, data, track.findings)).blocked())
            val instruments = instruments(track.program)
            for (pad in saved.pads) {
                val mine = WavReader.read(dir.resolve(pad.sampleFile))
                val theirs = WavReader.read(File(data, pad.sampleFile))
                assertEquals(mine.frameCount, theirs.frameCount, "${pad.displayName}")
                assertTrue(mine.samples.indices.all { abs(mine.samples[it] - theirs.samples[it]) <= lsb }, "${pad.displayName}: exported PCM")
                val instrument = (instruments[pad.slot - 1] as JsonValue.Obj).entries
                assertEquals(pad.muteGroup, num(instrument, "whichMuteGroup"), "${pad.displayName}: MPC 3 mute group")
                val layer = (((instrument.getValue("layersv") as JsonValue.Arr).items[0]) as JsonValue.Obj).entries
                assertEquals(listOf(0, 1, 4), listOf("sliceIndex", "sliceIncrement", "sliceCycleLength").map { num(layer, it) }, "${pad.displayName}: MPC 3 Slice Motion")
            }

            // MPC 2 has no Slice Motion: each pad is windowed to its first take, the accepted hit.
            // No velocity layers exist here, so the velocity-and-round-robin sentence does not apply;
            // the export screen says instead, before anything is written, that every pad keeps one take.
            assertTrue(Mpc2Limits.gridPads(saved).isEmpty())
            assertEquals(saved.pads, Mpc2Limits.firstTakePads(saved))
            for (format in listOf(ExportFormat.PROGRAM_FOLDER, ExportFormat.EXPANSION, ExportFormat.XPN)) {
                assertEquals(
                    "MPC 2 has no Slice Motion to deal a pad's takes: 16 pads keep their first take only. MPC 3 native deals every take.",
                    assertNotNull(ExportWizardModel(saved, dir).apply { setFormat(format) }.formatNote, "$format").message,
                )
            }
            assertNull(ExportWizardModel(saved, dir).apply { setFormat(ExportFormat.MPC3_TRACK) }.formatNote, "MPC 3 native deals every take")
            val folder = Exporters.export(ExportFormat.PROGRAM_FOLDER, saved, dir, root.resolve("mpc2"))
            assertFalse(ExportReadBack.verify(saved, folder).blocked())
            val xpm = folder.primary.let { if (it.isDirectory) it.walk().first { f -> f.name.endsWith(".xpm") } else it }.readText()
            val instrumentsXml = xpm.split("<Instrument number=").drop(1)
            for (pad in saved.pads) {
                val block = instrumentsXml.first { it.startsWith("\"${pad.slot - 1}\"") }
                assertTrue("<MuteGroup>${pad.muteGroup}</MuteGroup>" in block, "${pad.displayName}: MPC 2 mute group")
                val firstLayer = block.substringAfter("<Layer number=\"1\">").substringBefore("</Layer>")
                assertTrue("<SliceStart>0</SliceStart>" in firstLayer && "<SliceEnd>${pads[pad.slot - 1].snip.frameCount}</SliceEnd>" in firstLayer,
                    "${pad.displayName}: MPC 2 plays the first take")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /** The groove preview deals the takes in order, and a closed hat still cuts an open hat on every take. */
    private fun preview(saved: Kit, dir: File, pads: List<com.snipsnap.kit.ArrangedPad>) {
        val snare = saved.pads.single { it.displayName == "SNARE START" }.slot
        val takes = (listOf(pads[snare - 1].snip) + pads[snare - 1].takes).map { it.samples }
        // Four hits two seconds apart at 120 BPM, so no tail overlaps the next hit.
        val spaced = (0 until 4).map { Mpc3Note(Mpc3Note.noteFor(snare), it * 3840L, .9f) }
        val played = KitPreview.renderNotes(saved, dir, spaced, 16 * 960L, 120f)
        for (hit in 0 until 4) {
            val at = hit * 88200
            val left = FloatArray(takes[0].size) { played.samples[(at + it) * 2] }
            val fit = takes.map { t ->
                val g = left.indices.sumOf { left[it].toDouble() * t[it] } / t.sumOf { it.toDouble() * it }
                left.indices.sumOf { (left[it] - g * t[it]).let { e -> e * e } } / left.sumOf { it.toDouble() * it }
            }
            assertTrue(fit[hit] < 1e-8, "preview hit $hit plays take $hit: ${fit[hit]}")
            for ((t, e) in fit.withIndex()) if (t != hit) assertTrue(e > 1e-4, "preview hit $hit is not take $t: $e")
        }

        val open = saved.pads.single { it.displayName == "HAT OPEN" }.slot
        val closed = saved.pads.single { it.displayName == "HAT CLOSED" }.slot
        // Open on the beat, closed an eighth later, four times: each hat plays takes 1 to 4 in turn.
        val both = (0 until 4).flatMap { listOf(Mpc3Note(Mpc3Note.noteFor(open), it * 960L, .9f), Mpc3Note(Mpc3Note.noteFor(closed), it * 960L + 480, .9f)) }
        val closedOnly = both.filter { it.note == Mpc3Note.noteFor(closed) }
        val openOnly = both.filter { it.note == Mpc3Note.noteFor(open) }
        val length = 4 * 960L
        val mix = KitPreview.renderNotes(saved, dir, both, length, 120f)
        val alone = KitPreview.renderNotes(saved, dir, closedOnly, length, 120f)
        val ring = KitPreview.renderNotes(saved, dir, openOnly, length, 120f)
        val framesPerPulse = 60.0 / 120 * 44100 / 960
        for (beat in 0 until 4) {
            // From the end of the choke fade until the next open hat, only the closed hat sounds.
            val from = ((beat * 960L + 480) * framesPerPulse).toInt() + com.snipsnap.audio.AutoPlace.CHOKE_FADE
            val to = if (beat < 3) (((beat + 1) * 960L) * framesPerPulse).toInt() else from + 4410
            var openEnergy = 0.0
            for (f in from * 2 until to * 2) {
                assertEquals(alone.samples[f], mix.samples[f], 1e-6f, "beat $beat: the open hat is cut")
                openEnergy += ring.samples[f].toDouble() * ring.samples[f]
            }
            assertTrue(openEnergy > 1e-6, "beat $beat: left alone, the open hat would still ring there")
        }
    }

    private fun num(o: Map<String, JsonValue>, k: String) = (o.getValue(k) as JsonValue.Num).value.toInt()

    private fun instruments(file: File): List<JsonValue> {
        val stack = ArrayDeque<JsonValue>(); stack.add(requireNotNull(Acvs.read(file).payload))
        while (stack.isNotEmpty()) when (val cur = stack.removeLast()) {
            is JsonValue.Obj -> if ("instruments" in cur.entries) return (cur.entries.getValue("instruments") as JsonValue.Arr).items else stack.addAll(cur.entries.values)
            is JsonValue.Arr -> stack.addAll(cur.items)
            else -> Unit
        }
        error("no drum program in $file")
    }
}
