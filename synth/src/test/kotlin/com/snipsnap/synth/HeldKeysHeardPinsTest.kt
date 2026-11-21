package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The held keys of the nine voices that joined ARCO and BORE on 2026-11-20 (WHEEL, BAR, GLASS, VIOL; HOLLOW, SHAWM, SHAKU, PANPIPE,
 * VESSEL), pinned as the owner heard them: rounds held-arco-1 (HA1 to HA4) and held-bore-1 (HA5 to HA9), 2026-11-21, all KEEP, no
 * notes. Each clip played the hard key held 7 s, the soft key held 5 s (both through the keys instrument's player), then the LOOP
 * at HOLD 1 (pinned in [HoldHumanizePinsTest]). The key was middle C, SHAKU's C5 (its range starts at D4).
 *
 * Each entry is the zone the clip played: [ArcoKeys.pad] or [BoreKeys.padLayer] at that key in that layer, start shaped and loop
 * marked, as the SHA-256 of its float samples (32-bit, little-endian) and its loop start. For WHEEL, BAR, SHAWM and SHAKU the key
 * sits between zone roots (the instrument plays the root under it repitched); the zone maker and its code are the same.
 *
 * A change that is meant to move one replaces its hash on purpose, says so in its commit, and names the zone that moved.
 */
class HeldKeysHeardPinsTest {

    private fun hash(n: KeyNote): String {
        val bytes = ByteBuffer.allocate(n.snip.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(n.snip.samples)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) } + " loop " + n.loopStartFrame
    }

    @Test
    fun `the held keys the owner heard and kept render as heard`() {
        val jobs = LinkedHashMap<String, () -> KeyNote>()
        for ((v, k) in listOf(ArcoVoice.WHEEL to 60, ArcoVoice.BAR to 60, ArcoVoice.GLASS to 60, ArcoVoice.VIOL to 60))
            for (l in listOf(ArcoKeys.Layer.HARD, ArcoKeys.Layer.SOFT)) jobs["ARCO $v $k $l"] = { ArcoKeys.pad(v, Arco.defaults(v), k, l) }
        for ((v, k) in listOf(BoreVoice.HOLLOW to 60, BoreVoice.SHAWM to 60, BoreVoice.SHAKU to 72, BoreVoice.PANPIPE to 60, BoreVoice.VESSEL to 60)) {
            val (soft, hard) = BoreKeys.layerBreaths(v, Bore.defaults(v))
            jobs["BORE $v $k HARD"] = { BoreKeys.padLayer(v, Bore.defaults(v), k, hard) }
            jobs["BORE $v $k SOFT"] = { BoreKeys.padLayer(v, Bore.defaults(v), k, soft) }
        }
        val actual = jobs.entries.toList().parallelStream().map { (name, render) -> name to hash(render()) }.toList().toMap()
        val moved = HEARD.filter { (name, h) -> actual[name] != h }.keys
        assertEquals(HEARD.keys, actual.keys, "the pinned set names exactly the zones it covers")
        assertEquals(emptySet(), moved, "held zones that no longer render as heard: " + moved.joinToString { "$it = ${actual[it]}" })
    }

    private companion object {
        val HEARD = mapOf(
            "ARCO WHEEL 60 HARD" to "f2f25c45945c22ab0d2221b2a52eb8356d4432dcbbde21d4b21e2b59d6e1c40a loop 13530",
            "ARCO WHEEL 60 SOFT" to "7f1f36e3d2fcf6c6a95c10f2c8060d30b5e1ad6f6b131ca116096df20c60775d loop 15531",
            "ARCO BAR 60 HARD" to "512931d7469dee5da3d732785df2f07e6137faad63d0c1008c83fb761740ad53 loop 13530",
            "ARCO BAR 60 SOFT" to "60fe3e5868783c789fcdc6f1ff6212ecb59b48170e813f4ef0d2472fba9f8618 loop 15380",
            "ARCO GLASS 60 HARD" to "925328199331fb138a01f15572c42901fde69c0d7dc544ba79e3b76ff45feca9 loop 13530",
            "ARCO GLASS 60 SOFT" to "adf9b1770f0e15db57b4893c899dd82ac112942be658450397243e98f6b7c12e loop 15825",
            "ARCO VIOL 60 HARD" to "7878aac7d703c7e41409536e1afc9ca8661b6c421c4134643b5056307189473f loop 13530",
            "ARCO VIOL 60 SOFT" to "c30a040b5b781bd88b487b6d02a3ee6f37bb07d9501e267cac435de26c86e5f1 loop 16955",
            "BORE HOLLOW 60 HARD" to "6c90deafb9fc8ee305c45ccd5b4b6ac8ce4de86a5d350465d0c07bbc1a5e0154 loop 13530",
            "BORE HOLLOW 60 SOFT" to "b9b1a96e8ebe48b941da3c8fc3fcc8578fafcea582ee8efeb8e7636b48fa1b1d loop 13530",
            "BORE SHAWM 60 HARD" to "7df32b03a4e9ffe29f4b5b649aa60d1f9f25bef84593945ced14ae54715bb55c loop 14659",
            "BORE SHAWM 60 SOFT" to "f3f48e0f5a2ecbf2cd48f3d6675703ddcaec18aa464c39cb18d3d4cdb383dbe2 loop 16023",
            "BORE SHAKU 72 HARD" to "a613214ccb0f487558c9660c7d2c7ab57f14380454d76f3c36bb62e9e6472b71 loop 13530",
            "BORE SHAKU 72 SOFT" to "4c46cbb52d6ed2957dc47473f91e5f5068ad783408c059f06af39dc2b702ed96 loop 13530",
            "BORE PANPIPE 60 HARD" to "676f7a2008a88db4877a3582415a87939ef9224b26892200535a81913b01bec9 loop 13530",
            "BORE PANPIPE 60 SOFT" to "e7d457a801b5ad4ace70dbdfbb13de12839d706ed6e66eaf7cd86e8b524705f3 loop 13530",
            "BORE VESSEL 60 HARD" to "446ec4a1255687ba444539037e1e0ed176976915dcbb1042ba01957b8ff4681e loop 13530",
            "BORE VESSEL 60 SOFT" to "9afd821d4edff1ef333a40e9a9d4ee4951d78cdbc36c1b8d5171996dd84b47c1 loop 17646",
        )
    }
}
