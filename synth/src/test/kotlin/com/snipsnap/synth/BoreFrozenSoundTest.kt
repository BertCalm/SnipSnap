package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * BORE's sound, frozen before round two touched the engine (docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md,
 * "R2: OVERBLOW, as built"). Every hash below was taken from the code at `7bf13b28`, before OVERBLOW existed: the SHA-256 of
 * the rendered float samples (32-bit, little-endian, so a flipped bit or a changed length counts) of every factory preset, of
 * each voice at its defaults, and of each voice's LOOP at HOLD's top step. Round two adds OVERBLOW to FLUTE behind a branch
 * that a knob at 0 never enters; this is the proof that it does not, sample for sample, for every sound BORE already ships and
 * every saved recipe that names no OVERBLOW. `ObbligatoCompatibilityTest` freezes the same players from OBBLIGATO's side.
 *
 * SCRAMBLE is frozen too, by value: the five macros BORE had before round two, rolled from fixed seeds, must come out exactly as
 * they did, so a new macro cannot shift the random stream the old ones are drawn from.
 *
 * Nothing here may be re-pointed to make a later change pass. A change that is meant to move BORE's sound replaces a hash on
 * purpose, says so in its commit, and names the render that moved.
 */
class BoreFrozenSoundTest {

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(samples)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    /** Every render this file freezes, by a stable name. */
    private fun renders(): Map<String, () -> FloatArray> {
        val out = LinkedHashMap<String, () -> FloatArray>()
        for (preset in BorePresets.all()) out["${preset.voice}/${preset.name}"] = { preset.render().samples }
        for (voice in listOf(BoreVoice.FLUTE, BoreVoice.SAX)) {
            out["$voice/defaults"] = { Bore.render(voice).samples }
            out["$voice/LOOP"] = { Bore.render(voice, mapOf("HOLD" to 1f)).samples }
        }
        return out
    }

    /** The five macros BORE had before round two, as SCRAMBLE rolls them from [seed]: exact float text, in knob order. */
    private fun rolled(voice: BoreVoice, seed: Int, temperature: Float = 0.35f): String {
        val m = Bore.scramble(voice, Random(seed), temperature)
        return R1_MACROS.joinToString(";") { "$it=${m.getValue(it)}" }
    }

    @Test
    fun `every preset, each voice's defaults and each voice's LOOP render as they did before round two, bit for bit`() {
        val actual = renders().mapValues { (_, render) -> hash(render()) }
        if (FROZEN.isEmpty()) {
            println("BORE frozen hashes, to paste:")
            for ((name, h) in actual) println("        \"$name\" to \"$h\",")
        }
        assertEquals(actual.keys, FROZEN.keys, "the frozen set names exactly the renders it covers")
        val moved = actual.filter { (name, h) -> FROZEN[name] != h }.keys
        assertTrue(moved.isEmpty(), "these BORE renders changed: $moved")
    }

    @Test
    fun `scramble rolls the five original macros exactly as it did before round two`() {
        val actual = SCRAMBLE_CASES.associate { (voice, seed, t) -> "$voice/$seed/$t" to rolled(voice, seed, t) }
        if (FROZEN_SCRAMBLE.isEmpty()) {
            println("BORE frozen scramble, to paste:")
            for ((name, v) in actual) println("        \"$name\" to \"$v\",")
        }
        assertEquals(FROZEN_SCRAMBLE, actual)
    }

    @Test
    fun `the comparison can fail - a nudge of one macro by a thousandth changes the hash`() {
        val base = hash(Bore.render(BoreVoice.FLUTE).samples)
        val nudged = hash(Bore.render(BoreVoice.FLUTE, mapOf("LIP" to 0.501f)).samples)
        assertNotEquals(base, nudged, "a hash that does not see a thousandth of LIP could not see OVERBLOW leak either")
        assertEquals(base, FROZEN["FLUTE/defaults"], "the control reads the same render the frozen set does")
    }

