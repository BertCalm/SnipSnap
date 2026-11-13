package com.snipsnap.synth

import java.security.MessageDigest
import java.util.Collections

/**
 * The nine exact Wave 3 KEEP recipes, before any global factory or picker admission.
 * Both saved SING models remain alternatives. Resources retain the original UTF-8 recipe bytes.
 * The PCM hashes name lossless native PCM24 references, not oversampled raw or FloatArray samples.
 */
object CairnAcceptedSingSources {
    const val BANK_ID = "cairn-wave3-accepted-sing-v1"
    const val OWNER_BATCH = "trefoil-splay-cairn-published-source-v1"
    const val OWNER_RECEIPT_SHA256 = "8f4a30592cc1f91f9b5368657a051be1846306f496e8ae75d55c06eccaaba767"
    const val RECORDING_REVISION = "6edd57b1c6e55562fd326adf68caf64008a370c5"
    const val ACCEPTED_FEATURE = "cc7c9a4fde8fde4681d6f75e2310dc1dc9948c17"
    const val INTEGRATION_BASE = "6d2e954dda540cc763a418a6785fdf404fb6a4c2"
    const val CYCLE_FRAMES = 88_200
    const val FULL_FRAMES = 176_400

    data class Evidence(val path: String, val sha256: String)

    class Source internal constructor(
        val id: String,
        val recordingId: String,
        val model: Int,
        val midi: Int,
        val reviewedAt: String,
        val originalRecipe: Evidence,
        val originalCycle: Evidence,
        val genuinePrecedingHistory: Evidence,
        val ownerDescriptor: Evidence,
        val ownerAudition: Evidence,
        val cyclePcmS24leSha256: String,
        val fullPcmS24leSha256: String,
    ) {
        /** A new typed copy each time prevents a caller editing the bank through a mutable macro map. */
        fun patch(): CairnPatch {
            val patch = CairnPatch.fromJsonText(originalRecipeJson())
            check(patch.model == model && patch.midi == midi && patch.voice == CairnVoice.SING &&
                patch.velocity == 1f && patch.startAtContact && Cairn.isLoop(patch.macros)) {
                "CAIRN accepted recipe identity changed: $id"
            }
            return patch
        }

        fun originalRecipeJson(): String {
            val path = "/cairn-accepted-sing/recipes/$recordingId.json"
            val bytes = checkNotNull(CairnAcceptedSingSources::class.java.getResourceAsStream(path)) {
                "Missing CAIRN accepted recipe: $path"
            }.use { it.readBytes() }
            check(sha256(bytes) == originalRecipe.sha256) { "CAIRN accepted recipe bytes changed: $id" }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private val choices: List<Source> = Collections.unmodifiableList(listOf(
        Source(
            "cairn-before-48", "before-48", 4, 48, "2026-11-09T01:15:07.928Z",
            Evidence("sources/wave3-cairn-before-48.json", "1f32c9182ce3ae9f3bd488539292f2dc5836d93f5408427c0cc4fb4da297e318"),
            Evidence("sources/wave3-cairn-before-48.flac", "7aa6285b866ae82506b851aaa2ca5114317698cb73766cc2b57f38377dd5c8e5"),
            Evidence("sources/wave3-cairn-before-48-continuation.flac", "7140eb8dac08eba3382b686814731e3ef93833cb65a6be465fd0e23ad776a0e7"),
            Evidence("recipes/cairn-before-48.json", "69f6dafe550d1b131c46c3e4a37945e9c86d8e7b347019e4eb7416b677bb4e43"),
            Evidence("audio/cairn-before-48.wav", "9c4495ce8878c393712ab951045ef3591a565d0fa3110c137e18a8bf85220940"),
            "8c82232500bce4c4f530c1d81ef7c0986a43ccb074dc23d9050635bff2a3f7c6",
            "eda48495fe873ebd0bd96362ede218a4503df66ac3d7e2a890c47b96d0dd717b",
        ),
        Source(
            "cairn-after-48", "after-48", 5, 48, "2026-11-09T01:15:05.262Z",
            Evidence("sources/wave3-cairn-after-48.json", "5c55228354361d34329e1aa2dcba66cb3bd74d4703742d37375c959132111e8a"),
            Evidence("sources/wave3-cairn-after-48.flac", "431547cdf8be6b078b755c88a455adfbf5762cf5fcbbdb5cf2e7028ba2df3f6e"),
            Evidence("sources/wave3-cairn-after-48-continuation.flac", "db800d1ab6adac873cffa59f281254fe5a01c72ca33f8ed75878dbc021dbe3dd"),
            Evidence("recipes/cairn-after-48.json", "32a9add2b35e06d3675fe6b98b993ba17fea11cf5b82148fc927fe368bb8af43"),
            Evidence("audio/cairn-after-48.wav", "6982c71df9b00c392f46a9aae89b4ee5231a8376eaa6bf8385793b2afa61a37f"),
            "0b66499d574a90d64d3f9e77e016e4f47ef8dafecaabd41f0bfde641f7f877f3",
            "8633c43ab141f2a938aa4a9a32a7e8bb2c2fc182983b06f2905165318548c342",
        ),
        Source(
            "cairn-before-60", "before-60", 4, 60, "2026-11-09T01:13:20.910Z",
            Evidence("sources/wave3-cairn-before-60.json", "43bf6b83d89592fb28fc15590d03fe55e648c842c2b675aa20d84770aa6cff29"),
            Evidence("sources/wave3-cairn-before-60.flac", "5073884c746ef8595b081b5c51c85eb8f7f83633bec3b118c6293ca028091ab7"),
            Evidence("sources/wave3-cairn-before-60-continuation.flac", "ca2cc393b49f06b9b017dccfae0874b97b1d465cdc4841719b867577a5a9240d"),
            Evidence("recipes/cairn-before-60.json", "bbb561507d80424df09e4c067ed9ce3d359defac8477c925e1af1e11f3e21931"),
            Evidence("audio/cairn-before-60.wav", "05d530ac93c08b665bd522f0f10cbac01a4ce9dd78ab07d0b02dc0c6b554fb24"),
            "0cedcbe67d188f9274a1f7b3c543d35b92ae54b3fa5e1919d1cfa6159e3044dc",
            "0c4888ecbea3f5a1046680204004d993c24419a209da99654ba55a6de7db8a58",
        ),
        Source(
            "cairn-after-60", "after-60", 5, 60, "2026-11-09T01:13:26.438Z",
            Evidence("sources/wave3-cairn-after-60.json", "6fb5831a4761f332c14ae33db56444b8765cc098f060dae7932261f6ecc9ee74"),
            Evidence("sources/wave3-cairn-after-60.flac", "44289194a9a6620acc3542b305d8fb6a486ab7677a5983af7dc21903f00535b2"),
            Evidence("sources/wave3-cairn-after-60-continuation.flac", "e0033c15ba585d2df48ca31b675a7cd2409b4ad8d82a067b1810de1dd8e4ee69"),
            Evidence("recipes/cairn-after-60.json", "cfac208f9e5647db3fce70eecfc1531decfab4284b3e86896c296830e0a4a4d7"),
            Evidence("audio/cairn-after-60.wav", "1c2534bb5b1083ddbe650170dc2ac3a5f00b8cfc0125ca248fb22d7d2b09bf9d"),
            "a591e48f2b9d8b833664f86db9e3c996320499fe7ead735e31bd54dae573fbb2",
            "9e448dd6a54448812b0ba7fabd6cb9681ffb7e84009af36b4aa17f6be1c0d8f6",
        ),
        Source(
            "cairn-before-72", "before-72", 4, 72, "2026-11-09T01:15:03.002Z",
            Evidence("sources/wave3-cairn-before-72.json", "3069b24cf1f82828bab9e8e92c9b98967f7f24bef305cfb8913664f32e75b2ef"),
            Evidence("sources/wave3-cairn-before-72.flac", "06a48822c15864e8e5c6d2d5e7c3a7154d0db470977cfac8cf9666a48877727d"),
            Evidence("sources/wave3-cairn-before-72-continuation.flac", "14f44bbe79df9d7ccac72f658c8d49d6b3e569d7819bffb9d89166e7cd090c96"),
            Evidence("recipes/cairn-before-72.json", "9caef2a3380adc24d16aeb13b4a49c536ebbdff4339a1d727ccae1869b6d8d46"),
            Evidence("audio/cairn-before-72.wav", "b9a1d2ebb76fd2e3195b4baa95135b36bebac66607762b021b627ff60f4693a9"),
            "5941c37cdd172b3e8c0ab6abed0cae217a01dde7ac0093c827c9f68437d662c7",
            "3cc40b1d7935c54bf1232186d2e5ea1d3f447adf42a36a4e545b8671913dbfb6",
        ),
        Source(
            "cairn-after-72", "after-72", 5, 72, "2026-11-09T01:15:01.178Z",
            Evidence("sources/wave3-cairn-after-72.json", "160f00e8e1b7ade6fca90ab15889c61f0f81ff66f698c0a2256a74b38bc9e9d4"),
            Evidence("sources/wave3-cairn-after-72.flac", "60c351c5823cadd7be485e8cfbaa333bfb2b1f831026200c3d719cf458e81b7d"),
            Evidence("sources/wave3-cairn-after-72-continuation.flac", "5f61d3357aab9a1771a2e4e3102d87b2f87ad2a3a09bb8bf7237af855c0bdddf"),
            Evidence("recipes/cairn-after-72.json", "e0b3d54993b2d5ca34b337d82f54ba37bd50e091401f35d3180c40ba2ab86ba3"),
            Evidence("audio/cairn-after-72.wav", "66538872ba1aa4f3e7189eefcf9664c4f4ea1c6c5cb901ae45ce3eaf76698603"),
            "1f1ef4c4ada448af03af3c453156aed30f224ae868ac9541332bf3513e24fbc1",
            "5849335ac2d3e3b614e8ef3c80a7fc585e056507bd89d6bdd74d5af722380a55",
        ),
        Source(
            "cairn-groove-0", "groove-0", 5, 60, "2026-11-09T01:14:11.642Z",
            Evidence("sources/wave3-cairn-groove-0.json", "716bf855f68d3603cb8a60275673ebf8cca763e3509d2d6b3c7b394b14fc464c"),
            Evidence("sources/wave3-cairn-groove-0.flac", "e920f3a671b7f55dc4f999c2307c2e3cb4f01b2066e3535d2248248648eb9dde"),
            Evidence("sources/wave3-cairn-groove-0-continuation.flac", "95f8d6d0c39a80eb80a6c3e1a24258bccfd4b030c82692ce8a3e58a692fad984"),
            Evidence("recipes/cairn-groove-0.json", "df4279615bb00f37f6f5011252da914fb91066c64c4f197a1a83ef1c76212120"),
            Evidence("audio/cairn-groove-0.wav", "f46350ff0a4810c13278a0b90bbc6bf4cb11c1f0548a670ba240ec00244c28c5"),
            "b8b51a7064d9006edd95f4106cf246080980cc32f16f46f2633a9d44a7763c7e",
            "4cea24e3b1c030eebb37f7955f95cc8fbe883e25af058796efa687a364ede077",
        ),
        Source(
            "cairn-groove-50", "groove-50", 5, 60, "2026-11-09T01:14:13.897Z",
            Evidence("sources/wave3-cairn-groove-50.json", "3bebe5e825ba73400777f08c137e5d20bf3f9ff942e553c6dfaeacd61196dc5f"),
            Evidence("sources/wave3-cairn-groove-50.flac", "3f90f242d05a843ee8829d1643d99c5d16620c2282ef3b741034a30692dae305"),
            Evidence("sources/wave3-cairn-groove-50-continuation.flac", "535d2d4fd1505449362589d4a00765c3c5c95e0c1e28e2cc6e15f8d9985a1560"),
            Evidence("recipes/cairn-groove-50.json", "c361d703624ebe962a08676842582c6170a6f6b7d61c88d2f6f741599d1f2aab"),
            Evidence("audio/cairn-groove-50.wav", "e639c63c3b8569045b4ca05162cc1c309b267d854898943a3be5e7629b2dac60"),
            "504134dc2640682e1c5de1fb4ca3b6a3509c257bbee148d592ce3a3fdee437cf",
            "fadad43176bbd30f62a3208c947cede788bf80c7c418a6d4ad11dc96eb3c8b66",
        ),
        Source(
            "cairn-groove-100", "groove-100", 5, 60, "2026-11-09T01:14:16.079Z",
            Evidence("sources/wave3-cairn-groove-100.json", "1258ce4b0063507b92ef4c1d02046ab60a56c953099034c2b171f2c122eb7fb1"),
            Evidence("sources/wave3-cairn-groove-100.flac", "fd60d42419d98718fb84a3dde25083470b12624fb684433a9013336aed67bdb8"),
            Evidence("sources/wave3-cairn-groove-100-continuation.flac", "361d0955e3de764c52da78bfd6a16e936b3294d74e3da184fc4a9d0124af840b"),
            Evidence("recipes/cairn-groove-100.json", "e73f924e15b09ba1e1ea4a7382df4ef46b64be69077a49057f290dd9b00391f5"),
            Evidence("audio/cairn-groove-100.wav", "888ef750df6a5ac75f2b3978661e10600c70ddf7bbef561e485eb33437832d8f"),
            "dabe5430270c02dc292ab97c19736b81f51f3baade8b967b686cdd1e9417694e",
            "d11898b3100cacbbd21f0bc463b92eb0797321651d73015ddcbea2d74a57d4eb",
        ),
    ))

    fun all(): List<Source> = choices
    fun byId(id: String): Source? = choices.firstOrNull { it.id == id }

    internal fun requireMember(source: Source) {
        require(byId(source.id) === source) { "Select one of the nine source-bound CAIRN KEEP recipes" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
