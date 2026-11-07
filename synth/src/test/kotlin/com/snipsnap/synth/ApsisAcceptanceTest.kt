package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.KitStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ApsisAcceptanceTest {
    @Test fun `the acceptance layout retains six factory gestures normal touch and separate carry copies`() {
        val bank = ApsisAcceptanceBank.entries()
        assertEquals((1..16).toList(), bank.map { it.slot })
        assertEquals((ApsisPresets.forVoice(ApsisVoice.GRAIN) + ApsisPresets.forVoice(ApsisVoice.ANCHOR)).map { it.name }.toSet(),
            bank.map { it.preset }.toSet())
        assertEquals(listOf(15, 16), bank.filter { it.held }.map { it.slot })
        for (pad in bank) {
            assertEquals(listOf(.35f, .65f, 1f), pad.layers().map { it.patch.velocity })
            assertEquals(listOf(1..41, 42..84, 85..127), pad.layers().map { it.velStart..it.velEnd })
            val factory = ApsisPresets.forVoice(pad.patch.voice).single { it.name == pad.preset }
            assertEquals(factory.macros, pad.patch.macros)
            assertEquals(factory.config, pad.patch.config)
            assertEquals(factory.velocity, pad.layers()[1].patch.velocity)
        }
        assertEquals(bank[0].patch, bank[12].patch)
        assertEquals(bank[4].patch, bank[13].patch)
    }

    @Test fun `both native programs and every score reopen the exported PCM and saved recipes`() {
        val root = createTempDirectory("apsis-acceptance").toFile()
        try {
            val zip = ApsisAcceptanceGenerator.generate(root)
            val destination = File(root, ApsisAcceptanceGenerator.NAME)
            val kit = KitStore.load(File(destination, "${ApsisAcceptanceGenerator.NAME}_[TrackData]"))
            assertEquals(16, kit.pads.size)
            assertEquals(listOf(15, 16), kit.pads.filter { it.loop }.map { it.slot })
            assertEquals((1..16).toList(), kit.pads.map { it.muteGroup })
            assertEquals(48, kit.pads.sumOf { it.velocityLayers.size })
            ApsisKitGenerator.verifyPrograms(destination, kit)
            ZipFile(zip).use { archive ->
                assertEquals(48, archive.entries().asSequence().count { it.name.endsWith(".wav") })
                assertTrue(archive.entries().asSequence().any { it.name.endsWith(".xtd") })
                assertTrue(archive.entries().asSequence().any { it.name.endsWith(".xpm") })
            }
            val scores = File(root, "scores").listFiles()!!.sortedBy { it.name }
            assertEquals(6, scores.size)
            for (file in scores) {
                val score = ApsisMusicalScore.fromJsonValue(Json.parse(file.readText())) { path, sha ->
                    ApsisAcceptanceGenerator.readSample(root, path, sha)
                }
                val samples = ApsisScorePlayer.render(score)
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, Snip(samples, 1, Dsp.RATE)) }.toByteArray()
                assertContentEquals(expected, File(root, "audio/${score.id}_raw.wav").readBytes(), score.id)
                assertTrue(score.pads.all { it.layers.size == 3 })
            }
            val manifest = Json.parse(File(root, "kit-manifest.json").readText()).obj()
            val first = manifest.getValue("pads").arr().first().obj().getValue("layers").arr().first().obj()
            val error = assertFailsWith<IllegalArgumentException> {
                ApsisAcceptanceGenerator.readSample(root, first.getValue("sample").str(), "incorrect checksum")
            }
            assertTrue(error.message.orEmpty().contains("checksum differs"))
        } finally { root.deleteRecursively() }
    }
}
