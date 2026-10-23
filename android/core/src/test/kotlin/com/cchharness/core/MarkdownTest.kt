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
}
