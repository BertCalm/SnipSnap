package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cross-engine [Presets] dispatcher, now that all seven registered
 * engines sit behind it (THUMP first, PR #189; the other six join here).
 * Each `<Engine>PresetsTest` covers its own engine's content — identity/
 * sanity/round-trip/names/blocklist; this file covers only the
 * dispatcher's own routing.
 */
class PresetsTest {

    @Test
    fun `forVoice forwards every registered engine by name`() {
        assertEquals(ThumpPresets.forVoice(ThumpVoice.KICK), Presets.forVoice("THUMP", "KICK"))
        assertEquals(TinesPresets.forVoice(TinesVoice.BELL), Presets.forVoice("TINES", "BELL"))
        assertEquals(PluckPresets.forVoice(PluckVoice.HARP), Presets.forVoice("PLUCK", "HARP"))
        assertEquals(VelvetPresets.forVoice(VelvetVoice.BASS), Presets.forVoice("VELVET", "BASS"))
        assertEquals(FathomPresets.forVoice(FathomVoice.DEEP), Presets.forVoice("FATHOM", "DEEP"))
        assertEquals(ResinPresets.forVoice(ResinVoice.LEAD), Presets.forVoice("RESIN", "LEAD"))
        assertEquals(TonewheelPresets.forVoice(TonewheelVoice.STAB), Presets.forVoice("TONEWHEEL", "STAB"))
        assertEquals(VoxPresets.forVoice(VoxVoice.GHOST), Presets.forVoice("VOX", "GHOST"))
        assertEquals(SkinPresets.forVoice(SkinVoice.KICK), Presets.forVoice("SKIN", "KICK"))
        assertEquals(TidePresets.forVoice(TideVoice.BONGO), Presets.forVoice("TIDE", "BONGO"))
        assertEquals(SirenPresets.forVoice(SirenVoice.WAIL), Presets.forVoice("SIREN", "WAIL"))
        assertEquals(ForkPresets.forVoice(ForkVoice.TINE), Presets.forVoice("FORK", "TINE"))
        assertEquals(BorePresets.forVoice(BoreVoice.FLUTE), Presets.forVoice("BORE", "FLUTE"))
        assertEquals(BorePresets.forVoice(BoreVoice.SAX), Presets.forVoice("BORE", "SAX"))
        assertEquals(ArcoPresets.forVoice(ArcoVoice.CELLO), Presets.forVoice("ARCO", "CELLO"))
        assertEquals(ArcoPresets.forVoice(ArcoVoice.ERHU), Presets.forVoice("ARCO", "ERHU"))
        for (voice in MercuryVoice.entries) assertEquals(MercuryPresets.forVoice(voice), Presets.forVoice("MERCURY", voice.name))
        for (voice in FlotillaVoice.entries) assertEquals(FlotillaPresets.forVoice(voice), Presets.forVoice("FLOTILLA", voice.name))
        for (voice in CisternVoice.entries) assertEquals(CisternPresets.forVoice(voice), Presets.forVoice("CISTERN", voice.name))
        for (voice in TremorVoice.entries) assertEquals(TremorPresets.forVoice(voice), Presets.forVoice("TREMOR", voice.name))
        for (voice in CircuitVoice.entries) assertEquals(CircuitPresets.forVoice(voice), Presets.forVoice("CIRCUIT", voice.name))
        for (voice in MurkVoice.entries) assertEquals(MurkPresets.forVoice(voice), Presets.forVoice("MURK", voice.name))
        for (voice in ThawVoice.entries) assertEquals(ThawPresets.forVoice(voice), Presets.forVoice("THAW", voice.name))
        for (voice in UndertowVoice.entries) assertEquals(UndertowPresets.forVoice(voice), Presets.forVoice("UNDERTOW", voice.name))
        for (voice in SutureVoice.entries) assertEquals(SuturePresets.forVoice(voice), Presets.forVoice("SUTURE", voice.name))
        for (voice in CorollaVoice.entries) assertEquals(CorollaPresets.forVoice(voice), Presets.forVoice("COROLLA", voice.name))
    }

    @Test
    fun `forVoice returns empty for an unknown voice on a real engine`() {
        assertTrue(Presets.forVoice("THUMP", "NOT A VOICE").isEmpty())
    }

    @Test
    fun `byName finds a preset and returns null for a miss`() {
        val name = TonewheelPresets.forVoice(TonewheelVoice.SOUL).first().name
        assertEquals(name, Presets.byName("TONEWHEEL", "SOUL", name)?.name)
        assertEquals(null, Presets.byName("TONEWHEEL", "SOUL", "NOT A PRESET"))
    }

    @Test
    fun `all sums every registered engine's roster with nothing lost or duplicated`() {
        val expected = ThumpPresets.all() + TinesPresets.all() + PluckPresets.all() +
            VelvetPresets.all() + FathomPresets.all() + TonewheelPresets.all() + VoxPresets.all() +
            SkinPresets.all() + ResinPresets.all() + TidePresets.all() + SirenPresets.all() + ForkPresets.all() +
            BorePresets.all() + ArcoPresets.all() + MercuryPresets.all() +
            FlotillaPresets.all() + AerostatPresets.all() + TremorPresets.all() +
            CisternPresets.all() + CircuitPresets.all() + ThawPresets.all() +
            MurkPresets.all() + CorollaPresets.all() + UndertowPresets.all() + SuturePresets.all()
        assertEquals(expected.size, Presets.all().size)
        assertEquals(expected.toSet(), Presets.all().toSet())
    }

    @Test
    fun `an unregistered engine returns empty rather than throwing`() {
        // GRAINS has no voice enum and no Patch type (SynthScreen.kt's own
        // KDoc calls it "a different shape entirely") — every registered
        // engine has a roster as of this PR, so this is the one name
        // guaranteed to stay unregistered rather than a real engine that
        // will grow its own preset pass later and quietly stop testing
        // this path.
        assertTrue(Presets.forVoice("GRAINS", "ANYTHING").isEmpty())
    }
}
