package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TerraKits] held to the measured facts it claims: every pad filled, every
 * topology reachable, and every declared [DrumClass] matching what the real
 * [Classifier] actually says about that pad's own render - the same
 * measured-not-guessed discipline [TerraTest] holds each topology to
 * individually, now held across the whole 16-pad kit at once.
 */
class TerraKitsTest {

    @Test
    fun `the world percussion kit fills all sixteen pads`() {
        val kit = TerraKits.classic()
        assertEquals(16, kit.size)
        assertTrue(kit.all { it != null }, "every pad of a starter kit is filled")
    }

    @Test
    fun `every voice but the retired membrane reaches a pad`() {
        // COMPOUND_MEMBRANE no longer does: its last pad, A10 (Tabla Tun), moved to TUN on 2026-11-08,
        // after its other hand-drum pads had moved to the hand voices (docs/TERRA_HANDS.md). It is still
        // a voice of its own, and the frozen grid still renders its retired pads (TerraCases.RETIRED_HANDS).
        val voices = TerraKits.classic().mapNotNull { pad ->
            pad?.recipe?.let { PadRecipe.fromJsonValue(it) }?.patch?.voiceName
        }.toSet()
        assertEquals(TerraVoice.entries.map { it.name }.toSet() - TerraVoice.COMPOUND_MEMBRANE.name, voices)
    }

    /**
     * Each pad's declared class is checked against the real Classifier
     * output on that pad's own audio, not restated from the design intent -
     * this is what would have caught it immediately if a pad's declared
     * class had drifted from what TerraKits.kt actually renders.
     */
    @Test
    fun `every pad's declared class matches what the classifier actually hears`() {
        for ((i, pad) in TerraKits.classic().withIndex()) {
            val arranged = requireNotNull(pad) { "pad ${i + 1} is empty" }
            val measured = Classifier.classify(arranged.snip).drumClass
            assertEquals(
                arranged.drumClass, measured,
                "pad ${i + 1} is declared ${arranged.drumClass} but the classifier now hears $measured - " +
                    "either the declared class or the DSP behind it has drifted.",
            )
        }
    }

