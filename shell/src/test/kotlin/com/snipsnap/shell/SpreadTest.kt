package com.snipsnap.shell

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.DrumSynth
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.synth.OpenSky
import com.snipsnap.synth.Chamber
import com.snipsnap.synth.ChamberPatch
import com.snipsnap.synth.ChamberVoice
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticVoice
import com.snipsnap.synth.OpenSkyPatch
import com.snipsnap.synth.OpenSkyVoice
import com.snipsnap.synth.OtisPatch
import com.snipsnap.synth.OtisPresets
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Pitchwheel
import com.snipsnap.synth.PitchwheelPatch
import com.snipsnap.synth.PitchwheelVoice
import com.snipsnap.synth.PluckPatch
import com.snipsnap.synth.PluckVoice
import com.snipsnap.synth.StoopPatch
import com.snipsnap.synth.StoopVoice
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpreadTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("spread").toFile()

    private fun tone(hz: Double, seconds: Double = 0.8): Snip {
        val rate = 44100
        val out = FloatArray((seconds * rate).toInt()) { i ->
            val t = i / rate.toDouble()
            (0.6 * sin(2 * PI * hz * t) * exp(-1.5 * t)).toFloat()
        }
        return Snip(out, channels = 1, sampleRate = rate)
    }

    private fun kit(name: String) = KitBuilderModel.create(name, File(temp, name))

    @Test
    fun `Otis saved scores retain their relative authored root and complete voicing bounds`() {
        val misleading = tone(220.0)
        assertEquals(57f, assertNotNull(Spread.detect(misleading)), .2f)
        val draw = OtisPresets.all().first { it.name == "SQUEEZE DRAW" }
        val steam = OtisPresets.all().first { it.name == "STEAM CHORD" }
        for ((patch, expected) in listOf(
            draw.copy(midi = 57, macros = draw.macros + ("TUNE" to .5f)) to 57f,
            draw.copy(midi = 72, macros = draw.macros + ("TUNE" to 1f)) to 74f,
            draw.copy(midi = 48, macros = draw.macros + ("TUNE" to 0f)) to 36f,
            steam.copy(midi = 48, macros = steam.macros + ("TUNE" to 0f)) to 48f,
        )) {
            val saved = Patches.fromJsonText(patch.toJsonText()) as OtisPatch
            assertEquals(expected, Spread.detect(misleading, saved))
            assertEquals(patch.profile.events, saved.profile.events)
        }
    }

    @Test
    fun `Chamber spreads keep the authored root across voices models and surface balances`() {
        // A loud A3 contradicts the saved note. Upper bar and triangle modes,
        // or sympathetic room pitches, must not retune the four-source root.
        val misleading = tone(220.0)
        assertEquals(57f, assertNotNull(Spread.detect(misleading)), .2f)
        for (voice in ChamberVoice.entries) for (model in 1..Chamber.MODEL_VERSION) {
            for (midi in listOf(Chamber.MIDI_MIN, 60, Chamber.MIDI_MAX)) for (balance in listOf(0f, 1f)) {
                val patch = ChamberPatch("Authored quartet", voice, mapOf("BALANCE" to balance),
                    midi = midi, model = model)
                assertEquals(midi.toFloat(), Spread.detect(misleading, patch),
                    "$voice model $model BALANCE $balance must keep MIDI $midi")
            }
        }
    }

    @Test
    fun `Caustic spreads use the authored note instead of a dominant detected partial`() {
        val misleading = tone(220.0)
        assertEquals(57f, assertNotNull(Spread.detect(misleading)), .2f)
        for (voice in CausticVoice.entries) for (midi in listOf(36, 60, 84)) for (body in listOf(0f, .5f, 1f)) {
            val patch = CausticPatch("Saved root", voice,
                mapOf("SOURCE" to .7f, "FOCUS" to .85f, "BODY" to body, "LENGTH" to .1f),
                midi = midi, velocity = .35f, model = 1)
            assertEquals(midi.toFloat(), Spread.detect(misleading, patch), "$voice BODY $body must retain MIDI $midi")
        }
    }

    @Test
    fun `wheel spreads use the saved root and relative tune in every model`() {
        // A3 contradicts every requested source note. These are recipe tests,
        // so no render is needed to prove the harmonic detector is bypassed.
        val snip = tone(220.0)
        assertEquals(57f, Spread.detect(snip)!!, .2f)
        for (model in 1..Pitchwheel.MODEL_VERSION) {
            for (midi in listOf(24, 48, 96)) {
                for ((tune, offset) in listOf(0f to -12, .5f to 0, 1f to 12)) {
                    val patch = PitchwheelPatch("Wheel", PitchwheelVoice.RECOIL,
                        mapOf("TUNE" to tune), midi = midi, model = model)
                    assertEquals((midi + offset).toFloat(), Spread.detect(snip, patch),
                        "model $model, root $midi, TUNE $tune")
                }
            }
        }
        // MIDI 12 and 108 lie beyond Pitch.detect's 40–1200 Hz range. Keep
        // those effective notes; clamping them to the saved-root range is wrong.
        val low = PitchwheelPatch("Low wheel", PitchwheelVoice.CLUNK, mapOf("TUNE" to 0f), midi = 24)
        val high = low.copy(midi = 96, macros = mapOf("TUNE" to 1f))
        assertEquals(12f, Spread.detect(snip, low))
        assertEquals(108f, Spread.detect(snip, high))
        assertEquals(48f, Spread.detect(snip, low.copy(midi = 48, macros = emptyMap())),
            "an omitted TUNE stays centered on the saved root")
    }

    @Test
    fun `authored spread roots retain existing stoop and opensky behavior`() {
        val snip = tone(220.0)
        val stoop = StoopPatch("Stoop", StoopVoice.CHORD, emptyMap(), midi = 36)
        val sky = OpenSkyPatch("Sky", OpenSkyVoice.HALO, OpenSky.defaults(OpenSkyVoice.HALO), midi = 72)
        assertEquals(36f, Spread.detect(snip, stoop))
        assertEquals(72f, Spread.detect(snip, sky))
    }

    @Test
    fun `other spread recipes retain audio detection and its unknown result`() {
        val patch = PluckPatch("Pluck", PluckVoice.NYLON, emptyMap())
        val snip = tone(220.0)
        assertEquals(57f, Spread.detect(snip, patch)!!, .2f)
        val silence = Snip(FloatArray(44100), channels = 1, sampleRate = 44100)
        assertNull(Spread.detect(silence, patch))
    }

    @Test
    fun `a major spread walks the scale up the bank with each pad's own tune`() {
        val m = kit("Major")
        val plan = Spread.plan(m.kit, 60f, Spread.Options(rootMidi = 60, scale = Scale.MAJOR, padCount = 8))
        assertEquals((1..8).toList(), plan.notes.map { it.slot })
        assertEquals(listOf(60, 62, 64, 65, 67, 69, 71, 72), plan.notes.map { it.midi })
        assertEquals(listOf(0, 2, 4, 5, 7, 9, 11, 12), plan.notes.map { it.tuneCoarse })
        assertTrue(plan.notes.all { it.tuneFine == 0 })
        assertEquals(listOf("C4", "D4", "E4"), plan.notes.take(3).map { it.name })
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `a flat source is corrected on every pad, not just the first`() {
        // 30 cents flat of A3: every pad must add those cents back.
        val plan = Spread.plan(kit("Flat").kit, 56.7f, Spread.Options(rootMidi = 57, scale = Scale.MINOR_PENTATONIC, padCount = 4))
        assertEquals(listOf(0, 3, 5, 7), plan.notes.map { it.tuneCoarse })
        assertTrue(plan.notes.all { it.tuneFine == 30 }, plan.notes.toString())
    }

    @Test
    fun `the root can sit an octave away from the sample`() {
        val plan = Spread.plan(kit("Down").kit, 60f, Spread.Options(rootMidi = 48, scale = Scale.CHROMATIC, padCount = 4))
        assertEquals(listOf(-12, -11, -10, -9), plan.notes.map { it.tuneCoarse })
    }

    @Test
    fun `notes past the pad's tune range are skipped, not clamped`() {
        // A 16-pad major scale from C7 on a C1 sample climbs far past +36.
        val plan = Spread.plan(kit("Reach").kit, 24f, Spread.Options(rootMidi = 60, scale = Scale.MAJOR))
        assertTrue(plan.notes.all { it.tuneCoarse in -36..36 })
        assertTrue(plan.skipped.values.all { it == Spread.Skip.OUT_OF_REACH })
        assertEquals(16, plan.notes.size + plan.skipped.size)
        assertTrue(plan.skipped.isNotEmpty())
    }

    @Test
    fun `bank B spreads onto pads 17 up`() {
        val plan = Spread.plan(kit("BankB").kit, 60f, Spread.Options(rootMidi = 60, scale = Scale.MAJOR, padCount = 4, bank = 1))
        assertEquals(listOf(17, 18, 19, 20), plan.notes.map { it.slot })
    }

    @Test
    fun `full pads are kept by default and replaced only when asked`() {
        val m = kit("Full")
        m.assign(2, DrumSynth.kick(), DrumClass.KICK)
        val keep = Spread.plan(m.kit, 60f, Spread.Options(rootMidi = 60, scale = Scale.MAJOR, padCount = 4))
        assertEquals(listOf(1, 3, 4), keep.notes.map { it.slot })
        assertEquals(mapOf(2 to Spread.Skip.FULL), keep.skipped)
        assertEquals(64, keep.noteAt(3)?.midi, "a kept pad leaves a gap; the next pad still plays its own degree")

        val replace = Spread.plan(m.kit, 60f, Spread.Options(rootMidi = 60, scale = Scale.MAJOR, padCount = 4, replaceFull = true))
        assertEquals(listOf(1, 2, 3, 4), replace.notes.map { it.slot })
        assertEquals(listOf(2), replace.replacing)
    }

    @Test
    fun `mono picks a choke group nothing left behind is using`() {
        val m = kit("Mono")
        m.assign(5, DrumSynth.closedHat(), DrumClass.HAT_CLOSED) // outside the spread: holds group 1
        val plan = Spread.plan(m.kit, 60f, Spread.Options(rootMidi = 60, scale = Scale.MAJOR, padCount = 4, mono = true))
        assertEquals(2, plan.muteGroup)
        assertEquals(false, plan.monoUnavailable)

        val poly = Spread.plan(m.kit, 60f, Spread.Options(rootMidi = 60, scale = Scale.MAJOR, padCount = 4))
        assertEquals(0, poly.muteGroup)
    }

    @Test
    fun `a pad being replaced frees its choke group for the spread`() {
        val m = kit("Freed")
        m.assign(1, DrumSynth.closedHat(), DrumClass.HAT_CLOSED)
        val plan = Spread.plan(m.kit, 60f, Spread.Options(rootMidi = 60, scale = Scale.MAJOR, padCount = 4, mono = true, replaceFull = true))
        assertEquals(1, plan.muteGroup)
    }

    @Test
    fun `apply lands named, tuned, coloured pads that survive a reopen`() {
        val m = kit("Landed")
        val snip = tone(Scales.midiToHz(57).toDouble())
        val source = Spread.detect(snip)
        assertNotNull(source)
        assertEquals(57f, source, 0.2f)

        val plan = Spread.plan(m.kit, source, Spread.Options(rootMidi = 57, scale = Scale.MINOR, padCount = 8, mono = true))
        val color = AutoPlace.colorFor(DrumClass.TONAL)
        Spread.apply(m, snip, plan, Spread.Sound("Harp Pluck", DrumClass.TONAL, color, source = mapOf(Spread.FROM_KEY to "SYNTH")))
        m.save()

        val back = KitBuilderModel.open(m.kitDir)
        assertEquals(8, back.kit.pads.size)
        assertEquals("A3 Harp Pluck", back.pad(1)?.displayName)
        assertEquals("A4 Harp Pluck", back.pad(8)?.displayName)
        assertEquals(Spread.rootTint(color), back.pad(1)?.colorHex, "the root is the lighter shade")
        assertEquals(Spread.rootTint(color), back.pad(8)?.colorHex, "and so is its octave")
        assertEquals(color, back.pad(2)?.colorHex)
        assertEquals(12, back.pad(8)?.tuneCoarse)
        assertTrue(back.kit.pads.all { it.muteGroup == plan.muteGroup && it.muteGroup > 0 })
        assertEquals("C4", back.pad(3)?.source?.get(Spread.NOTE_KEY))
        assertEquals("SYNTH", back.pad(3)?.source?.get(Spread.FROM_KEY))
        assertEquals(8, back.kit.pads.map { it.sampleFile }.toSet().size, "each pad owns its sample")
    }

    @Test
    fun `apply keeps the tune it planned even when the kit has a key`() {
        // assign's retune-on-landing would otherwise snap every pad to the
        // kit key's nearest note, flattening the scale.
        val m = kit("Keyed")
        m.setKey(com.snipsnap.audio.KeySpec(0, Scale.MAJOR))
        val snip = tone(Scales.midiToHz(60).toDouble())
        val plan = Spread.plan(m.kit, Spread.detect(snip), Spread.Options(rootMidi = 60, scale = Scale.CHROMATIC, padCount = 4))
        Spread.apply(m, snip, plan, Spread.Sound("Tone", DrumClass.TONAL, AutoPlace.colorFor(DrumClass.TONAL)))
        assertEquals(listOf(0, 1, 2, 3), (1..4).map { m.pad(it)!!.tuneCoarse })
    }

    @Test
    fun `a spread pad carries the look and feel of the pad it came from`() {
        val m = kit("Like")
        val snip = tone(220.0)
        val orig = m.assign(1, snip, DrumClass.TONAL, "Bell")
        val like = m.update(1) { it.copy(level = 0.5f, pan = 0.2f, decay = 0.3f, oneShot = false) }
        val plan = Spread.plan(m.kit, Spread.detect(snip), Spread.Options(rootMidi = 57, scale = Scale.MAJOR, padCount = 4, bank = 1))
        Spread.apply(m, snip, plan, Spread.Sound(Spread.baseName(orig), DrumClass.TONAL, orig.colorHex!!, like = like))
        val p = m.pad(18)!!
        assertEquals(0.5f, p.level)
        assertEquals(0.2f, p.pan)
        assertEquals(0.3f, p.decay)
        assertEquals(false, p.oneShot)
        assertEquals("B3 Bell", p.displayName)
        assertEquals("Bell", Spread.baseName(p), "a second spread doesn't stack notes in the name")
    }

    @Test
    fun `no pitch falls back to the assumed note and the pad's own tune sets the root`() {
        assertNull(Spread.detect(DrumSynth.closedHat()))
        assertEquals(Spread.ASSUMED_MIDI, Spread.defaultRoot(null))
        assertEquals(60, Spread.defaultRoot(57.1f, tuneCoarse = 3))
        assertEquals(Spread.ROOT_MAX, Spread.defaultRoot(120f))
    }

    @Test
    fun `options refuse what the screen can't draw`() {
        assertFailsWith<IllegalArgumentException> { Spread.Options(rootMidi = 60, scale = Scale.MAJOR, padCount = 3) }
        assertFailsWith<IllegalArgumentException> { Spread.Options(rootMidi = 60, scale = Scale.MAJOR, bank = 2) }
        assertFailsWith<IllegalArgumentException> { Spread.Options(rootMidi = 10, scale = Scale.MAJOR) }
    }

    @Test
    fun `the root tint is halfway to white`() {
        assertEquals("#ffffff", Spread.rootTint("#ffffff"))
        assertEquals("#7f7f7f", Spread.rootTint("#000000"))
        assertEquals("#d7b5f7", Spread.rootTint("#b06cf0"))
    }

    @Test
    fun `the landing toast says only what happened`() {
        assertEquals(
            "C4 MAJOR ACROSS 16 PADS FROM A01.",
            Copy.spreadLanded("C4", "MAJOR", 16, "A01", 0, 0, 0, 0, false),
        )
        assertEquals(
            "A3 MIN PENT ACROSS 1 PAD FROM B01. 1 FULL PAD KEPT. 2 NOTES OUT OF TUNE RANGE. 1 ORIGINAL SLEEPS IN THE BIN. CHOKE GROUP 3: ONE NOTE AT A TIME.",
            Copy.spreadLanded("A3", "MIN PENT", 1, "B01", 1, 2, 1, 3, false),
        )
        assertTrue(Copy.spreadLanded("C4", "MAJOR", 4, "A01", 0, 0, 0, 0, true).endsWith("NO FREE CHOKE GROUP, SO NOTES OVERLAP."))
    }

    @Test
    fun `the toast reads its facts off the plan`() {
        val m = kit("Toast")
        m.assign(1, DrumSynth.kick(), DrumClass.KICK)
        val plan = Spread.plan(m.kit, 60f, Spread.Options(rootMidi = 60, scale = Scale.MAJOR, padCount = 4, bank = 0, mono = true))
        assertEquals("C4 MAJOR ACROSS 3 PADS FROM A02. 1 FULL PAD KEPT. CHOKE GROUP 1: ONE NOTE AT A TIME.", Spread.toast(plan))

        m.assign(2, DrumSynth.kick(), DrumClass.KICK)
        m.assign(3, DrumSynth.kick(), DrumClass.KICK)
        m.assign(4, DrumSynth.kick(), DrumClass.KICK)
        assertEquals(Copy.SPREAD_NOTHING, Spread.toast(Spread.plan(m.kit, 60f, Spread.Options(rootMidi = 60, scale = Scale.MAJOR, padCount = 4))))
    }
}
