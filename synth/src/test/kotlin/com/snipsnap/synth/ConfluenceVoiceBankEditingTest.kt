package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfluenceVoiceBankEditingTest {
    companion object {
        private val bank by lazy { ConfluenceVoiceBank.bundled() }
        private val original by lazy { bank.preset(bank.mainPresetId).recipe }
        private val edited by lazy {
            original.copy(name = "Rounded variation", space = .42f, divergence = .32f, answerDelaySeconds = .85f,
                roles = original.roles.map { spec ->
                    when (spec.role) {
                        ConfluenceRole.FOUNDATION -> spec.copy(region = bank.voice("round-oo").region, gain = .38f)
                        ConfluenceRole.AIRY -> spec.copy(gain = .7f)
                        ConfluenceRole.ANSWER -> spec.copy(gain = .85f)
                    }
                })
        }
        private val editedReport by lazy { bank.render(edited) }
    }

    @Test
    fun `valid edited controls and eligible foundation selection change the rendered gathering`() {
        val stock = bank.render(bank.mainPresetId)
        val variation = editedReport
        assertEquals(edited, variation.recipe)
        assertEquals(stock.mix.frameCount, variation.mix.frameCount)
        assertFalse(stock.mix.samples.contentEquals(variation.mix.samples))
        assertTrue(variation.mix.samples.all { it.isFinite() })
        assertTrue(variation.mix.peak() in .01f..0.991f)
        val foundations = variation.events.filter { it.role == ConfluenceRole.FOUNDATION && it.state == "ENTER" }
        assertTrue(foundations.isNotEmpty())
        assertTrue(foundations.all { it.sourceHash == bank.voice("round-oo").region.assetHash })
        assertEquals(original.roles.map { it.performerIds }, edited.roles.map { it.performerIds },
            "editing a voice or mix control does not silently change the fixed roster")
    }

    @Test
    fun `an edited recipe round trip regenerates exact audio traces and WAV bytes`() {
        val restored = ConfluenceAssemblyRecipe.fromJsonText(edited.toJsonText())
        val first = editedReport
        val replayed = bank.render(restored)
        assertContentEquals(first.mix.samples, replayed.mix.samples)
        assertEquals(first.performers, replayed.performers)
        assertEquals(first.events, replayed.events)
        fun wav(report: ConfluenceAssemblyReport): ByteArray = ByteArrayOutputStream().also {
            WavWriter.write(it, report.mix)
        }.toByteArray()
        assertContentEquals(wav(first), wav(replayed))
        // The new recipe overload follows the established factory path exactly.
        val byName = bank.render(bank.mainPresetId, normalize = false)
        val byRecipe = bank.render(original, normalize = false)
        assertContentEquals(byName.raw, byRecipe.raw)
        assertEquals(byName.events, byRecipe.events)
    }

    @Test
    fun `edited pad metadata retains the exact complete recipe source bindings and credits`() {
        val pad = bank.arrangedPad(edited, "My Gathering")
        assertEquals("My Gathering", pad.displayName)
        assertEquals(DrumClass.LOOP, pad.drumClass)
        assertTrue(pad.oneShot)
        assertEquals(null, pad.recipe, "asset-dependent gatherings do not masquerade as asset-free patches")
        assertContentEquals(editedReport.mix.samples, pad.snip.samples)
        assertEquals(bank.id, pad.source["bankId"])
        assertEquals(bank.version.toString(), pad.source["bankVersion"])
        assertEquals(edited.toJsonText(), pad.source["assemblyRecipe"])
        assertEquals("FOUNDATION,AIRY,ANSWER", pad.source["roles"])
        assertEquals(edited.roles.joinToString(",") { it.region.assetHash }, pad.source["sourceHashes"])
        val bindings = Json.parse(pad.source.getValue("roleVoiceIds")).obj().mapValues { it.value.str() }
        assertEquals(mapOf("FOUNDATION" to "round-oo", "AIRY" to "breathy-arc", "ANSWER" to "clear-arc"), bindings)
        assertFalse("presetId" in pad.source, "an edited arrangement must not claim an unchanged factory preset")
        assertEquals("CC-BY-4.0", pad.source["license"])
        assertEquals("https://creativecommons.org/licenses/by/4.0/", pad.source["licenseUrl"])
        assertTrue(pad.source.getValue("attribution").contains("VocalSet"))
        assertTrue(pad.source.getValue("sourceUrl").contains("zenodo.org/records/1193957"))
        assertTrue(pad.source.getValue("modifications").contains("exact stored Confluence assembly recipe"))
        assertFailsWith<IllegalArgumentException> { bank.arrangedPad(edited, " ") }
    }

    @Test
    fun `bank editing rejects cropped shifted retuned and unknown canonical source references`() {
        val foundation = original.roles.single { it.role == ConfluenceRole.FOUNDATION }
        val region = foundation.region
        val invalid = listOf(
            region.copy(startFrame = region.startFrame + 1),
            region.copy(endFrame = region.endFrame - 1),
            region.copy(sustainStartFrame = region.sustainStartFrame!! + 1),
            region.copy(sustainEndFrame = region.sustainEndFrame!! - 1),
            region.copy(referenceMidi = region.referenceMidi!! + .01f),
            region.copy(assetHash = "0".repeat(64)),
        )
        for (forged in invalid) {
            val recipe = original.copy(roles = original.roles.map { if (it.role == foundation.role) it.copy(region = forged) else it })
            assertFailsWith<IllegalArgumentException>("changed region $forged") { bank.render(recipe) }
            assertFailsWith<IllegalArgumentException>("changed pad region $forged") { bank.arrangedPad(recipe) }
        }
        assertFailsWith<IllegalArgumentException> { foundation.copy(region = region.copy(mode = ConfluenceSourceMode.TEXTURE)) }
        for ((role, wrongVoice) in listOf(ConfluenceRole.FOUNDATION to "clear-arc", ConfluenceRole.AIRY to "round-oo", ConfluenceRole.ANSWER to "breathy-arc")) {
            val misassigned = original.copy(roles = original.roles.map { if (it.role == role) it.copy(region = bank.voice(wrongVoice).region) else it })
            assertFailsWith<IllegalArgumentException> { bank.render(misassigned) }
        }
    }

    @Test
    fun `edited arrangements preserve whole native gestures and answer after the final upper release`() {
        val report = editedReport
        assertTrue(report.exchanges > 0)
        for (enter in report.events.filter { it.state == "ENTER" }) {
            val release = report.events.single { it.performerId == enter.performerId && it.eventIndex == enter.eventIndex && it.state == "RELEASE" }
            val selected = (enter.sourceEndFrame - enter.sourceStartFrame) * enter.durationRatio
            assertEquals(selected.roundToInt(), release.endFrame - enter.startFrame)
            assertTrue(abs(enter.pitchSemitones) < .5f, "editing mix controls retains each source's native register")
        }
        for (exchange in 0 until report.exchanges) {
            val callEnd = report.events.filter { it.role == ConfluenceRole.AIRY && it.eventIndex == exchange && it.state == "RELEASE" }.maxOf { it.endFrame }
            val answerStart = report.events.filter { it.role == ConfluenceRole.ANSWER && it.eventIndex == exchange && it.state == "ENTER" }.minOf { it.startFrame }
            assertTrue(answerStart >= callEnd + (edited.answerDelaySeconds * Confluence.RATE).roundToInt() - 1)
        }
        assertTrue(report.events.filter { it.state == "RELEASE" }.all { it.endFrame < report.mix.frameCount })
    }
}
