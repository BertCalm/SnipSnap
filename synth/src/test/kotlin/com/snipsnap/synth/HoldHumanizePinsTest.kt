package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ARCO and BORE as they ship since 2026-11-20, pinned: the SHA-256 of the rendered float samples (32-bit, little-endian) of every
 * factory preset, each voice's default note and each voice's default LOOP, and for ARCO a three second note.
 *
 * The owner judged every ARCO and BORE clip that day with "Holds are unnatural, they need more humanization", and SAX's presets with
 * "Opening is a bit harsh for most." So these sounds moved on purpose: a held note carries a player ([Arco.HUMAN], [Bore.HUMAN]) and
 * SAX opens more gently ([Bore.openingFor]). The hashes that were here before still hold with both switched off
 * (`BoreFrozenSoundTest`, `ArcoBuzzTest`, `ArcoFrozenR1cTest`); these are the ones a later change must not move by accident.
 * The owner heard them and kept them, with no notes, on 2026-11-20
 * (rounds fix-arco-1, BP1 to BP4, and fix-bore-1, BQ1 to BQ4), rendered from this code.
 *
 * A change that is meant to move one replaces its hash on purpose, says so in its commit, and names the render that moved.
 */
class HoldHumanizePinsTest {

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(samples)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    private fun bore(): Map<String, () -> FloatArray> {
        val out = LinkedHashMap<String, () -> FloatArray>()
        for (p in BorePresets.all()) out["${p.voice}/${p.name}"] = { p.render().samples }
        for (v in BoreVoice.entries) {
            out["$v/defaults"] = { Bore.render(v).samples }
            out["$v/LOOP"] = { Bore.render(v, mapOf("HOLD" to 1f)).samples }
        }
        return out
    }

    private fun arco(): Map<String, () -> FloatArray> {
        val out = LinkedHashMap<String, () -> FloatArray>()
        for (p in ArcoPresets.all()) out["${p.voice} ${p.name}"] = { p.render().samples }
        for (v in ArcoVoice.entries) {
            out["$v DEFAULT NOTE"] = { Arco.render(v, Arco.defaults(v)).samples }
            out["$v DEFAULT LOOP"] = { Arco.render(v, Arco.defaults(v) + ("HOLD" to 1f)).samples }
            out["$v HELD NOTE"] = { Arco.render(v, Arco.defaults(v) + ("HOLD" to Arco.holdFor(3f))).samples }
        }
        return out
    }

    private fun check(engine: String, renders: Map<String, () -> FloatArray>, pinned: Map<String, String>) {
        val actual = renders.mapValues { (_, r) -> hash(r()) }
        if (pinned.isEmpty()) {
            println("$engine shipped hashes, to paste:")
            for ((name, h) in actual) println("        \"$name\" to \"$h\",")
        }
        assertEquals(actual.keys, pinned.keys, "$engine: the pinned set names exactly the renders it covers")
        val moved = actual.filter { (name, h) -> pinned[name] != h }.keys
        assertTrue(moved.isEmpty(), "these $engine renders changed: $moved")
    }

    @Test
    fun `BORE as shipped - every preset, each voice's defaults and each voice's LOOP, bit for bit`() = check("BORE", bore(), BORE)

    @Test
    fun `ARCO as shipped - every preset, each voice's default note, default LOOP and a three second note, bit for bit`() = check("ARCO", arco(), ARCO)

    @Test
    fun `the comparison can fail, and the player and the opening are what moved these`() {
        assertNotEquals(BORE["FLUTE/defaults"], hash(Bore.render(BoreVoice.FLUTE, mapOf("LIP" to 0.501f)).samples), "a thousandth of LIP is seen")
        // The player: a three second bow and a held breath are not the renders they were with it off.
        for (v in ArcoVoice.entries) {
            val m = Arco.defaults(v) + ("HOLD" to Arco.holdFor(3f))
            assertNotEquals(ARCO["$v HELD NOTE"], hash(Arco.render(v, m, Arco.BUZZ_LAW, human = Humanize.OFF).samples), "ARCO $v: the player moved nothing")
        }
        for (v in BoreVoice.entries) {
            assertNotEquals(BORE["$v/LOOP"], hash(Bore.render(v, mapOf("HOLD" to 1f), Humanize.OFF, Bore.openingFor(v)).samples), "BORE $v: the player moved nothing")
        }
        // The opening: SAX's default note with the player on and the old opening is not the shipped note.
        assertNotEquals(BORE["SAX/defaults"], hash(Bore.render(BoreVoice.SAX, emptyMap(), Bore.HUMAN, Bore.OPENING_AS_HEARD).samples), "SAX: the opening moved nothing")
    }

