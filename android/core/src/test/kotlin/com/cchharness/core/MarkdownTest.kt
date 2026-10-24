package com.cchharness.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {
    @Test
    fun inlineSpans() {
        val s = parseInline("a **b** `c` *d* [e](https://x.y/z) f")
        assertEquals(
            listOf(
                Span("a "), Span("b", bold = true), Span(" "), Span("c", code = true), Span(" "),
                Span("d", italic = true), Span(" "), Span("e", link = "https://x.y/z"), Span(" f"),
            ),
            s,
        )
    }

    @Test
    fun javascriptLinksAreNotLinked() {
        val s = parseInline("[x](javascript:alert(1))")
        assertTrue(s.none { it.link != null })
    }

    @Test
    fun blocks() {
        val md = parseMarkdown("# Title\n\npara one\nline two\n\n- a\n- b\n1. x\n\n```kotlin\nval a = 1\n\nval b = 2\n```\nafter")
        assertEquals(
            listOf(
                MdBlock.Heading(1, listOf(Span("Title"))),
                MdBlock.Paragraph(listOf(Span("para one\nline two"))),
                MdBlock.ListItem(false, 0, listOf(Span("a"))),
                MdBlock.ListItem(false, 0, listOf(Span("b"))),
                MdBlock.ListItem(true, 1, listOf(Span("x"))),
                MdBlock.Code("val a = 1\n\nval b = 2"),
                MdBlock.Paragraph(listOf(Span("after"))),
            ),
            md,
        )
    }

    @Test
    fun unterminatedFenceKeepsContent() {
        assertEquals(listOf(MdBlock.Code("a\nb")), parseMarkdown("```\na\nb"))
    }

    @Test
    fun htmlIsKeptAsPlainText() {
        // rendering is Compose Text, never HTML: markup must pass through untouched
        assertEquals(listOf(MdBlock.Paragraph(listOf(Span("<script>x</script>")))), parseMarkdown("<script>x</script>"))
    }

    @Test
    fun emptyInput() {
        assertTrue(parseMarkdown("").isEmpty())
    }

    @Test
    fun table() {
        val md = parseMarkdown("| 技术栈 | 好 |\n| --- | :-: |\n| Avalonia | 是 |\n| Wails | 否 |\nafter")
        assertEquals(
            listOf(
                MdBlock.Table(
                    header = listOf(listOf(Span("技术栈")), listOf(Span("好"))),
                    align = listOf(Align.Start, Align.Center),
                    rows = listOf(
                        listOf(listOf(Span("Avalonia")), listOf(Span("是"))),
                        listOf(listOf(Span("Wails")), listOf(Span("否"))),
                    ),
                ),
                MdBlock.Paragraph(listOf(Span("after"))),
            ),
            md,
        )
    }

    @Test
    fun tableWithoutOuterPipesAndRaggedRows() {
        val md = parseMarkdown("a | b | c\n--|--|--\n1 | 2\n1 | 2 | 3 | 4")
        val t = md.single() as MdBlock.Table
        assertEquals(3, t.header.size)
        assertEquals(listOf(listOf(Span("1")), listOf(Span("2")), emptyList()), t.rows[0])
        assertEquals(3, t.rows[1].size)
    }

    @Test
    fun pipeInCodeOrEscapedStaysInCell() {
        val t = parseMarkdown("| a | b |\n|---|---|\n| `x|y` | p\\|q |").single() as MdBlock.Table
        assertEquals(listOf(listOf(Span("x|y", code = true)), listOf(Span("p|q"))), t.rows[0])
    }

    @Test
    fun aLineWithAPipeButNoSeparatorIsAParagraph() {
        assertEquals(listOf(MdBlock.Paragraph(listOf(Span("a | b")))), parseMarkdown("a | b"))
    }

    @Test
    fun quoteRuleAndDeepHeading() {
        val md = parseMarkdown("> one\n> two\n\n---\n\n#### h4")
        assertEquals(
            listOf(MdBlock.Quote(listOf(Span("one\ntwo"))), MdBlock.Rule, MdBlock.Heading(4, listOf(Span("h4")))),
            md,
        )
    }

    @Test
    fun nestedListsAndTasks() {
        val md = parseMarkdown("- a\n  - b\n    1. c\n- [x] done\n- [ ] todo")
        assertEquals(
            listOf(
                MdBlock.ListItem(false, 0, listOf(Span("a")), 0),
                MdBlock.ListItem(false, 0, listOf(Span("b")), 1),
                MdBlock.ListItem(true, 1, listOf(Span("c")), 2),
                MdBlock.ListItem(false, 0, listOf(Span("☑ "), Span("done")), 0),
                MdBlock.ListItem(false, 0, listOf(Span("☐ "), Span("todo")), 0),
            ),
            md,
        )
    }

    @Test
    fun nestedInlineStrikeAndBareLinks() {
        assertEquals(
            listOf(Span("a ", bold = true), Span("b", bold = true, code = true), Span(" tail")),
            parseInline("**a `b`** tail"),
        )
        assertEquals(listOf(Span("gone", strike = true)), parseInline("~~gone~~"))
        assertEquals(
            listOf(Span("see "), Span("https://a.b/c", link = "https://a.b/c"), Span(".")),
            parseInline("see https://a.b/c."),
        )
    }

    @Test
    fun bareUrlStopsAtCjkPunctuationAndBackticks() {
        assertEquals(
            listOf(Span("见 "), Span("https://a.b/c", link = "https://a.b/c"), Span("。"), Span("x", code = true)),
            parseInline("见 https://a.b/c。`x`"),
        )
    }
}
