package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Check audible control authority before level matching, and saved model-1 compatibility. */
class ContourControlsTest {
    private val controls = listOf("BODY", "EDGE", "ATTACK", "DECAY", "RATE", "RESO", "ENV")
    private val neutral = controls.associateWith { .5f }

    @Test
    fun `missing and neutral new controls preserve raw and finished finite and held audio exactly`() {
        // Archived recipe hashes separately establish the old DSP reference. Here every voice
        // exercises both public output paths with old seven-key maps and explicit neutral keys.
        for (voice in ContourVoice.entries) for (hold in listOf(0f, 1f)) {
            val old = ContourEngine.defaults(voice).filterKeys { it !in controls } + ("HOLD" to hold)
            val explicit = old + neutral
            val raw = ContourEngine.renderRaw(voice, old)
            val explicitRaw = ContourEngine.renderRaw(voice, explicit)
            assertTrue(raw.contentEquals(explicitRaw), "$voice HOLD $hold: neutral controls changed raw audio")
            val finished = ContourEngine.render(voice, old).samples
            val explicitFinished = ContourEngine.render(voice, explicit).samples
            assertTrue(finished.contentEquals(explicitFinished), "$voice HOLD $hold: neutral controls changed finished audio")
        }
    }

    @Test
    fun `BODY changes root to partial balance rather than just the output gain`() {
        val voice = ContourVoice.KEY
        val config = ContourConfig.legacyForVoice(voice).copy(subLevel = 0f, loopSeconds = 1f)
        val macros = staticOpen(voice) + ("HOLD" to 1f)
        fun body(value: Float) = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.BODY_ONLY,
            macros + ("BODY" to value), config = config)
        val round = body(0f)
        val partial = body(1f)
        val hz = ContourEngine.frequencyFor(voice, .5f).toDouble()
        val a = ContourTestAudio.power(round, 4096, 32768)
        val b = ContourTestAudio.power(partial, 4096, 32768)
        val rootRatio = harmonicEnergy(a, hz, 1) / harmonicEnergy(b, hz, 1)
        val partialRatio = (2..16).sumOf { harmonicEnergy(b, hz, it) } /
            (2..16).sumOf { harmonicEnergy(a, hz, it) }
        println("CONTOUR BODY: rounded/partial root power $rootRatio, partial/rounded overtone power $partialRatio")
        assertTrue(rootRatio > 25, "BODY did not substantially change the requested-root contribution")
        assertTrue(partialRatio > 3, "BODY did not independently increase the overtones")
        assertTrue(ContourTestAudio.spectralDistance(round, partial, 4096, hz, 32768) > 4,
            "BODY changed gain without a useful spectral change")
    }

    @Test
    fun `EDGE can silence its layer and independently bring it forward`() {
        val voice = ContourVoice.KEY
        val config = ContourConfig.legacyForVoice(voice).copy(edgeLevel = .2f, loopSeconds = 1f)
        val macros = staticOpen(voice) + ("HOLD" to 1f)
        fun edge(value: Float) = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.EDGE_ONLY,
            macros + ("EDGE" to value), config = config)
        val muted = edge(0f)
        val authored = edge(.5f)
        val forward = edge(1f)
        assertTrue(muted.all { it == 0f }, "EDGE 0 left an audible edge layer")
        assertTrue(ContourTestAudio.rms(authored) > .005, "the authored edge probe is silent")
        assertTrue(ContourTestAudio.rms(forward) > ContourTestAudio.rms(authored) * 2,
            "EDGE cannot bring the independent layer substantially forward")
        val bodyA = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.BODY_ONLY,
            macros + ("EDGE" to 0f), config = config)
        val bodyB = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.BODY_ONLY,
            macros + ("EDGE" to 1f), config = config)
        val hz = ContourEngine.frequencyFor(voice, .5f).toDouble()
        assertTrue(ContourTestAudio.spectralDistance(bodyA, bodyB, 4096, hz, 32768) < .05,
            "EDGE changed the body spectrum")
    }

    @Test
    fun `ATTACK changes measured onset and DECAY changes the audible lifetime`() {
        val voice = ContourVoice.KEY
        val macros = staticOpen(voice)
        val attackConfig = ContourConfig.forVoice(voice).copy(ampAttack = .12f, ampDecay = .08f,
            ampSustain = .9f, ampRelease = .15f, sustainSeconds = .6f)
        fun attack(value: Float) = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.BODY_ONLY,
            macros + ("ATTACK" to value), config = attackConfig)
        val fast = onsetHalf(attack(0f))
        val slow = onsetHalf(attack(1f))
        println("CONTOUR ATTACK: half-RMS onset $fast to $slow seconds")
        assertTrue(slow > fast * 10 && slow - fast > .2, "ATTACK has insufficient onset range: $fast to $slow")

        val decayConfig = attackConfig.copy(ampAttack = .02f, ampDecay = .3f,
            ampSustain = .45f, ampRelease = .3f, sustainSeconds = .1f)
        fun decay(value: Float) = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.BODY_ONLY,
            macros + ("DECAY" to value), config = decayConfig)
        val short = decay(0f)
        val long = decay(1f)
        val shortLife = energyLifetime(short)
        val longLife = energyLifetime(long)
        println("CONTOUR DECAY: 99-percent-energy lifetime $shortLife to $longLife seconds")
        assertTrue(long.size > short.size * 5, "DECAY left the rendered duration almost unchanged")
        assertTrue(longLife > shortLife * 3, "DECAY extended silence instead of the audible envelope")
        assertTrue(long.size <= Dsp.RATE * 8, "DECAY escaped the finite eight-second budget")
    }

    @Test
    fun `RATE changes the motion clock without changing its span or unfreezing a static source`() {
        val voice = ContourVoice.KEY
        val config = ContourConfig.forVoice(voice).copy(motionShape = ContourMotionShape.CYCLE,
            motionSeconds = .8f, loopSeconds = 1f)
        val macros = ContourEngine.defaults(voice) + mapOf("MOTION" to .7f, "DRIVE" to 0f, "FILTER" to 1f)
        val center = ContourEngine.scanCoordinates(voice, macros + ("MOTION" to 0f), 0.0, config).first
        fun trace(rate: Float) = DoubleArray(16_001) { i ->
            ContourEngine.scanCoordinates(voice, macros + ("RATE" to rate), i / 2000.0, config).first
        }
        val slow = trace(0f)
        val fast = trace(1f)
        fun crossings(x: DoubleArray) = (1 until x.size).count { x[it - 1] <= center && x[it] > center }
        val slowCycles = crossings(slow)
        val fastCycles = crossings(fast)
        println("CONTOUR RATE: $slowCycles to $fastCycles rising scan crossings over eight seconds")
        assertTrue(slowCycles >= 1 && fastCycles > slowCycles * 20, "RATE has insufficient independent clock range")
        assertTrue(abs(slow.max() - fast.max()) < .001 && abs(slow.min() - fast.min()) < .001,
            "RATE changed the scan span instead of just its timing")
        for (t in listOf(0.0, .17, 1.4, 5.0)) {
            val a = ContourEngine.scanCoordinates(voice, macros + mapOf("MOTION" to 0f, "RATE" to 0f), t, config)
            val b = ContourEngine.scanCoordinates(voice, macros + mapOf("MOTION" to 0f, "RATE" to 1f), t, config)
            assertEquals(a, b, "RATE moved a MOTION 0 source at $t seconds")
        }
        val frozenA = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.FROZEN,
            macros + ("RATE" to 0f), config = config)
        val frozenB = ContourEngine.renderDiagnosticRaw(voice, ContourDiagnostic.FROZEN,
            macros + ("RATE" to 1f), config = config)
        assertTrue(frozenA.contentEquals(frozenB), "RATE unfreezes the FROZEN diagnostic")
        val heldSlow = ContourEngine.renderLoopMeasured(voice, macros + ("RATE" to 0f), config = config)
        val heldFast = ContourEngine.renderLoopMeasured(voice, macros + ("RATE" to 1f), config = config)
        assertTrue(heldFast.motionCycles > heldSlow.motionCycles && heldSlow.motionCycles >= 1 && heldFast.motionCycles <= 16,
            "held RATE did not change its fitted periodic clock")
    }

    @Test
    fun `RESO changes spectral emphasis and ENV controls a transient filter gesture`() {
        val voice = ContourVoice.KEY
        val macros = staticOpen(voice) + mapOf("FILTER" to .18f, "EDGE" to 0f)
        val config = ContourConfig.forVoice(voice).copy(ampAttack = .01f, ampDecay = .05f,
            ampSustain = 1f, ampRelease = .1f, sustainSeconds = 1f,
            filterAttack = .004f, filterDecay = .08f, loopSeconds = 1f)
        val hz = ContourEngine.frequencyFor(voice, .5f).toDouble()
        val dull = ContourEngine.renderRaw(voice, macros + mapOf("HOLD" to 1f, "RESO" to 0f), config = config)
        val resonant = ContourEngine.renderRaw(voice, macros + mapOf("HOLD" to 1f, "RESO" to 1f), config = config)
        val resoDistance = ContourTestAudio.spectralDistance(dull, resonant, 4096, hz, 32768)
        assertTrue(resoDistance > 1, "RESO changed gain without useful spectral emphasis: $resoDistance dB")
        val plain = ContourEngine.renderRaw(voice, macros + ("ENV" to 0f), config = config)
        val gesture = ContourEngine.renderRaw(voice, macros + ("ENV" to 1f), config = config)
        val early = ContourTestAudio.spectralDistance(plain, gesture, 1024, hz)
        val late = ContourTestAudio.spectralDistance(plain, gesture, (Dsp.RATE * .75).toInt(), hz)
        println("CONTOUR filter: RESO spectral distance $resoDistance dB; ENV early $early dB, settled $late dB")
        assertTrue(early > 1, "ENV cannot create a useful filter gesture")
        assertTrue(late < .15 && early > late * 5, "ENV changed the resting spectrum instead of its transient")
    }

    @Test
    fun `combined new control extremes remain finite in tune and close their held loops`() {
        for ((index, voice) in ContourVoice.entries.withIndex()) for (extreme in listOf(0f, 1f)) {
            val tune = if (index % 2 == 0) .25f else .75f
            val macros = ContourEngine.defaults(voice) + controls.associateWith { extreme } +
                mapOf("TUNE" to tune, "SCAN" to .65f, "OFFSET" to 1f, "MOTION" to 1f,
                    "DRIVE" to 1f, "FILTER" to .45f)
            val config = ContourConfig.forVoice(voice).copy(loopSeconds = 1f)
            val loop = ContourEngine.renderLoopMeasured(voice, macros, config = config)
            val hz = ContourEngine.frequencyFor(voice, tune)
            val cents = ContourTestAudio.pitch(Snip(loop.samples, 1, Dsp.RATE), hz, .1f, .7f)
            assertTrue(abs(cents) < 5, "$voice controls $extreme: root moved $cents cents")
            assertTrue(loop.samples.all { it.isFinite() && abs(it) <= 1f }, "$voice controls $extreme: unsafe held output")
            assertTrue(ContourTestAudio.rms(loop.samples) > .01, "$voice controls $extreme: silent held output")
            assertTrue(loop.periodicError < 1e-3 && loop.stateError < 1e-3 && loop.seamBeforeBlend < 1e-3,
                "$voice controls $extreme: active states did not close before seam blending")
            assertTrue(Keys.seamError(loop.samples, loop.loopStart) < 1e-3, "$voice controls $extreme: loop seam")
            val finite = ContourEngine.renderRaw(voice, macros + ("HOLD" to .9f), config = config)
            assertTrue(finite.size in 1..Dsp.RATE * 8 && finite.all { it.isFinite() && abs(it) < 8 },
                "$voice controls $extreme: unsafe or unbounded finite output")
        }
    }

    @Test
    fun `nonneutral expanded controls survive saved recipes edits and deterministic replay`() {
        val voice = ContourVoice.HOLLOW
        val macros = ContourEngine.defaults(voice) + controls.withIndex().associate { (i, key) -> key to (i + 1) / 9f }
        val config = ContourConfig.forVoice(voice).copy(edgeRatio = 1.5f, edgeLag = .31f,
            bodyTable = ContourTable.SOFT_SAW, edgeTable = ContourTable.COPPER, loopSeconds = 1f)
        val patch = ContourPatch("Expanded saved recipe", voice, macros, config, .63f)
        val restored = ContourPatch.fromJsonText(patch.toJsonText())
        assertEquals(patch, restored)
        val recipe = PadRecipe(patch, FxChain(tape = mapOf("WOBBLE" to .1f)))
        assertEquals(recipe, PadRecipe.fromJsonText(recipe.toJsonText()))
        val edited = Patches.edited(restored, "Edited expanded recipe", macros + ("BODY" to .91f)) as ContourPatch
        assertEquals(config, edited.config, "macro edits discarded the structural recipe")
        assertEquals(macros + ("BODY" to .91f), edited.macros)
        val originalAudio = patch.render().samples
        assertTrue(originalAudio.contentEquals(restored.render().samples), "saved expanded controls changed the sound")
        assertTrue(!originalAudio.contentEquals(edited.render().samples), "the restored BODY control cannot edit the sound")
    }

    private fun staticOpen(voice: ContourVoice) = ContourEngine.defaults(voice) +
        mapOf("MOTION" to 0f, "FILTER" to 1f, "DRIVE" to 0f, "HOLD" to 0f)

    private fun harmonicEnergy(power: DoubleArray, hz: Double, harmonic: Int): Double {
        val center = (harmonic * hz * 32768 / Dsp.RATE).toInt()
        return (center - 4..center + 4).sumOf { power[it] }
    }

    /** Half of this note's own peak RMS; scalar gain cannot masquerade as a slower onset. */
    private fun onsetHalf(x: FloatArray): Double {
        val block = 256
        val rms = DoubleArray(x.size / block) { i -> ContourTestAudio.rms(x, i * block, (i + 1) * block) }
        return rms.indexOfFirst { it >= rms.max() * .5 }.toDouble() * block / Dsp.RATE
    }

    /** Last meaningful energy rather than the output-array size or a padded silence. */
    private fun energyLifetime(x: FloatArray): Double {
        val target = x.sumOf { it.toDouble() * it } * .99
        var sum = 0.0
        for (i in x.indices) {
            sum += x[i].toDouble() * x[i]
            if (sum >= target) return i.toDouble() / Dsp.RATE
        }
        error("energy probe is silent")
    }
}
