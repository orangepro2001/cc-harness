package com.cchharness.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cchharness.core.MdBlock
import com.cchharness.core.Span
import com.cchharness.core.parseMarkdown

private fun List<Span>.annotated(linkColor: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (s in this@annotated) {
        when {
            s.link != null -> withLink(
                LinkAnnotation.Url(
                    s.link!!,
                    TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ),
            ) { append(s.text) }
            s.code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, color = linkColor)) { append(s.text) }
            else -> withStyle(
                SpanStyle(
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Heading -> Text(
                    b.spans.annotated(linkColor, codeBg),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
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
                is MdBlock.ListItem -> Row(Modifier.padding(start = 4.dp)) {
                    Text(
                        if (b.ordered) "${b.index}. " else "• ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(b.spans.annotated(linkColor, codeBg), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
