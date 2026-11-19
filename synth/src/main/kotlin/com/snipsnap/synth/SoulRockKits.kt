package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad
import java.util.zip.GZIPInputStream

/** Owner-approved acoustic synthesis printed as factory audio. This preserves the audition PCM,
 * including its take leveling and true-peak headroom. It is not a new editable synthesis engine.
 * Each build owns its buffers; decoding cannot mutate a later build or the approved resources.
 */
object SoulRockKits {
    const val VERSION = 3
    const val TAKES = 3
    const val PADS = 16
    enum class Choice(val id: String, val title: String, val bpm: Int, internal val bank: String) {
        SOUL("soul-rock-pocket", "SOUL POCKET", 108, "soul-pocket"),
        ROADHOUSE("soul-rock-roadhouse", "ROADHOUSE ROCK", 134, "roadhouse-rock"),
        OPEN_ROOM("soul-rock-open-room", "OPEN ROOM ROCK", 89, "open-room-rock")
    }
    private val manifest by lazy { Json.parse(resource("manifest.json").decodeToString()).obj() }
    internal fun resource(name: String): ByteArray {
        require(name in setOf("manifest.json", "NOTICE.txt") ||
            Regex("(soul-pocket|roadhouse-rock|open-room-rock)/(?:[1-9]|1[0-6])-[0-2]-[0-2]\\.wav\\.gz").matches(name))
        // Explicit immutable versions prevent missing revised assets from silently
        // falling back to a different sound. Unchanged pads keep their original PCM.
        val assetVersion = when {
            name in setOf("manifest.json", "NOTICE.txt") -> VERSION
            name.startsWith("roadhouse-rock/12-") -> 3
            name.startsWith("roadhouse-rock/2-") -> 2
            else -> 1
        }
        return requireNotNull(javaClass.getResourceAsStream("/soul-rock/v$assetVersion/$name")) {
            "Missing Soul Rock asset: $name"
        }.use { stream -> if (name.endsWith(".gz")) GZIPInputStream(stream).use { it.readBytes() } else stream.readBytes() }
    }
    fun build(choice: Choice): List<ArrangedPad> {
        require(manifest.getValue("version").int() == VERSION)
        val kit = manifest.getValue("kits").arr().single { it.obj().getValue("name").str() == choice.bank }.obj()
        val recipes = kit.getValue("pads").arr()
        return (1..PADS).map { slot ->
            val recipe = recipes[slot - 1].obj()
            val voice = recipe.getValue("voice").str()
            val grid = List(3) { zone -> List(TAKES) { take ->
                WavReader.read(resource("${choice.bank}/$slot-$zone-$take.wav.gz"))
            } }
            val cls = when (slot) {
                1 -> DrumClass.KICK
                2, 3 -> DrumClass.SNARE
                5, 7 -> DrumClass.HAT_CLOSED
                6 -> DrumClass.HAT_OPEN
                8, 9 -> DrumClass.TOM
                14 -> when (choice) { Choice.SOUL -> DrumClass.CLAP; Choice.OPEN_ROOM -> DrumClass.TOM; else -> DrumClass.PERC }
                else -> DrumClass.PERC
            }
            val label = if (choice == Choice.ROADHOUSE && slot == 12) "Crash" else if (slot == 14) when (choice) {
                Choice.SOUL -> "Claps"; Choice.ROADHOUSE -> "Cowbell"; Choice.OPEN_ROOM -> "High Tom"
            } else names[slot - 1]
            ArrangedPad(grid.last().first(), cls, displayName = label, velocityTakes = grid,
                muteGroup = if (slot in 5..7) 1 else 0,
                source = buildMap {
                    put("factory", "soul-rock"); put("bankVersion", VERSION.toString())
                    put("kit", choice.bank); put("slot", slot.toString()); put("voice", voice)
                    put("sourceKind", "frozen acoustic synthesis"); put("rendered", "true")
                    put("zones", "3"); put("takes", TAKES.toString()); put("articulation", "finite")
                    put("audition", manifest.getValue("audition").str())
                    put("approval", recipe["approval"]?.str() ?: "Python audition approved 2026-11-16; hardware open")
                    recipe["sourceModel"]?.let { put("sourceModel", it.str()) }
                    for ((key, value) in recipe.getValue("macros").obj()) put("synthesis.$key", value.num().toString())
                })
        }
    }
    private val names = listOf("Kick", "Snare", "Rimshot", "Cross Stick", "Closed Hat", "Open Hat", "Pedal Hat",
        "Rack Tom", "Floor Tom", "Ride", "Ride Bell", "Sizzle", "Tambourine", "Color", "Press Roll", "Cymbal Swell")
}
