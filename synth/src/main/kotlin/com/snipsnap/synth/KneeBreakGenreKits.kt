package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad
import java.util.zip.GZIPInputStream

/** Printed oscillator/noise synthesis: preserves the owner-heard Python bank exactly.
 * These are factory kit assets, not a Kotlin port of the editable source model.
 * Every call owns its decoded buffers. The board is already printed; never apply it twice.
 * Three approved genre arrangements keep per-pad tuning, damping and mic selection.
 */
object KneeBreakGenreKits {
    const val VERSION = 1
    const val TAKES = 3
    enum class Choice(val id: String, val title: String, val genre: String, val tempoBpm: Float) {
        BOOM_BAP("knee-break-boom-bap", "KNEE BREAK BOOM BAP", "boom-bap", 90f),
        TRIP_HOP("knee-break-trip-hop", "KNEE BREAK TRIP HOP", "trip-hop", 76f),
        FUNK("knee-break-funk", "KNEE BREAK FUNK BREAKS", "funk", 108f)
    }
    private val manifest by lazy { Json.parse(resource("manifest.json").decodeToString()).obj() }
    internal fun resource(name: String): ByteArray {
        require(name in setOf("manifest.json", "REFERENCE-LICENSE.txt", "NOTICE.txt") ||
            Regex("(boom-bap|trip-hop|funk)/(?:[1-9]|1[0-6])-[0-2]-[0-2]\\.wav\\.gz").matches(name))
        val stream = requireNotNull(javaClass.getResourceAsStream("/knee-break-genres/v$VERSION/$name")) { "Missing Knee Break asset: $name" }
        return stream.use { if (name.endsWith(".gz")) GZIPInputStream(it).use { gz -> gz.readBytes() } else it.readBytes() }
    }
    fun build(choice: Choice = Choice.BOOM_BAP): List<ArrangedPad> {
        require(manifest.getValue("version").int() == VERSION)
        val names = manifest.getValue("roles").arr().map { it.str() }
        return (1..16).map { slot ->
            val grid = List(3) { zone -> List(TAKES) { take ->
                WavReader.read(resource("${choice.genre}/$slot-$zone-$take.wav.gz"))
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
                source = mapOf("factory" to "knee-break-genres", "bankVersion" to VERSION.toString(),
                    "role" to names[slot-1], "genre" to choice.genre,
                    "sourceKind" to "frozen oscillator/noise synthesis", "rendered" to "true",
                    "roundRobin" to "three independently synthesized strikes per velocity",
                    "approval" to "Genre audition accepted: Sounds good, proceed",
                    "audition" to manifest.getValue("audition").str(), "siteCommit" to manifest.getValue("siteCommit").str(),
                    "referenceLicense" to "CC0-1.0"))
        }
    }
}
