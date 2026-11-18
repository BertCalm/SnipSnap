package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.*

class TerraRoundRobinKitsTest {
    /**
     * The kept classic kit's PCM24 SHA-256s, as `TerraKitsTest` pins them (terra-kit-1, 2026-11-14):
     * the sixteen hard hits, and one hash over the 32 softer strikes, softest first, pads in order.
     */
    private val keptHashes = listOf(
        "5c1a08770a16062cd6c245982936e4ba55ed842b4e5acd5a62c5ea9dfce4a6ad", // A01 Udu Whoomp
        "e5dc15391d10d21d5600ad9951c78e7dcb90d8b5a5114026f10f2cf95fcfb191", // A02 Bayan Drag
        "c5786575d413bd5fffd6e3f13967c224c91a6b75150d8970a8caf72c7bd7c391", // A03 Djembe Bass
        "549b26a008a05bfe66f8834630b9695616f1f206595cdc3634cc1d943f815710", // A04 Cajon Low
        "cf08d6ec8375d67ca3861725f115cd651cf8efb4cf309309a02c9b0ef74c46a5", // A05 Dholak Bass
        "7f10f53609cf7ea54e7d30d57c81419214faaf8f542120eb6f61d754ae587f98", // A06 Dumbek Doum
        "75b64dbfa6018bcfcd09f4654ff77ca46653aa6175ec8d8e1b992044b552397f", // A07 Cajon Slap
        "5127667e4eb4bccba7e034c2d076afeb5579a6f57e029d7d89b51772469c962a", // A08 Djembe Open
        "40f3010a5e300d8d7979b079e4a10181cbc9adc39cb3690a3d495dffdb51bc06", // A09 Tabla Tin
        "88c9be917bbcbbd48c6c1f5a022b847dcca36ff0ced321222c40770171ec0c50", // A10 Tabla Tun
        "c7a4613fe61cc8cd7fd0840bb5b2123bfcb7c6a184bfeeef568f0e6d74e780a8", // A11 Dumbek Tek
        "966c4a2919039e55d11b9d294cb22120c99249ff026f254851a77fe37f40c2cf", // A12 Djembe Slap
        "39fc9dbbb42c86414cdbc6ff05353600cb39129bb5e5e003fbc9b38fb0133531", // A13 Agogo Low
        "096a427a271a134f0dd2f011b9c988eb1925088200316d17693d255abb74d128", // A14 Agogo High
        "680c6a3491297a470c9b83a009147d0ebd048f265c0381c480272dbc4affdf49", // A15 Agogo Clack
        "048f07542f81578ad2186135541e5e257f7aa77ecfbd5b06510d85bf8c52c4d0", // A16 Balafon Key
    )
    private val keptSoftHash = "48a4f37ed48e925d12613408cc1d4edb906b2217d629ac9d4a6be90eaaec66ae"

    private fun pcm24(snip: Snip, file: File): ByteArray {
        WavWriter.write(file, snip, WavWriter.BitDepth.PCM_24)
        return file.readBytes()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }

    private val built by lazy { TerraRoundRobinKits.build() }
    private val patches by lazy { TerraRoundRobinKits.patches() }

