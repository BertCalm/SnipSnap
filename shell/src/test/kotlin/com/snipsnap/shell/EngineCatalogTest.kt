package com.snipsnap.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SYNTH engine picker's pure half: the catalogue's data, the search over it and
 * the recents list. The screen is driven on a device (`EnginePickerSheetTest`); what
 * it shows for a given query, chip set and history is decided here, where it runs in
 * milliseconds.
 *
 * The fixtures use real engine names, because the catalogue is keyed by them, with
 * voices written out by hand. The picker's real voices live in `SynthScreen.kt`, in
 * an enum `:shell` cannot see, and a test that quoted them would only repeat them.
 */
class EngineCatalogTest {

    // ---- the catalogue's data ----

    @Test
    fun `every entry has one or two distinct categories`() {
        assertTrue(EngineCatalog.ENTRIES.isNotEmpty(), "the catalogue is empty")
        for ((name, entry) in EngineCatalog.ENTRIES) {
            assertTrue(
                entry.categories.size in 1..EngineEntry.MAX_CATEGORIES,
                "$name has ${entry.categories.size} categories; a chip filter needs one or two",
            )
            assertEquals(entry.categories.distinct(), entry.categories, "$name lists a category twice")
            assertEquals(entry.categories.first(), entry.primary, "$name's primary is not its first category")
        }
    }

    @Test
    fun `every description fits its row and reads as a sentence`() {
        for ((name, entry) in EngineCatalog.ENTRIES) {
            val d = entry.description
            assertTrue(d.isNotBlank(), "$name has no description")
            assertTrue(
                d.length <= EngineEntry.MAX_DESCRIPTION,
                "$name's description is ${d.length} characters, over ${EngineEntry.MAX_DESCRIPTION}: \"$d\"",
            )
            // Descriptions are messages, which the owner approved for sentence case; labels are caps.
            assertTrue(d.first().isUpperCase(), "$name's description does not start with a capital: \"$d\"")
            assertNotEquals(d.uppercase(), d, "$name's description is all caps: \"$d\"")
        }
    }

    @Test
    fun `category labels are caps because they are chip labels`() {
        for (category in EngineCategory.entries) {
            assertEquals(category.label.uppercase(), category.label, "$category's label is not caps")
        }
        assertEquals(EngineCategory.entries.size, EngineCategory.entries.map { it.label }.toSet().size, "two categories share a label")
    }

    @Test
    fun `no chip is dead and none selects everything`() {
        for (category in EngineCategory.entries) {
            val tagged = EngineCatalog.ENTRIES.count { category in it.value.categories }
            assertTrue(tagged >= 1, "${category.label} tags no engine, so its chip would always answer \"No engines match.\"")
            assertTrue(
                tagged < EngineCatalog.ENTRIES.size,
                "${category.label} tags every engine, so its chip would filter nothing",
            )
        }
    }

    @Test
    fun `entry looks an engine up by constant name`() {
        assertEquals(EngineCatalog.ENTRIES["SCANNED_CHAIN"], EngineCatalog.entry("SCANNED_CHAIN"))
        assertTrue(EngineCatalog.entry("SCANNED_CHAIN") != null, "SCANNED_CHAIN is the underscore case and must be keyed with it")
        assertNull(EngineCatalog.entry("SCANNED CHAIN"), "the display name is not the key")
        assertNull(EngineCatalog.entry("NO_SUCH_ENGINE"))
    }

    // ---- filterEngines ----

    /** Hand-written voices for a handful of real engine names; THUMP and ONSET have a kick, only ONSET has hats. */
    private val voices = mapOf(
        "THUMP" to listOf("KICK", "SNARE", "CLAP"),
        "ONSET" to listOf("KICK", "SNARE", "HAT_CLOSED", "HAT_OPEN"),
        "TINES" to listOf("BELL", "CHIME", "KALIMBA"),
        "SCANNED_CHAIN" to listOf("PLUCK", "METAL", "BLOOM"),
        "FATHOM" to listOf("DEEP", "GRIND", "GLASS"),
        "VELVET" to listOf("BASS", "BRASS", "SQUELCH"),
        "RESIN" to listOf("BASS", "LEAD", "BRASS"),
        "TONEWHEEL" to listOf("FULL", "SOUL", "STAB"),
    )
    private val names = voices.keys.toList()
    private fun voicesOf(name: String): List<String> = voices[name].orEmpty()
    private fun filter(query: String = "", categories: Set<EngineCategory> = emptySet(), of: List<String> = names) =
        filterEngines(of, ::voicesOf, query, categories)

