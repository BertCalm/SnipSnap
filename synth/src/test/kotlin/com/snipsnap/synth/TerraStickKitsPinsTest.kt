package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The owner approved the sound of TERRA's four Japanese kits on 2026-11-20 ("Sounds good, I like", after the round-7
 * listening page: MATSURI, THUNDER, BON, DUSK, the fan and the darker soft edge stroke). These are the grids that page
 * played: every pad's twelve zone samples, hashed as the 24-bit PCM a kit saves ([TerraStickKitsPins.gridHash]). A
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
            "MATSURI|Chan" to "0c35187d2e472a78818b20361824729dd1d1751b18208d35c357e3d20883d603",
            "MATSURI|Chiki" to "9c8471046b12a649af27738e99cc4f78c421cbb962f9b276e1e3f60bc215c6d6",
            "MATSURI|Chappa" to "435c80261b6f3b16f6952c1f8d47de057f7cf71c5682d6d0364cefcec75fb6a9",
            "MATSURI|Chappa Choke" to "4036294c238c938d788a680946492e1fa855c16c39ece7c8602f72c50ceae4be",
            "MATSURI|Don Big" to "6de1ef4236315c9c0dcd091d97375f5c5420697546576702c9045a298532759b",
            "MATSURI|Sasara" to "632ad4404287b8f4c1d518f28521b628bd4fdeaf59bfa736d4b3bd3c3aacf690",
            "MATSURI|Ten Roll" to "bc49cc95cc1e166ea92b7bb8a49e124cc18b5eccc933e699fc19367635f36441",
            "MATSURI|Don Riser" to "27891a29e1bba9dbb4891271c043f582ccc21f87d5b39aab3ac86e9f189ae5d3",
            "THUNDER|Odaiko Long" to "3bfd23f906bcee3e6c2dfbd81076117807fed2badd56ce452b0bd5b8a40d01d8",
            "THUNDER|Odaiko Low" to "7b3b125792e1989c13b27839c04cbb639136b7c779b6e38055c36872413f9940",
            "THUNDER|Odaiko Pair" to "fdb4b312b2d48b5d16f6b5486f27ed5f549a440d691d033c30ffe51203061c86",
            "THUNDER|Odaiko Deep" to "7547b8512acbedb75ed703937225d2086bab3ccd607c7b59300c6767752386d2",
            "THUNDER|Odaiko Soft" to "72258e2d882deab239b11c2198025420b3dbe52fd51f48d76258025e17f0ac1b",
            "THUNDER|Tom Low" to "338ff923bfc0626aee95b8e6c656df51954348cecf3fb9df4579573ffd1ac562",
            "THUNDER|Tom Mid" to "81a558c5b30c1b05fce2dd97563150d98a2844cf0082b82b43481ca3caa7329b",
            "THUNDER|Tom High" to "f1061bd2bd5f025c3952ff85f0bd6d8b6bcca6f4fba28125fbf220cb5b90083a",
            "THUNDER|Rim Crack" to "5f1ed9a153f9a21e0b2cde14b94fb6fab49931263a081fe7ea0250e8b4d59a72",
            "THUNDER|Rim Low" to "3ba084f0fd8db74a8433543a0d77e3b5a2382b7b99f881f181bfa1d407ece9ec",
            "THUNDER|Cymbal Wash" to "40bcbcc2767a63576d5b8da6789b7c6e8e3cc7dd0b26cb7643ad5eefcf39a4a1",
            "THUNDER|Gong Low" to "3c9b44f148201f05881959a885cab4302a361f358e36b0467870f429ec4c667e",
            "THUNDER|Snap" to "86788a075bc49d3288fa57235a4cbbd0f0336dd5ce4a12e6689adef53bd8e0c0",
            "THUNDER|Odaiko Roll" to "dc2274db11be09a8be95bd5f3253d23554e57793d8b3625d18472a271db24717",
            "THUNDER|Odaiko Riser" to "0c86788dad7b99cce3bfcd2a5dd009ead8b9f316f9ffbda57069a87dd51fcae8",
            "THUNDER|Tom Roll" to "ef54db456c4a7d27bc985088414fb147a8c89101dc3408950a023d5b3ae300a8",
            "BON|Water" to "ecf453e2047b8b63c0585afd3db78acdf110b635b3dfcf5b83eb082578bd7de3",
            "BON|Water Low" to "ac1eb9855db60b23c98e86fbb5691ab3f7c47334484c4fa22b30623cc97416fc",
            "BON|Water Tap" to "d4460e0e44f5039c40ede5159d5967e90407b1ed85fad0d142e557cbdc41cae1",
            "BON|Bell Shake" to "a383a25b0ac80c2df9545c58e18ef2aaf4b2175288a8c87142f50984931d95b8",
            "BON|Bell Single" to "d2d579269c84458a6e2c12f3d961bd26dd5b4900055490b61620fe14b9e0d9c9",
            "BON|Bell Muted" to "ebebebc19f048cde15036e5773e0d4cd5c4d46f97c1d822847c7c1b1410c2038",
            "BON|Ten Bright" to "2f533f731202452908b8a91d6a44b9038133d2c6778da7ed81eda919ae1421ac",
            "BON|Ten Ghost" to "7a171f8be838ba3f74ae953f89eb8247aec657e5eb06d4583cce228aa15785ab",
            "BON|Don Soft" to "f5ba83959aff944e7bdea94286fb3115c7f832739b57e2db68d356cd0804f386",
            "BON|Gong Mid" to "cc7e7a42c917296b94b8d56920b04ceee3915cda64fe7c4d81d1443591e14737",
            "BON|Gong Edge Hi" to "59ffb27e8b2326cf6633cc7a50b85a934c56b2e9dbfae8a1c33acebea5c76424",
            "BON|Cymbal Small" to "cdc098c353f8218e7967de42b798c977e19a356334e85a9f05d78e0fe50b2231",
            "BON|Slat Snap" to "f3b45b80ab2e55f47d5932a2eaae99e0527beda171bf7645980bba86c0c7b76a",
            "BON|Slat Slow" to "bad657f0dbc8b90ff04b9eb9b5388f3b2225b5659b3bd1e652b63f5ee9f3a30e",
            "BON|Slat Roll" to "c568621a8536d08e2eec68ee49c27c7b95560727f3c01c2acf301705f26effea",
            "BON|Bell Riser" to "b62d648ad5d9a1095db53559ff0080f4f373537c13998bd61ada1decd7210a94",
            "DUSK|Odaiko Rewind" to "f01b98f407d5cead844d8a25b63a42906a77482e6d5652deddefb0ea876db3ae",
            "DUSK|Odaiko Hall" to "794261a12ab45c90eec0db29ac02c57ed52ad9b54a7f325754d75f7321e0c294",
            "DUSK|Don Echo" to "978a64ad9ebc17825b73cc72c7e6b320e2e861c007e3863071320ebd899361f9",
            "DUSK|Don Ghost" to "0a72976ef66817e0f8a6aca9800359a5f560f9129aa193b85b95f8175c1519c9",
            "DUSK|Ten Echo" to "a84ca4edfdc2ef30514b0b16c566d9f36806fcc691a84f4d73d6d52989b88004",
            "DUSK|Ten Smear" to "947e020bcd22496078adb232068a6a6fc1f4b079e1aff046f299cb717c5d8f41",
            "DUSK|Ka Echo" to "d83c8151494035591f1c84eecb300344d54ffb12dcbd6c4f06d9317487921d67",
            "DUSK|Ka Tape" to "9500fac76d45ac20e5fe51e4aa7913de1718032018f7d090cf9a4432877b3771",
            "DUSK|Chan Echo" to "3baebdcfa6609267ba8b94e01cc41cb9d58543ec1ffefba4f7e4bb3593f2b79f",
            "DUSK|Chiki Echo" to "6dadb7671b85012e660bfc2edee45939b9e8908c20ea38607c2fab978322e814",
            "DUSK|Cymbal Spring" to "5f4aee3665af1fcaafa82d7e9af0919053cd7aeb4ddac5cab8005435b0d9a4b3",
            "DUSK|Fan Ghost" to "caf2d3f97584fbf2029c5d9d2917b3809b53015da9a16f8d262816168bb1b075",
            "DUSK|Bell Echo" to "3893d8b4d4b11e04d23bed2a04dc34d10811bc6eba9897533d9783ddfcfe9da8",
            "DUSK|Water Dub" to "19f7ff1095b2d064d7b1c9232b210e6fbb31290a9f43ed965f919019495ed0fe",
            "DUSK|Ten Roll Echo" to "be117745afbd67c01afc4f72d984b6f6a565275d6c554c36229a7b27a796b31f",
            "DUSK|Odaiko Swell" to "d34a452583abbdf51a26d7bda00128005dda523bbb6ffa06562d8d85e0305f77",
    )

    @Test fun `every pad of the four Japanese kits is the grid the owner approved`() {
        val actual = linkedMapOf<String, String>()
        for (k in TerraStickKits.Kit.entries) for (pad in TerraStickKits.build(k)) actual["${k.name}|${pad.displayName}"] = TerraStickKitsPins.gridHash(pad.velocityTakes)
        assertEquals(pinned.keys.toList(), actual.keys.toList(), "the kits' pads")
        val moved = pinned.filter { (key, hash) -> actual[key] != hash }.keys
        assertEquals(emptySet(), moved, "pads whose samples changed since the owner approved them")
    }
}
