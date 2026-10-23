package com.cchharness.core

/** A small markdown subset, the same one the web client renders. */
sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<Span>) : MdBlock
    data class Paragraph(val spans: List<Span>) : MdBlock
    data class Code(val text: String) : MdBlock
    data class ListItem(val ordered: Boolean, val index: Int, val spans: List<Span>) : MdBlock
}

data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

private val FENCE = Regex("^```.*$")
private val FENCE_END = Regex("^```\\s*$")
private val HEADING = Regex("^(#{1,3})\\s+(.*)$")
private val BULLET = Regex("^\\s*[-*]\\s+(.*)$")
private val NUMBERED = Regex("^\\s*(\\d+)\\.\\s+(.*)$")

fun parseMarkdown(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.replace("\r", "").split("\n")
    var i = 0
    fun isStructural(l: String) =
        FENCE.matches(l) || HEADING.matches(l) || BULLET.matches(l) || NUMBERED.matches(l)
    while (i < lines.size) {
        val line = lines[i]
        if (FENCE.matches(line)) {
            val buf = ArrayList<String>()
            i++
            while (i < lines.size && !FENCE_END.matches(lines[i])) buf += lines[i++]
            i++ // closing fence (or end of input for an unterminated block)
            out += MdBlock.Code(buf.joinToString("\n"))
            continue
        }
        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            out += MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2]))
            i++
            continue
        }
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        if (bullet != null || numbered != null) {
            out += if (numbered != null) {
                MdBlock.ListItem(true, numbered.groupValues[1].toInt(), parseInline(numbered.groupValues[2]))
            } else {
                MdBlock.ListItem(false, 0, parseInline(bullet!!.groupValues[1]))
            }
            i++
            continue
        }
        if (line.isBlank()) {
            i++
            continue
        }
        val para = ArrayList<String>()
        while (i < lines.size && lines[i].isNotBlank() && !isStructural(lines[i])) para += lines[i++]
        out += MdBlock.Paragraph(parseInline(para.joinToString("\n")))
    }
    return out
}

private val INLINE = Regex(
    "`([^`\\n]+)`" +                              // 1: code
        "|\\*\\*([^*\\n]+)\\*\\*" +               // 2: bold
        "|\\*([^*\\n]+)\\*" +                      // 3: italic
        "|\\[([^\\]\\n]+)]\\((https?://[^\\s)]+)\\)", // 4,5: link
)

fun parseInline(s: String): List<Span> {
    val spans = ArrayList<Span>()
    var pos = 0
    for (m in INLINE.findAll(s)) {
        if (m.range.first > pos) spans += Span(s.substring(pos, m.range.first))
        val g = m.groups
        spans += when {
            g[1] != null -> Span(g[1]!!.value, code = true)
            g[2] != null -> Span(g[2]!!.value, bold = true)
            g[3] != null -> Span(g[3]!!.value, italic = true)
            else -> Span(g[4]!!.value, link = g[5]!!.value)
        }
        pos = m.range.last + 1
    }
    if (pos < s.length) spans += Span(s.substring(pos))
    return spans
}
