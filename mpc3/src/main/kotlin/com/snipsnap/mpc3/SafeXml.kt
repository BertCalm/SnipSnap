package com.snipsnap.mpc3

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
 * fully unprotected. Each guard below is now tried independently: the
 * three OWASP-listed fallbacks (no external general/parameter entities, no
 * external DTD) still close the door even if `disallow-doctype-decl`
 * itself isn't recognized, and one unsupported name never costs the rest.
 */
object SafeXml {

    private fun tryFeature(dbf: DocumentBuilderFactory, name: String, value: Boolean) {
        try {
            dbf.setFeature(name, value)
        } catch (_: Exception) {
            // Not every JAXP implementation recognizes every feature name;
            // the other guards below still hold.
        }
    }

    fun newFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        tryFeature(this, "http://apache.org/xml/features/disallow-doctype-decl", true)
        tryFeature(this, "http://xml.org/sax/features/external-general-entities", false)
        tryFeature(this, "http://xml.org/sax/features/external-parameter-entities", false)
        tryFeature(this, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
        isNamespaceAware = false
    }
}
