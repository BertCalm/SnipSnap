package com.snipsnap.shell

import com.snipsnap.synth.Accord
import com.snipsnap.synth.AccordPatch
import com.snipsnap.synth.Aerostat
import com.snipsnap.synth.AerostatPatch
import com.snipsnap.synth.Accord
import com.snipsnap.synth.AccordPatch
import com.snipsnap.synth.Alloy
import com.snipsnap.synth.AlloyPatch
import com.snipsnap.synth.Apsis
import com.snipsnap.synth.ApsisPatch
import com.snipsnap.synth.Arco
import com.snipsnap.synth.ArcoPatch
import com.snipsnap.synth.Ballast
import com.snipsnap.synth.BallastPatch
import com.snipsnap.synth.Bellows
import com.snipsnap.synth.BellowsPatch
import com.snipsnap.synth.Bore
import com.snipsnap.synth.BorePatch
import com.snipsnap.synth.Cairn
import com.snipsnap.synth.CairnPatch
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.Chamber
import com.snipsnap.synth.ChamberPatch
import com.snipsnap.synth.Circuit
import com.snipsnap.synth.CircuitPatch
import com.snipsnap.synth.Cistern
import com.snipsnap.synth.CisternPatch
import com.snipsnap.synth.ContourEngine
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.Corolla
import com.snipsnap.synth.CorollaPatch
import com.snipsnap.synth.Crease
import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.Embouchure
import com.snipsnap.synth.EmbouchurePatch
import com.snipsnap.synth.ErosionPatch
import com.snipsnap.synth.Fathom
import com.snipsnap.synth.FathomPatch
import com.snipsnap.synth.Ferry
import com.snipsnap.synth.FerryPatch
import com.snipsnap.synth.Filament
import com.snipsnap.synth.FilamentPatch
import com.snipsnap.synth.Flock
import com.snipsnap.synth.FlockPatch
import com.snipsnap.synth.Flotilla
import com.snipsnap.synth.FlotillaPatch
import com.snipsnap.synth.Fork
import com.snipsnap.synth.ForkPatch
import com.snipsnap.synth.Glint
import com.snipsnap.synth.GlintPatch
import com.snipsnap.synth.Gyre
import com.snipsnap.synth.GyrePatch
import com.snipsnap.synth.Keel
import com.snipsnap.synth.Lorentz
import com.snipsnap.synth.LorentzPatch
import com.snipsnap.synth.KeelPatch
import com.snipsnap.synth.Lacquer
import com.snipsnap.synth.LacquerPatch
import com.snipsnap.synth.Lattice
import com.snipsnap.synth.LatticePatch
import com.snipsnap.synth.Libration
import com.snipsnap.synth.LibrationPatch
import com.snipsnap.synth.Lorentz
import com.snipsnap.synth.LorentzPatch
import com.snipsnap.synth.Magnet
import com.snipsnap.synth.MagnetPatch
import com.snipsnap.synth.Mercury
import com.snipsnap.synth.MercuryPatch
import com.snipsnap.synth.Meridian
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.synth.Murk
import com.snipsnap.synth.MurkPatch
import com.snipsnap.synth.Murmur
import com.snipsnap.synth.MurmurPatch
import com.snipsnap.synth.Nacre
import com.snipsnap.synth.NacrePatch
import com.snipsnap.synth.Nimbus
import com.snipsnap.synth.NimbusPatch
import com.snipsnap.synth.Obbligato
import com.snipsnap.synth.ObbligatoPatch
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmPatch
import com.snipsnap.synth.OnsetPatch
import com.snipsnap.synth.OpenSkyPatch
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.Orphica
import com.snipsnap.synth.OrphicaPatch
import com.snipsnap.synth.Osprey
import com.snipsnap.synth.OspreyPatch
import com.snipsnap.synth.Otis
import com.snipsnap.synth.OtisPatch
import com.snipsnap.synth.Oven
import com.snipsnap.synth.OvenPatch
import com.snipsnap.synth.Overlap
import com.snipsnap.synth.OverlapPatch
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Pitchwheel
import com.snipsnap.synth.PitchwheelPatch
import com.snipsnap.synth.Pluck
import com.snipsnap.synth.PluckPatch
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.Registers
import com.snipsnap.synth.RegistersPatch
import com.snipsnap.synth.Relay
import com.snipsnap.synth.RelayPatch
import com.snipsnap.synth.Resin
import com.snipsnap.synth.ResinPatch
import com.snipsnap.synth.Revel
import com.snipsnap.synth.RevelPatch
import com.snipsnap.synth.Rime
import com.snipsnap.synth.RimePatch
import com.snipsnap.synth.ScannedChain
import com.snipsnap.synth.ScannedChainPatch
import com.snipsnap.synth.Scour
import com.snipsnap.synth.ScourPatch
import com.snipsnap.synth.Shoal
import com.snipsnap.synth.ShoalPatch
import com.snipsnap.synth.SilkPatch
import com.snipsnap.synth.Siren
import com.snipsnap.synth.SirenPatch
import com.snipsnap.synth.SkinPatch
import com.snipsnap.synth.Skitter
import com.snipsnap.synth.SkitterPatch
import com.snipsnap.synth.Snap
import com.snipsnap.synth.SnapPatch
import com.snipsnap.synth.Spool
import com.snipsnap.synth.SpoolPatch
import com.snipsnap.synth.Stoop
import com.snipsnap.synth.StoopPatch
import com.snipsnap.synth.Suture
import com.snipsnap.synth.SuturePatch
import com.snipsnap.synth.Tangle
import com.snipsnap.synth.TanglePatch
import com.snipsnap.synth.TerraPatch
import com.snipsnap.synth.Tessera
import com.snipsnap.synth.TesseraPatch
import com.snipsnap.synth.Thaw
import com.snipsnap.synth.ThawPatch
import com.snipsnap.synth.ThumpPatch
import com.snipsnap.synth.Tidal
import com.snipsnap.synth.TidalPatch
import com.snipsnap.synth.Tide
import com.snipsnap.synth.TidePatch
import com.snipsnap.synth.Tines
import com.snipsnap.synth.TinesPatch
import com.snipsnap.synth.TinesVoice
import com.snipsnap.synth.Tonewheel
import com.snipsnap.synth.TonewheelPatch
import com.snipsnap.synth.Trefoil
import com.snipsnap.synth.TrefoilPatch
import com.snipsnap.synth.Tremor
import com.snipsnap.synth.TremorPatch
import com.snipsnap.synth.Trio
import com.snipsnap.synth.TrioPatch
import com.snipsnap.synth.Undertow
import com.snipsnap.synth.UndertowPatch
import com.snipsnap.synth.Velvet
import com.snipsnap.synth.VelvetPatch
import com.snipsnap.synth.Vent
import com.snipsnap.synth.VentPatch
import com.snipsnap.synth.VoxPatch
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPatch
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The root note of a tonal patch, read and stepped one way for every engine.
 *
 * SYNTH's ROOT / OCT steppers (the row STOOP and ORPHICA had first) need two things
 * from a patch: the note it plays, and a copy of the patch that plays a different
 * note with everything else kept. Engines store that note in two shapes:
 *
 * - **a saved field** (`midi` on the patch, STOOP, NACRE, OVEN and some forty others):
 *   stepping copies the patch with a new note. Where the engine also has a TUNE macro
 *   (ALLOY, MERIDIAN, OVEN, OTIS, BELLOWS...), TUNE stays what it was, a transposition
 *   of the base note, so the readout names the base note, the way MERIDIAN's caption
 *   already says: ROOT is the base note; TUNE transposes it.
 * - **the TUNE macro itself** (PLUCK, VELVET, PRISM, FORK, MERCURY and the other
 *   engines that ship with no note field): the engine snaps TUNE to semitones above a
 *   per-voice bottom, so stepping sets TUNE to the value that lands on the note asked
 *   for. The snap is inverted from the engine's own `midiFor` or `frequencyFor`, never
 *   from a second copy of its constants, so a register change in the engine moves the
 *   stepper with it. [Tune.tuneFor] is what the screen hands to its TUNE slider.
 *
 * [of] is an exhaustive `when` over the sealed [Patch], so a new engine does not
 * compile until someone decides whether it has a root (the compiler is the checklist
 * row). Drums, VOX, SILK's scale rows and the engines whose TUNE is a continuous
 * frequency sweep (TINES' bells, EROSION) answer null: a stepper on those would name a
 * note the engine does not play.
 *
 * Nothing here renders. The tests prove the round trip (step to a note, read that note
 * back) over every preset's whole range and hold the TUNE engines' frequency to within
 * five cents of the note named.
 */
object SynthRoot {

    /** A root the screen can read and step. [range] is the engine's own saved-note range. */
    sealed interface Control {
        val midi: Int
        val range: IntRange

        /** The patch playing [midi], clamped into [range], everything else kept. */
        fun at(midi: Int): Patch

        /** Whether a step of [delta] semitones stays inside [range]. */
        fun canStep(delta: Int): Boolean = midi + delta in range
    }

    /** The root is a saved note on the patch; [at] copies the patch with a new one. */
    class Field(
        override val midi: Int,
        override val range: IntRange,
        private val set: (Int) -> Patch,
    ) : Control {
        override fun at(midi: Int): Patch = set(midi.coerceIn(range))
    }

    /**
     * The root is the TUNE macro. [midiAt] is the engine's own snap from TUNE to a
     * note; [range] is what TUNE 0 and TUNE 1 land on. [tuneFor] inverts the snap.
     */
    class Tune(
        override val midi: Int,
        private val midiAt: (Float) -> Int,
        private val set: (tune: Float, midi: Int) -> Patch,
    ) : Control {
        private val bottom = midiAt(0f)
        private val top = midiAt(1f)
        override val range: IntRange = bottom..top

        /** The TUNE value whose snap lands on [midi] (clamped into [range]). */
        fun tuneFor(midi: Int): Float {
            val note = midi.coerceIn(range)
            return if (top == bottom) 0f else ((note - bottom).toFloat() / (top - bottom)).coerceIn(0f, 1f)
        }

        override fun at(midi: Int): Patch = set(tuneFor(midi), midi.coerceIn(range))
    }

    /** The root of [patch], or null for an engine that has none a note name would describe. */
    fun of(patch: Patch): Control? = when (patch) {
        // ---- a saved note, TUNE (where there is one) a transposition of it ----
        is AccordPatch -> Field(patch.midi, Accord.MIN_MIDI..Accord.MAX_MIDI) { patch.copy(midi = it) }
        is AlloyPatch -> Field(patch.midi, Alloy.MIDI_MIN..Alloy.MIDI_MAX) { patch.copy(midi = it) }
        is ApsisPatch -> Field(patch.midi, Apsis.MIDI_MIN..Apsis.MIDI_MAX) { patch.copy(midi = it) }
        is BellowsPatch -> Field(patch.midi, playedRange(Bellows.midiRange(patch.voice),
            patch.macros["TUNE"] ?: Bellows.defaults(patch.voice).getValue("TUNE"), Bellows.TUNE_SEMITONES)) { patch.copy(midi = it) }
        is CairnPatch -> Field(patch.midi, Cairn.MIN_MIDI..Cairn.MAX_MIDI) { patch.copy(midi = it) }
        is CausticPatch -> Field(patch.midi, CausticInstrument.MIDI_MIN..CausticInstrument.MIDI_MAX) { patch.copy(midi = it) }
        is ChamberPatch -> Field(patch.midi, Chamber.MIDI_MIN..Chamber.MIDI_MAX) { patch.copy(midi = it) }
        is CisternPatch -> Field(patch.midi, Cistern.MIDI_MIN..Cistern.MIDI_MAX) { patch.copy(midi = it) }
        is CreasePatch -> Field(patch.midi, Crease.MIDI_MIN..Crease.MIDI_MAX) { patch.copy(midi = it) }
        is EmbouchurePatch -> Field(patch.midi, playedRange(Embouchure.midiRange(patch.voice),
            patch.macros["TUNE"] ?: Embouchure.defaults(patch.voice, patch.model).getValue("TUNE"), Embouchure.TUNE_SEMITONES)) { patch.copy(midi = it) }
        is FerryPatch -> Field(patch.midi, Ferry.MIN_MIDI..Ferry.MAX_MIDI) { patch.copy(midi = it) }
        is FilamentPatch -> Field(patch.midi, Filament.MIN_MIDI..Filament.MAX_MIDI) { patch.copy(midi = it) }
        is FlockPatch -> Field(patch.midi, Flock.MIN_MIDI..Flock.MAX_MIDI) { patch.copy(midi = it) }
        is FlotillaPatch -> Field(patch.midi, Flotilla.MIDI_MIN..Flotilla.MIDI_MAX) { patch.copy(midi = it) }
        is KeelPatch -> Field(patch.midi, Keel.MIDI_MIN..Keel.MIDI_MAX) { patch.copy(midi = it) }
        is LacquerPatch -> Field(patch.midi, Lacquer.MIDI_MIN..Lacquer.MIDI_MAX) { patch.copy(midi = it) }
        is LatticePatch -> Field(patch.midi, Lattice.MIDI_MIN..Lattice.MIDI_MAX) { patch.copy(midi = it) }
        is LibrationPatch -> Field(patch.midi, Libration.MIN_MIDI..Libration.MAX_MIDI) { patch.copy(midi = it) }
        is MeridianPatch -> Field(patch.midi, Meridian.MIDI_MIN..Meridian.MIDI_MAX) { patch.copy(midi = it) }
        is MurmurPatch -> Field(patch.midi, Murmur.MIN_MIDI..Murmur.MAX_MIDI) { patch.copy(midi = it) }
        is NacrePatch -> Field(patch.midi, Nacre.MIN_MIDI..Nacre.MAX_MIDI) { patch.copy(midi = it) }
        is ObbligatoPatch -> Field(patch.midi, Obbligato.MIDI_MIN..Obbligato.MIDI_MAX) { patch.copy(midi = it) }
        is OhmPatch -> Field(patch.midi, Ohm.MIDI_MIN..Ohm.MIDI_MAX) { patch.copy(midi = it) }
        is OpenSkyPatch -> Field(patch.midi, OpenSkyPatch.MIDI_MIN..OpenSkyPatch.MIDI_MAX) { patch.copy(midi = it) }
        is OrphicaPatch -> Field(patch.config.midi, Orphica.MIDI_MIN..Orphica.MIDI_MAX) { patch.copy(config = patch.config.copy(midi = it)) }
        is OspreyPatch -> Field(patch.midi, Osprey.MIDI_MIN..Osprey.MIDI_MAX) { patch.copy(midi = it) }
        is OtisPatch -> Field(patch.midi, Otis.MIDI_MIN..Otis.MIDI_MAX) { patch.copy(midi = it) }
        is OvenPatch -> Field(patch.midi, Oven.MIDI_MIN..Oven.MIDI_MAX) { patch.copy(midi = it) }
        is OverlapPatch -> Field(patch.midi, Overlap.MIN_MIDI..Overlap.MAX_MIDI) { patch.copy(midi = it) }
        is PitchwheelPatch -> Field(patch.midi, Pitchwheel.MIDI_MIN..Pitchwheel.MIDI_MAX) { patch.copy(midi = it) }
        is RimePatch -> Field(patch.midi, Rime.MIN_MIDI..Rime.MAX_MIDI) { patch.copy(midi = it) }
        is ScannedChainPatch -> Field(patch.midi, ScannedChain.MIDI_MIN..ScannedChain.MIDI_MAX) { patch.copy(midi = it) }
        is ShoalPatch -> Field(patch.midi, Shoal.MIN_MIDI..Shoal.MAX_MIDI) { patch.copy(midi = it) }
        is SkitterPatch -> Field(patch.midi, Skitter.MIDI_MIN..Skitter.MIDI_MAX) { patch.copy(midi = it) }
        is StoopPatch -> Field(patch.midi, Stoop.MIDI_MIN..Stoop.MIDI_MAX) { patch.copy(midi = it) }
        is TanglePatch -> Field(patch.midi, Tangle.MIN_MIDI..Tangle.MAX_MIDI) { patch.copy(midi = it) }
        is TidalPatch -> Field(patch.midi, Tidal.MIN_MIDI..Tidal.MAX_MIDI) { patch.copy(midi = it) }
        is TrefoilPatch -> Field(patch.midi, Trefoil.MIN_MIDI..Trefoil.MAX_MIDI) { patch.copy(midi = it) }
        is TrioPatch -> Field(patch.midi, Trio.MIN_MIDI..Trio.MAX_MIDI) { patch.copy(midi = it) }
        is VentPatch -> Field(patch.midi, Vent.MIDI_MIN..Vent.MIDI_MAX) { patch.copy(midi = it) }
        is WickPatch -> Field(patch.midi, Wick.MIN_MIDI..Wick.MAX_MIDI) { patch.copy(midi = it) }

        // ---- TUNE is the root, snapped to semitones by the engine's own midiFor ----
        is AerostatPatch -> tune(patch, { Aerostat.midiFor(it) })
        is ArcoPatch -> tune(patch, { Arco.midiFor(patch.voice, it) })
        is BallastPatch -> tune(patch, { Ballast.midiFor(patch.voice, it) })
        is BorePatch -> tune(patch, { Bore.midiFor(patch.voice, it) })
        is CircuitPatch -> tune(patch, { Circuit.midiFor(it) })
        is ContourPatch -> tune(patch, { ContourEngine.midiFor(patch.voice, it) })
        is CorollaPatch -> tune(patch, { Corolla.midiFor(patch.voice, it) })
        is ForkPatch -> tune(patch, { Fork.midiFor(it) })
        is GyrePatch -> tune(patch, { Gyre.midiFor(patch.voice, it) })
        is LorentzPatch -> tune(patch, { Lorentz.midiFor(patch.voice, it) })
        is MagnetPatch -> tune(patch, { Magnet.rootMidi(patch.voice) + Magnet.semitonesFor(it) })
        is MercuryPatch -> tune(patch, { Mercury.midiFor(patch.voice, it) })
        is MurkPatch -> tune(patch, { Murk.midiFor(patch.voice, it) })
        is NimbusPatch -> tune(patch, { Nimbus.midiFor(patch.voice, it) })
        is PrismPatch -> tune(patch, { Prism.midiFor(patch.voice, it) })
        is RelayPatch -> tune(patch, { Relay.midiFor(patch.voice, it) })
        is RevelPatch -> tune(patch, { Revel.midiFor(patch.voice, it) })
        is ScourPatch -> tune(patch, { Scour.midiFor(patch.voice, it) })
        is SirenPatch -> tune(patch, { Siren.midiFor(it) })
        is SpoolPatch -> tune(patch, { Spool.midiFor(patch.voice, it) })
        is SuturePatch -> tune(patch, { Suture.midiFor(patch.voice, it) })
        is TesseraPatch -> tune(patch, { Tessera.midiFor(patch.voice, it) })
        is ThawPatch -> tune(patch, { Thaw.midiFor(patch.voice, it) })
        is TidePatch -> tune(patch, { Tide.midiFor(patch.voice, it) })
        is TremorPatch -> tune(patch, { Tremor.midiFor(patch.voice, it) })
        is UndertowPatch -> tune(patch, { Undertow.midiFor(patch.voice, it) })
        // REGISTERS saves the note TUNE lands on beside TUNE; withMacros moves it when TUNE moves.
        is RegistersPatch -> tune(patch, { Registers.midiFor(it) })
        // ORIGAMI's saved note overrides TUNE when present; a step moves both so neither goes stale.
        is OrigamiPatch -> Tune(
            patch.midi ?: Origami.midiFor(patch.voice, tuneOf(patch)),
            { Origami.midiFor(patch.voice, it) },
        ) { tune, midi -> patch.copy(macros = patch.macros + ("TUNE" to tune), midi = midi) }

        // ---- TUNE is the root, snapped to semitones by the engine's own frequencyFor ----
        is FathomPatch -> tune(patch, { nearestMidi(Fathom.frequencyFor(patch.voice, it)) })
        is GlintPatch -> tune(patch, { nearestMidi(Glint.frequencyFor(patch.voice, it)) })
        is PluckPatch -> tune(patch, { nearestMidi(Pluck.frequencyFor(patch.voice, it)) })
        is ResinPatch -> tune(patch, { nearestMidi(Resin.frequencyFor(patch.voice, it)) })
        is SnapPatch -> tune(patch, { nearestMidi(Snap.frequencyFor(it)) })
        is TonewheelPatch -> tune(patch, { nearestMidi(Tonewheel.frequencyFor(it)) })
        is VelvetPatch -> tune(patch, { nearestMidi(Velvet.frequencyFor(patch.voice, it)) })
        // Only KALIMBA snaps TUNE to semitones; the bells and blocks sweep a continuous carrier.
        is TinesPatch -> if (patch.voice == TinesVoice.KALIMBA) tune(patch, { nearestMidi(Tines.frequencyFor(patch.voice, it)) }) else null

        // ---- no root a note name would describe ----
        is ThumpPatch, is SkinPatch, is OnsetPatch, is TerraPatch, is VoxPatch -> null
        // SILK's TUNE walks scale degrees, EROSION's sweeps a frequency: neither names a note.
        is SilkPatch, is ErosionPatch -> null
    }

    /** The TUNE-is-root control for a patch whose engine snaps TUNE with [midiAt]. */
    private fun tune(patch: Patch, midiAt: (Float) -> Int): Tune =
        Tune(midiAt(tuneOf(patch)), midiAt) { tune, _ -> patch.withMacros(patch.macros + ("TUNE" to tune)) }

    /** A patch's TUNE, or the engines' shared centre when the recipe leaves it out. */
    private fun tuneOf(patch: Patch): Float = patch.macros["TUNE"]?.takeIf { it.isFinite() } ?: .5f

    /**
     * The base notes a saved-note-plus-TUNE engine accepts: its played note (base plus
     * TUNE's offset of [semitones]) must stay inside [range], which is what the patch
     * itself requires, so the top of the base range sits that offset below the top.
     */
    private fun playedRange(range: IntRange, tune: Float, semitones: Int): IntRange =
        range.first..(range.last - (tune.coerceIn(0f, 1f) * semitones).roundToInt())

    /** The MIDI note nearest [hz], equal temperament at A4 = 440 Hz. */
    internal fun nearestMidi(hz: Float): Int {
        require(hz > 0f && hz.isFinite()) { "a note needs a positive frequency, got $hz" }
        return (69 + 12 * ln(hz / 440f) / ln(2f)).roundToInt()
    }
}
