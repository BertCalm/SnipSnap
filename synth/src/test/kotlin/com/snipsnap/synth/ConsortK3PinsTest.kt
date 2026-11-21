package com.snipsnap.synth

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the owner heard in round K3 and kept (2026-11-20, "All sounds good"), pinned: the held key
 * samples behind clips K1 to K9 (samples and loop start), the fourteen character presets (P1 to P14),
 * the CONSORT TAKES renders (T1 to T3) and the low BASSOON (B1). `consort/k3-heard-v2.sha256` holds
 * the SHA-256 of each render's float samples. Version 2 (2026-11-21): the key samples (K lines) moved to the
 * held pads' eight-second cycle, the owner's call ("Keys should be like held"); every other line is version 1's. The chord clips (C1, C2) play the CLARINET keys pinned here.
 */
class ConsortK3PinsTest {
    private fun sha(samples: FloatArray, extra: Long = 0L): String {
        val bytes = java.nio.ByteBuffer.allocate(samples.size * 4 + 8)
        for (x in samples) bytes.putFloat(x)
        bytes.putLong(extra)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    private fun preset(name: String) = ConsortPresets.all().first { it.name == name }

    /** Every pinned render, by a label naming what it is. */
    private fun renders(): List<Pair<String, () -> String>> {
        val out = mutableListOf<Pair<String, () -> String>>()
        fun key(clip: String, p: ConsortPatch, midi: Int, touch: Int, take: Int) {
            out += "$clip ${p.name} key $midi touch ${touch + 1} take ${take + 1}" to {
                val n = ConsortKeys.render(p, ConsortKeys.Cell(midi, touch, take)); sha(n.snip.samples, n.loopStartFrame)
            }
        }
        val clar = preset("PLAIN CLARINET"); val z = ConsortKeys.padMidis(ConsortVoice.CLARINET)
        for (m in listOf(z[2], z[4], z[6])) key("K1", clar, m, 1, 0)
        key("K2", preset("PLAIN FLUTE"), ConsortKeys.padMidis(ConsortVoice.FLUTE)[4], 1, 0)
        key("K3", preset("PLAIN SAX"), ConsortKeys.padMidis(ConsortVoice.SAX)[3], 1, 0)
        key("K4", preset("PLAIN DUDUK"), ConsortKeys.padMidis(ConsortVoice.DUDUK)[2], 1, 0)
        preset("FULL QUARTET").let { key("K5", it, ConsortKeys.padMidis(it.voice)[3], 1, 0) }
        preset("REED TRIO").let { key("K6", it, ConsortKeys.padMidis(it.voice).last(), 1, 0) }
        key("K7", preset("FLUTTER FLUTE"), ConsortKeys.padMidis(ConsortVoice.FLUTE)[4], 1, 0)
        for (t in 0..2) key("K8", clar, z[4], t, 0)
        key("K9", clar, z[4], 1, 1)
        val new = listOf("SHAKUHACHI", "FUJARA", "FLUTE TRILL", "FLUTTER FLUTE", "SAX FALL", "SAX GROWL", "CLARINET SCOOP", "BAWU",
            "OBOE TRILL", "PKU", "BASSOON STABS", "BASSOON FALL", "DUDUK LAMENT", "DUDUK SCOOP")
        new.forEachIndexed { i, n -> out += "P${i + 1} $n" to { sha(preset(n).render().samples) } }
        val pads = SynthKits.consortPatches()
        for (v in ConsortRoundRobinKits.TOUCHES) out += "T1 ${pads[12].name} touch $v" to { sha(ConsortRoundRobinKits.render(pads[12], v, 0).samples) }
        out += "T2 ${pads[12].name} take 2" to { sha(ConsortRoundRobinKits.render(pads[12], Consort.DEFAULT_VELOCITY, 2).samples) }
        for (v in ConsortRoundRobinKits.TOUCHES) out += "T3 ${pads[3].name} touch $v" to { sha(ConsortRoundRobinKits.render(pads[3], v, 0).samples) }
        val bas = preset("PLAIN BASSOON")
        for (st in listOf(0, 7, 12)) out += "B1 PLAIN BASSOON +$st" to { sha(bas.withMacros(bas.macros + ("TUNE" to st / 24f)).render().samples) }
        return out
    }

    @Test
    fun `every K3 render the owner kept renders sample for sample as heard`() {
        val write = System.getenv("CONSORT_PINS_WRITE")
        val rows = renders().map { (label, hash) -> label to hash() }
        if (write != null) { File(write).writeText(rows.joinToString("") { "${it.first}\t${it.second}\n" }); return }
        val pinned = javaClass.getResourceAsStream("/consort/k3-heard-v2.sha256")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }.map { it.split('\t').let { (a, b) -> a to b } }
        assertEquals(pinned.map { it.first }, rows.map { it.first })
        for ((i, row) in rows.withIndex()) assertEquals(pinned[i].second, row.second, row.first)
    }
}
