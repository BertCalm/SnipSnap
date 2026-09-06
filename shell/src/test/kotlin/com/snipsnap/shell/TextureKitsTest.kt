package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextureKitsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("texture").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val rate = 44_100

    /** A one-second hit: low half sings 220 Hz, high half 2 kHz - position is audible. */
    private fun hit(seconds: Float = 1f): Snip = Snip(
        FloatArray((seconds * rate).toInt()) { i ->
            val hz = if (i < (seconds * rate).toInt() / 2) 220.0 else 2000.0
            (0.5 * Math.sin(2.0 * Math.PI * hz * i / rate)).toFloat()
        },
        1, rate,
    )

    private fun padBytes(dir: File): List<ByteArray> {
        val kit = KitStore.load(dir)
        return kit.pads.sortedBy { it.slot }.map { File(dir, it.sampleFile).readBytes() }
    }

    @Test
    fun `sculpt grows four LOOP takes with provenance and recipes, deterministic per seed`() {
        val dir = File(temp, "Hit Sculpt")
        val lines = mutableListOf<String>()
        val kit = TextureKits.render("Hit Sculpt", dir, hit(), "Kit:A01", TextureKits.Spec.Sculpt("cloud", 2f, 11L)) { lines += it }
        assertEquals(4, kit.pads.size)
        assertEquals(4, lines.size, "one line per take")
        for ((i, pad) in kit.pads.sortedBy { it.slot }.withIndex()) {
            assertEquals(DrumClass.LOOP, pad.drumClass)
            assertEquals("Cloud ${i + 1}", pad.displayName)
            assertEquals("Kit:A01", pad.source["sculptedFrom"])
            assertEquals("cloud", pad.source["mode"])
            assertEquals((11 + i).toString(), pad.source["seed"])
            val recipe = (pad.recipe!!.entries["sculpt"] as JsonValue.Obj).entries
            assertEquals("cloud", (recipe["mode"] as JsonValue.Str).value)
            assertEquals(2.0, (recipe["seconds"] as JsonValue.Num).value)
            val snip = WavReader.read(File(dir, pad.sampleFile))
            assertEquals(2f, snip.durationSeconds, 0.01f)
            assertEquals(2, snip.channels)
        }
        val again = File(temp, "Again")
        TextureKits.render("Again", again, hit(), "Kit:A01", TextureKits.Spec.Sculpt("cloud", 2f, 11L))
        assertEquals(padBytes(dir).map { it.toList() }, padBytes(again).map { it.toList() }, "same seed, same bytes")
        assertFailsWith<IllegalArgumentException> { TextureKits.sculptParams("blizzard") }
    }

    @Test
    fun `stretch slows the whole hit, four seeds, clamped to a minute`() {
        val dir = File(temp, "Hit Stretched")
        val kit = TextureKits.render("Hit Stretched", dir, hit(), "Kit:A01", TextureKits.Spec.Stretch(8f, 3L))
        assertEquals(4, kit.pads.size)
        val takes = kit.pads.sortedBy { it.slot }
        for ((i, pad) in takes.withIndex()) {
            assertEquals("Stretch ${i + 1}", pad.displayName)
            assertEquals(DrumClass.LOOP, pad.drumClass)
            assertEquals("stretch", pad.source["mode"])
            assertEquals("Kit:A01", pad.source["stretchedFrom"])
            val snip = WavReader.read(File(dir, pad.sampleFile))
            assertEquals(8f, snip.durationSeconds, 0.05f, "x8 of one second")
            val recipe = (pad.recipe!!.entries["stretch"] as JsonValue.Obj).entries
            assertEquals(8.0, (recipe["factor"] as JsonValue.Num).value, 1e-6)
            assertEquals((3 + i).toDouble(), (recipe["seed"] as JsonValue.Num).value)
        }
        val bytes = padBytes(dir)
        assertTrue(!bytes[0].contentEquals(bytes[1]), "different seeds, different phases")

        // A four-second source at x32 would run 128 s: the factor gives, the whole hit still fits.
        val long = File(temp, "Long")
        val longKit = TextureKits.render("Long", long, hit(4f), "Kit:A02", TextureKits.Spec.Stretch(32f, 1L))
        val snip = WavReader.read(File(long, longKit.pads.first().sampleFile))
        assertEquals(TextureKits.MAX_TEXTURE_SEC, snip.durationSeconds, 0.1f)
        val recipe = (longKit.pads.first().recipe!!.entries["stretch"] as JsonValue.Obj).entries
        assertEquals(15.0, (recipe["factor"] as JsonValue.Num).value, 1e-3, "the effective factor is what the recipe records")
    }

    @Test
    fun `freeze holds four instants - the loudest, then a quarter, half, three quarters in`() {
        val dir = File(temp, "Hit Frozen")
        val kit = TextureKits.render("Hit Frozen", dir, hit(), "Kit:A01", TextureKits.Spec.Freeze(3f, 5L))
        val takes = kit.pads.sortedBy { it.slot }
        val ats = takes.map { ((it.recipe!!.entries["stretch"] as JsonValue.Obj).entries["at"] as JsonValue.Num).value }
        assertEquals(listOf(0.25, 0.5, 0.75), ats.drop(1).map { Math.round(it * 100) / 100.0 })
        for ((i, pad) in takes.withIndex()) {
            assertEquals("Frozen ${i + 1}", pad.displayName)
            assertEquals("freeze", pad.source["mode"])
            assertEquals(3f, WavReader.read(File(dir, pad.sampleFile)).durationSeconds, 0.05f)
        }
        // The half-way instant sits at the seam; the quarter is pure 220 Hz, the three-quarter pure 2 kHz.
        fun energyAt(pad: Int, hz: Double): Double {
            val s = WavReader.read(File(dir, takes[pad].sampleFile))
            var re = 0.0
            var im = 0.0
            for (f in 0 until s.frameCount) {
                val v = s.samples[f * 2].toDouble()
                val ph = 2.0 * Math.PI * hz * f / rate
                re += v * Math.cos(ph)
                im += v * Math.sin(ph)
            }
            return re * re + im * im
        }
        assertTrue(energyAt(1, 220.0) > 10 * energyAt(1, 2000.0), "a quarter in is the low tone")
        assertTrue(energyAt(3, 2000.0) > 10 * energyAt(3, 220.0), "three quarters in is the high tone")
    }

    @Test
    fun `the panel's data - kinds, modes, knobs, names`() {
        assertEquals(listOf("SCULPT", "STRETCH"), TextureKits.KINDS)
        assertEquals(listOf("CLOUD", "SCRUB", "SWARM"), TextureKits.modesFor("SCULPT"))
        assertEquals(listOf("SLOW", "FREEZE"), TextureKits.modesFor("STRETCH"))
        assertFailsWith<IllegalArgumentException> { TextureKits.modesFor("SMEAR") }

        val by = TextureKits.knobFor("STRETCH", "SLOW")
        assertEquals("BY", by.label)
        assertEquals(8f, by.value(by.defaultFraction), 0.05f)
        assertEquals("×8", TextureKits.knobLabel(by, 8f))
        assertEquals("8 s", TextureKits.knobLabel(TextureKits.knobFor("SCULPT", "CLOUD"), 8.2f))
        assertEquals("HOLD", TextureKits.knobFor("STRETCH", "FREEZE").label)

        val spec = TextureKits.spec("SCULPT", "SWARM", TextureKits.knobFor("SCULPT", "SWARM").defaultFraction, 9L)
        assertEquals(TextureKits.Spec.Sculpt("swarm", 8f, 9L), (spec as TextureKits.Spec.Sculpt).copy(seconds = 8f))
        assertTrue(TextureKits.spec("STRETCH", "FREEZE", 0f, 1L) is TextureKits.Spec.Freeze)
        assertEquals(TextureKits.Spec.Stretch(2f, 1L), TextureKits.spec("STRETCH", "SLOW", 0f, 1L))

        assertEquals("Kick Sculpt", TextureKits.kitName("Kick", TextureKits.Spec.Sculpt("cloud")))
        assertEquals("Kick Frozen", TextureKits.kitName("Kick", TextureKits.Spec.Freeze()))
        assertEquals("Stretch 3", TextureKits.takeName(TextureKits.Spec.Stretch(), 2))
    }
}
