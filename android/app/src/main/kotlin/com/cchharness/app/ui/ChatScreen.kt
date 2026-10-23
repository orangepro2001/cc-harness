package com.cchharness.app.ui

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
) {
    val modes = PERMISSION_MODES.filter { it.first != "bypassPermissions" || allowBypass }
    var menuOpen by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
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
            MessageList(state, onAllow, onDeny, onAnswer, Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("输入消息") },
                    maxLines = 5,
                    modifier = Modifier.weight(1f).testTag("chat-input"),
                )
                Button(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.testTag("chat-send"),
                ) { Text("发送") }
            }
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
        items(state.items, key = { it.key }) { item -> ChatItemView(item, onAllow, onDeny, onAnswer) }
        if (state.liveText.isNotEmpty()) {
            item(key = "live") { AssistantBubble(state.liveText, live = true) }
        }
    }
}
