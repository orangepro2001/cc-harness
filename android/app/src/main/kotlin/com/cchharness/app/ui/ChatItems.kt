package com.cchharness.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cchharness.core.AssistantText
import com.cchharness.core.ChatItem
import com.cchharness.core.HarnessJson
import com.cchharness.core.Note
import com.cchharness.core.PermState
import com.cchharness.core.PermissionItem
import com.cchharness.core.ToolCall
import com.cchharness.core.UserMessage
import com.cchharness.core.Attachment
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val Pretty = Json { prettyPrint = true }

private fun prettyJson(raw: String): String = try {
    Pretty.encodeToString(JsonElement.serializer(), HarnessJson.parseToJsonElement(raw))
} catch (e: Exception) {
    raw
}

@Composable
fun ChatItemView(
    item: ChatItem,
    onAllow: (requestId: String, remember: Boolean) -> Unit,
    onDeny: (requestId: String) -> Unit,
    onAnswer: (PermissionItem, Map<String, String>) -> Unit,
    loadAttachment: suspend (Attachment) -> ByteArray? = { null },
) {
    when (item) {
        is UserMessage -> UserBubble(item.text, item.attachments, loadAttachment)
        is AssistantText -> AssistantBubble(item.text)
        is ToolCall -> ToolCallCard(item)
        is PermissionItem -> PermissionCard(item, onAllow, onDeny, onAnswer)
        is Note -> NoteLine(item)
    }
}

@Composable
fun UserBubble(
    text: String,
    attachments: List<Attachment> = emptyList(),
    loadAttachment: suspend (Attachment) -> ByteArray? = { null },
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = Harness.colors.userBubble,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.padding(start = 48.dp).testTag("user-bubble"),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (text.isNotBlank()) SelectionContainer { Text(text) }
                attachments.forEach { AttachmentView(it, loadAttachment) }
            }
        }
    }
}

/** An image shows as a thumbnail (fetched on demand); any other file as a labelled chip. */
@Composable
fun AttachmentView(att: Attachment, load: suspend (Attachment) -> ByteArray?) {
    if (att.isImage) {
        val bitmap by produceState<ImageBitmap?>(null, att.id) {
            value = withContext(Dispatchers.Default) { load(att)?.let { decodeThumbnail(it, THUMB_MAX_PX) } }
        }
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = att.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.heightIn(max = 200.dp).clip(RoundedCornerShape(8.dp)).testTag("att-image-${att.id}"),
            )
        } else {
            Text("🖼 ${att.name}", modifier = Modifier.testTag("att-${att.id}"))
        }
    } else {
        Text("📄 ${att.name} (${formatSize(att.size)})", modifier = Modifier.testTag("att-${att.id}"))
    }
}

private const val THUMB_MAX_PX = 800

fun formatSize(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / 1048576.0)
}

/** Decodes [bytes] downsampled so neither side exceeds [maxPx]; null if it is not a readable image. */
fun decodeThumbnail(bytes: ByteArray, maxPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

@Composable
fun AssistantBubble(text: String, live: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(
            if (live) Harness.colors.brand else MaterialTheme.colorScheme.onSurface,
            Modifier.padding(top = 9.dp),
            size = 7.dp,
            pulse = live,
        )
        SelectionContainer(Modifier.weight(1f)) {
            MarkdownText(text, Modifier.fillMaxWidth().testTag(if (live) "live-bubble" else "assistant-bubble"))
        }
    }
}

