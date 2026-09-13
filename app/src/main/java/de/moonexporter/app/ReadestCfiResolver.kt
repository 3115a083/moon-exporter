package de.moonexporter.app

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.Text
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Resolves Moon+ annotation text against the actual EPUB XHTML and emits a
 * Foliate/Readest-compatible EPUB CFI range. No range is invented when the
 * highlighted text cannot be found in the book.
 */
internal class ReadestCfiResolver(
    private val epubFile: File,
    private val spinePaths: List<String>,
) {
    internal data class ResolvedRange(
        val cfi: String,
        val spineIndex: Int,
        val matchedText: String,
    )

    private data class CharRef(val node: Node, val offset: Int)
    private data class IndexedText(val text: String, val refs: List<CharRef>)
    private data class Part(val index: Int, val id: String? = null, val offset: Int? = null)
    private enum class Marker { BEFORE, FIRST, LAST, AFTER }

    private val documents = mutableMapOf<Int, org.w3c.dom.Document?>()
    private val indexes = mutableMapOf<Int, IndexedText?>()

    fun resolve(text: String, preferredSpine: Int?, preferredPosition: Long?): ResolvedRange? {
        val needle = normalizeText(text)
        if (needle.isBlank()) return null

        val candidates = buildList {
            if (preferredSpine != null && preferredSpine in spinePaths.indices) add(preferredSpine)
            for (i in spinePaths.indices) if (i != preferredSpine) add(i)
        }
        for (spineIndex in candidates) {
            val indexed = indexes.getOrPut(spineIndex) { buildTextIndex(spineIndex) } ?: continue
            val starts = allMatches(indexed.text, needle)
            if (starts.isEmpty()) continue
            val start = preferredPosition?.takeIf { it >= 0 }?.let { wanted ->
                starts.minByOrNull { kotlin.math.abs(it.toLong() - wanted) }
            } ?: starts.first()
            val endExclusive = start + needle.length
            if (start !in indexed.refs.indices || endExclusive - 1 !in indexed.refs.indices) continue
            val startRef = indexed.refs[start]
            val endRef = indexed.refs[endExclusive - 1]
            val startParts = nodeToParts(startRef.node, startRef.offset) ?: continue
            val endParts = nodeToParts(endRef.node, endRef.offset + 1) ?: continue
            val range = buildRangeCfi(spineIndex, startParts, endParts) ?: continue
            return ResolvedRange(range, spineIndex, needle)
        }
        return null
    }

    private fun buildTextIndex(spineIndex: Int): IndexedText? {
        val doc = documents.getOrPut(spineIndex) { loadDocument(spineIndex) } ?: return null
        val root = findBody(doc.documentElement) ?: doc.documentElement ?: return null
        val out = StringBuilder()
        val refs = ArrayList<CharRef>()
        var pendingSpace: CharRef? = null

        fun walk(node: Node) {
            if (node.nodeType == Node.ELEMENT_NODE) {
                val name = (node.localName ?: node.nodeName).substringAfter(':').lowercase(Locale.ROOT)
                if (name == "script" || name == "style") return
            }
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                val value = node.nodeValue.orEmpty()
                value.forEachIndexed { offset, ch ->
                    if (ch.isWhitespace()) {
                        if (out.isNotEmpty()) pendingSpace = CharRef(node, offset)
                    } else {
                        if (pendingSpace != null && out.isNotEmpty() && out.last() != ' ') {
                            out.append(' ')
                            refs.add(pendingSpace!!)
                        }
                        pendingSpace = null
                        out.append(ch)
                        refs.add(CharRef(node, offset))
                    }
                }
                return
            }
            val children = node.childNodes
            for (i in 0 until children.length) walk(children.item(i))
        }

        walk(root)
        while (out.isNotEmpty() && out.last() == ' ') {
            out.deleteCharAt(out.lastIndex)
            refs.removeAt(refs.lastIndex)
        }
        return IndexedText(out.toString(), refs)
    }

    private fun loadDocument(spineIndex: Int): org.w3c.dom.Document? {
        val wanted = spinePaths.getOrNull(spineIndex)?.replace('\\', '/')?.trimStart('/') ?: return null
        val bytes = runCatching {
            ZipFile(epubFile).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull {
                    !it.isDirectory && it.name.replace('\\', '/').trimStart('/').equals(wanted, ignoreCase = true)
                } ?: return@use null
                if (entry.size > MAX_XHTML_BYTES) return@use null
                zip.getInputStream(entry).use { input ->
                    val data = input.readBytes(MAX_XHTML_BYTES + 1)
                    data.takeIf { it.size <= MAX_XHTML_BYTES }
                }
            }
        }.getOrNull() ?: return null

        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                isExpandEntityReferences = false
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            }
            factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> org.xml.sax.InputSource(ByteArrayInputStream(ByteArray(0))) }
            }.parse(ByteArrayInputStream(bytes))
        }.getOrNull()
    }

    private fun findBody(root: Element?): Element? {
        if (root == null) return null
        val name = (root.localName ?: root.nodeName).substringAfter(':')
        if (name.equals("body", true)) return root
        val children = root.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) findBody(child as Element)?.let { return it }
        }
        return null
    }

    private fun nodeToParts(node: Node, offset: Int?): List<Part>? {
        val parent = node.parentNode ?: return null
        val indexed = indexChildNodes(parent)
        val index = indexed.indexOfFirst { item ->
            when (item) {
                is Node -> item === node
                is List<*> -> item.any { it === node }
                else -> false
            }
        }
        if (index < 0) return null
        val chunk = indexed[index]
        var adjustedOffset = offset
        if (chunk is List<*> && offset != null) {
            var sum = 0
            for (part in chunk) {
                val n = part as? Node ?: continue
                if (n === node) {
                    sum += offset
                    break
                }
                sum += n.nodeValue.orEmpty().length
            }
            adjustedOffset = sum
        }
        val id = (node as? Element)?.getAttribute("id")?.takeIf { it.isNotBlank() }
        val current = Part(index, id, if (index % 2 == 1) adjustedOffset else null)
        val docRoot = node.ownerDocument?.documentElement
        return if (parent !== docRoot) {
            val prefix = nodeToParts(parent, null) ?: return null
            prefix + current
        } else listOf(current)
    }

    /** Port of foliate-js epubcfi.js indexChildNodes() semantics. */
    private fun indexChildNodes(parent: Node): MutableList<Any?> {
        val raw = mutableListOf<Node>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE || child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE) {
                val element = child as? Element
                if (element?.hasAttribute("cfi-inert") == true) continue
                if (element?.hasAttribute("cfi-skip") == true) {
                    raw += flattenSkip(child)
                } else raw += child
            }
        }

        val grouped = mutableListOf<Any?>()
        for (node in raw) {
            val last = grouped.lastOrNull()
            if (grouped.isEmpty()) grouped += node
            else if (isText(node)) {
                when (last) {
                    is MutableList<*> -> (last as MutableList<Node>).add(node)
                    is Node -> if (isText(last)) grouped[grouped.lastIndex] = mutableListOf(last, node) else grouped += node
                    else -> grouped += node
                }
            } else {
                if (last is Node && last.nodeType == Node.ELEMENT_NODE) grouped += null
                grouped += node
            }
        }
        if (grouped.firstOrNull() is Node && (grouped.first() as Node).nodeType == Node.ELEMENT_NODE) grouped.add(0, Marker.FIRST)
        if (grouped.lastOrNull() is Node && (grouped.last() as Node).nodeType == Node.ELEMENT_NODE) grouped += Marker.LAST
        grouped.add(0, Marker.BEFORE)
        grouped += Marker.AFTER
        return grouped
    }

    private fun flattenSkip(node: Node): List<Node> {
        val out = mutableListOf<Node>()
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE && child.nodeType != Node.TEXT_NODE && child.nodeType != Node.CDATA_SECTION_NODE) continue
            val el = child as? Element
            if (el?.hasAttribute("cfi-inert") == true) continue
            if (el?.hasAttribute("cfi-skip") == true) out += flattenSkip(child) else out += child
        }
        return out
    }

    private fun isText(node: Node): Boolean = node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE

    private fun buildRangeCfi(spineIndex: Int, start: List<Part>, end: List<Part>): String? {
        if (start.isEmpty() || end.isEmpty()) return null
        var common = 0
        while (common < start.size && common < end.size) {
            val a = start[common]
            val b = end[common]
            if (a.index != b.index || a.offset != null || b.offset != null) break
            common++
        }
        val parent = start.take(common)
        val startTail = start.drop(common)
        val endTail = end.drop(common)
        if (startTail.isEmpty() || endTail.isEmpty()) return null
        val inner = renderParts(parent) + "," + renderParts(startTail) + "," + renderParts(endTail)
        val section = 2 * (spineIndex + 1)
        return "epubcfi(/6/$section!$inner)"
    }

    private fun renderParts(parts: List<Part>): String = buildString {
        for (part in parts) {
            append('/').append(part.index)
            part.id?.let { append('[').append(escapeCfi(it)).append(']') }
            if (part.offset != null && part.index % 2 == 1) append(':').append(part.offset)
        }
    }

    private fun escapeCfi(value: String): String = value.replace(Regex("([\\^\\[\\](),;=])"), "^$1")

    private fun normalizeText(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun allMatches(haystack: String, needle: String): List<Int> {
        if (needle.isEmpty() || haystack.length < needle.length) return emptyList()
        val out = mutableListOf<Int>()
        var start = 0
        while (start <= haystack.length - needle.length) {
            val hit = haystack.indexOf(needle, startIndex = start, ignoreCase = true)
            if (hit < 0) break
            out += hit
            start = hit + 1
        }
        return out
    }

    private fun java.io.InputStream.readBytes(maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_XHTML_BYTES = 8 * 1024 * 1024
    }
}
