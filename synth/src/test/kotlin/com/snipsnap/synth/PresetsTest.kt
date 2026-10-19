package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cross-engine [Presets] dispatcher for every registered preset roster.
 * Each `<Engine>PresetsTest` covers its own engine's content — identity/
 * sanity/round-trip/names/blocklist; this file covers only the
 * dispatcher's own routing.
 */
class PresetsTest {

    @Test
    fun `forVoice forwards every registered engine by name`() {
        for (voice in ShoalVoice.entries) assertEquals(ShoalPresets.forVoice(voice), Presets.forVoice("SHOAL", voice.name))
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
        for (voice in GlintVoice.entries) assertEquals(GlintPresets.forVoice(voice), Presets.forVoice("GLINT", voice.name))
        assertEquals(SirenPresets.forVoice(SirenVoice.WAIL), Presets.forVoice("SIREN", "WAIL"))
        assertEquals(ForkPresets.forVoice(ForkVoice.TINE), Presets.forVoice("FORK", "TINE"))
        assertEquals(BorePresets.forVoice(BoreVoice.FLUTE), Presets.forVoice("BORE", "FLUTE"))
        assertEquals(BorePresets.forVoice(BoreVoice.SAX), Presets.forVoice("BORE", "SAX"))
        assertEquals(ArcoPresets.forVoice(ArcoVoice.CELLO), Presets.forVoice("ARCO", "CELLO"))
        assertEquals(ArcoPresets.forVoice(ArcoVoice.ERHU), Presets.forVoice("ARCO", "ERHU"))
        for (voice in MercuryVoice.entries) assertEquals(MercuryPresets.forVoice(voice), Presets.forVoice("MERCURY", voice.name))
        for (voice in AerostatVoice.entries) assertEquals(AerostatPresets.forVoice(voice), Presets.forVoice("AEROSTAT", voice.name))
        for (voice in FlotillaVoice.entries) assertEquals(FlotillaPresets.forVoice(voice), Presets.forVoice("FLOTILLA", voice.name))
        for (voice in CisternVoice.entries) assertEquals(CisternPresets.forVoice(voice), Presets.forVoice("CISTERN", voice.name))
        for (voice in TremorVoice.entries) assertEquals(TremorPresets.forVoice(voice), Presets.forVoice("TREMOR", voice.name))
        for (voice in CircuitVoice.entries) assertEquals(CircuitPresets.forVoice(voice), Presets.forVoice("CIRCUIT", voice.name))
        for (voice in MurkVoice.entries) assertEquals(MurkPresets.forVoice(voice), Presets.forVoice("MURK", voice.name))
        for (voice in NimbusVoice.entries) assertEquals(NimbusPresets.forVoice(voice), Presets.forVoice("NIMBUS", voice.name))
        for (voice in ThawVoice.entries) assertEquals(ThawPresets.forVoice(voice), Presets.forVoice("THAW", voice.name))
        for (voice in UndertowVoice.entries) assertEquals(UndertowPresets.forVoice(voice), Presets.forVoice("UNDERTOW", voice.name))
        for (voice in SutureVoice.entries) assertEquals(SuturePresets.forVoice(voice), Presets.forVoice("SUTURE", voice.name))
        for (voice in CorollaVoice.entries) assertEquals(CorollaPresets.forVoice(voice), Presets.forVoice("COROLLA", voice.name))
        for (voice in TesseraVoice.entries) assertEquals(TesseraPresets.forVoice(voice), Presets.forVoice("TESSERA", voice.name))
        for (voice in PitchwheelVoice.entries) assertEquals(PitchwheelPresets.forVoice(voice), Presets.forVoice("PITCHWHEEL", voice.name))
        for (voice in LacquerVoice.entries) assertEquals(LacquerPresets.forVoice(voice), Presets.forVoice("LACQUER", voice.name))
        for (voice in TangleVoice.entries) assertEquals(TanglePresets.forVoice(voice), Presets.forVoice("TANGLE", voice.name))
        for (voice in EmbouchureVoice.entries) assertEquals(EmbouchurePresets.forVoice(voice), Presets.forVoice("EMBOUCHURE", voice.name))
        for (voice in BellowsVoice.entries) assertEquals(BellowsPresets.forVoice(voice), Presets.forVoice("BELLOWS", voice.name))
        for (voice in MurmurVoice.entries) assertEquals(MurmurPresets.forVoice(voice), Presets.forVoice("MURMUR", voice.name))
        for (voice in OspreyVoice.entries) assertEquals(OspreyPresets.forVoice(voice), Presets.forVoice("OSPREY", voice.name))
        for (voice in RevelVoice.entries) assertEquals(RevelPresets.forVoice(voice), Presets.forVoice("REVEL", voice.name))
        for (voice in SpoolVoice.entries) assertEquals(SpoolPresets.forVoice(voice), Presets.forVoice("SPOOL", voice.name))
        for (voice in PrismVoice.entries) assertEquals(PrismPresets.forVoice(voice), Presets.forVoice("PRISM", voice.name))
        for (voice in AlloyVoice.entries) assertEquals(AlloyPresets.forVoice(voice), Presets.forVoice("ALLOY", voice.name))
        for (voice in ContourVoice.entries) assertEquals(ContourPresets.forVoice(voice), Presets.forVoice("CONTOUR", voice.name))
        for (voice in StoopVoice.entries) assertEquals(StoopPresets.forVoice(voice), Presets.forVoice("STOOP", voice.name))
        for (voice in SkitterVoice.entries) assertEquals(SkitterPresets.forVoice(voice), Presets.forVoice("SKITTER", voice.name))
        for (voice in RelayVoice.entries) assertEquals(RelayPresets.forVoice(voice), Presets.forVoice("RELAY", voice.name))
        for (voice in RegistersVoice.entries) assertEquals(RegistersPresets.forVoice(voice), Presets.forVoice("REGISTERS", voice.name))
        for (voice in OpenSkyVoice.entries) assertEquals(OpenSkyPresets.forVoice(voice), Presets.forVoice("OPENSKY", voice.name))
        for (voice in VentVoice.entries) assertEquals(VentKitPresets.forVoice(voice), Presets.forVoice("VENT", voice.name))
    }