    private fun catalogued(name: String) = EngineCatalog.entry(name)!!.categories

    @Test
    fun `an empty query and no chips shows every engine, A to Z by display name`() {
        assertEquals(
            listOf("FATHOM", "ONSET", "RESIN", "SCANNED_CHAIN", "THUMP", "TINES", "TONEWHEEL", "VELVET"),
            filter(),
        )
    }

    @Test
    fun `a blank query is an empty query`() {
        assertEquals(filter(), filter("   "))
    }

    @Test
    fun `kick finds the engines with a kick voice and not the rest`() {
        assertEquals(listOf("ONSET", "THUMP"), filter("kick"))
        assertFalse("TINES" in filter("kick"))
    }

    @Test
    fun `hat finds ONSET through HAT_CLOSED and HAT_OPEN`() {
        assertTrue("ONSET" in filter("hat"), "hat must find ONSET: ${filter("hat")}")
        // The voice alone is enough: an engine the catalogue has never heard of, so no description to help.
        assertEquals(listOf("MYSTERY"), filterEngines(listOf("MYSTERY"), { listOf("HAT_CLOSED") }, "hat", emptySet()))
    }

    @Test
    fun `the query is case-insensitive`() {
        assertEquals(filter("kick"), filter("KICK"))
        assertEquals(filter("kick"), filter("KiCk"))
        assertEquals(listOf("THUMP"), filter("Thump"))
    }

    @Test
    fun `an underscore and a space are the same character, in the query and in the names`() {
        assertEquals(listOf("ONSET"), filter("hat closed"))
        assertEquals(listOf("ONSET"), filter("hat_closed"))
        assertEquals(listOf("SCANNED_CHAIN"), filter("scanned chain"))
        assertEquals(listOf("SCANNED_CHAIN"), filter("scanned_chain"))
        assertEquals(listOf("SCANNED_CHAIN"), filter("d c"))
    }

    @Test
    fun `an engine matches on its own name even when no voice does`() {
        assertEquals(listOf("TONEWHEEL"), filter("tone"))
    }

    @Test
    fun `a query matches in the middle of a name or a voice, not only at the start`() {
        assertEquals(listOf("TINES"), filter("alim"))
        assertEquals(listOf("SCANNED_CHAIN"), filter("ned ch"))
        assertEquals(listOf("MYSTERY"), filterEngines(listOf("MYSTERY"), { listOf("COWBELL") }, "bell", emptySet()))
    }

    @Test
    fun `a description is searched at the start of a word, never in the middle of one`() {
        // THUMP's "Analog-style kick" and RESIN's "Fat analog-style bass": a word after a hyphen is a word.
        assertEquals(listOf("RESIN", "THUMP"), filter("analog"))
        assertEquals(listOf("RESIN", "THUMP"), filter("ANALOG-STYLE"))
        assertEquals(emptyList(), filter("nalog"), "the middle of a word in a description is not a match")
        // "that" is in ORPHICA's, MERCURY's and FLOCK's descriptions; hat must not find them through it.
        assertEquals(listOf("ORPHICA"), filterEngines(listOf("ORPHICA"), { emptyList() }, "that", emptySet()))
        assertEquals(emptyList(), filterEngines(listOf("ORPHICA", "MERCURY", "FLOCK"), { emptyList() }, "hat", emptySet()))
    }

    @Test
    fun `a category label is searched at the start of a word`() {
        // "organs" is in no description in the fixture, only in the label of the KEYS & ORGANS chip.
        assertEquals(listOf("TONEWHEEL"), filter("organs"), "KEYS & ORGANS must be found by organs")
        assertEquals(listOf("ONSET", "THUMP", "TINES"), filter("percussion"), "DRUMS & PERCUSSION, and TINES's own words")
        assertEquals(listOf("FATHOM", "RESIN", "VELVET"), filter("bass"), "the BASS label, the bass voices and the bass descriptions")
        assertEquals(emptyList(), filter("ercussion"))
    }

