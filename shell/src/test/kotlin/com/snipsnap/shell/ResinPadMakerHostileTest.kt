package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.ResinVoice
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MAKE INSTRUMENT's edges: the name it lands under is a preset's, and the
 * two steppers are floats a slider hands over. Neither can make it write
 * outside the instruments folder, write something the shelf can't read,
 * or throw where the sheet has no answer.
 */
class ResinPadMakerHostileTest {

    private val spec = ResinPadMaker.spec(ResinVoice.LEAD, emptyMap(), 0.2f, 0.4f)

    /** One render, packaged under every name: the names are what is under test. */
    private val notes by lazy { ResinPadMaker.zoneMidis(spec).map { ResinPadMaker.renderZone(spec, it) } }

    @Test
    fun `any preset name packages inside the folder and reads back from the shelf`() {
        val root = Files.createTempDirectory("hostile-names").toFile()
        val shelf = File(root, "Instruments").apply { mkdirs() }
        try {
            val names = listOf(
                "", " ", ".", "..", "../../escape", "/etc/passwd", "C:\\\\Windows", "a/b\\\\c",
                "NUL", "CON", "Big Brass", "Big Brass", "Big Brass", "trailing.", " padded ",
                "tab\there", "new\nline", "nul\u0000byte", "émigré", "日本語", "🎹 keys", "<>:\"|?*",
                "x".repeat(60), "x".repeat(300),
            )
            for (base in names) {
                val name = OneNote.freshName(shelf, base)
                ResinPadMaker.export(name, spec, notes, shelf)
                assertTrue(File(shelf, "$name${InstrumentStore.SUFFIX}").isFile, "'$base' as '$name': no sidecar")
            }
            val listed = InstrumentStore.list(shelf)
            assertEquals(names.size, listed.size, "the shelf reads back every one")
            // Nothing landed beside the shelf, whatever a name said.
            assertEquals(listOf("Instruments"), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a stepper handing over a float that is not a fraction still makes a spec`() {
        for (f in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 2f, -0f)) {
            val s = ResinPadMaker.spec(ResinVoice.BASS, emptyMap(), f, f)
            assertTrue(s.attackSeconds.isFinite() && s.releaseSeconds.isFinite(), "fraction $f made $s")
        }
    }
}
