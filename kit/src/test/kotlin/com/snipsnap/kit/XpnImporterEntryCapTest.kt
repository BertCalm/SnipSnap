package com.snipsnap.kit

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The entry list is materialized before any sample write, so the byte
 * budget alone never sees an archive of millions of empty entries.
 * The count is refused as it is read, at the same ceiling the other
 * archive doors hold.
 */
class XpnImporterEntryCapTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("xpncap").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `an archive declaring entries past the ceiling is refused while counting`() {
        val xpn = File(temp, "many.xpn")
        ZipOutputStream(xpn.outputStream()).use { zip ->
            for (i in 0..XpnImporter.MAX_ENTRIES) {
                zip.putNextEntry(ZipEntry("e$i.txt"))
                zip.closeEntry()
            }
        }
        val e = assertFailsWith<IllegalArgumentException> { XpnImporter.import(xpn, File(temp, "out")) }
        assertTrue(e.message!!.contains("${XpnImporter.MAX_ENTRIES}"), "message names the ceiling: ${e.message}")
    }
}
