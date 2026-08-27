package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.kit.WearLedger
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JCardTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("jcard").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun model(name: String): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name.replace(' ', '_')))
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        m.assign(2, DrumSynth.snare(), DrumClass.SNARE)
        m.assign(3, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        m.save()
        return m
    }

    @Test
    fun `the card is deterministic and shaped like a J-card`() {
        val m = model("Chamber")
        val a = JCard.png(m.kit, m.kitDir)
        val b = JCard.png(m.kit, m.kitDir)
        assertTrue(a.contentEquals(b), "same kit, same card, same bytes")

        val img = ImageIO.read(ByteArrayInputStream(a))
        assertEquals(JCard.DEFAULT_WIDTH, img.width)
        val expected = (JCard.DEFAULT_WIDTH * 0.64f).toInt() * 2 + (JCard.DEFAULT_WIDTH * 0.1475f).toInt()
        assertEquals(expected, img.height, "front + spine + back")

        // The card reflects the kit: a worn kit's card differs (the spine
        // carries the mileage), and so does a renamed one.
        val worn = m.kit.copy(wear = WearLedger(mileage = 500.0))
        assertTrue(!JCard.png(worn, m.kitDir).contentEquals(a), "mileage shows on the card")
    }

    @Test
    fun `long names and a full 32-pad kit still fit`() {
        val big = model("The Longest Kit Name Anyone Ever Typed Onto A Cassette")
        for (slot in 4..32) {
            big.assign(slot, DrumSynth.closedHat(seed = slot), DrumClass.PERC)
        }
        big.save()
        assertEquals(32, big.kit.pads.size)
        val png = JCard.png(big.kit, big.kitDir)
        assertTrue(png.isNotEmpty(), "the 32-pad card renders")
        val img = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(JCard.DEFAULT_WIDTH, img.width)
    }

    @Test
    fun `every starter kit renders its insert`() {
        StarterKits.ALL.forEachIndexed { i, starter ->
            val dir = File(temp, "starter-$i")
            val kit = starter.render("Starter ${starter.id}", dir)
            val png = JCard.png(kit, dir)
            assertTrue(png.isNotEmpty(), "${starter.id} renders a card")
        }
    }

    @Test
    fun `the groove notation folds the pattern to sixteen steps`() {
        val s16 = Mpc3Clip.PULSES_PER_16TH
        val clip = Mpc3Clip(
            "G", 2,
            listOf(
                Mpc3Note(36, 0, 0.9f),
                Mpc3Note(38, 4 * s16, 0.6f),
                // Bar two lands on the same folded step as the kick.
                Mpc3Note(36, Mpc3Clip.PULSES_PER_BAR, 0.5f),
            ),
        )
        val steps = JCard.stepVelocities(clip)
        assertEquals(16, steps.size)
        assertEquals(0.9f, steps[0], "the louder pass wins the folded step")
        assertEquals(0.6f, steps[4])
        assertEquals(0f, steps[1])
    }
}
