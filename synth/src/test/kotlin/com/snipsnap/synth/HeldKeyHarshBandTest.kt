package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The owner's 20 Nov 2026 notes on held keys: FILAMENT was "kind of abrasive" in the middle and
 * top while "bass notes are less abrasive", and CREASE Ink Ribbon "a bit harsh and was piercing".
 * Both came down to a standing band of overtones between 2 and 8 kHz nearly as loud as the note.
 * These tests bound that band against the fundamental on the settled sustain across C2 to C6,
 * and keep the sounds that were accepted or kept out of the change.
 */
class HeldKeyHarshBandTest {
    private val roots = listOf(36, 48, 60, 72, 84)

    /** Power between 2 and 8 kHz over the power within 3 % of the root, in dB, on the looped sustain. */
    private fun harshBandDb(note: KeyNote, midi: Int): Double {
        val samples = note.snip.samples
        val start = note.loopStartFrame.toInt()
        val loop = samples.size - start
        val size = 65_536
        val spectrum = Fft.magnitudeSpectrum(FloatArray(size) { samples[start + it % loop] }, size)
        val hz = Keys.midiHz(midi).toDouble()
        var band = 0.0
        var fundamental = 0.0
        for (bin in 1 until size / 2) {
            val f = bin * note.snip.sampleRate.toDouble() / size
            val power = spectrum[bin].toDouble() * spectrum[bin]
            if (f >= 2_000.0 && f < 8_000.0) band += power
            if (abs(f - hz) <= .03 * hz) fundamental += power
        }
        assertTrue(fundamental > 0.0, "MIDI $midi has no fundamental to measure against")
        return 10.0 * log10(band.coerceAtLeast(1e-30) / fundamental)
    }

    @Test
    fun `held FILAMENT keys keep the two to eight kilohertz band under the fundamental from C2 to C6`() {
        // Ceilings in dB by root, about 2 dB above the measured value of the brightest voice.
        val ceilings = mapOf(36 to FILAMENT_C2, 48 to FILAMENT_C3, 60 to FILAMENT_C4, 72 to FILAMENT_C5, 84 to FILAMENT_C6)
        for (voice in Filament.KEPT_VOICES) {
            val patch = FilamentPresets.forVoice(voice).first()
            for (midi in roots) {
                val db = harshBandDb(FilamentKeys.layer(patch.copy(midi = midi)), midi)
                println("HELD HARSH BAND FILAMENT $voice MIDI$midi %.1f dB".format(Locale.ROOT, db))
                assertTrue(db < ceilings.getValue(midi), "$voice MIDI$midi: 2-8 kHz is $db dB against the fundamental")
            }
        }
    }

    @Test
    fun `the FILAMENT tilt works on the upper register and leaves the bass and every patch render alone`() {
        val patch = FilamentPresets.forVoice(FilamentVoice.THREAD).first()
        for ((midi, least) in listOf(60 to 7.0, 72 to 6.0, 84 to 4.5)) {
            val before = harshBandDb(Filament.keyNote(patch.copy(midi = midi), tilt = 0.0), midi)
            val after = harshBandDb(Filament.keyNote(patch.copy(midi = midi)), midi)
            println("HELD HARSH BAND FILAMENT THREAD MIDI$midi before %.1f after %.1f dB".format(Locale.ROOT, before, after))
            assertTrue(before - after > least, "MIDI $midi: the tilt removed only ${before - after} dB of the band")
        }
        // The owner found the bass less abrasive: at C2 only the faint top overtones move, by under 4.5 dB.
        val low = patch.copy(midi = 36)
        val plain = Filament.keyNote(low, tilt = 0.0)
        val tilted = Filament.keyNote(low)
        val a = harmonicDb(plain, 36)
        val b = harmonicDb(tilted, 36)
        for (h in a.indices) assertTrue(abs(a[h] - b[h]) < 4.5, "C2 harmonic ${h + 1} moved ${b[h] - a[h]} dB")
        assertTrue(abs(a[1] - b[1]) < .5 && abs(a[2] - b[2]) < .5, "C2 lost its second or third harmonic")
        // A held patch render never tilts: it is still exactly the untilted key's sustain.
        val held = low.copy(macros = low.macros + ("HOLD" to 1f)).render().samples
        assertContentEquals(held, plain.snip.samples.copyOfRange(plain.loopStartFrame.toInt(), plain.snip.samples.size))
    }

