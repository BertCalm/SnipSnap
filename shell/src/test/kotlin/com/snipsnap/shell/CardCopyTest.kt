package com.snipsnap.shell

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CardCopyTest {

    private fun tree(): File {
        val root = Files.createTempDirectory("cardcopy").toFile()
        File(root, "Kit/Samples").mkdirs()
        File(root, "Kit/Empty").mkdirs()
        File(root, "Kit/Kit.xpm").writeBytes(ByteArray(30))
        File(root, "Kit/Samples/kick.wav").writeBytes(ByteArray(100))
        File(root, "Kit/Samples/snare.wav").writeBytes(ByteArray(200))
        File(root, "top.json").writeBytes(ByteArray(7))
        return root
    }

    /** What an export hands over: its primary, and its companion when it has one. */
    private fun items(root: File): List<File> = listOf(File(root, "Kit"), File(root, "top.json"))

    @Test
    fun `every folder is listed before anything inside it`() {
        // The rule the whole copy rests on: a document API creates a child
        // inside a folder it is handed, so the folder must already have
        // been made. Checked as a property over the plan, not by reading
        // off an expected order.
        val entries = CardCopy.plan(items(tree()))
        val seen = HashSet<List<String>>()
        for (e in entries) {
            for (i in 1..e.parents.size) {
                assertTrue(
                    e.parents.take(i) in seen,
                    "${e.segments} came before its parent ${e.parents.take(i)}",
                )
            }
            if (e.isDirectory) seen += e.segments
        }
    }

    @Test
    fun `the plan is what was handed over, and everything under it`() {
        assertEquals(
            listOf(
                listOf("Kit"),
                listOf("top.json"),
                listOf("Kit", "Empty"),
                listOf("Kit", "Kit.xpm"),
                listOf("Kit", "Samples"),
                listOf("Kit", "Samples", "kick.wav"),
                listOf("Kit", "Samples", "snare.wav"),
            ),
            CardCopy.plan(items(tree())).map { it.segments },
        )
    }

    @Test
    fun `only what the export produced, not the folder it sits in`() {
        // The trap this shape exists to avoid. A self-nesting format is
        // written straight into the app's exports directory, which holds
        // every earlier export too — so a plan built from that *folder*
        // would put all of them on the card. Handed the outcome's own
        // items, it carries exactly those.
        val root = tree()
        File(root, "SomeOtherKit/Samples").mkdirs()
        File(root, "SomeOtherKit/old.wav").writeBytes(ByteArray(9))

        val entries = CardCopy.plan(listOf(File(root, "Kit")))
        assertTrue(entries.none { it.segments.first() == "SomeOtherKit" }, "an earlier export came along")
        assertTrue(entries.none { it.segments.first() == "top.json" })
        assertEquals(listOf("Kit"), entries.first().segments)
    }

    @Test
    fun `a single file is copied as itself`() {
        val entries = CardCopy.plan(listOf(File(tree(), "top.json")))
        assertEquals(1, entries.size)
        assertEquals(listOf("top.json"), entries.single().segments)
        assertTrue(!entries.single().isDirectory)
    }

    @Test
    fun `an empty folder is still made`() {
        // It carries no bytes, so a copy that only walked files would skip
        // it — and a kit folder that should exist would simply be missing.
        val empty = CardCopy.plan(items(tree())).single { it.segments == listOf("Kit", "Empty") }
        assertTrue(empty.isDirectory)
    }

    @Test
    fun `the same tree plans the same way twice`() {
        // A directory listing has no order of its own. An export that
        // lands differently each time is one nobody can diff.
        val root = tree()
        assertEquals(
            CardCopy.plan(items(root)).map { it.segments },
            CardCopy.plan(items(root)).map { it.segments },
        )
    }

    @Test
    fun `the byte count is the files, not the folders`() {
        assertEquals(30L + 100L + 200L + 7L, CardCopy.byteCount(CardCopy.plan(items(tree()))))
    }

    @Test
    fun `nothing to copy plans nothing rather than failing`() {
        val empty = Files.createTempDirectory("cardcopy-empty").toFile()
        assertEquals(emptyList(), CardCopy.plan(emptyList()))
        // A companion the driver never wrote is skipped, not refused.
        assertEquals(emptyList(), CardCopy.plan(listOf(File(empty, "not-there"))))
        assertEquals(0L, CardCopy.byteCount(emptyList()))
    }

    @Test
    fun `the types the MPC cares about are named, and the rest are bytes`() {
        assertEquals("audio/wav", CardCopy.mimeFor("kick.wav"))
        assertEquals("audio/wav", CardCopy.mimeFor("KICK.WAV"))
        assertEquals("image/png", CardCopy.mimeFor("art.png"))
        assertEquals("application/json", CardCopy.mimeFor("kit.json"))
        assertEquals("audio/midi", CardCopy.mimeFor("groove.mid"))
        // The one that matters: guessed as text, the platform appends
        // ".txt" and the MPC will not read the program.
        assertEquals("application/octet-stream", CardCopy.mimeFor("Kit.xpm"))
        assertEquals("application/octet-stream", CardCopy.mimeFor("Song.xpj"))
        assertEquals("application/octet-stream", CardCopy.mimeFor("Keys.xty"))
        assertEquals("application/octet-stream", CardCopy.mimeFor("no-extension"))
        assertEquals("application/octet-stream", CardCopy.mimeFor(""))
    }

    @Test
    fun `an entry needs a name`() {
        assertFailsWith<IllegalArgumentException> {
            CardCopy.Entry(emptyList(), isDirectory = false, source = File("x"))
        }
    }

    // ---------- collisions: what a dub would land on ----------

    /** A card that holds [names] at each listed path, and nothing anywhere else. */
    private fun card(vararg at: Pair<List<String>, Set<String>>): (List<String>) -> Set<String>? {
        val held = at.toMap()
        return { path -> held[path] }
    }

    @Test
    fun `an empty card is collided with nowhere`() {
        val entries = CardCopy.plan(items(tree()))
        assertEquals(emptyList(), CardCopy.collisions(entries, card(emptyList<String>() to emptySet())))
    }

    @Test
    fun `a folder the card does not have clears everything under it`() {
        // The property that keeps the preflight to one listing per folder
        // that actually exists: `held` is never asked about a path below
        // one it already answered null for, and nothing under it is
        // reported.
        val entries = CardCopy.plan(items(tree()))
        val asked = ArrayList<List<String>>()
        val found = CardCopy.collisions(entries) { path ->
            asked += path
            if (path.isEmpty()) emptySet() else null
        }
        assertEquals(emptyList(), found)
        assertTrue(asked.all { it.isEmpty() || it == listOf("Kit") }, "asked below a folder the card lacks: $asked")
    }

    @Test
    fun `a colliding folder is counted once, not once per file inside it`() {
        // The copy overwrites by deleting the same-named document, so a
        // folder that collides goes whole. Reporting its contents as well
        // would tell someone they are about to lose four things when the
        // decision in front of them is about one.
        val entries = CardCopy.plan(items(tree()))
        val found = CardCopy.collisions(entries, card(emptyList<String>() to setOf("Kit")))
        assertEquals(listOf(listOf("Kit")), found.map { it.segments })
    }

    @Test
    fun `what comes back is always root entries, never something nested`() {
        // Not an arbitrary rule - it falls out of the other two, and it is
        // why the confirm can name one thing. A nested entry can only
        // collide if its folder was listable, which means the card holds
        // that folder, which means the folder collided first and pruned
        // it. So the answer is some subset of the copy's own root
        // entries: the export's primary and its companion.
        //
        // Written as a sweep over every card that can be described for
        // this tree, rather than one example, because the claim is that
        // there is no arrangement where a deeper entry survives.
        val entries = CardCopy.plan(items(tree()))
        val folders = entries.filter { it.isDirectory }.map { it.segments } + listOf(emptyList())
        val names = entries.map { it.name }.toSet()
        for (mask in 0 until (1 shl folders.size)) {
            val held = folders.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.associateWith { names }
            val found = CardCopy.collisions(entries) { held[it] }
            assertTrue(
                found.all { it.segments.size == 1 },
                "a nested entry survived for $mask: ${found.map { it.segments }}",
            )
        }
    }

    @Test
    fun `both root entries can collide at once`() {
        // The pruning is per-branch, not global: an export whose primary
        // and companion are both already on the card has two things in
        // the way, and neither hides the other.
        val entries = CardCopy.plan(items(tree()))
        val found = CardCopy.collisions(entries, card(emptyList<String>() to setOf("Kit", "top.json")))
        assertEquals(listOf(listOf("Kit"), listOf("top.json")), found.map { it.segments })
    }

    @Test
    fun `the head of the list is the shallowest thing in the way`() {
        // What the confirm names. Parents come before their contents in
        // the plan and collisions preserves that order, so the first
        // entry is the one a person would recognise.
        val entries = CardCopy.plan(items(tree()))
        val found = CardCopy.collisions(
            entries,
            card(
                emptyList<String>() to setOf("Kit"),
                listOf("Kit", "Samples") to setOf("kick.wav"),
            ),
        )
        assertEquals("Kit", found.first().name)
        assertTrue(found.none { it.segments.size > 1 }, "a pruned child survived: ${found.map { it.segments }}")
    }
}
