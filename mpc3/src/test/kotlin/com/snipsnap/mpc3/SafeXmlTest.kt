package com.snipsnap.mpc3

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
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
        // File.toURI() percent-encodes whatever the raw path needs
        // encoding (a space in the temp dir, say) - string-concatenating
        // "file://" + absolutePath would not, and a malformed SYSTEM URI
        // would prove nothing about SafeXml either way.
        val xxe = """
            <!DOCTYPE Program [<!ENTITY leak SYSTEM "${secret.toURI()}">]>
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

    /**
     * `getFeature` can itself throw for a name a JAXP implementation
     * doesn't recognize - exactly the case `SafeXml.tryFeature` is built
     * to tolerate on the `set` side. Asserting here only when `get`
     * succeeds keeps this test honest about what it can prove on a given
     * implementation, instead of failing this test over the very
     * portability gap `SafeXml` exists to survive.
     */
    private fun assertFeatureIfReadable(dbf: DocumentBuilderFactory, name: String, expected: Boolean) {
        val actual = try {
            dbf.getFeature(name)
        } catch (_: Exception) {
            return
        }
        assertEquals(expected, actual, "$name")
    }

    /** Same tolerance as [assertFeatureIfReadable], for the property-style guards. */
    private fun assertAttributeIfReadable(dbf: DocumentBuilderFactory, name: String, expected: Any) {
        val actual = try {
            dbf.getAttribute(name)
        } catch (_: Exception) {
            return
        }
        assertEquals(expected, actual, name)
    }

    @Test
    fun `newFactory sets every guard, not just the first`() {
        // The regression this guards against: an earlier version set only
        // disallow-doctype-decl inside a try/catch that swallowed failure
        // and stopped there, so one unrecognized feature name would have
        // left the factory fully unprotected. Checking the factory's own
        // state (not reimplementing SafeXml's logic) proves every guard
        // actually landed on the object callers get back - the behavioral
        // DOCTYPE/XXE tests above are what actually prove protection;
        // this is a second, more direct line of evidence on top.
        val dbf = SafeXml.newFactory()
        assertFeatureIfReadable(dbf, "http://apache.org/xml/features/disallow-doctype-decl", true)
        assertFeatureIfReadable(dbf, "http://xml.org/sax/features/external-general-entities", false)
        assertFeatureIfReadable(dbf, "http://xml.org/sax/features/external-parameter-entities", false)
        assertFeatureIfReadable(dbf, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        assertFeatureIfReadable(dbf, javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
        assertAttributeIfReadable(dbf, javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
        assertAttributeIfReadable(dbf, javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        assertFalse(dbf.isXIncludeAware)
        assertFalse(dbf.isExpandEntityReferences)
        assertFalse(dbf.isNamespaceAware)
    }
}
