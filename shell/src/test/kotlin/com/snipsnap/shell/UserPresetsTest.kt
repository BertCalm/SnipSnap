package com.snipsnap.shell

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.json.Json
import com.snipsnap.synth.CisternPatch
import com.snipsnap.synth.CisternVoice
import com.snipsnap.synth.ContourConfig
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.ContourRelation
import com.snipsnap.synth.ContourTable
import com.snipsnap.synth.ContourVoice
import com.snipsnap.synth.EmbouchureVoice
import com.snipsnap.synth.BellowsVoice
import com.snipsnap.synth.FathomVoice
import com.snipsnap.synth.FlotillaPatch
import com.snipsnap.synth.FlotillaVoice
import com.snipsnap.synth.Lacquer
import com.snipsnap.synth.LacquerPatch
import com.snipsnap.synth.LacquerRole
import com.snipsnap.synth.LacquerVoice
import com.snipsnap.synth.Murmur
import com.snipsnap.synth.MurmurConfig
import com.snipsnap.synth.MurmurFormantTarget
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.MurmurPresets
import com.snipsnap.synth.MurmurRouting
import com.snipsnap.synth.MurmurTrajectory
import com.snipsnap.synth.MurmurVoice
import com.snipsnap.synth.OpenSkyPatch
import com.snipsnap.synth.OhmGesture
import com.snipsnap.synth.OhmPatch
import com.snipsnap.synth.OhmVoice
import com.snipsnap.synth.OspreyVoice
import com.snipsnap.synth.PluckVoice
import com.snipsnap.synth.PitchwheelPatch
import com.snipsnap.synth.PitchwheelVoice
import com.snipsnap.synth.SkitterPatch
import com.snipsnap.synth.SkitterVoice
import com.snipsnap.synth.Presets
import com.snipsnap.synth.PrismVoice
import com.snipsnap.synth.StoopVoice
import com.snipsnap.synth.StoopPatch
import com.snipsnap.synth.ResinVoice
import com.snipsnap.synth.Relay
import com.snipsnap.synth.RelayAccent
import com.snipsnap.synth.RelayLoop
import com.snipsnap.synth.RelayPatch
import com.snipsnap.synth.RelayVoice
import com.snipsnap.synth.RevelVoice
import com.snipsnap.synth.TideVoice
import com.snipsnap.synth.TanglePatch
import com.snipsnap.synth.TangleVoice
import com.snipsnap.synth.SkinVoice
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpPatch
import com.snipsnap.synth.ThumpVoice
import com.snipsnap.synth.TinesVoice
import com.snipsnap.synth.TonewheelVoice
import com.snipsnap.synth.VelvetVoice
import com.snipsnap.synth.VoxVoice
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SAVE AS PRESET (`docs/WORKSHOP.md`, WS5): the player's own presets beside the kits, and the line that promotes one. */
class UserPresetsTest {

    private fun shelf(): File = java.nio.file.Files.createTempDirectory("presets").toFile()

