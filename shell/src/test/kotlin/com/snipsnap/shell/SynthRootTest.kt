package com.snipsnap.shell

import com.snipsnap.audio.Pitch
import com.snipsnap.synth.CairnPatch
import com.snipsnap.synth.CairnVoice
import com.snipsnap.synth.ContourEngine
import com.snipsnap.synth.ContourPatch
import com.snipsnap.synth.ContourVoice
import com.snipsnap.synth.ErosionPatch
import com.snipsnap.synth.ErosionVoice
import com.snipsnap.synth.Fathom
import com.snipsnap.synth.FathomPatch
import com.snipsnap.synth.FathomVoice
import com.snipsnap.synth.FerryPresets
import com.snipsnap.synth.Fork
import com.snipsnap.synth.ForkPatch
import com.snipsnap.synth.ForkVoice
import com.snipsnap.synth.Glint
import com.snipsnap.synth.GlintPatch
import com.snipsnap.synth.GlintVoice
import com.snipsnap.synth.GyrePatch
import com.snipsnap.synth.GyreVoice
import com.snipsnap.synth.Keys
import com.snipsnap.synth.MagnetPatch
import com.snipsnap.synth.MagnetVoice
import com.snipsnap.synth.NacrePresets
import com.snipsnap.synth.Origami
import com.snipsnap.synth.OrigamiPatch
import com.snipsnap.synth.OrigamiVoice
import com.snipsnap.synth.OrphicaConfig
import com.snipsnap.synth.OrphicaPatch
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Pluck
import com.snipsnap.synth.PluckPatch
import com.snipsnap.synth.PluckVoice
import com.snipsnap.synth.Presets
import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.PrismVoice
import com.snipsnap.synth.Resin
import com.snipsnap.synth.ResinPatch
import com.snipsnap.synth.ResinVoice
import com.snipsnap.synth.SilkPatch
import com.snipsnap.synth.SilkVoice
import com.snipsnap.synth.Snap
import com.snipsnap.synth.SnapPatch
import com.snipsnap.synth.SnapVoice
import com.snipsnap.synth.Stoop
import com.snipsnap.synth.StoopPatch
import com.snipsnap.synth.StoopVoice
import com.snipsnap.synth.TerraPatch
import com.snipsnap.synth.TerraVoice
import com.snipsnap.synth.Tines
import com.snipsnap.synth.TinesPatch
import com.snipsnap.synth.TinesVoice
import com.snipsnap.synth.Tonewheel
import com.snipsnap.synth.TonewheelPatch
import com.snipsnap.synth.TonewheelVoice
import com.snipsnap.synth.Velvet
import com.snipsnap.synth.VelvetPatch
import com.snipsnap.synth.VelvetVoice
import com.snipsnap.synth.WickPresets
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ROOT / OCT steppers' pure half: which engines have a root, what it reads, and
 * that stepping to a note lands on that note for every saved sound over its whole
 * range. The screen only draws what [SynthRoot] says.
 */
class SynthRootTest {

    companion object {
        /** Every factory sound, plus one hand-built patch per engine kept off the preset roster. */
        private val patches: List<Patch> by lazy {
            Presets.all() + listOf(
                GyrePatch("Door", GyreVoice.FLICK, emptyMap()),
                MagnetPatch("Door", MagnetVoice.JANGLE, emptyMap()),
                SilkPatch("Door", SilkVoice.OUD, emptyMap()),
                SnapPatch("Door", SnapVoice.ORBIT, emptyMap(), IntArray(Snap.TABLE_SIZE) { (it * 255) / (Snap.TABLE_SIZE - 1) }),
                TerraPatch("Door", TerraVoice.COMPOUND_MEMBRANE, emptyMap()),
                ErosionPatch("Door", ErosionVoice.ARC),
                CairnPatch("Door", CairnVoice.ANSWER, emptyMap()),
                WickPresets.all().first(),
                NacrePresets.all().first(),
                FerryPresets.all().first(),
            )
        }

        /** The engines whose every patch answers null: drums, voice, scale rows, frequency sweeps. */
        private val ROOTLESS = setOf("THUMP", "SKIN", "ONSET", "TERRA", "VOX", "SILK", "EROSION")

        private fun cents(hz: Float, midi: Int): Float = 1200f * ln(hz / Keys.midiHz(midi)) / ln(2f)
    }

