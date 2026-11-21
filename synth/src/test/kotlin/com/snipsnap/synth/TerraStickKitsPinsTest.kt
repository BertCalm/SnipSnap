package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The owner approved the sound of TERRA's four Japanese kits on 2026-11-20 ("Sounds good, I like", after the round-7
 * listening page: MATSURI, THUNDER, BON, DUSK, the fan and the darker soft edge stroke), and round 8's changes on
 * 2026-11-21 ("Everything sounds great": six pads that ring, three GRAIN pads in DUSK, every roll and riser on its
 * kit's tempo). These are the grids those pages played: every pad's twelve zone samples, hashed as the 24-bit PCM a kit saves ([TerraStickKitsPins.gridHash]). A
 * change to any voice, roll, chain or kit that moves a sample fails here; re-pin only with the owner's new listen
 * (docs/TERRA_STICKS.md).
 */
class TerraStickKitsPinsTest {
    private val pinned: Map<String, String> = linkedMapOf(
            "MATSURI|Don" to "eb2c83edcdd896f9b3e61850a330f55f1f425375e84a78153c1cf934307e6ada",
            "MATSURI|Don Low" to "048086d2843176f9a20c639fffea4b0e170dd28985f5be324a6ef0c1a75d9fec",
            "MATSURI|Don Tight" to "78c54843019d2b56dbccab2ab38a7e949a80e77b2974d3e7a6718e2da031db35",
            "MATSURI|Ten" to "ac13b5fb629edb6752576496b0303dc73103755c58aa95bef82fefcae48c6dfa",
            "MATSURI|Tsu" to "1c19b79685e9311b79af082f5a66576aea0e63b6be90e4badf1d01b69ee5170d",
            "MATSURI|Ten High" to "ce87613d04b9a7f2150389565cc9dfa6861e50b4e1e0ec1fcfdcc0d40322f22a",
            "MATSURI|Ka" to "e4c639e219d2c829316644b05ff2be0befe16eaa57359e624438f7041f99d15d",
            "MATSURI|Ka High" to "58b22ad6de873e96f5f3558a6cf374f2d38ed569536c8086785667c6420a9783",
            "MATSURI|Chan" to "6eb3e370ed461bea4dcdcca2d222382fcf909cee443aa53e299b2178dfdd1433",
            "MATSURI|Chiki" to "9c8471046b12a649af27738e99cc4f78c421cbb962f9b276e1e3f60bc215c6d6",
            "MATSURI|Chappa" to "435c80261b6f3b16f6952c1f8d47de057f7cf71c5682d6d0364cefcec75fb6a9",
            "MATSURI|Chappa Choke" to "4036294c238c938d788a680946492e1fa855c16c39ece7c8602f72c50ceae4be",
            "MATSURI|Don Big" to "6de1ef4236315c9c0dcd091d97375f5c5420697546576702c9045a298532759b",
            "MATSURI|Sasara" to "632ad4404287b8f4c1d518f28521b628bd4fdeaf59bfa736d4b3bd3c3aacf690",
            "MATSURI|Ten Roll" to "23c0abf1597c05e8deef9af1c585849cbc0bac3d6fba2fbc463da0503595349c",
            "MATSURI|Don Riser" to "aaf9dc225cc1e5bd306d9dc2ae1fcecb04c8cbef3cbc300a118e9e6b172d9eb0",
            "THUNDER|Odaiko Long" to "3a6e21c8880c092fe16205086a063eea0859e71155bbcc78576179a37220cb0a",
            "THUNDER|Odaiko Low" to "7b3b125792e1989c13b27839c04cbb639136b7c779b6e38055c36872413f9940",
            "THUNDER|Odaiko Pair" to "fdb4b312b2d48b5d16f6b5486f27ed5f549a440d691d033c30ffe51203061c86",
            "THUNDER|Odaiko Deep" to "2991309c3460648ef92e70bafe801469a80566d69ce7a4bd73829a305c78ca0f",
            "THUNDER|Odaiko Soft" to "72258e2d882deab239b11c2198025420b3dbe52fd51f48d76258025e17f0ac1b",
            "THUNDER|Tom Low" to "338ff923bfc0626aee95b8e6c656df51954348cecf3fb9df4579573ffd1ac562",
            "THUNDER|Tom Mid" to "81a558c5b30c1b05fce2dd97563150d98a2844cf0082b82b43481ca3caa7329b",
            "THUNDER|Tom High" to "f1061bd2bd5f025c3952ff85f0bd6d8b6bcca6f4fba28125fbf220cb5b90083a",
            "THUNDER|Rim Crack" to "5f1ed9a153f9a21e0b2cde14b94fb6fab49931263a081fe7ea0250e8b4d59a72",
            "THUNDER|Rim Low" to "3ba084f0fd8db74a8433543a0d77e3b5a2382b7b99f881f181bfa1d407ece9ec",
            "THUNDER|Cymbal Wash" to "909e279bb79558d50afd13d0893af68ff7e4b664633854e249f13953a40ddf95",
            "THUNDER|Gong Low" to "5dd103866b2f7604a309d2b35c54e5b5d68d8f2f32f363e084c19217d9ae5121",
            "THUNDER|Snap" to "86788a075bc49d3288fa57235a4cbbd0f0336dd5ce4a12e6689adef53bd8e0c0",
            "THUNDER|Odaiko Roll" to "45324c758ab81740e07dfb455a777e7439a32749e3d67fec35275074ff46a289",
            "THUNDER|Odaiko Riser" to "2de847fc1f2794859c7cf3f485a507a592bf59e3432e2a5bcc5f8c6ea033e11c",
            "THUNDER|Tom Roll" to "3aaa6aa62c72372d73c8d95e2d1da080908bf2d9a30813643de0a7271b1c07b5",
            "BON|Water" to "ecf453e2047b8b63c0585afd3db78acdf110b635b3dfcf5b83eb082578bd7de3",
            "BON|Water Low" to "ac1eb9855db60b23c98e86fbb5691ab3f7c47334484c4fa22b30623cc97416fc",
            "BON|Water Tap" to "d4460e0e44f5039c40ede5159d5967e90407b1ed85fad0d142e557cbdc41cae1",
            "BON|Bell Shake" to "a383a25b0ac80c2df9545c58e18ef2aaf4b2175288a8c87142f50984931d95b8",
            "BON|Bell Single" to "394178c6836c1c59f7724e4f370a1f22ca5358060384bdcdaa9c44d03dbe3d9d",
            "BON|Bell Muted" to "ebebebc19f048cde15036e5773e0d4cd5c4d46f97c1d822847c7c1b1410c2038",
            "BON|Ten Bright" to "2f533f731202452908b8a91d6a44b9038133d2c6778da7ed81eda919ae1421ac",
            "BON|Ten Ghost" to "7a171f8be838ba3f74ae953f89eb8247aec657e5eb06d4583cce228aa15785ab",
            "BON|Don Soft" to "f5ba83959aff944e7bdea94286fb3115c7f832739b57e2db68d356cd0804f386",
            "BON|Gong Mid" to "cc7e7a42c917296b94b8d56920b04ceee3915cda64fe7c4d81d1443591e14737",
            "BON|Gong Edge Hi" to "59ffb27e8b2326cf6633cc7a50b85a934c56b2e9dbfae8a1c33acebea5c76424",
            "BON|Cymbal Small" to "cdc098c353f8218e7967de42b798c977e19a356334e85a9f05d78e0fe50b2231",
            "BON|Slat Snap" to "f3b45b80ab2e55f47d5932a2eaae99e0527beda171bf7645980bba86c0c7b76a",
            "BON|Slat Slow" to "bad657f0dbc8b90ff04b9eb9b5388f3b2225b5659b3bd1e652b63f5ee9f3a30e",
            "BON|Slat Roll" to "0c817b187c9621fcce8714db7b1b0583136df89c76c7192428a99ebaa8b99ba7",
            "BON|Bell Riser" to "ec7afe5690488b2cba9a930ba5d62508b7a551f5b93e996b8de6d5dfc3e1c636",
            "DUSK|Odaiko Rewind" to "f01b98f407d5cead844d8a25b63a42906a77482e6d5652deddefb0ea876db3ae",
            "DUSK|Odaiko Hall" to "794261a12ab45c90eec0db29ac02c57ed52ad9b54a7f325754d75f7321e0c294",
            "DUSK|Don Echo" to "978a64ad9ebc17825b73cc72c7e6b320e2e861c007e3863071320ebd899361f9",
            "DUSK|Don Ghost" to "0a72976ef66817e0f8a6aca9800359a5f560f9129aa193b85b95f8175c1519c9",
            "DUSK|Ten Echo" to "a84ca4edfdc2ef30514b0b16c566d9f36806fcc691a84f4d73d6d52989b88004",
            "DUSK|Ten Grains" to "27a056be2ea1c7fa3e233f514bff566badc5f9761b1be12dca25691c7f66eac6",
            "DUSK|Ka Echo" to "d83c8151494035591f1c84eecb300344d54ffb12dcbd6c4f06d9317487921d67",
            "DUSK|Ka Grains" to "ebb090de317b6af90faa20e6630a0798209500cf1ae69035007bdb10f3a135f7",
            "DUSK|Chan Echo" to "3baebdcfa6609267ba8b94e01cc41cb9d58543ec1ffefba4f7e4bb3593f2b79f",
            "DUSK|Chiki Grains" to "9aa9fe165ee0f73e10d37638d2310d23d969c3241b7f4cac713bfc83fbf193ce",
            "DUSK|Cymbal Spring" to "5f4aee3665af1fcaafa82d7e9af0919053cd7aeb4ddac5cab8005435b0d9a4b3",
            "DUSK|Fan Ghost" to "caf2d3f97584fbf2029c5d9d2917b3809b53015da9a16f8d262816168bb1b075",
            "DUSK|Bell Echo" to "3893d8b4d4b11e04d23bed2a04dc34d10811bc6eba9897533d9783ddfcfe9da8",
            "DUSK|Water Dub" to "19f7ff1095b2d064d7b1c9232b210e6fbb31290a9f43ed965f919019495ed0fe",
            "DUSK|Ten Roll Echo" to "80ae31267e6707ed5552bed26119b2331beedd69a3c5c6dd006db7e72f91d8cd",
            "DUSK|Odaiko Swell" to "2d1d37a53ca2f1ee10e518381f53c0aa3152c527ebff40c1a75b36ebb4cc3ccb",
    )

    @Test fun `every pad of the four Japanese kits is the grid the owner approved`() {
        val actual = linkedMapOf<String, String>()
        for (k in TerraStickKits.Kit.entries) for (pad in TerraStickKits.build(k)) actual["${k.name}|${pad.displayName}"] = TerraStickKitsPins.gridHash(pad.velocityTakes)
        assertEquals(pinned.keys.toList(), actual.keys.toList(), "the kits' pads")
        val moved = pinned.filter { (key, hash) -> actual[key] != hash }.keys
        assertEquals(emptySet(), moved, "pads whose samples changed since the owner approved them")
    }
}
