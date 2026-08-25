package com.snipsnap.kit

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.mpc3.Acvs
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TempoMetadataTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("tempo").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun snip(seconds: Float = 0.2f): Snip =
        Cleanup.process(
            Snip(FloatArray((44_100 * seconds).toInt()) { i -> (0.5 * Math.sin(i / 15.0)).toFloat() }, 1, 44_100),
        )

    @Test
    fun `tempo round-trips through kit json and loop stems carry it`() {
        val dir = File(temp, "Kit")
        val kit = KitAssembler.assembleArranged(
            "Tempo Kit",
            listOf(
                ArrangedPad(snip(), DrumClass.KICK),
                ArrangedPad(snip(2f), DrumClass.LOOP),
            ),
            dir,
            tempoBpm = 92.4f,
        )
        assertEquals(92.4f, kit.tempoBpm)
        assertEquals(92.4f, KitStore.load(dir).tempoBpm)

        assertEquals("A01_Kick_01.wav", kit.pad(1)?.sampleFile, "non-loops are unchanged")
        assertEquals("A02_Loop_92bpm_01.wav", kit.pad(2)?.sampleFile, "the loop's tempo rides in its stem")
        assertTrue(File(dir, "A02_Loop_92bpm_01.wav").isFile)

        // Absent tempo: no bpm anywhere, old kits load unchanged.
        val plainDir = File(temp, "Plain")
        val plain = KitAssembler.assembleArranged(
            "Plain", listOf(ArrangedPad(snip(2f), DrumClass.LOOP)), plainDir,
        )
        assertEquals(null, plain.tempoBpm)
        assertEquals("A01_Loop_01.wav", plain.pad(1)?.sampleFile)

        assertFailsWith<IllegalArgumentException> { Kit("X", emptyList(), tempoBpm = -1f) }
    }

    @Test
    fun `the project export uses the kit's remembered tempo as master tempo`() {
        val dir = File(temp, "Proj")
        val kit = KitAssembler.assembleArranged(
            "Proj Kit", listOf(ArrangedPad(snip(), DrumClass.KICK)), dir, tempoBpm = 87.5f,
        )
        val card = File(temp, "card")
        val outcome = Exporters.export(ExportFormat.MPC3_PROJECT, kit, dir, card)
        val payload = Acvs.read(outcome.primary).payloadText
        assertTrue("\"masterTempo\": 87.5" in payload, "kit tempo should become the project tempo")

        // An explicit call-site tempo wins over the remembered one.
        val outcome2 = Exporters.export(
            ExportFormat.MPC3_PROJECT, kit, dir, File(temp, "card2"), tempoBpm = 120f,
        )
        assertTrue("\"masterTempo\": 120" in Acvs.read(outcome2.primary).payloadText)
    }
}
