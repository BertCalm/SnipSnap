package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Tidal
import com.snipsnap.synth.TidalPatch
import com.snipsnap.synth.TidalPresets
import com.snipsnap.synth.TidalVoice
import com.snipsnap.xpm.WavInfo
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The finite instrument's phone-independent save, replay, spread and export doors. */
class TidalRecallTest {
    @Test
    fun `breeding crosses timbre while keeping the source playback choice and exact performance`() {
        var timbreCrossed = false
        for (hold in listOf(0f, 1f)) for (seed in 0L..31L) {
            val a = TidalPatch("A", TidalVoice.PAIR, mapOf("HOLD" to hold, "PASSAGE" to .2f),
                midi = 57, velocity = .42f, seed = Long.MAX_VALUE, model = 1)
            val b = TidalPatch("B", TidalVoice.PAIR, mapOf("HOLD" to (1f - hold), "PASSAGE" to .8f),
                midi = 72, velocity = .9f, seed = Long.MIN_VALUE, model = 2)
            val child = Breed.cross(PadRecipe(a), PadRecipe(b), java.util.Random(seed)).patch as TidalPatch
            if (child.macros["PASSAGE"] != a.macros["PASSAGE"]) timbreCrossed = true
            assertEquals(hold, child.macros["HOLD"])
            assertEquals(a.midi, child.midi)
            assertEquals(a.velocity, child.velocity)
            assertEquals(a.seed, child.seed)
            assertEquals(a.model, child.model)
            assertEquals(child, TidalPatch.fromJsonText(child.toJsonText()))
        }
        assertTrue(timbreCrossed, "the playback guard must still allow TIDAL timbre crossover")
    }

    private fun authored(model: Int) = TidalPatch(
        "MY \"A\\ \$",
        TidalVoice.STREAM,
        Tidal.defaults(TidalVoice.STREAM, model) + mapOf("APPROACH" to .62f, "BIND" to .38f, "TAIL" to .73f),
        midi = 55, velocity = .37f, seed = Long.MIN_VALUE + 9_007_199_254_740_993L, model = model,
    )

