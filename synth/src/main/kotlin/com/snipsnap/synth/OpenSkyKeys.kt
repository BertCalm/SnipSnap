package com.snipsnap.synth

/** Native-MIDI HALO key zones. The current sample format carries an attack and
 * one sustain loop; note-off fades the whole wet sample via the program envelope.
 * It cannot play a separately evolving room tail after the source is released.
 */
object OpenSkyKeys {
    const val MIN_ZONE_MIDI = 24
    const val MAX_ZONE_MIDI = 108
    const val MIN_CENTER_MIDI = 36
    const val MAX_CENTER_MIDI = 96
    const val DEFAULT_LOOP_SECONDS = 3f
    const val MAX_LOOP_SECONDS = 4f
    const val MAX_SAMPLE_SECONDS = 30f

    fun midis(voice: OpenSkyVoice, rootMidi: Int = OpenSky.DEFAULT_MIDI): List<Int> {
        require(voice == OpenSkyVoice.HALO) { "OPENSKY key sustain currently supports HALO only" }
        require(rootMidi in MIN_CENTER_MIDI..MAX_CENTER_MIDI) { "OPENSKY key center must be MIDI $MIN_CENTER_MIDI..$MAX_CENTER_MIDI" }
        return (-12..12 step 3).map { rootMidi + it }
    }

    fun render(
        voice: OpenSkyVoice,
        macros: Map<String, Float>,
        midi: Int,
        velocity: Float = 1f,
        timbre: OpenSkyTimbre = OpenSkyTimbre(),
        wet: Boolean = true,
        loopSeconds: Float = DEFAULT_LOOP_SECONDS,
        cancelled: () -> Boolean = { false },
    ): KeyNote {
        val raw = OpenSky.keyProbe(voice, macros, midi, velocity, timbre, wet, loopSeconds, cancelled).note
        return raw.copy(snip = OpenSkyPatch.applyHeadroom(raw.snip, cancelled))
    }
}
