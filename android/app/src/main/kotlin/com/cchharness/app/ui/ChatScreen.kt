package com.cchharness.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontFamily
import com.cchharness.app.DraftAttachment
import com.cchharness.app.UploadState
import com.cchharness.core.Attachment
import com.cchharness.core.EFFORTS
import com.cchharness.core.MODELS
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import com.cchharness.core.ToolCall
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cchharness.core.ChatState
import com.cchharness.core.Connection
import com.cchharness.core.PERMISSION_MODES
import com.cchharness.core.PermissionItem
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    title: String,
    cwd: String,
    state: ChatState,
    connection: Connection,
    fallbackMode: String,
    allowBypass: Boolean,
    notice: String?,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onStopProcess: () -> Unit,
    onSetMode: (String) -> Unit,
    onAllow: (requestId: String, remember: Boolean) -> Unit,
    onDeny: (requestId: String) -> Unit,
    onAnswer: (PermissionItem, Map<String, String>) -> Unit,
    onDismissNotice: () -> Unit,
    drafts: List<DraftAttachment> = emptyList(),
    commands: List<String> = emptyList(),
    fallbackModel: String = "",
    fallbackEffort: String = "",
    onPickFiles: () -> Unit = {},
    onRemoveDraft: (Long) -> Unit = {},
    onSetModel: (String) -> Unit = {},
    onSetEffort: (String) -> Unit = {},
    loadAttachment: suspend (Attachment) -> ByteArray? = { null },
    onTakeOver: (() -> Unit)? = null,
) {
    val modes = PERMISSION_MODES.filter { it.first != "bypassPermissions" || allowBypass }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val uploading = drafts.any { it.state is UploadState.Uploading }
    val suggestions = slashSuggestions(draft, state.commands.ifEmpty { commands })
    val active = state.status == "busy" || state.status == "awaiting_permission"
    var justSent by remember { mutableStateOf(false) } // sent, but the server has not reported anything yet
    LaunchedEffect(state.status, state.lastSeq) { if (active) justSent = false }
    LaunchedEffect(justSent) {
        if (justSent) {
            delay(20_000)
            justSent = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("chat-back")) {
                        Text("‹", fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                title = {
                    Column {
                        Text(
                            title.ifBlank { "(未命名)" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            cwd,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = Mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    StatusBadge(state.status, Modifier.testTag("chat-status"))
                    Box {
                        TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("chat-menu")) {
                            Text("⋮", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("中断当前回合") },
                                enabled = active,
                                onClick = {
                                    menuOpen = false
                                    onInterrupt()
                                },
                                modifier = Modifier.testTag("menu-interrupt"),
                            )
                            DropdownMenuItem(
                                text = { Text("停止后台进程") },
                                enabled = state.status != "stopped",
                                onClick = {
                                    menuOpen = false
                                    onStopProcess()
                                },
                                modifier = Modifier.testTag("menu-stop"),
                            )
                            if (onTakeOver != null) {
                                DropdownMenuItem(
                                    text = { Text("在终端中接管(Claude Code)") },
                                    onClick = {
                                        menuOpen = false
                                        onTakeOver()
                                    },
                                    modifier = Modifier.testTag("menu-takeover"),
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("模型 / Effort…") },
                                onClick = {
                                    menuOpen = false
                                    settingsOpen = true
                                },
                                modifier = Modifier.testTag("menu-settings"),
                            )
                            modes.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text("权限: $label" + if (value == (state.permissionMode ?: fallbackMode)) " ✓" else "") },
                                    onClick = {
                                        menuOpen = false
                                        onSetMode(value)
                                    },
                                    modifier = Modifier.testTag("menu-mode-$value"),
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize().imePadding()) {
            if (connection !is Connection.Live) ConnectionBanner(connection)
            if (notice != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        notice,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp).testTag("chat-notice"),
                    )
                    TextButton(onClick = onDismissNotice) { Text("知道了") }
                }
            }
            MessageList(state, onAllow, onDeny, onAnswer, loadAttachment, Modifier.weight(1f))
            if (suggestions.isNotEmpty()) SlashSuggestions(suggestions) { draft = "/$it " }
            ActivityBar(state, connection, active || justSent, onInterrupt)
            Composer(
                draft = draft,
                onDraft = { draft = it },
                drafts = drafts,
                active = active,
                sleeping = state.status == "stopped",
                canSend = (draft.isNotBlank() || drafts.isNotEmpty()) && !uploading,
                onSend = {
                    onSend(draft)
                    draft = ""
                    justSent = true
                },
                onInterrupt = onInterrupt,
                onPickFiles = onPickFiles,
                onRemoveDraft = onRemoveDraft,
            )
        }
    }

    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            properties = HarnessDialogProperties,
            modifier = HarnessDialogModifier,
            title = { Text("模型与 Effort") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // The CLI reports its full model id on start; show the alias the user picked unless they changed it since.
                    val chosen = state.model?.takeIf { m -> MODELS.any { it.first == m } } ?: fallbackModel.ifBlank { state.model.orEmpty() }
                    Dropdown("模型", MODELS, chosen, onSetModel, tag = "chat-model")
                    Dropdown("Effort", EFFORTS, state.effort ?: fallbackEffort, onSetEffort, tag = "chat-effort")
                    Text(
                        "模型与 Effort 立即保存,不会打断当前回合,从下一条消息起生效。",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }, modifier = Modifier.testTag("chat-settings-close")) { Text("完成") } },
        )
    }
}

