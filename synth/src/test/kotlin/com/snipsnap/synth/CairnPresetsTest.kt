package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CAIRN's factory roster, held to the identity, round-trip, names, blocklist and spread
 * contract every `<Engine>PresetsTest` holds, plus the roster's own claims:
 * - SING's four are the owner's kept Wave 3 recipes, byte for byte (their accepted PCM24
 *   anchors are checked through [CairnAcceptedSingContext]).
 * - Every other preset pins the model its voice was built under.
 * - KNOCK, ANSWER and TUMBLE render the same at that model as at today's.
 *
 * This proves the roster is sound and that the kept recipes are unchanged. It cannot prove
 * the twenty new settings are good; they are heard on the CAIRN starter.
 */
class CairnPresetsTest {

    private companion object {
        /** The frozen roster: names in order, per voice. The starter and the phone ask for these by name. */
        val names = mapOf(
            CairnVoice.KNOCK to listOf("KNOCK", "KNOCK HEAVY", "KNOCK DRY", "KNOCK RING"),
            CairnVoice.ANSWER to listOf("ANSWER", "ANSWER LIGHT", "ANSWER WIDE", "ANSWER LOOP"),
            CairnVoice.TUMBLE to listOf("TUMBLE", "TUMBLE LONG", "TUMBLE PEBBLE", "TUMBLE STEEP"),
            CairnVoice.SING to listOf("SING EARLIER", "SING MOVING", "SING SMOOTH", "SING GROOVED"),
            CairnVoice.GRIND to listOf("GRIND", "GRIND SLOW", "GRIND COARSE", "GRIND LOOP"),
            CairnVoice.VESSEL to listOf("VESSEL", "VESSEL DEEP", "VESSEL SMALL", "VESSEL LOOP"),
        )

        /** Which kept Wave 3 recipe each SING preset is. */
        val keptSing = mapOf(
            "SING EARLIER" to "cairn-before-60",
            "SING MOVING" to "cairn-after-60",
            "SING SMOOTH" to "cairn-groove-0",
            "SING GROOVED" to "cairn-groove-100",
        )

        val models = mapOf(
            CairnVoice.KNOCK to 5, CairnVoice.ANSWER to 5, CairnVoice.TUMBLE to 5,
            CairnVoice.GRIND to 6, CairnVoice.VESSEL to 7,
        )

        /** The YOUR PRESET name limit (`UserPresets.MAX_NAME` in `:shell`), so a factory name can be saved as is. */
        const val MAX_NAME = 14
    }

    @Test
    fun `the roster is four named presets per voice in a frozen order`() {
        for (voice in CairnVoice.entries) {
            assertEquals(names.getValue(voice), CairnPresets.forVoice(voice).map { it.name }, "$voice roster")
        }
        assertEquals(24, CairnPresets.all().size)
        assertEquals(CairnPresets.all(), Presets.all().filter { it.engine == CairnPatch.ENGINE },
            "the shared roster files CAIRN's table, nothing more")
        for (voice in CairnVoice.entries) {
            assertEquals(CairnPresets.forVoice(voice), Presets.forVoice(CairnPatch.ENGINE, voice.name))
        }
    }

    @Test
    fun `names are short caps, unique and free of trademarks`() {
        val all = CairnPresets.all().map { it.name }
        assertEquals(all.size, all.toSet().size, "duplicate names: $all")
        for (name in all) {
            assertTrue(name.length <= MAX_NAME, "$name is over the $MAX_NAME-letter YOUR PRESET limit")
            assertEquals(name.uppercase(), name, "$name is not caps")
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(name), "$name reads as a real machine")
        }
    }

    @Test
    fun `the four SING presets are the kept Wave 3 recipes and replay their accepted anchors`() {
        for ((name, id) in keptSing) {
            val preset = CairnPresets.all().single { it.name == name }
            val source = checkNotNull(CairnAcceptedSingSources.byId(id))
            val kept = source.patch()
            assertEquals(kept, preset.copy(name = kept.name), "$name changes nothing but the name of $id")
            assertEquals(kept.macros.mapValues { it.value.toRawBits() }, preset.macros.mapValues { it.value.toRawBits() })
            // fromNative refuses any render whose cycle or full buffer differs from the accepted PCM24.
            val native = Cairn.inspect(preset.voice, preset.macros, preset.midi, preset.velocity,
                preset.startAtContact, model = preset.model)
            CairnAcceptedSingContext.fromNative(source, native)
            assertTrue(preset.render().samples.contentEquals(native.samples), "$name renders through its patch as accepted")
        }
    }

    @Test
    fun `new settings pin their voice's model, sit at C4 and full touch`() {
        for (preset in CairnPresets.all().filter { it.voice != CairnVoice.SING }) {
            assertEquals(models.getValue(preset.voice), preset.model, "${preset.name} model")
            assertEquals(Cairn.DEFAULT_MIDI, preset.midi, "${preset.name} root")
            assertEquals(1f, preset.velocity, "${preset.name} touch")
            assertTrue(preset.startAtContact)
            assertEquals(Cairn.macrosFor(preset.voice).map { it.name }.toSet(), preset.macros.keys, "${preset.name} names every control")
        }
    }

    @Test
    fun `KNOCK ANSWER and TUMBLE presets render the same at their pinned model as at today's`() {
        for (preset in CairnPresets.all().filter { it.voice in setOf(CairnVoice.KNOCK, CairnVoice.ANSWER, CairnVoice.TUMBLE) }) {
            val pinned = preset.render().samples
            val today = preset.copy(model = Cairn.MODEL_VERSION).render().samples
            assertTrue(pinned.contentEquals(today), "${preset.name}: model ${preset.model} and ${Cairn.MODEL_VERSION} differ")
        }
    }

    @Test
    fun `every preset round-trips its JSON to the same bytes and renders bounded audio`() {
        for (preset in CairnPresets.all()) {
            val restored = CairnPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            val samples = preset.render().samples
            assertTrue(samples.isNotEmpty() && samples.all { it.isFinite() && it in -1f..1f }, "${preset.name} renders bounded audio")
            assertTrue(samples.contentEquals(restored.render().samples), "${preset.name}: the restored recipe renders different bytes")
            assertTrue(samples.any { it != 0f }, "${preset.name} is silent")
        }
    }

    @Test
    fun `the loop presets file as loops and differ from their voice's other presets`() {
        for (preset in CairnPresets.all()) {
            if (Cairn.isLoop(preset.macros)) {
                assertEquals(DrumClass.LOOP, Cairn.drumClassFor(preset.voice, preset.macros, preset.velocity,
                    preset.startAtContact, preset.model), "${preset.name} is a loop")
            }
        }
        for (voice in CairnVoice.entries) {
            val roster = CairnPresets.forVoice(voice)
            // SING EARLIER and SING MOVING share their controls and differ by saved model, as kept.
            for (i in roster.indices) for (j in i + 1 until roster.size) {
                assertTrue(PresetTestSupport.rmsDistance(roster[i].macros, roster[j].macros) > 0f || roster[i].model != roster[j].model,
                    "${roster[i].name} and ${roster[j].name} are the same recipe")
            }
        }
    }
}
