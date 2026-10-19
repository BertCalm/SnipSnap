package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [Patches.decode], the door every engine's saved sound comes in through, on the macros field it reads for all of them. */
class PatchesTest {

    /** A THUMP kick whose JSON carries [macros] as written, or no macros field at all when it is null. */
    private fun kick(macros: String?): Patch {
        val field = if (macros == null) "" else ""","macros":$macros"""
        return Patches.fromJsonText("""{"engine":"THUMP","version":1,"name":"K","voice":"KICK"$field}""")
    }

    @Test
    fun `a macros field that is not an object is refused by name`() {
        for ((json, type) in listOf("[]" to "Arr", "[0.5]" to "Arr", "\"x\"" to "Str", "0.5" to "Num", "true" to "Bool")) {
            val refused = assertFailsWith<JsonException>("macros $json: Patches.decode must refuse a macros field that is not an object") {
                kick(json)
            }
            assertEquals("patch macros must be an object, got $type", refused.message)
        }
    }

    @Test
    fun `an absent or null macros field is a partial map with nothing in it`() {
        assertEquals(emptyMap(), kick(null).macros, "an absent macros field")
        assertEquals(emptyMap(), kick("null").macros, "a null macros field")
        assertEquals(mapOf("PUNCH" to 0.75f), kick("""{"PUNCH":0.75}""").macros, "a partial object")
    }

    /**
     * One saved sound of every engine: its first factory preset, or for the engines with no roster
     * ([PatchTypes.ROSTERLESS]) one built here, so each carries the fields its own engine requires.
     * A prototype held outside [Presets] ([PatchTypes.PROTOTYPES]) sends its first provisional recipe;
     * VENT also sends a legacy model-1 comparison beside its model-2 factory entry.
     */
    private fun onePatchPerEngine(): List<Patch> = Presets.all().distinctBy { it.engine } + listOf(
        SnapPatch("Door", SnapVoice.ORBIT, emptyMap(), IntArray(Snap.TABLE_SIZE) { (it * 255) / (Snap.TABLE_SIZE - 1) }),
        TerraPatch("Door", TerraVoice.COMPOUND_MEMBRANE, emptyMap()),
        SilkPatch("Door", SilkVoice.OUD, emptyMap()),
        GyrePatch("Door", GyreVoice.FLICK, emptyMap()),
        MagnetPatch("Door", MagnetVoice.JANGLE, emptyMap()),
        VentPresets.all().first(),
        OverlapPresets.all().first(),
        WickPresets.all().first(),
        NacrePresets.all().first(),
        ChamberPresets.all().first(),
    )

    @Test
    fun `every engine's saved sound comes through the macros door`() {
        val patches = onePatchPerEngine()
        assertEquals(PatchTypes.engines, patches.map { it.engine }.toSet(), "every engine sends one saved sound through the door")
        for (patch in patches) {
            val fields = patch.toJsonValue().entries
            if (patch is OpenSkyPatch) {
                // OPENSKY saves a complete model-3 control snapshot rather
                // than inheriting voice defaults when a field is missing.
                assertFailsWith<JsonException>("OPENSKY requires its complete saved macro snapshot") {
                    Patches.fromJsonValue(JsonValue.Obj(fields - "macros"))
                }
            } else {
                val absent = Patches.fromJsonValue(JsonValue.Obj(fields - "macros"))
                assertEquals(emptyMap(), absent.macros, "${patch.engine}: an absent macros field")
            }
            val malformed = JsonValue.Obj(fields + ("macros" to JsonValue.Arr(emptyList())))
            val refused = assertFailsWith<JsonException>("${patch.engine}: a macros array must be refused, not read as no macros") {
                Patches.fromJsonValue(malformed)
            }
            assertTrue("macros must be an object" in refused.message.orEmpty(), "${patch.engine} refused for another reason: ${refused.message}")
        }
    }
}
