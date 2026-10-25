package com.cchharness.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cchharness.app.UpdateState
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
    showTerminal: Boolean = false,
    onTerminals: () -> Unit = {},
    hostName: String = "",
    onHosts: (() -> Unit)? = null,
) {
    var confirmDelete by remember { mutableStateOf<SessionInfo?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column(
                        if (onHosts != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onHosts).testTag("host-switcher") else Modifier,
                    ) {
                        BrandTitle()
                        val sub = if (onHosts != null && hostName.isNotBlank()) "$hostName ▾" else user
                        if (sub.isNotBlank()) {
                            Text(sub, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("host-label"))
                        }
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh, modifier = Modifier.testTag("refresh")) { Text("刷新", color = muted) }
                    TextButton(onClick = onLogout, modifier = Modifier.testTag("logout")) { Text("退出", color = muted) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.testTag("new-session"),
            ) { Text("＋ 新建会话") }
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (notice != null) {
                Text(
                    notice,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
                        .clickable(onClick = onDismissNotice).padding(12.dp).testTag("notice"),
                )
            }
            val offered = update is UpdateState.Available || update is UpdateState.Downloading ||
                update is UpdateState.Ready || update is UpdateState.NeedsPermission
            if (offered) {
                Text(
                    "有新版本可更新,点这里查看",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
                        .clickable(onClick = onShowUpdate).padding(12.dp).testTag("update-banner"),
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = onImport,
                    label = { Text("接管本机会话") },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = null,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.testTag("import-local"),
                )
                if (showTerminal) {
                    AssistChip(
                        onClick = onTerminals,
                        label = { Text("＞_ 终端") },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = null,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.testTag("open-terminals"),
                    )
                }
                AssistChip(
                    onClick = onCheckUpdate,
                    enabled = update !is UpdateState.Checking,
                    label = { Text(if (update is UpdateState.Checking) "检查中…" else "检查更新") },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = null,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.testTag("check-update"),
                )
                Text(
                    if (versionName.isNotBlank()) "版本 $versionName" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    modifier = Modifier.testTag("app-version"),
                )
            }
            UsagePanel(usage, usageError, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            if (sessions.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✻", color = Harness.colors.brand, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("还没有会话,点右下角新建", style = MaterialTheme.typography.bodyMedium, color = muted)
                    Spacer(Modifier.height(96.dp))
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(sessions, key = { it.id }) { s -> SessionRow(s, onOpen, onAskDelete = { confirmDelete = s }) }
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
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SessionRow(s: SessionInfo, onOpen: (String) -> Unit, onAskDelete: () -> Unit) {
    val live = s.status == "busy" || s.status == "awaiting_permission"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onOpen(s.id) }
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp)
            .testTag("session-${s.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Dot(statusColor(s.status), pulse = live, size = 10.dp)
        Column(Modifier.weight(1f)) {
            Text(s.title.ifBlank { "(未命名)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${statusLabel(s.status)} · ${s.cwd}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onAskDelete, modifier = Modifier.testTag("delete-${s.id}")) {
            Text("删除", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
