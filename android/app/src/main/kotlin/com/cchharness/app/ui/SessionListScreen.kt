package com.cchharness.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import com.cchharness.app.UpdateState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cchharness.core.SessionInfo
import com.cchharness.core.Usage

fun statusLabel(status: String): String = when (status) {
    "idle" -> "空闲"
    "busy" -> "运行中"
    "awaiting_permission" -> "等待授权"
    "stopped" -> "进程未运行"
    else -> status
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    user: String,
    sessions: List<SessionInfo>,
    notice: String?,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onDismissNotice: () -> Unit,
    versionName: String = "",
    update: UpdateState = UpdateState.Idle,
    onCheckUpdate: () -> Unit = {},
    onShowUpdate: () -> Unit = {},
    usage: Usage? = null,
    usageError: String? = null,
    onImport: () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf<SessionInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("会话 · $user") },
                actions = {
                    TextButton(onClick = onRefresh, modifier = Modifier.testTag("refresh")) { Text("刷新") }
                    TextButton(onClick = onLogout, modifier = Modifier.testTag("logout")) { Text("退出") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, modifier = Modifier.testTag("new-session")) { Text("＋ 新建会话") }
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (notice != null) {
                Text(
                    notice,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDismissNotice).padding(12.dp).testTag("notice"),
                )
            }
            val offered = update is UpdateState.Available || update is UpdateState.Downloading ||
                update is UpdateState.Ready || update is UpdateState.NeedsPermission
            if (offered) {
                Text(
                    "有新版本可更新,点这里查看",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(onClick = onShowUpdate).padding(12.dp).testTag("update-banner"),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (versionName.isNotBlank()) "版本 $versionName" else "",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f).testTag("app-version"),
                )
                TextButton(onClick = onImport, modifier = Modifier.testTag("import-local")) { Text("接管本机会话") }
                TextButton(onClick = onCheckUpdate, enabled = update !is UpdateState.Checking, modifier = Modifier.testTag("check-update")) {
                    Text(if (update is UpdateState.Checking) "检查中…" else "检查更新")
                }
            }
            UsagePanel(usage, usageError)
            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有会话,点右下角新建", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
                    items(sessions, key = { it.id }) { s ->
                        ListItem(
                            headlineContent = {
                                Text(s.title.ifBlank { "(未命名)" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text("${statusLabel(s.status)} · ${s.cwd}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                TextButton(onClick = { confirmDelete = s }, modifier = Modifier.testTag("delete-${s.id}")) { Text("删除") }
                            },
                            modifier = Modifier.clickable { onOpen(s.id) }.testTag("session-${s.id}"),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            properties = HarnessDialogProperties,
            modifier = HarnessDialogModifier,
            title = { Text("删除会话") },
            text = { Text("删除「${s.title.ifBlank { "(未命名)" }}」及其全部记录?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        onDelete(s.id)
                    },
                    modifier = Modifier.testTag("confirm-delete"),
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}
