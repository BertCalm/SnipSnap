package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.*

class TerraGenreRoundRobinKitsTest {
    private val kits = TerraGenreRoundRobinKits.Kit.entries
    private val built by lazy { kits.associateWith { TerraGenreRoundRobinKits.build(it) } }
    private fun patches(kit: TerraGenreRoundRobinKits.Kit) = TerraGenreKits.patches(kit.source)

    /**
     * The kept genre kits' PCM24 SHA-256s, as `TerraGenreKitsTest` pins them (terra-presets-1, 2026-11-15): the
     * sixteen hard hits of each kit, and one hash over each kit's 32 softer strikes, softest first, pads in order.
     */
    private val keptHashes = mapOf(
        TerraGenreRoundRobinKits.Kit.SAHEL to (listOf(
            "4b3ffe6c8048a85693bf685b6ce3855d3e65f7616273fbaa89ea059a44f85951", "6a56debfebbfd7af60d4c1996cffa50eb00131edaa3bf01e30400961cf169a59",
            "15845cf6c1ea6de200203b8842098d50b6f46b26cb6b9e1ff62dcbba287748ed", "f1be58dc0fddad91cb8e6f4be3bbc3f086e7fe47f93882e849f73d6bf2b1c56f",
            "adf515dddeb134bc5662aff49dab4796260d5d2512fe610788479a32ea2e69d2", "c370f075464287b6eb96dd33b2364670dba096844065c86105913acc1a3a3f55",
            "d137c54b53604503f8c2fbba472ccd3a8a9f517ab8733c4c4806ebed1f09dbae", "acc2460bf889cda5cf247bb504646759f12ce1412bd4e5bf86d23f9bbb31fc57",
            "b1851530b8791ed1c044f52a656caaf719536d9f786eaccdd11ce2ff544e42a9", "4057bc8ae9fb6c321162052e1321f4f97a5cfd245c85a915e78116d0be071714",
            "39004257f1cdb9ad868bb45f4adb93b3e864c990b5095e42ef5b89c866643510", "beff3689f7e72186c371870345c037f8c805090163bba542cafea80a97b030c8",
            "2216ecacb0eb82f6fa57bf08715925baf3d4aa52c81ab2b55f11ba94af3e9dc0", "6c2f9f01225ec6796439dd2d449d8eae18d65771b662ded812fbe858ca615eb9",
            "35cf1ad177aaca99e8579cf650a8d9334665e6cbfcc29bb59c1ee17e26ecce3b", "3b3630ff6b62c9be6fb7c3c1b99b4ba9d271737f26950409872e7dd552f4bbff",
        ) to "17d704d5d0ddb51e58104ba7140b55df8dbf48489bc974e66efc08d6b426b19a"),
        TerraGenreRoundRobinKits.Kit.MAQAM to (listOf(
            "04d9339d6423be09e264253bd48df50775b579bb7535ece52c51dd67fb5ff349", "baf16e3f25c14a0c37d9b01dc9c14a71e38d5b34e42844017068dc0839776994",
            "9812a57e878665bcf7a1831fa560a8c2dec41e484f34b8ccb868f8219298b58f", "98ad14cde0126230afcc6bf97be518f54d03c3b94cf913e301060e24ccd56742",
            "a41e431e62afce8435d8892de8aecca7885f97bf2d519f8c1f212d6144b37101", "c5cbabb90f2b385d57c557814892f708c72dcb1bb1cc3f10b0657cf4f4411da4",
            "f1189d361b923beb681e8a1a6481e73a90ceaa7dfbe706b342239f9d72f937c9", "27bf0eecfab06c3c9ef73e9a98ae1afc0f5010aca50a4fc9e41517f3ecd5a9eb",
            "076dbcf77ad873f099b0fda22466f3fc3c6339687609dcde4e623e6c844368c1", "6a323416d9400831560b9f5b13d3bf7fc35462b4a132be482234f3991c1fa95a",
            "8cdb138463492979876065f1deb0fed3e1629af8708e65b7ff45d695f1fa916c", "118d46d3e016a08fa7841170ea8d1ed5321a9fef45dbc8c9982e87ba713d6f99",
            "8796bd186e3c05a6af5fb7de9ab53bd20cca7c03d51226c2e06b5da55053ffb3", "8d607cfe4e32a5622c84edd7aba5f710724785a14786c0a213c244f202422159",
            "e463f8df50aba869315886e6d026449ebb7b5ac1dddefb2684f64d333ff156e7", "df2b158b46ceec2890504447c128fb3fb5507efc7eb1ebb06fc947f7ac9dc1b6",
        ) to "68f3073a64c3545ba3b92e3399e5ac6521147d5a83fb347f6b2ade9e02b43b46"),
        TerraGenreRoundRobinKits.Kit.PUNJAB to (listOf(
            "4b1ac505b77e2fa8697a68c4962455042cb6b89c8ca6085f954aeb98f3dbed92", "8b94c1542d64e5464de841c282396cda267985ed7da8a9f3f502baa434513db8",
            "0302302443860c09b3f97db3c4d332b83182b48395aa976d686cf26aa5a1c309", "ede79d4cc96ca8293d61e67ede01afeb09d178b670141db081d2769a68c03ea9",
            "c729fab7aacb809132c7933980cf24fe6a79a9b68d3baa688c84ba25a89508c1", "1ee4c4a1eef3faca6555dd38e9d86d66f0e6b7d198aef378b0f88747604c8319",
            "d917b649c3412f20cf595436e6b86327ae09109ced90b03d3f80432e80e0c00b", "834852407350f0186426bb9bc2f313dc5ce5abf9d8e99c6076cc21b8674df339",
            "de2fc0e231bc82f70891f77051f2fad078acdf5439074d7dcb1d9ac0e418d7a9", "d5dc584721e6aabc587bfb247c8fd2622c4b0217e10b409887c75402975f4271",
            "398cc68d7dfb10a07a950b6db96f94f5d46b21c2254ef16907980976166a8a0b", "d1cef470e1acdaa124956fc6042776c3d6ba8ad6a8cb753a382b4fc6673660ba",
            "bd72845dda3ee0b50cf0b877aadff17b3dd7193cff43a9288bc209a43f643b46", "3d240568df44a53dd9efb9330840d8ca292763045c01f3e07d0084cbaf3e732a",
            "6a4d669643deb56a20740504d226dc1940947f98c16f20629ebc3ecdba0e91f8", "741abfd892e30f9beec1d21771c981bc6a0d073cc9d543d04942b5fa698302c2",
        ) to "f4746bb88655c6fd6647c62c1e1e3b622b293b8064ae0fee49811b4d2d59b3fc"),
    )

