package com.snipsnap.synth

/**
 * Six dry starting voices for the first Relay audition. Each carries the complete phrase,
 * not just a list of knob positions. The proposed 36-sound roster waits for sonic approval.
 */
object RelayPresets {
    private fun p(voice: RelayVoice, name: String, vararg macros: Pair<String, Float>) =
        RelayPatch(name, voice, Relay.defaults(voice) + macros.toMap())

    private val seeds: List<RelayPatch> by lazy {
        listOf(
            p(RelayVoice.KEY, "FIRST HANDOFF"),
            p(RelayVoice.BASS, "POCKET RELAY"),
            p(RelayVoice.PLUCK, "MUTED PASSAGE"),
            p(RelayVoice.ORGAN, "ORGAN AFTERGLOW"),
            p(RelayVoice.CHIME, "BELL EXIT"),
            p(RelayVoice.FLOW, "SOFT EXCHANGE"),
        )
    }

    fun forVoice(voice: RelayVoice): List<RelayPatch> = seeds.filter { it.voice == voice }

    fun all(): List<RelayPatch> = seeds.toList()
}