    /**
     * The list above is written by hand, so it can only cover the engines someone
     * remembered (it missed AEROSTAT and NIMBUS until this test existed). This one
     * reads both sides from the code: for every voice of every [Patch] type,
     * [Presets.forVoice] must serve exactly the presets [Presets.all] files under
     * it (none, for a rosterless engine or an empty voice); and every engine with a
     * [Patch] type must either have a roster in [Presets.all] or be one of
     * [PatchTypes.ROSTERLESS] or [PatchTypes.PROTOTYPES].
     */
    @Test
    fun `forVoice and all agree on every engine and voice`() {
        val all = Presets.all()
        for (type in PatchTypes.all) {
            val engine = PatchTypes.engineOf(type)
            for (voice in PatchTypes.voicesOf(type)) {
                val filed = all.filter { it.engine == engine && it.voiceName == voice }
                assertEquals(filed, Presets.forVoice(engine, voice), "$engine/$voice: forVoice disagrees with the presets all() files under it")
                PatchTypes.PROTOTYPE_VOICES[engine]?.let { provisional ->
                    assertEquals(voice in provisional, filed.isEmpty(),
                        "$engine/$voice: only its explicitly named prototype voices may be absent")
                }
            }
        }

        val rostered = all.map { it.engine }.toSet()
        val expected = PatchTypes.engines - PatchTypes.ROSTERLESS - PatchTypes.PROTOTYPES
        assertEquals(
            expected, rostered,
            "an engine with a Patch type must have a roster in Presets.all() or be listed as rosterless or a prototype " +
                "(missing ${expected - rostered}, unexpected ${rostered - expected})",
        )
        fun rosterClass(engine: String) = "com.snipsnap.synth." + engine.lowercase().replaceFirstChar { it.uppercase() } + "Presets"
        for (engine in PatchTypes.ROSTERLESS) {
            val roster = rosterClass(engine)
            assertTrue(runCatching { Class.forName(roster) }.isFailure, "$engine is listed as rosterless but $roster exists")
        }
        // A prototype is held out of Presets on purpose, not for want of recipes: it keeps
        // its own provisional roster, and nothing of it is served through the factory one.
        assertTrue(PatchTypes.PROTOTYPES.intersect(PatchTypes.ROSTERLESS).isEmpty(), "an engine is either rosterless or a prototype")
        for (engine in PatchTypes.PROTOTYPES) {
            val roster = rosterClass(engine)
            assertTrue(runCatching { Class.forName(roster) }.isSuccess, "$engine is listed as a prototype but has no $roster to audition")
        }
        for ((engine, voices) in PatchTypes.PROTOTYPE_VOICES) {
            assertTrue(engine in rostered && engine !in PatchTypes.PROTOTYPES && engine !in PatchTypes.ROSTERLESS)
            val type = PatchTypes.all.single { PatchTypes.engineOf(it) == engine }
            assertTrue(voices.isNotEmpty() && PatchTypes.voicesOf(type).containsAll(voices))
        }
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
    fun `byName finds a GLINT preset by voice and name`() {
        assertEquals("STILL AH", Presets.byName("GLINT", "VOWEL", "STILL AH")?.name)
        assertEquals(GlintPresets.forVoice(GlintVoice.SWEEP).first(), Presets.byName("GLINT", "SWEEP", "WARM DRIFT"))
        assertEquals(null, Presets.byName("GLINT", "SWEEP", "NOT A PRESET"))
    }

    @Test
    fun `all sums every registered engine's roster with nothing lost or duplicated`() {
        val expected = ThumpPresets.all() + TinesPresets.all() + PluckPresets.all() +
            VelvetPresets.all() + FathomPresets.all() + TonewheelPresets.all() + VoxPresets.all() +
            SkinPresets.all() + ResinPresets.all() + TidePresets.all() + GlintPresets.all() + SirenPresets.all() + ForkPresets.all() +
            BorePresets.all() + ArcoPresets.all() + MercuryPresets.all() +
            FlotillaPresets.all() + AerostatPresets.all() + TremorPresets.all() +
            CisternPresets.all() + CircuitPresets.all() + ThawPresets.all() +
            MurkPresets.all() + NimbusPresets.all() + CorollaPresets.all() + UndertowPresets.all() +
            SuturePresets.all() + TesseraPresets.all() + PitchwheelPresets.all() + RevelPresets.all() + PrismPresets.all() + AlloyPresets.all() + ContourPresets.all() + StoopPresets.all() + LacquerPresets.all() + SpoolPresets.all() + SkitterPresets.all() + TanglePresets.all() + RelayPresets.all() + EmbouchurePresets.all() + BellowsPresets.all() + MurmurPresets.all() + OspreyPresets.all() + RegistersPresets.all() + ShoalPresets.all() + OpenSkyPresets.all() + VentKitPresets.all()
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
