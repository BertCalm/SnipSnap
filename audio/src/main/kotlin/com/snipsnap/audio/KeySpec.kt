package com.snipsnap.audio

/**
 * A musical key: root as semitones above C, plus the scale. The one type
 * every surface means by "the kit's key" — the CLI's `--key`, the app's
 * key picker, and the `key` field persisted in `kit.json`.
 *
 * [parse] accepts the ways people actually write keys — `Am`, `C`,
 * `F#m`, `Eb major`, `Bbmin`, `Dminpent` — rather than demanding an enum
 * name. A bare note means major, `m`/`min`/`minor` means minor, matching
 * how key signatures are read everywhere else.
 */
data class KeySpec(val rootSemitone: Int, val scale: Scale) {

    init {
        require(rootSemitone in 0..11) { "root is 0..11 semitones above C, got $rootSemitone" }
    }

    /** "A minor", "D# major pentatonic" — display form. */
    val label: String
        get() = Scales.NOTE_NAMES[rootSemitone] + " " +
            scale.name.lowercase().replace('_', ' ')

    /** "A MINOR" in machine-stable parts, for serialization. */
    val noteName: String get() = Scales.NOTE_NAMES[rootSemitone]

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

        /** Throws [IllegalArgumentException] for anything it can't read. */
        fun parse(spec: String): KeySpec {
            val m = Regex("^([A-Ga-g])([#b]?)[\\s-]*([A-Za-z]*)$").matchEntire(spec.trim())
                ?: throw IllegalArgumentException("can't read key '$spec' - try Am, C major, F#minpent")
            var semi = NOTE_SEMITONE.getValue(m.groupValues[1].uppercase()[0])
            when (m.groupValues[2]) {
                "#" -> semi = (semi + 1) % 12
                "b" -> semi = (semi + 11) % 12
            }
            val scale = SCALE_WORDS[m.groupValues[3].lowercase()]
                ?: throw IllegalArgumentException(
                    "unknown scale '${m.groupValues[3]}' in '$spec' - " +
                        "try major, minor, majpent, minpent, chromatic",
                )
            return KeySpec(semi, scale)
        }
    }
}
