package com.snipsnap.shell

import com.snipsnap.json.JsonValue
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Terra
import com.snipsnap.synth.TerraPatch
import com.snipsnap.synth.TerraVoice
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpVoice
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A struck TERRA pad through `:shell`'s rewrites (spec, "Data flow and
 * compatibility", "Drivers survive the house's own rewrites"; "Testing",
 * tests 4 and 5 with a striker only). Nothing in `:shell` changes for
 * this:
 * - BREED rewrites macros through the patch's own JSON;
 * - the presets shelf renames through it;
 * - DO IT AGAIN plans through PadRecipe.
 *
 * So each one carries the striker, or refuses a recipe it cannot read, by
 * construction. These tests pin that.
 */
class TerraStrikerCarryTest {

    private val head = Terra.captureStriker(Thump.render(ThumpVoice.KICK)) ?: error("a kick is not silent")
    private val striker = TerraPatch.Striker(head, 0.75f, "A01")
    private val struck = TerraPatch("STRUCK", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.3f, "DECAY" to 0.4f), striker)
    private val plain = TerraPatch("PLAIN", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.7f, "DECAY" to 0.9f))

    @Test
    fun `BREED keeps parent A's striker and never crosses HIT`() {
        val child = Breed.cross(PadRecipe(patch = struck), PadRecipe(patch = plain), Random(3)).patch as TerraPatch
        assertEquals(striker, child.striker, "the child lost or changed A's striker")
        val reverse = Breed.cross(PadRecipe(patch = plain), PadRecipe(patch = struck), Random(3)).patch as TerraPatch
        assertNull(reverse.striker, "a child of a plain A took B's striker")
        val softer = struck.copy(striker = TerraPatch.Striker(head, 0.25f, "A01"))
        val both = Breed.cross(PadRecipe(patch = struck), PadRecipe(patch = softer), Random(5)).patch as TerraPatch
        assertEquals(0.75f, both.striker?.hit, "HIT was crossed like a macro")
    }

    @Test
    fun `the presets shelf keeps a striker through save, reload and a rename`() {
        val root = java.nio.file.Files.createTempDirectory("terra-presets").toFile()
        try {
            val mine = struck.copy(name = "MY DRUM")
            UserPresets.save(root, mine, nowMillis = 1_700_000_000_000L)
            val back = UserPresets.read(root).single().patch
            assertEquals(mine, back, "the reloaded preset is not the saved one")
            assertContentEquals(mine.render().samples, back.render().samples, "the reloaded preset renders other bytes")
            val binned = requireNotNull(UserPresets.forget(root, "TERRA", "COMPOUND_MEMBRANE", "MY DRUM", nowMillis = 1_700_000_100_000L))
            UserPresets.save(root, plain.copy(name = "MY DRUM"), nowMillis = 1_700_000_200_000L)
            val restored = requireNotNull(UserPresets.unforget(root, binned))
            assertEquals("MY DRUM 2", restored.name, "the restore should land under a fresh name")
            assertEquals(striker, (restored.patch as TerraPatch).striker, "the rename dropped the striker")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `DO IT AGAIN replays a struck recipe whole, and refuses one it cannot read`() {
        val recipe = PadRecipe(patch = struck)
        assertEquals(RecipeReplay.Plan.Patch(recipe), RecipeReplay.plan(recipe.toJsonValue()))
        assertEquals(struck, Breed.recipeOf(recipe.toJsonValue())?.patch)
        val later = LinkedHashMap(struck.toJsonValue().entries).also { it["version"] = JsonValue.Num(3.0) }
        val unreadable = JsonValue.Obj(LinkedHashMap(recipe.toJsonValue().entries).also { it["patch"] = JsonValue.Obj(later) })
        assertEquals(RecipeReplay.Plan.Refused(Copy.REPLAY_NO_DOOR), RecipeReplay.plan(unreadable))
        assertNull(Breed.recipeOf(unreadable), "BREED read a recipe it cannot decode")
    }
}