@Composable
fun NoteLine(note: Note) {
    Text(
        note.text,
        fontSize = 12.sp,
        color = if (note.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag(if (note.isError) "note-error" else "note"),
    )
}

@Composable
fun ToolCallCard(call: ToolCall) {
    var expanded by remember { mutableStateOf(false) }
    val state = when {
        call.result == null -> Harness.colors.brand
        call.isError -> Harness.colors.bad
        else -> Harness.colors.ok
    }
    // Like the CLI: "● Name  summary", and the result hangs off it once expanded.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .testTag("tool-${call.toolUseId}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(state, size = 7.dp, pulse = call.result == null)
            Text(call.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                call.summary,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "⌄" else "›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        if (expanded) {
            Column(Modifier.padding(start = 15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CodeBlock(prettyJson(call.inputJson))
                if (call.result != null) {
                    CodeBlock(call.result!!, error = call.isError, tag = "tool-result-${call.toolUseId}")
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(text: String, error: Boolean = false, tag: String = "code") {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = if (error) Harness.colors.bad else Harness.colors.codeText,
        modifier = Modifier
            .fillMaxWidth()
            .background(Harness.colors.codeBackground, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(tag),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermissionCard(
    item: PermissionItem,
    onAllow: (requestId: String, remember: Boolean) -> Unit,
    onDeny: (requestId: String) -> Unit,
    onAnswer: (PermissionItem, Map<String, String>) -> Unit,
) {
    val pending = item.state == PermState.Pending
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (pending) 1.5.dp else 1.dp, if (pending) Harness.colors.warn else MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = if (pending) 3.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().testTag("perm-${item.requestId}"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (item.isQuestion) "Claude 需要你的回答" else "请求使用工具: ${item.toolName}",
                fontWeight = FontWeight.Bold,
            )
            if (item.isQuestion) {
                QuestionForm(item, enabled = pending, onAnswer = onAnswer, onDeny = onDeny)
            } else {
                CodeBlock(prettyJson(HarnessJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), item.input)))
                if (pending) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { onAllow(item.requestId, false) }, modifier = Modifier.testTag("perm-allow")) { Text("允许") }
                        if (item.canRemember) {
                            OutlinedButton(onClick = { onAllow(item.requestId, true) }, modifier = Modifier.testTag("perm-always")) { Text("本会话总是允许") }
                        }
                        OutlinedButton(onClick = { onDeny(item.requestId) }, modifier = Modifier.testTag("perm-deny")) { Text("拒绝") }
                    }
                }
            }
            if (!pending) {
                Text(
                    when (item.state) {
                        PermState.Allowed -> "已允许"
                        PermState.AllowedRemembered -> "已允许 (本会话)"
                        PermState.Denied -> "已拒绝"
                        PermState.Cancelled -> "已取消"
                        PermState.Pending -> ""
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("perm-state"),
                )
            }
        }
    }
}

@Composable
private fun QuestionForm(
    item: PermissionItem,
    enabled: Boolean,
    onAnswer: (PermissionItem, Map<String, String>) -> Unit,
    onDeny: (requestId: String) -> Unit,
) {
    // question text -> chosen labels (kept in selection order)
    val picks = remember(item.requestId) { mutableStateMapOf<String, List<String>>() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item.questions.forEach { q ->
            Column {
                Text((if (q.header.isNotEmpty()) "[${q.header}] " else "") + q.question)
                q.options.forEach { opt ->
                    val chosen = picks[q.question].orEmpty()
                    val selected = opt.label in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                enabled = enabled,
                                role = if (q.multiSelect) Role.Checkbox else Role.RadioButton,
                                onClick = {
                                    picks[q.question] = when {
                                        q.multiSelect && selected -> chosen - opt.label
                                        q.multiSelect -> chosen + opt.label
                                        else -> listOf(opt.label)
                                    }
                                },
                            )
                            .testTag("option-${opt.label}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (q.multiSelect) Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
                        else RadioButton(selected = selected, onClick = null, enabled = enabled)
                        Text(opt.label + if (opt.description.isNotEmpty()) " — ${opt.description}" else "", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        if (enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAnswer(item, item.questions.associate { it.question to picks[it.question].orEmpty().joinToString(", ") }) },
                    enabled = item.questions.all { picks[it.question].orEmpty().isNotEmpty() },
                    modifier = Modifier.testTag("answer-submit"),
                ) { Text("提交") }
                OutlinedButton(onClick = { onDeny(item.requestId) }, modifier = Modifier.testTag("perm-deny")) { Text("拒绝") }
            }
        }
    }
}
