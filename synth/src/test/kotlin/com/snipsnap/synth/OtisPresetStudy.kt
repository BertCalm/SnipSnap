package com.snipsnap.synth

/** Twelve listening candidates built entirely from the unchanged model-3 controls. */
internal object OtisPresetStudy {
    data class Preset(
        val id: String,
        val name: String,
        val voice: OtisPrototypeVoice,
        val description: String,
        val organ: OtisOrganSettings,
        val air: OtisAirSettings,
    )

    data class Case(
        val id: String,
        val title: String,
        val family: String,
        val group: String,
        val description: String,
        val presetId: String,
        val presetName: String,
        val request: OtisPrototypeRequest,
        val comparison: OtisPrototypeRequest,
    )

    fun presets(): List<Preset> {
        val organ = OtisOrganSettings()
        val air = OtisAirSettings()
        return listOf(
            Preset(
                "organ_warm_room", "Warm Room", OtisPrototypeVoice.ORGAN,
                "A rounder low registration, softer contacts and a darker cabinet than the default organ; gentle drive keeps the body warm.",
                organ.copy(
                    drawbars = listOf(.72f, .38f, 1f, .20f, .10f, .06f, 0f, 0f, 0f),
                    click = .22f, percussionLevel = .18f, percussionHarmonic = OtisOrganPercussion.SECOND,
                    percussionDecaySeconds = .35, leakage = .25f, drive = .10f, cabinetHz = 3400.0,
                ), air,
            ),
            Preset(
                "organ_front_pew", "Front Pew", OtisPrototypeVoice.ORGAN,
                "Brighter upper drawbars, a firmer contact clack and shorter second-harmonic percussion give more bite than the default registration.",
                organ.copy(
                    drawbars = listOf(1f, .75f, 1f, .85f, .50f, .65f, .30f, .35f, .20f),
                    click = .68f, percussionLevel = .55f, percussionHarmonic = OtisOrganPercussion.SECOND,
                    percussionDecaySeconds = .20, leakage = .40f, drive = .33f, cabinetHz = 7600.0,
                ), air,
            ),
            Preset(
                "organ_turning_gold", "Turning Gold", OtisPrototypeVoice.ORGAN,
                "An open upper registration feeds a deeper rotating cabinet, moving from slow toward fast instead of the default dry cabinet.",
                organ.copy(
                    drawbars = listOf(.85f, .65f, 1f, .65f, .32f, .35f, .12f, .18f, .10f),
                    click = .40f, percussionLevel = .25f, drive = .18f,
                    rotary = true, rotaryStart = OtisOrganRotary.SLOW, rotaryTarget = OtisOrganRotary.FAST,
                    rotaryChangeSeconds = .20, rotaryDepth = .95f,
                ), air,
            ),
            Preset(
                "steam_roundhouse", "Roundhouse", OtisPrototypeVoice.STEAM,
                "Lower pressure rounds the pipe harmonics, with less opening air and steadier pitch than the default steam voice.",
                organ, air.copy(steamInstability = .12f, breath = .12f, pressure = .25f, release = .60f),
            ),
            Preset(
                "steam_boiler_call", "Boiler Call", OtisPrototypeVoice.STEAM,
                "Higher pressure gives the pipe a more forceful harmonic body and opening chuff, with less wandering pitch than the default.",
                organ, air.copy(steamInstability = .28f, breath = .52f, pressure = .95f, release = .20f),
            ),
            Preset(
                "steam_loose_valve", "Loose Valve", OtisPrototypeVoice.STEAM,
                "Stronger shared pressure drift and local pipe wobble make the steam voice less settled than the default, while a firm pipe body remains.",
                organ, air.copy(steamInstability = .95f, breath = .40f, pressure = .75f, release = .55f),
            ),
            Preset(
                "harp_easy_breath", "Easy Breath", OtisPrototypeVoice.HARP,
                "A gentler blues harmonica: lower breath pressure, a shallower entrance bend and lighter vibrato than the default.",
                organ, air.copy(harpBend = .20f, breath = .12f, harpVibrato = .18f, pressure = .25f, release = .65f),
            ),
            Preset(
                "harp_tin_cup", "Tin Cup", OtisPrototypeVoice.HARP,
                "More breath rasp and harder reed harmonics bring the blues harmonica forward, with a smaller entrance bend than the default.",
                organ, air.copy(harpBend = .45f, breath = .82f, harpVibrato = .34f, pressure = .90f, release = .28f),
            ),
            Preset(
                "harp_blue_pull", "Blue Pull", OtisPrototypeVoice.HARP,
                "A deeper scoop rises into each blues-harmonica note, followed by more pronounced delayed vibrato than the default.",
                organ, air.copy(harpBend = 1f, breath = .30f, harpVibrato = .62f, pressure = .72f, release = .50f),
            ),
            Preset(
                "squeeze_dry_button", "Dry Button", OtisPrototypeVoice.SQUEEZE,
                "Nearly aligned reed ranks and quieter bellows pulses give a drier, more focused sound than the default squeeze voice.",
                organ, air.copy(musette = .08f, bellowsPulse = .15f, pressure = .65f, release = .25f),
            ),
            Preset(
                "squeeze_wide_musette", "Wide Musette", OtisPrototypeVoice.SQUEEZE,
                "Wider reed spacing makes the musette beating more prominent than the default, with gentler bellows pulses around it.",
                organ, air.copy(musette = .95f, bellowsPulse = .30f, pressure = .60f, release = .60f),
            ),
            Preset(
                "squeeze_bellows_dance", "Bellows Dance", OtisPrototypeVoice.SQUEEZE,
                "Stronger bellows pulses open and close the reed brightness and level, with a tighter reed spread than the default.",
                organ, air.copy(musette = .48f, bellowsPulse = .95f, pressure = .90f, release = .24f),
            ),
        )
    }

    fun cases(): List<Case> = buildList {
        for (preset in presets()) {
            val note = listOf(OtisPrototypeNote(midi = 60, gateSeconds = 1.5, velocity = .8f))
            val phrase = listOf(60, 63, 67, 65).mapIndexed { index, midi ->
                OtisPrototypeNote(midi, startSeconds = index * .52, gateSeconds = .40, velocity = .8f)
            }
            fun addCase(gesture: String, group: String, notes: List<OtisPrototypeNote>, description: String) {
                val comparison = OtisPrototypeRequest(voice = preset.voice, notes = notes, model = 3)
                add(Case(
                    id = "${preset.id}_$gesture", title = "${preset.name} · ${group.lowercase()}",
                    family = preset.voice.name, group = group,
                    description = "${preset.description} $description",
                    presetId = preset.id, presetName = preset.name,
                    request = comparison.copy(organ = preset.organ, air = preset.air), comparison = comparison,
                ))
            }
            addCase("note", "Note", note, "C4 / MIDI 60; velocity .80 and a 1.5-second gate.")
            addCase("phrase", "Phrase", phrase,
                "C minor phrase C4–E♭4–G4–F4 / MIDI 60, 63, 67, 65; velocity .80, .52-second spacing and .40-second gates.")
            if (preset.voice == OtisPrototypeVoice.ORGAN || preset.voice == OtisPrototypeVoice.SQUEEZE) {
                val chord = listOf(60, 63, 67, 70).map { midi ->
                    OtisPrototypeNote(midi, gateSeconds = 1.5, velocity = .68f)
                }
                addCase("chord", "Chord", chord,
                    "Cm7 chord C4–E♭4–G4–B♭4 / MIDI 60, 63, 67, 70; simultaneous onset, velocity .68 and 1.5-second gates.")
            }
        }
    }
}
