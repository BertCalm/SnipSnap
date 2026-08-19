package com.snipsnap.mpc3

import java.io.File

/**
 * Which generation of MPC file this is.
 *
 * Both generations share extensions (`.xpj` projects especially), so detection
 * is content-based, never by filename: gzip magic means the MPC 3 ACVS
 * container, an XML declaration means the MPC 2 family.
 */
enum class MpcFormat { MPC2_XML, MPC3_ACVS, UNKNOWN }

object MpcFormats {

    fun detect(file: File): MpcFormat {
        val head = ByteArray(16)
        val n = file.inputStream().use { it.read(head) }
        return detect(if (n <= 0) ByteArray(0) else head.copyOf(n))
    }

    fun detect(bytes: ByteArray): MpcFormat {
        if (Acvs.isGzip(bytes)) return MpcFormat.MPC3_ACVS

        // MPC 2 files open with an XML declaration, possibly behind a UTF-8 BOM.
        var i = 0
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            i = 3
        }
        while (i < bytes.size && (bytes[i] == ' '.code.toByte() || bytes[i] == '\n'.code.toByte() ||
                bytes[i] == '\r'.code.toByte() || bytes[i] == '\t'.code.toByte())
        ) {
            i++
        }
        val xml = "<?xml".toByteArray(Charsets.US_ASCII)
        if (bytes.size - i >= xml.size && xml.indices.all { bytes[i + it] == xml[it] }) {
            return MpcFormat.MPC2_XML
        }
        return MpcFormat.UNKNOWN
    }
}
