package com.cchharness.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cchharness.app.Host

/** Lists the saved servers: tap one to switch to it, "编辑" to rename / move / remove it, or add another. */
@Composable
fun HostsDialog(
    hosts: List<Host>,
    activeId: String?,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    /** Returns false when the address is not usable. */
    onEdit: (id: String, name: String, url: String) -> Boolean,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<Host?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = HarnessDialogProperties,
        modifier = HarnessDialogModifier,
        title = { Text("主机") },
        text = {
            if (hosts.isEmpty()) {
                Text("还没有主机", color = muted, modifier = Modifier.testTag("hosts-empty"))
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(hosts, key = { it.id }) { h ->
                        val active = h.id == activeId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSwitch(h.id) }
                                .padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                                .testTag("host-${h.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Dot(if (active) Harness.colors.ok else MaterialTheme.colorScheme.outline, size = 10.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    h.name.ifBlank { h.url },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        h.url,
                                        if (active) "当前" else null,
                                        if (!h.loggedIn) "未登录" else null,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { editing = h }, modifier = Modifier.testTag("host-edit-${h.id}")) {
                                Text("编辑", style = MaterialTheme.typography.labelMedium, color = muted)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd, modifier = Modifier.testTag("host-add")) { Text("＋ 添加主机") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("hosts-close")) { Text("关闭") } },
    )

    editing?.let { h ->
        EditHostDialog(
            host = h,
            onSave = { name, url -> onEdit(h.id, name, url).also { ok -> if (ok) editing = null } },
            onRemove = {
                editing = null
                onRemove(h.id)
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun EditHostDialog(
    host: Host,
    onSave: (name: String, url: String) -> Boolean,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(host.id) { mutableStateOf(host.name) }
    var url by rememberSaveable(host.id) { mutableStateOf(host.url) }
    var invalid by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = HarnessDialogProperties,
        modifier = HarnessDialogModifier,
        title = { Text("编辑主机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    shape = RoundedCornerShape(12.dp),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("host-name"),
                )
                OutlinedTextField(
                    shape = RoundedCornerShape(12.dp),
                    value = url,
                    onValueChange = {
                        url = it
                        invalid = false
                    },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("host-url"),
                )
                if (invalid) {
                    Text("服务器地址无效", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("host-url-error"))
                } else if (url.isNotBlank() && url.trim().trimEnd('/') != host.url.trimEnd('/') && host.loggedIn) {
                    Text("改了地址就是另一台服务器,需要重新登录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(
                    onClick = { if (confirmRemove) onRemove() else confirmRemove = true },
                    modifier = Modifier.testTag("host-remove"),
                ) {
                    Text(
                        if (confirmRemove) "再点一次确认移除并退出登录" else "移除这台主机",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { invalid = !onSave(name, url) },
                enabled = url.isNotBlank(),
                modifier = Modifier.testTag("host-save"),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
