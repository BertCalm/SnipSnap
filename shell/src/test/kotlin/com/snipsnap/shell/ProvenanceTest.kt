package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitPad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one reader of a pad's `source` map.
 *
 * Before [Provenance] there were three, in `:app`, [LinerNotes] and
 * [Lineage], with different key lists and different orders — so these
 * tests are mostly about the two things that had gone wrong: **which
 * stamp wins**, and **that every door is known**. The second is checked
 * exhaustively over [Provenance.Kind] rather than case by case, because
 * the way the old readers rotted was a door being added and only one of
 * them hearing about it.
 */
class ProvenanceTest {

    private fun pad(slot: Int, source: Map<String, String>) =
        KitPad(slot = slot, sampleFile = "p$slot.wav", drumClass = DrumClass.KICK, source = source)

    /** A minimal `source` map that makes [kind] the winner, plus the extra keys its phrase reads. */
    private fun sourceFor(kind: Provenance.Kind): Map<String, String> = when (kind) {
        Provenance.Kind.DUG -> mapOf("song" to "Track 07.wav", "at" to "1:32")
        Provenance.Kind.CAPTURED -> mapOf("app" to "Player", "title" to "Side B")
        Provenance.Kind.STRETCHED -> mapOf("stretchedFrom" to "A01", "mode" to "stretch")
        Provenance.Kind.CHOPPED_IN_APP -> mapOf("origin" to "chop")
        else -> mapOf(kind.key to "Parent")
    }

    @Test
    fun `every door the enum knows gets a phrase, and nothing else does`() {
        for (kind in Provenance.Kind.entries) {
            val source = sourceFor(kind)
            assertEquals(kind, Provenance.kindOf(source), "$kind should be read back from $source")
            val phrase = Provenance.phrase(source)
            assertTrue(phrase != null && phrase.isNotBlank(), "$kind has no phrase")
            // Lower case, because LinerNotes makes its sentences by
            // capitalising one of these - a phrase that already shouted
            // would come out of that untouched and read as a heading.
            assertTrue(phrase!!.first().isLowerCase(), "a phrase starts lower case: $phrase")
        }

        assertNull(Provenance.phrase(emptyMap()), "a pad built by hand carries no stamp")
        assertNull(Provenance.kindOf(mapOf("level" to "0.7")), "an unrelated key is not provenance")
        assertNull(
            Provenance.kindOf(mapOf("origin" to "synth")),
            "`origin` is a general key and only its \"chop\" value is a stamp",
        )
    }

    @Test
    fun `when a pad carries several stamps the enum's order decides, and that order is the file's`() {
        // BREED and EVIL TWINS copy their parent's keys, so a pad really
        // can hold three of these at once. Whichever one the enum declares
        // first wins, everywhere, which is the whole point of the type.
        val many = mapOf(
            "song" to "Track 07.wav", "at" to "1:32",
            "resampledFrom" to "Night Drive",
            "file" to "break.wav",
            "importedFrom" to "pack.xpn",
        )
        assertEquals(Provenance.Kind.DUG, Provenance.kindOf(many))
        assertEquals("dug from \"Track 07.wav\" at 1:32", Provenance.phrase(many))

        // The correction this file took from the liner notes: a resample
        // generation outranks the chop that made it, because "bounced from
        // X and chopped again" contains "chopped from a file" and not the
        // other way round. The pad sheet used to rank these the other way.
        val bounced = mapOf("resampledFrom" to "Night Drive", "file" to "break.wav")
        assertEquals(Provenance.Kind.RESAMPLED, Provenance.kindOf(bounced))

        // A capture from another app: known only to Lineage before, so it
        // showed as a bare filename on the pad sheet and as "built by
        // hand" on the card.
        assertEquals("captured from Player \"Side B\"", Provenance.phrase(mapOf("app" to "Player", "title" to "Side B")))
        assertEquals("captured from Player", Provenance.phrase(mapOf("app" to "Player")))
        assertEquals("captured from \"Side B\"", Provenance.phrase(mapOf("title" to "Side B")))

        // STRETCH's two modes read as two different words, since a freeze
        // is not a stretch to anybody who did one.
        assertEquals("frozen from \"A01\"", Provenance.phrase(mapOf("stretchedFrom" to "A01", "mode" to "freeze")))
        assertEquals("stretched from \"A01\"", Provenance.phrase(mapOf("stretchedFrom" to "A01", "mode" to "stretch")))
        assertEquals("stretched from \"A01\"", Provenance.phrase(mapOf("stretchedFrom" to "A01")), "no mode, no guess")

        // A stamp with nothing after it still answers rather than throwing:
        // these maps come out of a kit.json a user can edit.
        assertEquals("dug from \"Track 07.wav\" at ?", Provenance.phrase(mapOf("song" to "Track 07.wav")))
    }

    @Test
    fun `lineage is extra parentage, shown beside the origin rather than instead of it`() {
        val twin = mapOf(KitBuilderModel.TWIN_OF to "A01", "file" to "break.wav")
        assertEquals(listOf("twin of A01"), Provenance.lineage(twin))
        assertEquals("chopped from \"break.wav\"", Provenance.phrase(twin), "the twin still knows its parent's tape")

        val both = mapOf(KitBuilderModel.TWIN_OF to "A01", "bredFrom" to "Mother x Father")
        assertEquals(listOf("twin of A01", "bred from Mother × Father"), Provenance.lineage(both))
        assertEquals(emptyList(), Provenance.lineage(mapOf("file" to "break.wav")))
    }

    @Test
    fun `a kit's own line is its top kind, folded, and the liner notes list the same thing in full`() {
        val dugTwice = listOf(
            pad(1, mapOf("song" to "A.wav", "at" to "0:10")),
            pad(2, mapOf("song" to "B.wav", "at" to "0:20")),
            pad(3, mapOf("file" to "break.wav")),
        )
        assertEquals("dug from \"A.wav\" at 0:10 +1 more", Provenance.ofKit(dugTwice))
        assertEquals(
            listOf("dug from \"A.wav\" at 0:10", "dug from \"B.wav\" at 0:20"),
            Provenance.allOfKit(dugTwice),
            "the chopped pad is a lower kind, so it is not part of the kit's answer",
        )

        val one = listOf(pad(1, mapOf("file" to "break.wav")), pad(2, mapOf("file" to "break.wav")))
        assertEquals("chopped from \"break.wav\"", Provenance.ofKit(one), "one parent, no count")

        assertNull(Provenance.ofKit(listOf(pad(1, emptyMap()))), "a kit built by hand gets no line at all")
        assertEquals(emptyList(), Provenance.allOfKit(emptyList()))

        // The sentence form is the phrase, capitalised and stopped - which
        // is how the card's liner notes and the KIT screen's line stay the
        // same claim in two registers.
        assertEquals("Chopped from \"break.wav\".", Provenance.asSentence("chopped from \"break.wav\""))
        assertEquals("THIS KIT: CHOPPED FROM \"BREAK.WAV\"", Copy.kitCameFrom("chopped from \"break.wav\""))
    }
}
