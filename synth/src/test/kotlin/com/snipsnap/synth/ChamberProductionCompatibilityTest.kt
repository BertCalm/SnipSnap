package com.snipsnap.synth

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChamberProductionCompatibilityTest {
    @Test fun `accepted model three production recordings retain every Float32 bit`() {
        // Captured from the accepted renderer before the finite-release hot-loop
        // changes. WAV16 goldens cannot reveal changes below their quantization.
        val accepted = listOf(
            Triple(ChamberVoice.HYBRID, 48, "d7fe98fc9bddad7048af84063c59572d615836823d7db6d1a0cf2256a98ba4bc"),
            Triple(ChamberVoice.HYBRID, 60, "97d6ab64dcd018211781320d2b598d1fc7f048fcc799d34bca53f7bb9c1acd8d"),
            Triple(ChamberVoice.AQUARIUM, 48, "42bb8407c0a6f07371b879363274285ef4336cb3f636e0c393669140618ca9bc"),
            Triple(ChamberVoice.AQUARIUM, 60, "77e67cb99d42c55c1f4c56761c87c9fbe358b5fc67a2550f64f97d02a4d57d0a"),
        )
        for ((voice, midi, expected) in accepted) {
            val samples = Chamber.render(voice, midi = midi, model = 3).samples
            val bytes = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES)
            samples.forEach { bytes.putInt(it.toRawBits()) }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            assertEquals(expected, hash, "$voice MIDI$midi changed the accepted production recording")
        }
    }
}
