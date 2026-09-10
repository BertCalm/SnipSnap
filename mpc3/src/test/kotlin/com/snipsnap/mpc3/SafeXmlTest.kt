package com.snipsnap.mpc3

import java.io.File
import java.io.StringReader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.xml.sax.InputSource

/**
 * [SafeXml] proven empirically, not assumed: this is the one factory
 * `MpcXRay`, `MpcDiff` and `XpnImporter` all parse untrusted XML through,
 * so its refusal is what actually protects them - a passing test here is
 * the only evidence any of the three refuse a hostile file.
 */
class SafeXmlTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("safexml").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun parse(xml: String) =
        SafeXml.newFactory().newDocumentBuilder().parse(InputSource(StringReader(xml)))

    @Test
    fun `an ordinary document parses as before`() {
        val doc = parse("<Program><Name>Funk Kit</Name></Program>")
        assertEquals("Funk Kit", doc.documentElement.getElementsByTagName("Name").item(0).textContent)
    }

    @Test
    fun `a DOCTYPE is refused, not silently parsed`() {
        assertFailsWith<Exception> {
            parse("<!DOCTYPE Program [<!ENTITY x \"boom\">]><Program>&x;</Program>")
        }
    }

    @Test
    fun `an XXE payload never puts a local file's contents in the tree`() {
        val secret = File(temp, "secret.txt").apply { writeText("do not read me") }
        val xxe = """
            <!DOCTYPE Program [<!ENTITY leak SYSTEM "file://${secret.absolutePath}">]>
            <Program><Name>&leak;</Name></Program>
        """.trimIndent()
        // Either the DOCTYPE itself is refused (the expected path) or, on a
        // hypothetical JAXP implementation that doesn't recognize that one
        // feature name, the fallback guards below still hold: the parse
        // must not come back holding the secret file's bytes.
        val leaked = try {
            parse(xxe).documentElement.getElementsByTagName("Name").item(0).textContent
        } catch (e: Exception) {
            ""
        }
        assertFalse("do not read me" in leaked, "the local file's contents must never reach the parsed tree: $leaked")
    }

    @Test
    fun `newFactory sets every guard, not just the first`() {
        // The regression this guards against: an earlier version set only
        // disallow-doctype-decl inside a try/catch that swallowed failure
        // and stopped there, so one unrecognized feature name would have
        // left the factory fully unprotected. Checking the factory's own
        // state (not reimplementing SafeXml's logic) proves every guard
        // actually landed on the object callers get back.
        val dbf = SafeXml.newFactory()
        assertEquals(true, dbf.getFeature("http://apache.org/xml/features/disallow-doctype-decl"))
        assertEquals(false, dbf.getFeature("http://xml.org/sax/features/external-general-entities"))
        assertEquals(false, dbf.getFeature("http://xml.org/sax/features/external-parameter-entities"))
        assertEquals(false, dbf.getFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd"))
        assertFalse(dbf.isXIncludeAware)
        assertFalse(dbf.isExpandEntityReferences)
        assertFalse(dbf.isNamespaceAware)
    }
}
