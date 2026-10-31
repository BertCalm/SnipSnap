package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * THROWAWAY PROBE. The owner's COIL specification, transcribed as written and
 * measured; nothing here ships. See the report for the compile changes C1..
 */
enum class CoilProbeVoice {
    CHUG,
    JANGLE,
    LEAD,
    MUTED,
    FUZZBOX,
}

internal object CoilProbe {

    const val TUNE_SEMITONES = 24
    const val ROOT_MIDI = 40 // E2 (~82.41 Hz standard low E)

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, neutral = 0.5f),
        MacroSpec("DRIVE", 0.45f),
        MacroSpec("TONE", 0.5f, neutral = 0.5f),
        MacroSpec("CAB", 0.6f),
        MacroSpec("MUTE", 0.2f),
        MacroSpec("SAG", 0.35f),
    )

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: CoilProbeVoice): List<MacroSpec> = MACROS

    fun defaults(voice: CoilProbeVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun drumClassFor(voice: CoilProbeVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val mute = macros["MUTE"] ?: defaults(voice).getValue("MUTE")
        return if (mute > 0.65f) DrumClass.PERC else DrumClass.TONAL
    }

    fun rootHz(voice: CoilProbeVoice): Float = when (voice) {
        CoilProbeVoice.CHUG    -> 41.20f // Low E1 (Drop tuning)
        CoilProbeVoice.JANGLE  -> 82.41f // Standard E2
        CoilProbeVoice.LEAD    -> 110.0f // A2
        CoilProbeVoice.MUTED   -> 82.41f // E2
        CoilProbeVoice.FUZZBOX -> 82.41f // E2
    }

    fun frequencyFor(voice: CoilProbeVoice, tune: Float): Float {
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return rootHz(voice) * 2f.pow(semis / 12f)
    }

    // C2: the spec's CoilPresets returns CoilPatch; the probe keeps the macro maps only.
    private object ProbePresets {
        private fun p(vararg macros: Pair<String, Float>): Map<String, Float> = macros.toMap()

        fun forVoice(voice: CoilProbeVoice): List<Map<String, Float>> = when (voice) {
            CoilProbeVoice.CHUG -> chugPresets
            CoilProbeVoice.JANGLE -> janglePresets
            CoilProbeVoice.LEAD -> leadPresets
            CoilProbeVoice.MUTED -> emptyList()
            CoilProbeVoice.FUZZBOX -> emptyList()
        }

        private val chugPresets = listOf(
            p("TUNE" to 0.1f, "DRIVE" to 0.85f, "TONE" to 0.65f, "CAB" to 0.95f, "MUTE" to 0.55f, "SAG" to 0.40f),
            p("TUNE" to 0.0f, "DRIVE" to 0.90f, "TONE" to 0.80f, "CAB" to 0.85f, "MUTE" to 0.80f, "SAG" to 0.20f),
            p("TUNE" to 0.15f, "DRIVE" to 0.75f, "TONE" to 0.35f, "CAB" to 0.90f, "MUTE" to 0.45f, "SAG" to 0.50f),
        )

        private val janglePresets = listOf(
            p("TUNE" to 0.5f, "DRIVE" to 0.25f, "TONE" to 0.70f, "CAB" to 0.20f, "MUTE" to 0.10f, "SAG" to 0.15f),
            p("TUNE" to 0.6f, "DRIVE" to 0.35f, "TONE" to 0.85f, "CAB" to 0.10f, "MUTE" to 0.20f, "SAG" to 0.30f),
        )

        private val leadPresets = listOf(
            p("TUNE" to 0.45f, "DRIVE" to 0.80f, "TONE" to 0.55f, "CAB" to 0.70f, "MUTE" to 0.05f, "SAG" to 0.85f),
        )
    }

    fun scramble(
        voice: CoilProbeVoice,
        random: Random,
        temperature: Float = 0.35f,
        near: Patch? = null,
    ): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) {
            base + near.macros.filterKeys { it in base }
        } else {
            base + ProbePresets.forVoice(voice).random(random).filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** C4: the stages of the chain, for measurement. [cabbed] is a copy taken before Tide.bandLimit. */
    data class Stages(
        val raw: FloatArray,
        val amped: FloatArray,
        val cabbed: FloatArray,
        val final: Snip,
    )

    fun render(
        voice: CoilProbeVoice,
        macros: Map<String, Float> = emptyMap(),
        seed: Int = 0,
        pickupPosOverride: Float? = null,
        humbuckerOverride: Boolean? = null,
    ): Snip = renderStages(voice, macros, seed, pickupPosOverride, humbuckerOverride).final

    // C6: skipBandLimit exists only for grid 4's variant; false is the spec's chain.
    internal fun renderStages(
        voice: CoilProbeVoice,
        macros: Map<String, Float> = emptyMap(),
        seed: Int = 0,
        pickupPosOverride: Float? = null,
        humbuckerOverride: Boolean? = null,
        skipBandLimit: Boolean = false,
    ): Stages {
        val m = defaults(voice) + macros
        val renderRate = RATE * Dsp.OVERSAMPLE

        val tune = m.getValue("TUNE")
        val drive = m.getValue("DRIVE")
        val tone = m.getValue("TONE")
        val cab = m.getValue("CAB")
        val mute = m.getValue("MUTE")
        val sag = m.getValue("SAG")

        val f0 = frequencyFor(voice, tune)
        val stringT60 = Dsp.expMap(1f - mute, 0.08f, 3.2f)
        val durationSec = (stringT60 * 1.25f).coerceIn(0.2f, 3.5f)
        val totalFrames = (durationSec * renderRate).toInt().coerceAtLeast(256)
        val rawBuffer = FloatArray(totalFrames)

        val prng = Dsp.Noise(if (seed == 0) Dsp.seedFor("COIL", voice, f0) else seed)

        // 1. Digital Waveguide Delay Line Setup
        val delaySamples = (renderRate / f0).toDouble()
        val maxDelay = (delaySamples * 2.0).toInt() + 64
        val delayLine = FloatArray(maxDelay)
        var writeIdx = 0

        // Inharmonic dispersion allpass (stiff steel core string)
        val stiffnessCoeff = -0.15f
        var apX1 = 0f
        var apY1 = 0f

        // Nut loss filter
        val nutFilter = Dsp.OnePole(renderRate)
        val nutCutoff = Dsp.expMap(1f - mute, 1800f, 16000f)
        val feedbackGain = Dsp.lin(1f - mute, 0.88f, 0.994f)

        // Plectrum Attack Excitation
        val pickFrames = (0.003f * renderRate).toInt().coerceAtLeast(4)
        val pickBuffer = FloatArray(pickFrames)
        val pickLp = Dsp.OnePole(renderRate)
        val pickCutoff = Dsp.expMap(1f - (mute * 0.5f), 3000f, 15000f)
        for (i in 0 until pickFrames) {
            val env = 0.5f * (1f - cos(2.0 * PI * i / pickFrames).toFloat())
            val noise = prng.next()
            pickBuffer[i] = pickLp.lp(noise * env, pickCutoff)
        }

        // Pickup placement geometry: normalized distance from bridge (0.0 = bridge, 1.0 = nut)
        val pickupPos = pickupPosOverride ?: when (voice) {
            CoilProbeVoice.CHUG -> 0.12f // Bridge humbucker position
            CoilProbeVoice.JANGLE -> 0.28f // Middle single-coil
            CoilProbeVoice.LEAD -> 0.42f // Neck pickup
            CoilProbeVoice.MUTED -> 0.15f // Bridge
            CoilProbeVoice.FUZZBOX -> 0.35f // Neck/Middle
        }
        val tapDelay = (delaySamples * pickupPos).coerceIn(1.0, delaySamples - 1.0)
        val isHumbucker = humbuckerOverride ?: (voice == CoilProbeVoice.CHUG || voice == CoilProbeVoice.LEAD)
        val humbuckerSpacing = (renderRate * 0.00045).coerceAtLeast(1.0) // ~18mm coil spacing

        // 2. Waveguide String Simulation Loop
        for (n in 0 until totalFrames) {
            val excitation = if (n < pickFrames) pickBuffer[n] else 0f

            // Read end of waveguide
            val readD = writeIdx - delaySamples
            var r0 = readD.toInt()
            while (r0 < 0) r0 += maxDelay
            r0 %= maxDelay
            val r1 = (r0 + 1) % maxDelay
            val frac = (readD - readD.toInt()).toFloat()
            val waveEnd = delayLine[r0] + (delayLine[r1] - delayLine[r0]) * frac

            // Inharmonic dispersion allpass
            val dispersed = stiffnessCoeff * (waveEnd - apY1) + apX1
            apX1 = waveEnd
            apY1 = dispersed

            // High frequency dissipation at termination
            val reflected = nutFilter.lp(dispersed, nutCutoff) * feedbackGain

            delayLine[writeIdx] = excitation + reflected

            // Dual-point electromagnetic pickup extraction
            val pReadD = writeIdx - tapDelay
            var pr0 = pReadD.toInt()
            while (pr0 < 0) pr0 += maxDelay
            pr0 %= maxDelay
            val pr1 = (pr0 + 1) % maxDelay
            val pFrac = (pReadD - pReadD.toInt()).toFloat()
            var pickupSignal = delayLine[pr0] + (delayLine[pr1] - delayLine[pr0]) * pFrac

            if (isHumbucker) {
                val hReadD = writeIdx - (tapDelay + humbuckerSpacing)
                var hr0 = hReadD.toInt()
                while (hr0 < 0) hr0 += maxDelay
                hr0 %= maxDelay
                val hr1 = (hr0 + 1) % maxDelay
                val hFrac = (hReadD - hReadD.toInt()).toFloat()
                val coilB = delayLine[hr0] + (delayLine[hr1] - delayLine[hr0]) * hFrac
                pickupSignal = (pickupSignal + coilB) * 0.707f
            }

            writeIdx = (writeIdx + 1) % maxDelay
            rawBuffer[n] = pickupSignal
        }

        // 3. Amplifier Non-Linear Saturation & Power Sag Stage
        val ampProcessed = applyAmpDrive(rawBuffer, voice, drive, sag, renderRate)

        // 4. FMV Tone Stack Filter Section
        val toneProcessed = applyToneStack(ampProcessed, voice, tone, renderRate)

        // 5. Reactive Speaker & Cabinet Impedance Network
        val cabProcessed = applyCabinetNetwork(toneProcessed, voice, cab, renderRate)
        val cabbedCopy = cabProcessed.copyOf()

        // 6. Anti-Aliasing (Tide Butterworth-8) and Punch Decimation
        if (!skipBandLimit) Tide.bandLimit(cabProcessed, renderRate)
        Dsp.normalizeByFold(cabProcessed, channels = 1)

        val punchAmount = Dsp.lin(mute, 0.25f, 0.75f)
        val decimated = Punch.applyOversampled(cabProcessed, punchAmount, RATE, channels = 1)
        Dsp.limitPeak(decimated)
        Dsp.fadeTail(decimated, ms = 4f, rate = RATE, channels = 1)

        return Stages(rawBuffer, ampProcessed, cabbedCopy, Snip(decimated, channels = 1, sampleRate = RATE))
    }

    private fun applyAmpDrive(
        input: FloatArray,
        voice: CoilProbeVoice,
        driveMacro: Float,
        sagMacro: Float,
        rate: Int,
    ): FloatArray {
        val total = input.size
        val output = FloatArray(total)

        val gainPre = when (voice) {
            CoilProbeVoice.CHUG -> Dsp.expMap(driveMacro, 2.0f, 35.0f)
            CoilProbeVoice.LEAD -> Dsp.expMap(driveMacro, 3.0f, 45.0f)
            CoilProbeVoice.FUZZBOX -> Dsp.expMap(driveMacro, 5.0f, 60.0f)
            CoilProbeVoice.MUTED -> Dsp.expMap(driveMacro, 1.5f, 18.0f)
            CoilProbeVoice.JANGLE -> Dsp.expMap(driveMacro, 0.8f, 6.0f)
        }

        var vSag = 0f
        val sagCharge = 1f - exp(-1.0 / (0.005 * rate)).toFloat()
        val sagDischarge = 1f - exp(-1.0 / (0.120 * rate)).toFloat()
        val sagDepth = sagMacro * 0.45f

        for (n in 0 until total) {
            val x = input[n] * gainPre

            // Dynamic grid sag calculation
            val absX = abs(x)
            if (absX > 1.0f) {
                vSag += sagCharge * (absX - 1.0f - vSag)
            } else {
                vSag -= sagDischarge * vSag
            }

            val biasedInput = x - (vSag * sagDepth)

            // Triode asymmetric transfer curve
            val saturated = if (biasedInput >= 0f) {
                tanh(biasedInput)
            } else {
                biasedInput / sqrt(1.0f + biasedInput * biasedInput)
            }

            output[n] = saturated
        }
        return output
    }

    private fun applyToneStack(
        input: FloatArray,
        @Suppress("UNUSED_PARAMETER") voice: CoilProbeVoice,
        toneMacro: Float,
        rate: Int,
    ): FloatArray {
        val total = input.size
        val output = FloatArray(total)

        // Bilinear FMV (Fender/Marshall/Vox) tonestack approximation
        val midDipFreq = Dsp.lin(toneMacro, 380f, 650f)
        val midDipDepth = Dsp.lin(1f - toneMacro, -12f, 2f)
        val trebleFreq = Dsp.expMap(toneMacro, 2500f, 6000f)
        val bassFreq = 120f

        val bassShelf = Dsp.Biquad().apply { lowShelf(bassFreq, 3.0f, rate) }
        val midNotch = Dsp.Biquad().apply { peaking(midDipFreq, midDipDepth, 1.4f, rate) }
        val trebleShelf = Dsp.Biquad().apply { highShelf(trebleFreq, (toneMacro - 0.5f) * 8f, rate) }

        for (n in 0 until total) {
            val s = input[n]
            output[n] = trebleShelf.process(midNotch.process(bassShelf.process(s)))
        }
        return output
    }

    private fun applyCabinetNetwork(
        input: FloatArray,
        @Suppress("UNUSED_PARAMETER") voice: CoilProbeVoice,
        cabMacro: Float,
        rate: Int,
    ): FloatArray {
        val total = input.size
        val output = FloatArray(total)

        // Cab morph: 0.0 = Open-back 1x12 Combo, 1.0 = Closed-back 4x12 Oversized Stack
        val thumpFreq = Dsp.lin(cabMacro, 110f, 78f)
        val thumpQ = Dsp.lin(cabMacro, 1.6f, 2.4f)
        val notchFreq = Dsp.lin(1f - cabMacro, 380f, 500f)
        val notchDepth = (1f - cabMacro) * -9.0f // Open-back rear-phase cancellation notch

        val coneThump = Dsp.Biquad().apply { peaking(thumpFreq, 6.0f, thumpQ, rate) }
        val openBackNotch = Dsp.Biquad().apply { peaking(notchFreq, notchDepth, 2.0f, rate) }

        // Cone breakup modes
        val breakup1 = Dsp.Biquad().apply { bandpass(2600f, 3.5f, rate) }
        val breakup2 = Dsp.Biquad().apply { bandpass(3750f, 4.0f, rate) }

        // Voice-coil inductance lowpass
        val voiceCoilLp = Dsp.OnePole(rate)
        val hfCutoff = Dsp.expMap(1f - (cabMacro * 0.3f), 4500f, 5800f)

        for (n in 0 until total) {
            val s = input[n]
            val thumping = openBackNotch.process(coneThump.process(s))
            val breakups = (breakup1.process(s) * 0.35f) + (breakup2.process(s) * 0.25f)
            val combined = thumping + breakups
            output[n] = voiceCoilLp.lp(combined, hfCutoff)
        }
        return output
    }
}