    @Test
    fun `the whole catalogue answers a search for a sound`() {
        val all = EngineCatalog.ENTRIES.keys.toList()
        // No voices: only the name, the description and the category labels speak, which is the part under test here.
        fun find(query: String) = filterEngines(all, { emptyList() }, query, emptySet())

        val bell = find("bell")
        for (engine in listOf("THAW", "NIMBUS", "COROLLA", "MERCURY", "TESSERA")) {
            assertTrue(engine in bell, "bell must find $engine; it found $bell")
        }
        val taggedBells = all.filter { EngineCategory.BELLS in EngineCatalog.entry(it)!!.categories }
        assertTrue(bell.containsAll(taggedBells), "every BELLS & MALLETS engine is a bell: missing ${taggedBells - bell.toSet()}")

        val bass = find("bass")
        assertTrue("FATHOM" in bass, "bass must find FATHOM: $bass")
        val taggedBass = all.filter { EngineCategory.BASS in EngineCatalog.entry(it)!!.categories }
        assertTrue(bass.containsAll(taggedBass), "every BASS engine is a bass: missing ${taggedBass - bass.toSet()}")

        val organ = find("organ")
        for (engine in listOf("TONEWHEEL", "REGISTERS")) assertTrue(engine in organ, "organ must find $engine: $organ")
        val taggedKeys = all.filter { EngineCategory.KEYS in EngineCatalog.entry(it)!!.categories }
        assertTrue(organ.containsAll(taggedKeys), "every KEYS & ORGANS engine is an organ search hit: missing ${taggedKeys - organ.toSet()}")

        val hat = find("hat")
        assertEquals(listOf("ONSET", "SKIN", "THUMP"), hat, "the hats in the descriptions, and no that")
        for (engine in listOf("ORPHICA", "MERCURY", "FLOCK", "NIMBUS", "OBBLIGATO", "RELAY", "SUTURE", "LATTICE")) {
            assertFalse(engine in hat, "$engine says that, not hat: $hat")
        }
    }

    @Test
    fun `surrounding spaces in the query are ignored`() {
        assertEquals(filter("kick"), filter("  kick "))
    }

    @Test
    fun `a nonsense query matches nothing`() {
        assertEquals(emptyList(), filter("zzqx"))
    }

    @Test
    fun `chips are an OR across the selected categories, and none selected means all`() {
        val bass = filter(categories = setOf(EngineCategory.BASS))
        assertTrue("FATHOM" in bass && "VELVET" in bass && "RESIN" in bass, "BASS misses a bass engine: $bass")
        assertEquals(names.filter { EngineCategory.BASS in catalogued(it) }.sorted(), bass)
        assertTrue(bass.size < names.size, "the BASS chip filtered nothing")

        val both = filter(categories = setOf(EngineCategory.BASS, EngineCategory.KEYS))
        assertEquals(
            names.filter { catalogued(it).any { c -> c == EngineCategory.BASS || c == EngineCategory.KEYS } }.sorted(),
            both,
        )
        assertTrue("TONEWHEEL" in both, "adding KEYS did not bring in TONEWHEEL: $both")
        assertTrue(both.containsAll(bass) && both.size > bass.size, "a second chip must widen the result")
    }

    @Test
    fun `an engine tagged with two categories shows under either chip`() {
        // VELVET is SYNTH first and BASS second.
        assertTrue("VELVET" in filter(categories = setOf(EngineCategory.BASS)))
        assertTrue("VELVET" in filter(categories = setOf(EngineCategory.SYNTH)))
    }

    @Test
    fun `the query and the chips combine with AND`() {
        assertEquals(listOf("ONSET", "THUMP"), filter("kick", setOf(EngineCategory.DRUMS)))
        assertEquals(emptyList(), filter("kick", setOf(EngineCategory.BELLS)))
        // FATHOM is BASS too, but has no brass voice.
        assertEquals(listOf("RESIN", "VELVET"), filter("brass", setOf(EngineCategory.BASS)))
        assertEquals(emptyList(), filter("brass", setOf(EngineCategory.DRUMS)))
    }

