package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.SootGenreKits.Genre
import com.snipsnap.synth.SootGenreKits.at
import com.snipsnap.synth.SootGenreKits.bars
import java.io.File
import java.security.MessageDigest

/**
 * Candidate four bars for SOOT BASEMENT DUB's A16, rendered after the owner heard the current one as
 * incoherent (2026-11-13). Writes `testkit/soot-dub-candidates/` and a hash of every kit's current four
 * bars (which these candidates must not change).
 */
object SootDubCandidateGenerator {
    private var dir = File("testkit/soot-dub-candidates")
    private fun write(name: String, x: FloatArray) {
        var p = 0f; for (s in x) p = maxOf(p, kotlin.math.abs(s))
        WavWriter.write(File(dir, "$name.wav"), Snip(FloatArray(x.size) { x[it] * .89f / p }, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
    }
    @JvmStatic fun main(args: Array<String>) {
        dir = File(args.getOrElse(0) { "testkit/soot-dub-candidates" }).apply { mkdirs() }
        val md = MessageDigest.getInstance("SHA-256")
        for (g in Genre.entries) { val x = SootGenreKits.phrase(g, 1f, 0).samples; val bb = java.nio.ByteBuffer.allocate(x.size * 4); for (s in x) bb.putFloat(s); md.update(bb.array()) }
        File(dir, "phrases.sha").writeText(md.digest().joinToString("") { "%02x".format(it) })
        // The version-1 one-drop, the owner heard it as incoherent (its wetness .7, the rim's send .85).
        val v1 = bars(
            { b -> at(b, 0, 1f, 8) },
            { b -> at(b, 1, .9f, 8) + (if (b == 1 || b == 3) at(b, 1, .5f, 14) else emptyList()) },
            { b -> at(b, 2, .5f, 2, 6, 10, 14) },
            { b -> if (b == 1) at(b, 8, .6f, 15) else emptyList() },
            { b -> if (b == 3) at(b, 10, 1f, 12) else emptyList() },
        )
        write("current", SootGenreKits.phrase(Genre.DUB, 1f, 0, v1, .7, mapOf(1 to .85)).samples)
        // A: one-drop, steady. Kick and rim on 3; hats every eighth, offbeats leaning; a lighter spring; the throw only at the turn.
        val a = bars(
            { b -> at(b, 0, 1f, 8) },
            { b -> at(b, 1, .85f, 8) + (if (b == 3) at(b, 1, .55f, 14) else emptyList()) },
            { b -> at(b, 2, .3f, 0, 4, 8, 12) + at(b, 2, .55f, 2, 6, 10, 14) },
            { b -> if (b == 1) at(b, 8, .5f, 15) else emptyList() },
        )
        write("a_onedrop", SootGenreKits.phrase(Genre.DUB, 1f, 0, a, .45, mapOf(1 to .4)).samples)
        // B: steppers. A kick on every beat, the rim on 3, hats on the offbeats, the throw at the turn.
        val b2 = bars(
            { b -> at(b, 0, .9f, 0, 4, 12) + at(b, 0, 1f, 8) },
            { b -> at(b, 1, .85f, 8) + (if (b == 3) at(b, 1, .55f, 14) else emptyList()) },
            { b -> at(b, 2, .5f, 2, 6, 10, 14) },
            { b -> if (b == 1) at(b, 8, .5f, 15) else emptyList() },
        )
        write("b_steppers", SootGenreKits.phrase(Genre.DUB, 1f, 0, b2, .5, mapOf(1 to .4)).samples)
    }
}
