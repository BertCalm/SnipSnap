package com.snipsnap.shell

import com.snipsnap.synth.Chamber
import com.snipsnap.synth.ChamberPatch
import com.snipsnap.synth.ChamberVoice
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticVoice

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.Alloy
import com.snipsnap.synth.AlloyPatch
import com.snipsnap.synth.AlloyVoice
import com.snipsnap.synth.Bellows
import com.snipsnap.synth.BellowsPatch
import com.snipsnap.synth.BellowsVoice
import com.snipsnap.synth.Circuit
import com.snipsnap.synth.CircuitPatch
import com.snipsnap.synth.CircuitVoice
import com.snipsnap.synth.CisternPatch
import com.snipsnap.synth.CisternVoice
import com.snipsnap.synth.ContourEngine
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.ContourVoice
import com.snipsnap.synth.Embouchure
import com.snipsnap.synth.EmbouchurePatch
import com.snipsnap.synth.EmbouchureVoice
import com.snipsnap.synth.Lacquer
import com.snipsnap.synth.LacquerPatch
import com.snipsnap.synth.LacquerPresets
import com.snipsnap.synth.LacquerVoice
import com.snipsnap.synth.Murmur
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.MurmurVoice
import com.snipsnap.synth.Nimbus
import com.snipsnap.synth.NimbusPatch
import com.snipsnap.synth.NimbusVoice
import com.snipsnap.synth.Osprey
import com.snipsnap.synth.OspreyCuratedPresets
import com.snipsnap.synth.OspreyPatch
import com.snipsnap.synth.OspreyPresets
import com.snipsnap.synth.OspreyVoice
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Pitchwheel
import com.snipsnap.synth.PitchwheelPatch
import com.snipsnap.synth.PitchwheelVoice
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.PrismVoice
import com.snipsnap.synth.Stoop
import com.snipsnap.synth.StoopPatch
import com.snipsnap.synth.StoopVoice
import com.snipsnap.synth.Relay
import com.snipsnap.synth.RelayPatch
import com.snipsnap.synth.RelayVoice
import com.snipsnap.synth.Revel
import com.snipsnap.synth.RevelPatch
import com.snipsnap.synth.RevelVoice
import com.snipsnap.synth.Skitter
import com.snipsnap.synth.SkitterPatch
import com.snipsnap.synth.SkitterVoice
import com.snipsnap.synth.Spool
import com.snipsnap.synth.SpoolPatch
import com.snipsnap.synth.SpoolVoice
import com.snipsnap.synth.Suture
import com.snipsnap.synth.SuturePatch
import com.snipsnap.synth.SutureVoice
import com.snipsnap.synth.Tessera
import com.snipsnap.synth.TesseraPatch
import com.snipsnap.synth.TesseraVoice
import com.snipsnap.synth.Tangle
import com.snipsnap.synth.TanglePatch
import com.snipsnap.synth.TangleVoice
import com.snipsnap.synth.Thaw
import com.snipsnap.synth.ThawPatch
import com.snipsnap.synth.ThawVoice
import com.snipsnap.synth.Undertow
import com.snipsnap.synth.UndertowPatch
import com.snipsnap.synth.UndertowVoice
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StarterKitsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("starters").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** What a single-engine starter is built from: its patch type, every voice it can play, and its engine's own filing. */
    private class OneEngine(val patch: Class<out Patch>, val voices: Set<String>, val drumClassFor: (Patch) -> DrumClass)

    /**
     * The single-engine starters with no block of their own below. Each SynthKits
     * builder lands sixteen pads through `pad(patch, drumClass)` with no chain, so
     * every pad is dry, carries its engine's patch, is filed by that engine's
     * drumClassFor, and the sixteen reach every voice.
     */
    private val oneEngineStarters: Map<String, OneEngine> = mapOf(
        "chamber" to OneEngine(ChamberPatch::class.java, ChamberVoice.entries.map { it.name }.toSet()) {
            (it as ChamberPatch).let { p -> Chamber.drumClassFor(p.voice, p.macros) }
        },
        "caustic" to OneEngine(CausticPatch::class.java, CausticVoice.entries.map { it.name }.toSet()) {
            (it as CausticPatch).let { p -> CausticInstrument.drumClassFor(p.voice, p.macros, p.model) }
        },
        "circuit" to OneEngine(CircuitPatch::class.java, CircuitVoice.entries.map { it.name }.toSet()) {
            (it as CircuitPatch).let { p -> Circuit.drumClassFor(p.voice, p.macros) }
        },
        "thaw" to OneEngine(ThawPatch::class.java, ThawVoice.entries.map { it.name }.toSet()) {
            (it as ThawPatch).let { p -> Thaw.drumClassFor(p.voice, p.macros) }
        },
        "nimbus" to OneEngine(NimbusPatch::class.java, NimbusVoice.entries.map { it.name }.toSet()) {
            (it as NimbusPatch).let { p -> Nimbus.drumClassFor(p.voice, p.macros) }
        },
        "undertow" to OneEngine(UndertowPatch::class.java, UndertowVoice.entries.map { it.name }.toSet()) {
            (it as UndertowPatch).let { p -> Undertow.drumClassFor(p.voice, p.macros) }
        },
        "suture" to OneEngine(SuturePatch::class.java, SutureVoice.entries.map { it.name }.toSet()) {
            (it as SuturePatch).let { p -> Suture.drumClassFor(p.voice, p.macros) }
        },
        "prism" to OneEngine(PrismPatch::class.java, PrismVoice.entries.map { it.name }.toSet()) {
            (it as PrismPatch).let { p -> Prism.drumClassFor(p.voice, p.macros) }
        },
        "alloy" to OneEngine(AlloyPatch::class.java, AlloyVoice.entries.map { it.name }.toSet()) {
            (it as AlloyPatch).let { p -> Alloy.drumClassFor(p.voice, p.macros) }
        },
        "contour" to OneEngine(ContourPatch::class.java, ContourVoice.entries.map { it.name }.toSet()) {
            (it as ContourPatch).let { p -> ContourEngine.drumClassFor(p.voice, p.macros) }
        },
        "spool" to OneEngine(SpoolPatch::class.java, SpoolVoice.entries.map { it.name }.toSet()) {
            (it as SpoolPatch).let { p -> Spool.drumClassFor(p.voice, p.macros) }
        },
        "embouchure" to OneEngine(EmbouchurePatch::class.java, EmbouchureVoice.entries.map { it.name }.toSet()) {
            (it as EmbouchurePatch).let { p -> Embouchure.drumClassFor(p.voice, p.macros) }
        },
        "bellows" to OneEngine(BellowsPatch::class.java, BellowsVoice.entries.map { it.name }.toSet()) {
            (it as BellowsPatch).let { p -> Bellows.drumClassFor(p.voice, p.macros) }
        },
        "murmur" to OneEngine(MurmurPatch::class.java, MurmurVoice.entries.map { it.name }.toSet()) {
            (it as MurmurPatch).let { p -> Murmur.drumClassFor(p.voice, p.macros) }
        },
        "osprey-curated" to OneEngine(OspreyPatch::class.java, OspreyVoice.entries.map { it.name }.toSet()) {
            (it as OspreyPatch).let { p -> Osprey.drumClassFor(p.voice, p.macros) }
        },
    )

    @Test
    fun `every starter renders a preflight-clean kit folder`() {
        assertEquals(StarterKits.ALL.size, StarterKits.ALL.map { it.id }.toSet().size, "ids unique")
        for (starter in StarterKits.ALL) {
            val dir = File(temp, starter.id)
            val kit = starter.render("Starter ${starter.id}", dir, seed = 1)
            if (starter.id == "blank") {
                // The empty grid is the point: no pads to assign yet, and
                // preflight correctly refuses to export nothing.
                assertTrue(kit.pads.isEmpty(), "blank is the empty grid")
                assertTrue(Preflight.check(kit, dir).blocked(), "blank should block export until captured into")
            } else {
                assertTrue(kit.pads.isNotEmpty(), starter.id)
                assertFalse(Preflight.check(kit, dir).blocked(), "${starter.id} failed preflight")
                // Regenerable where the engine records how: most starter pads
                // carry recipes (not all — some treatments are render-only).
                if (starter.id != StarterKits.CONFLUENCE_ID) {
                    assertTrue(kit.pads.any { it.recipe != null }, "${starter.id}: no pad carries a recipe")
                }
                // Most starters fill bank A. The wordless source bank has eight pads;
                // VENT leaves four capture slots free; the A/B dip fills bank B with a twin of each.
                val slots = when (starter.id) {
                    "lucky-dip-ab" -> 32
                    StarterKits.CONFLUENCE_ID -> 8
                    "vent" -> 12
                    "strike-fx" -> 4
                    else -> 16
                }
                assertEquals((1..slots).toList(), kit.pads.map { it.slot }, "${starter.id}: a full bank, no gaps")
                // Every recipe a starter writes reads back through the typed layer.
                for (pad in kit.pads) {
                    if (pad.recipe != null) assertNotNull(Breed.recipeOf(pad.recipe), "${starter.id} ${pad.displayName}: its recipe does not parse")
                }
                // CLOUD's grain clouds (A07-A16) are render-only: GRAINS has no Patch type.
                // CONFLUENCE carries source-bank provenance instead of typed Patch recipes.
                // Every remaining pad of every starter carries its recipe.
                val renderOnly = kit.pads.filter { it.recipe == null }.map { it.slot }
                val expectedRenderOnly = when (starter.id) {
                    "cloud" -> (7..16).toList()
                    StarterKits.CONFLUENCE_ID -> (1..8).toList()
                    else -> emptyList()
                }
                assertEquals(expectedRenderOnly, renderOnly, "${starter.id}: pads with no recipe")
            }
            oneEngineStarters[starter.id]?.let { engine ->
                val model = KitBuilderModel.open(dir)
                assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
                assertEquals(16, model.kit.pads.size)
                val patches = model.kit.pads.map { pad ->
                    val recipe = assertNotNull(Breed.recipeOf(pad.recipe), "${starter.id} ${pad.displayName}: no recipe")
                    val patch = assertNotNull(recipe.patch, "${starter.id} ${pad.displayName}: the recipe has no patch")
                    assertTrue(engine.patch.isInstance(patch), "${starter.id} ${pad.displayName}: a ${patch.javaClass.simpleName}, not a ${engine.patch.simpleName}")
                    assertNull(recipe.fx, "${starter.id} ${pad.displayName}: starter pads land dry")
                    assertEquals(engine.drumClassFor(patch), pad.drumClass, "${starter.id} ${pad.displayName}: filed by its engine")
                    assertTrue(pad.oneShot, "${starter.id} ${pad.displayName}: plays its complete rendered buffer")
                    patch
                }
                assertEquals(engine.voices, patches.map { it.voiceName }.toSet(), "${starter.id}: every voice is reachable")
                if (starter.id == "osprey-curated") {
                    val curated = patches.map { assertIs<OspreyPatch>(it) }
                    assertEquals(OspreyCuratedPresets.all(), curated, "the curated roster survives kit save/load unchanged")
                    assertEquals(curated.map { it.name }, model.kit.pads.map { it.displayName }, "preset names stay visible on the pads")
                    assertTrue(curated.all { it.modelVersion == 3 }, "the curated bank keeps the approved R3 renderer")
                    assertTrue(curated.map { it.midi }.toSet().size > 1, "curated roles retain their different registers")
                    assertTrue(curated.map { it.durationSeconds }.toSet().size > 1, "short gestures and long tails retain their durations")
                    assertTrue(model.kit.pads.all { it.drumClass == DrumClass.TONAL }, "long finite tails stay TONAL rather than looping")
                }
                // The first pad (a finite note) and the last (held for most kits) each
                // regenerate their saved WAV exactly from the recipe alone.
                val first = model.kit.pads.first()
                val last = model.kit.pads.last()
                val firstBytes = File(dir, first.sampleFile).readBytes()
                val lastBytes = File(dir, last.sampleFile).readBytes()
                val firstAgain = RecipeReplay.apply(model, last.slot, first.recipe!!, last.displayName)
                assertEquals(first.recipe, firstAgain.pad.recipe, "${starter.id}: the first pad's recipe survives replay")
                assertTrue(firstBytes.contentEquals(File(dir, firstAgain.pad.sampleFile).readBytes()), "${starter.id}: the first pad regenerates exactly")
                val lastAgain = RecipeReplay.apply(model, first.slot, last.recipe!!, first.displayName)
                assertEquals(last.recipe, lastAgain.pad.recipe, "${starter.id}: the last pad's recipe survives replay")
                assertTrue(lastBytes.contentEquals(File(dir, lastAgain.pad.sampleFile).readBytes()), "${starter.id}: the last pad regenerates exactly")
            }
            if (starter.id == "cistern") {
                // Reuse this rendered kit to exercise its saved recipes through
                // the editing door, rather than rendering the whole kit twice.
                val model = KitBuilderModel.open(dir)
                assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
                val drumClasses = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
                val voices = model.kit.pads.map { pad ->
                    val recipe = Breed.recipeOf(pad.recipe)!!
                    val patch = assertIs<CisternPatch>(recipe.patch)
                    assertNull(recipe.fx, "${pad.displayName}: the wet surface is the engine itself")
                    assertTrue(pad.drumClass !in drumClasses, "${pad.displayName}: ${pad.drumClass}")
                    assertTrue(pad.oneShot, "${pad.displayName}: starter pads play their complete rendered surface")
                    patch.voice
                }
                assertEquals(CisternVoice.entries.toSet(), voices.toSet(), "all six surfaces are reachable")
                val source = model.kit.pads.first()
                val target = model.kit.pads.last()
                val original = File(dir, source.sampleFile).readBytes()
                val replayed = RecipeReplay.apply(model, target.slot, source.recipe!!, target.displayName)
                assertEquals(source.recipe, replayed.pad.recipe, "pitch and velocity survive replay")
                assertTrue(original.contentEquals(File(dir, replayed.pad.sampleFile).readBytes()), "the saved surface regenerates exactly")
            }
            if (starter.id == "tessera") {
                val model = KitBuilderModel.open(dir)
                assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
                assertEquals(16, model.kit.pads.size)
                val patches = model.kit.pads.map { pad ->
                    val recipe = Breed.recipeOf(pad.recipe)!!
                    val patch = assertIs<TesseraPatch>(recipe.patch)
                    assertNull(recipe.fx, "${pad.displayName}: the chamber is inside the instrument")
                    assertEquals(Tessera.drumClassFor(patch.voice, patch.macros), pad.drumClass)
                    assertTrue(pad.oneShot, "${pad.displayName}: the pad plays its complete finite or settled buffer")
                    patch
                }
                assertEquals(TesseraVoice.entries.toSet(), patches.map { it.voice }.toSet())
                assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { Tessera.midiFor(it.voice, it.macros.getValue("TUNE")) })
                assertEquals(DrumClass.LOOP, model.kit.pads.last().drumClass)
                val source = model.kit.pads.first()
                val target = model.kit.pads.last()
                val original = File(dir, source.sampleFile).readBytes()
                val replayed = RecipeReplay.apply(model, target.slot, source.recipe!!, target.displayName)
                assertEquals(source.recipe, replayed.pad.recipe, "the material and chamber recipe survive replay")
                assertTrue(original.contentEquals(File(dir, replayed.pad.sampleFile).readBytes()), "the saved object regenerates exactly")
            }
            if (starter.id == "pitchwheel") {
                val model = KitBuilderModel.open(dir)
                assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
                assertEquals(16, model.kit.pads.size)
                val patches = model.kit.pads.map { pad ->
                    val recipe = Breed.recipeOf(pad.recipe)!!
                    val patch = assertIs<PitchwheelPatch>(recipe.patch)
                    assertNull(recipe.fx, "${pad.displayName}: wheel and resin establish their sound dry")
                    assertEquals(Pitchwheel.drumClassFor(patch.voice, patch.macros), pad.drumClass)
                    assertTrue(pad.oneShot, "${pad.displayName}: the pad plays its finite gesture or settled buffer")
                    patch
                }
                assertEquals(PitchwheelVoice.entries.toSet(), patches.map { it.voice }.toSet())
                assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
                assertTrue(model.kit.pads.dropLast(1).all { it.drumClass == DrumClass.TONAL })
                assertEquals(DrumClass.LOOP, model.kit.pads.last().drumClass)
                val source = model.kit.pads.first()
                val target = model.kit.pads.last()
                val original = File(dir, source.sampleFile).readBytes()
                val replayed = RecipeReplay.apply(model, target.slot, source.recipe!!, target.displayName)
                assertEquals(source.recipe, replayed.pad.recipe, "the complete wheel recipe survives replay")
                assertTrue(original.contentEquals(File(dir, replayed.pad.sampleFile).readBytes()), "the saved wheel regenerates exactly")
            }
            if (starter.id == "lacquer") {
                val model = KitBuilderModel.open(dir)
                assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
                assertEquals(16, model.kit.pads.size)
                val patches = model.kit.pads.map { pad ->
                    val recipe = requireNotNull(Breed.recipeOf(pad.recipe))
                    val patch = assertIs<LacquerPatch>(recipe.patch)
                    assertNull(recipe.fx, "${pad.displayName}: first acceptance auditions dry layer articulation")
                    assertEquals(Lacquer.drumClassFor(patch.voice, patch.macros), pad.drumClass)
                    assertTrue(pad.oneShot)
                    patch
                }
                assertEquals(LacquerVoice.entries.toSet(), patches.map { it.voice }.toSet())
                assertEquals(LacquerPresets.references(), patches.drop(8), "the sixteen-pad starter preserves its original eight reference recipes")
                assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
                assertEquals(1, model.kit.pads.count { it.drumClass == DrumClass.LOOP })
                val source = model.kit.pads.first()
                val target = model.kit.pads.last()
                val original = File(dir, source.sampleFile).readBytes()
                val replayed = RecipeReplay.apply(model, target.slot, source.recipe!!, target.displayName)
                assertEquals(source.recipe, replayed.pad.recipe, "all serialized layer templates survive recipe replay")
                assertTrue(original.contentEquals(File(dir, replayed.pad.sampleFile).readBytes()), "the saved layered tone regenerates exactly")
            }
            if (starter.id == "stoop") {
                val model = KitBuilderModel.open(dir)
                assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
                assertEquals(16, model.kit.pads.size)
                val patches = model.kit.pads.map { pad ->
                    val recipe = Breed.recipeOf(pad.recipe)!!
                    val patch = assertIs<StoopPatch>(recipe.patch)
                    assertNull(recipe.fx, "${pad.displayName}: the groove source and answer are dry")
                    assertEquals(Stoop.drumClassFor(patch.voice, patch.macros), pad.drumClass)
                    assertEquals(patch.midi.toString(), pad.source["rootMidi"])
                    if (patch.voice == StoopVoice.CHORD) {
                        assertEquals(listOf(0, 7, 12), patch.chordIntervals)
                        assertEquals("0,7,12", pad.source["chordIntervals"])
                    }
                    patch
                }
                assertEquals(StoopVoice.entries.toSet(), patches.map { it.voice }.toSet())
                assertEquals(listOf(36, 39, 41, 43, 60, 63, 65, 67), patches.take(8).map { it.midi })
                assertTrue(model.kit.pads.take(14).all { it.drumClass == DrumClass.TONAL })
                assertTrue(model.kit.pads.takeLast(2).all { it.drumClass == DrumClass.LOOP })
                val source = model.kit.pads[12]
                val target = model.kit.pads.last()
                val original = File(dir, source.sampleFile).readBytes()
                val replayed = RecipeReplay.apply(model, target.slot, source.recipe!!, target.displayName)
                assertEquals(source.recipe, replayed.pad.recipe, "source, answer, chord and note survive replay")
                assertTrue(original.contentEquals(File(dir, replayed.pad.sampleFile).readBytes()), "the saved groove regenerates exactly")
            }
            if (starter.id == "tangle") {
                val model = KitBuilderModel.open(dir)
                assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
                assertEquals(16, model.kit.pads.size)
                val patches = model.kit.pads.map { pad ->
                    val recipe = Breed.recipeOf(pad.recipe)!!
                    val patch = assertIs<TanglePatch>(recipe.patch)
                    assertNull(recipe.fx, "${pad.displayName}: the oscillator relationship is dry")
                    assertEquals(Tangle.drumClassFor(patch.voice, patch.macros), pad.drumClass)
                    assertTrue(pad.oneShot, "${pad.displayName}: the complete finite or settled buffer plays once")
                    patch
                }
                assertEquals(TangleVoice.entries.toSet(), patches.map { it.voice }.toSet())
                assertEquals(listOf(36, 39, 41, 43, 46, 48, 51, 53), patches.take(8).map { it.midi })
                assertTrue(model.kit.pads.take(14).all { it.drumClass == DrumClass.TONAL })
                assertTrue(model.kit.pads.takeLast(2).all { it.drumClass == DrumClass.LOOP })
                val source = model.kit.pads.first()
                val target = model.kit.pads.last()
                val original = File(dir, source.sampleFile).readBytes()
                val replayed = RecipeReplay.apply(model, target.slot, source.recipe!!, target.displayName)
                assertEquals(source.recipe, replayed.pad.recipe, "root and velocity survive replay")
                assertTrue(original.contentEquals(File(dir, replayed.pad.sampleFile).readBytes()), "the saved oscillator system regenerates exactly")
            }
            if (starter.id == "revel") {
                val model = KitBuilderModel.open(dir)
                assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
                assertEquals(16, model.kit.pads.size)
                val patches = model.kit.pads.map { pad ->
                    val recipe = Breed.recipeOf(pad.recipe)!!
                    val patch = assertIs<RevelPatch>(recipe.patch)
                    assertNull(recipe.fx, "${pad.displayName}: the ensemble supplies its own motion")
                    assertEquals(Revel.drumClassFor(patch.voice, patch.macros), pad.drumClass)
                    assertTrue(pad.oneShot)
                    patch
                }
                assertEquals(RevelVoice.entries.toSet(), patches.map { it.voice }.toSet())
                assertEquals(DrumClass.LOOP, model.kit.pads.last().drumClass)
                val source = model.kit.pads.first()
                val target = model.kit.pads.last()
                val original = File(dir, source.sampleFile).readBytes()
                val replayed = RecipeReplay.apply(model, target.slot, source.recipe!!, target.displayName)
                assertEquals(source.recipe, replayed.pad.recipe, "saved phrase and microphone state survive replay")
                assertTrue(original.contentEquals(File(dir, replayed.pad.sampleFile).readBytes()), "the saved ensemble regenerates exactly")
            }
            if (starter.id == "skitter") {
                val model = KitBuilderModel.open(dir)
                assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
                assertEquals(16, model.kit.pads.size)
                val patches = model.kit.pads.map { pad ->
                    val recipe = Breed.recipeOf(pad.recipe)!!
                    val patch = assertIs<SkitterPatch>(recipe.patch)
                    assertNull(recipe.fx, "${pad.displayName}: oscillator recovery and flutter come from the circuit")
                    assertEquals(Skitter.drumClassFor(patch.voice, patch.macros), pad.drumClass)
                    assertTrue(pad.oneShot, "${pad.displayName}: the pad plays its full finite or held buffer")
                    patch
                }
                assertEquals(SkitterVoice.entries.toSet(), patches.map { it.voice }.toSet())
                assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
                assertTrue(model.kit.pads.take(14).all { it.drumClass == DrumClass.TONAL })
                assertTrue(model.kit.pads.takeLast(2).all { it.drumClass == DrumClass.LOOP })
                val source = model.kit.pads.first()
                val target = model.kit.pads.last()
                val original = File(dir, source.sampleFile).readBytes()
                val replayed = RecipeReplay.apply(model, target.slot, source.recipe!!, target.displayName)
                assertEquals(source.recipe, replayed.pad.recipe, "note, excitation velocity and seed survive replay")
                assertTrue(original.contentEquals(File(dir, replayed.pad.sampleFile).readBytes()), "the saved circuit regenerates exactly")
            }
            if (starter.id == "relay") {
                val model = KitBuilderModel.open(dir)
                assertEquals(KeySpec.parse("Cminpent"), model.kit.key)
                assertEquals(16, model.kit.pads.size)
                val patches = model.kit.pads.map { pad ->
                    val recipe = Breed.recipeOf(pad.recipe)!!
                    val patch = assertIs<RelayPatch>(recipe.patch)
                    assertNull(recipe.fx, "${pad.displayName}: Relay's identity audition is dry")
                    assertEquals(Relay.drumClassFor(patch.voice, patch.macros), pad.drumClass)
                    assertTrue(pad.oneShot, "${pad.displayName}: plays its complete finite or repeating region")
                    patch
                }
                assertEquals(RelayVoice.entries.toSet(), patches.map { it.voice }.toSet())
                assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { Relay.midiFor(it.voice, it.macros.getValue("TUNE")) })
                assertTrue(model.kit.pads.take(14).all { it.drumClass == DrumClass.TONAL })
                assertTrue(model.kit.pads.takeLast(2).all { it.drumClass == DrumClass.LOOP })
                val source = model.kit.pads.first()
                val target = model.kit.pads.last()
                val original = File(dir, source.sampleFile).readBytes()
                val replayed = RecipeReplay.apply(model, target.slot, source.recipe!!, target.displayName)
                assertEquals(source.recipe, replayed.pad.recipe, "source definitions and phrase scheduling survive replay")
                assertTrue(original.contentEquals(File(dir, replayed.pad.sampleFile).readBytes()), "the saved phrase regenerates exactly")
            }
            if (starter.id == "osprey") {
                // Not one of oneEngineStarters: every pad sits at C4, so the kit names no key.
                val model = KitBuilderModel.open(dir)
                assertNull(model.kit.key, "OSPREY's pads all sit at C4 and name no key")
                assertEquals(16, model.kit.pads.size)
                val patches = model.kit.pads.map { pad ->
                    val recipe = assertNotNull(Breed.recipeOf(pad.recipe), "${pad.displayName}: no recipe")
                    val patch = assertIs<OspreyPatch>(recipe.patch)
                    assertNull(recipe.fx, "${pad.displayName}: the sea gesture lands dry")
                    assertEquals(Osprey.drumClassFor(patch.voice, patch.macros), pad.drumClass)
                    assertTrue(pad.oneShot, "${pad.displayName}: plays its complete finite gesture")
                    patch
                }
                assertEquals(OspreyVoice.entries.toSet(), patches.map { it.voice }.toSet())
                assertEquals(OspreyPresets.coastWeather(), patches.take(15), "the fifteen shore/sea presets, in roster order")
                assertTrue(patches.all { it.midi == 60 && it.seed == 731 && it.modelVersion == Osprey.MODEL_VERSION }, "rooted at C4, one seed, the current model")
                assertEquals(1f, patches.last().macros.getValue("HOLD"), "the sixteenth pad is the coast with extended excitation")
                // The first pad and the extended last one each regenerate their saved WAV exactly.
                val first = model.kit.pads.first()
                val last = model.kit.pads.last()
                val firstBytes = File(dir, first.sampleFile).readBytes()
                val lastBytes = File(dir, last.sampleFile).readBytes()
                val firstAgain = RecipeReplay.apply(model, last.slot, first.recipe!!, last.displayName)
                assertEquals(first.recipe, firstAgain.pad.recipe, "note, seed, duration and model survive replay")
                assertTrue(firstBytes.contentEquals(File(dir, firstAgain.pad.sampleFile).readBytes()), "the first sea gesture regenerates exactly")
                val lastAgain = RecipeReplay.apply(model, first.slot, last.recipe!!, first.displayName)
                assertEquals(last.recipe, lastAgain.pad.recipe, "the extended coast's recipe survives replay")
                assertTrue(lastBytes.contentEquals(File(dir, lastAgain.pad.sampleFile).readBytes()), "the extended sea gesture regenerates exactly")
            }
            assertEquals(starter, StarterKits.byId(starter.id))
        }
        assertEquals(null, StarterKits.byId("nope"))
    }

    @Test
    fun `seeded starters reroll and are deterministic per seed`() {
        val dip = StarterKits.byId("lucky-dip")!!
        assertTrue(dip.seeded)
        val a = dip.render("Dip A", File(temp, "a"), seed = 7)
        val b = dip.render("Dip A", File(temp, "b"), seed = 7)
        val c = dip.render("Dip A", File(temp, "c"), seed = 8)

        val jsonA = File(temp, "a/kit.json").readText()
        assertEquals(jsonA, File(temp, "b/kit.json").readText(), "same seed, same kit")
        assertNotEquals(jsonA, File(temp, "c/kit.json").readText(), "different seed, different kit")
        assertEquals(a.pads.map { it.slot }, b.pads.map { it.slot })
        assertTrue(a.pads.isNotEmpty() && c.pads.isNotEmpty())

        assertFalse(StarterKits.byId("factory")!!.seeded, "the factory kit never rerolls")
    }

    @Test
    fun `starter IDs are stable, include new synth kits, and blank leads`() {
        assertEquals(
            listOf("blank", "factory", "strike-fx", "lucky-dip", "lucky-dip-ab", "melodic", "chip", "overlap", "tidal", "ohm", "cloud", StarterKits.CONFLUENCE_ID, "circuit", "thaw", "nimbus", "undertow", "suture", "revel", "tessera", "pitchwheel", "prism", "origami", "filament", "chamber", "alloy", "contour", "stoop", "lacquer", "spool", "skitter", "tangle", "caustic", "crease", "lattice", "flock", "relay", "shoal", "embouchure", "bellows", "murmur", "scanned-chain", "trefoil", "trio", "osprey", "osprey-curated", "opensky", "vent", "skin", "cistern", "upright", "aerostat", "tremor", "ballast", "velocity"),
            StarterKits.ALL.map { it.id },
        )
        val chamber = StarterKits.byId("chamber")!!
        assertEquals("CHAMBER", chamber.displayName)
        assertFalse(chamber.seeded, "CHAMBER is a fixed authored kit")
        assertEquals(KeySpec.parse("Cminpent"), chamber.key)
        val cistern = StarterKits.byId("cistern")!!
        assertEquals("CISTERN", cistern.displayName)
        assertFalse(cistern.seeded, "CISTERN is a fixed authored kit")
        assertEquals(KeySpec.parse("Cminpent"), cistern.key)
        val tessera = StarterKits.byId("tessera")!!
        assertEquals("TESSERA", tessera.displayName)
        assertFalse(tessera.seeded, "TESSERA is a fixed authored kit")
        assertEquals(KeySpec.parse("Cminpent"), tessera.key)
        val pitchwheel = StarterKits.byId("pitchwheel")!!
        assertEquals("PITCHWHEEL", pitchwheel.displayName)
        assertFalse(pitchwheel.seeded, "PITCHWHEEL is a fixed dry audition kit")
        assertEquals(KeySpec.parse("Cminpent"), pitchwheel.key)
        val stoop = StarterKits.byId("stoop")!!
        assertEquals("STOOP", stoop.displayName)
        assertFalse(stoop.seeded, "STOOP is a fixed dry groove kit")
        assertEquals(KeySpec.parse("Cminpent"), stoop.key)
        val lacquer = StarterKits.byId("lacquer")!!
        assertEquals("LACQUER", lacquer.displayName)
        assertFalse(lacquer.seeded, "LACQUER is a fixed dry audition kit")
        assertEquals(KeySpec.parse("Cminpent"), lacquer.key)
        val tangle = StarterKits.byId("tangle")!!
        assertEquals("TANGLE", tangle.displayName)
        assertFalse(tangle.seeded, "TANGLE is a fixed dry audition kit")
        assertEquals(KeySpec.parse("Cminpent"), tangle.key)
        val flock = StarterKits.byId("flock")!!
        assertEquals("FLOCK", flock.displayName)
        assertFalse(flock.seeded, "FLOCK repeats its saved finite performances")
        assertEquals(KeySpec.parse("Cminpent"), flock.key)
        val embouchure = StarterKits.byId("embouchure")!!
        assertEquals("EMBOUCHURE", embouchure.displayName)
        assertFalse(embouchure.seeded, "EMBOUCHURE is a fixed dry audition kit")
        assertEquals(KeySpec.parse("Cminpent"), embouchure.key)
        val murmur = StarterKits.byId("murmur")!!
        assertEquals("MURMUR", murmur.displayName)
        assertFalse(murmur.seeded, "MURMUR is a fixed dry audition kit")
        assertEquals(KeySpec.parse("Cminpent"), murmur.key)
        val osprey = StarterKits.byId("osprey")!!
        assertEquals("OSPREY", osprey.displayName)
        assertFalse(osprey.seeded, "OSPREY is a fixed dry audition kit")
        assertNull(osprey.key, "OSPREY's sea gestures all sit at C4 and name no key")
        val ospreyCurated = StarterKits.byId("osprey-curated")!!
        assertEquals("OSPREY CURATED", ospreyCurated.displayName)
        assertFalse(ospreyCurated.seeded, "OSPREY CURATED repeats its authored finite gestures")
        assertEquals(KeySpec.parse("Cminpent"), ospreyCurated.key)
        val revel = StarterKits.byId("revel")!!
        assertEquals("REVEL", revel.displayName)
        assertFalse(revel.seeded, "REVEL is a fixed dry ensemble kit")
        assertEquals(KeySpec.parse("Cminpent"), revel.key)
        val spool = StarterKits.byId("spool")!!
        assertEquals("SPOOL", spool.displayName)
        assertFalse(spool.seeded, "SPOOL is a fixed dry recording kit")
        assertEquals(KeySpec.parse("Cminpent"), spool.key)
        val prism = StarterKits.byId("prism")!!
        assertEquals("PRISM", prism.displayName)
        assertFalse(prism.seeded, "PRISM is a fixed dry audition kit")
        assertEquals(KeySpec.parse("Cminpent"), prism.key)
        val alloy = StarterKits.byId("alloy")!!
        assertEquals("ALLOY", alloy.displayName)
        assertFalse(alloy.seeded, "ALLOY is a fixed dry audition kit")
        assertEquals(KeySpec.parse("Cminpent"), alloy.key)
        val contour = StarterKits.byId("contour")!!
        assertEquals("CONTOUR", contour.displayName)
        assertFalse(contour.seeded, "CONTOUR is a fixed dry audition kit")
        assertEquals(KeySpec.parse("Cminpent"), contour.key)
        val skitter = StarterKits.byId("skitter")!!
        assertEquals("SKITTER", skitter.displayName)
        assertFalse(skitter.seeded, "SKITTER is a fixed dry circuit kit")
        assertEquals(KeySpec.parse("Cminpent"), skitter.key)
        val relay = StarterKits.byId("relay")!!
        assertEquals("RELAY", relay.displayName)
        assertFalse(relay.seeded, "RELAY is a fixed dry audition kit")
        assertEquals(KeySpec.parse("Cminpent"), relay.key)
    }

    @Test
    fun `the velocity starter gives every pad soft layers`() {
        val dir = kotlin.io.path.createTempDirectory("velocity").toFile()
        try {
            val kit = StarterKits.byId("velocity")!!.render("VELOCITY", dir)
            val assigned = kit.pads.filter { it.sampleFile.isNotBlank() }
            assertTrue(assigned.isNotEmpty(), "the starter rendered an empty kit")
            for (pad in assigned) {
                assertEquals(
                    3, pad.velocityLayers.size,
                    "${pad.displayName}: two soft zones plus the main sample on top",
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the velocity starter genuinely re-renders soft layers, not the same take low-passed`() {
        val dir = kotlin.io.path.createTempDirectory("velocity-rerender").toFile()
        try {
            val kit = StarterKits.byId("velocity")!!.render("VELOCITY RERENDER", dir)
            // Factory Snare (A02, ThumpVoice.SNARE) exposes SNAP - one of
            // Velocity.BRIGHTNESS_MACROS' matches, and (since the membrane
            // rebuild) SNARE's own voice-scoped replacement for TONE - so
            // its soft layers must be genuinely re-rendered, the same proof
            // KitBuilderTest and RobinTest use at their own doors. Read the
            // patch back off the kit's own stored recipe rather than
            // reconstructing ThumpKits' internals by hand.
            val pad = kit.pad(2)!!
            val patch = Breed.recipeOf(pad.recipe)!!.patch!!
            val main = com.snipsnap.audio.WavReader.read(File(dir, pad.sampleFile))
            val soft = com.snipsnap.audio.WavReader.read(File(dir, pad.velocityLayers[0].sampleFile))

            // The exact call Velocity.variantsAt makes for the softest of
            // two variants (i=0, count=2).
            val expected = com.snipsnap.synth.Velocity.variantsAt(main, patch, null, count = 2)[0]
            assertEquals(expected.samples.size, soft.samples.size, "a re-render has its own length")
            for (i in soft.samples.indices) {
                assertTrue(
                    kotlin.math.abs(soft.samples[i] - expected.samples[i]) <= 2f / 32767f,
                    "sample $i: starter soft ${soft.samples[i]} vs variantsAt ${expected.samples[i]}",
                )
            }
            // Peak-matched to the main take (Velocity.layerAt's own
            // contract) - "timbre only, level is the hardware's job" as a
            // measurement, not a restatement of the formula above.
            fun peak(x: FloatArray) = x.maxOf { kotlin.math.abs(it) }
            assertTrue(
                kotlin.math.abs(peak(soft.samples) - peak(main.samples)) <= 2f / 32767f,
                "the soft layer should be peak-matched to the main take",
            )
            // And it must differ from what the old soften()-only path would
            // have given at the same depth - proof the onset actually moved.
            val viaSoften = com.snipsnap.synth.Velocity.soften(main, 2f / 3f)
            val n = minOf(soft.samples.size, viaSoften.samples.size, 400)
            val differing = (0 until n).count { kotlin.math.abs(soft.samples[it] - viaSoften.samples[it]) > 1e-3f }
            assertTrue(differing > 50, "the starter's soft layer should differ from soften()'s; only $differing did")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the A-B starter fills bank B and melodic remembers its key`() {
        val ab = StarterKits.byId("lucky-dip-ab")!!.render("DipAB", File(temp, "ab"), seed = 3)
        assertTrue(ab.pads.any { it.slot > 16 }, "bank B should be populated")

        val melodic = StarterKits.byId("melodic")!!.render("Melodic", File(temp, "mel"), seed = 0)
        assertEquals(KeySpec.parse("Aminpent"), melodic.key, "melodic content declares its key")
    }

    @Test
    fun `melodic pads gate while factory pads stay one-shot`() {
        val melodic = StarterKits.byId("melodic")!!.render("Melodic Gate", File(temp, "mel-gate"), seed = 0)
        assertTrue(melodic.pads.isNotEmpty(), "melodic should have pads")
        for (pad in melodic.pads) {
            assertFalse(pad.oneShot, "${pad.displayName}: melodic pads should sustain while held")
        }

        val factory = StarterKits.byId("factory")!!.render("Factory OneShot", File(temp, "factory-oneshot"), seed = 0)
        assertTrue(factory.pads.isNotEmpty(), "factory should have pads")
        for (pad in factory.pads) {
            assertTrue(pad.oneShot, "${pad.displayName}: factory pads should remain one-shot")
        }
    }
}
