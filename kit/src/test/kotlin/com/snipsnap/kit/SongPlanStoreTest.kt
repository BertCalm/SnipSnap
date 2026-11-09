package com.snipsnap.kit

import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongPlanStoreTest {

    private val dir: File = java.nio.file.Files.createTempDirectory("songplan").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun plan() = SongPlan(
        seed = 3,
        sections = listOf(
            PlanSection("intro", ClipRef.SPARSE, 1, seed = 3),
            PlanSection("theme", ClipRef.CAPTURED, 2, locked = true, seed = 1),
            PlanSection("variation", ClipRef.yours(2), 4, seed = 3),
            PlanSection("the turn", ClipRef.FILL, 1, seed = 3),
        ),
    )

    @Test
    fun `a plan round-trips with full fidelity, locks and per-section seeds included`() {
        SongPlanStore.save(dir, "Kitchen", plan())
        assertEquals(plan(), SongPlanStore.load(dir, "Kitchen"))
        assertTrue(File(dir, SongPlanStore.FILE_NAME).isFile)
    }

    @Test
    fun `no file, or no plan for that kit, is no plan`() {
        assertNull(SongPlanStore.load(dir, "Kitchen"))
        SongPlanStore.save(dir, "Kitchen", plan())
        assertNull(SongPlanStore.load(dir, "Funk Room"))
        assertFalse(SongPlanStore.has(dir, "Funk Room"))
    }

    @Test
    fun `one file holds a plan per kit and saving one leaves the other alone`() {
        val other = SongPlan(0, listOf(PlanSection("only", ClipRef.HALF)))
        SongPlanStore.save(dir, "Kitchen", plan())
        SongPlanStore.save(dir, "Funk Room", other)
        SongPlanStore.save(dir, "Kitchen", plan().withRepeats(0, 3)!!)
        assertEquals(other, SongPlanStore.load(dir, "Funk Room"))
        assertEquals(3, SongPlanStore.load(dir, "Kitchen")!!.sections[0].repeats)
    }

    @Test
    fun `unknown fields are ignored, and a section missing the optional ones takes the defaults`() {
        File(dir, SongPlanStore.FILE_NAME).writeText(
            """{"version":1,"flavour":"later","plans":{"Kitchen":{"seed":2,"extra":[1],
              "sections":[{"name":"theme","clip":"captured","colour":"red"},{"name":"b","clip":"yours:1","repeats":3,"locked":true,"seed":2}]}}}""",
        )
        val loaded = assertNotNull(SongPlanStore.load(dir, "Kitchen"))
        assertEquals(2, loaded.seed)
        assertEquals(PlanSection("theme", "captured", 1, false, 0), loaded.sections[0])
        assertEquals(PlanSection("b", "yours:1", 3, true, 2), loaded.sections[1])
    }

    @Test
    fun `a file from a newer build is no plan, and a save refuses to flatten it`() {
        val text = """{"version":2,"plans":{"Kitchen":{"seed":0,"sections":[{"name":"a","clip":"captured"}]}}}"""
        File(dir, SongPlanStore.FILE_NAME).writeText(text)
        assertNull(SongPlanStore.load(dir, "Kitchen"))
        assertFailsWith<IOException> { SongPlanStore.save(dir, "Kitchen", plan()) }
        assertEquals(text, File(dir, SongPlanStore.FILE_NAME).readText(), "the newer file is untouched")
    }

    @Test
    fun `a corrupt file is no plan and never throws, and a save replaces it`() {
        val file = File(dir, SongPlanStore.FILE_NAME)
        for (junk in listOf("", "{", "[1,2]", """{"version":"x"}""", """{"version":1,"plans":[]}""", "\u0000\u0001")) {
            file.writeText(junk)
            assertNull(SongPlanStore.load(dir, "Kitchen"), "junk: $junk")
        }
        SongPlanStore.save(dir, "Kitchen", plan())
        assertEquals(plan(), SongPlanStore.load(dir, "Kitchen"))
    }

    @Test
    fun `a bad plan is none and does not take the other kits' plans down`() {
        File(dir, SongPlanStore.FILE_NAME).writeText(
            """{"version":1,"plans":{
              "Bad":{"seed":0,"sections":[]},
              "Bad2":{"seed":0,"sections":[{"name":"a","clip":"captured","repeats":99}]},
              "Bad3":{"seed":0,"sections":[{"clip":"captured"}]},
              "Good":{"seed":0,"sections":[{"name":"a","clip":"captured"}]}}}""",
        )
        assertNull(SongPlanStore.load(dir, "Bad"))
        assertNull(SongPlanStore.load(dir, "Bad2"))
        assertNull(SongPlanStore.load(dir, "Bad3"))
        assertNotNull(SongPlanStore.load(dir, "Good"))
        // Saving another kit keeps what it can still read.
        SongPlanStore.save(dir, "New", plan())
        assertNotNull(SongPlanStore.load(dir, "Good"))
    }

    @Test
    fun `a kit renamed takes its plan with it`() {
        SongPlanStore.save(dir, "Kitchen", plan())
        SongPlanStore.save(dir, "Funk Room", SongPlan(0, listOf(PlanSection("only", ClipRef.HALF))))
        SongPlanStore.rename(dir, "Kitchen", "Kitchen 2")
        assertNull(SongPlanStore.load(dir, "Kitchen"))
        assertEquals(plan(), SongPlanStore.load(dir, "Kitchen 2"))
        assertNotNull(SongPlanStore.load(dir, "Funk Room"))
    }

    @Test
    fun `renaming a kit with no plan, or in a project with no song file, changes nothing`() {
        SongPlanStore.rename(dir, "Kitchen", "Kitchen 2")
        assertFalse(File(dir, SongPlanStore.FILE_NAME).exists())
        SongPlanStore.save(dir, "Funk Room", plan())
        val before = File(dir, SongPlanStore.FILE_NAME).readText()
        SongPlanStore.rename(dir, "Kitchen", "Kitchen 2")
        assertEquals(before, File(dir, SongPlanStore.FILE_NAME).readText())
    }

    @Test
    fun `no temp file is left beside the song`() {
        SongPlanStore.save(dir, "Kitchen", plan())
        assertEquals(listOf(SongPlanStore.FILE_NAME), dir.list()!!.toList())
    }

    // ---- the pure edits ----

    @Test
    fun `move, repeats, clip and lock edit one section and refuse what does not apply`() {
        val p = plan()
        assertEquals(listOf("theme", "intro", "variation", "the turn"), p.moved(0, 1)!!.sections.map { it.name })
        assertNull(p.moved(0, -1))
        assertNull(p.moved(3, 1))
        assertEquals(16, p.withRepeats(0, 99)!!.sections[0].repeats, "held to the ceiling")
        assertEquals(1, p.withRepeats(2, 0)!!.sections[2].repeats, "and the floor")
        assertEquals(ClipRef.TIGHT, p.withClip(1, ClipRef.TIGHT)!!.sections[1].clip)
        assertTrue(p.withLock(0, true)!!.sections[0].locked)
        assertNull(p.withLock(9, true))
    }

    @Test
    fun `duplicate puts an unlocked copy next, delete keeps at least one, add stops at the cap`() {
        val p = plan()
        val d = p.duplicated(1)!!
        assertEquals(listOf("intro", "theme", "theme", "variation", "the turn"), d.sections.map { it.name })
        assertTrue(p.sections[1].locked)
        assertFalse(d.sections[2].locked, "the copy is the one to vary")
        assertEquals(listOf("intro", "variation", "the turn"), p.without(1)!!.sections.map { it.name }.filterNot { it == "theme" })
        assertNull(SongPlan(0, listOf(PlanSection("a", ClipRef.CAPTURED))).without(0))
        var full = p
        while (full.sections.size < SongPlan.MAX_SECTIONS) full = full.added(ClipRef.CAPTURED)!!
        assertNull(full.added(ClipRef.CAPTURED))
        assertNull(full.duplicated(0))
    }

    @Test
    fun `reroll gives every unlocked section the new seed and leaves a locked one on its own`() {
        val r = plan().rerolled(9)
        assertEquals(9, r.seed)
        assertEquals(listOf(9, 1, 9, 9), r.sections.map { it.seed })
    }

    @Test
    fun `a section keeps its own bass and pull-up through a save, a copy and a new name`() {
        // Set against what the names would say, so a round trip that fell back on the name would show.
        val p = SongPlan(0, listOf(PlanSection("intro", ClipRef.SPARSE, bass = true), PlanSection("the turn", ClipRef.FILL, pullUp = false, bass = true)))
        SongPlanStore.save(dir, "Kitchen", p)
        val back = SongPlanStore.load(dir, "Kitchen")!!
        assertEquals(p, back)
        assertEquals(listOf(true, true), back.sections.map { it.bass })
        assertEquals(listOf(false, false), back.sections.map { it.pullUp })
        val renamed = back.sections[1].copy(name = "my break")
        assertTrue(renamed.bass)
        assertFalse(renamed.pullUp)
        val copied = back.duplicated(0)!!.sections[1]
        assertTrue(copied.bass, "a copy keeps the bass")
    }

    @Test
    fun `a song kept before sections had their own bass and pull-up reads them from the names`() {
        File(dir, SongPlanStore.FILE_NAME).writeText(
            """{"version":1,"plans":{"Kitchen":{"seed":0,"sections":[""" +
                """{"name":"intro","clip":"sparse"},{"name":"theme","clip":"captured"},{"name":"the turn","clip":"fill"},""" +
                """{"name":"reprise","clip":"captured"},{"name":"section 5","clip":"captured"}]}}}""",
        )
        val p = SongPlanStore.load(dir, "Kitchen")!!
        assertEquals(listOf(false, true, false, true, false), p.sections.map { it.bass })
        assertEquals(listOf(false, false, true, false, false), p.sections.map { it.pullUp })
        assertEquals(SongPlanStore.VERSION, 1, "no version bump: an older build still reads the file, and ignores the new fields")
    }

    @Test
    fun `an added section carries the bass and no pull-up, and each can be set`() {
        val added = plan().added(ClipRef.CAPTURED)!!
        val s = added.sections.last()
        assertTrue(s.bass)
        assertFalse(s.pullUp)
        val i = added.sections.lastIndex
        assertFalse(added.withBass(i, false)!!.sections[i].bass)
        assertTrue(added.withPullUp(i, true)!!.sections[i].pullUp)
        assertNull(added.withBass(99, true))
    }

    @Test
    fun `yours references round-trip their number`() {
        assertEquals(2, ClipRef.yoursId(ClipRef.yours(2)))
        assertNull(ClipRef.yoursId("captured"))
        assertNull(ClipRef.yoursId("yours:x"))
        assertNull(ClipRef.yoursId("yours:-1"))
    }
}
