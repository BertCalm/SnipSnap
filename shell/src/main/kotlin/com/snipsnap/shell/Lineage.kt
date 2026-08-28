package com.snipsnap.shell

import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The crate's genealogy (HH3). Every verb in the product stamps
 * provenance — chop names its file, the dig its song and timestamp,
 * resample its parent and generation, merge both parents, import its
 * archive — and this walks those stamps into a family tree: kit →
 * parent kits (resolved by name through the crate root, so the walk
 * crosses folders) → down to the terminal origins. Deterministic: the
 * same crate always draws the same tree.
 */
object Lineage {

    /** One tree node: a kit, or a terminal origin line. */
    data class Node(val label: String, val children: List<Node> = emptyList())

    /**
     * Trace [kitDir]'s ancestry. Parent kits are resolved by name among
     * the kits under [root] (default: the kit's own parent folder — the
     * crate its siblings live in); a parent that isn't there is still
     * shown, honestly labeled. Cycles and diamonds print once.
     */
    fun trace(kitDir: File, root: File? = null): Node {
        val crate = root ?: kitDir.absoluteFile.parentFile ?: kitDir
        val index = LinkedHashMap<String, File>()
        for (dir in KitStore.list(crate)) {
            try {
                index.putIfAbsent(KitStore.load(dir).name, dir)
            } catch (e: Exception) {
                // An unreadable sibling can't break someone else's tree.
            }
        }
        return kitNode(KitStore.load(kitDir), index, visited = mutableSetOf())
    }

    private fun kitNode(kit: Kit, index: Map<String, File>, visited: MutableSet<String>): Node {
        visited += kit.name
        val children = mutableListOf<Node>()

        val kitRefs =
            kit.pads.mapNotNull { it.source["resampledFrom"] }.distinct().sorted().map { "resampled from" to it } +
                kit.pads.mapNotNull { it.source["mergedFrom"] }.distinct().sorted().map { "merged from" to it }
        for ((how, name) in kitRefs) {
            val dir = index[name]
            children += when {
                name in visited -> Node("$how $name (shown above)")
                dir == null -> Node("$how $name (not in the crate)")
                else -> {
                    val parent = try {
                        KitStore.load(dir)
                    } catch (e: Exception) {
                        null
                    }
                    if (parent == null) {
                        Node("$how $name (unreadable)")
                    } else {
                        val sub = kitNode(parent, index, visited)
                        Node("$how ${sub.label}", sub.children)
                    }
                }
            }
        }

        if (kitRefs.isEmpty()) {
            // The chain bottoms out here: terminal origins. (Only here -
            // resample carries inherited stamps forward on every pad, so
            // showing them at every level would repeat the roots.)
            val origins = kit.pads.mapNotNull { p ->
                val s = p.source
                when {
                    s["song"] != null -> "dug from ${s["song"]}" + (s["at"]?.let { " at $it" } ?: "")
                    s["file"] != null -> "chopped from ${s["file"]}"
                    s["importedFrom"] != null -> "imported from ${s["importedFrom"]}"
                    s["app"] != null || s["title"] != null ->
                        "captured from " + listOfNotNull(s["app"], s["title"]?.let { "\"$it\"" }).joinToString(" ")
                    else -> null
                }
            }.distinct().sorted()
            if (origins.isEmpty()) children += Node("made from scratch") else origins.forEach { children += Node(it) }
        }
        return Node(kit.name, children)
    }

    /** The tree as terminal text, `|-`/`` `- `` ASCII like the rest of the CLI. */
    fun render(tree: Node): String = buildString {
        append(tree.label).append('\n')
        fun rec(children: List<Node>, prefix: String) {
            children.forEachIndexed { i, c ->
                val last = i == children.lastIndex
                append(prefix).append(if (last) "`- " else "|- ").append(c.label).append('\n')
                rec(c.children, prefix + if (last) "   " else "|  ")
            }
        }
        rec(tree.children, "")
    }

    /** The tree as a card — the J-card's palette, the terminal's shape. */
    fun renderPng(tree: Node, out: File, widthPx: Int = 900): File {
        require(widthPx in 300..4000) { "widthPx 300..4000, got $widthPx" }
        val lines = render(tree).trimEnd('\n').split('\n')
        val lineHeight = widthPx / 30
        val pad = lineHeight
        val height = pad * 3 + lineHeight * (lines.size + 1)
        val img = BufferedImage(widthPx, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(0x1a, 0x1a, 0x1a)
            g.fillRect(0, 0, widthPx, height)
            g.font = Font(Font.MONOSPACED, Font.BOLD, (lineHeight * 0.72f).toInt())
            g.color = Color(0xe8, 0xc5, 0x4a)
            g.drawString("LINEAGE", pad, pad + lineHeight)
            g.font = Font(Font.MONOSPACED, Font.PLAIN, (lineHeight * 0.66f).toInt())
            lines.forEachIndexed { i, line ->
                g.color = if (i == 0) Color.WHITE else Color(0xb8, 0xb8, 0xb8)
                g.drawString(line, pad, pad + lineHeight * (i + 2))
            }
        } finally {
            g.dispose()
        }
        out.parentFile?.mkdirs()
        ImageIO.write(img, "png", out)
        return out
    }
}
