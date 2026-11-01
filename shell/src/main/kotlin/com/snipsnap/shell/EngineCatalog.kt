package com.snipsnap.shell

/**
 * The kinds of sound the SYNTH engine picker filters by. The [label] is what the
 * chip says, so it is caps like every other chip. The set is meant to grow: a new
 * category is one constant here, and the picker draws a chip for it.
 *
 * The nine were drawn by the luthier from what each engine's voices and docs say
 * it is for, not from how it sounds. FX is folded into [PADS] because only SIREN
 * and VENT are plain effect engines and a category of two or three is not a
 * filter. [BELLS] says mallets because the set includes wood bars, glass and
 * ice-like plates, not only metal.
 */
enum class EngineCategory(val label: String) {
    DRUMS("DRUMS & PERCUSSION"),
    BASS("BASS"),
    KEYS("KEYS & ORGANS"),
    STRINGS("PLUCKED & STRINGS"),
    WINDS("WINDS & BRASS"),
    VOICES("VOICES"),
    SYNTH("SYNTH"),
    BELLS("BELLS & MALLETS"),
    PADS("PADS & FX"),
}

/**
 * What the picker knows about one engine beyond its name: one or two [categories],
 * the first being what it is chiefly for, and a [description] of at most
 * [MAX_DESCRIPTION] characters. The description is a message, so it stays in
 * sentence case.
 */
data class EngineEntry(val categories: List<EngineCategory>, val description: String) {
    /** The category the engine is chiefly for: the first one listed. */
    val primary: EngineCategory get() = categories.first()

    companion object {
        /** The longest a description may be: it shares a row with the name and two more lines. */
        const val MAX_DESCRIPTION = 60

        /** The most categories one engine carries; a third would make the filters stop discriminating. */
        const val MAX_CATEGORIES = 2
    }
}

/**
 * Every engine on SYNTH's picker, tagged and described. Keyed by the constant's own
 * name (`Engine.name` in `SynthScreen.kt`, so `SCANNED_CHAIN`, with the underscore).
 *
 * A convention law holds this to the picker in both directions, so an engine cannot
 * ship without a tag and a description, and a removed one cannot leave an entry
 * behind. Nothing here was heard: it classifies what each engine is for.
 */