    /** A kick the player wrecked: SCRAMBLE's own odd floats, nothing a table would round. */
    private val wrecked = ThumpPatch("MY KICK", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK) + Thump.scramble(ThumpVoice.KICK, Random(7)))
    private val snare = ThumpPatch("MY KICK", ThumpVoice.SNARE, Thump.defaults(ThumpVoice.SNARE))

    @Test
    fun `saved OHM encounters keep model seed and gesture on the shelf and in promotion`() {
        val root = shelf()
        try {
            for (model in listOf(1, 2)) {
                val source = OhmPatch("MY FAMILY", linkedMapOf("TOUCH" to .27f, "COMMUNE" to .73f),
                    midi = 57, velocity = .35f, seed = -905, gesture = OhmGesture.GATHER, model = model)
                UserPresets.save(root, source, nowMillis = model.toLong())
                val back = UserPresets.read(root).single()
                assertEquals(source, back.patch, "a restart retains the full encounter")
                val line = UserPresets.rosterLine(back.patch)
                assertEquals("p(OhmVoice.STRING, \"MY FAMILY\", \"TOUCH\" to 0.27f, \"COMMUNE\" to 0.73f)" +
                    ".copy(midi = 57, velocity = 0.35f, seed = -905, gesture = OhmGesture.GATHER, model = $model),", line)
                assertEquals("OhmPresets.kt\n  $line\n", UserPresets.renderAll(listOf(back)))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `OHM factory names reserve only their own voice shelf`() {
        assertEquals(UserPresets.Check.Factory("GATHER"), UserPresets.check(" gather ", "OHM", "STRING", emptyList()))
        assertEquals(UserPresets.Check.Fresh("MY FAMILY"), UserPresets.check("my family", "OHM", "STRING", emptyList()))
    }

    @Test
    fun `a saved preset survives a restart and re-renders the same bytes`() {
        val root = shelf()
        try {
            assertEquals(emptyList(), UserPresets.read(root), "no file, no presets")
            val saved = UserPresets.save(root, wrecked, nowMillis = 1_700_000_000_000L)
            assertEquals(wrecked, saved.patch)
            // A fresh read is the restart: nothing but the file.
            val back = UserPresets.read(root)
            assertEquals(listOf(saved), back)
            assertEquals(wrecked, back.single().patch, "every macro's exact value, the name and the voice")
            assertTrue(wrecked.render().samples.contentEquals(back.single().patch.render().samples), "the same bytes the phone heard")
            assertEquals(File(root, "presets.json"), UserPresets.file(root), "one file at the shelf root, beside the kit folders")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the name rules - blank, too long, the factory's, fresh, or one of yours`() {
        val mine = listOf(UserPresets.Saved(wrecked, 1L))
        assertEquals(UserPresets.Check.Blank, UserPresets.check("  \n ", "THUMP", "KICK", mine))
        assertEquals(UserPresets.Check.TooLong("FIFTEEN LETTERSX"), UserPresets.check("fifteen lettersx", "THUMP", "KICK", mine))
        assertEquals(UserPresets.Check.Factory("DUSTY BOOM"), UserPresets.check(" dusty   boom ", "THUMP", "KICK", mine), "a factory name is refused, whatever the case or spacing")
        assertEquals(UserPresets.Check.Fresh("DUSTY BOOM"), UserPresets.check("dusty boom", "THUMP", "SNARE", mine), "the factory's names are per voice")
        assertEquals(UserPresets.Check.Replaces("MY KICK"), UserPresets.check("my kick", "THUMP", "KICK", mine))
        assertEquals(UserPresets.Check.Fresh("MY KICK"), UserPresets.check("my kick", "THUMP", "SNARE", mine), "yours are per voice too")
        assertEquals("HAT CLOSED 2", UserPresets.normalize("  hat\tclosed   2 "))
    }

    @Test
    fun `title-case cistern factory names reserve their normalized saved names`() {
        val factory = Presets.byName("CISTERN", "FIRST", "First Drop") as CisternPatch
        assertEquals(null, Presets.byName("CISTERN", "FIRST", "FIRST DROP"), "factory lookup keeps its exact-label contract")
        for (raw in listOf("First Drop", "FIRST DROP", " first\t drop ")) {
            assertEquals(UserPresets.Check.Factory("FIRST DROP"), UserPresets.check(raw, "CISTERN", "FIRST", emptyList()))
        }
        assertEquals(UserPresets.Check.Fresh("FIRST DROP"), UserPresets.check("First Drop", "CISTERN", "DRIP", emptyList()), "factory names remain per voice")

        val root = shelf()
        try {
            val own = UserPresets.save(root, factory.copy(name = "MY SURFACE"), 1L)
            val before = UserPresets.file(root).readText()
            val refusal = assertFailsWith<IllegalArgumentException> {
                UserPresets.save(root, factory.copy(name = "FIRST DROP"), 2L)
            }
            assertTrue("the factory already has FIRST DROP" in refusal.message.orEmpty(), "the normalized factory collision is the refusal")
            assertEquals(before, UserPresets.file(root).readText(), "a refused duplicate leaves the existing store unchanged")
            assertEquals(listOf(own), UserPresets.read(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `saving under one of your names replaces it in place, and never touches another voice's namesake`() {
        val root = shelf()
        try {
            UserPresets.save(root, wrecked, 1L)
            UserPresets.save(root, snare, 2L)
            val rewrecked = ThumpPatch("MY KICK", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK) + Thump.scramble(ThumpVoice.KICK, Random(8)))
            val replaced = UserPresets.save(root, rewrecked, 3L)
            val all = UserPresets.read(root)
            assertEquals(listOf(replaced, UserPresets.Saved(snare, 2L)), all, "the kick keeps its place in the list; the snare is untouched")
            assertEquals(rewrecked, all.first().patch)
            assertEquals(listOf(replaced), UserPresets.forVoice(all, "THUMP", "KICK"))
            assertEquals(listOf(UserPresets.Saved(snare, 2L)), UserPresets.forVoice(all, "THUMP", "SNARE"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `save refuses a name check would refuse`() {
        val root = shelf()
        try {
            assertTrue(runCatching { UserPresets.save(root, ThumpPatch("dusty boom", ThumpVoice.KICK, emptyMap()), 1L) }.isFailure, "not normalized")
            assertTrue(runCatching { UserPresets.save(root, ThumpPatch("DUSTY BOOM", ThumpVoice.KICK, emptyMap()), 1L) }.isFailure, "the factory's")
            assertTrue(runCatching { UserPresets.save(root, ThumpPatch("FIFTEEN LETTERSX", ThumpVoice.KICK, emptyMap()), 1L) }.isFailure, "too long")
            assertFalse(UserPresets.file(root).exists(), "a refusal writes nothing")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the suggested name is the voice numbered from 1, skipping what is taken, and always fits`() {
        val root = shelf()
        try {
            assertEquals("KICK 1", UserPresets.suggest("THUMP", "KICK", emptyList()))
            assertEquals("HAT CLOSED 1", UserPresets.suggest("THUMP", "HAT_CLOSED", emptyList()))
            val one = UserPresets.save(root, ThumpPatch("KICK 1", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK)), 1L)
            assertEquals("KICK 2", UserPresets.suggest("THUMP", "KICK", listOf(one)))
            assertEquals("SNARE 1", UserPresets.suggest("THUMP", "SNARE", listOf(one)), "numbered per voice")
            // Every voice of every engine, at a number a phone will never reach.
            val voices = listOf(
                "THUMP" to ThumpVoice.entries.map { it.name }, "SKIN" to SkinVoice.entries.map { it.name },
                "TINES" to TinesVoice.entries.map { it.name }, "VELVET" to VelvetVoice.entries.map { it.name },
                "VOX" to VoxVoice.entries.map { it.name }, "PLUCK" to PluckVoice.entries.map { it.name },
                "TONEWHEEL" to TonewheelVoice.entries.map { it.name }, "FATHOM" to FathomVoice.entries.map { it.name },
                "RESIN" to ResinVoice.entries.map { it.name }, "TIDE" to TideVoice.entries.map { it.name },
                "CISTERN" to CisternVoice.entries.map { it.name },
                "PITCHWHEEL" to PitchwheelVoice.entries.map { it.name },
                "REVEL" to RevelVoice.entries.map { it.name },
                "PRISM" to PrismVoice.entries.map { it.name },
                "CONTOUR" to ContourVoice.entries.map { it.name },
                "STOOP" to StoopVoice.entries.map { it.name },
                "SKITTER" to SkitterVoice.entries.map { it.name },
                "TANGLE" to TangleVoice.entries.map { it.name },
                "RELAY" to RelayVoice.entries.map { it.name },
                "EMBOUCHURE" to EmbouchureVoice.entries.map { it.name },
                "BELLOWS" to BellowsVoice.entries.map { it.name },
                "MURMUR" to MurmurVoice.entries.map { it.name },
                "OSPREY" to OspreyVoice.entries.map { it.name },
                "OHM" to OhmVoice.entries.map { it.name },
            )
            for ((engine, names) in voices) {
                for (v in names) {
                    val suggestion = UserPresets.suggest(engine, v, emptyList())
                    assertTrue(suggestion.length + 2 <= UserPresets.MAX_NAME, "$engine $v: '$suggestion' leaves no room for a two-digit number")
                    assertEquals(UserPresets.Check.Fresh(suggestion), UserPresets.check(suggestion, engine, v, emptyList()))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a file this build cannot read is never written over, and an entry it cannot read rides through a save`() {
        val root = shelf()
        try {
            val file = UserPresets.file(root)
            file.writeText("{\"version\": 1, \"presets\": [{\"savedAt\": 5, \"patch\": {\"engine\": \"FUTURE\", \"version\": 1, \"name\": \"X\", \"voice\": \"Y\", \"macros\": {}}}]}")
            assertEquals(emptyList(), UserPresets.read(root), "an engine this build does not know is skipped, not fatal")
            UserPresets.save(root, wrecked, 9L)
            assertEquals(listOf(UserPresets.Saved(wrecked, 9L)), UserPresets.read(root))
            assertTrue("\"FUTURE\"" in file.readText(), "the unread entry is still in the file for the build that can read it")
            file.writeText("not json at all")
            assertTrue(runCatching { UserPresets.read(root) }.exceptionOrNull() is JsonException, "a file that is not this store's throws")
            assertTrue(runCatching { UserPresets.save(root, wrecked, 10L) }.isFailure, "and a save refuses rather than writing over it")
            assertEquals("not json at all", file.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a roster line pastes into the engine's own table with every macro's exact value`() {
        val line = UserPresets.rosterLine(ThumpPatch("MY KICK", ThumpVoice.KICK, linkedMapOf("TUNE" to 0.34f, "SWEEP" to 0.45f)))
        assertEquals("p(ThumpVoice.KICK, \"MY KICK\", \"TUNE\" to 0.34f, \"SWEEP\" to 0.45f),", line)
        // Every factory preset, back out of its own line to the float it holds.
        val value = Regex("\"[^\"]+\" to ([-0-9.E]+)f")
        val stoopValue = Regex("\\b(touch|color|shadow|wear|length|hold) = ([-0-9.E]+)f")
        for (preset in Presets.all()) {
            val line = UserPresets.rosterLine(preset)
            if (preset is OpenSkyPatch) {
                val promoted = openSkyFromRosterLine(line)
                assertEquals(preset, promoted, "${preset.name}: complete source, articulation and render-state promotion")
                assertEquals(preset.macros.mapValues { it.value.toRawBits() }, promoted.macros.mapValues { it.value.toRawBits() },
                    "${preset.engine}/${preset.name}: the JSON constructor rounds no macro")
            } else if (preset is LacquerPatch) {
                assertEquals(preset, lacquerFromRosterLine(line), "${preset.name}: complete profile promotion")
            } else if (preset is StoopPatch) {
                val parsed = stoopValue.findAll(line).associate { it.groupValues[1].uppercase() to it.groupValues[2].toFloat() }
                assertEquals(preset.macros, parsed, "${preset.engine}/${preset.name}: the named macro arguments round a value")
            } else {
                val parsed = value.findAll(line).map { it.groupValues[1].toFloat() }.toList()
                assertEquals(preset.macros.values.toList(), parsed, "${preset.engine}/${preset.name}: the line rounds a value")
            }
        }
        val quoted = UserPresets.rosterLine(ThumpPatch("SAY \"HI\" \$X", ThumpVoice.KICK, emptyMap()))
        assertTrue("\"SAY \\\"HI\\\" \\\$X\"" in quoted, quoted)
        val two = listOf(UserPresets.Saved(wrecked, 1L), UserPresets.Saved(snare, 2L))
        val rendered = UserPresets.renderAll(two)
        assertTrue(rendered.startsWith("ThumpPresets.kt\n  p(ThumpVoice.KICK, \"MY KICK\", "), rendered)
        assertTrue("\n  p(ThumpVoice.SNARE, \"MY KICK\", " in rendered, rendered)
    }

    @Test
    fun `a saved cistern roster line retains a custom note and strike velocity`() {
        val root = shelf()
        try {
            val source = CisternPatch(
                "LOW SURFACE", CisternVoice.POOL,
                linkedMapOf("DROP" to 0.73f, "DRAIN" to 0.21f),
                midi = 36, velocity = 0.3f,
            )
            UserPresets.save(root, source, 1L)
            val saved = UserPresets.read(root).single()
            assertEquals(source, saved.patch, "the shelf retains state outside the macros")
            val line = UserPresets.rosterLine(saved.patch)
            assertEquals(
                "p(CisternVoice.POOL, \"LOW SURFACE\", \"DROP\" to 0.73f, \"DRAIN\" to 0.21f).copy(midi = 36, velocity = 0.3f),",
                line,
                "the pasteable factory line reconstructs the saved sound, including its non-default note and energy",
            )
            assertEquals("CisternPresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a saved tangle retains requested root and playing velocity when promoted`() {
        val root = shelf()
        try {
            val source = TanglePatch(
                "LOW TANGLE", TangleVoice.HOLLOW,
                linkedMapOf("CATCH" to .73f, "CHASE" to .21f), midi = 31, velocity = .3f,
            )
            UserPresets.save(root, source, 1L)
            val saved = UserPresets.read(root).single()
            assertEquals(source, saved.patch, "the shelf retains state outside the six macros")
            val line = UserPresets.rosterLine(saved.patch)
            assertEquals(
                "p(TangleVoice.HOLLOW, \"LOW TANGLE\", \"CATCH\" to 0.73f, \"CHASE\" to 0.21f).copy(midi = 31, velocity = 0.3f),",
                line,
            )
            assertEquals("TanglePresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
            assertTrue(source.render().samples.contentEquals(saved.patch.render().samples), "saved pitch and energy reproduce the same sound")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a saved wheel retains root gesture velocity and model when promoted`() {
        val root = shelf()
        try {
            for (model in listOf(1, 2)) {
                val source = PitchwheelPatch(
                    "LOW WHEEL", PitchwheelVoice.RECOIL,
                    linkedMapOf("ADHESION" to .83f, "HEAT" to .21f), midi = 36, velocity = .3f, model = model,
                )
                UserPresets.save(root, source, 1L)
                val saved = UserPresets.read(root).single()
                assertEquals(source, saved.patch)
                val line = UserPresets.rosterLine(saved.patch)
                assertEquals(
                    "p(PitchwheelVoice.RECOIL, \"LOW WHEEL\", \"ADHESION\" to 0.83f, \"HEAT\" to 0.21f).copy(midi = 36, velocity = 0.3f, model = $model),",
                    line,
                )
                assertEquals("PitchwheelPresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a saved contour retains its complete spectral recipe when promoted`() {
        val root = shelf()
        try {
            val source = ContourPatch(
                "MY CORNER", ContourVoice.BASS,
                linkedMapOf("TUNE" to .5f, "SCAN" to .30f, "OFFSET" to .45f, "MOTION" to .48f,
                    "FILTER" to .58f, "DRIVE" to .20f, "HOLD" to 0f),
                config = ContourConfig.legacyForVoice(ContourVoice.BASS).copy(
                    bodyTable = ContourTable.SOFT_SAW, edgeTable = ContourTable.COPPER,
                    relation = ContourRelation.OPPOSED, bodyTravel = .22f, edgeTravel = .60f,
                    ampDecay = .45f, ampRelease = .42f,
                ),
                velocity = .3f,
            )
            UserPresets.save(root, source, 1L)
            val saved = UserPresets.read(root).single()
            assertEquals(source, saved.patch)
            val patch = saved.patch as ContourPatch
            val line = UserPresets.rosterLine(patch)
            assertTrue("bodyTable = ContourTable.SOFT_SAW" in line)
            assertTrue("edgeTable = ContourTable.COPPER" in line)
            assertTrue("relation = ContourRelation.OPPOSED" in line)
            assertTrue("ampDecay = 0.45f" in line)
            assertTrue("bodyHarmonics = ${patch.config.bodyHarmonics}f" in line)
            assertTrue("edgeRootLevel = ${patch.config.edgeRootLevel}f" in line)
            assertTrue("edgeAttack = ${patch.config.edgeAttack}f" in line)
            assertTrue("edgeDecay = ${patch.config.edgeDecay}f" in line)
            assertTrue("edgeSustain = ${patch.config.edgeSustain}f" in line)
            assertTrue("loopSeconds = ${patch.config.loopSeconds}f" in line)
            assertTrue("velocity = 0.3f, model = ${patch.model}" in line)
            assertEquals("ContourPresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a saved Relay keeps its source schedule and excitation when promoted`() {
        val root = shelf()
        try {
            val base = Relay.phraseFor(RelayVoice.KEY)
            val phrase = base.copy(
                sources = base.sources.mapIndexed { index, source -> if (index == 0) source.copy(gain = .42f) else source },
                orderRoster = base.orderRoster.reversed(),
                accents = listOf(RelayAccent(.5f, .8f), RelayAccent(2.5f, .4f)),
                tempoBpm = 120f,
                loop = RelayLoop(prerollCycles = 3, wrapFrames = 1536),
            )
            val source = RelayPatch("MY RELAY", RelayVoice.KEY, Relay.defaults(RelayVoice.KEY), phrase, velocity = .3f)
            UserPresets.save(root, source, 1L)
            val saved = UserPresets.read(root).single()
            assertEquals(source, saved.patch, "all state outside the macro map survives the shelf")
            val line = UserPresets.rosterLine(saved.patch)
            val literal = line.substringAfter("RelayPatch.fromJsonText(").substringBeforeLast(") }")
            val promoted = RelayPatch.fromJsonText(Json.parse(literal).str())
            assertEquals(source, promoted, "the pasteable roster line retains source, order, accent, tempo and loop configuration")
            assertTrue(source.render().samples.contentEquals(promoted.render().samples), "promotion regenerates the saved sound")
            assertEquals("RelayPresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a saved circuit retains root excitation velocity and noise seed when promoted`() {
        val root = shelf()
        try {
            val source = SkitterPatch(
                "LOW CIRCUIT", SkitterVoice.BASS,
                linkedMapOf("AGITATE" to .73f, "STATIC" to .21f), midi = 36, velocity = .3f, seed = -927,
            )
            UserPresets.save(root, source, 1L)
            val saved = UserPresets.read(root).single()
            assertEquals(source, saved.patch)
            val line = UserPresets.rosterLine(saved.patch)
            assertEquals(
                "p(SkitterVoice.BASS, \"LOW CIRCUIT\", \"AGITATE\" to 0.73f, \"STATIC\" to 0.21f).copy(midi = 36, velocity = 0.3f, seed = -927),",
                line,
            )
            assertEquals("SkitterPresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `saved lacquer promotion retains custom independent layers root velocity and escaped name`() {
        val root = shelf()
        try {
            val profile = Lacquer.profileFor(LacquerVoice.KEY)
            val custom = profile.copy(layers = profile.layers.map { layer ->
                if (layer.role == LacquerRole.TRAIL) layer.copy(
                    gain = layer.gain * .85f,
                    timing = layer.timing.copy(reliefSeconds = .08f),
                    envelope = layer.envelope.copy(releaseSeconds = 1.1f),
                ) else layer
            })
            val source = LacquerPatch("COAT \"A\" \$X", LacquerVoice.KEY,
                linkedMapOf("RELIEF" to .83f, "GLUE" to .21f), midi = 36, velocity = .3f, profile = custom)
            UserPresets.save(root, source, 1L)
            val saved = UserPresets.read(root).single()
            assertEquals(source, saved.patch)
            val line = UserPresets.rosterLine(saved.patch)
            assertEquals(source, lacquerFromRosterLine(line), "the constructor rebuilds every nested field and escaped text")
            assertEquals("LacquerPresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Contour promotion reconstructs every spectral and edge envelope field exactly`() {
        val root = shelf()
        try {
            val source = ContourPatch(
                "MY EDGE", ContourVoice.BASS,
                linkedMapOf("TUNE" to .5f, "SCAN" to .30f, "OFFSET" to .45f, "MOTION" to .48f,
                    "FILTER" to .58f, "DRIVE" to .20f, "HOLD" to 0f),
                config = ContourConfig.legacyForVoice(ContourVoice.BASS).copy(
                    bodyTable = ContourTable.SOFT_SAW, edgeTable = ContourTable.COPPER,
                    relation = ContourRelation.OPPOSED, bodyTravel = .22f, edgeTravel = .60f,
                    ampDecay = .45f, ampRelease = .42f,
                    bodyHarmonics = 1.37f, edgeRootLevel = .29f,
                    edgeAttack = .017f, edgeDecay = .91f, edgeSustain = .43f,
                ),
                velocity = .3f,
            )
            UserPresets.save(root, source, 1L)
            val saved = UserPresets.read(root).single().patch as ContourPatch
            assertEquals(source, saved, "all revised fields survive the shelf JSON roundtrip")
            val line = UserPresets.rosterLine(saved)
            val constructor = line.substringAfter("config = ").substringBefore(", velocity = ")
            assertEquals(
                "ContourConfig(bodyTable = ContourTable.SOFT_SAW, edgeTable = ContourTable.COPPER, " +
                    "edgeRatio = 1.0f, edgeLag = 0.18f, relation = ContourRelation.OPPOSED, " +
                    "bodyLevel = 0.8f, edgeLevel = 0.31f, subLevel = 0.09f, " +
                    "bodyHarmonics = 1.37f, edgeRootLevel = 0.29f, " +
                    "edgeAttack = 0.017f, edgeDecay = 0.91f, edgeSustain = 0.43f, " +
                    "bodyScanBias = 0.0f, edgeScanBias = 0.0f, bodyTravel = 0.22f, edgeTravel = 0.6f, " +
                    "ampAttack = 0.006f, ampDecay = 0.45f, ampSustain = 0.48f, " +
                    "ampRelease = 0.42f, sustainSeconds = 0.75f, " +
                    "filterAttack = 0.005f, filterDecay = 0.5f, filterAmount = 0.6f, " +
                    "motionShape = ContourMotionShape.FALL, motionSeconds = 1.2f, " +
                    "filterType = ContourFilterType.LOW_PASS, loopSeconds = 2.0f)",
                constructor,
                "the pasteable Kotlin constructor retains the exact architecture, including all five revised fields",
            )
            // Read the emitted constructor's own literals into the patch decoder. Any omitted
            // revised field falls back to neutral legacy state and changes this recipe.
            val fields = constructor.removePrefix("ContourConfig(").removeSuffix(")").split(", ").associate { entry ->
                val (name, literal) = entry.split(" = ", limit = 2)
                name to if (literal.endsWith("f")) JsonValue.Num(literal.dropLast(1).toDouble())
                    else JsonValue.Str(literal.substringAfterLast('.'))
            }
            val promoted = ContourPatch.fromJsonValue(JsonValue.Obj(
                saved.toJsonValue().entries + ("config" to JsonValue.Obj(fields)),
            )) as ContourPatch
            assertEquals(source.config, promoted.config, "promotion reconstructs the saved contour and independent edge envelope")
            assertEquals(source, promoted, "all audible recipe fields survive promotion")
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The MURMUR line is hand-built Kotlin, about 600 characters of nested constructor
     * per preset, and nothing compiles it until the owner pastes it into
     * MurmurPresets.kt. This saves a mouth whose every config field differs from its
     * voice's own configuration, with a quote, a backslash and a dollar in a target
     * name and a gain small enough to print as 5.0E-4, then reads the line back the
     * way the compiler would ([KotlinLiteral]) and rebuilds the patch from it.
     */
    @Test
    fun `a saved mouth retains root velocity and its complete instrument definition when promoted`() {
        val root = shelf()
        try {
            val held = MurmurPresets.all().first { it.name == "HELD HUM" }
            val config = MurmurConfig(
                routing = MurmurRouting.STACK, ratios = listOf(1.0, 1.5, 2.0001, 7.25),
                trajectory = MurmurTrajectory.ATTACK, direction = 1, referenceSeconds = .35,
                attack = .0015, decay = 2.75, sustain = .05, release = .3,
                targets = listOf(
                    MurmurFormantTarget("Say \"o\" \\ \$x", listOf(300.0, 900.0, 1_600.0, 2_700.0),
                        listOf(90.0, 170.0, 250.0, 340.0), listOf(1.6, .7, 5.0E-4, .2), .15),
                    MurmurFormantTarget("Wide", listOf(700.0, 1_200.0, 2_400.0, 3_200.0),
                        listOf(180.0, 210.0, 310.0, 390.0), listOf(1.2, 1.3, .6, .35), .6),
                ),
            )
            val source = held.copy(name = "MY \"HUM\" \$X", midi = 48, velocity = .7f, config = config)
            UserPresets.save(root, source, 1L)
            val saved = UserPresets.read(root).single()
            assertEquals(source, saved.patch, "the shelf retains root, velocity and the complete configuration")
            val line = UserPresets.rosterLine(saved.patch)
            assertEquals("MurmurPresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
            assertEquals(source, murmurFromRosterLine(line), "the pasteable line rebuilds the saved mouth exactly")
        } finally {
            root.deleteRecursively()
        }
    }

    /** The instance fields of a data class: its constructor properties, the arguments a pasted constructor must name. */
    private fun fieldsOf(type: Class<*>): Set<String> =
        type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.isSynthetic }.map { it.name }.toSet()

    /**
     * [line] as MurmurPresets.kt would build it: `p(voice, name, macros...)` is
     * `MurmurPatch(name, voice, Murmur.defaults(voice) + macros)`, then `.copy(...)`.
     * Every literal must have the type its parameter takes, and every constructor
     * must name exactly the fields of its class, so a field the line drops (or one
     * added to the class later) fails here rather than in the owner's paste.
     */
    private fun murmurFromRosterLine(line: String): MurmurPatch {
        assertTrue(line.endsWith(","), line)
        val copy = KotlinLiteral.parse(line.dropLast(1)) as KotlinLiteral.Call
        assertEquals("copy", copy.name, line)
        val p = copy.receiver as KotlinLiteral.Call
        assertEquals("p", p.name)
        assertTrue(p.named.isEmpty(), "the p helper takes positional arguments")
        val voiceRef = p.positional[0] as KotlinLiteral.Ref
        assertEquals("MurmurVoice", voiceRef.owner)
        val voice = MurmurVoice.valueOf(voiceRef.name)
        val name = (p.positional[1] as KotlinLiteral.Str).value
        val macros = p.positional.drop(2).associate { arg ->
            val pair = arg as KotlinLiteral.Pair
            (pair.first as KotlinLiteral.Str).value to float(pair.second)
        }
        assertEquals(fieldsOf(MurmurPatch::class.java) - setOf("name", "voice", "macros"), copy.named.keys, "the copy carries every field p does not")
        assertTrue(copy.positional.isEmpty())
        return MurmurPatch(
            name, voice, Murmur.defaults(voice) + macros,
            midi = int(copy.named.getValue("midi")),
            velocity = float(copy.named.getValue("velocity")),
            config = murmurConfig(copy.named.getValue("config")),
        )
    }

    private fun murmurConfig(node: KotlinLiteral.Node): MurmurConfig {
        val call = node as KotlinLiteral.Call
        assertEquals("MurmurConfig", call.name)
        assertTrue(call.receiver == null && call.positional.isEmpty(), "MurmurConfig is built with named arguments")
        assertEquals(fieldsOf(MurmurConfig::class.java), call.named.keys, "the constructor names every MurmurConfig field")
        val a = call.named
        return MurmurConfig(
            routing = MurmurRouting.valueOf(entry(a.getValue("routing"), "MurmurRouting")),
            ratios = doubles(a.getValue("ratios")),
            trajectory = MurmurTrajectory.valueOf(entry(a.getValue("trajectory"), "MurmurTrajectory")),
            direction = int(a.getValue("direction")),
            referenceSeconds = double(a.getValue("referenceSeconds")),
            attack = double(a.getValue("attack")),
            decay = double(a.getValue("decay")),
            sustain = double(a.getValue("sustain")),
            release = double(a.getValue("release")),
            targets = list(a.getValue("targets")).map { target ->
                val t = target as KotlinLiteral.Call
                assertEquals("MurmurFormantTarget", t.name)
                assertTrue(t.receiver == null && t.positional.isEmpty(), "MurmurFormantTarget is built with named arguments")
                assertEquals(fieldsOf(MurmurFormantTarget::class.java), t.named.keys, "the constructor names every MurmurFormantTarget field")
                MurmurFormantTarget(
                    name = (t.named.getValue("name") as KotlinLiteral.Str).value,
                    centers = doubles(t.named.getValue("centers")),
                    widths = doubles(t.named.getValue("widths")),
                    gains = doubles(t.named.getValue("gains")),
                    sourceBalance = double(t.named.getValue("sourceBalance")),
                )
            },
        )
    }

    private fun number(node: KotlinLiteral.Node, type: KotlinLiteral.Num.Type): String {
        val num = node as? KotlinLiteral.Num ?: error("not a number literal: $node")
        assertEquals(type, num.type, "${num.text} is a ${num.type} literal where the parameter takes a $type")
        return num.text
    }
    private fun double(node: KotlinLiteral.Node) = number(node, KotlinLiteral.Num.Type.DOUBLE).toDouble()
    private fun float(node: KotlinLiteral.Node) = number(node, KotlinLiteral.Num.Type.FLOAT).dropLast(1).toFloat()
    private fun int(node: KotlinLiteral.Node) = number(node, KotlinLiteral.Num.Type.INT).toInt()
    private fun entry(node: KotlinLiteral.Node, owner: String): String {
        val ref = node as KotlinLiteral.Ref
        assertEquals(owner, ref.owner)
        return ref.name
    }
    private fun list(node: KotlinLiteral.Node): List<KotlinLiteral.Node> {
        val call = node as KotlinLiteral.Call
        assertTrue(call.receiver == null && call.name == "listOf" && call.named.isEmpty(), "a list is listOf(...): $call")
        return call.positional
    }
    private fun doubles(node: KotlinLiteral.Node): List<Double> = list(node).map(::double)

    private fun lacquerFromRosterLine(line: String): LacquerPatch {
        assertTrue(line.startsWith("LacquerPatch.fromJsonText(\"") && line.endsWith("\"),"), line)
        val literal = line.removePrefix("LacquerPatch.fromJsonText(").removeSuffix("),")
        // Kotlin escapes a dollar sign in addition to JSON's ordinary string escapes.
        val json = Json.parse(literal.replace("\\\$", "\$")).str()
        return LacquerPatch.fromJsonText(json)
    }

    private fun openSkyFromRosterLine(line: String): OpenSkyPatch {
        val call = KotlinLiteral.parse(line.removeSuffix(",")) as KotlinLiteral.Call
        assertEquals("fromJsonText", call.name)
        val receiver = call.receiver as KotlinLiteral.Call
        assertEquals("OpenSkyPatch", receiver.name)
        assertTrue(receiver.positional.isEmpty() && receiver.named.isEmpty())
        assertTrue(call.named.isEmpty())
        val recipe = (call.positional.single() as KotlinLiteral.Str).value
        return OpenSkyPatch.fromJsonText(recipe)
    }

    @Test
    fun `every roster line names a table and a helper that exist`() {
        val engines = Presets.all().map { it.engine }.distinct()
        assertTrue(engines.size >= 8, "every registered engine ships a roster: $engines")
        for (engine in engines) {
            val table = File("../" + UserPresets.ROSTER_DIR + UserPresets.rosterFile(engine))
            assertTrue(table.isFile, "the table a $engine preset pastes into: $table")
            val text = table.readText(Charsets.UTF_8)
            if (engine == OpenSkyPatch.ENGINE) {
                val decoder = OpenSkyPatch.Companion.javaClass.getMethod("fromJsonText", String::class.java)
                assertEquals(OpenSkyPatch::class.java, decoder.returnType, "OPENSKY promotion names its public complete-JSON constructor")
            } else if (engine == StoopPatch.ENGINE) {
                assertTrue(Regex("private fun p\\(\\s*voice: StoopVoice, name: String, source: StoopSource, description: String, tags: String").containsMatchIn(text))
                for (argument in listOf("touch: Float?", "color: Float?", "shadow: Float?", "wear: Float?", "length: Float?", "hold: Float", "midi: Int?", "velocity: Float", "model: Int")) {
                    assertTrue(argument in text, "STOOP's metadata helper accepts the roster line's $argument")
                }
            } else {
                val helper = "private fun p(voice: ${UserPresets.voiceEnum(engine)}, name: String, vararg macros: Pair<String, Float>)"
                assertTrue(helper in text, "${table.name} is written with the helper the line uses: $helper")
            }
            val enum = Class.forName("com.snipsnap.synth." + UserPresets.voiceEnum(engine))
            assertTrue(enum.isEnum, "${UserPresets.voiceEnum(engine)} is the voice enum")
        }
    }

    @Test
    fun `forget moves a preset to the bin, restore brings it back, and a save in between carries the bin`() {
        val root = shelf()
        try {
            UserPresets.save(root, wrecked, 1L)
            UserPresets.save(root, snare, 2L)
            assertEquals(null, UserPresets.forget(root, "THUMP", "KICK", "NOT MINE", 5L), "nothing by that name is there to forget")
            val binned = UserPresets.forget(root, "THUMP", "KICK", "MY KICK", 5L)
            assertEquals(UserPresets.Binned(UserPresets.Saved(wrecked, 1L), 5L), binned)
            assertEquals(listOf(UserPresets.Saved(snare, 2L)), UserPresets.read(root), "off the strip")
            assertEquals(listOf(binned), UserPresets.bin(root), "and in the bin")
            assertEquals(null, UserPresets.forget(root, "THUMP", "KICK", "MY KICK", 6L), "forgotten once is forgotten")
            // A save while it sleeps must not lose it: the bin rides every write.
            val clap = ThumpPatch("CLAP 1", ThumpVoice.CLAP, Thump.defaults(ThumpVoice.CLAP))
            UserPresets.save(root, clap, 7L)
            assertEquals(listOf(binned), UserPresets.bin(root))
            val back = UserPresets.unforget(root, binned!!)
            assertEquals(UserPresets.Saved(wrecked, 1L), back, "back under its own name, with its own savedAt")
            assertEquals(listOf(UserPresets.Saved(snare, 2L), UserPresets.Saved(clap, 7L), back), UserPresets.read(root), "at the end of the strip")
            assertEquals(emptyList(), UserPresets.bin(root))
            assertEquals(null, UserPresets.unforget(root, binned), "a row that outran the tap restores nothing")
            assertFalse("\"binned\"" in UserPresets.file(root).readText(), "an empty bin is not written")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a forgotten name can be saved again, and the restore then lands under a fresh name that fits`() {
        val root = shelf()
        try {
            UserPresets.save(root, wrecked, 1L)
            val binned = UserPresets.forget(root, "THUMP", "KICK", "MY KICK", 2L)!!
            val newer = ThumpPatch("MY KICK", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK))
            UserPresets.save(root, newer, 3L)
            val back = UserPresets.unforget(root, binned)!!
            assertEquals("MY KICK 2", back.name, "the newer one keeps the name; the restored one is freshened")
            assertEquals(wrecked.macros, back.patch.macros, "and is still the sound that was forgotten")
            assertEquals(ThumpVoice.KICK, (back.patch as ThumpPatch).voice)
            assertEquals(listOf("MY KICK", "MY KICK 2"), UserPresets.forVoice(UserPresets.read(root), "THUMP", "KICK").map { it.name })
            // The fresh name fits the strip's rule even when the base fills it.
            val long = "FOURTEEN LETTE"
            assertEquals(UserPresets.MAX_NAME, long.length)
            assertEquals("FOURTEEN LET 2", UserPresets.freshName(long) { it == long })
            assertEquals("FOURTEEN LET 3", UserPresets.freshName(long) { it == long || it == "FOURTEEN LET 2" })
            assertEquals("MY KICK", UserPresets.freshName("MY KICK") { false })
            // And the merge's reading of a fresh name, which must agree with freshName exactly.
            assertTrue(UserPresets.freshened("MY KICK 2", "MY KICK") && UserPresets.freshened("MY KICK 10", "MY KICK"))
            assertTrue(UserPresets.freshened("FOURTEEN LET 2", long), "the trimmed base counts")
            assertFalse(UserPresets.freshened("MY KICK", "MY KICK"), "the name itself is not a fresh name")
            assertFalse(UserPresets.freshened("MY KICK 1", "MY KICK") || UserPresets.freshened("MY KICK 05", "MY KICK"), "numbers freshName never makes")
            assertFalse(UserPresets.freshened("KICK 2", "KICK 1"), "a numbered base is not the base of its neighbour")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the bin sweeps at the boundary, and the readout counts down to agree`() {
        val root = shelf()
        try {
            val day = 24L * 60 * 60 * 1000
            UserPresets.save(root, wrecked, 1L)
            UserPresets.save(root, snare, 2L)
            val old = UserPresets.forget(root, "THUMP", "KICK", "MY KICK", 10L)!!
            val young = UserPresets.forget(root, "THUMP", "SNARE", "MY KICK", 10L + 5 * day)!!
            assertEquals(UserPresets.BIN_DAYS, old.daysLeft(10L))
            assertEquals(1, old.daysLeft(10L + UserPresets.BIN_DAYS * day - 1), "a partial day still reads 1")
            assertEquals(0, old.daysLeft(10L + UserPresets.BIN_DAYS * day), "0 exactly where the sweep goes")
            assertEquals(0, old.daysLeft(10L + 40 * day), "never below zero")
            assertEquals(0, UserPresets.sweepBin(root, 10L + UserPresets.BIN_DAYS * day - 1), "not yet")
            assertEquals(1, UserPresets.sweepBin(root, 10L + UserPresets.BIN_DAYS * day), "the old one goes at the boundary")
            assertEquals(listOf(young), UserPresets.bin(root))
            assertEquals(0, UserPresets.sweepBin(root, 10L + UserPresets.BIN_DAYS * day), "and nothing is written for nothing")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a file from before the bin reads as an empty bin, and a binned entry this build cannot read is let go`() {
        val root = shelf()
        try {
            val file = UserPresets.file(root)
            val patch = wrecked.toJsonText().replace("\n", "")
            file.writeText("{\"version\": 1, \"presets\": [{\"savedAt\": 1, \"patch\": $patch}]}")
            assertEquals(listOf(UserPresets.Saved(wrecked, 1L)), UserPresets.read(root))
            assertEquals(emptyList(), UserPresets.bin(root))
            file.writeText("{\"version\": 1, \"presets\": [], \"binned\": [{\"savedAt\": 1, \"binnedAt\": 2, \"patch\": {\"engine\": \"FUTURE\"}}]}")
            assertEquals(emptyList(), UserPresets.bin(root), "not this build's to keep")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the forget lines ask first, then say where it went and how it comes back`() {
        assertEquals("FORGET MY KICK? IT WAITS IN DELETED PRESETS FOR ${Reversal.DAYS} DAYS.", Copy.presetForgetAsk("MY KICK"))
        assertEquals("MY KICK IS OFF THE STRIP. ${Reversal.MIND}", Copy.presetForgotten("MY KICK"))
        assertEquals("MY KICK 2 IS BACK UNDER YOURS.", Copy.presetRestored("MY KICK 2"))
        assertTrue("HOLD ITS CHIP TO FORGET IT" in Copy.PRESET_NAME_NOTE, "the save note says how a preset leaves: ${Copy.PRESET_NAME_NOTE}")
        assertTrue(Reversal.BIN in Copy.PRESET_NAME_NOTE, "and for how long the bin keeps it")
        assertTrue("STAYS" in Copy.PRESET_FORGET_FAILED && "BIN" in Copy.PRESET_RESTORE_FAILED)
    }

    @Test
    fun `the copy lines say what happened and what is not kept`() {
        assertEquals("MY KICK SAVED. IT IS UNDER THE FACTORY ROW ON EVERY KIT.", Copy.presetSaved("MY KICK"))
        assertEquals("MY KICK REPLACED. THE OLD SETTINGS ARE NOT KEPT.", Copy.presetReplaced("MY KICK"))
        assertEquals("REPLACES YOUR MY KICK. THE OLD SETTINGS ARE NOT KEPT.", Copy.presetReplaces("MY KICK"))
        assertEquals("THE FACTORY HAS DUSTY BOOM. PICK ANOTHER NAME.", Copy.presetNameFactory("DUSTY BOOM"))
        assertTrue("${UserPresets.MAX_NAME} LETTERS" in Copy.PRESET_NAME_NOTE, "the note says the one number the rule has: ${Copy.PRESET_NAME_NOTE}")
        assertTrue(Copy.PRESET_NAME_BLANK.endsWith("."))
        assertTrue(Copy.PRESET_SAVE_FAILED.endsWith("."))
    }


    @Test
    fun `a backup's presets merge in - the shelf's own stay, twins are skipped, namesakes land fresh, the bin rides minus what expired`() {
        val old = shelf()
        val new = shelf()
        val mine = shelf()
        try {
            val day = 24L * 60 * 60 * 1000
            val now = 1_700_000_000_000L
            // The old phone: two presets kept, two forgotten - one long expired, one with days left.
            UserPresets.save(old, wrecked, 1L)
            UserPresets.save(old, snare, 2L)
            UserPresets.save(old, ThumpPatch("CLAP 1", ThumpVoice.CLAP, Thump.defaults(ThumpVoice.CLAP)), 3L)
            UserPresets.save(old, ThumpPatch("HAT 1", ThumpVoice.HAT_CLOSED, Thump.defaults(ThumpVoice.HAT_CLOSED)), 4L)
            val expired = UserPresets.forget(old, "THUMP", "CLAP", "CLAP 1", now - 40 * day)!!
            val waiting = UserPresets.forget(old, "THUMP", "HAT_CLOSED", "HAT 1", now - 5 * day)!!
            val carried = UserPresets.file(old).readText(Charsets.UTF_8)

            // The new phone, bare: everything lands as it was; the expired one never gets out of the zip.
            val first = UserPresets.merge(new, carried, now)
            assertEquals(listOf(UserPresets.Saved(wrecked, 1L), UserPresets.Saved(snare, 2L)), first.landed)
            assertEquals(0, first.identical)
            assertEquals(1, first.binned)
            assertEquals(first.landed, UserPresets.read(new))
            assertEquals(listOf(waiting), UserPresets.bin(new), "the one with days left rides, with its own stamp; the expired one does not: $expired")

            // The same backup again: nothing lands, nothing is written.
            val file = UserPresets.file(new)
            file.setLastModified(1_000_000L)
            val again = UserPresets.merge(new, carried, now)
            assertEquals(emptyList(), again.landed)
            assertEquals(2, again.identical)
            assertEquals(0, again.binned, "a bin entry the shelf holds is not carried twice")
            assertEquals(1_000_000L, file.lastModified(), "nothing to land, nothing written")
            assertEquals(listOf(waiting), UserPresets.bin(new))

            // A shelf that saved its own MY KICK meanwhile, newer and different: it keeps the name and its place;
            // the backup's lands fresh, at the end, still the sound the backup held.
            val newer = UserPresets.save(mine, ThumpPatch("MY KICK", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK)), 9L)
            val third = UserPresets.merge(mine, carried, now)
            assertEquals(listOf("MY KICK 2" to "KICK", "MY KICK" to "SNARE"), third.landed.map { it.name to it.voice })
            assertEquals(wrecked.macros, third.landed.first().patch.macros)
            assertEquals(listOf(newer) + third.landed, UserPresets.read(mine), "the shelf's own first and untouched; the backup's after")
            assertEquals(0, third.identical)
            // And that backup a second time: MY KICK 2 is the backup's MY KICK already, so nothing sprouts a MY KICK 3.
            val fourth = UserPresets.merge(mine, carried, now)
            assertEquals(emptyList(), fourth.landed)
            assertEquals(2, fourth.identical)
            assertEquals(listOf("MY KICK", "MY KICK 2"), UserPresets.forVoice(UserPresets.read(mine), "THUMP", "KICK").map { it.name })
        } finally {
            old.deleteRecursively()
            new.deleteRecursively()
            mine.deleteRecursively()
        }
    }

    @Test
    fun `a backup whose presets file is not one merges nothing, and an entry neither build reads rides once`() {
        val root = shelf()
        try {
            UserPresets.save(root, wrecked, 1L)
            val file = UserPresets.file(root)
            file.setLastModified(1_000_000L)
            assertTrue(runCatching { UserPresets.merge(root, "not json at all", 5L) }.exceptionOrNull() is JsonException)
            assertTrue(runCatching { UserPresets.merge(root, "{\"version\": 2, \"presets\": []}", 5L) }.exceptionOrNull() is JsonException, "a version this build does not know")
            assertEquals(1_000_000L, file.lastModified(), "a refusal writes nothing")
            assertEquals(listOf(UserPresets.Saved(wrecked, 1L)), UserPresets.read(root))

            val future = "{\"version\": 1, \"presets\": [{\"savedAt\": 5, \"patch\": {\"engine\": \"FUTURE\", \"version\": 1, \"name\": \"X\", \"voice\": \"Y\", \"macros\": {}}}]}"
            assertEquals(UserPresets.Merged(emptyList(), 0, 0), UserPresets.merge(root, future, 5L))
            assertEquals(1, Regex("\"FUTURE\"").findAll(file.readText()).count(), "carried for the build that can read it")
            UserPresets.merge(root, future, 6L)
            assertEquals(1, Regex("\"FUTURE\"").findAll(file.readText()).count(), "and never twice")
            assertEquals(listOf(UserPresets.Saved(wrecked, 1L)), UserPresets.read(root), "the shelf's own untouched")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the backup and landing lines count the presets beside the kits, and read as they always did without them`() {
        assertEquals("2 KITS ON ONE FILE. PICK WHERE IT GOES.", Copy.backedUp(2, 0))
        assertEquals("3 KITS AND 2 PRESETS ON ONE FILE. PICK WHERE IT GOES.", Copy.backedUp(3, 0, 2))
        assertEquals("1 KIT AND 1 PRESET ON ONE FILE. 1 SKIPPED. PICK WHERE IT GOES.", Copy.backedUp(1, 1, 1))
        assertEquals("1 KIT LANDED ON THE SHELF.", Copy.landed(1, 0))
        assertEquals("1 KIT LANDED ON THE SHELF. 1 PRESET UNDER YOURS.", Copy.landed(1, 0, 1))
        assertEquals("2 KITS LANDED ON THE SHELF. 3 PRESETS UNDER YOURS. 1 SKIPPED.", Copy.landed(2, 1, 3))
    }

    /**
     * FLOTILLA keeps its note and strike velocity outside the macro map, as CISTERN
     * does, so its promoted line has to carry them. This reads the line back the way
     * FlotillaPresets.kt would build it ([KotlinLiteral]) and plays it.
     */
    @Test
    fun `a saved flotilla roster line retains its note and strike velocity`() {
        val root = shelf()
        try {
            val source = FlotillaPatch(
                "LOW WAKE", FlotillaVoice.RIPPLE,
                linkedMapOf("PULSE" to .2f, "SURFACE" to .4f), midi = 40, velocity = .3f,
            )
            UserPresets.save(root, source, 1L)
            val saved = UserPresets.read(root).single()
            assertEquals(source, saved.patch, "the shelf retains state outside the macros")
            val line = UserPresets.rosterLine(saved.patch)
            assertEquals(
                "p(FlotillaVoice.RIPPLE, \"LOW WAKE\", \"PULSE\" to 0.2f, \"SURFACE\" to 0.4f).copy(midi = 40, velocity = 0.3f),",
                line,
                "UserPresets.rosterLine's FLOTILLA branch: the pasteable line carries the saved note and strike velocity",
            )
            assertEquals("FlotillaPresets.kt\n  $line\n", UserPresets.renderAll(listOf(saved)))
            val rebuilt = flotillaFromRosterLine(line)
            assertEquals(source, rebuilt, "the pasteable line rebuilds the saved patch exactly")
            assertTrue(source.render().samples.contentEquals(rebuilt.render().samples), "and plays the bytes the phone heard")
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * [line] as FlotillaPresets.kt builds it: `p(voice, name, macros...)` is
     * `FlotillaPatch(name, voice, macros.toMap())`, then `.copy(...)`.
     */
    private fun flotillaFromRosterLine(line: String): FlotillaPatch {
        assertTrue(line.endsWith(","), line)
        val copy = KotlinLiteral.parse(line.dropLast(1)) as KotlinLiteral.Call
        assertEquals("copy", copy.name, "the line carries no .copy for the fields p does not take: $line")
        val p = copy.receiver as KotlinLiteral.Call
        assertEquals("p", p.name)
        assertTrue(p.named.isEmpty(), "the p helper takes positional arguments")
        val voiceRef = p.positional[0] as KotlinLiteral.Ref
        assertEquals("FlotillaVoice", voiceRef.owner)
        val voice = FlotillaVoice.valueOf(voiceRef.name)
        val name = (p.positional[1] as KotlinLiteral.Str).value
        val macros = p.positional.drop(2).associate { arg ->
            val pair = arg as KotlinLiteral.Pair
            (pair.first as KotlinLiteral.Str).value to float(pair.second)
        }
        // FlotillaPatch's init refuses every model but Flotilla.MODEL_VERSION, which is the default p builds.
        assertEquals(fieldsOf(FlotillaPatch::class.java) - setOf("name", "voice", "macros", "model"), copy.named.keys,
            "the copy carries every field p does not")
        assertTrue(copy.positional.isEmpty())
        return FlotillaPatch(name, voice, macros,
            midi = int(copy.named.getValue("midi")), velocity = float(copy.named.getValue("velocity")))
    }
}
