package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.*

class TerraGenreKitsTest {
    private val kits = TerraGenreKits.Kit.entries
    private fun sound(p: TerraPatch) = p.voice to (Terra.defaults(p.voice) + p.macros)
    private val classic = TerraKits.classic().map { PadRecipe.fromJsonValue(it!!.recipe!!).patch as TerraPatch }

    /** The strongest spectral peak between [lo] and [hi] Hz, from 20 ms into the hit, with parabolic interpolation. */
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

    private fun cents(hz: Double, target: Double) = 1200 * ln(hz / target) / ln(2.0)

    @Test fun `each kit has sixteen distinct, labelled pads`() {
        for (kit in kits) {
            val patches = TerraGenreKits.patches(kit)
            assertEquals(16, patches.size, kit.name)
            assertEquals(16, patches.map { it.name }.toSet().size, "${kit.name} pad names are unique")
            assertEquals(16, patches.map(::sound).toSet().size, "${kit.name} pads are distinct sounds")
            for (p in patches) {
                assertTrue(p.name.length <= 14, "${p.name} fits a pad label")
                assertEquals(Terra.macrosFor(p.voice).map { it.name }.toSet(), p.macros.keys, "${p.name} names every macro")
                assertNotEquals(TerraVoice.COMPOUND_MEMBRANE, p.voice, "${p.name} is on the retired membrane")
            }
        }
    }

