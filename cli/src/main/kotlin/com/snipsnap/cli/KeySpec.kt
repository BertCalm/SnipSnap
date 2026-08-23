package com.snipsnap.cli

import com.snipsnap.audio.Scale
import com.snipsnap.audio.Scales

/**
 * A parsed `--key` argument: root as semitones above C, plus the scale.
 *
 * Accepts the ways people actually write keys — `Am`, `C`, `F#m`,
 * `Eb major`, `Bbmin`, `Dminpent` — rather than demanding an enum name.
 * A bare note means major, `m`/`min`/`minor` means minor, matching how
 * key signatures are read everywhere else.
 */
data class KeySpec(val rootSemitone: Int, val scale: Scale) {

    val label: String
        get() = Scales.NOTE_NAMES[rootSemitone] + " " +
            scale.name.lowercase().replace('_', ' ')

    companion object {

        private val NOTE_SEMITONE = mapOf(
            'C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11,
        )

        private val SCALE_WORDS = mapOf(
            "" to Scale.MAJOR,
            "maj" to Scale.MAJOR,
            "major" to Scale.MAJOR,
            "m" to Scale.MINOR,
            "min" to Scale.MINOR,
            "minor" to Scale.MINOR,
            "majpent" to Scale.MAJOR_PENTATONIC,
            "majorpent" to Scale.MAJOR_PENTATONIC,
            "majorpentatonic" to Scale.MAJOR_PENTATONIC,
            "minpent" to Scale.MINOR_PENTATONIC,
            "minorpent" to Scale.MINOR_PENTATONIC,
            "minorpentatonic" to Scale.MINOR_PENTATONIC,
            "pent" to Scale.MINOR_PENTATONIC,
            "pentatonic" to Scale.MINOR_PENTATONIC,
            "chrom" to Scale.CHROMATIC,
            "chromatic" to Scale.CHROMATIC,
        )

        fun parse(spec: String): KeySpec {
            val m = Regex("^([A-Ga-g])([#b]?)[\\s-]*([A-Za-z]*)$").matchEntire(spec.trim())
                ?: throw CliError("can't read key '$spec' - try Am, C major, F#minpent")
            var semi = NOTE_SEMITONE.getValue(m.groupValues[1].uppercase()[0])
            when (m.groupValues[2]) {
                "#" -> semi = (semi + 1) % 12
                "b" -> semi = (semi + 11) % 12
            }
            val scale = SCALE_WORDS[m.groupValues[3].lowercase()]
                ?: throw CliError(
                    "unknown scale '${m.groupValues[3]}' in '$spec' - " +
                        "try major, minor, majpent, minpent, chromatic",
                )
            return KeySpec(semi, scale)
        }
    }
}
