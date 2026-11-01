package com.snipsnap.synth

/** The same musical probes across the unchanged sources, with every control left at its default. */
internal object OtisRangeStudy {
    data class Case(
        val id: String,
        val title: String,
        val family: String,
        val register: String,
        val group: String,
        val description: String,
        val request: OtisPrototypeRequest,
    )

    fun cases(): List<Case> = buildList {
        for (voice in OtisPrototypeVoice.entries) {
            val family = voice.name.lowercase()
            val label = when (voice) {
                OtisPrototypeVoice.ORGAN -> "Organ"
                OtisPrototypeVoice.STEAM -> "Steam"
                OtisPrototypeVoice.HARP -> "Blues harmonica"
                OtisPrototypeVoice.SQUEEZE -> "Squeeze"
            }
            val defaults = OtisPrototypeRequest(voice, model = 3)
            for ((register, root) in listOf("low" to 48, "middle" to 60, "high" to 72)) {
                val octave = root / 12 - 1
                val rootName = "C$octave"
                for ((gesture, velocity) in listOf("soft" to .35f, "hard" to .90f)) {
                    add(Case(
                        id = "${family}_${register}_$gesture",
                        title = "$label · $register · $gesture",
                        family = voice.name,
                        register = register,
                        group = if (gesture == "soft") "Soft note" else "Hard note",
                        description = "$rootName / MIDI $root, velocity ${if (gesture == "soft") ".35" else ".90"}, " +
                            "1.5-second gate. Same note and gate in every family.",
                        request = defaults.copy(notes = listOf(OtisPrototypeNote(midi = root, velocity = velocity))),
                    ))
                }

                val phrase = listOf(0, 3, 7, 5).mapIndexed { index, interval ->
                    OtisPrototypeNote(root + interval, startSeconds = index * .52, gateSeconds = .40, velocity = .8f)
                }
                add(Case(
                    id = "${family}_${register}_phrase",
                    title = "$label · $register · phrase",
                    family = voice.name,
                    register = register,
                    group = "Phrase",
                    description = "C minor phrase: C$octave–E♭$octave–G$octave–F$octave / " +
                        "MIDI ${phrase.joinToString(", ") { it.midi.toString() }}; velocity .80, " +
                        ".52-second spacing and .40-second gates. One continuous request in every family.",
                    request = defaults.copy(notes = phrase),
                ))

                if (voice == OtisPrototypeVoice.ORGAN || voice == OtisPrototypeVoice.SQUEEZE) {
                    val chord = listOf(0, 3, 7, 10).map { interval ->
                        OtisPrototypeNote(root + interval, gateSeconds = 1.5, velocity = .68f)
                    }
                    add(Case(
                        id = "${family}_${register}_chord",
                        title = "$label · $register · Cm7",
                        family = voice.name,
                        register = register,
                        group = "Chord",
                        description = "C minor seventh (Cm7): C$octave–E♭$octave–G$octave–B♭$octave / " +
                            "MIDI ${chord.joinToString(", ") { it.midi.toString() }}; simultaneous onset, " +
                            "velocity .68 and 1.5-second gates.",
                        request = defaults.copy(notes = chord),
                    ))
                }
            }
        }
    }
}