    private companion object {
        val R1_MACROS = listOf("TUNE", "BREATH", "LIP", "CHIFF", "HOLD")

        val SCRAMBLE_CASES = listOf(
            Triple(BoreVoice.FLUTE, 42, 0.35f), Triple(BoreVoice.FLUTE, 7, 0.35f), Triple(BoreVoice.FLUTE, 2026, 1f),
            Triple(BoreVoice.SAX, 42, 0.35f), Triple(BoreVoice.SAX, 7, 0.35f), Triple(BoreVoice.SAX, 2026, 1f),
        )

        /** Taken from `7bf13b28`, before OVERBLOW. */
        val FROZEN: Map<String, String> = mapOf(
        "FLUTE/BREATHY" to "5a7e644b5f3bf9c308846895f43ff130d35a1e88ad257393d93e487472a0723b",
        "FLUTE/CLEAN TONE" to "b87c91947c766cb59a2b364179a488c1fa41725fdcbb05f5cc662a21bd1200db",
        "FLUTE/SOFT SWELL" to "8d760a21f8be687f3f818098aa19a9bb3d9b4aea1377976dcebb68a412b63f02",
        "FLUTE/HARD TONGUE" to "5a14c4e5ee50afe283ca1bf65d0bf3308907435f56946455cd88c1a1d50f8a1e",
        "FLUTE/LOW REGISTER" to "8c88945a0515f06480bd8adfc139438f8efb62911b522c2ac660e0bc2e1d7163",
        "FLUTE/HIGH AIR" to "284ae4a08977f53f8fa066bf16f333a3e293d7c0302538f41c921dec259f67f4",
        "FLUTE/LONG NOTE" to "605de2622dcb35240154b8ca0e93d6bdad35581186b085d31ef090585d85e4f6",
        "FLUTE/STEADY LOOP" to "cec81b49155c471b477e1e242d91bf00a5a8fe9c6a1eac58bdc4fb39760d96fb",
        "SAX/LOW HONK" to "067f84db82424d7cd514f73f6e7e080da56011aaa8a5130b466bae1e959afafe",
        "SAX/SMOOTH" to "113a2e9bc722530b8eb68de8d8c53ea011aae3af7084f51b925df5bd2494017d",
        "SAX/BITE" to "5a3bf6b11baf472b694e94555fe37703948948ef7573b376b88d13d49cdc14e4",
        "SAX/AIRY REED" to "7f6cd837d42f3586f5170b3d4abbb075b4f725285fdd4d6d60437f5ec1babd05",
        "SAX/HIGH STAB" to "24f7be6a6e6a2f40ec7e7da1fc34cb96f32dd3cc7f023a973cefa447206371b0",
        "SAX/PAD REED" to "bd05e072d841ee3a65fa9c84886b32c0a6e834e6b1156fb451413c68876633b7",
        "SAX/SOLO LOOP" to "eea848f82ee1141266c8a59aee05e058fee041e760eed7a07f22d0532d82cffd",
        "SAX/GROWL" to "704af99cb533331c76e67d7ac890b6c1e3331c138fd9917a25869b01b4e0ef9d",
        "FLUTE/defaults" to "9c81a1a80ff106bc8300d28dce87f7c7132c5975c4139a4948bf48fa11d73a1e",
        "FLUTE/LOOP" to "bed538d6d5493b71c963ab9d7f34cbd2cdcb0c8a57f12e92f1c690b246be72e1",
        "SAX/defaults" to "2f7bc8c45af700cb631b789109d716ada53f45d5e33118c5c943e91f8cd98262",
        "SAX/LOOP" to "2739fc19c269c9c3016da9514b67c06d36d3d4fc7f14fcccffd3628ac8445c97",
        )

        /** Taken from `7bf13b28`, before OVERBLOW. */
        val FROZEN_SCRAMBLE: Map<String, String> = mapOf(
        "FLUTE/42/0.35" to "TUNE=0.74505186;BREATH=0.17735936;LIP=0.7470658;CHIFF=0.5910298;HOLD=0.39370733",
        "FLUTE/7/0.35" to "TUNE=0.47510216;BREATH=0.37905073;LIP=0.8955877;CHIFF=0.9424366;HOLD=0.5596659",
        "FLUTE/2026/1.0" to "TUNE=0.8482453;BREATH=0.5148087;LIP=0.21908253;CHIFF=0.9595698;HOLD=0.5336941",
        "SAX/42/0.35" to "TUNE=0.64505184;BREATH=0.37735933;LIP=0.84706575;CHIFF=0.6910298;HOLD=0.39370733",
        "SAX/7/0.35" to "TUNE=0.09510215;BREATH=0.42905068;LIP=0.39558765;CHIFF=0.7924366;HOLD=0.0",
        "SAX/2026/1.0" to "TUNE=0.8482453;BREATH=0.5148087;LIP=0.21908253;CHIFF=0.9595698;HOLD=0.5336941",
        )
    }
}
