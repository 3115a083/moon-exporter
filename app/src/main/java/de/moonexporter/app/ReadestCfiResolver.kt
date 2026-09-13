package de.moonexporter.app

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** Resolves Moon+ highlight text to a real Foliate/Readest EPUB CFI range. */
internal class ReadestCfiResolver(
    private val epubFile: File,
    private val spinePaths: List<String>,
) : AutoCloseable {
    internal data class ResolvedRange(val cfi: String, val spineIndex: Int, val matchedText: String)

    private data class CharRef(val node: Node, val offset: Int)
    private data class IndexedText(val text: String, val refs: List<CharRef>)
    private data class Part(val index: Int, val id: String? = null, val offset: Int? = null)
    private enum class Marker { BEFORE, FIRST, LAST, AFTER }

    private val documents = mutableMapOf<Int, Document?>()
    private val indexes = mutableMapOf<Int, IndexedText?>()
    private var openedZip: ZipFile? = null

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
            val doc = documents[spineIndex] ?: continue
            val root = documentElement(doc) ?: continue
            val startParts = nodeToParts(startRef.node, startRef.offset, root) ?: continue
            val endParts = nodeToParts(endRef.node, endRef.offset + 1, root) ?: continue
            val cfi = buildRangeCfi(spineIndex, startParts, endParts) ?: continue
            return ResolvedRange(cfi, spineIndex, needle)
        }
        return null
    }

    override fun close() {
        runCatching { openedZip?.close() }
        openedZip = null
        documents.clear()
        indexes.clear()
    }

    private fun buildTextIndex(spineIndex: Int): IndexedText? {
        val doc = documents.getOrPut(spineIndex) { loadDocument(spineIndex) } ?: return null
        val root = doc.selectFirst("body") ?: documentElement(doc) ?: return null
        val out = StringBuilder()
        val refs = ArrayList<CharRef>()
        var pendingSpace: CharRef? = null

        fun appendText(node: TextNode) {
            node.wholeText.forEachIndexed { offset, raw ->
                val ch = canonicalChar(raw)
                if (ch == null) return@forEachIndexed
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
        }

        fun walk(node: Node) {
            if (node is Element) {
                val name = node.tagName().substringAfter(':').lowercase(Locale.ROOT)
                if (name == "script" || name == "style") return
            }
            if (node is TextNode) {
                appendText(node)
                return
            }
            node.childNodes().forEach(::walk)
        }

        walk(root)
        while (out.isNotEmpty() && out.last() == ' ') {
            out.deleteCharAt(out.lastIndex)
            refs.removeAt(refs.lastIndex)
        }
        return IndexedText(out.toString(), refs)
    }

    private fun loadDocument(spineIndex: Int): Document? {
        val wanted = spinePaths.getOrNull(spineIndex)?.replace('\\', '/')?.trimStart('/') ?: return null
        val bytes = runCatching {
            val zip = openedZip ?: ZipFile(epubFile).also { openedZip = it }
            val entry = zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.replace('\\', '/').trimStart('/').equals(wanted, ignoreCase = true)
            } ?: return@runCatching null
            if (entry.size > MAX_XHTML_BYTES) return@runCatching null
            zip.getInputStream(entry).use { input ->
                val data = input.readLimited(MAX_XHTML_BYTES + 1)
                data.takeIf { it.size <= MAX_XHTML_BYTES }
            }
        }.getOrNull() ?: return null

        return runCatching {
            Jsoup.parse(bytes.toString(Charsets.UTF_8), "", Parser.xmlParser())
        }.recoverCatching {
            Jsoup.parse(bytes.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun documentElement(doc: Document): Element? =
        doc.children().firstOrNull { it.tagName().equals("html", true) }
            ?: doc.children().firstOrNull()

    private fun nodeToParts(node: Node, offset: Int?, root: Element): List<Part>? {
        val parent = node.parentNode() ?: return null
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
                val n = part as? TextNode ?: continue
                if (n === node) {
                    sum += offset
                    break
                }
                sum += n.wholeText.length
            }
            adjustedOffset = sum
        }
        val id = (node as? Element)?.id()?.takeIf { it.isNotBlank() }
        val current = Part(index, id, if (index % 2 == 1) adjustedOffset else null)
        return if (parent !== root) {
            val prefix = nodeToParts(parent, null, root) ?: return null
            prefix + current
        } else listOf(current)
    }

    /** Port of Readest's foliate-js epubcfi.js indexChildNodes() semantics. */
    private fun indexChildNodes(parent: Node): MutableList<Any?> {
        val raw = mutableListOf<Node>()
        for (child in parent.childNodes()) {
            if (child !is Element && child !is TextNode) continue
            if (child is Element && child.hasAttr("cfi-inert")) continue
            if (child is Element && child.hasAttr("cfi-skip")) raw += flattenSkip(child) else raw += child
        }

        val grouped = mutableListOf<Any?>()
        for (node in raw) {
            val last = grouped.lastOrNull()
            if (grouped.isEmpty()) grouped += node
            else if (node is TextNode) {
                when (last) {
                    is MutableList<*> -> @Suppress("UNCHECKED_CAST") (last as MutableList<Node>).add(node)
                    is TextNode -> grouped[grouped.lastIndex] = mutableListOf(last, node)
                    else -> grouped += node
                }
            } else {
                if (last is Element) grouped += null
                grouped += node
            }
        }
        if (grouped.firstOrNull() is Element) grouped.add(0, Marker.FIRST)
        if (grouped.lastOrNull() is Element) grouped += Marker.LAST
        grouped.add(0, Marker.BEFORE)
        grouped += Marker.AFTER
        return grouped
    }

    private fun flattenSkip(node: Node): List<Node> {
        val out = mutableListOf<Node>()
        for (child in node.childNodes()) {
            if (child !is Element && child !is TextNode) continue
            if (child is Element && child.hasAttr("cfi-inert")) continue
            if (child is Element && child.hasAttr("cfi-skip")) out += flattenSkip(child) else out += child
        }
        return out
    }

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
        return "epubcfi(/6/${2 * (spineIndex + 1)}!$inner)"
    }

    private fun renderParts(parts: List<Part>): String = buildString {
        for (part in parts) {
            append('/').append(part.index)
            part.id?.let { append('[').append(escapeCfi(it)).append(']') }
            if (part.offset != null && part.index % 2 == 1) append(':').append(part.offset)
        }
    }

    private fun escapeCfi(value: String): String = value.replace(Regex("([\\^\\[\\](),;=])"), "^$1")

    private fun canonicalChar(ch: Char): Char? = when (ch) {
        '\u00ad' -> null
        '\u00a0', '\u2007', '\u202f' -> ' '
        '\u2018', '\u2019', '\u201a', '\u201b' -> '\''
        '\u201c', '\u201d', '\u201e', '\u201f' -> '"'
        '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2212' -> '-'
        else -> ch
    }

    private fun normalizeText(value: String): String {
        val out = StringBuilder()
        var pendingSpace = false
        for (raw in value) {
            val ch = canonicalChar(raw) ?: continue
            if (ch.isWhitespace()) {
                if (out.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace && out.isNotEmpty() && out.last() != ' ') out.append(' ')
                pendingSpace = false
                out.append(ch)
            }
        }
        return out.toString().trim()
    }

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

    private fun java.io.InputStream.readLimited(maxBytes: Int): ByteArray {
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