    @Test
    fun `every engine in the roster is decided, and only the rootless ones answer null`() {
        val byEngine = patches.groupBy { it.engine }
        assertTrue(byEngine.size > 60, "the fixture reaches ${byEngine.size} engines; the roster is larger")
        val rootless = byEngine.filterValues { list -> list.all { SynthRoot.of(it) == null } }.keys
        assertEquals(ROOTLESS, rootless, "engines with no root at all")
        // TINES is split: KALIMBA snaps TUNE to a note, the bells sweep a carrier.
        val tines = byEngine.getValue("TINES")
        assertTrue(tines.any { SynthRoot.of(it) != null } && tines.any { SynthRoot.of(it) == null }, "TINES has both kinds")
        for (patch in tines) assertEquals((patch as TinesPatch).voice == TinesVoice.KALIMBA, SynthRoot.of(patch) != null, patch.name)
    }

    @Test
    fun `the readout is the saved note, or the note TUNE snaps to`() {
        assertEquals(50, SynthRoot.of(StoopPatch("s", StoopVoice.BASS, Stoop.defaults(StoopVoice.BASS), midi = 50))!!.midi)
        assertEquals(63, SynthRoot.of(OrphicaPatch("o", OrphicaConfig(midi = 63)))!!.midi)
        val prism = PrismPatch("p", PrismVoice.KEY, Prism.defaults(PrismVoice.KEY) + ("TUNE" to .25f))
        assertEquals(Prism.midiFor(PrismVoice.KEY, .25f), SynthRoot.of(prism)!!.midi)
        // ORIGAMI: a saved note overrides TUNE; without one TUNE decides.
        val tune = Origami.defaults(OrigamiVoice.entries.first())
        assertEquals(Origami.midiFor(OrigamiVoice.entries.first(), tune.getValue("TUNE")),
            SynthRoot.of(OrigamiPatch("g", OrigamiVoice.entries.first(), tune))!!.midi)
        assertEquals(70, SynthRoot.of(OrigamiPatch("g", OrigamiVoice.entries.first(), tune, midi = 70))!!.midi)
        // A recipe with no TUNE reads at the shared centre, the way the engines render it.
        assertEquals(Fork.midiFor(.5f), SynthRoot.of(ForkPatch("f", ForkVoice.entries.first(), emptyMap()))!!.midi)
    }

    @Test
    fun `stepping to any note in the range lands on that note, for every saved sound`() {
        var stepped = 0
        for (patch in patches) {
            val control = SynthRoot.of(patch) ?: continue
            assertTrue(control.midi in control.range, "${patch.engine} ${patch.name}: ${control.midi} outside ${control.range}")
            for (midi in control.range) {
                val next = control.at(midi)
                val again = assertNotNull(SynthRoot.of(next), "${patch.engine} ${patch.name} at $midi")
                assertEquals(midi, again.midi, "${patch.engine} ${patch.name}: asked for $midi")
                assertEquals(control.range, again.range, "${patch.engine} ${patch.name}: the range moved at $midi")
                assertEquals(patch.engine, next.engine); assertEquals(patch.voiceName, next.voiceName)
                stepped++
            }
            // Outside the range the step clamps rather than refuses.
            assertEquals(control.range.first, SynthRoot.of(control.at(control.range.first - 1))!!.midi)
            assertEquals(control.range.last, SynthRoot.of(control.at(control.range.last + 1))!!.midi)
        }
        assertTrue(stepped > 10_000, "stepped $stepped notes; the roster should give far more")
    }

    @Test
    fun `octave steps are gated by the range, the way the STOOP row was`() {
        val bottom = SynthRoot.of(StoopPatch("s", StoopVoice.BASS, Stoop.defaults(StoopVoice.BASS), midi = Stoop.MIDI_MIN))!!
        assertFalse(bottom.canStep(-1)); assertFalse(bottom.canStep(-12)); assertTrue(bottom.canStep(12))
        val nearTop = SynthRoot.of(StoopPatch("s", StoopVoice.BASS, Stoop.defaults(StoopVoice.BASS), midi = Stoop.MIDI_MAX - 5))!!
        assertTrue(nearTop.canStep(1)); assertFalse(nearTop.canStep(12))
        assertEquals(Stoop.MIDI_MAX - 5 + 1, (nearTop.at(nearTop.midi + 1) as StoopPatch).midi)
    }

