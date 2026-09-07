package com.snipsnap.kit

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class KitAssemblerLocaleTest {

    private val original: Locale = Locale.getDefault()

    @AfterTest
    fun restore() {
        Locale.setDefault(original)
    }

    // Snip(samples, channels, sampleRate) — channels comes second; passing
    // the rate there trips `require(channels in 1..2)`.
    private fun tone(): Snip = Snip(FloatArray(2048) { 0.2f }, 1, 44100)

    @Test
    fun `sample stems and display names stay ASCII under an eastern-digit locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val dir = Files.createTempDirectory("kit-locale").toFile()
        val arranged = listOf(
            ArrangedPad(tone(), DrumClass.KICK),
            ArrangedPad(tone(), DrumClass.KICK),
            ArrangedPad(tone(), DrumClass.SNARE),
        )
        val kit = KitAssembler.assembleArranged("LOCALE KIT", arranged, dir)
        for (pad in kit.pads) {
            assertTrue(
                pad.sampleFile.all { it.code in 32..126 },
                "non-ASCII in sample filename: ${pad.sampleFile}",
            )
            assertTrue(
                pad.displayName.all { it.code in 32..126 },
                "non-ASCII in display name: ${pad.displayName}",
            )
            assertTrue(Names.isMpcSafe(pad.sampleStem), "not MPC-safe: ${pad.sampleStem}")
        }
    }
}
