package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ChainInfo
import com.snipsnap.kit.ChainZone
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.WearLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KitDiffTest {

    private fun pad(slot: Int, file: String = "A0${slot}_kick.wav") = KitPad(slot = slot, sampleFile = file)

    private fun kit(vararg pads: KitPad) = Kit("drums", pads.toList())

    private fun obj(vararg pairs: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*pairs))

    private fun texts(from: Kit, to: Kit) = KitDiff.changes(from, to).map { it.text }

    @Test
    fun `identical snapshots have no changes`() {
        val k = kit(pad(1), pad(2))
        assertEquals(emptyList(), KitDiff.changes(k, k))
        assertEquals("NO CHANGES", KitDiff.headline(emptyList()))
    }

    @Test
    fun `changes are keyed by slot, so an added pad does not shift the others`() {
        val from = kit(pad(1), pad(3))
        val to = kit(pad(1), pad(2), pad(3))
        val changes = KitDiff.changes(from, to)
        assertEquals(1, changes.size, "only the added pad is a change: $changes")
        assertEquals(2, changes[0].slot)
        assertEquals("A02 ADDED: A02_KICK", changes[0].text)
    }

    @Test
    fun `a cleared pad is named by the pad that was there`() {
        val from = kit(pad(1), pad(4, "A04_clap.wav"))
        val to = kit(pad(1))
        assertEquals(listOf("A04 CLEARED: A04_CLAP"), texts(from, to))
    }

    @Test
    fun `pad fields read in the pad's own words on one line`() {
        val a = pad(3)
        val b = a.copy(level = 0.5f, pan = 0.25f, tuneCoarse = -2, tuneFine = 15, muteGroup = 1, oneShot = false)
        val line = texts(kit(a), kit(b)).single()
        assertTrue(line.startsWith("A03 "), line)
        assertTrue("LEVEL 0.71 → 0.50" in line, line)
        assertTrue("PAN 0.50 → 0.25" in line, line)
        assertTrue("TUNE 0 → -2" in line, line)
        assertTrue("FINE 0 → +15" in line, line)
        assertTrue("MUTE GROUP 0 → 1" in line, line)
        assertTrue("ONE-SHOT OFF" in line, line)
        assertEquals(3, KitDiff.changes(kit(a), kit(b)).single().slot)
    }

    @Test
    fun `a shape field left null reads as DEFAULT, never an invented number`() {
        val a = pad(1)
        val b = a.copy(decay = 0.3f)
        assertEquals(listOf("A01 DECAY DEFAULT → 0.30"), texts(kit(a), kit(b)))
        assertEquals(listOf("A01 DECAY 0.30 → DEFAULT"), texts(kit(b), kit(a)))
    }

    @Test
    fun `a recipe is named the way its own card names it`() {
        val plain = pad(1)
        val crushed = plain.copy(recipe = obj("era" to JsonValue.Str("sp1200"), "amount" to JsonValue.Num(0.35)))
        assertEquals(listOf("A01 TREATED: CRUSH 35%"), texts(kit(plain), kit(crushed)))

        val smeared = plain.copy(recipe = obj("treatment" to JsonValue.Str("smeared"), "amount" to JsonValue.Num(0.5)))
        assertEquals(listOf("A01 CRUSH 35% → TAIL 50%"), texts(kit(crushed), kit(smeared)))

        val retuned = plain.copy(recipe = obj("keyed" to JsonValue.Str("retuned"), "amount" to JsonValue.Num(0.4)))
        assertEquals("IN KEY 40%", KitDiff.recipeName(retuned.recipe!!), "the card's display word, not the segment id")

        assertEquals(listOf("A01 UNTREATED"), texts(kit(crushed), kit(plain)))
    }

    @Test
    fun `recipes no card reads back still get a short honest word`() {
        assertEquals("MUTATED: MORPH", KitDiff.recipeName(obj("mutate" to obj("mode" to JsonValue.Str("morph")))))
        assertEquals("OUTSIDE: REAMP", KitDiff.recipeName(obj("outside" to obj("move" to JsonValue.Str("reamp")))))
        assertEquals("ROUND ROBIN ×4", KitDiff.recipeName(obj("robin" to obj("takes" to JsonValue.Num(4.0)))))
        assertEquals("SPLICED", KitDiff.recipeName(obj("splice" to obj("crossfaded" to JsonValue.Bool(false)))))
        assertEquals("DOCTORED: SUB-CARVE", KitDiff.recipeName(obj("doctor" to JsonValue.Str("sub-carve"))))
        assertEquals("CLEANED", KitDiff.recipeName(obj("clean" to obj())))
        assertEquals("SCULPTED: FREEZE", KitDiff.recipeName(obj("sculpt" to obj("mode" to JsonValue.Str("freeze")))))
        assertEquals("SMEAR", KitDiff.recipeName(obj("verb" to JsonValue.Str("smear"), "amount" to JsonValue.Num(0.2))))
        assertEquals("SYNTH PATCH", KitDiff.recipeName(obj("patch" to obj())))
        assertEquals("RECIPE", KitDiff.recipeName(obj("somethingNew" to JsonValue.Bool(true))), "unknown shapes are still reported, just not named")
    }

    @Test
    fun `layers and chains are counted, not dumped`() {
        val a = pad(1, "A01_kick.wav")
        val layered = a.copy(
            velocityLayers = listOf(
                KitLayer("A01_kick_soft.wav", 0, 63),
                KitLayer("A01_kick.wav", 64, 127),
            ),
        )
        assertEquals(listOf("A01 LAYERS 1 → 2"), texts(kit(a), kit(layered)))

        // Four slices that cycle only three: both numbers are said, because
        // `PadHit` plays the cycle and the WAV holds the slices.
        val chained = a.copy(chain = ChainInfo(boundaries = listOf(0L, 1000L, 2000L, 3000L), cycle = 3))
        assertEquals(listOf("A01 CHAINED: 4 SLICES, CYCLE 3"), texts(kit(a), kit(chained)))
        assertEquals(listOf("A01 UNCHAINED"), texts(kit(chained), kit(a)))

        val zoned = a.copy(
            chain = ChainInfo(
                boundaries = listOf(0L, 1000L, 2000L, 3000L),
                cycle = 2,
                zones = listOf(ChainZone(0, 63, 0, 2), ChainZone(64, 127, 2, 2)),
            ),
        )
        assertEquals(listOf("A01 CHAIN 4 SLICES, CYCLE 3 → 4 SLICES, 2 ZONES"), texts(kit(chained), kit(zoned)))
    }

    @Test
    fun `the AMT is rounded the way the card's own stepper prints it`() {
        val recipe = obj("era" to JsonValue.Str("sp1200"), "amount" to JsonValue.Num(0.346))
        assertEquals("CRUSH 35%", KitDiff.recipeName(recipe), "roundToInt, not toInt — PadSheetScreen prints 35% for this pad")
    }

    @Test
    fun `kit-level changes come first and carry no slot`() {
        val from = kit(pad(1))
        val to = Kit(
            "drums two",
            listOf(pad(1).copy(level = 0.2f)),
            key = KeySpec(9, Scale.MINOR),
            tempoBpm = 92.5f,
            wear = WearLedger(),
        )
        val changes = KitDiff.changes(from, to)
        assertEquals(
            listOf("RENAMED \"drums\" → \"drums two\"", "KEY NONE → A MINOR", "TEMPO NONE → 92.5 BPM", "TAPE WEAR ON"),
            changes.take(4).map { it.text },
        )
        assertTrue(changes.take(4).all { it.slot == null })
        assertEquals(1, changes.last().slot)
        assertEquals("5 CHANGES", KitDiff.headline(changes))
        assertEquals("1 CHANGE", KitDiff.headline(changes.take(1)))
    }

    @Test
    fun `provenance alone is not a change`() {
        val a = pad(1)
        val b = a.copy(source = mapOf("app" to "SnipSnap", "outside" to "REAMP"))
        assertEquals(emptyList(), KitDiff.changes(kit(a), kit(b)))
    }
}