object EngineCatalog {
    val ENTRIES: Map<String, EngineEntry> = mapOf(
        "LATTICE" to EngineEntry(listOf(EngineCategory.SYNTH), "Warm, plucked or hollow notes that drop out and come back"),
        "THUMP" to EngineEntry(listOf(EngineCategory.DRUMS), "Analog-style kick, snare, hats, clap, tom, cowbell, rim"),
        "SCANNED_CHAIN" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.BELLS), "Evolving pitched notes: springy plucks, metal and swells"),
        "SKIN" to EngineEntry(listOf(EngineCategory.DRUMS), "Acoustic-style kick, snare, hats, toms, ride and shaker"),
        "ONSET" to EngineEntry(listOf(EngineCategory.DRUMS), "Kick, snare and hats that blend electronic and acoustic"),
        "TINES" to EngineEntry(listOf(EngineCategory.BELLS, EngineCategory.DRUMS), "Bells, chimes, kalimba and small percussion hits"),
        "VELVET" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.BASS), "Classic subtractive synth: bass, brass, acid and chip"),
        "VOX" to EngineEntry(listOf(EngineCategory.VOICES, EngineCategory.PADS), "Choir, robot, speech, throat singing and beatbox"),
        "PLUCK" to EngineEntry(listOf(EngineCategory.STRINGS, EngineCategory.BASS), "Nylon, harp, koto, banjo, sitar and upright bass plucks"),
        "ORPHICA" to EngineEntry(listOf(EngineCategory.STRINGS, EngineCategory.PADS), "Plucked harp that breaks into stuttering grains as it rings"),
        "TONEWHEEL" to EngineEntry(listOf(EngineCategory.KEYS), "Drawbar organ: full, soul and stab registrations"),
        "REGISTERS" to EngineEntry(listOf(EngineCategory.KEYS), "Pipe organ, reed organ and transistor organ"),
        "FATHOM" to EngineEntry(listOf(EngineCategory.BASS), "Deep, grinding and glassy bass with glide"),
        "RESIN" to EngineEntry(listOf(EngineCategory.BASS, EngineCategory.SYNTH), "Fat analog-style bass and leads with a singing filter"),
        "TIDE" to EngineEntry(listOf(EngineCategory.DRUMS, EngineCategory.SYNTH), "Bongo and conga hits, water drips, gongs and plinks"),
        "GLINT" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.VOICES), "Buzzy synth tones with a sweeping, vowel-like peak"),
        "SIREN" to EngineEntry(listOf(EngineCategory.PADS), "Dub siren: wails, trills, lasers and bird chirps"),
        "FORK" to EngineEntry(listOf(EngineCategory.KEYS, EngineCategory.BELLS), "Electric piano and vibraphone-style bars"),
        "FLOTILLA" to EngineEntry(listOf(EngineCategory.PADS), "Slow looping textures of soft pulses and wooden knocks"),
        "AEROSTAT" to EngineEntry(listOf(EngineCategory.WINDS, EngineCategory.DRUMS), "Tube knock followed by a rising, whistling air tone"),
        "TREMOR" to EngineEntry(listOf(EngineCategory.DRUMS), "Low drum hits with rattling beads and ringing strings"),
        "BALLAST" to EngineEntry(listOf(EngineCategory.BASS), "Deep electronic bass with ringing strings and glass taps"),
        "CISTERN" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.DRUMS), "Looping drips and splashes on a drum skin"),
        "CIRCUIT" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.WINDS), "Looping ensemble of breathy tones, rattles and wood clacks"),
        "OBBLIGATO" to EngineEntry(listOf(EngineCategory.WINDS), "Flute and reed pair that play and answer each other"),
        "ALLOY" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.BASS), "FM-style keys, bass, plucks, brass and strings"),
        "THAW" to EngineEntry(listOf(EngineCategory.BELLS, EngineCategory.PADS), "Icy glass-like plate tones, struck or rubbed"),
        "MURK" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.DRUMS), "Tuned wood knocks and owl hoots, mostly as loops"),
        "NIMBUS" to EngineEntry(listOf(EngineCategory.BELLS, EngineCategory.PADS), "Six cymbal-like metal plates that ring, shimmer and roll"),
        "COROLLA" to EngineEntry(listOf(EngineCategory.BELLS, EngineCategory.PADS), "Soft metallic chimes that swell into clustered bell tones"),
        "UNDERTOW" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.WINDS), "Looping breathy, hollow tones with ceramic clicks"),
        "SUTURE" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.BELLS), "Looping bronze-like tones that swell, strain and settle"),
        "TESSERA" to EngineEntry(listOf(EngineCategory.BELLS), "Tuned mallet sounds: wood bars, wire strings, metal tubes"),
        "PITCHWHEEL" to EngineEntry(listOf(EngineCategory.STRINGS, EngineCategory.DRUMS), "Pitched wooden ratchet clicks, plucks and drawn tones"),
        "REVEL" to EngineEntry(listOf(EngineCategory.DRUMS), "Interlocking hand-drum phrases from six tuned skins"),
        "PRISM" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.KEYS), "Additive synth: keys, plucks, organ, bass, glass, slow pads"),
        "CONTOUR" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.BASS), "Wavetable synth: bass, keys, plucks, leads and swells"),
        "STOOP" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.BASS), "Groove synth: bass, e-piano, clav, organ, lead, chords"),
        "LACQUER" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.KEYS), "Layered keys, glass, brass, bass, pluck and silky tones"),
        "SPOOL" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.VOICES), "Tape-machine strings, choir, flute and brass textures"),
        "SKITTER" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.BASS), "Gritty, unstable synth bass, leads and chirps"),
        "TANGLE" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.BASS), "Beating, metallic synth bass, leads and pulses"),
        "SHOAL" to EngineEntry(listOf(EngineCategory.BELLS, EngineCategory.PADS), "Shimmering glass, wood, bowl and wire tones that drift"),
        "RELAY" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.KEYS), "Keys, bass, plucks and organ that change tone mid-note"),
        "EMBOUCHURE" to EngineEntry(listOf(EngineCategory.WINDS), "Reed, brass, whistle and bowed tones shaped by effort"),
        "BELLOWS" to EngineEntry(listOf(EngineCategory.WINDS, EngineCategory.KEYS), "Accordion and hurdy-gurdy style reed tones with drones"),
        "MURMUR" to EngineEntry(listOf(EngineCategory.VOICES, EngineCategory.SYNTH), "Vowel-like FM tones: choir, bass, keys, pluck and lead"),
        "OSPREY" to EngineEntry(listOf(EngineCategory.PADS), "Pitched sea swells with bird and animal calls"),
        "OPENSKY" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.SYNTH), "Wide, shimmering saw leads and pads, plus a crystal bell"),
        "OVERLAP" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.BELLS), "Glassy plucks, swelling textures and pulsing breaths"),
        "OVEN" to EngineEntry(listOf(EngineCategory.KEYS, EngineCategory.STRINGS), "Piano-like hammered strings from felt-soft to hard"),
        "FLOCK" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.SYNTH), "Swarm of drifting oscillators that gathers into one note"),
        "VENT" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.SYNTH), "Unpredictable chaotic tones: round, wire, tearing, pulsing"),
        "CREASE" to EngineEntry(listOf(EngineCategory.SYNTH), "Synth tones that bend and relax: round, pinched, rubbery"),
        "TRIO" to EngineEntry(listOf(EngineCategory.STRINGS), "Tres, berimbau and charango plucks, solo or together"),
        // NACRE (#722) reached the picker as the catalogue's law landed (#730). Classified from its own KDoc
        // (a played string duet with sympathetic, watery returns), not from a listen.
        "NACRE" to EngineEntry(listOf(EngineCategory.STRINGS, EngineCategory.PADS), "A plucked string duet with shimmering, watery returns"),
        "ORIGAMI" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.BELLS), "Metallic keys and soft sustained tones with folded overtones"),
        // TREFOIL (#731) joins the picker beside ORIGAMI. Classified from TREFOIL.md, not from a listen.
        "TREFOIL" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.BELLS), "Pitched three-body tones that touch and exchange energy"),
        "OHM" to EngineEntry(listOf(EngineCategory.STRINGS), "Warm plucked string with octave and glassy companions"),
        "MERCURY" to EngineEntry(listOf(EngineCategory.BELLS), "Glass and steel tones that are tapped, rubbed and bent"),
        "CAUSTIC" to EngineEntry(listOf(EngineCategory.SYNTH, EngineCategory.BELLS), "Resonant singing tones with a full body and long tail"),
        "FILAMENT" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.BELLS), "Glassy pitched notes with a shimmering tail"),
        "APSIS" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.BELLS), "Evolving ringing tones whose overtones sweep and linger"),
        "TIDAL" to EngineEntry(listOf(EngineCategory.PADS, EngineCategory.BELLS), "Evolving ringing tones with a rough, string-like edge"),
        "CHAMBER" to EngineEntry(listOf(EngineCategory.DRUMS, EngineCategory.BELLS), "Tuned drum, bar, cowbell and triangle hits in a room"),
        "LIBRATION" to EngineEntry(listOf(EngineCategory.KEYS, EngineCategory.BELLS), "Wooden and glassy notes with moving, lingering overtones"),
        "MERIDIAN" to EngineEntry(listOf(EngineCategory.BELLS, EngineCategory.PADS), "Swinging contacts leave wood, wire and glass ringing"),
    )

    /** The entry for the engine constant [name], or null for a name the catalogue does not know. */
    fun entry(name: String): EngineEntry? = ENTRIES[name]
}
