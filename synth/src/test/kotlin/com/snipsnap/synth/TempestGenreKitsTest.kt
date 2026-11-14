package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class TempestGenreKitsTest {
    private val kits = TempestGenreKits.Kit.entries
    private fun roles(patches: List<TempestPatch>) = TempestVoice.entries.associateWith { v -> patches.count { it.voice == v } }
    /** A recipe without its display name: two pads with the same sound but different names still match. */
    private fun sound(p: TempestPatch) = Triple(p.voice, p.macros, p.model)

    /**
     * The PCM24 bytes of every pad the owner kept in round tempest-kits-1 (K1–K6, 2026-11-14).
     * K1, K3 and K5 played these exact renders, so a changed recipe or engine fails here.
     */
    private val keptHashes = mapOf(
        TempestGenreKits.Kit.CIRCUIT to listOf(
            "f4a5fe82d7d993858a2d831e1851b51775328cabbf72d7ef90bbbc25eb3ab7a9", // A01
            "8f5656f1e263f1ece411be2d9f7928a3f18e69d06430d38eae1fa9bb1ab72f98", // A02
            "15328f83d7b33b0c0be390d3e49a362bc38d1d1d15a007883cf193d5d3694b0a", // A03
            "9d027b3d4474ff563363ccbc4d8c9d72e074dee9c73408b2d8defff8e16f2d67", // A04
            "ee6da6d4784a03e9f20e5897aaf3e84a9d0e693110fadfc8f3378a8f2d1acfc7", // A05
            "d502a2c342de9d5952d34c8e07224e56deabae010e32e4ee6e1ba471c8886d2e", // A06
            "d8f910d0cce9eb9034b091cf233b6d724fcf53ac15a5cd29926a606177fde8c9", // A07
            "068dba64478ac9f76e742b88e1203fa346a50d4e7e5b2bdd8fbad77ed21c7655", // A08
            "83357f690370602837cf3d4dcbc55889d3b4594622ffb23a87dcaf786c973a97", // A09
            "756e5e7ab4da520e04a4b727dd09169e3787f9ee407a76822b80384659712415", // A10
            "ce244d148e31e1afa90d83826bf54046bad461ac9e5bfefedc8a5d92d86dbff1", // A11
            "cb7536e24748b5b19a0a412094c53992294bf60d548a4a666bd9a2c517fbe2c7", // A12
            "1f0742f14617142c825c28ac3edebe2e09f3cded800f4149ddbf24a14b219aea", // A13
            "9d376efed48272aa09fbd11142f9cf16ee3565cc80c1bf0844228ae32c9bdaba", // A14
            "b67113923026eab8dc8a95551845c685f16ea59da4d719effd1f927c6ab7894a", // A15
            "5ca688cae7045a6c223f1e3a22e4c85e0a08cb24eaa334df22a93850e3d477b4", // A16
        ),
        TempestGenreKits.Kit.FOUNDRY to listOf(
            "6e0280e3a01659d66e60b3b3a1dd9c933db81a5c134864b7b4f90cb32fb1cd9b", // A01
            "4938861c3386e9efcc99f94c504e907cd3610008109748951fd8657a6fa72a93", // A02
            "eb530dec1233b77eb55503feb15efa797e180d2bcd48af5901b22d99f54bec97", // A03
            "be7963027c184bf009e706830aec3dc82bc887f1377ed4cbec55f74403397a9e", // A04
            "521a8c7c65c5c6ea40c7a42f554e2c5dc87fe36a69ea61c63b7bceb359f9347c", // A05
            "ff4de4de18bba671533f12004dea8742261cb39e4f6a8f1f34000ad2769f364b", // A06
            "540f2280ca2443628fa4e9e41bb7da69543674ca3014f45c8c0ebe4c82bae723", // A07
            "f752ec7659320393240cb968e2201035ba5f6afdfcab9350f2556ad0097e9a84", // A08
            "37efec1e6af88181c6bd1cb2e24fc5ccf3084f7adebee46d0b1f9cbc049ef635", // A09
            "9e33939dcb8c05e5c591bc46452118a4b8e1dd26a15b067bad81a5b5a94b46f6", // A10
            "d9714f2eac29eadc1202707bab7a2ec8b707ef19f2b2e723ccdd85f9b773355c", // A11
            "eb209661e9e3f1a3aafea48d79d77272509cb626195f64ec85fac2e7afa30d33", // A12
            "6b5c5af9312978f19b96a0f7ab0a874fd9628c1090ab33d6a01703c818bfcf4d", // A13
            "9b89a5cd6c1c181a1383ba11d909fc3e31f554f2617f3d14c23ffbeec8a4edd0", // A14
            "49f3371669f85b3dd9573f37a0834474e39ee984ea161ea7a9a0c5302771b514", // A15
            "e7d98762cf1a1f94c33e50a73b3680ac7da02b84907003a7c5dcbbe1c4d68819", // A16
        ),
        TempestGenreKits.Kit.STEP to listOf(
            "98bc50cf54d719b4ad7174efea28dbd31eafcbbd6c74a91bb0dca2053947a6f9", // A01
            "2f89a0f6c59d28e49864f58b904455cc7c243d07b9d2ae1dbd775f472c24c4ea", // A02
            "a957624881c58bbfa671dc8c2bde8b81c68994d40eb5c8733848af78fda50a82", // A03
            "0ee38797f191514f09a139a9d305b15485d02f2d4df2ed70359ea83381c230ef", // A04
            "9f54d8ec696ff820d344c6a8ac3def25b579884d77bff6c5479661921e71378d", // A05
            "e36e3e54875c3e9f90e210481524b2ec67d6e533eef538c1deb16f5693afe102", // A06
            "fd029d7261833b45fe5f22782c9bbe1b74336229aa09f60641cfeef9c48cbf88", // A07
            "ae8820f20e15e127335da997784dd6e25161456e221a68082b22e487442fc3a7", // A08
            "fbe6c9e8f0156120058a9cd5f39eb866b3545544447ac58688f2d7a711d4cf61", // A09
            "5dfb3ab892d517abd4784e710b802ba660ad3a76970ebbfb5585fe7187e226ec", // A10
            "1385d87aafa14c590bdccee20108c096bc4dae094f513abc8c621869946bf8e8", // A11
            "cb7536e24748b5b19a0a412094c53992294bf60d548a4a666bd9a2c517fbe2c7", // A12
            "18f88b49ecd218a24c0d0103ac686c9dd173e61318194482306bdb8557e247ad", // A13
            "205de1c1b107d6e4c1d5ee6717558e6dbbb3e48bb4556f50c883ca23dd467376", // A14
            "65c17dc73d6c470e8b751d3d5bdd629ea181301d46ac856b0572aebdeb846566", // A15
            "42020216339ab23e39cb9465f998c92a9f33ac01cd7b9da6f2e1b4a61f1acb11", // A16
        ),
    )

    @Test fun `every kept genre-kit pad retains the PCM24 bytes the owner heard`() {
        val dir = kotlin.io.path.createTempDirectory("tempest-genre-kept").toFile()
        try {
            for (kit in kits) {
                val pads = TempestGenreKits.build(kit)
                val hashes = keptHashes.getValue(kit)
                assertEquals(16, hashes.size)
                for ((i, pad) in pads.withIndex()) {
                    val file = File(dir, "probe.wav")
                    WavWriter.write(file, pad!!.snip, WavWriter.BitDepth.PCM_24)
                    val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                    assertEquals(hashes[i], actual, "${kit.name} A${"%02d".format(i + 1)} ${pad.displayName} changed since the owner kept it")
                }
            }
        } finally { dir.deleteRecursively() }
    }

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
                assertEquals(if (patch in TempestPresets.all()) "accepted" else "kept", pad.source["status"])
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