/** Commands matching what has been typed after a leading "/" (nothing once arguments start). */
fun slashSuggestions(draft: String, commands: List<String>, limit: Int = 30): List<String> {
    if (!draft.startsWith("/") || draft.any { it.isWhitespace() }) return emptyList()
    val q = draft.drop(1).lowercase()
    val (starts, rest) = commands.partition { it.lowercase().startsWith(q) }
    return (starts + rest.filter { q in it.lowercase() }).take(limit)
}

@Composable
private fun SlashSuggestions(items: List<String>, onPick: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).heightIn(max = 220.dp).testTag("slash-list"),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            items.forEach { c ->
                Text(
                    "/$c",
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = 14.dp, vertical = 10.dp).testTag("slash-$c"),
                )
            }
        }
    }
}

@Composable
private fun DraftChips(drafts: List<DraftAttachment>, onRemove: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp).testTag("draft-chips"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        drafts.forEach { d ->
            val suffix = when (val s = d.state) {
                UploadState.Uploading -> " · 上传中…"
                is UploadState.Failed -> " · ${s.message}"
                is UploadState.Done -> if (d.size > 0) " · ${formatSize(d.size)}" else ""
            }
            InputChip(
                selected = false,
                onClick = { onRemove(d.localId) },
                label = { Text((if (d.image) "🖼 " else "📄 ") + d.name + suffix, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Text("×") },
                colors = if (d.state is UploadState.Failed) {
                    InputChipDefaults.inputChipColors(labelColor = MaterialTheme.colorScheme.error)
                } else {
                    InputChipDefaults.inputChipColors()
                },
                modifier = Modifier.widthIn(max = 280.dp).testTag("draft-${d.localId}"),
            )
        }
    }
}

@Composable
private fun ConnectionBanner(connection: Connection) {
    val text = when (connection) {
        Connection.Connecting -> "正在连接…"
        Connection.Live -> return
        Connection.Unauthorized -> "登录已过期"
        is Connection.Failed -> "连接中断,正在重试: ${connection.message}"
    }
    Text(
        text,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("chat-connection"),
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun MessageList(
    state: ChatState,
    onAllow: (String, Boolean) -> Unit,
    onDeny: (String) -> Unit,
    onAnswer: (PermissionItem, Map<String, String>) -> Unit,
    loadAttachment: suspend (Attachment) -> ByteArray?,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    var stick by remember { mutableStateOf(true) }
    val total = state.items.size + if (state.liveText.isNotEmpty()) 1 else 0

    // Follow new messages only while the user is at the bottom; scrolling up pauses it.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect { stick = !listState.canScrollForward }
    }
    LaunchedEffect(total, state.liveText.length) {
        // A huge offset is clamped to the end, so a tall last item shows its bottom, not its top.
        if (stick && total > 0) listState.scrollToItem(total - 1, Int.MAX_VALUE)
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("chat-list"),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            items(state.items, key = { it.key }) { item -> ChatItemView(item, onAllow, onDeny, onAnswer, loadAttachment) }
            if (state.liveText.isNotEmpty()) {
                item(key = "live") { AssistantBubble(state.liveText, live = true) }
            }
        }
    }
}