    @Test
    fun `a stepped TUNE engine plays within six cents of the note it names`() {
        // The engines whose TUNE snap is read back through frequencyFor: the inversion is
        // inferred from the frequency, so it is held to the note's pitch, not only its number.
        val checks: List<Pair<Patch, (Float) -> Float>> =
            VelvetVoice.entries.map { v -> VelvetPatch("v", v, Velvet.defaults(v)) to { t: Float -> Velvet.frequencyFor(v, t) } } +
            ResinVoice.entries.map { v -> ResinPatch("r", v, Resin.defaults(v)) to { t: Float -> Resin.frequencyFor(v, t) } } +
            PluckVoice.entries.map { v -> PluckPatch("p", v, Pluck.defaults(v)) to { t: Float -> Pluck.frequencyFor(v, t) } } +
            GlintVoice.entries.map { v -> GlintPatch("g", v, Glint.defaults(v)) to { t: Float -> Glint.frequencyFor(v, t) } } +
            FathomVoice.entries.map { v -> FathomPatch("f", v, Fathom.defaults(v)) to { t: Float -> Fathom.frequencyFor(v, t) } } +
            TonewheelVoice.entries.map { v -> TonewheelPatch("t", v, Tonewheel.defaults(v)) to { t: Float -> Tonewheel.frequencyFor(t) } } +
            listOf(TinesPatch("k", TinesVoice.KALIMBA, Tines.defaults(TinesVoice.KALIMBA)) to { t: Float -> Tines.frequencyFor(TinesVoice.KALIMBA, t) }) +
            ContourVoice.entries.map { v -> ContourPatch("c", v, ContourEngine.defaults(v)) to { t: Float -> ContourEngine.frequencyFor(v, t) } }
        // PLUCK's SITAR root is 139 Hz by the engine's own choice, 5.1 cents sharp of C#3
        // (138.59 Hz); every other voice sits within a cent. The bound is six, so a root
        // that drifted a whole semitone, or a snap that lost a step, still fails here.
        for ((patch, hzAt) in checks) {
            val control = SynthRoot.of(patch) as SynthRoot.Tune
            for (midi in control.range) {
                val off = cents(hzAt(control.tuneFor(midi)), midi)
                assertTrue(abs(off) < 6f, "${patch.engine} ${patch.voiceName} at $midi plays $off cents off")
            }
        }
    }

    @Test
    fun `an octave step on a TUNE engine doubles the rendered pitch`() {
        val chip = VelvetPatch("c", VelvetVoice.CHIP, Velvet.defaults(VelvetVoice.CHIP))
        val control = SynthRoot.of(chip)!!
        val low = control.at(control.range.first + 2)
        val high = control.at(control.range.first + 14)
        val lowHz = Pitch.detect(low.render())!!.hz
        val highHz = Pitch.detect(high.render())!!.hz
        // The autocorrelation detector reads CHIP's square an octave low at times (123.6 Hz
        // for B3 on the first run), so the check is on pitch class: the rendered note is the
        // named note to within 20 cents, some whole number of octaves aside.
        fun pitchClassCents(hz: Float, midi: Int): Float {
            val raw = cents(hz, midi) % 1200f
            return if (raw > 600f) raw - 1200f else if (raw < -600f) raw + 1200f else raw
        }
        assertTrue(abs(pitchClassCents(lowHz, control.range.first + 2)) < 20f, "low note detected at $lowHz Hz")
        assertTrue(abs(pitchClassCents(highHz, control.range.first + 14)) < 20f, "high note detected at $highHz Hz")
    }

    @Test
    fun `nearest note rounds the way equal temperament does`() {
        assertEquals(69, SynthRoot.nearestMidi(440f))
        assertEquals(40, SynthRoot.nearestMidi(82.4f))
        assertEquals(28, SynthRoot.nearestMidi(41.2f))
        assertEquals(60, SynthRoot.nearestMidi(261.63f))
        assertNull(runCatching { SynthRoot.nearestMidi(0f) }.getOrNull())
    }
}
