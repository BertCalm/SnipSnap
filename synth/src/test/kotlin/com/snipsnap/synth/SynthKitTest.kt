package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthKitTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("synthkit").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `the melodic kit is sixteen tonal pads`() {
        val kit = SynthKits.melodic()
        assertEquals(16, kit.size)
        assertTrue(kit.all { it != null && it.drumClass == DrumClass.TONAL }, "every pad is a note")
    }

    @Test
    fun `a melodic pad carries a little wow by default`() {
        // FxChain nests a section's macros under its own lowercase JSON key
        // (`FxChain.toJsonValue`, `SECTIONS`) - TAPE's own macro name is
        // "WOBBLE" (`Tape.MACROS`), so the recipe's fx must carry a "tape"
        // section with a "WOBBLE" entry, not a top-level "WOBBLE" key.
        val kit = SynthKits.melodic()
        val pad = kit.first { it != null }!!
        val fx = PadRecipe.fromJsonValue(pad.recipe!!).fx
        val tape = fx?.section("tape")
        assertTrue(tape != null && tape.containsKey("WOBBLE") && tape.getValue("WOBBLE") > 0f, "melodic pads should default to a touch of tape motion, got $tape")
    }

    @Test
    fun `the pluck side ascends and the root is the lowest note`() {
        // Root bottom-left, ascending - the SCALE layout convention, measured
        // off the actual renders by autocorrelation.
        val kit = SynthKits.melodic()
        val pitches = (0 until 12).map { TestPitch.estimate(kit[it]!!.snip) }
        for (i in 1 until 12) {
            assertTrue(
                pitches[i] > pitches[i - 1] * 1.02f,
                "pad ${i + 1} (${pitches[i]} Hz) should sit above pad $i (${pitches[i - 1]} Hz)",
            )
        }
    }

    @Test
    fun `the tide kit walks a bongo up the pentatonic and fills the grid`() {
        val kit = SynthKits.tide()
        assertEquals(16, kit.size)
        assertTrue(kit.all { it != null && it.recipe != null }, "every pad is a TIDE render with its recipe")
        assertTrue(kit.take(12).all { it!!.drumClass == DrumClass.PERC }, "bongos and drips are percussion")
        assertTrue(kit.drop(12).all { it!!.drumClass == DrumClass.TONAL }, "gongs and flares are notes")
        val pitches = (0 until 8).map { TestPitch.estimate(kit[it]!!.snip, fromSec = 0.02f, windowSec = 0.08f) }
        for (i in 1 until pitches.size) {
            assertTrue(pitches[i] > pitches[i - 1] * 1.02f, "bongo ${i + 1} (${pitches[i]} Hz) should sit above bongo $i (${pitches[i - 1]} Hz)")
        }
    }

    @Test
    fun `the bore kit is a triad on each voice and eight presets, tape on the notes and dry on the loops`() {
        val kit = SynthKits.bore()
        assertEquals(16, kit.size)
        assertTrue(kit.all { it != null && it.recipe != null }, "every pad is a BORE render with its recipe")
        for ((i, pad) in kit.withIndex()) {
            val recipe = PadRecipe.fromJsonValue(pad!!.recipe!!)
            val patch = recipe.patch as? BorePatch
            assertTrue(patch != null, "pad ${i + 1} should be a BORE patch, got ${recipe.patch?.engine}")
            val loop = Bore.isLoop(patch!!.macros.getValue("HOLD"))
            assertEquals(Bore.drumClassFor(patch.voice, patch.macros), pad.drumClass, "pad ${i + 1} is filed wrongly")
            if (loop) {
                assertEquals(DrumClass.LOOP, pad.drumClass, "pad ${i + 1} is a LOOP and must be filed one")
                assertEquals(null, recipe.fx, "pad ${i + 1} is a LOOP and lands dry")
            } else {
                assertEquals(Bore.LANDING_TAPE, recipe.fx?.tape, "pad ${i + 1} lands with the recipe's TAPE")
            }
        }
        // The two rows play a chord: each ascends by the semitones the kit says, root C4 and C3.
        for ((start, voice) in listOf(0 to BoreVoice.FLUTE, 4 to BoreVoice.SAX)) {
            val pitches = (start until start + 4).map { TestPitch.estimate(kit[it]!!.snip, fromSec = 0.3f, windowSec = 0.3f) }
            val expected = listOf(0, 4, 7, 12).map { Keys.midiHz(Bore.rootMidi(voice) + it) }
            for (k in pitches.indices) {
                val cents = 1200 * Math.log((pitches[k] / expected[k]).toDouble()) / Math.log(2.0)
                assertTrue(Math.abs(cents) < 60.0, "$voice pad ${start + k + 1} plays ${pitches[k]} Hz, ${"%.0f".format(cents)} cents from ${expected[k]} Hz")
            }
        }
    }

    @Test
    fun `the arco kit is eight cello stabs up the pentatonic, six erhu presets and a loop for each voice, all dry`() {
        val kit = SynthKits.arco()
        assertEquals(16, kit.size)
        assertTrue(kit.all { it != null && it.recipe != null }, "every pad is an ARCO render with its recipe")
        val patches = kit.mapIndexed { i, pad ->
            val recipe = PadRecipe.fromJsonValue(pad!!.recipe!!)
            val patch = recipe.patch as? ArcoPatch
            assertTrue(patch != null, "pad ${i + 1} should be an ARCO patch, got ${recipe.patch?.engine}")
            assertEquals(null, recipe.fx, "pad ${i + 1} lands dry: ARCO has no landing chain")
            patch!!
        }
        // The kit files every pad with Arco.drumClassFor, so comparing to that call would pass whatever it said: the expected
        // class is written out. Fourteen notes under the 1.5 s line are PERC and the last two pads are the loops.
        for (k in 0 until 16) {
            val expected = if (k >= 14) DrumClass.LOOP else DrumClass.PERC
            assertEquals(expected, kit[k]!!.drumClass, "pad ${k + 1} is filed ${kit[k]!!.drumClass}")
        }
        // A01-A08: CELLO up the minor pentatonic from its root, every stab a note and not a LOOP. The stab's HOLD is the bottom of the
        // knob (SynthKits.ARCO_STAB_HOLD is private, so the test writes 0) and every other macro is at its default: the row is what
        // the knobs sound like before anyone touches them.
        val walk = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        val defaults = Arco.defaults(ArcoVoice.CELLO)
        for (k in 0 until 8) {
            val macros = patches[k].macros
            assertEquals(ArcoVoice.CELLO, patches[k].voice, "pad ${k + 1} is CELLO")
            assertEquals("Cello ${k + 1}", patches[k].name, "pad ${k + 1}'s patch name")
            assertEquals(Arco.rootMidi(ArcoVoice.CELLO) + walk[k], Arco.midiFor(ArcoVoice.CELLO, macros.getValue("TUNE")), "pad ${k + 1} is the ${walk[k]}th semitone")
            assertEquals(0f, macros.getValue("HOLD"), "pad ${k + 1} is a stab: HOLD at the bottom of the knob")
            for ((name, default) in defaults) {
                if (name == "TUNE" || name == "HOLD") continue
                assertEquals(default, macros.getValue(name), "pad ${k + 1}: $name is at its default")
            }
        }
        // A09-A14: the six ERHU presets, in this order; A15 and A16 the two loops, one a voice.
        val erhu = listOf("NASAL LINE", "MOON FIDDLE", "THIN SCRAPE", "HIGH CRY", "SLOW CRY", "TEA HOUSE")
        for (k in 8 until 14) {
            assertEquals(ArcoVoice.ERHU, patches[k].voice, "pad ${k + 1} is ERHU")
            assertEquals(erhu[k - 8], patches[k].name, "pad ${k + 1} is the preset ${erhu[k - 8]}")
        }
        assertEquals(ArcoVoice.CELLO to "ENDLESS DRAW", patches[14].voice to patches[14].name, "A15 is CELLO's LOOP")
        assertEquals(ArcoVoice.ERHU to "ENDLESS CRY", patches[15].voice to patches[15].name, "A16 is ERHU's LOOP")
        for (k in 14..15) {
            val hz = Arco.frequencyFor(patches[k].voice, patches[k].macros.getValue("TUNE"))
            val cents = FineTuning.cents(FineTuning.measuredHz(kit[k]!!.snip, hz, fromSec = 0.1f, bodySeconds = 1.4f), hz.toDouble())
            assertTrue(Math.abs(cents) < 5.0, "the LOOP on pad ${k + 1} plays ${"%.2f".format(java.util.Locale.ROOT, cents)} cents from its note")
        }
    }

    @Test
    fun `the magnet kit is a chug riff, a jangle chord and a lead pair, each landed through its amp`() {
        val kit = SynthKits.magnet()
        assertEquals(16, kit.size)
        assertTrue(kit.none { it == null }, "no empty pad in the magnet kit")
        // The notes the kit plays (MIDI): B1 D2 E2 F#2 A2 B2 D3 E3 on CHUG, E2 B2 E3 G#3 B3 E4 on JANGLE,
        // then F#3 and B3 on CHUG for the lead pair.
        val expectedMidi = listOf(35, 38, 40, 42, 45, 47, 50, 52) + listOf(40, 47, 52, 56, 59, 64) + listOf(54, 59)
        val leadValve = mapOf("DRIVE" to 0.78f, "SAG" to 0.4f, "TONE" to 0.5f, "CAB" to 0.95f)
        for (i in kit.indices) {
            val n = i + 1
            val pad = kit[i]!!
            assertEquals(DrumClass.TONAL, pad.drumClass, "pad $n is a note")
            val recipe = PadRecipe.fromJsonValue(pad.recipe ?: error("pad $n carries no recipe"))
            val patch = recipe.patch as? MagnetPatch ?: error("pad $n should be a MAGNET patch, got ${recipe.patch?.engine}")

            val voice = patch.voice
            val expectedVoice = if (i in 8..13) MagnetVoice.JANGLE else MagnetVoice.CHUG
            assertEquals(expectedVoice, voice, "pad $n is the wrong voice")
            val lead = i >= 14
            assertEquals(if (lead) 0.35f else Magnet.defaults(voice).getValue("BLEND"), patch.macros.getValue("BLEND"), "pad $n BLEND")
            assertTrue(recipe.fx?.valve != null, "pad $n lands through VALVE")
            assertEquals(if (lead) leadValve else Magnet.LANDING_VALVE.getValue(voice), recipe.fx?.valve, "pad $n amp")

            // The pitch is read on the dry patch render: pad.snip has been through the landing VALVE.
            val tune = patch.macros.getValue("TUNE")
            assertEquals(expectedMidi[i], Magnet.rootMidi(voice) + Magnet.semitonesFor(tune), "pad $n plays the wrong note")
            val want = Magnet.frequencyFor(voice, tune)
            val dry = patch.render()
            assertEquals(Dsp.RATE, dry.sampleRate, "pad $n dry render rate")
            val cents = FineTuning.cents(FineTuning.measuredHz(dry.samples, Dsp.RATE, want), want.toDouble())
            val rounded = Math.round(cents * 100.0) / 100.0
            println("MAGNET kit pad $n $voice midi ${expectedMidi[i]} $want Hz, dry read $rounded cents")
            assertTrue(Math.abs(cents) <= 10.0, "pad $n ($voice) reads $rounded cents from $want Hz")

            val regenerated = recipe.render()
            assertTrue(regenerated.samples.contentEquals(pad.snip.samples), "pad $n does not regenerate bit for bit from its recipe")
        }
    }

    @Test
    fun `the chip kit is sixteen crunched pads that keep their identities`() {
        val kit = SynthKits.chip()
        assertEquals(16, kit.size)
        assertTrue(kit.all { it != null }, "no empty pads in the chip kit")
        // The whole point of the kit: the converter grunge is character,
        // not identity - the drums still classify as themselves.
        assertEquals(DrumClass.KICK, com.snipsnap.audio.Classifier.classify(kit[0]!!.snip).drumClass)
        assertEquals(DrumClass.SNARE, com.snipsnap.audio.Classifier.classify(kit[1]!!.snip).drumClass)
        // And the chip notes ascend like the melodic kit's plucks do.
        val pitches = (6 until 16).map { TestPitch.estimate(kit[it]!!.snip, fromSec = 0.03f, windowSec = 0.15f) }
        for (i in 1 until pitches.size) {
            assertTrue(
                pitches[i] > pitches[i - 1] * 1.02f,
                "chip pad ${i + 7} (${pitches[i]} Hz) should sit above pad ${i + 6} (${pitches[i - 1]} Hz)",
            )
        }
    }

    @Test
    fun `melodic kit to sd card, end to end`() {
        val kitDir = File(temp, "kit")
        val kit = KitAssembler.assembleArranged("Synth Melodic", SynthKits.melodic(), kitDir)

        assertEquals(16, kit.pads.size)
        val findings = Preflight.check(kit, kitDir)
        assertTrue(!findings.blocked(), "melodic kit must pass preflight: $findings")

        val result = KitExporter.exportProgramFolder(kit, kitDir, File(temp, "sd"))
        assertEquals(16, result.samples.size)
        for (wav in result.samples) {
            assertEquals(44_100, WavInfo.read(wav).sampleRate)
        }
        val xml = result.program.readText()
        assertTrue("<SampleName>A01_Tonal_01</SampleName>" in xml)
        assertTrue("<SampleName>A16_Tonal_16</SampleName>" in xml)
    }

    @Test
    fun `the melodic kit's kalimba pads are TINES notes at the same pitches`() {
        // KALIMBA moved engines (spec decision 5, Phase 1 gate: TINES won).
        // The pads keep their slots and their notes; only the engine changes.
        val kit = SynthKits.melodic()
        for (i in 6..10) {
            val recipe = PadRecipe.fromJsonValue(kit[i]!!.recipe!!)
            val patch = recipe.patch as? TinesPatch
            assertTrue(patch != null, "pad ${i + 1} should be a TINES patch, got ${recipe.patch?.engine}")
            assertEquals(TinesVoice.KALIMBA, patch!!.voice, "pad ${i + 1} voice")
        }
    }

    @Test
    fun `the second mercury kit is five eddy, five vessel and six shard presets, all dry and filed LOOP`() {
        val kit = SynthKits.mercury2()
        assertEquals(16, kit.size)
        assertTrue(kit.all { it != null && it.recipe != null }, "every pad is a MERCURY render with its recipe")
        val patches = kit.mapIndexed { i, pad ->
            val recipe = PadRecipe.fromJsonValue(pad!!.recipe!!)
            val patch = recipe.patch as? MercuryPatch
            assertTrue(patch != null, "pad ${i + 1} should be a MERCURY patch, got ${recipe.patch?.engine}")
            assertEquals(null, recipe.fx, "pad ${i + 1} lands dry")
            patch!!
        }
        // Written out: every one of these presets is past the classifier's 1.5 s line, so the length rule files each LOOP.
        for (k in 0 until 16) assertEquals(DrumClass.LOOP, kit[k]!!.drumClass, "pad ${k + 1} is filed ${kit[k]!!.drumClass}")
        assertEquals(
            listOf(
                "EDDY HEARTH HUM", "EDDY TWIN BEATING", "EDDY SLOW SWIRL", "EDDY RIM WAVER", "EDDY STRUCK BOWL",
                "VESSEL CISTERN", "VESSEL DRAIN PIPE", "VESSEL BOILER HUM", "VESSEL WATER TOWER", "VESSEL TWIN PIPES",
                "SHARD BROKEN PANE", "SHARD SPLINTER", "SHARD SKITTER", "SHARD HAIRLINE", "SHARD FRAYED EDGE", "SHARD TIN SKY",
            ),
            patches.map { "${it.voice} ${it.name}" },
        )
        for (p in patches) assertEquals(MercuryPresets.forVoice(p.voice).first { it.name == p.name }, p, "${p.voice} ${p.name} is the preset itself")
    }

    @Test
    fun `the mercury kit is eight pings up the pentatonic, four sing and four blade presets, all dry`() {
        val kit = SynthKits.mercury()
        assertEquals(16, kit.size)
        assertTrue(kit.all { it != null && it.recipe != null }, "every pad is a MERCURY render with its recipe")
        val patches = kit.mapIndexed { i, pad ->
            val recipe = PadRecipe.fromJsonValue(pad!!.recipe!!)
            val patch = recipe.patch as? MercuryPatch
            assertTrue(patch != null, "pad ${i + 1} should be a MERCURY patch, got ${recipe.patch?.engine}")
            assertEquals(null, recipe.fx, "pad ${i + 1} lands dry: MERCURY has no landing chain")
            patch!!
        }
        // Written out, not read back from Mercury.drumClassFor: every pad here is its contact plus a ringing tail past the
        // classifier's 1.5 s line, so the length rule files every one LOOP.
        for (k in 0 until 16) assertEquals(DrumClass.LOOP, kit[k]!!.drumClass, "pad ${k + 1} is filed ${kit[k]!!.drumClass}")
        val walk = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        val defaults = Mercury.defaults(MercuryVoice.PING)
        for (k in 0 until 8) {
            val macros = patches[k].macros
            assertEquals(MercuryVoice.PING, patches[k].voice, "pad ${k + 1} is PING")
            assertEquals("Ping ${k + 1}", patches[k].name, "pad ${k + 1}'s patch name")
            assertEquals(Mercury.rootMidi(MercuryVoice.PING) + walk[k], Mercury.midiFor(MercuryVoice.PING, macros.getValue("TUNE")), "pad ${k + 1} is the ${walk[k]}th semitone")
            for ((name, default) in defaults) {
                if (name == "TUNE") continue
                assertEquals(default, macros.getValue(name), "pad ${k + 1}: $name is at its default")
            }
        }
        val names = patches.drop(8).map { "${it.voice} ${it.name}" }
        assertEquals(
            listOf(
                "SING LONG RUB", "SING SINGING EDGE", "SING GLASS CURRENT", "SING LOW HUM",
                "BLADE BENT RIBBON", "BLADE WHISTLE BEND", "BLADE DOWN BEND", "BLADE WOBBLE STEEL",
            ),
            names,
        )
        for (k in 8 until 16) assertEquals(MercuryPresets.forVoice(patches[k].voice).first { it.name == patches[k].name }, patches[k], "pad ${k + 1} is the preset itself")
    }

    @Test
    fun `the flotilla kit is eight ripples up the pentatonic and eight presets, all dry, and exports at 44100`() {
        val kit = SynthKits.flotilla()
        assertEquals(16, kit.size)
        assertTrue(kit.all { it != null && it.recipe != null }, "every pad is a FLOTILLA render with its recipe")
        val patches = kit.mapIndexed { i, pad ->
            val recipe = PadRecipe.fromJsonValue(pad!!.recipe!!)
            val again = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe.patch, again.patch, "pad ${i + 1} recipe")
            val patch = recipe.patch as? FlotillaPatch
            assertTrue(patch != null, "pad ${i + 1} should be a FLOTILLA patch, got ${recipe.patch?.engine}")
            assertEquals(null, recipe.fx, "pad ${i + 1} lands dry")
            assertEquals(DrumClass.LOOP, pad.drumClass, "pad ${i + 1}")
            patch!!
        }
        val walk = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        val defaults = Flotilla.defaults(FlotillaVoice.RIPPLE)
        for (k in 0 until 8) {
            assertEquals(FlotillaVoice.RIPPLE, patches[k].voice)
            assertEquals("Wake ${k + 1}", patches[k].name)
            assertEquals(60 + walk[k], patches[k].midi)
            assertEquals(defaults, patches[k].macros)
        }
        assertEquals(
            listOf(
                "KNOCK Open Wood",
                "HOLLOW Deep Cavity",
                "CROSSWAVE Crossing Paths",
                "DRIFT Warm Canopy",
                "DRIFT Gentle Current",
                "GATHER Gathered Vessels",
                "DRIFT Held Sparse",
                "GATHER Held Dense",
            ),
            patches.drop(8).map { "${it.voice} ${it.name}" },
        )
        val kitDir = File(temp, "flotilla")
        val assembled = KitAssembler.assembleArranged("Flotilla", kit, kitDir)
        val result = KitExporter.exportProgramFolder(assembled, kitDir, File(temp, "sd"))
        assertEquals(16, result.samples.size)
        for (wav in result.samples) assertEquals(44_100, WavInfo.read(wav).sampleRate)
    }
}