    private fun pcm24(snip: Snip, file: File): ByteArray {
        WavWriter.write(file, snip, WavWriter.BitDepth.PCM_24)
        return file.readBytes()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun `take zero of every zone is the kept genre kit, byte for byte, and hashes as the owner heard it`() {
        val dir = kotlin.io.path.createTempDirectory("terra-genre-rr-kept").toFile()
        try {
            val probe = File(dir, "probe.wav")
            for (kit in kits) {
                val kept = TerraGenreKits.build(kit.source).map { it!! }
                val pads = built.getValue(kit)
                val patches = patches(kit)
                val (hard, softHash) = keptHashes.getValue(kit)
                val soft = MessageDigest.getInstance("SHA-256")
                assertEquals(16, pads.size)
                for ((i, pad) in pads.withIndex()) {
                    val zones = kept[i].softVariants + kept[i].snip
                    assertEquals(3, pad.velocityTakes.size)
                    assertContentEquals(kept[i].snip.samples, pad.snip.samples)
                    for ((z, row) in pad.velocityTakes.withIndex()) {
                        assertContentEquals(zones[z].samples, row[0].samples, "${kit.name} ${pad.displayName} zone $z take 0")
                        // The take path at take 0 renders the kept audio too, not just reuses it.
                        assertContentEquals(zones[z].samples, TerraGenreRoundRobinKits.render(kit, patches[i], TerraRoundRobinKits.TOUCHES[z], 0).samples,
                            "${kit.name} ${pad.displayName} zone $z rendered at take 0")
                    }
                    assertEquals(hard[i], hex(MessageDigest.getInstance("SHA-256").digest(pcm24(pad.velocityTakes[2][0], probe))), "${kit.name} ${pad.displayName} take 0")
                    for (z in 0..1) soft.update(pcm24(pad.velocityTakes[z][0], probe))
                }
                assertEquals(softHash, hex(soft.digest()), "${kit.name}: the softer strikes' take 0")
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the grid is each kept kit's sixteen pads, three touches and four takes, with the recipe in the source`() {
        for (kit in kits) {
            val kept = TerraGenreKits.build(kit.source).map { it!! }
            val patches = patches(kit)
            assertEquals(patches.map { it.name }, TerraGenreRoundRobinKits.STRIKES.getValue(kit).keys.toList())
            assertTrue(TerraGenreRoundRobinKits.OWN_NEAREST.all { n -> kits.any { k -> patches(k).any { it.name == n } } })
            for ((i, pad) in built.getValue(kit).withIndex()) {
                assertEquals(patches[i].name, pad.displayName)
                assertEquals(kept[i].drumClass, pad.drumClass)
                assertNull(pad.recipe, "a single-hit recipe cannot regenerate the takes")
                assertNull(pad.muteGroup, "the kept kits' own rule: no TERRA pad chokes another")
                assertTrue(pad.oneShot)
                assertEquals(patches[i], TerraPatch.fromJsonText(pad.source.getValue("recipe")))
                assertEquals(kit.source.displayName, pad.source["kit"])
                val strikes = TerraGenreRoundRobinKits.strikesFor(kit, patches[i])
                assertEquals(strikes.joinToString(","), pad.source["rendererTakes"])
                assertEquals(0, strikes[0])
                assertEquals(TerraGenreRoundRobinKits.TAKES, strikes.toSet().size)
                assertTrue(pad.velocityTakes.all { it.size == TerraGenreRoundRobinKits.TAKES })
            }
        }
    }

    /** The take rule seeds each pad's landing on its name, so no two of the 64 TERRA pads may share one. */
    @Test fun `pad names are unique across the classic kit and the three genre kits`() {
        val names = TerraRoundRobinKits.patches().map { it.name } + kits.flatMap { k -> patches(k).map { it.name } }
        assertEquals(64, names.size)
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun `a later take moves only the spot and the landing, within their spreads`() {
        for (kit in kits) for (patch in patches(kit)) {
            val hasPos = Terra.defaults(patch.voice).containsKey("POS")
            for (s in TerraGenreRoundRobinKits.strikesFor(kit, patch).drop(1)) {
                val (factor, offset) = TerraRoundRobinKits.landing(patch, s)
                assertTrue(factor in (1f - TerraRoundRobinKits.forceSpread(patch.voice))..1f, "${patch.name} strike $s factor $factor")
                assertTrue(abs(offset) <= TerraRoundRobinKits.POSITION_SPREAD, "${patch.name} strike $s offset $offset")
                if (!hasPos) assertEquals(0f, offset, "${patch.name} has no strike point to move")
            }
        }
    }

    @Test fun `every later take replays, and the four takes of every zone differ by ear, not only in phase`() {
        val m = TerraRoundRobinMeasure
        for (kit in kits) for ((i, pad) in built.getValue(kit).withIndex()) {
            for ((z, row) in pad.velocityTakes.withIndex()) {
                for ((take, snip) in row.withIndex()) {
                    if (take > 0) assertContentEquals(TerraGenreRoundRobinKits.render(kit, patches(kit)[i], TerraRoundRobinKits.TOUCHES[z], take).samples,
                        snip.samples, "${pad.displayName} zone $z take $take replays")
                    assertEquals(row[0].frameCount, snip.frameCount)
                    assertTrue(snip.samples.all { it.isFinite() && abs(it) <= 1f })
                }
                // The hard zone at the screen's floor; the softer zones only have to be no copy. The pick held both:
                // measured, the least hard pair is 0.23 dB (Doum Open), the least softer pair 0.022 dB (Doum Open, soft).
                val least = if (z == 2) TerraRoundRobinKits.MIN_DISTANCE_DB else SOFT_TAKE_DISTANCE_DB
                for (a in row.indices) for (b in a + 1 until row.size) {
                    val d = m.spectroDistance(row[a].samples, row[b].samples)
                    assertTrue(d >= least, "${kit.name} ${pad.displayName} zone $z takes $a/$b differ by only $d dB")
                }
            }
        }
    }

    /**
     * The screen the takes were chosen by, TERRA ROUND ROBIN's, per kit: a hard take starts when the kept hit starts,
     * keeps its spectral shape within [TerraRoundRobinKits.SHAPE_LIMIT_DB] and under 0.35 times the nearest other pad
     * of its kit, has 90 % of its energy by 0.7 to 1.4 times the kept hit's time, and sits at least
     * [TerraRoundRobinKits.MIN_DISTANCE_DB] from it, phase-blind. The six sibling pads in [TerraGenreRoundRobinKits.OWN_NEAREST]
     * must instead stay nearer their own kept hit than any other pad's. Measured: starts within 0.57 ms, shape 0.0 to 1.99
     * dB, energy time 0.85 to 1.39 times, 0.23 to 4.23 dB from the kept hit phase-blind (the fitted drums furthest).
     */
    @Test fun `hard takes keep the kept hit's start, spectral shape and length, and are not a copy of it`() {
        val m = TerraRoundRobinMeasure
        for (kit in kits) {
            val pads = built.getValue(kit)
            val hard = pads.map { it.velocityTakes.last() }
            val shapes = hard.map { m.shape(it[0].samples) }
            for ((i, row) in hard.withIndex()) {
                val name = "${kit.name} ${pads[i].displayName}"
                val nearest = shapes.indices.filter { it != i }.minOf { m.distance(shapes[i], shapes[it]) }
                val kept = row[0].samples
                for ((take, snip) in row.withIndex().drop(1)) {
                    val x = snip.samples
                    assertEquals(m.startMs(kept), m.startMs(x), 1.0, "$name take $take start")
                    val d = m.distance(shapes[i], m.shape(x))
                    assertTrue(d < TerraRoundRobinKits.SHAPE_LIMIT_DB, "$name take $take shape $d dB from its own hit")
                    if (pads[i].displayName in TerraGenreRoundRobinKits.OWN_NEAREST) {
                        val shape = m.shape(x)
                        val other = shapes.indices.filter { it != i }.minOf { m.distance(shape, shapes[it]) }
                        assertTrue(d < other, "$name take $take shape $d dB from its own hit, $other dB from the nearest other pad")
                    } else assertTrue(d < .35 * nearest, "$name take $take shape $d dB from its own hit, nearest other pad $nearest dB")
                    val ratio = m.t90Ms(x) / m.t90Ms(kept)
                    assertTrue(ratio in .7..1.4, "$name take $take energy time ratio $ratio")
                    val apart = m.spectroDistance(kept, x)
                    assertTrue(apart >= TerraRoundRobinKits.MIN_DISTANCE_DB, "$name take $take only $apart dB from the kept hit")
                }
            }
        }
    }

    /** TerraGenreKitsTest's estimator: the strongest peak between [lo] and [hi] Hz, from 20 ms into the hit, parabolic. */
    private fun peakHz(samples: FloatArray, rate: Int, lo: Double, hi: Double): Double {
        val n = 16384
        val start = (0.02 * rate).toInt()
        val re = FloatArray(n) { i -> val k = start + i; if (k < samples.size) samples[k] * (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI * i / n).toFloat()) else 0f }
        val im = FloatArray(n)
        Fft.forward(re, im)
        val mag = DoubleArray(n / 2) { kotlin.math.hypot(re[it].toDouble(), im[it].toDouble()) }
        val a = (lo * n / rate).toInt(); val b = (hi * n / rate).toInt()
        val k = (a..b).maxBy { mag[it] }
        val (l, c, r) = Triple(ln(mag[k - 1] + 1e-12), ln(mag[k] + 1e-12), ln(mag[k + 1] + 1e-12))
        val d = 0.5 * (l - r) / (l - 2 * c + r)
        return (k + d) * rate / n
    }

    /** No take moves pitch: every balafon take, at every touch, still sounds the note it is named after. Measured: every take reads -0.02 to +0.08 cents from its name. */
    @Test fun `every balafon take sounds the note it is named after`() {
        val notes = mapOf("G3" to 196.00, "A3" to 220.00, "B3" to 246.94, "D4" to 293.66, "E4" to 329.63)
        val kit = TerraGenreRoundRobinKits.Kit.SAHEL
        val keys = built.getValue(kit).filter { it.source["voice"] == TerraVoice.TUNED_BAR.name }
        assertEquals(notes.keys.map { "Balafon $it" }, keys.map { it.displayName })
        for (pad in keys) {
            val target = notes.getValue(pad.displayName!!.removePrefix("Balafon "))
            for ((z, row) in pad.velocityTakes.withIndex()) for ((t, snip) in row.withIndex()) {
                val hz = peakHz(snip.samples, snip.sampleRate, target * 0.8, target * 1.25)
                val cents = 1200 * ln(hz / target) / ln(2.0)
                assertTrue(abs(cents) < 15, "${pad.displayName} zone $z take $t measures $hz Hz, $cents cents off")
            }
        }
    }

    /**
     * Every Hi and Lo pad keeps its order on every take: each hard take is the kept recipe struck at its own kept TUNE
     * (only POS and the landing move), re-rendered here from the recipe, so the pairs TerraGenreKitsTest orders stay ordered.
     */
    @Test fun `every Hi and Lo pad stays higher or lower than its partner on every take`() {
        val pairs = listOf(
            Triple(TerraGenreRoundRobinKits.Kit.SAHEL, "Djembe Tone Hi", "Djembe Tone"),
            Triple(TerraGenreRoundRobinKits.Kit.SAHEL, "Bell High", "Bell Low"),
            Triple(TerraGenreRoundRobinKits.Kit.MAQAM, "Darbuka High", "Darbuka Low"),
            Triple(TerraGenreRoundRobinKits.Kit.MAQAM, "Doum Open", "Doum Low"),
            Triple(TerraGenreRoundRobinKits.Kit.PUNJAB, "Dholak Hi", "Dholak Boom"),
            Triple(TerraGenreRoundRobinKits.Kit.PUNJAB, "Tabla Na Hi", "Tabla Tin Lo"),
        )
        for ((kit, high, low) in pairs) {
            val patches = patches(kit)
            val pads = built.getValue(kit)
            for (name in listOf(high, low)) {
                val i = patches.indexOfFirst { it.name == name }
                val p = patches[i]
                for ((t, s) in TerraGenreRoundRobinKits.strikesFor(kit, p).withIndex().drop(1)) {
                    val (factor, offset) = TerraRoundRobinKits.landing(p, s)
                    val macros = if (offset == 0f) p.macros else p.macros + ("POS" to (p.macros.getValue("POS") + offset).coerceIn(0f, 1f))
                    assertEquals(p.macros.getValue("TUNE"), macros.getValue("TUNE"))
                    val again = if (p.voice in Terra.FITTED_HANDS && p.striker == null) Terra.renderFittedTake(p.voice, macros, factor, s)
                        else Terra.render(p.voice, macros, p.striker, factor, s)
                    assertContentEquals(again.samples, pads[i].velocityTakes[2][t].samples, "${p.name} take $t is struck at its kept TUNE")
                }
            }
            val h = patches.single { it.name == high }; val l = patches.single { it.name == low }
            assertTrue(h.macros.getValue("TUNE") > l.macros.getValue("TUNE"), "$high is tuned above $low")
        }
    }

    private companion object {
        /** The least phase-blind distance between two takes of a softer zone (TerraRoundRobinMeasure.spectroDistance): no copy. */
        const val SOFT_TAKE_DISTANCE_DB = 0.02
    }
}
