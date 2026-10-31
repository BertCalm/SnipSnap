package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StrikeDemoTest {
    @Test
    fun `saved starter plays the named effect at each thumb position`() {
        val dir = kotlin.io.path.createTempDirectory("strike-fx").toFile()
        try {
            val started = System.nanoTime()
            val kit = StarterKits.byId("strike-fx")!!.render("STRIKE FX", dir)
            println("STRIKE FX creation including WAV writes: ${(System.nanoTime() - started) / 1_000_000} ms")
            assertEquals(kit, KitStore.load(dir), "labels and layers survive saving")
            assertEquals(4, kit.pads.size)
            for (pad in kit.pads) {
                val layers = pad.velocityLayers
                assertEquals(listOf("DRY", "REVERB", "ECHO"), layers.map { it.displayName })
                val wavs = layers.associate { it.sampleFile to WavReader.read(File(dir, it.sampleFile)) }
                for ((index, amount) in listOf(0f, 0.5f, 1f).withIndex()) {
                    val touch = StrikeStrip.hit(1f, true, amount, false)
                    val played = assertNotNull(PadHit.resolve(pad, touch.velocity, 0, touch.gainVelocity) {
                        wavs[it]?.frameCount?.toLong()
                    })
                    assertEquals(layers[index].sampleFile, played.sampleFile)
                    assertEquals(wavs.getValue(played.sampleFile).frameCount.toLong(), played.endFrameExclusive,
                        "the entire FX tail must play")
                    assertEquals(pad.level, played.gainLeft)
                    assertEquals(index, StrikeStrip.layerHit(pad, touch.velocity).selected)
                }
                val counts = (0..100).map { StrikeStrip.hit(1f, true, it / 100f, false) }
                    .groupingBy { PadHit.layerIndex(pad, it.velocity) }.eachCount()
                assertTrue(counts.values.all { it >= 32 }, "each effect has roughly a third of the thumb travel")
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `feedback agrees with playback at zone gaps and on unnamed and unlayered pads`() {
        val pad = KitPad(7, "echo.wav", velocityLayers = listOf(
            KitLayer("dry.wav", 1, 31, "DRY"), KitLayer("echo.wav", 96, 127, "ECHO"),
        ))
        for (midi in 0..127) {
            val velocity = midi / 127f
            val feedback = StrikeStrip.layerHit(pad, velocity)
            val played = PadHit.resolve(pad, velocity, 0) { 100L }!!
            assertEquals(played.sampleFile, pad.velocityLayers[feedback.selected].sampleFile)
        }
        assertEquals(listOf("SINGLE"), StrikeStrip.layerHit(KitPad(1, "a.wav"), 1f).labels)
        assertEquals(listOf("LAYER 1", "LAYER 2"), StrikeStrip.layerHit(
            pad.copy(velocityLayers = pad.velocityLayers.map { it.copy(displayName = null) }), 1f,
        ).labels)
    }
}
