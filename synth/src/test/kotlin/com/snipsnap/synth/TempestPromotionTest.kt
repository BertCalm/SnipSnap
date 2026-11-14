package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class TempestPromotionTest {
    @Test fun `all sixteen factory kit pads retain their published audition PCM24 hashes`() {
        val hashes=listOf(
            "bc6b9dc21caa5c5f28df3271f20f863b92d2d1cc2fa971edd51efd82125a7477", // A01
            "606196e3ca3108df0291df26a61429052360cb61488c3d0357b0f2060728b927", // A02
            "f2414d3f4a13442eb3d58c8cce0f4aa7abf4d2668dd957c7a8e6cd59913791de", // A03
            "946f1993d1f6eb46a3523cb7256045d451185122bc025aed4656b5d803cf0dc8", // A04
            "ee6da6d4784a03e9f20e5897aaf3e84a9d0e693110fadfc8f3378a8f2d1acfc7", // A05
            "fd029d7261833b45fe5f22782c9bbe1b74336229aa09f60641cfeef9c48cbf88", // A06
            "c6ccb7d1204edfaef4b7fe6b70b3a7acd87a220fd1681deeb18d74defec13213", // A07
            "af24a5bebc15153a7381b59f2c5d642e13131008c2246cad688cdf19a5384683", // A08
            "c28d3ad1a99f681419b845651f274d20470a1efd7f5f97ee0e6b9a2805d2b279", // A09
            "756e5e7ab4da520e04a4b727dd09169e3787f9ee407a76822b80384659712415", // A10
            "cb7536e24748b5b19a0a412094c53992294bf60d548a4a666bd9a2c517fbe2c7", // A11
            "9e33939dcb8c05e5c591bc46452118a4b8e1dd26a15b067bad81a5b5a94b46f6", // A12
            "b2ca1beebb72097e8de0e1db2e1fc7a58501677ffd1a93a6e41c17f2c8c6b2b4", // A13
            "3e469206f71ea16d45b81abedd9bc153de93591349d8a0a33f3b4b920851d167", // A14
            "eb209661e9e3f1a3aafea48d79d77272509cb626195f64ec85fac2e7afa30d33", // A15
            "6b5c5af9312978f19b96a0f7ab0a874fd9628c1090ab33d6a01703c818bfcf4d", // A16

        )
        val patches=TempestPresets.fullKitPatches()
        assertEquals(16,patches.size)
        val pads=SynthKits.tempest()
        val dir=kotlin.io.path.createTempDirectory("tempest-promoted").toFile()
        try {
            for ((i,p) in patches.withIndex()) {
                assertEquals(p,Presets.byName("TEMPEST",p.voiceName,p.name))
                assertEquals(p,PadRecipe.fromJsonValue(pads[i]!!.recipe!!).patch)
                val file=File(dir,"probe.wav")
                WavWriter.write(file,pads[i]!!.snip,WavWriter.BitDepth.PCM_24)
                val actual=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(hashes[i],actual,"A${i+1} ${p.name} changed since the owner audition")
                assertEquals(if (p.voice==TempestVoice.HAT) 1 else 0,pads[i]!!.muteGroup)
            }
        } finally { dir.deleteRecursively() }
    }
}
