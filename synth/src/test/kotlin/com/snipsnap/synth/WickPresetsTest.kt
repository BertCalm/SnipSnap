package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Factory contracts and preservation of the actual twenty-seven published patch JSONs. */
class WickPresetsTest {
    @Test
    fun `six complete dry recipes per voice are reachable through the factory roster`() {
        val all = WickPresets.all()
        assertEquals(36, all.size)
        assertEquals(36, all.map { it.name }.toSet().size)
        assertEquals(9, WickPresets.additions().size)
        assertTrue(all.containsAll(WickPresets.additions()))
        assertTrue(Presets.all().containsAll(all))
        assertTrue(Presets.forVoice(WickPatch.ENGINE, "NOT_A_VOICE").isEmpty())
        for (voice in WickVoice.entries) {
            val bank = WickPresets.forVoice(voice)
            assertEquals(6, bank.size, "$voice factory count")
            assertEquals(bank, Presets.forVoice(WickPatch.ENGINE, voice.name), "$voice factory dispatch")
            for (patch in bank) {
                assertEquals(Wick.defaults(voice).keys, patch.macros.keys, "${patch.name}: every control is saved")
                assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f }, patch.name)
                assertTrue(patch.name.length <= 18, "${patch.name}: sampler label length")
                assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name), patch.name)
                assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
                assertEquals(null, Presets.landingFor(patch.engine, patch.voiceName, patch.macros), "${patch.name}: dry identity")
            }
        }
    }

    @Test
    fun `the twenty-seven published patches retain every saved field and their relative order`() {
        val reference = Json.parse(PUBLISHED_RECIPES).arr()
        val names = reference.map { it.obj().getValue("name").str() }
        assertEquals(27, names.size)
        val preserved = WickPresets.all().filter { it.name in names }
        assertEquals(names, preserved.map { it.name })
        assertEquals(reference, preserved.map { it.toJsonValue() }, "published controls, notes, velocities and bodies remain exact")
        for (voice in WickVoice.entries) {
            val first = preserved.first { it.voice == voice }
            assertEquals(first, WickPresets.forVoice(voice).first(), "$voice keeps its published default")
        }
    }

    @Test
    fun `factory renders remain finite bounded pitched and usefully levelled`() {
        val choking = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED,
            DrumClass.HAT_OPEN, DrumClass.CLAP, DrumClass.TOM)
        for (patch in WickPresets.all()) {
            val snip = renders.getValue(patch)
            assertTrue(snip.frameCount > 0 && snip.channels == 1 && snip.sampleRate == Dsp.RATE, patch.name)
            assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "${patch.name}: finite output bounds")
            assertTrue(abs(snip.samples.average()) < .015, "${patch.name}: DC")
            assertTrue(Loudness.of(snip) >= Dsp.MELODIC_LOUDNESS_TARGET * .9f || snip.peak() >= .95f,
                "${patch.name}: native render is too quiet")
            val pitch = assertNotNull(Pitch.detect(snip, .05f, .25f), "${patch.name}: opening pitch")
            val cents = FineTuning.cents(pitch.hz.toDouble(), Keys.midiHz(patch.midi).toDouble())
            assertTrue(abs(cents) < 35 && pitch.confidence > .3f,
                "${patch.name}: requested pitch $cents cents, confidence ${pitch.confidence}")
            // Full finite notes can exceed the classifier's duration shortcut. This checks
            // that no factory file enters a real drum choke class, not that LOOP means HOLD.
            assertTrue(Classifier.classify(snip).drumClass !in choking, "${patch.name}: pitched routing")
            val expected = if (patch.macros.getValue("HOLD") == 1f) DrumClass.LOOP else DrumClass.TONAL
            assertEquals(expected, Wick.drumClassFor(patch.voice, patch.macros), "${patch.name}: intentional note/held filing")
        }
    }

    @Test
    fun `every factory patch round-trips and regenerates its exact body`() {
        for (patch in WickPresets.all()) {
            val restored = Patches.fromJsonText(patch.toJsonText())
            assertEquals(patch, restored, patch.name)
            assertContentEquals(renders.getValue(patch).samples, restored.render().samples,
                "${patch.name}: saved normal patch replay")
        }
    }

    @Test
    fun `same-voice recipes differ beyond export gain or a renamed control vector`() {
        for (voice in WickVoice.entries) {
            val bank = WickPresets.forVoice(voice)
            for ((index, a) in bank.withIndex()) for (b in bank.drop(index + 1)) {
                if (a.rattleSource == b.rattleSource) {
                    assertTrue(PresetTestSupport.rmsDistance(a.macros, b.macros) > .05f,
                        "$voice: ${a.name} and ${b.name} control vectors cluster")
                }
                val distance = TangleMeasure.distance(renders.getValue(a).samples, renders.getValue(b).samples)
                assertTrue(distance > .01, "$voice: ${a.name} and ${b.name} only differ by export gain ($distance)")
            }
        }
    }

    @Test
    fun `each voice retains its named held recipe with a genuine preceding period`() {
        val held = WickPresets.all().filter { it.macros.getValue("HOLD") == 1f }
        assertEquals(6, held.size)
        assertEquals(WickVoice.entries.toSet(), held.map { it.voice }.toSet())
        for (patch in held) {
            assertTrue(patch.name.startsWith("Held "), patch.name)
            val probe = Wick.inspect(patch.voice, patch.macros, patch.midi, patch.velocity,
                rattleSource = patch.rattleSource)
            val loop = assertNotNull(probe.diagnostics.loop, patch.name)
            val context = assertNotNull(probe.seamContext, patch.name)
            assertTrue(loop.converged && loop.stateError < 2e-6 && loop.audioError < 1e-7,
                "${patch.name}: complete held state must recur")
            assertTrue(context.size >= probe.samples.size + 256, "${patch.name}: independent preceding context")
            assertTrue(Keys.seamError(context, probe.samples.size) < Keys.MAX_SEAM_ERROR,
                "${patch.name}: genuine preceding-cycle audio seam")
        }
    }

    private companion object {
        val renders by lazy { WickPresets.all().associateWith { it.render() } }

        // Captured from the published audition and Source Kit before factory expansion.
        // This pins real saved patch data rather than recomputing the old recipes from new defaults.
        const val PUBLISHED_RECIPES = """[{"engine":"WICK","version":1,"name":"Small Spark","voice":"PLUCK","macros":{"SPARK":0.44999998807907104,"FEED":0.05000000074505806,"CASCADE":0.20000000298023224,"TIMBRE":0.25,"RECOVERY":0.30000001192092896,"HOLD":0},"midi":48,"velocity":1},{"engine":"WICK","version":1,"name":"Soft Wick","voice":"PLUCK","macros":{"SPARK":0.20000000298023224,"FEED":0,"CASCADE":0.11999999731779099,"TIMBRE":0.18000000715255737,"RECOVERY":0.4000000059604645,"HOLD":0},"midi":48,"velocity":1},{"engine":"WICK","version":1,"name":"Dry Chain","voice":"PLUCK","macros":{"SPARK":0.6000000238418579,"FEED":0.10000000149011612,"CASCADE":0.6000000238418579,"TIMBRE":0.41999998688697815,"RECOVERY":0.30000001192092896,"HOLD":0},"midi":48,"velocity":1},{"engine":"WICK","version":1,"name":"Held Glow","voice":"PLUCK","macros":{"SPARK":0.4000000059604645,"FEED":0.3499999940395355,"CASCADE":0.4000000059604645,"TIMBRE":0.30000001192092896,"RECOVERY":0.44999998807907104,"HOLD":1},"midi":48,"velocity":1},{"engine":"WICK","version":1,"name":"Dry Wood","voice":"WOOD","macros":{"SPARK":0.6000000238418579,"FEED":0.15000000596046448,"CASCADE":0.4000000059604645,"TIMBRE":0.4000000059604645,"RECOVERY":0.4000000059604645,"HOLD":0},"midi":48,"velocity":1},{"engine":"WICK","version":1,"name":"Soft Body","voice":"WOOD","macros":{"SPARK":0.30000001192092896,"FEED":0.05000000074505806,"CASCADE":0.20000000298023224,"TIMBRE":0.20000000298023224,"RECOVERY":0.550000011920929,"HOLD":0},"midi":48,"velocity":1},{"engine":"WICK","version":1,"name":"Small Rebounds","voice":"WOOD","macros":{"SPARK":0.6499999761581421,"FEED":0.20000000298023224,"CASCADE":0.6499999761581421,"TIMBRE":0.5,"RECOVERY":0.30000001192092896,"HOLD":0},"midi":48,"velocity":1},{"engine":"WICK","version":1,"name":"Held Wood","voice":"WOOD","macros":{"SPARK":0.5,"FEED":0.4000000059604645,"CASCADE":0.550000011920929,"TIMBRE":0.3499999940395355,"RECOVERY":0.5,"HOLD":1},"midi":48,"velocity":1},{"engine":"WICK","version":1,"name":"Bubble Chain","voice":"BUBBLE","macros":{"SPARK":0.4000000059604645,"FEED":0.25,"CASCADE":0.699999988079071,"TIMBRE":0.44999998807907104,"RECOVERY":0.5,"HOLD":0},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Slow Feed","voice":"BUBBLE","macros":{"SPARK":0.30000001192092896,"FEED":0.3499999940395355,"CASCADE":0.6499999761581421,"TIMBRE":0.30000001192092896,"RECOVERY":0.6499999761581421,"HOLD":0},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Spent Bubbles","voice":"BUBBLE","macros":{"SPARK":0.6000000238418579,"FEED":0.8500000238418579,"CASCADE":0.8500000238418579,"TIMBRE":0.6000000238418579,"RECOVERY":0.800000011920929,"HOLD":0},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Held Bubbles","voice":"BUBBLE","macros":{"SPARK":0.44999998807907104,"FEED":0.6499999761581421,"CASCADE":0.75,"TIMBRE":0.44999998807907104,"RECOVERY":0.6000000238418579,"HOLD":1},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Busy Bubbles","voice":"BUBBLE","macros":{"SPARK":0.4000000059604645,"FEED":0.6499999761581421,"CASCADE":0.699999988079071,"TIMBRE":0.44999998807907104,"RECOVERY":0.5,"HOLD":0},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Open Air","voice":"BREATH","macros":{"SPARK":0.20000000298023224,"FEED":0.6000000238418579,"CASCADE":0.3499999940395355,"TIMBRE":0.20000000298023224,"RECOVERY":0.699999988079071,"HOLD":0},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Single Breath","voice":"BREATH","macros":{"SPARK":0.25,"FEED":0,"CASCADE":0.15000000596046448,"TIMBRE":0.11999999731779099,"RECOVERY":0.800000011920929,"HOLD":0},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Layered Air","voice":"BREATH","macros":{"SPARK":0.30000001192092896,"FEED":0.800000011920929,"CASCADE":0.6000000238418579,"TIMBRE":0.3499999940395355,"RECOVERY":0.550000011920929,"HOLD":0},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Held Breath","voice":"BREATH","macros":{"SPARK":0.25,"FEED":0.6499999761581421,"CASCADE":0.4000000059604645,"TIMBRE":0.25,"RECOVERY":0.699999988079071,"HOLD":1},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Metal Flash","voice":"FLICKER","macros":{"SPARK":0.550000011920929,"FEED":0.44999998807907104,"CASCADE":0.6499999761581421,"TIMBRE":0.800000011920929,"RECOVERY":0.3499999940395355,"HOLD":0},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Single Glint","voice":"FLICKER","macros":{"SPARK":0.4000000059604645,"FEED":0,"CASCADE":0,"TIMBRE":0.6499999761581421,"RECOVERY":0.44999998807907104,"HOLD":0},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Bright Chain","voice":"FLICKER","macros":{"SPARK":0.6499999761581421,"FEED":0.550000011920929,"CASCADE":0.800000011920929,"TIMBRE":0.949999988079071,"RECOVERY":0.4000000059604645,"HOLD":0},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Held Flicker","voice":"FLICKER","macros":{"SPARK":0.5,"FEED":0.550000011920929,"CASCADE":0.699999988079071,"TIMBRE":0.75,"RECOVERY":0.44999998807907104,"HOLD":1},"midi":60,"velocity":1},{"engine":"WICK","version":1,"name":"Warm Wire","voice":"RATTLE","macros":{"SPARK":0.6499999761581421,"FEED":0.5,"CASCADE":0.6499999761581421,"TIMBRE":0.44999998807907104,"RECOVERY":0.6499999761581421,"HOLD":0},"midi":60,"velocity":1,"rattleSource":"WARM_WIRE"},{"engine":"WICK","version":1,"name":"Hollow Reed","voice":"RATTLE","macros":{"SPARK":0.6499999761581421,"FEED":0.5,"CASCADE":0.6499999761581421,"TIMBRE":0.44999998807907104,"RECOVERY":0.6499999761581421,"HOLD":0},"midi":60,"velocity":1,"rattleSource":"HOLLOW_REED"},{"engine":"WICK","version":1,"name":"Rubbed Glass","voice":"RATTLE","macros":{"SPARK":0.6499999761581421,"FEED":0.5,"CASCADE":0.6499999761581421,"TIMBRE":0.44999998807907104,"RECOVERY":0.6499999761581421,"HOLD":0},"midi":60,"velocity":1,"rattleSource":"RUBBED_GLASS"},{"engine":"WICK","version":1,"name":"Depleted Rattle","voice":"RATTLE","macros":{"SPARK":0.75,"FEED":0.800000011920929,"CASCADE":0.8500000238418579,"TIMBRE":0.6499999761581421,"RECOVERY":0.6499999761581421,"HOLD":0},"midi":60,"velocity":1,"rattleSource":"WARM_WIRE"},{"engine":"WICK","version":1,"name":"Small Rattle","voice":"RATTLE","macros":{"SPARK":0.44999998807907104,"FEED":0.25,"CASCADE":0.44999998807907104,"TIMBRE":0.30000001192092896,"RECOVERY":0.5,"HOLD":0},"midi":60,"velocity":1,"rattleSource":"WARM_WIRE"},{"engine":"WICK","version":1,"name":"Held Rattle","voice":"RATTLE","macros":{"SPARK":0.6000000238418579,"FEED":0.550000011920929,"CASCADE":0.699999988079071,"TIMBRE":0.4000000059604645,"RECOVERY":0.6499999761581421,"HOLD":1},"midi":60,"velocity":1,"rattleSource":"WARM_WIRE"}]"""
    }
}