    @Test
    fun `empty inputs give an empty result and never throw`() {
        assertEquals(emptyList(), filter(of = emptyList()))
        assertEquals(emptyList(), filter("kick", of = emptyList()))
        assertEquals(emptyList(), filter(categories = setOf(EngineCategory.BASS), of = emptyList()))
        // No voices at all: the name, the description and the category labels can still match.
        assertEquals(listOf("ONSET", "THUMP"), filterEngines(names, { emptyList() }, "kick", emptySet()), "kick is in two descriptions")
        assertEquals(listOf("THUMP"), filterEngines(names, { emptyList() }, "thump", emptySet()))
        assertEquals(emptyList(), filterEngines(names, { emptyList() }, "zzqx", emptySet()))
    }

    @Test
    fun `a name the catalogue does not know shows with no chips and under none`() {
        val of = listOf("THUMP", "MYSTERY")
        assertEquals(listOf("MYSTERY", "THUMP"), filter(of = of))
        assertEquals(listOf("THUMP"), filter(categories = setOf(EngineCategory.DRUMS), of = of))
    }

    @Test
    fun `order is by display name with the underscore read as a space`() {
        // By raw name ABC sorts before AB_C (the letter C before the underscore); by display name
        // "AB C" sorts before "ABC" (the space before the letter). The spec is the display name.
        assertEquals(listOf("AB_C", "ABC"), filterEngines(listOf("ABC", "AB_C"), { emptyList() }, "", emptySet()))
        assertEquals(listOf("SCANNED_CHAIN", "SKIN"), filterEngines(listOf("SKIN", "SCANNED_CHAIN"), { emptyList() }, "", emptySet()))
    }

    @Test
    fun `a name given twice is listed once`() {
        assertEquals(listOf("THUMP"), filterEngines(listOf("THUMP", "THUMP"), ::voicesOf, "", emptySet()))
    }

    // ---- engineVoicesLine ----

    @Test
    fun `the voices line joins up to four names with a middle dot`() {
        assertEquals("", engineVoicesLine(emptyList()))
        assertEquals("KICK", engineVoicesLine(listOf("KICK")))
        assertEquals("KICK · SNARE · CLAP", engineVoicesLine(listOf("KICK", "SNARE", "CLAP")))
        assertEquals("A · B · C · D", engineVoicesLine(listOf("A", "B", "C", "D")))
    }

    @Test
    fun `the voices line counts what it leaves out`() {
        assertEquals("A · B · C · D +1", engineVoicesLine(listOf("A", "B", "C", "D", "E")))
        assertEquals("A · B · C · D +8", engineVoicesLine(('A'..'L').map { it.toString() }))
        assertEquals("A · B +2", engineVoicesLine(listOf("A", "B", "C", "D"), shown = 2))
    }

    @Test
    fun `the voices line reads an underscore as a space`() {
        assertEquals("KICK · HAT CLOSED · HAT OPEN", engineVoicesLine(listOf("KICK", "HAT_CLOSED", "HAT_OPEN")))
    }

    @Test
    fun `the voices a query hit come first, so a hit through a voice is not hidden in the count`() {
        val thump = listOf("KICK", "SNARE", "HAT_CLOSED", "HAT_OPEN", "CLAP", "TOM", "COWBELL", "RIM", "SPARK")
        assertEquals("KICK · SNARE · HAT CLOSED · HAT OPEN +5", engineVoicesLine(thump))
        // COWBELL was the seventh voice and so behind the "+5"; bell brings it to the front.
        assertEquals("COWBELL · KICK · SNARE · HAT CLOSED +5", engineVoicesLine(thump, query = "bell"))
        // Several hits keep their own order, ahead of the rest in theirs.
        assertEquals("HAT CLOSED · HAT OPEN · KICK · SNARE +5", engineVoicesLine(thump, query = "hat"))
        // Underscore and space are one character here too; case does not matter.
        assertEquals("HAT CLOSED · KICK · SNARE · HAT OPEN +5", engineVoicesLine(thump, query = "Hat_Closed"))
    }

