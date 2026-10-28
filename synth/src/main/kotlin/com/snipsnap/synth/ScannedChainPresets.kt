package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.sin

/** Complete, editable starting points. Every point and non-macro setting stays with the patch. */
object ScannedChainPresets {
    private val presets = listOf(
        ScannedChainPatch("Spring Pluck", ScannedChainVoice.PLUCK),
        ScannedChainPatch("Metallic Trace", ScannedChainVoice.METAL),
        ScannedChainPatch("Evolving Bloom", ScannedChainVoice.BLOOM),

        // The release covers most of Short Pluck, giving the rhythmic role an early decay.
        role("Short Pluck", ScannedChainVoice.PLUCK, midi = 60,
            motion = .85f, damp = .70f, bend = .18f,
            seconds = .80f, attack = .003f, release = .65f),
        role("Low Pluck", ScannedChainVoice.PLUCK, midi = 48,
            motion = .45f, damp = .40f, bend = .60f,
            seconds = 1.45f, attack = .006f, release = .80f),

        role("Bright Strike", ScannedChainVoice.METAL, midi = 72,
            motion = .95f, damp = .55f, bend = .85f,
            seconds = .90f, attack = .002f, release = .65f),
        role("Long Ring", ScannedChainVoice.METAL, midi = 60,
            motion = .70f, damp = .08f, bend = .35f,
            seconds = 2.40f, attack = .008f, release = .65f),

        role("Slow Bloom", ScannedChainVoice.BLOOM, midi = 48,
            motion = .80f, damp = .18f, bend = .25f, bloom = .95f,
            seconds = 3.80f, attack = .25f, release = 1.20f),
        // A strong initial root exercises the source-relative driver with a rounder material.
        role("Round Bloom", ScannedChainVoice.BLOOM, midi = 60,
            motion = 1f, damp = .24f, bend = .18f, bloom = .80f,
            seconds = 2.60f, attack = .025f, release = .70f,
            shape = ScannedChainShape.drawn(FloatArray(ScannedChain.SHAPE_SIZE) { i ->
                (.35 * sin(2 * PI * i / ScannedChain.SHAPE_SIZE)).toFloat()
            }, label = "Round bloom sine")),
    )

    private fun role(
        name: String, voice: ScannedChainVoice, midi: Int,
        motion: Float, damp: Float, bend: Float, bloom: Float = 0f,
        seconds: Float, attack: Float, release: Float,
        shape: ScannedChainShape = ScannedChainShape.factory(voice),
    ) = ScannedChainPatch(name, voice,
        macros = mapOf("MOTION" to motion, "DAMP" to damp, "BEND" to bend, "BLOOM" to bloom),
        shape = shape,
        config = ScannedChainConfig.forVoice(voice).copy(seconds = seconds,
            attackSeconds = attack, releaseSeconds = release),
        midi = midi,
    )

    fun all(): List<ScannedChainPatch> = presets
    fun forVoice(voice: ScannedChainVoice): List<ScannedChainPatch> = presets.filter { it.voice == voice }
}
