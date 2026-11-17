package com.snipsnap.shell

import com.snipsnap.audio.*
import com.snipsnap.json.*
import java.nio.file.Files
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class ParadeStudyInputTest {
    @Test fun `pinned audio loads but one changed PCM byte refuses`() {
        val root = Files.createTempDirectory("parade-input").toFile()
        try {
            val file = File(root, "hit.wav")
            WavWriter.write(file, Snip(FloatArray(1024) { if (it == 20) .5f else 0f }, 1, 44100), WavWriter.BitDepth.PCM_16)
            val bytes = file.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val spec = JsonValue.Obj(mapOf("file" to JsonValue.Str("hit.wav"), "sha256" to JsonValue.Str(hash)))
            assertEquals(1024, ParadeKitTransport.verifiedWav(root, spec).frameCount)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            val error = assertFailsWith<IllegalArgumentException> { ParadeKitTransport.verifiedWav(root, spec) }
            assertTrue(error.message.orEmpty().contains("Study PCM hash mismatch"))
        } finally { root.deleteRecursively() }
    }
}
