package com.snipsnap.synth

import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.audio.Snip
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic descriptor proof; this metadata repair does not re-render a musical keyboard. */
class MeridianKeyboardDescriptionTest {
    @Test fun `held coverage text follows both the actual bounded and genuinely spanned descriptors`() {
        for (range in listOf(36..72, 0..127)) {
            val program = KeygroupProgram("Meridian Coverage", listOf(Keygroup(range.first, range.last,
                57, listOf(VelocityLayer("note", 32, 0, 127)))))
            assertEquals(range, MeridianKitGenerator.heldKeyboardCoverage(program))
            val text = MeridianKitGenerator.heldKeyboardRangeText(program)
            assertTrue("MIDI ${range.first}-${range.last}" in text)
            assertTrue("outside that range are unmapped" in text)
            if (range == 36..72) assertFalse("MIDI 0-127" in text)
        }
    }

    @Test fun `bounded source description agrees with actual phone and native keygroup coverage`() {
        val root = createTempDirectory("meridian-description").toFile()
        try { for (range in listOf(36..72, 48..54)) {
            val midis = (range.first..range.last step 3).toList()
            val groups = midis.map { midi -> Keygroup(maxOf(range.first, midi - 1), minOf(range.last, midi + 1),
                midi, listOf(VelocityLayer("note$midi", 32, 0, 127))) }
            val program = KeygroupProgram("Meridian Bounds${range.first}", groups)
            val samples = midis.associate { "note$it" to Snip(FloatArray(32), 1, 44_100) }
            OneNote.writePackage(program.name, program, samples, root, overwrite = false, spanKeyboard = false)
            val instrument = InstrumentStore.list(root).single { it.second.name == program.name }.second
            val phoneRange = instrument.zones.minOf { it.lowNote }..instrument.zones.maxOf { it.highNote }
            val native = Mpc3Project.read(File(root, "${program.name}.xty"))
                .drumPrograms().single().getValue("instruments").arr().take(groups.size).map { it.obj() }
            val nativeRange = native.minOf { it.getValue("lowNote").int() }..native.maxOf { it.getValue("highNote").int() }
            val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(root, "${program.name}_[TrackData]/${program.name}.xpm"))
            val slots = xml.getElementsByTagName("Instrument")
            val legacyNotes = (0 until groups.size).flatMap { i ->
                val slot = slots.item(i) as org.w3c.dom.Element
                val low = slot.getElementsByTagName("LowNote").item(0).textContent.toInt()
                val high = slot.getElementsByTagName("HighNote").item(0).textContent.toInt()
                (low..high).toList()
            }
            assertEquals(range.toList(), legacyNotes)
            assertEquals(range, phoneRange)
            assertEquals(phoneRange, nativeRange)
            assertEquals(phoneRange, MeridianKitGenerator.heldKeyboardCoverage(program))
            assertTrue("MIDI ${nativeRange.first}-${nativeRange.last}" in MeridianKitGenerator.heldKeyboardRangeText(program))
        } } finally { root.deleteRecursively() }
    }
}
