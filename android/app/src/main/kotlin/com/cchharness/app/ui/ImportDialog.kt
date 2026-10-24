package com.cchharness.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.cchharness.app.LocalImport

/** Lists conversations of the local Claude Code (terminal / VS Code) and takes one over. */
@Composable
fun ImportDialog(
    state: LocalImport,
    onImport: (String) -> Unit,
    onForce: (String) -> Unit,
    onCancelForce: () -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val shown = state.sessions.filter {
        filter.isBlank() || "${it.title} ${it.cwd}".contains(filter.trim(), ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = HarnessDialogProperties,
        modifier = HarnessDialogModifier,
        title = { Text("接管本机会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "这些是本机 Claude Code(终端 / VS Code)里的会话。接管后沿用同一个会话 ID,手机和网页都能继续,回到终端也能接着聊。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    label = { Text("筛选标题或目录") },
                    modifier = Modifier.fillMaxWidth().testTag("import-filter"),
                )
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("import-error"))
                }
                when {
                    state.loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    shown.isEmpty() -> Text(
                        if (state.sessions.isEmpty()) "没有可接管的本机会话" else "没有匹配的会话",
                        modifier = Modifier.padding(8.dp).testTag("import-empty"),
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.id }) { s ->
                            ListItem(
                                headlineContent = {
                                    Text(s.title.ifBlank { "(无标题)" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        (if (s.active) "可能正在使用 · " else "") + s.cwd,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                trailingContent = if (state.importing == s.id) {
                                    { CircularProgressIndicator(Modifier.padding(4.dp)) }
                                } else null,
                                modifier = Modifier
                                    .clickable(enabled = state.importing == null) { onImport(s.id) }
                                    .testTag("local-${s.id}"),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("import-close")) { Text("关闭") } },
    )

    state.confirmForce?.let { id ->
        AlertDialog(
            onDismissRequest = onCancelForce,
            properties = HarnessDialogProperties,
            modifier = HarnessDialogModifier,
            title = { Text("会话可能仍在使用") },
            text = { Text("这个会话刚刚还在被修改,可能仍在终端或 VS Code 里打开。两边同时写入会让历史错乱,仍要接管吗?") },
            confirmButton = { TextButton(onClick = { onForce(id) }, modifier = Modifier.testTag("import-force")) { Text("仍要接管") } },
            dismissButton = { TextButton(onClick = onCancelForce) { Text("取消") } },
        )
    }
}
