package com.cchharness.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    val modes = PERMISSION_MODES.filter { it.first != "bypassPermissions" || allowBypass }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val uploading = drafts.any { it.state is UploadState.Uploading }
    val suggestions = slashSuggestions(draft, state.commands.ifEmpty { commands })
    val active = state.status == "busy" || state.status == "awaiting_permission"

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack, modifier = Modifier.testTag("chat-back")) { Text("←") } },
                title = {
                    Column {
                        Text(title.ifBlank { "(未命名)" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cwd, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    AssistChip(onClick = {}, label = { Text(statusLabel(state.status)) }, modifier = Modifier.testTag("chat-status"))
                    Box {
                        TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("chat-menu")) { Text("⋮") }
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
        Column(Modifier.padding(inner).fillMaxSize().imePadding()) {
            if (connection !is Connection.Live) ConnectionBanner(connection)
            if (notice != null) {
                Text(
                    notice,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("chat-notice"),
                )
                TextButton(onClick = onDismissNotice) { Text("知道了") }
            }
            MessageList(state, onAllow, onDeny, onAnswer, loadAttachment, Modifier.weight(1f))
            if (suggestions.isNotEmpty()) SlashSuggestions(suggestions) { draft = "/$it " }
            if (drafts.isNotEmpty()) DraftChips(drafts, onRemoveDraft)
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedButton(onClick = onPickFiles, modifier = Modifier.testTag("chat-attach")) { Text("＋") }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("输入消息,/ 调出命令") },
                    maxLines = 5,
                    modifier = Modifier.weight(1f).testTag("chat-input"),
                )
                Button(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = (draft.isNotBlank() || drafts.isNotEmpty()) && !uploading,
                    modifier = Modifier.testTag("chat-send"),
                ) { Text("发送") }
            }
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
                        "Effort 在下一条消息起生效(空闲时会自动重启后台进程并恢复上下文)。",
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
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(max = 220.dp).testTag("slash-list"),
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
            .padding(horizontal = 12.dp, vertical = 4.dp)
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
        if (stick && total > 0) listState.scrollToItem(total - 1)
    }

    LazyColumn(
        modifier.fillMaxWidth().testTag("chat-list"),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        items(state.items, key = { it.key }) { item -> ChatItemView(item, onAllow, onDeny, onAnswer, loadAttachment) }
        if (state.liveText.isNotEmpty()) {
            item(key = "live") { AssistantBubble(state.liveText, live = true) }
        }
    }
}