    /** The first twelve harmonics against the fundamental, in dB. */
    private fun harmonicDb(note: KeyNote, midi: Int): DoubleArray {
        val samples = note.snip.samples
        val start = note.loopStartFrame.toInt()
        val loop = samples.size - start
        val size = 65_536
        val spectrum = Fft.magnitudeSpectrum(FloatArray(size) { samples[start + it % loop] }, size)
        val hz = Keys.midiHz(midi).toDouble()
        val power = DoubleArray(12)
        for (bin in 1 until size / 2) {
            val f = bin * note.snip.sampleRate.toDouble() / size
            val h = Math.round(f / hz).toInt()
            if (h in 1..12 && abs(f - h * hz) <= 6.0) power[h - 1] += spectrum[bin].toDouble() * spectrum[bin]
        }
        return DoubleArray(12) { 10.0 * log10(power[it].coerceAtLeast(1e-30) / power[0]) }
    }

    @Test
    fun `held CREASE Ink Ribbon keeps the two to eight kilohertz band under the fundamental from C2 to C6`() {
        val ink = CreasePresets.patches().single { it.name == "Ink Ribbon" }
        val ceilings = mapOf(36 to CREASE_C2, 48 to CREASE_C3, 60 to CREASE_C4, 72 to CREASE_C5, 84 to CREASE_C6)
        for (midi in roots) {
            val after = harshBandDb(CreaseKeys.render(ink, midi), midi)
            println("HELD HARSH BAND CREASE Ink Ribbon MIDI$midi %.1f dB".format(Locale.ROOT, after))
            assertTrue(after < ceilings.getValue(midi), "Ink Ribbon MIDI$midi: 2-8 kHz is $after dB against the fundamental")
        }
        for (midi in listOf(60, 72)) {
            val before = harshBandDb(Crease.keyProbe(ink, midi, 4, true, { false }, 1.0).note, midi)
            val after = harshBandDb(CreaseKeys.render(ink, midi), midi)
            println("HELD HARSH BAND CREASE Ink Ribbon MIDI$midi before %.1f after %.1f dB".format(Locale.ROOT, before, after))
            assertTrue(before - after > CREASE_LEAST, "MIDI $midi: the shallower fold removed only ${before - after} dB of the band")
        }
    }

    @Test
    fun `only held RUBBER keys fold less deeply`() {
        assertTrue(Crease.heldDepth(CreaseVoice.RUBBER) < 1.0)
        for (voice in listOf(CreaseVoice.ROUND, CreaseVoice.PINCH)) {
            assertTrue(Crease.heldDepth(voice) == 1.0, "$voice held keys were kept or accepted as they are")
        }
        // Paper Keys, kept on 20 Nov 2026: the default held note is the full-depth circuit, sample for sample.
        val paper = CreasePresets.patches().single { it.name == "Paper Keys" }
        val kept = Crease.keyProbe(paper, 60, 4, true, { false }, 1.0).note
        val now = CreaseKeys.render(paper, 60)
        assertContentEquals(kept.snip.samples, now.snip.samples)
        assertTrue(kept.loopStartFrame == now.loopStartFrame)
    }

    private companion object {
        // Measured 20 Nov 2026, brightest voice per root, with about 2 dB of room.
        // FILAMENT before the tilt: C4 -15, C5 -0.3, C6 -0.6. After: C4 -26.4, C5 -13.0, C6 -7.2.
        const val FILAMENT_C2 = -60.0; const val FILAMENT_C3 = -60.0; const val FILAMENT_C4 = -23.5
        const val FILAMENT_C5 = -11.0; const val FILAMENT_C6 = -5.5
        // Ink Ribbon before: C2 -23.0, C3 -10.9, C4 -4.1, C5 -2.5, C6 +0.7.
        // After: C2 -35.9, C3 -21.9, C4 -10.9, C5 -8.4, C6 -1.7.
        const val CREASE_C2 = -30.0; const val CREASE_C3 = -19.5; const val CREASE_C4 = -8.5
        const val CREASE_C5 = -6.0; const val CREASE_C6 = 0.0
        const val CREASE_LEAST = 4.0
    }
}
