package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pinned before coefficient caching and operator reuse; checks Float bits rather than rounded WAV samples. */
class MurmurRenderCompatibilityTest {
    @Test
    fun `held coefficient caching and operator reuse retain model arithmetic across registers and motion`() {
        for (fixture in fixtures) {
            val macros = Murmur.defaults(fixture.voice) + mapOf("MOTION" to fixture.motion, "HOLD" to 1f)
            val samples = Murmur.render(fixture.voice, macros, midi = fixture.midi, velocity = .83f,
                config = Murmur.configurationFor(fixture.voice, model = fixture.model)).samples
            val bytes = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) bytes.putFloat(sample)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            assertEquals(fixture.hash, hash, "${fixture.voice} model ${fixture.model}, MIDI ${fixture.midi}, MOTION ${fixture.motion}")
        }
    }

    private data class Fixture(val voice: MurmurVoice, val model: Int, val midi: Int, val motion: Float, val hash: String)

    companion object {
        // Independent baseline: unchanged engine at 84471534a, captured before optimization.
        // Full scratch evidence also compared 216 finite/held renders at C2/C4/C7 and three motions.
        private val fixtures = listOf(
            Fixture(MurmurVoice.BASS, 1, 36, 0f, "1db91aa30a574a4420a8ae4f8a2c0bc5696d178240b5f7f30e2867e1fc2d6ebd"),
            Fixture(MurmurVoice.KEY, 1, 60, 0.25f, "999fc1faa0de7405a1747992b0a6544b2f80226c2e092f64ae0a01b7f13c3b41"),
            Fixture(MurmurVoice.PLUCK, 1, 96, 1f, "5a795bd763cf94dc2cca7be97fbc0bf6eda6a49f45f1c277d3ac98c27992bf87"),
            Fixture(MurmurVoice.LEAD, 1, 36, 0f, "9916e8d8fd5414f66dbb1fdaad95f39d963612a2076612c2db3b5697cd02fff1"),
            Fixture(MurmurVoice.CHOIR, 1, 60, 0.25f, "32809f1fa0ebcc219db9781a878f9275c4ffec7cfe2da95a08ffdd41c3c05ad5"),
            Fixture(MurmurVoice.METAL, 1, 96, 1f, "cd77153ccef90bfd71b154f24cf2c73087a008a637667d003be379efc3ad6387"),
            Fixture(MurmurVoice.BASS, 2, 36, 0.25f, "fa57eae560942e91ed430156df5fc8decbabcc1e0127de4108e04b0d0b1ceb20"),
            Fixture(MurmurVoice.KEY, 2, 60, 1f, "9db1b2be7c1691335f8d7d91e5d659d4a5fd971396d51e33c0d8f779863808f7"),
            Fixture(MurmurVoice.PLUCK, 2, 96, 0f, "2f48af4d61ab0b60bba5353a2d1a0686b22c0ef15fe658e38df42b7d675eb9eb"),
            Fixture(MurmurVoice.LEAD, 2, 36, 0.25f, "8159f501a505c096695a9ae9b70ee7833904c7621065be9bae2fa0b3d42d7f43"),
            Fixture(MurmurVoice.CHOIR, 2, 60, 1f, "a29382653cc299e8aa2ea3f5364a39c9daae618d5df3988e642d24457109c8eb"),
            Fixture(MurmurVoice.METAL, 2, 96, 0f, "f500aebd196af448f1af5f5a3dd3ef34764a3f50bf3410939149a40838142bfd"),
        )
    }
}
