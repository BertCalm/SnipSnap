package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad
import java.util.zip.GZIPInputStream

/** Printed oscillator/noise synthesis: preserves the owner-heard Python bank exactly.
 * These are factory kit assets, not a Kotlin port of the editable source model.
 * Every call owns its decoded buffers. The board is already printed; never apply it twice.
 * Body/Tighter and the three treatments are variants, not six distinct genre kits.
 */
object KneeBreakKits {
    const val VERSION = 1
    const val TEMPO = 94f
    const val TAKES = 3
    enum class Choice(val id: String, val title: String, val body: String, val treatment: String) {
        BODY("knee-break-body", "KNEE BREAK BODY", "body", "close"),
        KNEE("knee-break-knee", "KNEE BREAK KNEE", "body", "knee"),
        CASSETTE("knee-break-cassette", "KNEE BREAK CASSETTE", "body", "cassette"),
        TIGHT("knee-break-tight", "KNEE BREAK TIGHT", "tight", "close"),
        TIGHT_KNEE("knee-break-tight-knee", "KNEE BREAK TIGHT KNEE", "tight", "knee"),
        TIGHT_CASSETTE("knee-break-tight-cassette", "KNEE BREAK TIGHT CASSETTE", "tight", "cassette")
    }
    private val manifest by lazy { Json.parse(resource("manifest.json").decodeToString()).obj() }
    internal fun resource(name: String): ByteArray {
        require(name in setOf("manifest.json", "REFERENCE-LICENSE.txt", "NOTICE.txt", "model.json") ||
            Regex("(body|tight)/(close|knee|cassette)/(?:[1-9]|1[0-6])-[0-2]-[0-2]\\.wav\\.gz").matches(name))
        val stream = requireNotNull(javaClass.getResourceAsStream("/knee-break/v$VERSION/$name")) { "Missing Knee Break asset: $name" }
        return stream.use { if (name.endsWith(".gz")) GZIPInputStream(it).use { gz -> gz.readBytes() } else it.readBytes() }
    }
    fun build(choice: Choice = Choice.KNEE): List<ArrangedPad> {
        require(manifest.getValue("version").int() == VERSION)
        val names = manifest.getValue("roles").arr().map { it.str() }
        return (1..16).map { slot ->
            val grid = List(3) { zone -> List(TAKES) { take ->
                WavReader.read(resource("${choice.body}/${choice.treatment}/$slot-$zone-$take.wav.gz"))
            } }
            val cls = when (slot) {
                1, 5 -> DrumClass.KICK
                2, 6, 7 -> DrumClass.SNARE
                3, 11 -> DrumClass.HAT_CLOSED
                4 -> DrumClass.HAT_OPEN
                9, 10 -> DrumClass.TOM
                else -> DrumClass.PERC
            }
            ArrangedPad(grid.last().first(), cls, level = .65f, displayName = names[slot-1],
                velocityTakes = grid, muteGroup = if (slot in setOf(3,4,11)) 1 else 0,
                source = mapOf("factory" to "knee-break", "bankVersion" to VERSION.toString(),
                    "role" to names[slot-1], "body" to choice.body, "treatment" to choice.treatment,
                    "sourceKind" to "frozen oscillator/noise synthesis", "rendered" to "true",
                    "roundRobin" to "three independently synthesized strikes per velocity",
                    "approval" to "R3 core accepted; R5 knee direction accepted; R4 expansion carried forward",
                    "audition" to manifest.getValue("audition").str(), "siteCommit" to manifest.getValue("siteCommit").str(),
                    "referenceLicense" to "CC0-1.0"))
        }
    }
}
