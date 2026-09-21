package com.snipsnap.mpc3

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The one hardened `DocumentBuilderFactory` this codebase parses untrusted
 * XML with — an `.xpm` program, an MPC 2 project, anything that crossed a
 * card, a zip or a share sheet rather than something we wrote ourselves.
 *
 * `disallow-doctype-decl` is the complete fix (no DOCTYPE, no XXE), but
 * three sites here used to set only that one feature inside a try/catch
 * that swallowed failure and stopped — so a JAXP implementation that
 * doesn't recognize that exact feature name would silently parse the file
 * fully unprotected. Every guard below is now tried independently, so one
 * unsupported name never costs the rest: the three OWASP-listed feature
 * fallbacks (no external general/parameter entities, no external DTD)
 * close the same door a different way, secure processing caps entity
 * expansion generally, the two `ACCESS_EXTERNAL_*` properties are the
 * belt an implementation that ignores every feature name above still
 * wears, and the three direct property setters below get the identical
 * tolerance.
 *
 * That last part is not decoration. On a real Android device, `.xpm`
 * export's own READ BACK self-check failed with `THIS PARSER DOES NOT
 * SUPPORT SPECIFICATION "UNKNOWN" VERSION "0.0"` — traced empirically
 * (one guard tried in isolation at a time, live on-device, since desktop
 * Xerces never reproduces this) to `setXIncludeAware(false)`: Android's
 * JAXP implementation throws `UnsupportedOperationException` — not
 * `ParserConfigurationException`, and not from `setFeature`/`setAttribute`
 * either, both of which "succeed" — the moment `newDocumentBuilder()` is
 * later called on a factory carrying that call. `isXIncludeAware` already
 * defaults to `false` everywhere (XInclude processing is opt-in), so this
 * call was always redundant against the default — it just happened to be
 * the one redundant call an Android JAXP implementation refuses outright.
 * Wrapping it in the same tolerant [trySetProperty] every other guard here
 * already uses is the fix: skip a setter a given implementation refuses,
 * keep every other guard intact, exactly as [tryFeature]/[tryAttribute]
 * already do for features and JAXP-1.5 attributes.
 */
object SafeXml {

    private fun tryFeature(dbf: DocumentBuilderFactory, name: String, value: Boolean) {
        try {
            dbf.setFeature(name, value)
        } catch (_: Exception) {
            // Not every JAXP implementation recognizes every feature name;
            // the other guards here still hold.
        }
    }

    private fun tryAttribute(dbf: DocumentBuilderFactory, name: String, value: Any) {
        try {
            dbf.setAttribute(name, value)
        } catch (_: Exception) {
            // Same tolerance as tryFeature, for the property-style guards.
        }
    }

    /**
     * Same tolerance as [tryFeature]/[tryAttribute], for the three direct
     * boolean setters (`isXIncludeAware` etc). These aren't JAXP feature/
     * attribute names — they're plain property setters on the factory
     * itself — but an implementation can still refuse one at
     * `newDocumentBuilder()` time (Android's does, for `isXIncludeAware`;
     * see this object's own doc), and a refusal here must cost that one
     * guard alone, never every guard set before it.
     */
    private fun trySetProperty(set: () -> Unit) {
        try {
            set()
        } catch (_: Exception) {
        }
    }

    fun newFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        tryFeature(this, "http://apache.org/xml/features/disallow-doctype-decl", true)
        tryFeature(this, "http://xml.org/sax/features/external-general-entities", false)
        tryFeature(this, "http://xml.org/sax/features/external-parameter-entities", false)
        tryFeature(this, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        tryFeature(this, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        // Empty string is JAXP's own spelling of "no external access
        // allowed" for these two - not a wildcard, the opposite of one.
        tryAttribute(this, XMLConstants.ACCESS_EXTERNAL_DTD, "")
        tryAttribute(this, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        trySetProperty { isXIncludeAware = false }
        trySetProperty { isExpandEntityReferences = false }
        trySetProperty { isNamespaceAware = false }
    }

    /** The hardened [DocumentBuilder] every real call site here uses. */
    fun newBuilder(): DocumentBuilder = newFactory().newDocumentBuilder()
}
