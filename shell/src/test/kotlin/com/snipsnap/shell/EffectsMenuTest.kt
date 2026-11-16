package com.snipsnap.shell

import com.snipsnap.synth.BoardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EffectsMenuTest {

    private val targets = EffectsMenu.ALL.map { it.target }

    @Test
    fun `every effect the EFFECTS page offers is in the list exactly once`() {
        val segments = PadSheet.ALL_SEGMENTS.filter { it !in PadSheet.PEDAL_SEGMENTS && it != PadSheet.NONE }
        val expected: List<EffectsMenu.Target> =
            segments.map { EffectsMenu.Target.Segment(it) } +
                PadSheet.PEDAL_SEGMENTS.map { EffectsMenu.Target.Pedal(it) } +
                BoardType.entries.map { EffectsMenu.Target.Board(it) } +
                OutsideSheet.Move.entries.map { EffectsMenu.Target.Outside(it) } +
                listOf(EffectsMenu.Target.Chain, EffectsMenu.Target.Shape)
        assertEquals(targets.size, targets.distinct().size, "a row appears twice: ${targets.groupBy { it }.filter { it.value.size > 1 }.keys}")
        assertEquals(expected.toSet(), targets.toSet(), "missing: ${expected - targets.toSet()}; extra: ${targets - expected.toSet()}")
        // 32 segments, 17 pedals, 5 boards, 2 moves, the chain and SHAPE (Luthier's count, 2026-11-15).
        assertEquals(58, targets.size)
        assertFalse(EffectsMenu.Target.Segment(PadSheet.MORE) in targets, "MORE opens a list; it is not an effect")
    }

    @Test
    fun `a segment row is never a pedal and a pedal row is always on the slate`() {
        for (t in targets) when (t) {
            is EffectsMenu.Target.Segment -> {
                assertTrue(t.id in PadSheet.ALL_SEGMENTS, t.id)
                assertFalse(t.id in PadSheet.PEDAL_SEGMENTS, "${t.id} is a pedal: list it as one so the row puts it on and takes it off")
            }
            is EffectsMenu.Target.Pedal -> assertTrue(t.word in PadSheet.PEDAL_SEGMENTS, t.word)
            else -> Unit
        }
    }

    @Test
    fun `names are the owner's, unique and in caps`() {
        val names = EffectsMenu.ALL.map { it.name }
        assertEquals(names.size, names.distinct().size, "two rows share a name: $names")
        for (n in names) assertEquals(n.uppercase(), n, "a name is a label: $n")
        // A sample of the owner's list (2026-11-15), one per kind of target.
        assertEquals("QUICK ECHO", EffectsMenu.nameFor(EffectsMenu.Target.Segment("SLAP")))
        assertEquals("ENDLESS", EffectsMenu.nameFor(EffectsMenu.Target.Segment("ETERNAL")))
        assertEquals("OLD RECORDING", EffectsMenu.nameFor(EffectsMenu.Target.Segment(PadSheet.DUST)))
        assertEquals("CUT TAIL", EffectsMenu.nameFor(EffectsMenu.Target.Pedal("HUSH")))
        assertEquals("BACKWARDS ECHO", EffectsMenu.nameFor(EffectsMenu.Target.Pedal("REWIND")))
        assertEquals("DREAMY SPACE", EffectsMenu.nameFor(EffectsMenu.Target.Board(BoardType.VELOUR)))
        assertEquals("FUZZ HALO", EffectsMenu.nameFor(EffectsMenu.Target.Board(BoardType.OBLITERATE)))
        assertEquals("THROUGH SPEAKER", EffectsMenu.nameFor(EffectsMenu.Target.Outside(OutsideSheet.Move.REAMP)))
        assertEquals("REAL ROOM", EffectsMenu.nameFor(EffectsMenu.Target.Outside(OutsideSheet.Move.ROOM)))
        assertEquals("EFFECT CHAIN", EffectsMenu.nameFor(EffectsMenu.Target.Chain))
        // KIT TOOLS keeps FREEZE; the effect that holds a tail is ENDLESS, so the two never share a word.
        assertFalse("FREEZE" in names)
    }

    @Test
    fun `every line is a short caption - sentence case, no full stop`() {
        for (e in EffectsMenu.ALL) {
            val line = e.line
            assertTrue(line.isNotBlank(), e.name)
            assertTrue(line.first().isUpperCase(), "sentence case: $line")
            assertTrue(line.any { it.isLowerCase() }, "a line is not a label: $line")
            assertFalse(line.endsWith(".") || line.endsWith("…"), "a caption has no full stop: $line")
            assertTrue(line.length <= 60, "two short lines on a phone at most: $line (${line.length})")
        }
    }

    @Test
    fun `nine groups of a phone's screenful each, headings in caps`() {
        assertEquals(
            listOf(
                "HIT HARDER", "DIRTY OR OLD", "ECHO AND SPACE", "MOVEMENT", "FILTER AND WAH",
                "IN TIME WITH THE BEAT", "PITCH AND REVERSE", "SOFTER OR LONGER", "STRANGE",
            ),
            EffectsMenu.GROUPS.map { it.heading },
        )
        for (g in EffectsMenu.GROUPS) {
            assertTrue(g.entries.size in 4..8, "${g.heading} holds ${g.entries.size}")
            assertEquals(g.heading.uppercase(), g.heading)
            // The closed line names what is inside, in the group's order.
            assertEquals(g.entries.joinToString(" · ") { it.name }, g.summary)
        }
        assertEquals(listOf(EffectsMenu.Target.Chain, EffectsMenu.Target.Shape), EffectsMenu.PAGES.map { it.target })
    }

    @Test
    fun `onNow lights the rows a pad carries, with the strip's amount`() {
        val bare = com.snipsnap.kit.KitPad(slot = 1, sampleFile = "A01_Kick.wav", drumClass = com.snipsnap.audio.DrumClass.KICK)
        assertTrue(EffectsMenu.onNow(bare).isEmpty(), "a bare pad lights nothing")

        val crushed = bare.copy(recipe = com.snipsnap.json.JsonValue.Obj(linkedMapOf("era" to com.snipsnap.json.JsonValue.Str("sp1200"), "amount" to com.snipsnap.json.JsonValue.Num(0.35))))
        assertEquals(mapOf<EffectsMenu.Target, String>(EffectsMenu.Target.Segment("CRUSH") to "ON · 35%"), EffectsMenu.onNow(crushed))

        val board = com.snipsnap.synth.PedalBoard(listOf(com.snipsnap.synth.PedalBoard.Pick("delayed", 0.7f), com.snipsnap.synth.PedalBoard.Pick("driven", 1f)))
        val pedals = bare.copy(recipe = com.snipsnap.synth.PadRecipe(pedals = board).toJsonValue(), attack = 0.5f)
        assertEquals(
            mapOf(
                EffectsMenu.Target.Pedal("DELAY") to "ON · 70%",
                EffectsMenu.Target.Pedal("DRIVE") to "ON · 100%",
                EffectsMenu.Target.Shape to "ON",
            ),
            EffectsMenu.onNow(pedals),
        )
        for (t in EffectsMenu.onNow(pedals).keys) assertTrue(t in targets, "$t lights a row the list does not draw")
    }

    @Test
    fun `START HERE is eight drum-safe rows taken from their groups`() {
        assertEquals(
            listOf("PUNCH", "HARD CLIP", "SHARPER HIT", "ROOM REVERB", "QUICK ECHO", "CRUSH", "SUB", "REVERSE"),
            EffectsMenu.START_HERE.map { it.name },
        )
        val grouped = EffectsMenu.GROUPS.flatMap { it.entries }
        // The same entries, not copies: one line per effect.
        for (e in EffectsMenu.START_HERE) assertTrue(grouped.any { it === e }, "${e.name} is not the group's own entry")
        // None needs the kit's tempo or key, which a drum on its own may not have.
        val keyed = setOf("ROLL", "GATE", "PUMP", "ONBEAT", "WOBBLE", PadSheet.TUNE, "BODY", "ETERNAL")
        for (e in EffectsMenu.START_HERE) {
            val t = e.target
            if (t is EffectsMenu.Target.Segment) assertFalse(t.id in keyed, "${e.name} reads the kit's tempo or key")
        }
    }

    @Test
    fun `an effect or a pedal is heard before it is kept, and nothing else is`() {
        for (entry in EffectsMenu.ALL) {
            val heardFirst = entry.target is EffectsMenu.Target.Segment || entry.target is EffectsMenu.Target.Pedal
            assertEquals(heardFirst, EffectsMenu.tries(entry.target), entry.name)
        }
        assertTrue(EffectsMenu.START_HERE.all { EffectsMenu.tries(it.target) }, "every starter is heard first")
        assertFalse(EffectsMenu.tries(EffectsMenu.Target.Board(BoardType.WIDE)), "a board already stages until KEEP")
        assertFalse(EffectsMenu.tries(EffectsMenu.Target.Chain))
        assertFalse(EffectsMenu.tries(EffectsMenu.Target.Shape))
    }

    @Test
    fun `the keep bar names what it keeps and fits a row`() {
        for (entry in EffectsMenu.ALL.filter { EffectsMenu.tries(it.target) }) {
            val keep = EffectsMenu.keepLabel(entry.name)
            assertTrue(keep.startsWith("KEEP ") && keep.endsWith(entry.name), keep)
            assertTrue(keep.length <= MutateSheet.ROW_CHARS, "too long for the bar: $keep")
        }
        assertTrue(EffectsMenu.TRY_LINE.contains("KEEP"), "the line says what keeps it")
    }
}
