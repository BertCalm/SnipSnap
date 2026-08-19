package com.snipsnap.mpc3

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MpcFormatsTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("formats").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private val acvsBytes = Acvs.write(
        AcvsHeader("3.6.0.1", "SerialisableProjectData", "json", "Linux"),
        "{}",
    )

    @Test
    fun `gzip magic means MPC 3`() {
        assertEquals(MpcFormat.MPC3_ACVS, MpcFormats.detect(acvsBytes))
    }

    @Test
    fun `an xml declaration means MPC 2`() {
        assertEquals(
            MpcFormat.MPC2_XML,
            MpcFormats.detect("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<MPCVObject>".toByteArray()),
        )
    }

    @Test
    fun `a BOM or leading whitespace does not hide the xml`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(MpcFormat.MPC2_XML, MpcFormats.detect(bom + "<?xml".toByteArray()))
        assertEquals(MpcFormat.MPC2_XML, MpcFormats.detect("\n  <?xml".toByteArray()))
    }

    @Test
    fun `anything else is unknown, not a guess`() {
        assertEquals(MpcFormat.UNKNOWN, MpcFormats.detect("RIFF....WAVE".toByteArray()))
        assertEquals(MpcFormat.UNKNOWN, MpcFormats.detect(ByteArray(0)))
        assertEquals(MpcFormat.UNKNOWN, MpcFormats.detect(byteArrayOf(0x1f)))
    }

    @Test
    fun `detects from a file by content, never by extension`() {
        // Same extension, different generations — the whole reason detection
        // is content-based.
        val a = File(temp, "project-a.xpj").apply { writeBytes(acvsBytes) }
        val b = File(temp, "project-b.xpj").apply { writeText("<?xml version=\"1.0\"?><MPCVObject/>") }

        assertEquals(MpcFormat.MPC3_ACVS, MpcFormats.detect(a))
        assertEquals(MpcFormat.MPC2_XML, MpcFormats.detect(b))
    }
}
