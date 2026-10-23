package com.cchharness.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cchharness.core.DirListing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Browses the server's directories (only those the server allows) and returns
 * the chosen working directory. [initialPath] empty starts at the roots.
 */
@Composable
fun DirectoryPickerDialog(
    initialPath: String,
    load: suspend (path: String?, hidden: Boolean) -> DirListing,
    makeDir: suspend (parent: String, name: String) -> String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(initialPath) }
    var hidden by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf<DirListing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    // why we fell back to the roots; shown once the roots have loaded
    var carried by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path, hidden, reloadTick) {
        try {
            listing = load(path.ifEmpty { null }, hidden)
            error = carried
            carried = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            if (path.isNotEmpty() && listing == null) {
                // an invalid starting point (e.g. a stale path) falls back to the roots
                carried = "无法打开 $path: $msg"
                path = ""
            } else {
                error = msg
            }
        }
    }

    val current = listing
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = HarnessDialogProperties,
        modifier = HarnessDialogModifier,
        title = { Text("选择工作目录") },
        text = {
            Column {
                Text(
                    current?.path?.ifEmpty { "(根目录)" } ?: "加载中…",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag("picker-path"),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("picker-list")) {
                    if (current != null && current.path.isNotEmpty()) {
                        item(key = "up") {
                            Text(
                                "⬆ 上级目录",
                                modifier = Modifier.fillMaxWidth().clickable { path = current.parent }.padding(vertical = 10.dp).testTag("picker-up"),
                            )
                            HorizontalDivider()
                        }
                    }
                    items(current?.entries.orEmpty(), key = { it.path }) { e ->
                        Text(
                            (if (current?.path.isNullOrEmpty()) "🏠 " else "📁 ") + e.name,
                            modifier = Modifier.fillMaxWidth().clickable { path = e.path }.padding(vertical = 10.dp).testTag("picker-entry-${e.name}"),
                        )
                        HorizontalDivider()
                    }
                    if (current != null && current.entries.isEmpty()) {
                        item(key = "empty") { Text("(没有子目录)", modifier = Modifier.padding(vertical = 10.dp)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // the whole label is the touch target, not just the small box
                    Row(
                        Modifier
                            .toggleable(value = hidden, role = Role.Checkbox, onValueChange = { hidden = it })
                            .padding(end = 12.dp)
                            .testTag("picker-hidden"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = hidden, onCheckedChange = null)
                        Text("显示隐藏目录")
                    }
                    TextButton(
                        onClick = { asking = true },
                        enabled = current != null && current.path.isNotEmpty(),
                        modifier = Modifier.testTag("picker-mkdir"),
                    ) { Text("新建文件夹") }
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("picker-error"))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { current?.path?.takeIf { it.isNotEmpty() }?.let(onSelect) },
                enabled = current != null && current.path.isNotEmpty(),
                modifier = Modifier.testTag("picker-select"),
            ) { Text("选择此目录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (asking && current != null) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { asking = false },
            properties = HarnessDialogProperties,
            modifier = HarnessDialogModifier,
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称") },
                    modifier = Modifier.testTag("mkdir-name"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parent = current.path
                        val n = name.trim()
                        asking = false
                        scope.launch {
                            try {
                                path = makeDir(parent, n) // step into the new folder
                                reloadTick++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: e.toString()
                            }
                        }
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.testTag("mkdir-confirm"),
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("取消") } },
        )
    }
}
