package com.snipsnap.mpc3

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The MPC 3 container: gzip around a five-line text header and a payload.
 *
 * ```
 * ACVS                        ← magic
 * 3.7.0.56                    ← firmware that wrote the file
 * SerialisableProjectData     ← object type
 * json                        ← payload encoding
 * Linux                       ← platform (standalone hardware)
 * <payload>
 * ```
 *
 * Structure per the community `.xpj` knowledge base (see docs/MPC3_FORMAT.md,
 * which also carries its reliability caveats). The container is generic across
 * MPC 3 object types — line 3 names what's inside — which is exactly why this
 * reader exists before we own a single real file: the moment a Live III
 * program or project lands, it gets dissected instead of stared at.
 *
 * One documented sharp edge: MPC 3 uses `9223372036854775807` (INT64_MAX) as
 * an "unbounded" sentinel. JSON numbers here are doubles, so that value reads
 * back approximately; compare against [INT64_MAX_APPROX], never exact.
 */
data class AcvsHeader(
    val firmware: String,
    val objectType: String,
    val encoding: String,
    val platform: String,
) {
    companion object {
        const val MAGIC = "ACVS"
        const val ENCODING_JSON = "json"
    }
}

data class AcvsContainer(
    val header: AcvsHeader,
    /** The raw payload text, exactly as stored after the header's newline. */
    val payloadText: String,
    /** Parsed payload when the header says json; null for other encodings. */
    val payload: JsonValue?,
)

class AcvsException(message: String) : RuntimeException(message)

object Acvs {

    /** What INT64_MAX looks like after a trip through a double. */
    const val INT64_MAX_APPROX: Double = 9.223372036854776E18

    fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    fun read(file: File): AcvsContainer = read(file.readBytes())

    fun read(bytes: ByteArray, inflateLimit: Long = LimitedRead.DEFAULT_LIMIT): AcvsContainer {
        if (!isGzip(bytes)) {
            throw AcvsException("not an MPC 3 container: missing gzip magic (found ${preview(bytes)})")
        }
        // Untrusted gzip: inflate through a ceiling, never unbounded - a few
        // KB of hostile input can otherwise inflate to gigabytes.
        val text = try {
            GZIPInputStream(bytes.inputStream()).use {
                LimitedRead.bytes(it, inflateLimit, "ACVS container").toString(Charsets.UTF_8)
            }
        } catch (e: LimitedRead.TooLargeException) {
            throw AcvsException(e.message ?: "ACVS container too large")
        } catch (e: java.util.zip.ZipException) {
            throw AcvsException("corrupt gzip in ACVS container: ${e.message}")
        } catch (e: java.io.EOFException) {
            throw AcvsException("truncated gzip in ACVS container")
        }
        val parts = text.split("\n", limit = 6)
        if (parts.size < 6) throw AcvsException("truncated ACVS header: ${parts.size - 1} of 5 lines")

        val lines = parts.take(5).map { it.trimEnd('\r') }
        if (lines[0] != AcvsHeader.MAGIC) {
            throw AcvsException("bad ACVS magic: \"${lines[0]}\"")
        }
        val header = AcvsHeader(
            firmware = lines[1],
            objectType = lines[2],
            encoding = lines[3],
            platform = lines[4],
        )
        val payloadText = parts[5]
        val payload = if (header.encoding == AcvsHeader.ENCODING_JSON) {
            try {
                Json.parse(payloadText)
            } catch (e: com.snipsnap.json.JsonException) {
                throw AcvsException("ACVS payload is not valid JSON: ${e.message}")
            }
        } else {
            null
        }
        return AcvsContainer(header, payloadText, payload)
    }

    /**
     * Assemble a container. This proves the *container* mechanics round-trip;
     * whether an MPC accepts a payload we authored is the open hardware
     * question, and nothing here claims otherwise.
     */
    fun write(header: AcvsHeader, payloadText: String): ByteArray {
        val text = listOf(
            AcvsHeader.MAGIC,
            header.firmware,
            header.objectType,
            header.encoding,
            header.platform,
            payloadText,
        ).joinToString("\n")
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun preview(bytes: ByteArray): String =
        bytes.take(8).joinToString(" ") { "%02x".format(it) }
}
