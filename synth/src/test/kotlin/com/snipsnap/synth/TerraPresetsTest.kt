package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TerraPresetsTest {
    private val kitPatches = TerraKits.classic().map { PadRecipe.fromJsonValue(it!!.recipe!!).patch as TerraPatch }

    @Test fun `the sixteen kept pads are factory presets, recipe for recipe and sample for sample`() {
        assertEquals(kitPatches, TerraPresets.kept())
        val all = TerraPresets.all()
        for ((i, kept) in kitPatches.withIndex()) {
            val preset = all.single { it.name == kept.name }
            assertEquals(kept, preset, "pad ${i + 1}")
            assertContentEquals(TerraKits.classic()[i]!!.snip.samples, preset.render().samples, kept.name)
        }
    }

    @Test fun `four presets on every playing voice and none on the retired membrane`() {
        for (voice in TerraVoice.entries) {
            val presets = TerraPresets.forVoice(voice)
            if (voice == TerraVoice.COMPOUND_MEMBRANE) assertTrue(presets.isEmpty())
            else assertEquals(4, presets.size, voice.name)
            assertTrue(presets.all { it.voice == voice })
        }
        assertEquals(88, TerraPresets.all().size)      // 48 kept and pinned below, and the ten stick voices' 40 (provisional, docs/TERRA_STICKS.md)
    }

    @Test fun `names are unique and fit a pad label`() {
        val names = TerraPresets.all().map { it.name }
        assertEquals(names.size, names.toSet().size)
        for (n in names) assertTrue(n.length <= 14, "$n is longer than 14")
    }

    @Test fun `every new preset spells out all its voice's macros and is a different sound from every kept pad`() {
        val kept = TerraPresets.kept().toSet()
        val fresh = TerraPresets.all().filter { it !in kept }
        assertEquals(72, fresh.size)      // 32 on the first twelve voices, 40 on the stick voices
        for (p in fresh) {
            assertEquals(Terra.macrosFor(p.voice).map { it.name }.toSet(), p.macros.keys, "${p.name} names every macro")
            assertTrue(p.macros.values.all { it in 0f..1f }, p.name)
            assertTrue(kept.none { it.voice == p.voice && Terra.defaults(p.voice) + it.macros == Terra.defaults(p.voice) + p.macros }, "${p.name} repeats a kept pad")
            val s = p.render().samples
            assertTrue(s.all { it.isFinite() } && s.any { it != 0f }, p.name)
            for (k in kept.filter { it.voice == p.voice }) assertNotEquals(k.render().samples.toList(), s.toList(), "${p.name} sounds like ${k.name}")
        }
    }

    @Test fun `every preset regenerates from its saved recipe`() {
        for (p in TerraPresets.all()) {
            val back = TerraPatch.fromJsonText(p.toJsonText())
            assertEquals(p, back)
            assertContentEquals(p.render().samples, back.render().samples, p.name)
        }
    }

    /**
     * The PCM24 bytes of the forty presets the owner kept in round terra-presets-1 (W1–W10, 2026-11-15): every
     * preset of the ten voices before BAYAN and TUN. W1–W10 played exactly these renders, so a change fails here.
     */
    private val keptRound1 = mapOf(
        "Udu Whoomp" to "5c1a08770a16062cd6c245982936e4ba55ed842b4e5acd5a62c5ea9dfce4a6ad", // RESONANT_CAVITY
        "Cajon Low" to "549b26a008a05bfe66f8834630b9695616f1f206595cdc3634cc1d943f815710", // RESONANT_CAVITY
        "Udu Long" to "c23b2a05df86d1aeb3983054551fe50e68a9d0be515dd0c5743e6015f10a1b12", // RESONANT_CAVITY
        "Udu Tok" to "a34f64279139e3efbf887d232f5cbea8626a2d6df3cfb36ced92fc00b7eed585", // RESONANT_CAVITY
        "Agogo Low" to "39fc9dbbb42c86414cdbc6ff05353600cb39129bb5e5e003fbc9b38fb0133531", // CONICAL_BELL
        "Agogo High" to "096a427a271a134f0dd2f011b9c988eb1925088200316d17693d255abb74d128", // CONICAL_BELL
        "Agogo Clack" to "680c6a3491297a470c9b83a009147d0ebd048f265c0381c480272dbc4affdf49", // CONICAL_BELL
        "Agogo Muted" to "ad561b375f87fc0e125fc7e0f1857c92670e38499cc47700e5f532dd51921f5d", // CONICAL_BELL
        "Balafon Key" to "048f07542f81578ad2186135541e5e257f7aa77ecfbd5b06510d85bf8c52c4d0", // TUNED_BAR
        "Balafon Low" to "20fe1d6b08b5a96a5a065b989b20b94f2a703b10ef70d5279b9971580a7ba4d4", // TUNED_BAR
        "Balafon Dry" to "bdb10dbbfee4ba4344c5cf4aa970caa2be4c17f52d05a16cd9a4b9a7446e84c1", // TUNED_BAR
        "Balafon Soft" to "f987b6030fe8356d9c007ab9dbe96dfc62314854798b18048f6c3097948b5874", // TUNED_BAR
        "Cajon Slap" to "75b64dbfa6018bcfcd09f4654ff77ca46653aa6175ec8d8e1b992044b552397f", // BOX
        "Cajon Bass" to "1d73c5d64b4b44cb4b453299b16b7982c4c0aee231ce6ea4a1c19833c4765f75", // BOX
        "Cajon Ghost" to "836c597f934fa89df70e52688918813f6f31d266db756b2fe03fc3675bc3703f", // BOX
        "Cajon Dry" to "86ee60df9a0945a470fd196e9875d2623c9db532a33b52466eca348c9d3c9c11", // BOX
        "Djembe Slap" to "966c4a2919039e55d11b9d294cb22120c99249ff026f254851a77fe37f40c2cf", // SLAP
        "Slap Tight" to "7ed2011ca0811666ccf19158c0aa17f7067c33965aaa786d1e4816de03575cc8", // SLAP
        "Slap Deep" to "3b8babc72bddb5c737c4e18814c6a3801741c041b02b4e9b3724efd0f9a84c37", // SLAP
        "Slap Soft" to "5eda12aa790fd52bbec75e9108b03813ea80070665c8d9b1afbf063e3347d5da", // SLAP
        "Tabla Tin" to "40f3010a5e300d8d7979b079e4a10181cbc9adc39cb3690a3d495dffdb51bc06", // DAYAN
        "Tabla Na" to "7621b5b6fe151e5d90ed5c421296be60c70f8b50af65925c0074f99171f934b9", // DAYAN
        "Tabla Ring" to "b26578e155705af924cc1b40f5059c35198026d00dba2e033367653c2e161fa2", // DAYAN
        "Tabla Soft" to "75237aef33c9fee6f5acb9d48b7ce48d23ddd5783c014f97d77df1a5b2c665cb", // DAYAN
        "Dumbek Tek" to "c7a4613fe61cc8cd7fd0840bb5b2123bfcb7c6a184bfeeef568f0e6d74e780a8", // DARBUKA
        "Dumbek Ka" to "bbba0e383dc225ceea24715e1d0e617eb69ce71773410ae723fefc623a79dac4", // DARBUKA
        "Dumbek Snap" to "2e0f6f2945f3b251eef651d578ab6a637bf95e2f872f5df7fd8fe51bdf71a982", // DARBUKA
        "Dumbek Ring" to "73d3857372af686bc0a10ed7ce9a9d507ecdc9249c0302bee0d1d098fda2f558", // DARBUKA
        "Dumbek Doum" to "7f10f53609cf7ea54e7d30d57c81419214faaf8f542120eb6f61d754ae587f98", // DOUM
        "Doum Deep" to "fe060f956d1e6322c8db4f1c2bdac94309b07e9f9a117abd5913a0cc932383e3", // DOUM
        "Doum Short" to "21d99e15b3c3c71672fc906b8e7acde4954ac6da2b177082fb4478bceea0341b", // DOUM
        "Doum Hard" to "c818cabc15e183a217cf307ef45e1b5954c45a17c2bf0044f735d3aa313124b2", // DOUM
        "Djembe Bass" to "c5786575d413bd5fffd6e3f13967c224c91a6b75150d8970a8caf72c7bd7c391", // DJEMBE
        "Djembe Open" to "5127667e4eb4bccba7e034c2d076afeb5579a6f57e029d7d89b51772469c962a", // DJEMBE
        "Djembe Bass Lo" to "fcd7647c0e1661f9699e69d5e00074b3902524ca8d11275344dd9a0342f1b7dc", // DJEMBE
        "Djembe Open Hi" to "07505c7c8ec54dc0f401d1ce49cb5fb21d84284cf669c3c2c3b0811742c39c8c", // DJEMBE
        "Dholak Bass" to "cf08d6ec8375d67ca3861725f115cd651cf8efb4cf309309a02c9b0ef74c46a5", // DHOLAK
        "Dholak Flat" to "42b9655855e240339fe259a5bd2deb25ea360426ab040876844520ae3440f113", // DHOLAK
        "Dholak Swoop" to "e2cf84e569204647c7c25cbc9b0e4f24c77415d34bd8f066573d2a61a898b95b", // DHOLAK
        "Dholak Dry" to "3ad7252d0cccc5fb1cab8a843861796afc375b646869a4ed09de02de207f3e0e", // DHOLAK
    )

    @Test fun `the forty presets kept in terra-presets-1 retain the bytes the owner heard`() {
        val probe = java.io.File.createTempFile("terra-preset", ".wav")
        try {
            val kept = TerraPicker.voices.take(10).flatMap { TerraPresets.forVoice(it) }
            assertEquals(keptRound1.keys.toList(), kept.map { it.name })
            for (p in kept) {
                com.snipsnap.audio.WavWriter.write(probe, p.render(), com.snipsnap.audio.WavWriter.BitDepth.PCM_24)
                val actual = java.security.MessageDigest.getInstance("SHA-256").digest(probe.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(keptRound1.getValue(p.name), actual, "${p.voice} ${p.name} changed since the owner kept it")
            }
        } finally { probe.delete() }
    }

    /**
     * The PCM24 bytes of BAYAN's and TUN's reworked sets, which the owner kept in round terra-presets-2 (Z2 and Z4,
     * 2026-11-15). With [keptRound1] this pins all 48 presets. Z2 and Z4 played exactly these renders.
     */
    private val keptRound2 = mapOf(
        "Bayan Drag" to "e5dc15391d10d21d5600ad9951c78e7dcb90d8b5a5114026f10f2cf95fcfb191", // BAYAN
        "Bayan Deep" to "929fe736329a3e06c703e96f66edd4dcdd0a04b892bb8299db16ed83e1417b98", // BAYAN
        "Bayan Slap" to "aee1277769f2896ba4de959929848c99aad4aa97657c0fdae8147eec7e88f2c7", // BAYAN
        "Bayan Fall Hi" to "c25209aae715fb94b64444a6006026fc29d287ed476b9d09afd798d5c50e63ac", // BAYAN
        "Tabla Tun" to "88c9be917bbcbbd48c6c1f5a022b847dcca36ff0ced321222c40770171ec0c50", // TUN
        "Tun Deep" to "b211fab524c0cc739b1e3d0bd54b5f5c57cd07bc0054c3618ff2a39800a8085d", // TUN
        "Tun Muted" to "d965f85092d6c12914b25bfbf82c12bece1dee0705d69084315951088eca30b5", // TUN
        "Tun Pure" to "8ba273e688d770d561b25c6824d7957fe33810cbb2d32ff6859e27d0b9a3f32a", // TUN
    )

    @Test fun `the eight BAYAN and TUN presets kept in terra-presets-2 retain the bytes the owner heard`() {
        val probe = java.io.File.createTempFile("terra-preset", ".wav")
        try {
            val kept = listOf(TerraVoice.BAYAN, TerraVoice.TUN).flatMap { TerraPresets.forVoice(it) }
            assertEquals(keptRound2.keys.toList(), kept.map { it.name })
            assertEquals(TerraPicker.voices.drop(10).take(2), listOf(TerraVoice.BAYAN, TerraVoice.TUN), "the two voices after the first ten")
            for (p in kept) {
                com.snipsnap.audio.WavWriter.write(probe, p.render(), com.snipsnap.audio.WavWriter.BitDepth.PCM_24)
                val actual = java.security.MessageDigest.getInstance("SHA-256").digest(probe.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(keptRound2.getValue(p.name), actual, "${p.voice} ${p.name} changed since the owner kept it")
            }
        } finally { probe.delete() }
    }

    /**
     * The PCM24 bytes of the stick voices' forty presets (TerraSticks: ODAIKO to FAN), which the owner kept on
     * 2026-11-20 ("Ok, they are good", after hearing each at a soft and a full touch on the kits' listening page;
     * docs/TERRA_STICKS.md). A change to any of them fails here.
     */
    private val keptSticks = linkedMapOf(
        "Odaiko Long" to "a9102ab8d7e7c812881ab780b5455e77f5a04bdf16405b2a69d10e9e486e16d8", // ODAIKO
        "Odaiko Low" to "a83bd06c14b2e9635d3ca0c9447379b7ea700655871719a92bd30ad29bd852f4", // ODAIKO
        "Odaiko Pair" to "165588914a53deeff2ebd1fa7cb7b5ffdec5ef70b98d20e06dc18d51ea07dec1", // ODAIKO
        "Odaiko Deep" to "196d215902e80b176474bc29b8da5115def74064e76584d07bfa0ef9614a2d71", // ODAIKO
        "Nagado Don" to "fcaf9b7a211d1512c9aabb11978bb9d2b5c97533ba13b4df441ebcd5e52ae1cc", // NAGADO
        "Nagado Low" to "3aff93c6103adc432e63f82b08f74c3e1234ea5366ec60b5559e518f8d27253e", // NAGADO
        "Nagado Tight" to "d970d2c1bf7e0fe183c604bf5ee44a3271b0c7c4430a079fea6148795f082484", // NAGADO
        "Nagado Soft" to "f537bc92a1baa2066b8f2a5e4cf1949afb51b8c0bf7dd728a89729a2d892349e", // NAGADO
        "Shime Ten" to "c073e32cd5f3019f94ba80b9a8f7a8fa0151e9af3501005f3fc945093cf897c2", // SHIME
        "Shime Second" to "95ff68bcb52e2fe075d408887354d523e174b011972a25c958b40fb3b065d71d", // SHIME
        "Shime High" to "b5cfc0f35ae32dd2d1d7825d2755ae99afbecdb5f001fa0a2494c2b22be65ffb", // SHIME
        "Shime Soft" to "cb7faaee90b09de1dbca02d157a5a3c96cc8d36dd2255eb1e68a6584c23e6c6e", // SHIME
        "Ka Rim" to "cf2ff4722ddf97b947f87fb11c4280287b461f0cda3420b19f065ab5d3115d71", // KA
        "Ka High" to "fb87d449c69054d4918e25b58ecea76411ffbe962cc1fdccf1c0028a0d91a978", // KA
        "Ka Low" to "94b10c023966bb1d946f7a4a8a5e10f20c81cf714316bd3e507785e0f8beab9b", // KA
        "Ka Crack" to "f1179a8046d9d8297ee870802e6bb2ffc1a333d9d147d5d96e2161e134762c0a", // KA
        "Kane Chan" to "5a180d48b57369c8214390ed1d11c767f6a6aa678aa4fe9efdb7ccfd202fa19c", // KANE
        "Kane Chiki" to "9ad539edbad4a388c095b75d8c19f1c0ed2ec0202e54dbce301015ac7dabda7f", // KANE
        "Kane Mid" to "bdd4c13c239f65ba04ae499b50eb8f242e0fcc4d9457576088404394c6c19848", // KANE
        "Kane Low Ring" to "e09cc7904a0107591289350c6cc73e6f3d01ab42bf7fe243f71fd46f7316d7ce", // KANE
        "Chappa Open" to "08eb23dd35181e4678771e8ed386cf54b754e8afeee5b8ec304d98086fb15b26", // CHAPPA
        "Chappa Choke" to "836e1497e8dfff46a222728267bf517be4829bef90d321186e47781f51f48504", // CHAPPA
        "Chappa Small" to "6be8f28531ff47fe4342261a034f0833477db6e1ac3ef2c1137d25265e8eb45d", // CHAPPA
        "Chappa Wash" to "4496715917ed25066353a67adadda7bf64e3a6c463837e785b9d04d2688f03d3", // CHAPPA
        "Sasara Fall" to "f33ea06fc33e3b91d087f952a31849daa712a7e1b54aa57800f47e93bbe821b4", // BINZASARA
        "Sasara Snap" to "5e5d0b567dca94bc1a6abd26c3621f475d1be2031482c898a86816cd6170bbbf", // BINZASARA
        "Sasara Slow" to "3365559142356d8e607bbf6b1b1d620f93a328db7cab93773dde1d18218aaf01", // BINZASARA
        "Sasara Low" to "f5f6314a45e22c9e13aa7d29956b43e175d5c9450b78837b65e88da88d84199e", // BINZASARA
        "Bell Shake" to "a9df99873ccd76fd34e0a88112dbe448eb599fcc2b6e63c53cfa80ca37731407", // COWBELL
        "Bell Single" to "e597d90eb79612eba69665c55bc6f3aa690d94c23cdb5b752d1044f702fda236", // COWBELL
        "Bell Muted" to "a9a683502979f31bfce104bb4cec64f04aa68110a935f361a9fec1bf058047f1", // COWBELL
        "Bell Low Shake" to "a521c6d6a94588e27cb8b8846c9846ece23e06d03b80d1d2a84e93ace027e55e", // COWBELL
        "Water Drum" to "c78d4551934d367f553c9b9b1356243a1962a262f12c265dd1baee324e61da94", // WATER_DRUM
        "Water Low" to "906cc866720b9a6a3e24306712fc321b7e7b009dad940fc9acddf289290c3035", // WATER_DRUM
        "Water Tap" to "df6522fe7235f0d76d60e92ae2e47494a9a2171e360137e61dc6ed330f121b57", // WATER_DRUM
        "Water Soft" to "f0d82711ba464c27a77f7acc8839c92b926f281618a9d76cc4aa68b59459cddf", // WATER_DRUM
        "Fan Slap" to "852e1d603b0b52b1a264dfa07b1433cf425b629dd647e3cbacce113815a7b9a3", // FAN
        "Fan Swish" to "c075fc6f684afe7198e7589073a148d1ce87fe5c584841906fd44ac1d63d03c5", // FAN
        "Fan Snap" to "4a2099e45f404604e5800b33cc54229be90608e21c2077ef0f64b10b036b5888", // FAN
        "Fan Wave" to "cee02769e24d99e9849d096a7119dba95e182ec3feed5aaad5afb0241ab6d8f6", // FAN
    )

    @Test fun `the forty stick-voice presets the owner kept retain the bytes the owner heard`() {
        val probe = java.io.File.createTempFile("terra-preset", ".wav")
        try {
            val kept = TerraSticks.VOICES.flatMap { TerraPresets.forVoice(it) }
            assertEquals(keptSticks.keys.toList(), kept.map { it.name })
            for (p in kept) {
                com.snipsnap.audio.WavWriter.write(probe, p.render(), com.snipsnap.audio.WavWriter.BitDepth.PCM_24)
                val actual = java.security.MessageDigest.getInstance("SHA-256").digest(probe.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(keptSticks.getValue(p.name), actual, "${p.voice} ${p.name} changed since the owner kept it")
            }
        } finally { probe.delete() }
    }
}
