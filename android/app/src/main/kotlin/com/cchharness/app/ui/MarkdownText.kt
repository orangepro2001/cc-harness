package com.cchharness.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cchharness.core.Align
import com.cchharness.core.MdBlock
import com.cchharness.core.Span
import com.cchharness.core.parseMarkdown

private fun List<Span>.annotated(linkColor: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (s in this@annotated) {
        val decoration = if (s.strike) TextDecoration.LineThrough else null
        when {
            s.link != null -> withLink(
                LinkAnnotation.Url(
                    s.link!!,
                    TextLinkStyles(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = if (s.strike) TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)) else TextDecoration.Underline,
                            fontWeight = if (s.bold) FontWeight.Bold else null,
                        ),
                    ),
                ),
            ) { append(s.text) }
            s.code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground,
                    color = linkColor,
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    textDecoration = decoration,
                ),
            ) { append(s.text) }
            else -> withStyle(
                SpanStyle(
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                    textDecoration = decoration,
                ),
            ) { append(s.text) }
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = Harness.colors.codeBackground
    val codeFg = Harness.colors.codeText
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Heading -> Text(
                    b.spans.annotated(linkColor, codeBg),
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2, 3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.bodyLarge
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = if (b.level <= 2) 6.dp else 2.dp),
                )
                is MdBlock.Paragraph -> Text(b.spans.annotated(linkColor, codeBg), style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Code -> Text(
                    b.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = codeFg,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(codeBg, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                is MdBlock.ListItem -> Row(Modifier.padding(start = (4 + 18 * b.level).dp)) {
                    val task = b.spans.firstOrNull()?.text.let { it == "☑ " || it == "☐ " } // the checkbox replaces the bullet
                    if (!task) Text(
                        if (b.ordered) "${b.index}. " else if (b.level == 0) "• " else "◦ ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                    Text(b.spans.annotated(linkColor, codeBg), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min).testTag("md-quote")) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
                    Text(
                        b.spans.annotated(linkColor, codeBg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                is MdBlock.Table -> MarkdownTable(b, linkColor, codeBg)
            }
        }
    }
}

/**
 * Columns size to their content (clamped), and the whole table scrolls sideways when it is
 * wider than the screen, instead of squeezing every cell into an unreadable column.
 */
@Composable
private fun MarkdownTable(t: MdBlock.Table, linkColor: Color, codeBg: Color) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val widths = remember(t) {
        t.header.indices.map { c ->
            val longest = (listOf(t.header[c]) + t.rows.map { it[c] }).maxOf { spans -> spans.sumOf { cellWidth(it.text) } }
            (longest * 8 + 28).coerceIn(64, 240).dp
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, line, RoundedCornerShape(8.dp))
            .testTag("md-table"),
    ) {
        (listOf(t.header) + t.rows).forEachIndexed { r, row ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                row.forEachIndexed { c, spans ->
                    Box(
                        Modifier
                            .width(widths[c])
                            .fillMaxHeight()
                            .background(if (r == 0) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                            .border(0.5.dp, line)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            spans.annotated(linkColor, codeBg),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (r == 0) FontWeight.Bold else null,
                            textAlign = when (t.align[c]) {
                                Align.Start -> TextAlign.Start
                                Align.Center -> TextAlign.Center
                                Align.End -> TextAlign.End
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** Display columns of [s]: CJK characters count double. */
private fun cellWidth(s: String): Int {
    var n = 0
    for (c in s) n += if (c.code >= 0x2E80) 2 else 1
    return n
}
