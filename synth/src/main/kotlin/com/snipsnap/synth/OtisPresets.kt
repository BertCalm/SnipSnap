package com.snipsnap.synth

/** Only the finite performances auditioned during the accepted character and draw rounds. */
object OtisPresets {
    private fun events(midis: List<Int>, anchor: Int = 60, gate: Double = 1.5, velocity: Float = .8f) =
        midis.map { OtisEvent(it - anchor, gateSeconds = gate, velocity = velocity) }
    private fun p(name: String, voice: OtisVoice, events: List<OtisEvent> = listOf(OtisEvent()), midi: Int = Otis.defaultMidi(voice),
        air: OtisAirProfile = OtisAirProfile()) = OtisPatch(name, voice, midi = midi, profile = OtisProfile(events = events, air = air))

    private val organ = listOf(
        p("ORGAN NOTE", OtisVoice.ORGAN),
        p("ORGAN CHORD", OtisVoice.ORGAN, events(listOf(60, 63, 67, 70), velocity = .68f)))
    private val steam = listOf(
        p("STEAM NOTE", OtisVoice.STEAM),
        p("LOW STEAM", OtisVoice.STEAM, midi = 48),
        p("STEAM CHORD", OtisVoice.STEAM, events(listOf(48, 60, 67), velocity = .7f)),
        p("PUSHED STEAM", OtisVoice.STEAM,
            listOf(0, 3, 7, 0).mapIndexed { i, pitch -> OtisEvent(pitch, i * .42, .34, .9f) },
            air = OtisAirProfile(pressure = .95f, breath = .18f, steamInstability = .65f)))
    private val harp = listOf(
        p("HARP NOTE", OtisVoice.HARP),
        p("HARP CHORD", OtisVoice.HARP, events(listOf(60, 64, 67), velocity = .78f), midi = 60),
        p("HARP DRAW", OtisVoice.HARP, events(listOf(67, 71, 74, 77), anchor = 67, velocity = .78f)),
        p("HARP STABS", OtisVoice.HARP, listOf(0.0, .45, .9).flatMap { start ->
            listOf(0, 4, 7).map { OtisEvent(it, start, .19, .82f) }
        }, midi = 60),
        p("HARP BLUES", OtisVoice.HARP,
            listOf(0, 3, 5, 6, 7, 10, 0).mapIndexed { i, pitch -> OtisEvent(pitch, i * .30, .25, .84f) },
            air = OtisAirProfile(harpBend = .8f, breath = .5f, pressure = .8f, harpVibrato = .45f)))
    private val squeeze = listOf(
        p("SQUEEZE NOTE", OtisVoice.SQUEEZE),
        p("DRY SQUEEZE", OtisVoice.SQUEEZE, air = OtisAirProfile(musette = 0f, bellowsPulse = .1f)),
        p("SQUEEZE CHORD", OtisVoice.SQUEEZE, events(listOf(60, 63, 67, 70), velocity = .68f)),
        p("SQUEEZE DRAW", OtisVoice.SQUEEZE, events(listOf(60, 63, 67, 70), gate = 3.6, velocity = .68f)),
        p("SHARED KEYS", OtisVoice.SQUEEZE, listOf(OtisEvent(0, gateSeconds = 1.8), OtisEvent(3, .45, .65))))

    fun forVoice(voice: OtisVoice): List<OtisPatch> = when (voice) {
        OtisVoice.ORGAN -> organ
        OtisVoice.STEAM -> steam
        OtisVoice.HARP -> harp
        OtisVoice.SQUEEZE -> squeeze
    }
    fun all(): List<OtisPatch> = OtisVoice.entries.flatMap(::forVoice)
}
