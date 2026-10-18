package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpoolPresetsTest {
    @Test
    fun `factory bank has the specified thirty six unique complete recipes`() {
        val bank = SpoolPresets.all()
        assertEquals(36, bank.size)
        assertEquals(mapOf(SpoolVoice.STRING to 8, SpoolVoice.CHOIR to 8, SpoolVoice.FLUTE to 6,
            SpoolVoice.BRASS to 6, SpoolVoice.PALIMPSEST to 4, SpoolVoice.CUT to 4),
            bank.groupingBy { it.voice }.eachCount())
        assertEquals(bank.size, bank.map { it.name }.toSet().size)
        assertTrue(bank.map { it.name }.containsAll(listOf("DIAGONAL HEAD", "BORROWED ATTACK",
            "THREE SPRINGS", "FOLDED RECORDING")))
        for (patch in bank) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name), patch.name)
            assertEquals(Spool.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys,
                "${patch.name} omitted a saved control")
            assertEquals(3, patch.machine.roster.size, "${patch.name} relies on an implicit source roster")
        }
    }

    @Test
    fun `approved first six and per voice defaults keep their full original recipes`() {
        val names = listOf("SOFT FRAME", "RETURNING CHOIR", "FLUTE MEMORY", "WORN BRASS", "GHOST COPY", "SHORT SPLICE")
        val controls = listOf(
            listOf(.5f, .15f, .10f, .40f, .15f, .05f, 0f),
            listOf(.5f, .25f, .15f, .45f, .20f, .05f, 0f),
            listOf(.5f, .20f, .10f, .40f, .15f, .10f, 0f),
            listOf(.5f, .20f, .15f, .40f, .15f, .10f, 0f),
            listOf(.5f, .65f, .40f, .65f, .70f, .25f, 0f),
            listOf(.5f, .55f, .35f, .50f, .45f, .75f, 0f),
        )
        val families = listOf(
            listOf(SpoolSourceFamily.STRING, SpoolSourceFamily.CHOIR, SpoolSourceFamily.FLUTE),
            listOf(SpoolSourceFamily.CHOIR, SpoolSourceFamily.STRING, SpoolSourceFamily.FLUTE),
            listOf(SpoolSourceFamily.FLUTE, SpoolSourceFamily.CHOIR, SpoolSourceFamily.STRING),
            listOf(SpoolSourceFamily.BRASS, SpoolSourceFamily.STRING, SpoolSourceFamily.CHOIR),
            listOf(SpoolSourceFamily.STRING, SpoolSourceFamily.CHOIR, SpoolSourceFamily.FLUTE),
            listOf(SpoolSourceFamily.BRASS, SpoolSourceFamily.FLUTE, SpoolSourceFamily.STRING),
        )
        val gains = List(4) { listOf(.94f, .04f, .02f) } +
            listOf(listOf(.35f, .50f, .15f), listOf(.80f, .12f, .08f))
        val macroNames = listOf("TUNE", "HEAD", "STRAIN", "RETURN", "IMPRINT", "SPLICE", "HOLD")
        val expected = SpoolVoice.entries.mapIndexed { i, voice ->
            val originalMachine = SpoolMachine(
                roster = families[i].mapIndexed { j, family ->
                    SpoolStrip(family, gains[i][j], listOf(1f, 1.07f, .93f)[j],
                        .06f + j * .025f, .82f, .18f)
                },
                gateSeconds = 1.35f, sourceSeconds = 3f, motorTorque = 1f, returnSeconds = 1.35f,
                headOffsets = listOf(0f, -.12f, .16f),
                spliceSchedule = listOf(SpoolSplice(.22f, .14f), SpoolSplice(.44f, -.17f),
                    SpoolSplice(.67f, .12f), SpoolSplice(.85f, -.20f)),
                routes = listOf(SpoolRoute(0, 1, .42f), SpoolRoute(1, 2, .42f), SpoolRoute(2, 0, .42f)),
                holdMode = SpoolHoldMode.FROZEN_SNAPSHOT, seed = 26, writeDelaySamples = 32, reengage = false,
            )
            SpoolPatch(names[i], voice, macroNames.zip(controls[i]).toMap(), originalMachine, velocity = 1f, model = 1)
        }
        assertEquals(expected, SpoolPresets.all().take(6))
        assertEquals(expected.map { it.toJsonText() }, SpoolPresets.all().take(6).map { it.toJsonText() },
            "approved recipe serialization changed")
        for (patch in expected) assertEquals(patch, SpoolPresets.forVoice(patch.voice).first(), patch.name)
    }

    @Test
    fun `bank differences survive removing names transposition and random seeds`() {
        val bank = SpoolPresets.all()
        assertTrue(bank.all { it.macros.getValue("TUNE") == .5f }, "transposition must not supply bank variety")
        val recipes = bank.map { Triple(it.voice, it.macros - "TUNE", it.machine.copy(seed = 26)) }
        assertEquals(bank.size, recipes.toSet().size, "two names describe the same recording machine")
        for (voice in listOf(SpoolVoice.STRING, SpoolVoice.CHOIR, SpoolVoice.FLUTE, SpoolVoice.BRASS)) {
            val patches = SpoolPresets.forVoice(voice)
            assertTrue(patches.map { it.machine.gateSeconds }.toSet().size >= 5, "$voice lacks articulation variety")
            assertTrue(patches.map { it.machine }.toSet().size >= 5, "$voice only changes macro labels")
            assertTrue(patches.all { it.machine.roster.first().gain >= .80f }, "$voice lost its recognizable main family")
        }
    }

    @Test
    fun `every complete saved machine roundtrips and survives ordinary macro edits`() {
        for (patch in SpoolPresets.all()) {
            val json = patch.toJsonText()
            val restored = Patches.fromJsonText(json)
            assertEquals(patch, restored, patch.name)
            assertEquals(json, restored.toJsonText(), "${patch.name} lost serialized machine calibration")
            val changed = patch.macros + ("STRAIN" to .31f)
            assertEquals(patch.copy(name = "Edited ${patch.name}", macros = changed),
                Patches.edited(restored, "Edited ${patch.name}", changed), "${patch.name} reset its geometry")
        }
    }

    @Test
    fun `held snapshots and finite rereads have explicit distinct saved semantics`() {
        val bank = SpoolPresets.all()
        val held = bank.filter { Spool.isLoop(it.macros.getValue("HOLD")) }
        assertEquals(setOf("FRAME CYCLE", "VOWEL WHEEL", "CARBON CYCLE", "SPLICED CIRCLE"), held.map { it.name }.toSet())
        assertTrue(held.all { it.machine.holdMode == SpoolHoldMode.FROZEN_SNAPSHOT && !it.machine.reengage },
            "a frozen held cycle must not promise a finite second note")
        val rereads = bank.filter { it.machine.reengage }
        assertEquals(setOf("CHOIR AFTERIMAGE", "BORROWED ATTACK", "NEIGHBOR PRINT", "FOLDED RECORDING"),
            rereads.map { it.name }.toSet())
        assertTrue(rereads.all { it.macros.getValue("HOLD") == 0f && it.macros.getValue("IMPRINT") >= .70f &&
            it.machine.routes.isNotEmpty() }, "finite second encounters need saved write paths")
    }
}
