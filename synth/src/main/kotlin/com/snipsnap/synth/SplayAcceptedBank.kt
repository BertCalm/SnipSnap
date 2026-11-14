package com.snipsnap.synth

/**
 * The five kept Wave 3 population performances, also filed by SplayFactoryPresets.
 * Counts are members of one TONGUE voice, not additional voices.
 * The room used raw output times a fixed matched gain; patch.render() retains native leveling.
 * Complete source and recording bindings live in test resources splay-accepted/manifest.json.
 */
object SplayAcceptedBank {
    const val MATCHED_GAIN = 2.56811770069021
    const val WAVE3_MANIFEST_SHA256 = "0b4efb577a80c616e1752ef802b39fb3cafa9678c4606af8c7e670d06a060312"
    const val SOURCE_ARCHIVE_SHA256 = "d09c5018ca50debd98f37f5842e545a72be7db69512034911e5cced5e06dca11"

    data class Entry(val id: String, val mechanisms: Int, val patch: SplayPatch,
                     val originalRecipeSha256: String, val rawWavSha256: String,
                     val matchedWavSha256: String)

    private fun entry(id: String, mechanisms: Int, population: Float, recipeHash: String,
                      rawHash: String, matchedHash: String) = Entry(id, mechanisms,
        SplayPatch("$mechanisms ${if (mechanisms == 1) "mechanism" else "mechanisms"}", SplayVoice.TONGUE,
            linkedMapOf("TRACE" to .62f, "SEED GAP" to .72f, "ENERGY" to .78f,
                "CONTACT" to .70f, "POPULATION" to population, "HOLD" to 0f),
            midi = 60, velocity = .8f, seed = 41001, model = 2),
        recipeHash, rawHash, matchedHash)

    /** Native linear POPULATION values are retained exactly; the geometric count dial is separate work. */
    val entries: List<Entry> = listOf(
        entry("pc-1", 1, 0f,
            "a9b070c5fe955f5e1334911617915293cac775d5784cb47b08cbdedf74ea13ef",
            "618536c8aeb22f8689750996805abb2f4a1e79d3574860b1a4b97525206be637",
            "6a7c4a937f3389e67df32661e33e2ae629916b01ea29d6fcead398c53e9f3e83"),
        entry("pc-2", 2, 1f / 99f,
            "01fcf7fd09bf6a88fd5215127bf4884e03aa31767113d1aa094c9949c6807abf",
            "631e00891a4524633dd43b608398dc869be7e43cf25d8638c7fce37fd78ebffd",
            "f1591af0d9959070ba19a15902c13eb3c745fc2593029a9e6be66aee70b44bf0"),
        entry("pc-4", 4, 3f / 99f,
            "8de4a7a9c286a56f1be369c39cbc330c88cc317184fdc3b48b4384a94e5b1a9a",
            "0f1053d3826d65e927cf48d9f730acbf05e2fce449c0358b8a13b1bcc818b031",
            "b66311851dee063dda962a9efda5ecd2468256170bac1b7e2c6a76b1f7b357ba"),
        entry("pc-8", 8, 7f / 99f,
            "1f05d4a83cafe4b00bcf654f7f2ea83173c97b7b980ff4be4473a6a5e42ed5f4",
            "0f78b9360c89b796e90507961df6efc2c169e2556830288aa334ea1d0645f2e5",
            "cf12f9dd992b31e2d9a1b3461633f31bebb5a95f08ab135c90fb4d142e3cb0fd"),
        entry("pc-16", 16, 15f / 99f,
            "d8f545d5f05ce948483f02901aea986c87c32afd6f9b150bc4f6011dcabc67dc",
            "08e61fc031fc7440d6c3eddf71e190c3f983a4e4a8d72b1dfde748b5b46030e9",
            "5a059c1358f6d9dc86a1b367a3889b490456706c9635eab2423e15320856c897"),
    )
}