/** The rounded input card: attachments, text field, "+" attach and a send / stop button. */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    drafts: List<DraftAttachment>,
    active: Boolean,
    sleeping: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onInterrupt: () -> Unit,
    onPickFiles: () -> Unit,
    onRemoveDraft: (Long) -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 2.dp,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                if (drafts.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    DraftChips(drafts, onRemoveDraft)
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RoundIconButton(
                        onClick = onPickFiles,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("chat-attach"),
                    ) { Text("＋", fontSize = 20.sp) }
                    TextField(
                        value = draft,
                        onValueChange = onDraft,
                        placeholder = { Text(if (sleeping) "进程已休眠,发送消息会自动恢复…" else "给 Claude 发消息…") },
                        maxLines = 6,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f).testTag("chat-input"),
                    )
                    if (active && !canSend) {
                        RoundIconButton(
                            onClick = onInterrupt,
                            color = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.testTag("chat-stop"),
                        ) { Text("■", fontSize = 13.sp) }
                    } else {
                        RoundIconButton(
                            onClick = onSend,
                            enabled = canSend,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.testTag("chat-send"),
                        ) { Text("↑", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIconButton(
    onClick: () -> Unit,
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) color else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (enabled) contentColor else MaterialTheme.colorScheme.outline,
        modifier = modifier.padding(bottom = 4.dp).size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** What the session is doing right now, from the newest items of the conversation. */
internal fun activityLabel(state: ChatState): String {
    val last = state.items.lastOrNull()
    return when {
        state.status == "awaiting_permission" || state.pendingPermissions.isNotEmpty() -> "等待你授权"
        state.status != "busy" -> "已发送,等待 Claude 响应…"
        state.liveText.isNotEmpty() -> "正在输出…"
        last is ToolCall && last.result == null -> "运行 ${last.name}…"
        else -> "思考中…"
    }
}

/**
 * One line above the input, like the CLI's "✻ Thinking… (12s · esc to interrupt)": proves the
 * session is alive, says what it is doing and for how long, and turns amber when nothing has
 * arrived for a while (the process is still running; it is just a long step).
 */
@Composable
private fun ActivityBar(state: ChatState, connection: Connection, visible: Boolean, onInterrupt: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var since by remember { mutableLongStateOf(0L) }
    var lastChange by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val connected = connection is Connection.Live
    val show = visible || !connected
    LaunchedEffect(state.lastSeq, state.liveText.length, state.status) { lastChange = System.currentTimeMillis() }
    LaunchedEffect(visible) { since = if (visible) System.currentTimeMillis() else 0L }
    LaunchedEffect(show) {
        while (show) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    if (!show) return

    val quiet = (now - lastChange) / 1000
    val stalled = connected && state.status == "busy" && quiet > 30
    val color = when {
        !connected -> MaterialTheme.colorScheme.error
        state.status == "awaiting_permission" || stalled -> Harness.colors.warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        !connected -> "连接中断,正在重连…"
        stalled -> "${activityLabel(state)} · ${quiet}s 没有新输出(进程仍在运行)"
        else -> activityLabel(state)
    }
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "angle")
    Row(
        Modifier.fillMaxWidth().widthIn(max = 760.dp).padding(horizontal = 18.dp, vertical = 2.dp).testTag("activity-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "✻",
            color = if (connected && !stalled && state.status != "awaiting_permission") Harness.colors.brand else color,
            fontSize = 16.sp,
            modifier = Modifier.rotate(if (connected) angle else 0f),
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = if (connected && !stalled) MaterialTheme.colorScheme.onSurface else color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).testTag("activity-label"),
            )
            if (since > 0) {
                val s = ((now - since) / 1000).coerceAtLeast(0)
                Text(
                    "(" + (if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s") + ")",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("activity-time"),
                )
            }
        }
        if (state.status == "busy" || state.status == "awaiting_permission") {
            TextButton(onClick = onInterrupt, modifier = Modifier.testTag("activity-stop")) {
                Text("中断", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
