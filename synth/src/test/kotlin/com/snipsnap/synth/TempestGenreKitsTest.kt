package com.snipsnap.synth

import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import kotlin.test.*

class TempestGenreKitsTest {
    private val kits = TempestGenreKits.Kit.entries
    private fun roles(patches: List<TempestPatch>) = TempestVoice.entries.associateWith { v -> patches.count { it.voice == v } }
    /** A recipe without its display name: two pads with the same sound but different names still match. */
    private fun sound(p: TempestPatch) = Triple(p.voice, p.macros, p.model)

    @Test fun `each kit has sixteen pads with its authored roles and the accepted models`() {
        val expected = mapOf(
            TempestGenreKits.Kit.CIRCUIT to listOf(2, 4, 2, 3, 2, 3),
            TempestGenreKits.Kit.FOUNDRY to listOf(3, 5, 2, 1, 3, 2),
            TempestGenreKits.Kit.STEP to listOf(2, 4, 3, 2, 0, 5),
        )
        for (kit in kits) {
            val patches = TempestGenreKits.patches(kit)
            assertEquals(16, patches.size, kit.name)
            // KICK, METAL, SNARE, CLAP, TOM, HAT
            assertEquals(expected.getValue(kit), TempestVoice.entries.map { roles(patches).getValue(it) }, kit.name)
            assertEquals(16, patches.map { it.name }.toSet().size, "${kit.name} pad names are unique")
            for (p in patches) {
                assertEquals(Tempest.defaultModelFor(p.voice), p.model, p.name)
                assertEquals(0f, p.macros.getValue("HOLD"), p.name)
                assertTrue(p.name.length <= 16, "${p.name} fits a pad label")
            }
        }
    }

    @Test fun `each kit reuses at most four accepted presets and they are unchanged`() {
        val factory = TempestPresets.all()
        for (kit in kits) {
            val patches = TempestGenreKits.patches(kit)
            val reused = patches.filter { it in factory }
            assertTrue(reused.size in 1..4, "${kit.name} reuses ${reused.size}")
            assertEquals(reused.map { it.name }, TempestGenreKits.acceptedNames(kit))
            // A new pad never borrows a factory name for a different sound.
            for (p in patches - reused.toSet()) assertTrue(factory.none { it.name == p.name }, p.name)
        }
    }

    @Test fun `kits are distinct from each other and from the accepted TEMPEST kit`() {
        val all = kits.associate { it.name to TempestGenreKits.patches(it) } + ("TEMPEST" to TempestPresets.fullKitPatches())
        val names = all.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size) {
            val a = all.getValue(names[i]); val b = all.getValue(names[j])
            val bSounds = b.map(::sound).toSet()
            assertTrue(a.count { sound(it) !in bSounds } >= 12, "${names[i]} and ${names[j]} differ in at least 12 pads")
            assertTrue((0 until 16).count { sound(a[it]) != sound(b[it]) } >= 6, "${names[i]} and ${names[j]} differ pad by pad")
            for (voice in listOf(TempestVoice.KICK, TempestVoice.SNARE)) {
                val shared = a.filter { it.voice == voice }.map(::sound).toSet() intersect b.filter { it.voice == voice }.map(::sound).toSet()
                assertTrue(shared.isEmpty(), "${names[i]} and ${names[j]} share a $voice")
            }
            assertNotEquals(roles(a), roles(b), "${names[i]} and ${names[j]} have different role balances")
        }
    }

    @Test fun `hats choke in group one, every pad regenerates from its saved recipe`() {
        for (kit in kits) {
            val pads = TempestGenreKits.build(kit)
            val patches = TempestGenreKits.patches(kit)
            assertEquals(16, pads.size)
            for ((i, nullable) in pads.withIndex()) {
                val pad = assertNotNull(nullable)
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                val patch = assertIs<TempestPatch>(recipe.patch)
                assertEquals(patches[i], patch)
                assertEquals(patch.name, pad.displayName)
                assertEquals(if (patch.voice == TempestVoice.HAT) 1 else 0, pad.muteGroup, patch.name)
                assertNull(recipe.fx); assertNull(recipe.board)
                assertTrue(pad.oneShot && pad.softVariants.isEmpty())
                assertContentEquals(pad.snip.samples, recipe.render().samples, patch.name)
                assertContentEquals(pad.snip.samples, TempestPatch.fromJsonValue(patch.toJsonValue()).render().samples)
                assertTrue(pad.snip.samples.all { it.isFinite() } && pad.snip.samples.any { it != 0f }, patch.name)
                assertEquals(if (patch in TempestPresets.all()) "accepted" else "candidate", pad.source["status"])
            }
        }
    }

    @Test fun `both MPC formats export and read back every genre kit`() {
        val root = kotlin.io.path.createTempDirectory("tempest-genre-kits").toFile()
        try {
            for (genre in kits) {
                val work = File(root, "${genre.id}-work").apply { mkdirs() }
                val staging = File(root, "${genre.id}-out").apply { mkdirs() }
                val kit = KitAssembler.assembleArranged(genre.displayName, TempestGenreKits.build(genre), work, null)
                assertEquals(16, kit.pads.size)
                assertEquals(kit.pads.map { p -> if ((PadRecipe.fromJsonValue(p.recipe!!).patch as TempestPatch).voice == TempestVoice.HAT) 1 else 0 },
                    kit.pads.map { it.muteGroup })
                val result = Mpc3Exporter.exportTrack(kit, work, staging, overwrite = true, mpc2Twin = true)
                val data = File(staging, Mpc3TrackWriter.trackDataDirName(kit.name))
                val twin = File(data, "${kit.name}.xpm")
                assertEquals(kit.pads.map { it.sampleFile }.toSet(), result.samples.map { it.name }.toSet())
                KitStore.save(kit, data)
                assertEquals(kit, KitStore.load(data))
                val findings = listOf(
                    ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings),
                    ExportOutcome(ExportFormat.PROGRAM_FOLDER, twin, null, result.findings),
                ).flatMap { ExportReadBack.verify(kit, it) }
                assertFalse(findings.blocked(), "${genre.name}: ${findings.joinToString { it.message }}")
            }
        } finally { root.deleteRecursively() }
    }
}
