package com.snipsnap.shell

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * September UAT, finding 15: every kit read DRAFT forever because nothing
 * recorded a dub. These pin what the chip may honestly claim.
 */
class DubStampTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("dubstamp").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun kitDir(name: String) = File(temp, name).apply { mkdirs() }

    @Test
    fun `a kit that was never dubbed is a DRAFT`() {
        assertEquals(DubStamp.Status.DRAFT, DubStamp.status(null, currentCardTree = "content://card/A"))
        assertEquals(DubStamp.Status.DRAFT, DubStamp.status(null, currentCardTree = null))
    }

    @Test
    fun `a dub that reached no card reads DUBBED, whatever card is in the phone`() {
        val stamp = DubStamp.Stamp(atMillis = 1L, cardTree = null)
        assertEquals(DubStamp.Status.DUBBED, DubStamp.status(stamp, currentCardTree = null))
        assertEquals(DubStamp.Status.DUBBED, DubStamp.status(stamp, currentCardTree = "content://card/A"))
    }

    @Test
    fun `ON CARD means the card in the phone right now, and nothing weaker`() {
        val stamp = DubStamp.Stamp(atMillis = 1L, cardTree = "content://card/A")
        assertEquals(DubStamp.Status.ON_CARD, DubStamp.status(stamp, currentCardTree = "content://card/A"))

        // A different card is in the phone: the kit is on *a* card, not this
        // one. Saying ON CARD here would point the user at a card that does
        // not hold their kit.
        assertEquals(DubStamp.Status.DUBBED, DubStamp.status(stamp, currentCardTree = "content://card/B"))

        // No card at all - forgotten, or never granted since. Same rule.
        assertEquals(DubStamp.Status.DUBBED, DubStamp.status(stamp, currentCardTree = null))
    }

    /**
     * The stamp lives inside the kit folder so a rename carries it, which
     * also means a kit shared in from elsewhere can arrive carrying one. It
     * must not be able to claim this phone's card.
     */
    @Test
    fun `a stamp from someone else's phone cannot claim this phone's card`() {
        val theirs = DubStamp.Stamp(atMillis = 1L, cardTree = "content://com.other.provider/tree/1234")
        assertEquals(DubStamp.Status.DUBBED, DubStamp.status(theirs, currentCardTree = "content://card/A"))
    }

    @Test
    fun `a stamp round-trips through the sidecar`() {
        val dir = kitDir("RoundTrip")
        DubStamp.write(dir, DubStamp.Stamp(atMillis = 1_726_000_000_000L, cardTree = "content://card/A"))
        assertEquals(DubStamp.Stamp(1_726_000_000_000L, "content://card/A"), DubStamp.read(dir))

        // And the cardless shape, which is a different JSON (no key at all).
        DubStamp.write(dir, DubStamp.Stamp(atMillis = 7L, cardTree = null))
        assertEquals(DubStamp.Stamp(7L, null), DubStamp.read(dir))
    }

    @Test
    fun `the sidecar is named so the shelf never lists it as a kit`() {
        // The shelf lists directories holding a kit.json; a leading dot keeps
        // this out of the way of anything that scans by name as well.
        assertTrue(DubStamp.FILE.startsWith("."), DubStamp.FILE)
        assertTrue(DubStamp.FILE != "kit.json")
    }

    /**
     * A torn write, a hand-edit, a version this build cannot read: the kit
     * reads DRAFT. Under-claiming is the safe direction, and a shelf drawing
     * twenty rows must not throw because one file is bad.
     */
    @Test
    fun `an unreadable stamp is no stamp, not a crash`() {
        val dir = kitDir("Torn")
        assertNull(DubStamp.read(dir), "no file at all")

        File(dir, DubStamp.FILE).writeText("{ this is not json")
        assertNull(DubStamp.read(dir), "unparseable")

        File(dir, DubStamp.FILE).writeText("""{"version":1}""")
        assertNull(DubStamp.read(dir), "no timestamp - nothing to claim")

        File(dir, DubStamp.FILE).writeText("""[1,2,3]""")
        assertNull(DubStamp.read(dir), "not an object")

        // A blank card uri is not a card.
        File(dir, DubStamp.FILE).writeText("""{"version":1,"atMillis":5,"cardTree":"  "}""")
        assertEquals(DubStamp.Stamp(5L, null), DubStamp.read(dir))
    }

    /**
     * A schema this build does not know may put anything in these fields.
     * Half-reading one would let a stranger's file decide what the shelf
     * claims about a card, so the version is checked rather than merely
     * written - `Crate.loadIndex`'s own rule.
     */
    @Test
    fun `a stamp from a schema this build does not know is no stamp`() {
        val dir = kitDir("Versions")
        File(dir, DubStamp.FILE).writeText("""{"version":2,"atMillis":5,"cardTree":"content://card/A"}""")
        assertNull(DubStamp.read(dir), "a future version")

        File(dir, DubStamp.FILE).writeText("""{"atMillis":5,"cardTree":"content://card/A"}""")
        assertNull(DubStamp.read(dir), "no version at all - nothing this app writes lacks one")

        File(dir, DubStamp.FILE).writeText("""{"version":"1","atMillis":5}""")
        assertNull(DubStamp.read(dir), "a version of the wrong type")
    }

    @Test
    fun `the three chips are three different words`() {
        val words = DubStamp.Status.entries.map { Copy.dubChip(it) }
        assertEquals(words.size, words.toSet().size, "each state must read differently: $words")
        for (w in words) {
            assertEquals(w.uppercase(java.util.Locale.ROOT), w, "TapeOS shouts: $w")
            assertTrue(!w.endsWith("."), "a chip is furniture, not a sentence: $w")
            assertTrue(w.length <= 10, "it sits on a kit row: $w")
        }
        assertEquals("ON CARD", Copy.dubChip(DubStamp.Status.ON_CARD))
        assertEquals("DRAFT", Copy.dubChip(DubStamp.Status.DRAFT), "the word the shelf already used stays put")
    }
}
