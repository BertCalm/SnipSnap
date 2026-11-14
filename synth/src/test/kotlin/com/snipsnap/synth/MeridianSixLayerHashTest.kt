package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MERIDIAN SIX bank's 48 native layers (PCM24, the kit's own WAV settings).
 *
 * [UNSHAPED_V2] was captured on the base branch before touch shaping existed (bank v2).
 * Rendering each layer with no shaping must still give exactly those bytes, so shaping
 * changes nothing unless a recipe asks for it, and every hard layer stays as kept.
 *
 * The owner kept both auditioned soft/medium amounts, A (.5) and B (1.0), in
 * meridian-touch-1 and chose B on 14 Nov. Both are pinned, so changing
 * [MeridianSixKits.SOFT_TOUCH_SHAPING] between them needs no other edit here.
 */
class MeridianSixLayerHashTest {
    @Test fun `bank layers render their pinned hashes, with hard layers unchanged from bank v2`() {
        val shaped = when (MeridianSixKits.SOFT_TOUCH_SHAPING) {
            .5f -> SHAPED_HALF
            1f -> SHAPED_FULL
            else -> error("no pinned MERIDIAN SIX hashes for touch shaping ${MeridianSixKits.SOFT_TOUCH_SHAPING}")
        }
        for (slot in 1..16) {
            val pad = MeridianSixKits.buildPad(slot)
            val bank = MeridianSixVelocityRecipe.fromJsonText(pad.source.getValue(MeridianSixVelocityRecipe.SOURCE_KEY))
            val expected = shaped[slot - 1] + UNSHAPED_V2[slot - 1][2]
            assertEquals(expected, bank.layers.map { it.wavSha256 }, "slot $slot")
            assertEquals(expected, (pad.softVariants + pad.snip).map { MeridianSixKits.wavHash(it) }, "slot $slot audio")
        }
    }

    @Test fun `with no shaping every layer renders the pre-shaping bank v2 bytes`() {
        for ((index, entry) in MeridianSixKits.entries().withIndex()) {
            val hard = MeridianSixFactoryV2.forName(entry.factoryPreset).copy(midi = entry.midi)
            val hashes = MeridianSixKits.VELOCITIES.map { v ->
                val patch = hard.copy(velocity = v)
                assertEquals(0f, patch.touchShaping)
                MeridianSixKits.wavHash(MeridianKits.sourceRecipe(patch).render())
            }
            assertEquals(UNSHAPED_V2[index], hashes, "slot ${index + 1}")
        }
    }

