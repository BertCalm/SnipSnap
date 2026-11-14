package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.synth.*
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.*

/**
 * FIRED BRONZE on the shelf. Model 1 is the kit approved on MPC (2026-11-08), frozen file for file. The
 * starter is model 2: each pad one chain of the same four strike layers, each dealt fresh strikes by the
 * shared robin (`Robin.firedBronzeGrid`), every layer's first strike the approved layer. Followed through
 * editing, saving, the app's pad resolver and preview mixer, and the MPC 3 exporter.
 */
class FiredBronzeStarterTest {
    private val dir = java.nio.file.Files.createTempDirectory("fired-bronze").toFile()
    @AfterTest fun clean() { dir.deleteRecursively() }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }

    /** The approved kit, as the starter rendered it before round robin: model 1's four-layer bank. */
    private fun approvedKit(into: File) = KitAssembler.assembleArranged("Fired Bronze", FiredBronzeInstrument.kit(model = 1), into, tempoBpm = 96f)

    @Test fun `model 1 still renders all 64 approved files and stays an editable four layer bank`() {
        val kitDir = File(dir, "approved")
        val kit = approvedKit(kitDir)
        val frozen = com.snipsnap.json.Json.parse(javaClass.getResourceAsStream("/fired-bronze/approved-wavs.json")!!
            .bufferedReader().use { it.readText() }).obj().getValue("files").obj()
        assertEquals(64, frozen.size)
        for ((file, hash) in frozen) assertEquals(hash.str(), sha256(File(kitDir, file)), file)
        assertTrue(kit.pads.all(FiredBronzePads::canEdit))
        // Its saves write no model, so they read back as model 1.
        assertTrue(kit.pads.all { (PadRecipe.fromJsonValue(it.recipe!!).patch as FiredBronzePatch).model == 1 })
    }

    @Test fun `the starter is a strike grid whose first strikes are the approved layers, and no two strikes are copies`() {
        val approvedDir = File(dir, "approved")
        val approved = approvedKit(approvedDir)
        val kitDir = File(dir, "kit")
        val starter = StarterKits.byId(StarterKits.FIRED_BRONZE_ID)!!
        assertFalse(starter.seeded)
        starter.render("Fired Bronze", kitDir)
        val kit = KitStore.load(kitDir)
        assertEquals(96f, kit.tempoBpm)
        assertEquals(16, kit.pads.size)
        val takes = FiredBronzeInstrument.TAKES
        for ((pad, old) in kit.pads.zip(approved.pads)) {
            assertTrue(FiredBronzePads.canEdit(pad), "${pad.displayName}")
            assertEquals(old.displayName, pad.displayName); assertEquals(old.level, pad.level); assertEquals(old.muteGroup, pad.muteGroup)
            assertEquals(FiredBronzeInstrument.MODEL_VERSION, (PadRecipe.fromJsonValue(pad.recipe!!).patch as FiredBronzePatch).model)
            val zones = assertNotNull(pad.chain?.zones)
            assertEquals(FiredBronzePads.GRID_WINDOWS, zones.map { it.velStart to it.velEnd })
            assertEquals(List(4) { takes }, zones.map { it.cycle })
            // Each layer's first strike is the approved layer, sample for sample.
            val whole = WavReader.read(File(kitDir, pad.sampleFile))
            old.velocityLayers.forEachIndexed { z, layer ->
                val first = BreakerKitProof.slice(whole, pad, z * takes)
                assertContentEquals(WavReader.read(File(approvedDir, layer.sampleFile)).samples, first, "${pad.displayName} layer $z")
            }
        }
        // Every strike is another strike, and every softer layer a different strike.
        for (g in BreakerKitProof.measure(kit, kitDir)) {
            println(String.format(java.util.Locale.ROOT, "FIRED BRONZE %s: closest strikes %.1f dB apart, least-changed layer %.1f dB", g.name, g.takesApartDb, g.leastZoneMoveDb))
            assertTrue(g.closestTakes < BreakerKitProof.COPY_LIKENESS, "${g.name}: two strikes are copies (${g.takesApartDb} dB apart)")
            assertTrue(g.leastZoneMoveDb > BreakerKitProof.LEVEL_ONLY_DB, "${g.name}: a layer is another turned down")
        }
    }

    @Test fun `edits regenerate the whole grid, survive saving and replay, and other rewrites are refused`() {
        val kitDir = File(dir, "kit")
        StarterKits.byId(StarterKits.FIRED_BRONZE_ID)!!.render("Fired Bronze", kitDir)
        val model = KitBuilderModel.open(kitDir)
        model.update(5) { it.copy(source = it.source + mapOf("rawSha256" to "old", "rawVelocitySha256" to "old")) }
        val old = model.pad(5)!!
        val patch = PadRecipe.fromJsonValue(old.recipe!!).patch as FiredBronzePatch
        val edited = patch.withMacros(patch.macros + ("AMP" to .9f))
        val recipe = PadRecipe(edited)
        val before = File(kitDir, old.sampleFile).readBytes()
        val new = model.replaceFiredBronze(5, recipe)
        assertEquals(old.chain!!.zones, new.chain!!.zones)
        assertFalse("rawSha256" in new.source)
        assertFalse("rawVelocitySha256" in new.source)
        assertTrue(new.source.getValue("attribution").contains("ajaysm"))
        assertEquals(old.level, new.level)
        val expected = File(dir, "expected.wav")
        WavWriter.write(expected, Robin.concat(Robin.firedBronzeGrid(edited)))
        val actual = File(kitDir, new.sampleFile).readBytes()
        assertContentEquals(expected.readBytes(), actual)
        assertFalse(before.contentEquals(actual))
        assertEquals(Robin.sliceStarts(Robin.firedBronzeGrid(edited)), new.chain!!.boundaries)
        // Replaying the same recipe rebuilds the same grid.
        RecipeReplay.apply(model, 5, recipe.toJsonValue(), "Edge Ring")
        assertContentEquals(actual, File(kitDir, new.sampleFile).readBytes())
        model.save()
        assertTrue(FiredBronzePads.canEdit(KitBuilderModel.open(kitDir).pad(5)))
        assertFalse(FiredBronzePads.canEdit(new.copy(chain = new.chain!!.copy(zones = new.chain!!.zones!!.map { it.copy(cycle = 1) }))))
        assertFalse(FiredBronzePads.canEdit(new.copy(recipe = null)))
        assertFalse(FiredBronzePads.canEdit(new.copy(recipe = PadRecipe(edited.copy(model = 1)).toJsonValue())))
        assertFailsWith<IllegalArgumentException> { model.replaceAudio(5, null) { it } }
    }

    @Test fun `playback deals each layer's strikes in turn, and the MPC 3 export carries them`() {
        val kitDir = File(dir, "kit")
        StarterKits.byId(StarterKits.FIRED_BRONZE_ID)!!.render("Fired Bronze", kitDir)
        val saved = KitStore.load(kitDir)
        for (pad in saved.pads) {
            val frames = WavReader.read(File(kitDir, pad.sampleFile)).frameCount.toLong()
            fun start(velocity: Float, hit: Int) = assertNotNull(PadHit.resolve(pad, velocity, hit) { frames }).startFrame
            val zones = assertNotNull(pad.chain?.zones)
            val anchors = zones.map { zone ->
                val velocity = (zone.velStart + zone.velEnd) / 2f / 127f
                val starts = List(zone.cycle * 2) { start(velocity, it) }
                assertEquals(zone.cycle, starts.take(zone.cycle).toSet().size, "${pad.displayName}: a fresh strike per hit")
                assertEquals(starts.take(zone.cycle), starts.drop(zone.cycle), "${pad.displayName}: the cycle repeats")
                starts.first()
            }
            assertEquals(zones.size, anchors.toSet().size, "${pad.displayName}: each layer is its own slices")
        }
        // The preview mixer the app plays grooves through cycles the strikes too.
        val hits = Mpc3Clip("A01", 2, (0..7).map { Mpc3Note(Mpc3Note.noteFor(1), it * 480L, .9f) })
        val cycling = KitPreview.render(saved, kitDir, hits, 96f)
        val pinned = saved.copy(pads = saved.pads.map { p -> p.copy(chain = p.chain?.let { c -> c.copy(zones = c.zones?.map { it.copy(cycle = 1) }) }) })
        assertFalse(cycling.samples.contentEquals(KitPreview.render(pinned, kitDir, hits, 96f).samples))

        val export = Mpc3Exporter.exportTrack(saved, kitDir, File(dir, "MPC3"), overwrite = true)
        TriadKitProof.verifyTrack(export.program, saved)
        val data = export.samples.first().parentFile
        assertFalse(ExportReadBack.verify(saved, ExportOutcome(ExportFormat.MPC3_TRACK, export.program, data, export.findings)).blocked())
        for (pad in saved.pads) {
            val mine = WavReader.read(File(kitDir, pad.sampleFile))
            val theirs = WavReader.read(File(data, pad.sampleFile))
            assertEquals(mine.frameCount, theirs.frameCount, "${pad.displayName}")
            assertTrue(mine.samples.indices.all { abs(mine.samples[it] - theirs.samples[it]) <= 3f / 8388608 }, "${pad.displayName}: exported PCM")
        }
    }
}
