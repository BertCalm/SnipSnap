package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.ArrangedPad
import java.util.zip.GZIPInputStream

/** Frozen synthesis and CC0 magnitude resynthesis, not an editable engine.
 * The approved R10 base is preserved except six v2 crescendo layers awaiting listening.
 * Offline phase solvers are precomputed; loading never reruns them or changes the approved PCM.
 * Builds own their buffers so editing a pad cannot mutate the factory bank.
 */
object SunroomKits {
    const val VERSION = 2
    const val TAKES = 3
    const val PADS = 16
    enum class Choice(val id: String, val title: String, val bank: String, val tempoBpm: Float = 116f) {
        EARLY("sunroom-early", "SUNROOM EARLY POP", "early"),
        ORCHESTRAL("sunroom-orchestral", "SUNROOM ORCHESTRAL POP", "orchestral"),
        PSYCH("sunroom-psych", "SUNROOM PSYCH POP", "psych")
    }
    private val manifest by lazy { Json.parse(resource("manifest.json").decodeToString()).obj() }
    internal fun resource(name: String): ByteArray {
        require(name in setOf("manifest.json", "NOTICE.txt", "percussion-provenance.json", "acoustic-provenance.json", "brush-provenance.json", "crescendo-provenance.json") ||
            Regex("(early|orchestral|psych)/(?:[1-9]|1[0-6])-[0-2]-[0-2]\\.wav\\.gz").matches(name))
        // v2 overrides only the two softer crescendo zones. Keep every other approved
        // resource in v1, including all three hard takes, rather than duplicate the bank.
        val version = if (name in setOf("manifest.json", "crescendo-provenance.json") ||
            Regex("psych/12-[0-1]-[0-2]\\.wav\\.gz").matches(name)) VERSION else 1
        return requireNotNull(javaClass.getResourceAsStream("/sunroom/v$version/$name")) { "Missing Sunroom asset: $name" }
            .use { if (name.endsWith(".gz")) GZIPInputStream(it).use { gz -> gz.readBytes() } else it.readBytes() }
    }
    fun build(choice: Choice): List<ArrangedPad> {
        require(manifest.getValue("version").int() == VERSION)
        val roles = manifest.getValue("roles").obj().getValue(choice.bank).arr().map { it.str() }
        val labels = manifest.getValue("labels").obj().getValue(choice.bank).arr().map { it.str() }
        return (1..PADS).map { slot ->
            val voice = roles[slot - 1]
            val grid = List(3) { z -> List(TAKES) { t -> WavReader.read(resource("${choice.bank}/$slot-$z-$t.wav.gz")) } }
            val cls = when (voice) {
                "kick", "felt-kick", "resonant-kick" -> DrumClass.KICK
                "snare", "brush-snare", "concert-snare", "rimshot", "rim-backbeat" -> DrumClass.SNARE
                "closed-hat", "pedal-hat" -> DrumClass.HAT_CLOSED
                "open-hat" -> DrumClass.HAT_OPEN
                "rack-tom", "low-tom" -> DrumClass.TOM
                "handclap" -> DrumClass.CLAP
                else -> DrumClass.PERC
            }
            ArrangedPad(grid.last().first(), cls, level = .65f, displayName = labels[slot - 1],
                velocityTakes = grid, muteGroup = if (choice == Choice.EARLY && slot in setOf(3, 4, 10)) 1 else 0,
                source = mapOf("factory" to "sunroom", "bankVersion" to VERSION.toString(),
                    "kit" to choice.bank, "role" to voice, "slot" to slot.toString(),
                    "sourceKind" to "frozen synthesis and reference-derived resynthesis", "rendered" to "true",
                    "articulation" to "finite", "zones" to "3", "takes" to TAKES.toString(),
                    "velocityKind" to if (voice == "cymbal-swell") "authored upper-mode damping over the measured crescendo" else "authored or recorded-profile layers",
                    "roundRobin" to if (voice == "brush-snare" || choice == Choice.ORCHESTRAL && slot >= 15)
                        "resynthesis of separate source strikes" else "distinct procedural or reconstructed-phase variants",
                    "approval" to manifest.getValue("approval").str(),
                    "audition" to manifest.getValue("audition").str(), "siteCommit" to manifest.getValue("siteCommit").str()))
        }
    }
}
