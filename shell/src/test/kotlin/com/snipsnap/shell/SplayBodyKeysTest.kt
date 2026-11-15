package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.SplayFactoryPresets
import com.snipsnap.synth.SplayVoice
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The MAKE INSTRUMENT keys of SPLAY's five bodies as the owner kept them (round splay-bodies-1, B1-B5,
 * all KEEP on 14 Nov 2026): each body's sampled roots C2, A2, F#3, D#4, C5, A5 and C6 at full touch, and
 * C2, C4 and C6 at the softest touch, rendered from the keygroup recipes of its kept setting.
 */
class SplayBodyKeysTest {
    private val loud = listOf(36, 45, 54, 63, 72, 81, 84)
    private val soft = listOf(36, 60, 84)

    private fun hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(x: FloatArray): String {
        val bytes = java.nio.ByteBuffer.allocate(x.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in x) bytes.putFloat(v)
        return hex(bytes.array())
    }

    /** The desk clip: each note cut at 1.2 s with a 50 ms fade, .35 s apart, trimmed to a .94 peak, as PCM16. */
    private fun deskWav(takes: List<FloatArray>): String {
        val rate = 44_100; val fade = rate / 20; val gap = (.35 * rate).toInt()
        val parts = takes.map { x -> val n = minOf(x.size, (1.2 * rate).toInt()); FloatArray(n) { i -> x[i] * (if (i >= n - fade) (n - i).toFloat() / fade else 1f) } }
        val out = FloatArray(parts.sumOf { it.size } + gap * (parts.size - 1)); var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size + gap }
        val peak = out.maxOf { abs(it) }
        val y = if (peak > .94f) FloatArray(out.size) { out[it] * .94f / peak } else out
        return hex(ByteArrayOutputStream().also { WavWriter.write(it, Snip(y, 1, rate), WavWriter.BitDepth.PCM_16) }.toByteArray())
    }

    /** Per body: the seven full-touch roots, then the three soft-touch roots, as float renders. */
    private val takes = mapOf(
        SplayVoice.WIRE to listOf(
            "16e8eef3a6576977ff08c4debcdeec734c22ef4fb147afca79d41d33671374a0",
            "c3e9d72d074761fb9b7580f2f9f914be7b4e68b2d7a05007170f6e025c1f2b2a",
            "586d2a7bf9dcfb8238f0890cb75edb3ae6b15afe4e22a91af362e2b6cc729e29",
            "fdb80182b8faf77dcc421fb29a2de07294319b3cecaee00f8a8ec03679ebb1d3",
            "902dd225bf55ab3f12518b4fa9e165e41b8b3e136fbc692e83753a2829291cc5",
            "bdeae82c2742a157a80b605643eda244903671c72e8a230016621451dc95a6a5",
            "0d62325d08ed48d8ca5775a4fc4df5ce04f88c5bd033fd57efd9e0d05889c76f",
            "3b17f0ab674e86e0776c6a4786b8bacabcffcd3b6eff0b08c06e56aff5d9c877",
            "fe3e5eb1ed584089ca3f6b4d80f8df3000c08a99a43f38a8324b219ac69a9042",
            "7788a8c12fff2b5dbc568c4984738c14ea209b311c493ae12d442e74128e6d0f",
        ),
        SplayVoice.RUB to listOf(
            "166f3589113821c8d92495fd30a61c8dc78db9caaf812572963921171c888ebd",
            "fcece88201f56c5f4b81a76ee29024340a6a057a9c40c1771942b64b53e77ab9",
            "4dcb6b9392b2ca87e969b307e631fc624ebbe294146bff3518fb77d511eb7363",
            "f68410e8f4f685c31ee105dd2bc6632d32ec44f629a4170e45b7fa387e95caea",
            "713b4c697ec764c9cf54edc675b5fd6a1663990e574114cf2b753bdaa024cbce",
            "6e1fbbaca6307600a685007feb1bc0a458e21a343d323759d4e8fa2a4e67922b",
            "7f3af30b4189dcec860b71391e7709a00eb06f4caab7f495f685a6ab845555d9",
            "59b605bb1c863ce171d08a86ea905406d4beaf2cb24da4aee157a2f97ba2ec05",
            "865c8c5ff05fc02b5dd35813ea4952b93a6b676c6b6d0a7880210a6b413fc07f",
            "370ab21d41eef31b47d98c3bc96c5b8eb8c8e7560a4f263d5507307b7c469192",
        ),
        SplayVoice.AIR to listOf(
            "6b6d07ff1a940c2d307dbe5bd68f0da7464f0b1aaa92db3476739694db9bad4a",
            "0771f7d008d90037c6f5c62e7b29b41f4d1b14bd07923b5870c1c383bd69b5a3",
            "eb864db4775e8d7383b13941ba13f224418a8168ba5d38511264199f40cd39ee",
            "ecca9d991b834cc0b3da8314576bf9d79a21218e5f98297dcdd30047dd2748d0",
            "a3a85e5111a61092572cb052d4b277950299042777dc9b8282ee9ddc3b92624d",
            "8cf86fdd61063bd1431706e524dcaf4e064e29823892bc28c8c5501471f2129b",
            "cd1b9c1c19fbbf32c9206fff3af4e43112f1816737b2dea4e29c3d2abd5529f9",
            "ac04b8d585d57725cdbeeca884df8a66e81f82807220b4bec853b2485267e3aa",
            "7915b21649c3d3cb25a59d58fd5fab1edb4bca43b88671d0eae6e26c9b57bfce",
            "90d1a0df5072882bce3d988c90eb5d1e8166879db56ed0c27aa3ad7d41c889d6",
        ),
        SplayVoice.BAR to listOf(
            "495efec0874f9e6e6ce54d6d6aa5d97429d38027ad12baa1561f763fb8f3ca28",
            "04cd33a6775fb0a4f0b6ca625bea46f651879e636e6309991e76221b5d253e69",
            "7973875ccdeb794fc4b42f8fb37159368c6333283c94ab22fff8e6be63c2ce48",
            "3fc41302748d7a8589d48c5b4a6f4e8aaabab4f55e9a48b35c5dae1662c0fd95",
            "b0486f4982b5cbf5a292759147430913746b6e8ba0c4c1268fd292cf448cff89",
            "090d66c70ec39a200d5035953f2613b684e2b3b649059bcdd7b87bca662281a0",
            "40dbd42c9e5d95ed570d18d63a8c9acc2705ff57d20eca1d6e22aec367cd494a",
            "d9c409cae7445b8fa1bcd9369bf36067927d5126a8968e463af6cf4844cb7536",
            "8d9bdb6a545e9edcd05e9821b38c7b8232dc0caf0a964b691c5f4705b8dc122b",
            "20cf31aca1004e6d41e8e65d770b52b102a585809526949ebb5959933a86f4c0",
        ),
        SplayVoice.SKIN to listOf(
            "4b6415d21fa5fb77a0280769fb71013778a6e51fe4bcfca3aa6e22727c64e1c4",
            "f70f8c20fbec1dc4d815e43d5ba978f171f0d3b98ca6d5d680e7a4a593608910",
            "acc702eeee6b10383fb269d7e138723542aa529276ecf704d5e3f775beb55804",
            "559ac21febe0debc51c3c706b7fe75a3fa40e1025f749247ab9f2ce1db1e20b7",
            "2d98db0e3753c38a68b305f79c3744267d108751c182c10320a9427989add742",
            "2bed0555b50d8537287a2baf0a105cf1263a6c69374ab8caf99e5c19aed89f7c",
            "a4d671564e2528825de7cd34739e124464f97562e1ce6d20f6fdb570b508e2fa",
            "5012b036c36ab899665eff2f890a8a69f52534b6b2e7b4df1d87b5002a8c9690",
            "9c6542ed0461c4db71008098604953c474bb13dae1ffc9d4b7bc6d26c9e0e4d4",
            "350470c7696aba45696819693386ba17447c0b920368658c0c1958ec6c3d3322",
        ),
    )

    /** SHA-256 of the five desk files B1-B5. */
    private val desk = mapOf(
        SplayVoice.WIRE to "3be596bbd93629e37d2d0a5a0a0897bb797712c176984581f2f379a0ec7a7c31",
        SplayVoice.RUB to "de0dee472c8f9c96326cac94b5620384a0b12b76bac6e8aa838d78e62939b6c6",
        SplayVoice.AIR to "b3e26306c797091586e39dfb6b96b3d302cbf11fd31866337503b4b6ba61e01e",
        SplayVoice.BAR to "643b104416e6b9db84738360d4cd3f3083e1369641a53ba353550dc6f3d76eab",
        SplayVoice.SKIN to "a6c441abc4ee3d0a83ea9fa83c99e000c6343d99f82a7e397e7acf0f46e3b009",
    )

    @Test fun `each body's keys render exactly the samples the owner kept, and rebuild the desk clip`() {
        for (voice in SplayVoice.entries.filter { it.body != null }) {
            val spec = SplayFiniteKeygroupMaker.Spec(SplayFactoryPresets.forVoice(voice).single())
            val recipes = SplayFiniteKeygroupMaker.recipes(spec)
            val heard = loud.map { recipes.getValue(SplayFiniteKeygroupMaker.Cell(it, 2)) } +
                soft.map { recipes.getValue(SplayFiniteKeygroupMaker.Cell(it, 0)) }
            assertEquals(List(7) { .8f } + List(3) { .8f * .3f }, heard.map { it.velocity }, "$voice touches")
            val renders = heard.map { it.render().samples }
            assertEquals(takes.getValue(voice), renders.map { sha(it) }, "$voice key samples")
            assertEquals(desk.getValue(voice), deskWav(renders), "$voice desk clip")
        }
    }
}