    private companion object {
        /** Taken on 2026-11-20 from the commit that added the player and SAX's softened opening. */
        val BORE: Map<String, String> = mapOf(
        "FLUTE/BREATHY" to "df4e6c8ebf35698f1b0853e60fb88a071b7fc34b74a94a0ca06fe39d915f90da",
        "FLUTE/CLEAN TONE" to "47cf3236f17c71d7ee58a1ac5d60abfbd30241c652f04e9b000befabb5362879",
        "FLUTE/SOFT SWELL" to "3ff66c72100903c2735735c46c0e33cbc6e6f0e3d41feae0e0acabce2598611f",
        "FLUTE/HARD TONGUE" to "7602b9bda70887a41c0465c3dcc7a5da28a46c7c384ff29b9b1885d4b4a33cde",
        "FLUTE/LOW REGISTER" to "78df260232acd899b00f0dcc50d5da5fbabab20ca409897ff132388f4ef7c8e7",
        "FLUTE/HIGH AIR" to "331c8de487708bab9993deea0280f4ba2e140bab272bf837827cf62c7070e212",
        "FLUTE/LONG NOTE" to "d8f8e6e7ca2207124ec35fbfc3baf3c86ba63081227e8534d56b93b1d472837f",
        "FLUTE/STEADY LOOP" to "b4976fbadac8715690fbc114e36dfd6791b69ee4d1e11a4d730c9f406628f341",
        "SAX/LOW HONK" to "faccc27b41f68a89559da41daa71de3ff073526de9be5d700ccc086c77f512eb",
        "SAX/SMOOTH" to "2afd9babe37573737f15d4fad0fc23746a5160996ed84396a650941d49ff2c79",
        "SAX/BITE" to "7079cc7ea26b1dfdd03859e90f02a8343a2ded5bd145c9e70ce744b8a5d90b3d",
        "SAX/AIRY REED" to "9c0d655661d96fa4c20cc36841106b739c77d76b8ed0633b0b7eb87d119eef89",
        "SAX/HIGH STAB" to "44b261c2f01278c61a313b5e51f63699706962043f8eb5c53da11af2ab983bf9",
        "SAX/PAD REED" to "1c78fb0671111bbb56ce04e5314cb0c8257dc494801844e324c2a1e6c5fae4cc",
        "SAX/SOLO LOOP" to "2da8e9a0972110f6256620252c2672550bb5389a6abacf0f3cbbf52edad43799",
        "SAX/GROWL" to "3138843e0f5dbb24b8d94cc2b31f35e7c779ef61e52275db201d678ba4b6fd7d",
        "FLUTE/defaults" to "e57125916dafbf1f37424b913e0334b325129159495b42e39345f872fb17afd6",
        "FLUTE/LOOP" to "ed3c0096c60b8f1b9572ed767fec9c089f504d571ab9a221b372aa873d088867",
        "SAX/defaults" to "d0fabc4d35ba84a98265453d9112160243840de659188e7b189234660a312ff6",
        "SAX/LOOP" to "0cd281a49998b45552fcecdcc74678e199c3b29c9f4951f54483f723db7e1c28",
        )

        /** Taken on 2026-11-20 from the commit that added the player. Notes with 0.6 s of bow or less carry no player and kept their old hashes. */
        val ARCO: Map<String, String> = mapOf(
        "CELLO SLOW BOW" to "e6d49856be7dc10c2fa95491212c8796e5d3bc8cc944d23d6c2741b9bf62e3b0",
        "CELLO SHORT STAB" to "2294f4312feb5c38b8626969a289f0b0c98c2a1a110bd1654a2861a1db889659",
        "CELLO DEEP PEDAL" to "beb75c6dfedf3af79bad99f21333c65b24e209887be5cd81a139122e1b035392",
        "CELLO GRIT BOW" to "83120befc0bbac5fa1257d0550ed647a19e7ba0a268fe720ec8e8e5fc20c3496",
        "CELLO DRY SCRAPE" to "b1d02fab35227615ea448ce69058b152b32a6c22c5d8085b1a7fd865301846d5",
        "CELLO CINEMA LOW" to "7b04d6002ce21868cb81591411875dfe3ca02365f817892ce023f1fdaa064fad",
        "CELLO HORSEHAIR" to "5cce5581e7ad99d632f8e00a87ece96e7f043d4567921dececf34ad6bee3c11c",
        "CELLO ENDLESS DRAW" to "428c86531c19dd0d90883ab403429b1bd7ce37a4fbb7b81bd94a717ea2b0ce57",
        "ERHU NASAL LINE" to "37324c95dcb815ae50b867b56701b552197e7f32098a5637f496c8959e830c71",
        "ERHU MOON FIDDLE" to "f86bec6264acd3d0c44f12a61ebf83b3b7ac20d997746607c099c4304978fa4b",
        "ERHU THIN SCRAPE" to "e42b34ee3195c8a5380e027af5461b606b75476a596ef2af03ee47cbe73c110c",
        "ERHU HIGH CRY" to "15522b3b70988022d8469f06534fa164ef29005be86115306412b9d87c707746",
        "ERHU SLOW CRY" to "c8aff8af3ea6e8dcbd595a12101b5e5856d4063a7ea0be207c11186133c4e939",
        "ERHU TEA HOUSE" to "a88be0c63c0fc596c55309706d4f80ee90e7541019b39de85644732ec572514c",
        "ERHU TWO STRING" to "c2edb050d21574e98aab2942bdb9f0dc8f974771c4fb015fc6a37f907138dbc7",
        "ERHU ENDLESS CRY" to "84a871fa4e333fe2164eeb2519f59bac14590ef8a9d0d396ff4b3d15c4d29219",
        "SARANGI LOW SA" to "98099b9a566e4e46c27065228772cb6691df44bae3a6d0ae16288675cd4470fe",
        "SARANGI SILVER HALO" to "66a2363ec6b755cd9d813b4214437905dd2c41fcefe9cba18ac608fcbe311fb9",
        "SARANGI GUT ACCENT" to "b6c1fc76d91ffbcba8484eb461552bb5fe731eae020bb778a8a5e6aa28af05fc",
        "SARANGI HIGH LAMENT" to "bd92ab1ed3b5ba88de023dd1c2382e31ec2abe828dbb28a853cf53f2dee87df9",
        "SARANGI ENDLESS SA" to "4a4ea72c617554c787ba6f37c48097318d956eeaa89ae2e8604a5b0f34de0304",
        "SARANGI ENDLESS PA" to "c072162a1ba1ac65a10d869d0636213b8b648749c1134feeb3ee93d8b0190672",
        "CELLO DEFAULT NOTE" to "6be6f46fb1535b39189a456c879c27f9011b561ab1542dc72b0cafcf97220606",
        "CELLO DEFAULT LOOP" to "17e7b3f0724c363e5041165daafb9e8803969b4cb7df983304316fdaa858e6f3",
        "CELLO HELD NOTE" to "2f3a4fefa7fd8b95c31836f4408536370e1b93ab77e9b1136fdba8126ee1fd26",
        "ERHU DEFAULT NOTE" to "ab369c77a70a8c0db040b9630e5294ad4bb1db4e356eede78eb4d878301d11b8",
        "ERHU DEFAULT LOOP" to "6cc7dd22d67a4f8f0708963908252013bde9497421f0206d92084663ff111881",
        "ERHU HELD NOTE" to "9c9be0e2d90cd353b5dfe8b839ee40fc039a622f2867c34e1f42b6d3fc103b23",
        "SARANGI DEFAULT NOTE" to "6986f4e4f2048774b22e1e6f0714bdac9e566208cadaa9af09fe43a68a46658b",
        "SARANGI DEFAULT LOOP" to "72ac9e6789b987cf896871d1ae444774f38cbb18bd2b07fa6ebd2f9d37fad402",
        "SARANGI HELD NOTE" to "529522443fb20376b672265f9eb2cf0efb1c22fdca59cea893d6be679137c939",
        )
    }
}
