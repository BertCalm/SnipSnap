package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad

/**
 * Frozen, owner-approved Round 5 delivery, including the recording-derived tambourine
 * and the accepted silence trim. Loading the printed samples keeps the audition's
 * exact gain, timbral velocity layers and effect tails; it does not reapply a board.
 *
 * This bank deliberately remains separate from [VelourKits]'s original model-1
 * renderer and saved boards. The three treatments are not three genre kits, and
 * these single-strike velocity layers do not claim round-robin completion.
 *
 * A15 derives from VSCO Community Edition recordings (CC0): Sam Gossner and Simon
 * Dalzell, sample cutting by Elan Hickler / Soundemote. Reference license, hashes
 * and the audition source commit are shipped beside the bank's manifest.
 */
object VelourApprovedKits {
    const val VERSION = 1
    const val TEMPO = 84f
    enum class Treatment(val id: String) { CLOSE("close"), VELOUR("velour"), BLOOM("bloom") }
    private val manifest by lazy { Json.parse(resource("manifest.json").decodeToString()).obj() }

    internal fun resource(name: String): ByteArray {
        require(name in setOf("manifest.json", "REFERENCE-LICENSE.txt") ||
            Regex("(close|velour|bloom)/[0-9]{2}-(soft|mid|hard)\\.wav").matches(name))
        return requireNotNull(javaClass.getResourceAsStream("/velour/approved-v$VERSION/$name")) {
            "Missing approved Velour bank asset: $name"
        }.use { it.readBytes() }
    }

    /** Every invocation owns its PCM buffers, so editing a kit cannot mutate the factory bank. */
    fun build(treatment: Treatment = Treatment.VELOUR): List<ArrangedPad> {
        require(manifest.getValue("version").int() == VERSION)
        val pads = manifest.getValue("kits").obj().getValue(treatment.id).arr()
        require(pads.map { it.obj().getValue("slot").int() } == (1..16).toList())
        return pads.map { value ->
            val pad = value.obj()
            val layerInfo = pad.getValue("layers").arr().map { it.obj() }
            require(layerInfo.size == 3)
            val layers = layerInfo.map { WavReader.read(resource(it.getValue("file").str())) }
            val slot = pad.getValue("slot").int()
            ArrangedPad(layers.last(), DrumClass.valueOf(pad.getValue("class").str()),
                level = pad.getValue("level").num().toFloat(),
                softVariants = layers.dropLast(1), displayName = pad.getValue("name").str(),
                layerNames = listOf("Soft", "Medium", "Hard"), muteGroup = pad.getValue("muteGroup").int(),
                source = mapOf("factory" to "velour", "bankVersion" to VERSION.toString(),
                    "role" to pad.getValue("name").str(), "rendered" to "true",
                    "treatment" to if (slot in 5..6) "dry" else treatment.id,
                    "approval" to "round5-trimmed-baseline", "roundRobin" to "pending",
                    "audition" to manifest.getValue("audition").str(),
                    "asset" to layerInfo.last().getValue("file").str(),
                    "assetSha256" to layerInfo.last().getValue("sha256").str(),
                    "sourceKind" to if (slot == 15) "CC0 reference-derived resynthesis" else "synthetic",
                    "reference" to if (slot == 15) manifest.getValue("reference").str() else "",
                    "referenceLicense" to if (slot == 15) "CC0-1.0" else ""))
        }
    }
}
