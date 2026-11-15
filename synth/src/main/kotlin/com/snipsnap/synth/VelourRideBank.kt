package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad

/** Selected Round 7 B, frozen after the existing Kotlin fitted cymbal and Python board.
 * PCM16 matches the lossless listening preview; exporting at 24 bits adds no lost detail.
 * Other pads still use the Round 5 bank and approved Round 6 variations.
 */
internal object VelourRideBank {
    private val manifest by lazy { Json.parse(resource("manifest.json").decodeToString()).obj() }
    internal fun resource(name: String): ByteArray {
        require(name in setOf("manifest.json", "REFERENCE-LICENSE.txt") ||
            Regex("(close|velour|bloom)/[0-2]-[0-3]\\.wav").matches(name))
        return requireNotNull(javaClass.getResourceAsStream("/velour/ride-b-v1/$name")).use { it.readBytes() }
    }
    private fun take(t: VelourApprovedKits.Treatment, zone: Int, take: Int) =
        WavReader.read(resource("${t.id}/$zone-$take.wav"))

    fun grid(t: VelourApprovedKits.Treatment) = List(3) { z -> List(4) { n -> take(t,z,n) } }

    fun replace(pad: ArrangedPad, t: VelourApprovedKits.Treatment): ArrangedPad {
        val layers = List(3) { take(t,it,0) }
        val file = "${t.id}/2-0.wav"
        val asset = manifest.getValue("assets").arr().map { it.obj() }.single { it.getValue("file").str() == file }
        return pad.copy(snip = layers.last(), softVariants = layers.dropLast(1), source = pad.source + mapOf(
            "approval" to "round7 B selected; velocity/take expansion",
            "bankVersion" to "round5+ride-b-v1", "asset" to "ride-b-v1/$file",
            "assetSha256" to asset.getValue("sha256").str(),
            "sourceKind" to "CC0 reference-fitted modal cymbal",
            "reference" to manifest.getValue("reference").str(), "referenceLicense" to "CC0-1.0",
            "audition" to manifest.getValue("audition").str(),
            "roundRobin" to "four fitted strikes available in opt-in grid"))
    }
}
