package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/**
 * FRESH TAPE → ETCH (round K2): sixteen pads from the factory bank, every one a sound the owner heard in round 3.
 *
 * - A01 to A08: SAW's note walking C minor pentatonic up from C2 (C, E♭, F, G, B♭, then the octave's C, E♭, F), so
 *   the kit plays as a bass straight away.
 * - A09 to A13: the other five voices' notes, each on its own default root.
 * - A14 to A16, loops last: RUBBER's line, GRIT's line and SAW's busy line, the three at the kit's [BPM]. SQUARE's
 *   and DUB's lines run at 112 BPM, so they stay presets rather than sit in a kit at another tempo.
 *
 * Every one-shot pad is a grid of three velocity zones ([VELOCITIES]) by four takes: the zones are the engine's own
 * accent (a harder hit opens the filter and charges the accent capacitor, not only louder), and the takes are the
 * engine's dealt round-robin set ([Etch.takeFor], takes 1 to 4; take 0, the plain render, is not on a pad). A loop
 * pad has the three zones and no takes: restarting a phrase plays the same phrase, and a loop render costs 0.3 to
 * 0.5 s, so twelve of them would slow the kit down for nothing a listener hears. Level is the engine's: a zone keeps
 * [Etch.render]'s velocity law, so a soft zone is softer as well as darker.
 */
object EtchKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 1
    const val ID = "etch"
    const val PADS = 16
    const val TAKES = 4
    /** The loops' tempo, and the kit's. */
    const val BPM = 130
    val VELOCITIES = listOf(.30f, .65f, 1f)

    /** The walk, in semitones over C2. */
    private val WALK = listOf(0, 3, 5, 7, 10, 12, 15, 17)

    /** One pad: its source preset, the note it plays, and its name on the pad. */
    class Pad(val preset: EtchPatch, val midi: Int, val name: String) {
        val loop: Boolean get() = Etch.isLoop(Etch.settled(preset.voice, preset.macros).getValue("HOLD"))
    }

    fun pads(): List<Pad> {
        fun preset(voice: EtchVoice, name: String) = EtchPresets.forVoice(voice).first { it.name == name }
        val saw = preset(EtchVoice.SAW, "Note")
        val walk = WALK.map { s -> Pad(saw, Etch.DEFAULT_MIDI + s, "SAW ${com.snipsnap.audio.Scales.nameOf(Etch.DEFAULT_MIDI + s)}") }
        val notes = listOf(EtchVoice.SQUARE, EtchVoice.RUBBER, EtchVoice.WHISTLE, EtchVoice.GRIT, EtchVoice.DUB).map { v ->
            val p = preset(v, "Note"); Pad(p, p.midi, "${v.name} NOTE")
        }
        // Loops at the kit's one tempo: SQUARE's and DUB's lines run at 112 BPM, so they stay presets.
        val loops = listOf(
            Pad(preset(EtchVoice.RUBBER, "Line"), Etch.DEFAULT_MIDI, "RUBBER LINE"),
            Pad(preset(EtchVoice.GRIT, "Line"), Etch.DEFAULT_MIDI, "GRIT LINE"),
            Pad(preset(EtchVoice.SAW, "Busy line"), Etch.DEFAULT_MIDI, "SAW BUSY LINE"),
        ).onEach { check(Etch.lineFor(it.preset.voice).bpm == BPM.toDouble()) { "${it.name} is not at the kit's tempo" } }
        return (walk + notes + loops).also { check(it.size == PADS) }
    }

    /** One cell of a pad: its preset at the pad's note, [velocity] and engine take [take] (0 is the plain render). */
    fun recipe(pad: Pad, velocity: Float): EtchPatch = pad.preset.copy(midi = pad.midi, velocity = velocity)

    fun render(pad: Pad, velocity: Float, take: Int): Snip = recipe(pad, velocity).render(velocity, take)

    /**
     * The sixteen pads. Every cell is an independent pure render, so they run in parallel, the slow loop cells
     * first, and are then assembled exactly as [arranged] assembles them: the same bytes, sooner.
     */
    fun build(): List<ArrangedPad> {
        val pads = pads()
        val cells = pads.indices.flatMap { p -> cellsOf(pads[p]).map { (v, t) -> Triple(p, v, t) } }
            .sortedByDescending { pads[it.first].loop }
        val rendered = cells.parallelStream().map { cell -> cell to render(pads[cell.first], cell.second, cell.third) }
            .collect(java.util.stream.Collectors.toList()).toMap()
        return pads.mapIndexed { p, pad -> assemble(pad) { v, t -> rendered.getValue(Triple(p, v, t)) } }
    }

    /** The (velocity, engine take) cells a pad holds, in the order [assemble] lays them out. */
    private fun cellsOf(pad: Pad): List<Pair<Float, Int>> =
        if (pad.loop) VELOCITIES.map { it to 0 } else VELOCITIES.flatMap { v -> (1..TAKES).map { t -> v to t } }

    /** One pad as the kit lands it: a velocity × take grid for a note, three velocity zones for a loop. */
    fun arranged(pad: Pad): ArrangedPad = assemble(pad) { v, t -> render(pad, v, t) }

    private fun assemble(pad: Pad, cell: (Float, Int) -> Snip): ArrangedPad {
        val source = mapOf(
            "engine" to EtchPatch.ENGINE, "kitVersion" to VERSION.toString(), "model" to Etch.MODEL_VERSION.toString(),
            "voice" to pad.preset.voice.name, "preset" to pad.preset.name, "midi" to pad.midi.toString(),
            "velocities" to VELOCITIES.joinToString(","), "takes" to if (pad.loop) "1" else TAKES.toString(),
            "articulation" to if (pad.loop) "loop" else "finite",
        )
        val cls = Etch.drumClassFor(pad.preset.voice, pad.preset.macros)
        return if (pad.loop) {
            val zones = VELOCITIES.map { v -> cell(v, 0) }
            ArrangedPad(zones.last(), DrumClass.LOOP, softVariants = zones.dropLast(1), displayName = pad.name,
                // The loops choke each other, so a new line cuts the last one.
                muteGroup = 2, source = source)
        } else {
            val grid = VELOCITIES.map { v -> List(TAKES) { t -> cell(v, t + 1) } }
            ArrangedPad(grid.last().first(), cls, displayName = pad.name, velocityTakes = grid,
                // One bass: a new note cuts the last one, as a monophonic line does.
                muteGroup = 1, source = source)
        }
    }
}
