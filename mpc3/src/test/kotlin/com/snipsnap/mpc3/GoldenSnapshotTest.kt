package com.snipsnap.mpc3

import com.snipsnap.xpm.DrumProgram
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.Pad
import com.snipsnap.xpm.VelocityLayer
import com.snipsnap.xpm.XpmWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden snapshots of the format writers. Every writer claims deterministic
 * output; this pins the *exact* text of each one for a fixed program, so a
 * refactor that silently shifts a field, a default, or the byte layout fails
 * here loudly instead of shipping a subtly-wrong file to the hardware.
 *
 * The inputs are fixed programs, not synthesized kits, so the snapshot moves
 * only when a **writer** changes — which is exactly when a human should look
 * and, if the change is intended, refresh the golden (delete the file and
 * re-run, or set `UPDATE_GOLDENS`).
 *
 * The small MPC 2 `.xpm` is pinned as full text — a diff shows exactly what
 * moved. The MPC 3 payloads carry all 128 slots fully formed (megabytes of
 * boilerplate), so those are pinned by SHA-256 digest: any drift still flips
 * the hash, and to see *what* changed a dev regenerates and diffs locally.
 */
class GoldenSnapshotTest {

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val goldenDir = File("../reference/golden/snapshots")

    /** A fixed drum program: a two-pad kit, one plain pad and one layered. */
    private fun drumProgram() = DrumProgram(
        name = "Golden Kit",
        pads = listOf(
            Pad("A01_Kick_01", frameCount = 22_050L, level = 0.8f, pan = 0.5f, muteGroup = 0, color = 0xE8542E),
            Pad(
                "A02_Snare_01", frameCount = 18_000L, level = 0.7f, pan = 0.4f,
                tuneCoarse = 2, muteGroup = 3, color = 0xFFC41F,
                velocityLayers = listOf(
                    VelocityLayer("A02_Snare_soft", 12_000L, 0, 63),
                    VelocityLayer("A02_Snare_01", 18_000L, 64, 127),
                ),
            ),
        ),
    )

    private fun keygroupProgram() = KeygroupProgram(
        name = "Golden Keys",
        keygroups = listOf(
            Keygroup(0, 127, rootNote = 60, layers = listOf(VelocityLayer("Keys_C4", 44_100L, 0, 127))),
        ),
    )

    private fun clip() = Mpc3Clip(
        "Golden Groove", 1,
        listOf(Mpc3Note(36, 0, 0.9f), Mpc3Note(38, 960, 0.85f)),
    )

    @Test
    fun `every writer's text matches its committed golden`() {
        val track = Mpc3TrackWriter()
        // The small MPC 2 program is pinned verbatim; the big MPC 3 payloads
        // by digest.
        val fullText = mapOf("golden-kit.xpm" to XpmWriter().write(drumProgram()))
        val digests = mapOf(
            "golden-track.xtd.sha256" to track.payloadText(drumProgram(), clips = listOf(clip())),
            "golden-keygroup.xty.sha256" to track.keygroupPayloadText(keygroupProgram()),
            "golden-project.xpj.sha256" to Mpc3ProjectWriter().payloadText(
                "Golden Session",
                listOf(Mpc3ProjectTrack.Drum(drumProgram(), clips = listOf(clip()))),
                tempoBpm = 92f,
            ),
        )
        val update = System.getenv("UPDATE_GOLDENS") != null

        fun check(name: String, actual: String) {
            val golden = File(goldenDir, name)
            if (update || !golden.isFile) {
                golden.parentFile.mkdirs()
                golden.writeText(actual, Charsets.UTF_8)
                return
            }
            assertEquals(
                golden.readText(Charsets.UTF_8), actual,
                "$name drifted from its golden - if the writer change is intended, " +
                    "delete reference/golden/snapshots/$name and re-run (or set UPDATE_GOLDENS)",
            )
        }

        fullText.forEach { (name, text) -> check(name, text) }
        digests.forEach { (name, payload) -> check(name, sha256(payload)) }
    }
}
