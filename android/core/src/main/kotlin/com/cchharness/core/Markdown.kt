package com.cchharness.core

/** The markdown subset both clients render: the web client implements the same grammar. */
sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<Span>) : MdBlock
    data class Paragraph(val spans: List<Span>) : MdBlock
    data class Code(val text: String) : MdBlock

    /** [level] is the nesting depth (0 = top level), derived from the indentation. */
    data class ListItem(val ordered: Boolean, val index: Int, val spans: List<Span>, val level: Int = 0) : MdBlock
    data class Quote(val spans: List<Span>) : MdBlock
    data object Rule : MdBlock
    data class Table(val header: List<List<Span>>, val align: List<Align>, val rows: List<List<List<Span>>>) : MdBlock
}

enum class Align { Start, Center, End }

data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    val strike: Boolean = false,
)

private val FENCE = Regex("^\\s*```.*$")
private val FENCE_END = Regex("^\\s*```\\s*$")
private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")

/** Two spaces (or a tab) per nesting level. */
private fun levelOf(indent: String): Int = indent.replace("\t", "  ").length / 2

private fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    val cells = ArrayList<String>()
    val cur = StringBuilder()
    var i = 0
    var inCode = false
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < s.length && s[i + 1] == '|' -> { cur.append('|'); i++ }
            c == '`' -> { inCode = !inCode; cur.append(c) }
            c == '|' && !inCode -> { cells += cur.toString().trim(); cur.setLength(0) }
            else -> cur.append(c)
        }
        i++
    }
    cells += cur.toString().trim()
    return cells
}

private fun looksLikeRow(l: String) = l.contains('|') && l.isNotBlank()

fun parseMarkdown(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.replace("\r", "").split("\n")
    var i = 0
    fun tableAt(k: Int) = k + 1 < lines.size && looksLikeRow(lines[k]) && TABLE_SEP.matches(lines[k + 1]) && lines[k + 1].contains('-')
    fun isStructural(k: Int): Boolean {
        val l = lines[k]
        return FENCE.matches(l) || HEADING.matches(l) || BULLET.matches(l) || NUMBERED.matches(l) ||
            QUOTE.matches(l) || RULE.matches(l) || tableAt(k)
    }
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
        if (RULE.matches(line)) {
            out += MdBlock.Rule
            i++
            continue
        }
        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            out += MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2]))
            i++
            continue
        }
        if (tableAt(i)) {
            val header = splitRow(line)
            val align = splitRow(lines[i + 1]).map {
                when {
                    it.startsWith(":") && it.endsWith(":") && it.length > 1 -> Align.Center
                    it.endsWith(":") -> Align.End
                    else -> Align.Start
                }
            }
            i += 2
            val rows = ArrayList<List<List<Span>>>()
            while (i < lines.size && looksLikeRow(lines[i]) && !FENCE.matches(lines[i])) {
                val cells = splitRow(lines[i])
                // ragged rows are padded / cut to the header width so every row lines up
                rows += List(header.size) { c -> parseInline(cells.getOrElse(c) { "" }) }
                i++
            }
            out += MdBlock.Table(
                header.map { parseInline(it) },
                List(header.size) { align.getOrElse(it) { Align.Start } },
                rows,
            )
            continue
        }
        val quote = QUOTE.matchEntire(line)
        if (quote != null) {
            val buf = ArrayList<String>()
            while (i < lines.size) {
                val q = QUOTE.matchEntire(lines[i]) ?: break
                buf += q.groupValues[1]
                i++
            }
            out += MdBlock.Quote(parseInline(buf.joinToString("\n").trim()))
            continue
        }
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        if (bullet != null || numbered != null) {
            out += if (numbered != null) {
                MdBlock.ListItem(true, numbered.groupValues[2].toInt(), parseInline(numbered.groupValues[3]), levelOf(numbered.groupValues[1]))
            } else {
                var text = bullet!!.groupValues[2]
                var prefix = ""
                // GitHub task lists
                Regex("^\\[([ xX])]\\s+").find(text)?.let {
                    prefix = if (it.groupValues[1] == " ") "☐ " else "☑ "
                    text = text.substring(it.range.last + 1)
                }
                MdBlock.ListItem(false, 0, (if (prefix.isEmpty()) emptyList() else listOf(Span(prefix))) + parseInline(text), levelOf(bullet.groupValues[1]))
            }
            i++
            continue
        }
        if (line.isBlank()) {
            i++
            continue
        }
        val para = ArrayList<String>()
        while (i < lines.size && lines[i].isNotBlank() && !isStructural(i)) para += lines[i++]
        if (para.isEmpty()) { // a lone line the paragraph loop refused: keep it as text, never spin
            para += lines[i++]
        }
        out += MdBlock.Paragraph(parseInline(para.joinToString("\n")))
    }
    return out
}

private val INLINE = Regex(
    "`([^`\\n]+)`" +                                   // 1: code
        "|\\*\\*(.+?)\\*\\*" +                         // 2: bold (may nest other spans)
        "|~~(.+?)~~" +                                 // 3: strike
        "|\\*([^*\\n]+)\\*" +                          // 4: italic
        "|\\[([^\\]\\n]+)]\\((https?://[^\\s)]+)\\)" + // 5,6: link
        "|(https?://[A-Za-z0-9\\-._~:/?#@!$&*+,;=%]+)",     // 7: bare url (ASCII only: CJK punctuation ends it)
)

fun parseInline(s: String): List<Span> = inline(s, Span(""))

private fun inline(s: String, base: Span): List<Span> {
    val spans = ArrayList<Span>()
    var pos = 0
    fun plain(t: String) {
        if (t.isNotEmpty()) spans += base.copy(text = t)
    }
    for (m in INLINE.findAll(s)) {
        if (m.range.first > pos) plain(s.substring(pos, m.range.first))
        val g = m.groups
        when {
            g[1] != null -> spans += base.copy(text = g[1]!!.value, code = true)
            g[2] != null -> spans += inline(g[2]!!.value, base.copy(bold = true))
            g[3] != null -> spans += inline(g[3]!!.value, base.copy(strike = true))
            g[4] != null -> spans += inline(g[4]!!.value, base.copy(italic = true))
            g[5] != null -> spans += base.copy(text = g[5]!!.value, link = g[6]!!.value)
            else -> {
                // sentence punctuation right after a URL is not part of it
                var url = g[7]!!.value
                var tail = ""
                while (url.isNotEmpty() && url.last() in ".,;:!?'\"，。；：！？") {
                    tail = url.last() + tail
                    url = url.dropLast(1)
                }
                spans += base.copy(text = url, link = url)
                if (tail.isNotEmpty()) spans += base.copy(text = tail)
            }
        }
        pos = m.range.last + 1
    }
    if (pos < s.length) plain(s.substring(pos))
    return spans
}