    /** Every pad's recipe round-trips through JSON and regenerates the same audio, bit-for-bit. */
    @Test
    fun `every pad's recipe regenerates its own audio`() {
        for ((i, pad) in TerraKits.classic().withIndex()) {
            val arranged = requireNotNull(pad) { "pad ${i + 1} is empty" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "pad ${i + 1} carries no recipe" })
            val regenerated = recipe.render()
            assertTrue(
                arranged.snip.samples.contentEquals(regenerated.samples),
                "pad ${i + 1}'s recipe did not regenerate the same audio it shipped with",
            )
        }
    }

    /**
     * The PCM24 bytes of every pad the owner kept in round terra-kit-1 (2026-11-14): E1 played these
     * sixteen renders one second apart, E2 grooved on them, and the owner kept the full kit over a
     * picks-only version. A changed recipe, voice or engine path fails here.
     */
    private val keptHashes = listOf(
        "5c1a08770a16062cd6c245982936e4ba55ed842b4e5acd5a62c5ea9dfce4a6ad", // A01 Udu Whoomp
        "e5dc15391d10d21d5600ad9951c78e7dcb90d8b5a5114026f10f2cf95fcfb191", // A02 Bayan Drag
        "c5786575d413bd5fffd6e3f13967c224c91a6b75150d8970a8caf72c7bd7c391", // A03 Djembe Bass
        "549b26a008a05bfe66f8834630b9695616f1f206595cdc3634cc1d943f815710", // A04 Cajon Low
        "cf08d6ec8375d67ca3861725f115cd651cf8efb4cf309309a02c9b0ef74c46a5", // A05 Dholak Bass
        "7f10f53609cf7ea54e7d30d57c81419214faaf8f542120eb6f61d754ae587f98", // A06 Dumbek Doum
        "75b64dbfa6018bcfcd09f4654ff77ca46653aa6175ec8d8e1b992044b552397f", // A07 Cajon Slap
        "5127667e4eb4bccba7e034c2d076afeb5579a6f57e029d7d89b51772469c962a", // A08 Djembe Open
        "40f3010a5e300d8d7979b079e4a10181cbc9adc39cb3690a3d495dffdb51bc06", // A09 Tabla Tin
        "88c9be917bbcbbd48c6c1f5a022b847dcca36ff0ced321222c40770171ec0c50", // A10 Tabla Tun
        "c7a4613fe61cc8cd7fd0840bb5b2123bfcb7c6a184bfeeef568f0e6d74e780a8", // A11 Dumbek Tek
        "966c4a2919039e55d11b9d294cb22120c99249ff026f254851a77fe37f40c2cf", // A12 Djembe Slap
        "39fc9dbbb42c86414cdbc6ff05353600cb39129bb5e5e003fbc9b38fb0133531", // A13 Agogo Low
        "096a427a271a134f0dd2f011b9c988eb1925088200316d17693d255abb74d128", // A14 Agogo High
        "680c6a3491297a470c9b83a009147d0ebd048f265c0381c480272dbc4affdf49", // A15 Agogo Clack
        "048f07542f81578ad2186135541e5e257f7aa77ecfbd5b06510d85bf8c52c4d0", // A16 Balafon Key
    )

    /** One SHA-256 over the 32 softer strikes (two per pad, softest first, pads in order), as PCM24 files. */
    private val keptSoftHash = "48a4f37ed48e925d12613408cc1d4edb906b2217d629ac9d4a6be90eaaec66ae"

    private fun pcm24(snip: com.snipsnap.audio.Snip, file: File): ByteArray {
        WavWriter.write(file, snip, WavWriter.BitDepth.PCM_24)
        return file.readBytes()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test
    fun `every kept pad and its softer strikes retain the bytes the owner heard`() {
        val dir = kotlin.io.path.createTempDirectory("terra-kept").toFile()
        try {
            val probe = File(dir, "probe.wav")
            val soft = MessageDigest.getInstance("SHA-256")
            val pads = TerraKits.classic()
            assertEquals(16, keptHashes.size)
            for ((i, nullable) in pads.withIndex()) {
                val pad = requireNotNull(nullable)
                val actual = hex(MessageDigest.getInstance("SHA-256").digest(pcm24(pad.snip, probe)))
                assertEquals(keptHashes[i], actual, "A${"%02d".format(i + 1)} ${pad.displayName} changed since the owner kept it")
                assertEquals(2, pad.softVariants.size, pad.displayName)
                for (v in pad.softVariants) soft.update(pcm24(v, probe))
            }
            assertEquals(keptSoftHash, hex(soft.digest()), "a softer strike changed since the owner kept the kit")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `both MPC formats export and read back the kept kit with its velocity zones`() {
        val root = kotlin.io.path.createTempDirectory("terra-kit-export").toFile()
        try {
            val work = File(root, "work").apply { mkdirs() }
            val staging = File(root, "out").apply { mkdirs() }
            val kit = KitAssembler.assembleArranged("Terra", TerraKits.classic(), work, null)
            assertEquals(16, kit.pads.size)
            assertTrue(kit.pads.all { it.velocityLayers.size == 3 }, "each pad keeps its two softer strikes under the hard one")
            val result = Mpc3Exporter.exportTrack(kit, work, staging, overwrite = true, mpc2Twin = true)
            val data = File(staging, Mpc3TrackWriter.trackDataDirName(kit.name))
            val twin = File(data, "${kit.name}.xpm")
            val samples = kit.pads.flatMap { p -> p.velocityLayers.map { it.sampleFile } + p.sampleFile }.toSet()
            assertEquals(samples, result.samples.map { it.name }.toSet())
            KitStore.save(kit, data)
            assertEquals(kit, KitStore.load(data))
            val findings = listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, twin, null, result.findings),
            ).flatMap { ExportReadBack.verify(kit, it) }
            assertFalse(findings.blocked(), findings.joinToString { it.message })
        } finally { root.deleteRecursively() }
    }
}