    @Test fun `no recipe is shared between kits or with the classic kit, and any two kits differ in at least 12 pads`() {
        val all = kits.associate { it.name to TerraGenreKits.patches(it) } + ("CLASSIC" to classic)
        val names = all.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size) {
            val a = all.getValue(names[i]); val b = all.getValue(names[j])
            val bSounds = b.map(::sound).toSet()
            assertTrue(a.count { sound(it) !in bSounds } >= 12, "${names[i]} and ${names[j]} differ in at least 12 pads")
            assertTrue(a.none { sound(it) in bSounds }, "${names[i]} and ${names[j]} share a recipe")
        }
    }

    @Test fun `no kick or snare is shared between kits`() {
        val classed = kits.associateWith { kit -> TerraGenreKits.build(kit).map { it!! } }
        for (i in kits.indices) for (j in i + 1 until kits.size) {
            fun hits(k: TerraGenreKits.Kit) = classed.getValue(k).filter { it.drumClass == DrumClass.KICK || it.drumClass == DrumClass.SNARE }
                .map { it.snip.samples.toList() }.toSet()
            assertTrue((hits(kits[i]) intersect hits(kits[j])).isEmpty(), "${kits[i]} and ${kits[j]} share a kick or snare")
        }
    }

    @Test fun `every balafon key sounds the note it is named after`() {
        val notes = mapOf("G3" to 196.00, "A3" to 220.00, "B3" to 246.94, "D4" to 293.66, "E4" to 329.63)
        val keys = TerraGenreKits.patches(TerraGenreKits.Kit.SAHEL).filter { it.voice == TerraVoice.TUNED_BAR }
        assertEquals(notes.keys.map { "Balafon $it" }, keys.map { it.name })
        for (k in keys) {
            val target = notes.getValue(k.name.removePrefix("Balafon "))
            val snip = k.render()
            val hz = peakHz(snip.samples, snip.sampleRate, target * 0.8, target * 1.25)
            assertTrue(abs(cents(hz, target)) < 15, "${k.name} measures $hz Hz, ${cents(hz, target)} cents off")
        }
    }

    @Test fun `every Hi and Lo pad is higher or lower than its partner`() {
        val pairs = listOf(
            Triple(TerraGenreKits.Kit.SAHEL, "Djembe Tone Hi", "Djembe Tone"),
            Triple(TerraGenreKits.Kit.SAHEL, "Bell High", "Bell Low"),
            Triple(TerraGenreKits.Kit.MAQAM, "Darbuka High", "Darbuka Low"),
            Triple(TerraGenreKits.Kit.MAQAM, "Doum Open", "Doum Low"),
            Triple(TerraGenreKits.Kit.PUNJAB, "Dholak Hi", "Dholak Boom"),
            Triple(TerraGenreKits.Kit.PUNJAB, "Tabla Na Hi", "Tabla Tin Lo"),
        )
        for ((kit, high, low) in pairs) {
            val patches = TerraGenreKits.patches(kit)
            val h = patches.single { it.name == high }; val l = patches.single { it.name == low }
            assertEquals(h.voice, l.voice)
            assertTrue(h.macros.getValue("TUNE") > l.macros.getValue("TUNE"), "$high is tuned above $low")
        }
    }

    @Test fun `every pad regenerates from its saved recipe and carries two softer strikes`() {
        for (kit in kits) {
            val patches = TerraGenreKits.patches(kit)
            for ((i, nullable) in TerraGenreKits.build(kit).withIndex()) {
                val pad = assertNotNull(nullable)
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                assertEquals(patches[i], recipe.patch)
                assertEquals(patches[i].name, pad.displayName)
                assertNull(recipe.fx); assertNull(recipe.board)
                assertEquals(2, pad.softVariants.size, patches[i].name)
                assertContentEquals(pad.snip.samples, recipe.render().samples, patches[i].name)
                assertTrue(pad.snip.samples.all { it.isFinite() } && pad.snip.samples.any { it != 0f }, patches[i].name)
                assertEquals("kept", pad.source["status"])
            }
        }
    }

    @Test fun `both MPC formats export and read back every genre kit`() {
        val root = kotlin.io.path.createTempDirectory("terra-genre-kits").toFile()
        try {
            for (genre in kits) {
                val work = File(root, "${genre.id}-work").apply { mkdirs() }
                val staging = File(root, "${genre.id}-out").apply { mkdirs() }
                val kit = KitAssembler.assembleArranged(genre.displayName, TerraGenreKits.build(genre), work, null)
                assertEquals(16, kit.pads.size)
                val result = Mpc3Exporter.exportTrack(kit, work, staging, overwrite = true, mpc2Twin = true)
                val data = File(staging, Mpc3TrackWriter.trackDataDirName(kit.name))
                val twin = File(data, "${kit.name}.xpm")
                KitStore.save(kit, data)
                assertEquals(kit, KitStore.load(data))
                val findings = listOf(
                    ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings),
                    ExportOutcome(ExportFormat.PROGRAM_FOLDER, twin, null, result.findings),
                ).flatMap { ExportReadBack.verify(kit, it) }
                assertFalse(findings.blocked(), "${genre.name}: ${findings.joinToString { it.message }}")
            }
        } finally { root.deleteRecursively() }
    }

    /**
     * The PCM24 bytes of every pad the owner kept in round terra-presets-1 (X1–X3 and Y1–Y3, 2026-11-15), and one
     * hash over each kit's 32 softer strikes, which the grooves played. A changed recipe or engine fails here.
     */
    private val kept = mapOf(
        TerraGenreKits.Kit.SAHEL to (listOf(
            "4b3ffe6c8048a85693bf685b6ce3855d3e65f7616273fbaa89ea059a44f85951", // A01 Udu Deep
            "6a56debfebbfd7af60d4c1996cffa50eb00131edaa3bf01e30400961cf169a59", // A02 Djembe Boom
            "15845cf6c1ea6de200203b8842098d50b6f46b26cb6b9e1ff62dcbba287748ed", // A03 Djembe Low
            "f1be58dc0fddad91cb8e6f4be3bbc3f086e7fe47f93882e849f73d6bf2b1c56f", // A04 Djembe Tone
            "adf515dddeb134bc5662aff49dab4796260d5d2512fe610788479a32ea2e69d2", // A05 Djembe Tone Hi
            "c370f075464287b6eb96dd33b2364670dba096844065c86105913acc1a3a3f55", // A06 Djembe Crack
            "d137c54b53604503f8c2fbba472ccd3a8a9f517ab8733c4c4806ebed1f09dbae", // A07 Slap Light
            "acc2460bf889cda5cf247bb504646759f12ce1412bd4e5bf86d23f9bbb31fc57", // A08 Djembe Muffle
            "b1851530b8791ed1c044f52a656caaf719536d9f786eaccdd11ce2ff544e42a9", // A09 Bell Low
            "4057bc8ae9fb6c321162052e1321f4f97a5cfd245c85a915e78116d0be071714", // A10 Bell High
            "39004257f1cdb9ad868bb45f4adb93b3e864c990b5095e42ef5b89c866643510", // A11 Bell Clack
            "beff3689f7e72186c371870345c037f8c805090163bba542cafea80a97b030c8", // A12 Balafon G3
            "2216ecacb0eb82f6fa57bf08715925baf3d4aa52c81ab2b55f11ba94af3e9dc0", // A13 Balafon A3
            "6c2f9f01225ec6796439dd2d449d8eae18d65771b662ded812fbe858ca615eb9", // A14 Balafon B3
            "35cf1ad177aaca99e8579cf650a8d9334665e6cbfcc29bb59c1ee17e26ecce3b", // A15 Balafon D4
            "3b3630ff6b62c9be6fb7c3c1b99b4ba9d271737f26950409872e7dd552f4bbff", // A16 Balafon E4
        ) to "17d704d5d0ddb51e58104ba7140b55df8dbf48489bc974e66efc08d6b426b19a"),
        TerraGenreKits.Kit.MAQAM to (listOf(
            "04d9339d6423be09e264253bd48df50775b579bb7535ece52c51dd67fb5ff349", // A01 Doum Low
            "baf16e3f25c14a0c37d9b01dc9c14a71e38d5b34e42844017068dc0839776994", // A02 Doum Open
            "9812a57e878665bcf7a1831fa560a8c2dec41e484f34b8ccb868f8219298b58f", // A03 Doum Muted
            "98ad14cde0126230afcc6bf97be518f54d03c3b94cf913e301060e24ccd56742", // A04 Doum Heavy
            "a41e431e62afce8435d8892de8aecca7885f97bf2d519f8c1f212d6144b37101", // A05 Tek
            "c5cbabb90f2b385d57c557814892f708c72dcb1bb1cc3f10b0657cf4f4411da4", // A06 Tek Hard
            "f1189d361b923beb681e8a1a6481e73a90ceaa7dfbe706b342239f9d72f937c9", // A07 Ka
            "27bf0eecfab06c3c9ef73e9a98ae1afc0f5010aca50a4fc9e41517f3ecd5a9eb", // A08 Ka Soft
            "076dbcf77ad873f099b0fda22466f3fc3c6339687609dcde4e623e6c844368c1", // A09 Tek Ghost
            "6a323416d9400831560b9f5b13d3bf7fc35462b4a132be482234f3991c1fa95a", // A10 Tek Ring
            "8cdb138463492979876065f1deb0fed3e1629af8708e65b7ff45d695f1fa916c", // A11 Darbuka Pop
            "118d46d3e016a08fa7841170ea8d1ed5321a9fef45dbc8c9982e87ba713d6f99", // A12 Darbuka High
            "8796bd186e3c05a6af5fb7de9ab53bd20cca7c03d51226c2e06b5da55053ffb3", // A13 Darbuka Low
            "8d607cfe4e32a5622c84edd7aba5f710724785a14786c0a213c244f202422159", // A14 Udu Drop
            "e463f8df50aba869315886e6d026449ebb7b5ac1dddefb2684f64d333ff156e7", // A15 Bell Ring
            "df2b158b46ceec2890504447c128fb3fb5507efc7eb1ebb06fc947f7ac9dc1b6", // A16 Bell Tick
        ) to "68f3073a64c3545ba3b92e3399e5ac6521147d5a83fb347f6b2ade9e02b43b46"),
        TerraGenreKits.Kit.PUNJAB to (listOf(
            "4b1ac505b77e2fa8697a68c4962455042cb6b89c8ca6085f954aeb98f3dbed92", // A01 Dholak Boom
            "8b94c1542d64e5464de841c282396cda267985ed7da8a9f3f502baa434513db8", // A02 Dholak Hi
            "0302302443860c09b3f97db3c4d332b83182b48395aa976d686cf26aa5a1c309", // A03 Dholak Thud
            "ede79d4cc96ca8293d61e67ede01afeb09d178b670141db081d2769a68c03ea9", // A04 Dholak Ring
            "c729fab7aacb809132c7933980cf24fe6a79a9b68d3baa688c84ba25a89508c1", // A05 Bayan Ghe Low
            "1ee4c4a1eef3faca6555dd38e9d86d66f0e6b7d198aef378b0f88747604c8319", // A06 Bayan Swoop
            "d917b649c3412f20cf595436e6b86327ae09109ced90b03d3f80432e80e0c00b", // A07 Bayan Ka
            "834852407350f0186426bb9bc2f313dc5ce5abf9d8e99c6076cc21b8674df339", // A08 Tabla Na Hi
            "de2fc0e231bc82f70891f77051f2fad078acdf5439074d7dcb1d9ac0e418d7a9", // A09 Tabla Tin Lo
            "d5dc584721e6aabc587bfb247c8fd2622c4b0217e10b409887c75402975f4271", // A10 Tabla Te
            "398cc68d7dfb10a07a950b6db96f94f5d46b21c2254ef16907980976166a8a0b", // A11 Tun Long
            "d1cef470e1acdaa124956fc6042776c3d6ba8ad6a8cb753a382b4fc6673660ba", // A12 Tun Knock
            "bd72845dda3ee0b50cf0b877aadff17b3dd7193cff43a9288bc209a43f643b46", // A13 Cajon Snap
            "3d240568df44a53dd9efb9330840d8ca292763045c01f3e07d0084cbaf3e732a", // A14 Cajon Thump
            "6a4d669643deb56a20740504d226dc1940947f98c16f20629ebc3ecdba0e91f8", // A15 Bell Chime
            "741abfd892e30f9beec1d21771c981bc6a0d073cc9d543d04942b5fa698302c2", // A16 Bell Clink
        ) to "f4746bb88655c6fd6647c62c1e1e3b622b293b8064ae0fee49811b4d2d59b3fc"),
    )

    @Test fun `every kept genre-kit pad and softer strike retains the bytes the owner heard`() {
        val probe = File.createTempFile("terra-genre", ".wav")
        fun pcm24(s: com.snipsnap.audio.Snip): ByteArray { com.snipsnap.audio.WavWriter.write(probe, s, com.snipsnap.audio.WavWriter.BitDepth.PCM_24); return probe.readBytes() }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
        try {
            for (kit in kits) {
                val (pads, softHash) = kept.getValue(kit)
                val soft = java.security.MessageDigest.getInstance("SHA-256")
                for ((i, pad) in TerraGenreKits.build(kit).withIndex()) {
                    assertEquals(pads[i], hex(java.security.MessageDigest.getInstance("SHA-256").digest(pcm24(pad!!.snip))),
                        "${kit.name} A${"%02d".format(i + 1)} ${pad.displayName} changed since the owner kept it")
                    for (v in pad.softVariants) soft.update(pcm24(v))
                }
                assertEquals(softHash, hex(soft.digest()), "${kit.name}: a softer strike changed since the owner kept it")
            }
        } finally { probe.delete() }
    }
}
