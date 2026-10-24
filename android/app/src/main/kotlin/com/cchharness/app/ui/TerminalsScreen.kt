package com.cchharness.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.cchharness.core.CreateTerminal
import com.cchharness.core.DirListing
import com.cchharness.core.TerminalInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalsScreen(
    terminals: List<TerminalInfo>,
    error: String?,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onKill: (String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmKill by remember { mutableStateOf<TerminalInfo?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("terminals-back")) {
                        Text("‹", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                title = { Text("终端", style = MaterialTheme.typography.titleLarge) },
                actions = { TextButton(onClick = onRefresh, modifier = Modifier.testTag("terminals-refresh")) { Text("刷新", color = muted) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.testTag("new-terminal"),
            ) { Text("＋ 新终端") }
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                        .testTag("terminals-error"),
                )
            }
            Text(
                "终端是服务器上的真实 shell,断开后会继续运行,可随时回来。",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (terminals.isEmpty()) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("＞_", style = MaterialTheme.typography.headlineMedium, color = Harness.colors.brand)
                    Spacer(Modifier.height(6.dp))
                    Text("还没有终端,点右下角新建", style = MaterialTheme.typography.bodyMedium, color = muted)
                    Spacer(Modifier.height(96.dp))
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(terminals, key = { it.id }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(t.id) }
                                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp).testTag("terminal-${t.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Dot(if (t.attached > 0) Harness.colors.ok else MaterialTheme.colorScheme.outline, size = 10.dp)
                            Column(Modifier.weight(1f)) {
                                Text(t.title.ifBlank { t.id }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (if (t.kind == "claude") "Claude Code · " else "") + t.cwd,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = Mono,
                                    color = muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { confirmKill = t }, modifier = Modifier.testTag("kill-${t.id}")) {
                                Text("结束", style = MaterialTheme.typography.labelMedium, color = muted)
                            }
                        }
                    }
                }
            }
        }
    }

    confirmKill?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmKill = null },
            properties = HarnessDialogProperties,
            modifier = HarnessDialogModifier,
            title = { Text("结束终端") },
            text = { Text("关闭「${t.title.ifBlank { t.id }}」及其中运行的程序?") },
            confirmButton = {
                TextButton(onClick = { confirmKill = null; onKill(t.id) }, modifier = Modifier.testTag("confirm-kill")) {
                    Text("结束", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmKill = null }) { Text("取消") } },
        )
    }
}

@Composable
fun NewTerminalDialog(
    error: String?,
    load: suspend (path: String?, hidden: Boolean) -> DirListing,
    makeDir: suspend (parent: String, name: String) -> String,
    onCreate: (CreateTerminal) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = HarnessDialogProperties,
        modifier = HarnessDialogModifier,
        title = { Text("新建终端") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(100) },
                    label = { Text("名称(可空)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("nt-title"),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cwd,
                        onValueChange = { cwd = it },
                        label = { Text("起始目录") },
                        placeholder = { Text("留空 = 个人工作区") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).testTag("nt-cwd"),
                    )
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag("nt-pick")) { Text("选择…") }
                }
                Text("在服务器上以服务用户的身份运行 shell,可以 cd 到任意位置。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("nt-error"))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(CreateTerminal(cwd = cwd.trim(), title = title.trim())) },
                modifier = Modifier.testTag("nt-create"),
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (picking) {
        DirectoryPickerDialog(
            initialPath = cwd.trim().takeIf { it.startsWith("/") }.orEmpty(),
            load = load,
            makeDir = makeDir,
            onSelect = { cwd = it; picking = false },
            onDismiss = { picking = false },
        )
    }
}
