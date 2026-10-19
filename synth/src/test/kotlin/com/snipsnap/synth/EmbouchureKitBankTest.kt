package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Severity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbouchureKitBankTest {
    @Test
    fun `the accepted sixteen pad starter retains every recipe setting and PCM24 sample`() {
        val fixtures = EmbouchurePresetAcceptance.golden.getValue("starter").arr().map { it.obj() }
        val patches = SynthKits.embouchureStarterPatches()
        assertEquals(16, patches.size)
        assertEquals(fixtures.map { it.getValue("pad").obj().getValue("recipe").obj().getValue("patch") },
            patches.map { it.toJsonValue() }, "bank expansion changed the original starter recipes or order")
        val arranged = SynthKits.embouchure()
        val root = kotlin.io.path.createTempDirectory("embouchure-starter-golden").toFile()
        try {
            val kit = KitAssembler.assembleArranged("SnipSnap Embouchure Kit", arranged, root)
            for (i in fixtures.indices) {
                val expected = fixtures[i]
                val pad = requireNotNull(arranged[i])
                assertEquals(expected.getValue("pad"), KitStore.padToJson(kit.pads[i]), "starter pad ${i + 1} settings changed")
                EmbouchurePresetAcceptance.assertWav(pad.snip, expected, "starter pad ${i + 1}")
                assertEquals(expected.getValue("sha256").str(),
                    EmbouchurePresetAcceptance.sha256(File(root, kit.pads[i].sampleFile).readBytes()))
            }
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `three dry sixteen pad banks export all thirty six presets once and twelve complementary notes`() {
        val banks = SynthKits.embouchureFactoryBanks()
        assertEquals(listOf("embouchure-reeds-air", "embouchure-brass-bass", "embouchure-bow-soft"), banks.map { it.id })
        assertEquals(EmbouchurePresets.all().toSet(), banks.flatMap { it.presetPatches }.toSet())
        assertEquals(36, banks.flatMap { it.presetPatches }.size, "a preset was exported twice")
        val expectedNotes = listOf(
            listOf(EmbouchureVoice.REED to 48, EmbouchureVoice.REED to 55,
                EmbouchureVoice.WHISTLE to 63, EmbouchureVoice.WHISTLE to 70),
            listOf(EmbouchureVoice.BASS to 36, EmbouchureVoice.BASS to 43,
                EmbouchureVoice.BRASS to 51, EmbouchureVoice.BRASS to 58),
            listOf(EmbouchureVoice.BOW to 48, EmbouchureVoice.BOW to 55,
                EmbouchureVoice.SOFT to 51, EmbouchureVoice.SOFT to 58),
        )
        val root = kotlin.io.path.createTempDirectory("embouchure-factory-banks").toFile()
        try {
            for ((bankIndex, bank) in banks.withIndex()) {
                assertEquals(12, bank.presetPatches.size)
                assertEquals(4, bank.notePatches.size)
                assertEquals((13..16).toList(), bank.noteSlots)
                assertEquals(expectedNotes[bankIndex], bank.notePatches.map { it.voice to it.midi })
                assertTrue(bank.notePatches.all { it.macros.getValue("TUNE") == 0f && it.macros.getValue("HOLD") == 0f })
                val arranged = bank.render()
                assertEquals(16, arranged.size)
                for ((index, nullablePad) in arranged.withIndex()) {
                    val pad = requireNotNull(nullablePad)
                    val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                    val patch = assertIs<EmbouchurePatch>(recipe.patch)
                    assertEquals(bank.patches[index], patch)
                    assertEquals(patch.name, pad.displayName)
                    assertNull(recipe.fx)
                    assertTrue(!recipe.alias && recipe.treatment == null && recipe.amount == null)
                    assertEquals(if (Embouchure.isLoop(patch.macros.getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL, pad.drumClass)
                    assertTrue(patch.midi in Embouchure.midiRange(patch.voice))
                    assertEquals(Embouchure.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys)
                    EmbouchurePresetAcceptance.assertAudio(pad.snip, "${bank.id} pad ${index + 1}")
                    if (index >= 12) EmbouchurePresetAcceptance.assertRoot(pad.snip, patch,
                        .6f * Embouchure.gestureSeconds(patch.voice, patch.macros))
                }
                val work = File(root, bank.id)
                val kit = KitAssembler.assembleArranged(bank.name, arranged, work)
                val saved = KitStore.load(work)
                assertEquals(kit.pads, saved.pads)
                assertEquals(bank.patches, saved.pads.map { PadRecipe.fromJsonValue(requireNotNull(it.recipe)).patch })
                for (format in listOf(ExportFormat.PROGRAM_FOLDER, ExportFormat.MPC3_TRACK)) {
                    val exported = Exporters.export(format, saved, work, File(root, "${bank.id}-${format.id}"), overwrite = true)
                    val findings = ExportReadBack.verify(saved, exported)
                    assertTrue(findings.none { it.severity == Severity.FAIL }, "${bank.id} $format: ${findings.joinToString { it.message }}")
                    assertEquals(17, findings.count { it.severity == Severity.OK })
                    assertTrue(exported.primary.isFile)
                }
                for (pad in saved.pads) EmbouchurePresetAcceptance.assertAudio(WavReader.read(File(work, pad.sampleFile)), "${bank.id} ${pad.slot} saved PCM")
            }
        } finally { root.deleteRecursively() }
    }
}