    @Test fun `take zero of every zone is the kept kit, byte for byte, and hashes as the owner heard it`() {
        val kept = TerraKits.classic().map { it!! }
        assertEquals(16, built.size)
        val dir = kotlin.io.path.createTempDirectory("terra-rr-kept").toFile()
        try {
            val probe = File(dir, "probe.wav")
            val soft = MessageDigest.getInstance("SHA-256")
            for ((i, pad) in built.withIndex()) {
                val zones = kept[i].softVariants + kept[i].snip
                assertEquals(3, pad.velocityTakes.size)
                assertContentEquals(kept[i].snip.samples, pad.snip.samples)
                for ((z, row) in pad.velocityTakes.withIndex()) {
                    assertContentEquals(zones[z].samples, row[0].samples, "${pad.displayName} zone $z take 0")
                    // The take path at take 0 renders the kept audio too, not just reuses it.
                    assertContentEquals(zones[z].samples, TerraRoundRobinKits.render(patches[i], TerraRoundRobinKits.TOUCHES[z], 0).samples,
                        "${pad.displayName} zone $z rendered at take 0")
                }
                assertEquals(keptHashes[i], hex(MessageDigest.getInstance("SHA-256").digest(pcm24(pad.velocityTakes[2][0], probe))), "${pad.displayName} take 0")
                for (z in 0..1) soft.update(pcm24(pad.velocityTakes[z][0], probe))
                // The renderer's public take 0 is today's render.
                assertContentEquals(patches[i].render().samples, Terra.render(patches[i].voice, patches[i].macros, 1f, take = 0).samples)
                if (patches[i].voice in Terra.FITTED_HANDS)
                    assertContentEquals(patches[i].render().samples, Terra.renderFittedTake(patches[i].voice, patches[i].macros, 1f, 0).samples)
            }
            assertEquals(keptSoftHash, hex(soft.digest()), "the softer strikes' take 0")
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the grid is the kept kit's sixteen pads, three touches and four takes, with the recipe in the source`() {
        val kept = TerraKits.classic().map { it!! }
        for ((i, pad) in built.withIndex()) {
            assertEquals(patches[i].name, pad.displayName)
            assertEquals(kept[i].drumClass, pad.drumClass)
            assertNull(pad.recipe, "a single-hit recipe cannot regenerate the takes")
            assertNull(pad.muteGroup, "the kept kit's own rule: no TERRA pad chokes another")
            assertTrue(pad.oneShot)
            assertEquals(patches[i], TerraPatch.fromJsonText(pad.source.getValue("recipe")))
            val strikes = TerraRoundRobinKits.strikesFor(patches[i])
            assertEquals(strikes.joinToString(","), pad.source["rendererTakes"])
            assertEquals(0, strikes[0])
            assertEquals(TerraRoundRobinKits.TAKES, strikes.toSet().size)
            assertTrue(pad.velocityTakes.all { it.size == TerraRoundRobinKits.TAKES })
        }
        assertEquals(TerraKits.classic().map { PadRecipe.fromJsonValue(it!!.recipe!!).patch }, patches)
        assertEquals(patches.map { it.name }, TerraRoundRobinKits.STRIKES.keys.toList())
    }

    @Test fun `a later take moves only the spot and the landing, within their spreads`() {
        for (patch in patches) {
            assertEquals(1f to 0f, TerraRoundRobinKits.landing(patch, 0))
            val hasPos = Terra.defaults(patch.voice).containsKey("POS")
            for (s in TerraRoundRobinKits.strikesFor(patch).drop(1)) {
                val (factor, offset) = TerraRoundRobinKits.landing(patch, s)
                assertTrue(factor in (1f - TerraRoundRobinKits.forceSpread(patch.voice))..1f, "${patch.name} strike $s factor $factor")
                assertTrue(abs(offset) <= TerraRoundRobinKits.POSITION_SPREAD, "${patch.name} strike $s offset $offset")
                if (!hasPos) assertEquals(0f, offset, "${patch.name} has no strike point to move")
            }
        }
    }

    @Test fun `every later take replays, and the four takes of every zone differ by ear, not only in phase`() {
        val m = TerraRoundRobinMeasure
        for ((i, pad) in built.withIndex()) {
            for ((z, row) in pad.velocityTakes.withIndex()) {
                for ((take, snip) in row.withIndex()) {
                    if (take > 0) assertContentEquals(TerraRoundRobinKits.render(patches[i], TerraRoundRobinKits.TOUCHES[z], take).samples,
                        snip.samples, "${pad.displayName} zone $z take $take replays")
                    assertEquals(row[0].frameCount, snip.frameCount)
                    assertTrue(snip.samples.all { it.isFinite() && abs(it) <= 1f })
                }
                // Phase-blind: band levels in 23 ms frames. A re-drawn noise with the same spectrum, which a
                // waveform residual would call a different hit, reads near zero here.
                // The hard zone, where repeats are heard most, at the screen's floor; the softer zones' noise
                // grows as v^1.5 and less, so they only have to be no copy (the soft tin's least pair: 0.03 dB).
                val least = if (z == 2) TerraRoundRobinKits.MIN_DISTANCE_DB else SOFT_TAKE_DISTANCE_DB
                for (a in row.indices) for (b in a + 1 until row.size) {
                    val d = m.spectroDistance(row[a].samples, row[b].samples)
                    assertTrue(d >= least, "${pad.displayName} zone $z takes $a/$b differ by only $d dB")
                }
            }
        }
    }

    /**
     * Descriptive bounds, not a listening verdict: a hard take starts when the kept hit starts, keeps
     * its spectral shape within [TerraRoundRobinKits.SHAPE_LIMIT_DB] and far nearer it than the nearest
     * other pad, has 90 % of its energy by 0.7 to 1.4 times the kept hit's time, and sits at least
     * [TerraRoundRobinKits.MIN_DISTANCE_DB] from it, phase-blind.
     */
    @Test fun `hard takes keep the kept hit's start, spectral shape and length, and are not a copy of it`() {
        val m = TerraRoundRobinMeasure
        val hard = built.map { it.velocityTakes.last() }
        val shapes = hard.map { m.shape(it[0].samples) }
        for ((i, row) in hard.withIndex()) {
            val name = built[i].displayName
            val nearest = shapes.indices.filter { it != i }.minOf { m.distance(shapes[i], shapes[it]) }
            val kept = row[0].samples
            for ((take, snip) in row.withIndex().drop(1)) {
                val x = snip.samples
                assertEquals(m.startMs(kept), m.startMs(x), 1.0, "$name take $take start")
                val d = m.distance(shapes[i], m.shape(x))
                assertTrue(d < TerraRoundRobinKits.SHAPE_LIMIT_DB && d < .35 * nearest, "$name take $take shape $d dB from its own hit, nearest other pad $nearest dB")
                val ratio = m.t90Ms(x) / m.t90Ms(kept)
                assertTrue(ratio in .7..1.4, "$name take $take energy time ratio $ratio")
                val apart = m.spectroDistance(kept, x)
                assertTrue(apart >= TerraRoundRobinKits.MIN_DISTANCE_DB, "$name take $take only $apart dB from the kept hit")
            }
        }
    }

    private companion object {
        /** The least phase-blind distance between two takes of a softer zone (TerraRoundRobinMeasure.spectroDistance): no copy. */
        const val SOFT_TAKE_DISTANCE_DB = 0.02
    }
}
