package com.snipsnap.mpc3

import javax.xml.XMLConstants
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
 * expansion generally, and the two `ACCESS_EXTERNAL_*` properties are the
 * belt an implementation that ignores every feature name above still
 * wears — they block external DTD/schema fetches at a layer feature
 * toggles don't reach at all.
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
        isXIncludeAware = false
        isExpandEntityReferences = false
        isNamespaceAware = false
    }
}