    @Test
    fun `a query no voice answers leaves the voices line as it was`() {
        val voices = listOf("KICK", "SNARE", "CLAP", "TOM", "RIM")
        assertEquals(engineVoicesLine(voices), engineVoicesLine(voices, query = "analog"))
        assertEquals(engineVoicesLine(voices), engineVoicesLine(voices, query = ""))
        assertEquals(engineVoicesLine(voices), engineVoicesLine(voices, query = "   "))
    }

    // ---- RecentEngines ----

    @Test
    fun `recents start empty`() {
        assertEquals(emptyList(), RecentEngines().list)
        assertEquals("", RecentEngines().encode())
    }

    @Test
    fun `the most recent engine comes first`() {
        val r = RecentEngines()
        r.used("THUMP"); r.used("SKIN"); r.used("ONSET")
        assertEquals(listOf("ONSET", "SKIN", "THUMP"), r.list)
    }

    @Test
    fun `using an engine again moves it to the front without a second copy`() {
        val r = RecentEngines()
        r.used("THUMP"); r.used("SKIN"); r.used("THUMP")
        assertEquals(listOf("THUMP", "SKIN"), r.list)
    }

    @Test
    fun `recents are capped, the oldest falling off`() {
        val r = RecentEngines(max = 3)
        for (n in listOf("A", "B", "C", "D", "E")) r.used(n)
        assertEquals(listOf("E", "D", "C"), r.list)
        val five = RecentEngines()
        for (n in listOf("A", "B", "C", "D", "E", "F", "G")) five.used(n)
        assertEquals(listOf("G", "F", "E", "D", "C"), five.list, "the default cap is five")
    }

    @Test
    fun `a blank name, or one holding the separator, is not recorded`() {
        val r = RecentEngines()
        r.used(""); r.used("  "); r.used("A,B")
        assertEquals(emptyList(), r.list)
    }

    @Test
    fun `the list is a snapshot a later use does not change`() {
        val r = RecentEngines()
        r.used("THUMP")
        val before = r.list
        r.used("SKIN")
        assertEquals(listOf("THUMP"), before)
        assertEquals(listOf("SKIN", "THUMP"), r.list)
    }

    @Test
    fun `encoding round-trips the list in order`() {
        val r = RecentEngines()
        for (n in listOf("THUMP", "SCANNED_CHAIN", "ONSET")) r.used(n)
        val back = RecentEngines.decode(r.encode(), valid = setOf("THUMP", "SCANNED_CHAIN", "ONSET", "SKIN"))
        assertEquals(listOf("ONSET", "SCANNED_CHAIN", "THUMP"), back.list)
        assertEquals(r.encode(), back.encode())
    }

    @Test
    fun `decoding drops a name the caller no longer has`() {
        val back = RecentEngines.decode("TINES,GONE,ONSET,ALSO_GONE,THUMP", valid = setOf("TINES", "ONSET", "THUMP"))
        assertEquals(listOf("TINES", "ONSET", "THUMP"), back.list)
        assertEquals("TINES,ONSET,THUMP", back.encode(), "the dropped names must not come back on the next save")
    }

    @Test
    fun `decoding tolerates nothing, blanks and noise`() {
        val valid = setOf("THUMP")
        assertEquals(emptyList(), RecentEngines.decode(null, valid).list)
        assertEquals(emptyList(), RecentEngines.decode("", valid).list)
        assertEquals(emptyList(), RecentEngines.decode(" , ,,", valid).list)
        assertEquals(listOf("THUMP"), RecentEngines.decode(" ,THUMP, ,", valid).list)
    }

    @Test
    fun `decoding drops repeats and keeps no more than the cap`() {
        assertEquals(listOf("A", "B"), RecentEngines.decode("A,B,A,B", valid = setOf("A", "B")).list)
        assertEquals(listOf("A", "B"), RecentEngines.decode("A,B,C,D", valid = setOf("A", "B", "C", "D"), max = 2).list)
    }

    @Test
    fun `a decoded list carries on from where it was`() {
        val r = RecentEngines.decode("A,B", valid = setOf("A", "B", "C"))
        r.used("C")
        assertEquals(listOf("C", "A", "B"), r.list)
        r.used("B")
        assertEquals(listOf("B", "C", "A"), r.list)
    }
}
