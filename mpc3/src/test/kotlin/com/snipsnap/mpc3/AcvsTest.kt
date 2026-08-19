package com.snipsnap.mpc3

import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcvsTest {

    private val header = AcvsHeader(
        firmware = "3.7.0.56",
        objectType = "SerialisableProjectData",
        encoding = "json",
        platform = "Linux",
    )

    @Test
    fun `round-trips a json container`() {
        val payload = """{"data": {"version": 28, "tracks": []}}"""
        val container = Acvs.read(Acvs.write(header, payload))

        assertEquals(header, container.header)
        assertEquals(payload, container.payloadText)
        val data = (container.payload as JsonValue.Obj).entries["data"] as JsonValue.Obj
        assertEquals(28, (data.entries["version"] as JsonValue.Num).value.toInt())
    }

    @Test
    fun `the bytes really are gzip`() {
        val bytes = Acvs.write(header, "{}")
        assertTrue(Acvs.isGzip(bytes))
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)
    }

    @Test
    fun `rejects non-gzip input with the bytes it saw`() {
        val e = assertFailsWith<AcvsException> { Acvs.read("<?xml version=\"1.0\"?>".toByteArray()) }
        assertTrue("3c 3f" in e.message!!, e.message)
    }

    @Test
    fun `rejects a wrong magic line`() {
        val bytes = Acvs.write(header, "{}")
        // Re-gzip with a corrupted first line.
        val text = java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes()
            .toString(Charsets.UTF_8).replaceFirst("ACVS", "BCVS")
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(text.toByteArray()) }

        assertFailsWith<AcvsException> { Acvs.read(out.toByteArray()) }
    }

    @Test
    fun `rejects a truncated header`() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write("ACVS\n3.7\njson".toByteArray()) }
        assertFailsWith<AcvsException> { Acvs.read(out.toByteArray()) }
    }

    @Test
    fun `a non-json encoding keeps the raw payload and parses nothing`() {
        val binaryHeader = header.copy(encoding = "binary")
        val container = Acvs.read(Acvs.write(binaryHeader, "not json at all"))
        assertNull(container.payload)
        assertEquals("not json at all", container.payloadText)
    }

    @Test
    fun `bad json in a json container is a clear error`() {
        assertFailsWith<AcvsException> { Acvs.read(Acvs.write(header, "{oops")) }
    }

    @Test
    fun `tolerates CRLF header lines`() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use {
            it.write("ACVS\r\n3.6.0.1\r\nSerialisableProjectData\r\njson\r\nLinux\r\n{}".toByteArray())
        }
        val container = Acvs.read(out.toByteArray())
        assertEquals("3.6.0.1", container.header.firmware)
    }

    @Test
    fun `newlines inside the payload survive the 5-line split`() {
        val payload = "{\n  \"data\": {\n    \"version\": 28\n  }\n}"
        assertEquals(payload, Acvs.read(Acvs.write(header, payload)).payloadText)
    }

    @Test
    fun `the INT64_MAX sentinel reads approximately, as documented`() {
        val container = Acvs.read(Acvs.write(header, """{"length": 9223372036854775807}"""))
        val length = ((container.payload as JsonValue.Obj).entries["length"] as JsonValue.Num).value
        assertTrue(abs(length - Acvs.INT64_MAX_APPROX) < 1e4)
    }
}
