package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Acceptance of the authored recipes; the separate engine tests retain their existing numerical gates. */
class EmbouchurePresetsTest {
    private val expected = linkedMapOf(
        EmbouchureVoice.REED to listOf("Leaning Reed", "Short Overblow", "Tongue Point", "Round Reed", "Open Reed", "Breath Accent", "Low Murmur", "Held Reed"),
        EmbouchureVoice.BRASS to listOf("Muted Bell", "Bell Call", "Warm Cup", "Short Bell", "Lower Brass", "Held Bell"),
        EmbouchureVoice.BASS to listOf("Breath Pocket", "Reed Pocket", "Deep Column", "Biting Bass", "Air Floor", "Held Foundation"),
        EmbouchureVoice.WHISTLE to listOf("Narrow Air", "Clear Call", "Air Thread", "Held Air"),
        EmbouchureVoice.BOW to listOf("Relaxed Bow", "Pointed Bow", "Hollow String", "Rosin Line", "Held String", "Low Bow Hold"),
        EmbouchureVoice.SOFT to listOf("Soft Entry", "Held Effort", "Warm Arrival", "Velvet Cut", "Feather Breath", "Held Velvet"),
    )

    private fun held(patch: EmbouchurePatch) = Embouchure.isLoop(patch.macros.getValue("HOLD"))
    private fun restored(patch: EmbouchurePatch): EmbouchurePatch {
        val decoded = assertIs<EmbouchurePatch>(Patches.fromJsonText(patch.toJsonText()))
        assertEquals(patch, decoded)
        assertEquals(patch, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).patch)
        assertEquals(2, decoded.model)
        return decoded
    }

    @Test
    fun `the catalog contains thirty six complete named playing roles with the accepted eight first`() {
        val all = EmbouchurePresets.all()
        assertEquals(36, all.size)
        assertEquals(36, all.map { it.name }.distinct().size)
        assertEquals(listOf(8, 6, 6, 4, 6, 6), expected.keys.map { EmbouchurePresets.forVoice(it).size })
        for ((voice, names) in expected) assertEquals(names, EmbouchurePresets.forVoice(voice).map { it.name })
        assertEquals(28, all.count { !held(it) })
        assertEquals(8, all.count(::held))
        assertEquals(listOf("Leaning Reed", "Muted Bell", "Breath Pocket", "Soft Entry", "Short Overblow", "Relaxed Bow", "Narrow Air", "Held Effort"),
            EmbouchurePresets.initial().map { it.name })
        assertEquals(EmbouchurePresets.initial(), all.take(8))
        val catalog = EmbouchurePresets.catalog()
        assertEquals(all, catalog.map { it.patch })
        assertEquals(EmbouchurePresets.initial(), catalog.filter { it.isInitial }.map { it.patch })
        assertEquals(setOf("lead", "bass", "air", "bowed", "soft"), catalog.map { it.category }.toSet())
        for (entry in catalog) {
            val patch = entry.patch
            assertEquals(entry, EmbouchurePresets.metadataFor(patch.name))
            assertEquals(held(patch), entry.isHeld)
            assertTrue(entry.description.length >= 35 && entry.role.label.isNotBlank(), "${patch.name} lacks musical intent")
            assertEquals(Embouchure.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys)
            assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f })
            assertTrue(patch.midi in Embouchure.midiRange(patch.voice))
            restored(patch)
        }
        assertNull(EmbouchurePresets.metadataFor("missing preset"))
        // A name, a transposition, or a velocity change cannot create another authored control recipe.
        val identities = all.map { patch -> JsonValue.Obj(patch.toJsonValue().entries - setOf("name", "midi", "velocity") +
            ("macros" to JsonValue.Obj(patch.toJsonValue().entries.getValue("macros").obj() - "TUNE"))) }
        assertEquals(36, identities.distinct().size, "factory entries only rename or transpose an existing recipe")
    }

    @Test
    fun `all eight accepted model two presets retain their complete recipes and published PCM16`() {
        val clips = EmbouchurePresetAcceptance.golden.getValue("presets").arr().map { it.obj() }
        assertEquals(8, clips.size)
        val initial = EmbouchurePresets.initial()
        assertEquals(clips.map { it.getValue("recipe") }, initial.map { it.toJsonValue() }, "accepted recipe changed")
        for ((patch, clip) in initial.zip(clips)) EmbouchurePresetAcceptance.assertWav(restored(patch).render(), clip, patch.name)
    }

    @Test
    fun `every finite recipe speaks through its full envelope at hard and native soft velocity then decays without a fade`() {
        val failures = mutableListOf<String>()
        for (patch in EmbouchurePresets.all().filterNot(::held)) {
            try {
                val duration = Embouchure.gestureSeconds(patch.voice, patch.macros)
                for (velocity in listOf(1f, .35f)) {
                    val played = restored(patch.copy(velocity = velocity))
                    val snip = if (velocity == 1f) played.render() else Velocity.atVelocity(restored(patch), velocity)
                    EmbouchurePresetAcceptance.assertAudio(snip, "${patch.name} v=$velocity")
                    assertEquals(Embouchure.renderFrames(patch.voice, patch.macros), snip.frameCount)
                    assertTrue(snip.durationSeconds > duration + .5f, "${patch.name} truncates its source gesture")
                    for (fraction in listOf(.5f, .72f)) {
                        val from = duration * fraction
                        EmbouchurePresetAcceptance.assertRoot(snip, played, from)
                        val body = EmbouchurePresetAcceptance.rms(snip.samples, Dsp.RATE, from.toDouble(), from + .25)
                        assertTrue(body > EmbouchurePresetAcceptance.rms(snip.samples, Dsp.RATE) * .08,
                            "${patch.name} v=$velocity loses its voiced envelope at $from")
                    }
                    val metrics = EmbouchureControlMetrics.measure(snip, duration.toDouble(), EmbouchurePresetAcceptance.wanted(played).toDouble())
                    assertTrue(metrics.energy90SpanSeconds > maxOf(.35, duration * .25),
                        "${patch.name} v=$velocity is a brief onset rather than its full voiced phrase: ${metrics.energy90SpanSeconds}s")
                    assertTrue(metrics.bodyRootShare > .005, "${patch.name} v=$velocity root is only an inaudible trace")
                    println("EMBOUCHURE PRESET ${patch.name} v=$velocity span=${metrics.energy90SpanSeconds} root=${metrics.bodyRootShare} noise=${metrics.bodyNonharmonicShare}")
                }
                // Follow the actual recipe's release, without replacing its gesture or applying finish()'s fade.
                val p = Embouchure.play(patch.voice, patch.macros, Embouchure.Probe(midi = patch.midi,
                    gesture = patch.gesture, model = patch.model, totalSeconds = duration + 1.6f))
                assertTrue(p.trace.finite && p.raw.all { it.isFinite() } && p.maxAbs < 2.5f, "${patch.name} left its raw bounds")
                val sourceOff = (p.sourceEndSeconds * p.trace.rate).toInt() + 2
                assertTrue(p.trace.player.drop(sourceOff).all { it == 0f }, "${patch.name} still supplies work after release")
                val body = EmbouchurePresetAcceptance.rms(p.raw, p.rate, duration * .5, duration * .75)
                val tail = EmbouchurePresetAcceptance.rms(p.raw, p.rate, duration + 1.3, duration + 1.6)
                assertTrue(body > 1e-5 && tail < body * .025, "${patch.name} raw passive tail does not dissipate: $body -> $tail")
                val releaseEnergy = p.trace.energy.drop(sourceOff).take(100).average()
                assertTrue(releaseEnergy > 0 && p.trace.energy.takeLast(100).average() < releaseEnergy * .05,
                    "${patch.name} stored energy does not dissipate after its actual gesture")
                assertTrue(p.raw.none { it != 0f && abs(it) < java.lang.Float.MIN_NORMAL }, "${patch.name} raw subnormals")
            } catch (failure: AssertionError) { failures += "${patch.name}: ${failure.message}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun checkHeld(patch: EmbouchurePatch) {
        val loop = Embouchure.renderLoopMeasured(patch.voice, patch.macros, patch.midi, patch.velocity, patch.gesture, model = patch.model)
        val label = "${patch.name} v=${patch.velocity} MIDI=${patch.midi} TUNE=${patch.macros["TUNE"]}"
        val seam = Keys.seamError(loop.audio, loop.loopStart)
        println("EMBOUCHURE PRESET HOLD $label state=${loop.stateError} seam=$seam settle=${loop.settleSeconds}")
        assertTrue(loop.converged && loop.stateError < .02, "$label full state did not converge: ${loop.stateError}")
        assertTrue(seam < 1e-3, "$label independent continuous loops fail seam: $seam")
        assertEquals(loop.seam, seam, loop.seam * 1e-3 + 1e-15)
        val continuous = Snip(loop.samples, 1, Dsp.RATE)
        EmbouchurePresetAcceptance.assertAudio(continuous, label)
        EmbouchurePresetAcceptance.assertRoot(continuous, patch, .2f, .4f)
        val published = if (patch.velocity == .35f) Velocity.atVelocity(restored(patch.copy(velocity = 1f)), .35f) else restored(patch).render()
        EmbouchurePresetAcceptance.assertAudio(published, label)
        EmbouchurePresetAcceptance.assertRoot(published, patch, .2f, .4f)
        EmbouchurePresetAcceptance.assertPlaybackBoundary(published, label)
    }

    @Test
    fun `every exact held recipe closes its actual states and playback boundary at native hard and soft velocity`() {
        val failures = mutableListOf<String>()
        for (patch in EmbouchurePresets.all().filter(::held)) for (velocity in listOf(1f, .35f)) {
            try { checkHeld(patch.copy(velocity = velocity)) }
            catch (failure: AssertionError) { failures += "${patch.name} v=$velocity: ${failure.message}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `authored held colors and representative finite gestures capture both supported register endpoints`() {
        val failures = mutableListOf<String>()
        val finiteNames = listOf("Tongue Point", "Warm Cup", "Air Floor", "Air Thread", "Rosin Line", "Feather Breath")
        val selected = EmbouchurePresets.all().filter { held(it) || it.name in finiteNames }
        assertEquals(14, selected.size)
        for (patch in selected) for (midi in listOf(Embouchure.midiRange(patch.voice).first, Embouchure.midiRange(patch.voice).last)) {
            val atNote = patch.copy(midi = midi, macros = patch.macros + ("TUNE" to 0f))
            try {
                if (held(atNote)) checkHeld(atNote) else {
                    val soft = atNote.copy(velocity = .35f)
                    val snip = Velocity.atVelocity(restored(atNote), soft.velocity)
                    EmbouchurePresetAcceptance.assertAudio(snip, "${soft.name} MIDI=$midi")
                    for (fraction in listOf(.5f, .72f)) EmbouchurePresetAcceptance.assertRoot(snip, soft,
                        fraction * Embouchure.gestureSeconds(soft.voice, soft.macros))
                }
            } catch (failure: AssertionError) { failures += "${patch.name} MIDI=$midi: ${failure.message}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `authored colors differ in body texture or playing contour after matching pitch and gain`() {
        data class Sound(val patch: EmbouchurePatch, val reading: EmbouchureControlMetrics.Reading, val contour: DoubleArray)
        fun contour(snip: Snip): DoubleArray {
            val bins = DoubleArray(32) { bin -> EmbouchurePresetAcceptance.rms(snip.samples, snip.sampleRate,
                bin * snip.durationSeconds / 32.0, (bin + 1) * snip.durationSeconds / 32.0) }
            val mean = bins.average().coerceAtLeast(1e-12)
            return DoubleArray(bins.size) { bins[it] / mean }
        }
        val failures = mutableListOf<String>()
        for (voice in EmbouchureVoice.entries) {
            // Transposition must not be the only distinction. Use one legal note for every color in a voice.
            val sounds = EmbouchurePresets.forVoice(voice).map { original ->
                val patch = original.copy(midi = Embouchure.rootMidi(voice), macros = original.macros + ("TUNE" to .5f))
                val snip = patch.render()
                val duration = if (held(patch)) snip.durationSeconds else Embouchure.gestureSeconds(voice, patch.macros)
                Sound(original, EmbouchureControlMetrics.measure(snip, duration.toDouble(), EmbouchurePresetAcceptance.wanted(patch).toDouble()), contour(snip))
            }
            for (i in sounds.indices) for (j in i + 1 until sounds.size) {
                val a = sounds[i]; val b = sounds[j]
                if (a.patch in EmbouchurePresets.initial() && b.patch in EmbouchurePresets.initial()) continue
                val body = EmbouchureControlMetrics.weightedDb(a.reading.body, b.reading.body)
                val harmonic = (1 until a.reading.bodyHarmonicsDb.size).filter {
                    maxOf(a.reading.bodyHarmonicShares[it], b.reading.bodyHarmonicShares[it]) >= .003
                }.maxOfOrNull { abs(a.reading.bodyHarmonicsDb[it] - b.reading.bodyHarmonicsDb[it]) } ?: 0.0
                val noise = abs(a.reading.bodyNonharmonicShare - b.reading.bodyNonharmonicShare)
                // A held buffer may start at a different best cut. Moving the same contour around
                // its cycle is not another color, so compare its best circular alignment.
                val contour = if (held(a.patch) && held(b.patch)) a.contour.indices.minOf { shift ->
                    a.contour.indices.sumOf { abs(a.contour[it] - b.contour[(it + shift) % b.contour.size]) } / a.contour.size
                } else a.contour.indices.sumOf { abs(a.contour[it] - b.contour[it]) } / a.contour.size
                val attack = EmbouchureControlMetrics.attackDifferenceSeconds(a.reading, b.reading)
                val span = abs(a.reading.energy90SpanSeconds - b.reading.energy90SpanSeconds)
                val playingRole = held(a.patch) != held(b.patch)
                val responseCue = body >= 1.0 || harmonic >= 1.0 || noise >= .015 ||
                    (if (held(a.patch) && held(b.patch)) contour >= .10 else attack >= .08 || span >= .25)
                // These are regression/listening prompts, not a claim that a number proves perceived difference.
                println("EMBOUCHURE COLOR ${a.patch.name} / ${b.patch.name}: bodyDb=$body harmonicDb=$harmonic noise=$noise attack=$attack span=$span contour=$contour role=$playingRole")
                if (!playingRole && !responseCue) failures += "${a.patch.name} / ${b.patch.name}: body=$body harmonic=$harmonic noise=$noise attack=$attack span=$span contour=$contour"
            }
        }
        assertTrue(failures.isEmpty(), "Close colors need a distinct recipe and a listening review:\n${failures.joinToString("\n")}")
    }
}