    @Test
    fun `saved encounter and promoted constructor retain both models and exact performance`() {
        val root = kotlin.io.path.createTempDirectory("tidal-presets-recall").toFile()
        try {
            for (model in listOf(1, 2)) {
                val source = authored(model)
                val shelf = File(root, model.toString())
                val saved = UserPresets.save(shelf, source, nowMillis = 1_700_000_000_000L + model)
                val reopened = UserPresets.read(shelf).single()
                assertEquals(saved, reopened)
                val recalled = assertIs<TidalPatch>(reopened.patch)
                assertEquals(source, recalled, "reopening retains note, velocity, full signed seed, controls and renderer")
                assertContentEquals(source.render().samples, recalled.render().samples)
                assertEquals("TidalPresets.kt", UserPresets.rosterFile(source.engine))
                assertEquals("TidalVoice", UserPresets.voiceEnum(source.engine))

                val line = UserPresets.rosterLine(recalled)
                val prefix = "TidalPatch.fromJsonText("
                assertTrue(line.startsWith(prefix) && line.endsWith("),"), line)
                // Promotion escapes Kotlin interpolation in addition to JSON string syntax.
                // Remove only that extra escape, then parse the literal and complete recipe.
                val literal = line.removePrefix(prefix).removeSuffix("),").replace("\\$", "$")
                val promoted = TidalPatch.fromJsonText(Json.parse(literal).str())
                assertEquals(source, promoted, "a copied roster line must recreate the full authored recipe")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `landed long encounter stays finite through recipe replay restart and authored pitch spread`() {
        val root = kotlin.io.path.createTempDirectory("tidal-pad-recall").toFile()
        try {
            val source = authored(2)
            val recipe = PadRecipe(source)
            val kitDir = File(root, "kit")
            KitBuilderModel.fromChop("Tidal Recall", listOf(ArrangedPad(
                recipe.render(), DrumClass.LOOP, recipe.toJsonValue(), displayName = source.name,
            )), kitDir)
            val model = KitBuilderModel.open(kitDir)
            val first = assertNotNull(model.pad(1))
            assertTrue(first.oneShot, "duration filing must not turn a finite encounter into held playback")
            assertEquals(DrumClass.LOOP, first.drumClass)
            val savedRecipe = assertNotNull(first.recipe)
            val restored = assertIs<TidalPatch>(PadRecipe.fromJsonValue(savedRecipe).patch)
            assertEquals(source, restored)
            assertIs<RecipeReplay.Plan.Patch>(RecipeReplay.plan(savedRecipe))
            val wavInfo = WavInfo.read(File(kitDir, first.sampleFile))
            assertEquals(9.8, wavInfo.frameCount.toDouble() / wavInfo.sampleRate, .00001)

            // The recipe is the authority even when the detector sees no pitched audio.
            assertEquals(source.midi.toFloat(), Spread.detect(Snip(FloatArray(0), 1, 44_100), restored))

            model.assign(2, DrumSynth.snare(), DrumClass.SNARE)
            val copied = RecipeReplay.apply(model, 2, savedRecipe, "SNARE 02")
            assertTrue(copied.pad.oneShot)
            assertEquals(savedRecipe, copied.pad.recipe)
            assertContentEquals(File(kitDir, first.sampleFile).readBytes(),
                File(kitDir, copied.pad.sampleFile).readBytes(), "replay preserves the full finite encounter WAV")
            model.save(accrueWear = false)
            val restarted = KitBuilderModel.open(kitDir)
            val pad = assertNotNull(restarted.pad(2))
            assertTrue(pad.oneShot)
            assertEquals(source, PadRecipe.fromJsonValue(assertNotNull(pad.recipe)).patch)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sixteen pad starter retains dry editable encounters and exports unlooped MPC programs`() {
        val root = kotlin.io.path.createTempDirectory("tidal-starter-export").toFile()
        try {
            val starter = assertNotNull(StarterKits.byId("tidal"))
            assertFalse(starter.seeded)
            val kitDir = File(root, "kit")
            starter.render("Tidal Starter", kitDir)
            val model = KitBuilderModel.open(kitDir)
            model.save(accrueWear = false)
            val kit = KitStore.load(kitDir)
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertEquals(KeySpec.parse("Cminpent"), kit.key)
            assertFalse(Preflight.check(kit, kitDir).blocked())
            val patches = kit.pads.map { pad ->
                assertTrue(pad.oneShot)
                assertEquals(DrumClass.LOOP, pad.drumClass)
                assertNull(pad.chain)
                assertTrue(pad.velocityLayers.isEmpty())
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                assertNull(recipe.fx)
                assertFalse(recipe.alias)
                val patch = assertIs<TidalPatch>(recipe.patch)
                assertEquals(2, patch.model)
                assertEquals(0f, Tidal.settled(patch.voice, patch.macros, patch.model).getValue("HOLD"))
                patch
            }
            assertEquals(TidalVoice.entries.toSet(), patches.map { it.voice }.toSet())
            assertTrue(patches.containsAll(TidalPresets.all()))

            val output = Mpc3Exporter.exportTrack(kit, kitDir, File(root, "export"), mpc2Twin = true)
            assertEquals(16, output.samples.size)
            val data = output.samples.first().parentFile
            val xpm = File(data, kit.name + ".xpm")
            assertTrue(xpm.isFile)
            val native = Mpc3Project.read(output.program)
            assertTrue(native.isTrack)
            val instruments = (native.drumPrograms().single().getValue("instruments") as JsonValue.Arr).items
            val xmlFactory = DocumentBuilderFactory.newInstance()
            xmlFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val xml = xmlFactory.newDocumentBuilder().parse(xpm)
            val xmlInstruments = xml.getElementsByTagName("Instrument")
            val exportedKit = KitStore.load(data)

            for ((index, pad) in kit.pads.withIndex()) {
                assertContentEquals(File(kitDir, pad.sampleFile).readBytes(),
                    File(data, pad.sampleFile).readBytes(), "export copies native rendered WAV bytes")
                val saved = exportedKit.pads.single { it.slot == pad.slot }
                assertEquals(pad.recipe, saved.recipe)
                assertTrue(saved.oneShot)
                val instrument = instruments[index].obj()
                assertEquals(0, instrument.getValue("triggerMode").int(), "MPC 3 must play the complete finite buffer once")
                val layer = (instrument.getValue("layersv") as JsonValue.Arr).items.first().obj()
                assertEquals(JsonValue.Bool(false), layer.getValue("loop"))
                val slice = layer.getValue("sliceInfo").obj()
                assertEquals(0, slice.getValue("LoopMode").int())
                assertEquals(0, slice.getValue("LoopStart").int())
                val xmlInstrument = xmlInstruments.item(index) as Element
                fun text(tag: String) = xmlInstrument.getElementsByTagName(tag).item(0).textContent
                assertEquals("True", text("OneShot"), "MPC 2 must play the complete finite buffer once")
                assertEquals("0", text("SliceLoop"))
                assertEquals("0", text("SliceLoopStart"))
            }
            val readBack = listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK, output.program, data, output.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, xpm, null, output.findings),
            ).flatMap { ExportReadBack.verify(kit, it) }
            assertFalse(readBack.blocked(), readBack.toString())
        } finally {
            root.deleteRecursively()
        }
    }
}
