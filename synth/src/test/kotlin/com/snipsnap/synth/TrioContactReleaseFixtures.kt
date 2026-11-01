package com.snipsnap.synth

/** Published PCM24 files from the current/model-6 side of the accepted contact audition. */
internal object TrioContactReleaseFixtures {
    const val ROUND = "trio-contact-dbd5a31d3ccc64a7"
    data class Replay(val drama: Float, val touch: Float, val sha256: String)

    // Full model-6 native renders at C4/velocity1/seed47010, followed by .25 monitor gain.
    // These are complete WavWriter PCM24 file hashes, including the WAV header.
    val modelSix = listOf(
        Replay(.5f, .8f, "83ada5f303e1d696b8ef514f5fb777b0c1d72b2fe5e93b05ab4980a896a3a9d9"),
        Replay(.5f, .9f, "5cedff3fd24a22c11497cdb32f53c332cbec3b4f3937e20697198f332057b0fb"),
        Replay(.5f, 1f, "738ff11a598bc8e844fc5173b5c0792b41d1672851cb5ca3cf9376b5aa061fd0"),
        Replay(1f, .8f, "8ec718b3bb0d14c6072044e1ad61b726c240fcc66ca39b0189e895e7d1aecc3a"),
        Replay(1f, .9f, "01e6588356f3049017b2418f01e4456047f2dffe26dcac78e291ca5292dbad0f"),
        Replay(1f, 1f, "95cf528df5ce9f406b270481dde5afa30593cc05eed9debb63829c2332f3f017"),
    )
}
