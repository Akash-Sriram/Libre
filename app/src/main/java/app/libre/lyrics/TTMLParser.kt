package app.libre.lyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure Kotlin / DOM parser for TTML (Timed Text Markup Language) synced lyrics.
 * Converts Apple Music TTML formatted lyrics into standard synced LRC timestamps [mm:ss.xx].
 *
 * Ported from Metrolist for Libre.
 */
object TTMLParser {

    private const val TTML_PARAMETER_NS = "http://www.w3.org/ns/ttml#parameter"

    data class ParsedLine(
        val text: String,
        val startTime: Double,
        val words: List<ParsedWord> = emptyList(),
        val isBackground: Boolean = false
    )

    data class ParsedWord(
        val text: String,
        val startTime: Double,
        val endTime: Double
    )

    private data class SpanInfo(
        val text: String,
        val startTime: Double,
        val endTime: Double
    )

    fun ttmlToLrc(ttml: String): String? {
        val lines = parseTTML(ttml)
        if (lines.isEmpty()) return null

        val sb = StringBuilder(lines.size * 64)
        for (line in lines) {
            if (line.text.isNotBlank()) {
                val timeStr = formatLrcTime(line.startTime)
                sb.append(timeStr).append(line.text).append("\n")
            }
        }
        val result = sb.toString().trim()
        return if (result.isNotBlank()) result else null
    }

    fun parseTTML(ttml: String): List<ParsedLine> {
        val lines = mutableListOf<ParsedLine>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
            try { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) {}
            try { factory.isExpandEntityReferences = false } catch (_: Exception) {}

            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(ttml.toByteArray(Charsets.UTF_8)))
            val pNodes = doc.getElementsByTagName("p")

            for (i in 0 until pNodes.length) {
                val p = pNodes.item(i) as? Element ?: continue
                val beginAttr = timingAttr(p, "begin")
                var lineStart = if (beginAttr.isNotEmpty()) parseTime(beginAttr) else 0.0

                val spanInfos = mutableListOf<SpanInfo>()
                extractSpans(p, spanInfos)

                if (spanInfos.isNotEmpty() && lineStart == 0.0) {
                    lineStart = spanInfos.first().startTime
                }

                val lineText = if (spanInfos.isNotEmpty()) {
                    spanInfos.joinToString(" ") { it.text.trim() }.trim()
                } else {
                    p.textContent?.trim().orEmpty()
                }

                if (lineText.isNotBlank()) {
                    lines.add(
                        ParsedLine(
                            text = lineText,
                            startTime = lineStart,
                            words = spanInfos.map { ParsedWord(it.text, it.startTime, it.endTime) }
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Silently fall through
        }
        return lines.sortedBy { it.startTime }
    }

    private fun extractSpans(element: Element, spanInfos: MutableList<SpanInfo>) {
        var child = element.firstChild
        while (child != null) {
            if (child is Element) {
                val name = child.localName ?: child.nodeName.substringAfterLast(':')
                if (name == "span") {
                    val begin = timingAttr(child, "begin")
                    val end = timingAttr(child, "end")
                    val text = child.textContent?.trim().orEmpty()
                    if (begin.isNotEmpty() && text.isNotEmpty()) {
                        val startTime = parseTime(begin)
                        val endTime = if (end.isNotEmpty()) parseTime(end) else startTime + 1.0
                        spanInfos.add(SpanInfo(text, startTime, endTime))
                    } else {
                        extractSpans(child, spanInfos)
                    }
                }
            }
            child = child.nextSibling
        }
    }

    private fun timingAttr(el: Element, localName: String): String {
        val direct = el.getAttribute(localName)
        if (direct.isNotEmpty()) return direct
        val param = el.getAttributeNS(TTML_PARAMETER_NS, localName)
        if (param.isNotEmpty()) return param
        val ttm = el.getAttribute("ttm:$localName")
        if (ttm.isNotEmpty()) return ttm
        return ""
    }

    private fun parseTime(time: String): Double {
        val t = time.trim()
        val c1 = t.indexOf(':')
        return if (c1 != -1) {
            val c2 = t.lastIndexOf(':')
            if (c1 == c2) {
                (t.substring(0, c1).toIntOrNull() ?: 0) * 60.0 + (t.substring(c1 + 1).toDoubleOrNull() ?: 0.0)
            } else {
                (t.substring(0, c1).toIntOrNull() ?: 0) * 3600.0 +
                        (t.substring(c1 + 1, c2).toIntOrNull() ?: 0) * 60.0 +
                        (t.substring(c2 + 1).toDoubleOrNull() ?: 0.0)
            }
        } else {
            t.toDoubleOrNull() ?: 0.0
        }
    }

    private fun formatLrcTime(time: Double): String {
        val totalMs = (time * 1000).toLong().coerceAtLeast(0L)
        val m = totalMs / 60000
        val s = (totalMs % 60000) / 1000
        val c = (totalMs % 1000) / 10
        return String.format("[%02d:%02d.%02d]", m, s, c)
    }
}
