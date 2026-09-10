package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.KeySpec
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeReplayTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("replay").toFile()

    private fun obj(vararg pairs: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*pairs))

    /** Two pads with the SAME bytes, so a replay on 2 can be checked against the original on 1 byte for byte. */
    private fun twins(name: String, key: KeySpec? = null): KitBuilderModel {
        val m = KitBuilderModel.create(name, File(temp, name))
        val snare = DrumSynth.snare()
        m.assign(1, snare, DrumClass.SNARE)
        m.assign(2, snare, DrumClass.SNARE)
        key?.let { m.setKey(it) }
        return m
    }

    private fun bytes(m: KitBuilderModel, slot: Int) = File(m.kitDir, m.pad(slot)!!.sampleFile).readBytes()

    @Test
    fun `plans refuse by name, never by skipping`() {
        assertEquals(RecipeReplay.Plan.Refused(Copy.REPLAY_NOTHING), RecipeReplay.plan(null))
        val mutate = RecipeReplay.plan(
            obj("mutate" to obj("mode" to JsonValue.Str("splice"), "with" to JsonValue.Arr(listOf(JsonValue.Str("Kit:A02"))))),
        )
        assertEquals(RecipeReplay.Plan.Refused("MUTATE (SPLICE WITH KIT A02) NEEDS ITS PARENT - NOT CARRIED."), mutate)
        assertEquals(RecipeReplay.Plan.Refused(Copy.REPLAY_SPLICE), RecipeReplay.plan(obj("splice" to obj("crossfaded" to JsonValue.Bool(true)))))
        assertEquals(RecipeReplay.Plan.Refused(Copy.REPLAY_OUTSIDE), RecipeReplay.plan(obj("outside" to obj("move" to JsonValue.Str("reamp")))))
        assertEquals(RecipeReplay.Plan.Refused(Copy.replayMeasured("THE DOCTOR")), RecipeReplay.plan(obj("doctor" to JsonValue.Str("sub-carve"))))
        assertEquals(RecipeReplay.Plan.Refused(Copy.replayMeasured("CLEAN")), RecipeReplay.plan(obj("clean" to obj())))
        assertEquals(RecipeReplay.Plan.Refused(Copy.replayMeasured("SCULPT")), RecipeReplay.plan(obj("sculpt" to obj("mode" to JsonValue.Str("freeze")))))
        assertEquals(RecipeReplay.Plan.Refused(Copy.REPLAY_NO_DOOR), RecipeReplay.plan(obj("somethingNew" to JsonValue.Bool(true))))
        assertEquals(RecipeReplay.Plan.Refused(Copy.REPLAY_NO_DOOR), RecipeReplay.plan(obj("verb" to JsonValue.Str("wobble"), "amount" to JsonValue.Num(0.2))))
        // A ROOM trip nests its outside block inside a mutate recipe: MUTATE's refusal wins, and names the room.
        val room = RecipeReplay.plan(
            obj(
                "mutate" to obj(
                    "mode" to JsonValue.Str("room"),
                    "with" to JsonValue.Arr(listOf(JsonValue.Str("${Rooms.LABEL_PREFIX}Funk Room"))),
                    "outside" to obj("move" to JsonValue.Str("room")),
                ),
            ),
        )
        assertIs<RecipeReplay.Plan.Refused>(room)
        assertTrue("FUNK ROOM" in room.reason, room.reason)
    }

    @Test
    fun `plans read every dial the writers wrote`() {
        assertEquals(RecipeReplay.Plan.Era("sp1200", 0.35f), RecipeReplay.plan(obj("era" to JsonValue.Str("sp1200"), "amount" to JsonValue.Num(0.35))))
        assertEquals(
            RecipeReplay.Plan.Character("punched", 0.6f),
            RecipeReplay.plan(obj("recipe" to JsonValue.Num(1.0), "fx" to obj(), "treatment" to JsonValue.Str("punched"), "amount" to JsonValue.Num(0.6))),
        )
        val keyed = RecipeReplay.plan(
            obj(
                "keyed" to JsonValue.Str("eternal"),
                "key" to JsonValue.Str("C MAJOR"),
                "amount" to JsonValue.Num(0.5),
                "seed" to JsonValue.Num(7.0),
                "decay" to JsonValue.Num(2.5),
                "division" to JsonValue.Str("1/8"),
                "tail" to JsonValue.Num(4.0),
                "knee" to JsonValue.Num(0.02),
            ),
        )
        assertEquals(
            RecipeReplay.Plan.KeyedPlan("eternal", 0.5f, 7L, Keyed.Dials(decay = 2.5f, division = "1/8", tail = 4f, knee = 0.02f), "C MAJOR"),
            keyed,
        )
        assertEquals(RecipeReplay.Plan.Smear(0.4f), RecipeReplay.plan(obj("verb" to JsonValue.Str("smear"), "amount" to JsonValue.Num(0.4))))
        assertEquals(RecipeReplay.Plan.RobinDeal(4, 9, 2), RecipeReplay.plan(obj("robin" to obj("takes" to JsonValue.Num(4.0), "seed" to JsonValue.Num(9.0), "zones" to JsonValue.Num(2.0)))))
        assertEquals(RecipeReplay.Plan.RobinDeal(3, 0, null), RecipeReplay.plan(obj("robin" to obj("takes" to JsonValue.Num(3.0)))))
    }

    @Test
    fun `an era and a character replay byte for byte, and the clip names them`() {
        val m = twins("Era")
        m.eraPad(1, "sp1200", 0.5f)
        val clip = RecipeReplay.clip(m.pad(1)!!.recipe, m.name, 1)
        assertNotNull(clip)
        assertEquals("CRUSH 50%", clip.word)
        assertEquals("ERA A01", clip.from)
        val done = RecipeReplay.apply(m, 2, clip.recipe, "SNARE 02")
        assertEquals(m.pad(1)!!.recipe, done.pad.recipe)
        assertTrue(bytes(m, 1).contentEquals(bytes(m, 2)), "the same era at the same AMT on the same take is the same sound")
        assertEquals("CRUSH 50% DONE AGAIN ON SNARE 02. ORIGINAL SLEEPS IN THE BIN.", done.toast)

        val c = twins("Char")
        c.characterPad(1, "punched", 0.7f)
        RecipeReplay.apply(c, 2, c.pad(1)!!.recipe!!, "SNARE 02")
        assertEquals(c.pad(1)!!.recipe, c.pad(2)!!.recipe)
        assertTrue(bytes(c, 1).contentEquals(bytes(c, 2)))
        assertNull(RecipeReplay.clip(null, "x", 1), "nothing to copy off an untouched pad")
    }

    @Test
    fun `a keyed recipe rings in the destination's key, and the toast names the source's when it differs`() {
        val m = twins("KeyedD", key = KeySpec.parse("Dm"))
        m.keyedPad(1, "bodied", 0.6f)
        val recipe = m.pad(1)!!.recipe!!
        // Pretend the source was written in another kit's key: the label is
        // carried, the destination's own key is what plays.
        val carried = JsonValue.Obj(recipe.entries + ("key" to JsonValue.Str("C MAJOR")))
        val done = RecipeReplay.apply(m, 2, carried, "SNARE 02")
        assertEquals("D MINOR", m.lastKeyLabel)
        assertTrue("IN D MINOR - THE SOURCE WAS C MAJOR" in done.toast, done.toast)
        assertTrue(bytes(m, 1).contentEquals(bytes(m, 2)), "same dials, same key, same take: same sound")

        val same = RecipeReplay.apply(twins("KeyedSame", key = KeySpec.parse("Dm")).also { it.keyedPad(1, "bodied", 0.6f) }, 2, recipe, "SNARE 02")
        assertTrue("THE SOURCE WAS" !in same.toast, same.toast)
    }

    @Test
    fun `smear, a patch and a robin deal replay through their own doors`() {
        val s = twins("Smear")
        s.smearPad(1, 0.4f)
        assertEquals(0.4f, PadSheet.readSmear(s.pad(1)!!.recipe))
        RecipeReplay.apply(s, 2, s.pad(1)!!.recipe!!, "SNARE 02")
        assertEquals(s.pad(1)!!.recipe, s.pad(2)!!.recipe)
        assertTrue(bytes(s, 1).contentEquals(bytes(s, 2)))
        // Smearing a smeared pad restores first, never stacks.
        val before = bytes(s, 1)
        s.smearPad(1, 0.4f)
        assertTrue(before.contentEquals(bytes(s, 1)), "the same AMT twice is the same sound, not a double smear")

        val p = twins("Patch")
        p.desamplePad(1, evenIfFar = true)
        val done = RecipeReplay.apply(p, 2, p.pad(1)!!.recipe!!, "SNARE 02")
        assertEquals(p.pad(1)!!.recipe, p.pad(2)!!.recipe)
        assertTrue(bytes(p, 1).contentEquals(bytes(p, 2)), "a patch renders the same bytes wherever it lands")
        assertEquals("SNARE 02 IS THAT PATCH NOW. ITS OWN SOUND SLEEPS IN THE BIN.", done.toast)

        val r = twins("Robin")
        Robin.apply(r, 1, takes = 3, seed = 5)
        val dealt = RecipeReplay.apply(r, 2, r.pad(1)!!.recipe!!, "SNARE 02")
        assertEquals(3, dealt.pad.chain!!.boundaries.size)
        assertEquals("ROUND ROBIN ×3 DEALT AGAIN ON SNARE 02 - SAME RECIPE, NEW DEAL.", dealt.toast)
    }

    @Test
    fun `a refused recipe throws its reason and touches nothing`() {
        val m = twins("Refuse")
        val before = bytes(m, 2)
        val e = assertFailsWith<IllegalArgumentException> {
            RecipeReplay.apply(m, 2, obj("splice" to obj("crossfaded" to JsonValue.Bool(false))), "SNARE 02")
        }
        assertEquals(Copy.REPLAY_SPLICE, e.message)
        assertTrue(before.contentEquals(bytes(m, 2)))
        assertNull(m.pad(2)!!.recipe)
    }
}
