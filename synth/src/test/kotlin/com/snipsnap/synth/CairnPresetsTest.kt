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
 * This proves the roster is sound and that the kept recipes are unchanged. The owner heard
 * and kept all 24 and the CAIRN starter in round cairn-presets-1; the last two tests pin
 * those renders.
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

    /**
     * The owner kept all 24 presets and the CAIRN starter in round cairn-presets-1
     * (P1-P6, 14 Nov 2026). These are the renders those six clips were built from,
     * byte for byte: every factory preset at its saved note and touch, and the
     * starter's sixteen pads, which are the same renders in starter order.
     */
    private val keptRenders = mapOf(
            "KNOCK" to "9b0b0733a1f1af65a144cec05e6de0af42e52aabeebb87803bef214c6ae276b6",
            "KNOCK HEAVY" to "677f0fc003fa35216ddd47511a8fa0d35f330d2c9256806cbdb3f4137a800c04",
            "KNOCK DRY" to "418be3c4f1e40780e783688fb419e1935a4cefbfc3fd38dc196ea31cb55755cf",
            "KNOCK RING" to "4c7ca43bfae2a537946cc333cc41ed1e51cabdfeb4f213b3178ae944a7e6a7fb",
            "ANSWER" to "d739590e83706827fe3a5f9e3bf0bf29b6369fbd3e3e5245488e00e2ccca5878",
            "ANSWER LIGHT" to "f098567f45814ec5aa9b0b6820b66e20ba8da8e1b268c5e5d45e4de3def4d293",
            "ANSWER WIDE" to "8aff59225aeeea6af46ebfa9e9be030a3edf67473acb4cb8bbd560a993508714",
            "ANSWER LOOP" to "ea3e2bde98e578a59e66f24de3ae25bc646ab2ef4e1041ddd8db8ee1d2a017b1",
            "TUMBLE" to "a665757ef4a432a988924d51425e75bc73b54d7baacfc5979afa62f263d61a8d",
            "TUMBLE LONG" to "63bf2f67c17c4c4829b1eb504ea623207fe1a76f4f754bc3fc61d6e1aa86d950",
            "TUMBLE PEBBLE" to "d474ef3dc3bd8025fddfbf935c976409f411766079ce181a71cfab9985a28314",
            "TUMBLE STEEP" to "b218f96a51dbb29e5d852795dd85c0323bb4c96054de0579c4333cce48c383d7",
            "SING EARLIER" to "f38c533057f288bec6a27bb07b92c4da33ec4415256a8be6a4210ab08c10ccea",
            "SING MOVING" to "e7392e55b6df7317c1be628edd73cf212eae7a5f82d6bbe2e38110ccfd8faa07",
            "SING SMOOTH" to "8301710a4fd9d9413c372ac2fba27abf2dc35fa8814f996ba1b7890de1806728",
            "SING GROOVED" to "62aac5d77c98df736917139597502950cd9b31ca4bffcaa6f55d401727e22daf",
            "GRIND" to "3c09e0ef399d5db060d4aba219a27da7cf84fe0f2c292a25b92c543e1a867a20",
            "GRIND SLOW" to "a858faf1c5d4e94de8e17c2c5da5b0e1e88023dca9dd0198d3598015f64e52c8",
            "GRIND COARSE" to "0981ce2c365faeea77e96a04b3608bfef6bffe14fd8d834e9271d53a4a75dcca",
            "GRIND LOOP" to "3ff2a2fbab71f7934c454c0d0c22c2bba5cb8c6b224ade173cfadaa4e710a79a",
            "VESSEL" to "1c67b83607ca3e149759e8486888883add6671c8cdae3f9e9d568a65ae394185",
            "VESSEL DEEP" to "622613f25b14298c2037edf6c9353cc920b90bcb5baf2bc0b38d6959ef2419dd",
            "VESSEL SMALL" to "9e5942b1d127bd26d2dc2c028c767ab23d57b94e57308cf16a1540e39658e159",
            "VESSEL LOOP" to "622bfe229f608c553f3a5da83d8f6c0f87c409509a6848e6d6fec44ace2bc337",
    )

    private fun floatSha(s: FloatArray): String {
        val b = java.nio.ByteBuffer.allocate(s.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (x in s) b.putFloat(x)
        return java.security.MessageDigest.getInstance("SHA-256").digest(b.array())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test
    fun `the 24 presets the owner kept render byte-identically`() {
        assertEquals(keptRenders.keys.toList(), CairnPresets.all().map { it.name })
        for (patch in CairnPresets.all()) {
            val snip = PadRecipe(patch).render()
            assertEquals(1, snip.channels, "${patch.name} is mono")
            assertEquals(keptRenders.getValue(patch.name), floatSha(snip.samples), "kept preset ${patch.name} moved")
        }
    }

    @Test
    fun `the CAIRN starter the owner kept plays the kept renders in order`() {
        val pads = SynthKits.cairn()
        assertEquals(listOf("KNOCK", "KNOCK HEAVY", "KNOCK RING", "ANSWER", "ANSWER LIGHT", "ANSWER WIDE", "TUMBLE", "TUMBLE LONG", "TUMBLE PEBBLE", "SING EARLIER", "SING MOVING", "GRIND", "GRIND COARSE", "VESSEL", "VESSEL DEEP", "VESSEL SMALL"), pads.map { it!!.displayName })
        for (pad in pads) assertEquals(keptRenders.getValue(pad!!.displayName!!), floatSha(pad.snip.samples),
            "starter pad ${pad.displayName} moved")
    }
}
