package com.snipsnap.synth

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OtisPresetStudyTest {
    @Test
    fun `twelve unique candidates provide three distinct control sets per family`() {
        val presets = OtisPresetStudy.presets()
        assertEquals(12, presets.size)
        assertEquals(12, presets.map { it.id }.distinct().size, "preset IDs collide")
        assertEquals(12, presets.map { it.name }.distinct().size, "preset names collide")
        for (voice in OtisPrototypeVoice.entries) {
            val family = presets.filter { it.voice == voice }
            assertEquals(3, family.size, "$voice does not have three candidates")
            assertEquals(3, family.map { it.organ to it.air }.distinct().size, "$voice repeats a control set")
            assertTrue(family.all { it.name.isNotBlank() && it.description.isNotBlank() })
        }
        assertEquals(1, presets.count { it.organ.rotary }, "only the rotating-organ candidate should enable the cabinet")
    }

    @Test
    fun `thirty cases cover identical notes phrases and the two chord families`() {
        val presets = OtisPresetStudy.presets().associateBy { it.id }
        val cases = OtisPresetStudy.cases()
        val expected = buildSet {
            for (preset in presets.values) {
                add(preset.id to "Note")
                add(preset.id to "Phrase")
                if (preset.voice in listOf(OtisPrototypeVoice.ORGAN, OtisPrototypeVoice.SQUEEZE)) add(preset.id to "Chord")
            }
        }
        assertEquals(30, cases.size)
        assertEquals(30, cases.map { it.id }.distinct().size)
        assertEquals(expected, cases.map { it.presetId to it.group }.toSet())
        assertEquals(12, cases.count { it.group == "Note" })
        assertEquals(12, cases.count { it.group == "Phrase" })
        assertEquals(6, cases.count { it.group == "Chord" })
        for (case in cases) {
            val preset = presets.getValue(case.presetId)
            assertEquals("${preset.id}_${case.group.lowercase()}", case.id)
            assertEquals(preset.name, case.presetName)
            assertEquals(preset.voice.name, case.family)
            val notes = case.request.notes
            when (case.group) {
                "Note" -> assertEquals(listOf(OtisPrototypeNote(midi = 60, gateSeconds = 1.5, velocity = .8f)), notes)
                "Phrase" -> {
                    assertEquals(listOf(60, 63, 67, 65), notes.map { it.midi })
                    for ((index, note) in notes.withIndex()) {
                        assertEquals(listOf(0.0, .52, 1.04, 1.56)[index], note.startSeconds, 1e-12)
                        assertEquals(.40, note.gateSeconds)
                        assertEquals(.8f, note.velocity)
                    }
                }
                "Chord" -> {
                    assertEquals(listOf(60, 63, 67, 70), notes.map { it.midi })
                    assertTrue(notes.all { it.startSeconds == 0.0 && it.gateSeconds == 1.5 && it.velocity == .68f },
                        "${case.id} does not hold its complete Cm7 voicing together")
                }
                else -> error("unexpected preset gesture ${case.group}")
            }
        }
    }

    @Test
    fun `default comparisons preserve every performance choice and only source controls change`() {
        for (case in OtisPresetStudy.cases()) {
            val expected = OtisPrototypeRequest(case.request.voice, case.request.notes, model = 3)
            assertEquals(expected, case.comparison, "${case.id} changed its default comparison")
            assertEquals(3, case.request.model)
            assertEquals(case.comparison.seed, case.request.seed)
            assertEquals(case.comparison.notes, case.request.notes)
            assertEquals(case.comparison.copy(organ = case.request.organ, air = case.request.air), case.request,
                "${case.id} changed something beyond source controls")
            assertNotEquals(case.comparison, case.request, "${case.id} is the unchanged default under another name")
        }
    }

    @Test
    fun `presets leave all other families controls at their defaults`() {
        val organ = OtisOrganSettings()
        val air = OtisAirSettings()
        for (preset in OtisPresetStudy.presets()) {
            if (preset.voice == OtisPrototypeVoice.ORGAN) {
                assertEquals(air, preset.air, "${preset.id} changed an air-source control")
                continue
            }
            assertEquals(organ, preset.organ, "${preset.id} changed the organ controls")
            val restored = when (preset.voice) {
                OtisPrototypeVoice.STEAM -> preset.air.copy(steamInstability = air.steamInstability,
                    breath = air.breath, pressure = air.pressure, release = air.release)
                OtisPrototypeVoice.HARP -> preset.air.copy(harpBend = air.harpBend, breath = air.breath,
                    harpVibrato = air.harpVibrato, pressure = air.pressure, release = air.release)
                OtisPrototypeVoice.SQUEEZE -> preset.air.copy(musette = air.musette, bellowsPulse = air.bellowsPulse,
                    pressure = air.pressure, release = air.release)
                else -> error("organ handled separately")
            }
            assertEquals(air, restored, "${preset.id} changed an unrelated family's control")
        }
    }

    @Test
    fun `complete preset recipes round trip without relying on source defaults`() {
        assertEquals(OtisPresetStudy.presets(), OtisPresetStudy.presets())
        assertEquals(OtisPresetStudy.cases(), OtisPresetStudy.cases())
        for (case in OtisPresetStudy.cases()) {
            val text = OtisPrototypeRecipe.toJsonText(case.request)
            val decoded = OtisPrototypeRecipe.fromJsonText(text)
            assertEquals(case.request, decoded, "${case.id} lost controls in its recipe")
            assertEquals(text, OtisPrototypeRecipe.toJsonText(decoded), "${case.id} recipe serialization is unstable")
        }
    }

    @Test
    fun `every candidate replays exactly and changes emitted tone beyond RMS level`() {
        val note = listOf(OtisPrototypeNote(midi = 60, gateSeconds = .35, velocity = .8f))
        val defaults = OtisPrototypeVoice.entries.associateWith { voice ->
            OtisPrototypeRequest(voice, note, model = 3).render()
        }
        for (case in OtisPresetStudy.cases().filter { it.group == "Note" }) {
            val request = case.request.copy(notes = note)
            val first = request.render()
            val replay = OtisPrototypeRecipe.fromJsonText(OtisPrototypeRecipe.toJsonText(request)).render()
            assertContentEquals(first.samples, replay.samples, "${case.presetId} cannot replay its saved controls")
            assertTrue(first.samples.all { it.isFinite() }, "${case.presetId} generated nonfinite audio")

            // Compare only the common held region, excluding different release
            // durations. Unit-RMS scaling removes source gain as an explanation.
            val baseline = defaults.getValue(request.voice)
            val from = (first.sampleRate * .02).toInt()
            val to = (first.sampleRate * .33).toInt()
            fun rms(samples: FloatArray): Double = sqrt((from until to).sumOf {
                samples[it].toDouble() * samples[it]
            } / (to - from))
            val a = rms(first.samples)
            val b = rms(baseline.samples)
            assertTrue(a > 1e-5 && b > 1e-5, "${case.presetId} has no held body to compare")
            val distance = sqrt((from until to).sumOf {
                val difference = first.samples[it] / a - baseline.samples[it] / b
                difference * difference
            } / (to - from))
            assertTrue(distance > .06, "${case.presetId} differs from default only trivially after RMS scaling: $distance")
        }
    }
}