    @Test fun `pinned tables are complete and distinct`() {
        val all = UNSHAPED_V2.flatten() + SHAPED_HALF.flatten() + SHAPED_FULL.flatten()
        assertEquals(16 * 3 + 16 * 2 * 2, all.size)
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    private val UNSHAPED_V2 = listOf(
        listOf("bf090f80c61e18799c92e0e83072907302403cfb65ed605135eaef49cdfaabce", "0ef2d455901b47fde32feae6a9cdd09c6be1a3f3757bca5bee0889c28f0c9a96", "f1b4a3b57256402dc6dcfd0cbfbe633a932f3dfae56e5645cfbf24f6da22d47a"),
        listOf("f68c5016342cc2cba692a828e6c734d9c2beac715fe08851ef87c08169486df9", "304e7bca8441dee64953e7f9fbe72373e99bcaff8ddc529140ffdc323feb7954", "9ec494cd7e8b8a7f03099dc90d929352bb9dfb67fcc7112973472624c60acedf"),
        listOf("a6521d23a5282234ee7bc811d16e343f461c95d4d27f385b14b058bf12fcb648", "0313d930dbfa8164e691405f7ea3ba3e9e659abbe807a5efb1716986996892ed", "7a453a78e5c4e57e6e9bfc06179a89d2c1eaf5bed6dac49aa5181ae5534cd6c9"),
        listOf("2eef8a0855b8a8e4899f568ae66ec5c51d496801829e913ed6cb619f756a76c0", "8a5ec6301a2b05681b7a6239ba4686a059e436435800df6e6a89a9039e5720e1", "08839e4db1d145a9410b15a9800420bc2a35c4e56a458a7ad314deb6080f1059"),
        listOf("ceb50939565e2019b783630740e52d1aebdd2060d42c1685177c9165feb8cb2e", "c3c253a5bc786ac2b2e854e4a68be518b72b207992f9b6d0476dc514b1dc588a", "ca5071333ee893f6bd5c64e2216e7d9e39760c80040ae0794231bcff715c5e14"),
        listOf("d8d73bd2840e3eea7f980db16a5eced13290fec3cc4db8becac6db46b118898d", "6455fb0af927e900066fb074281482baab6f360b53ce270eafbe662e7202c7f5", "86fdd7c0a1d5c514340effdbbf9f6ea35c98d51f91af2055b9e62a8076d1b562"),
        listOf("2bdacbbd4710d5b5ec19dcc8185a6d20e991627ab82a2ca195220e6df42c281e", "406962c607970caefea48837a758bf5ea728e56658302069ea30ebc138628fc2", "956b40b6b94226133fff3e6720e2bcfd30a2d04dbe700ab34b0d07fd9995a61d"),
        listOf("36e0dd01ea537cd3c78e2e4b264fa411a667adcdf29a03fb73a58cbc559c3c9b", "8d72dbfde39474ef4dd56d88994d176955f77c02eed9b352621c1ab822410722", "d818b1bcb3ec78d158022856eb3539a1de33c780725ffba63f72b9b94642d352"),
        listOf("d855d6441d7103229d3b2475937a0c458b231418d3cc9f8d9e9e829087a584b4", "703a6307fa8a1043bec94a77ed123f9a879cf3b5d7347b605de9d51542663c35", "99d4cf35c8aac4701ad7dff18185f8742860ab237998bb5550b26daca6d73199"),
        listOf("249599242c6396594c7af93fdaf2df596b361a7844251946ca0f349106e35f61", "f68266900adddf0ae7d8d6649cfdfcfc5c20c9bf3845f0e47a0c106f326ea8b7", "a8817de64b20c9115dbb1f5e40648e793ee259fa1d4cd30ef4cc37d496d8ed06"),
        listOf("8b75f33c1d51a47051695ac7f81d32d13d51dda8d599db7c1247cea12fcc7138", "25b429f17e3281bd724f4d7ebb176897f4c35f9fd3cb6541c7b062e3ae89b959", "46552ebc8adfbba4a7252d78bf8bb8cce1411e34a000ea3d3f1efba1b97bea0c"),
        listOf("35fc1e9a0ac960f6b6ef0fe61524327be133ed5d3b6e4a0b9a49d67fd026a259", "f1f14c622c434b21572a4bf066899eddeca69418c5daa5f6cf0265dd8cfb4e91", "8172228491921e4eda1d58430db20057db978ae60aabba7346f4e1e18148e211"),
        listOf("b2e63a23d36dd47bd0d31c18cd42cd4c6c9cb0dd9dac20d6e78b7d0cbb8a8dee", "7a05ef811c6d6d6ee6c7e23a914893e6390c5ec62e4af7ac56880a90a73f1e3d", "293e19ab28b9d67cc04c409d0d0f11f7146f26abb9d305ef247a0c25b5eab67c"),
        listOf("d81a8f59d691286851daa4efa03743e38d9f6418c765c812ae1f4452a716ce2e", "ff2859f5abcd8c861dc9fe56f81a1a69a232b68e6bb921145669073aa14a53d8", "cb636062336e4bcbcb6fa3d35a24e09f0a0c8597f7d8415988861a92f7adfe6f"),
        listOf("75e83706b68c7467d6eeb4dcfbd7bd052e9ed960cad2fabe7ab9c199eb126534", "92b63aae37183f47319cf5a0a674e1ceca2ec1e39dfe76e196291b6cf883b6e2", "3cd909d0b79d0a9de535ba1b637d8f77005e03c6758ec4156bd881336780da91"),
        listOf("6ca5cf4374b5037cabcca57c08804c10e9676cc7cc4707f9fa9bc0290f6a4195", "ee0c1f4d1216299da52126db83d7ad6df9a7a9c46bba4d1aa62c52c4a4d39b11", "880ad2d8575256f16de6a8187e29f1a459a477c3ac6139172b38c735d77c723f"),
    )

    private val SHAPED_HALF = listOf(
        listOf("852851f957b89c5f18810780b9856ec946d35782da39dba25df4e74e9bb7f4a2", "1ffbcabe2e81c1e34df63f99b5a2ecf371d79395f015e718b0ce3119d8729eb1"),
        listOf("6aecc3f4ce544bb35cb530edc38696672e8d009e8ed323ab2ae8b166e7222a9d", "92235f5e55e6378a57f4f9d58e09a31932d675f01212b8ab81cf2eb94082d417"),
        listOf("b72104327172a2e6a5cd47b36a3a8dd823e2434bda927ec977e1d33093298dbe", "b6cf34662f9471a7160d90558e8d5f62604238312e81c87351f6e09d6606f802"),
        listOf("cba213f1f473d19fb7ecbc54f2130a4efd1fb9fb771ee2cee56010782edcf433", "9f77313a8a32ae6a88be8b3104a26cfe2d5b838474cc2784cbed2833fffe451a"),
        listOf("faf0b525f1f3e1a450f7564adb36817688500473d995a0025dc2ca151f37d430", "8a3d6f7d97e6e4ead5a564c44ca17c70ba7e62a32e060ac606788574eff08633"),
        listOf("8d01950b4a2ef1bc95965fb83358cbccf3cbb550a49850b1d2ac4d5d1dcee9cc", "60b27d771823f7c04ab0720176bd8a347ce779209114ce945b053d6aa8ea84db"),
        listOf("21cd08d085999c909e6666fb89756192d13726c08c431ee70c2a6045f99c5103", "22ada9c4603190f8d081ad9050c90033c479844e779fd969ef6e30755832e0b1"),
        listOf("005ba183dc12b540b09929a4bf040b8ef58ac37558990e7e7fbbdbc2bff8dac1", "fe806bc5a7025612dbdb9964b9930bbec32ba90afa7e7f4a6d324629c68ed7c8"),
        listOf("e576306065b322a8f63dad5173a23573f5966530d2e614f9332edd19438cb7ee", "2fa1ab45ff5d725c76265d15553dcf5fb1dfa0ca072621cf4517e6a6a3ac8fda"),
        listOf("aefa185ede53523aee8bf58742e9e0864432a3135f9c8d40ece0acff35fdbbd4", "d0f8969beb96420bb5c1b5ed2f04ef7c45e0c15e8c66f015a96fb8a419d77488"),
        listOf("80bb8aff4db3a155e74bbfe1fe660e236c0f505c3aadc42472349b550f5237bf", "9e097070c14c9925a7d2a8b04d3563b9525c17becc61210c7a85d499dad1025a"),
        listOf("b33ab9a385c7faf16bd63c70cd143f2d11e45bb5c2cd9150f75d2fbfe187567c", "7a8e4406ed5ea4eac6f750b891bdf9298e1ec892990bf424cef34d3c582bdedb"),
        listOf("2bd9f91e9bb4c313e1a4dc4550fe9ea93ce5071527e7700abf2f2bb341dfe93b", "9760ae593c6d91bb9558d4532d7c3d536c6b47bf4fbb9c60ce2f71f08907b1e0"),
        listOf("995735de2d90186d171204a6f100f81eabf843cf8caaca8f11768043ca7f832d", "0e652005850a75685d6d772c77e083b86b6155f0f2be74c4f7b5f212de704ff4"),
        listOf("f5949833174dfa34be32a6593c481bfe01c4c2fa7f921884ab3e3b6d5e70905b", "c17ea58f19d1e2f8791667253dedaf88238241be9a5447708433e8e50364a638"),
        listOf("204a8480e9a635a071c0a0d97ccf9cee66ed1ea01e9420005228e0b35c3c4938", "debdeb13e6b71c85c3cda0048d9d023f85f0eddc3bb99c26ec32b3580570c4ed"),
    )

    private val SHAPED_FULL = listOf(
        listOf("55b93b7fff547d52da9b088ee36395904edde1b67bc51d267c30615bec9d6f9c", "a24900b1d6b6d6eb1bd2c84af41de73b2da237eec7172bcecc52e0d9ac12f2e5"),
        listOf("53fc3d4d12018f02d8e7d4da80260768117288c7c89f98cd87159ed4ffe3cd2b", "c5399ee8ab4d8d63da16a89918a892355a5574d519fc1b5a2fb7270ad2026a6d"),
        listOf("0e407698fc220432956861aa47cce6723714bb02fa163c8045bcf27b1ee5528b", "e47442d6fffa17e4df3d466a7fc7c9ef4ea1fcc2f9a2fafb79da1bae8762ae2f"),
        listOf("485bf325d6846bda9949a551e87fa9afb57b032f177f522669513191a286f474", "4e1ff33af8f33fef32c92fa875d008fcfa5cca4c3a6713a726dfd623ec76d5b4"),
        listOf("253e5d84f250c7871bb380e438aff788fc3fd9641ad6f334faed7f64358ab709", "22ceac6639397f180ece2ca9ba079ef879fd325fe795f2cfcf4ce072dfe7e58d"),
        listOf("67f723f94e646888fbd1c9efbe1597336bc30e26e5e4714513f01597d0d510a3", "ebe45bde16ec2ffa4d8cdfb59ff83095a688009e10df0cd07c3958c8893d78fd"),
        listOf("f2ea3ce1635e9b5a51b9e5bd5dbe9b9a757b1f6ee0072617040f2a782ba51244", "b91557e80d76fec629bacec7c4cd86387f057b737bd1e61e78fd0cf5ce589268"),
        listOf("c97601c7e9b52c6f6cbb0882fd6942a52cffba946f2cee05b2564bdd8af9075b", "6071a009eeb103030e8ae862e09d8b7345b1a20b48e37d712e25a2badbfa3de6"),
        listOf("b1c5742ddf25370559ff25b3b455e1a7dd2f58556c3ba2f2c5bb55626144b1bf", "e3a1418686584a73517014d82003dba1e890ff21890d475c31bfc42115a59be8"),
        listOf("0fea3c1e43151ad2c713f07ae4a038ba4bf14868e3b00d3d5ae4fa51777673f3", "b48e9bde06e5d5f74b82f4a088ba594f7e213bd4f140a3cb6d9c733461e7ee26"),
        listOf("f587f42c898179aa51d5c0f66a0dad2ba31843215f01c1539358be9332ed8dac", "ab52a89a216d8e565607f018feec98a431b71bf63142437eb367e1b19549fd4a"),
        listOf("3aa298b451032d56a7c1b0d36221d685ae60f1be18fc26b631d9b068b517be33", "0f6fc3df8e30486d472b6eda1553b29c04dc6a1af244b90863b99847a2828976"),
        listOf("db738b559fd8d9ac97db474e5b33c4a7b7c899d18c5c0b31428d1b2588b24328", "510b944db356dbaee98548a18a9ec8b58f5573323705d1925e89422277b430c6"),
        listOf("5326e23e8fd853b1889937b8b1f0a4d72b369006259a7d46d7fca54ccc38f841", "24eb2e90879e942bb2f4277b141c74fd713bb8b80d80a3343e089981355fc8ab"),
        listOf("19d9dd27f2e49abca6438b066d150f002ba6fba67d15a5a366d0de165c5a72e9", "fc3609982c4547210912f52f3aef8a4617ace07b0145632d1877c8d3bd7b3004"),
        listOf("6c4674740325efce4886abcd111738491badeb1a9a620da1f2f014b5eb8e4a04", "1b31a1516ff38ce0a3edb7375bb798634b97e50b974110ea47b0748eb69da70d"),
    )
}
