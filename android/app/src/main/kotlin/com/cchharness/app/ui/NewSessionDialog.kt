package com.cchharness.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.cchharness.core.CreateSession
import com.cchharness.core.DirListing
import com.cchharness.core.EFFORTS
import com.cchharness.core.MODELS
import com.cchharness.core.Me
import com.cchharness.core.PERMISSION_MODES

@Composable
fun NewSessionDialog(
    me: Me,
    error: String?,
    load: suspend (path: String?, hidden: Boolean) -> DirListing,
    makeDir: suspend (parent: String, name: String) -> String,
    onCreate: (CreateSession) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var effort by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("auto") }
    var picking by remember { mutableStateOf(false) }
    val modes = PERMISSION_MODES.filter { it.first != "bypassPermissions" || me.allowBypass }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = HarnessDialogProperties,
        modifier = HarnessDialogModifier,
        title = { Text("新建会话") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(100) },
                    label = { Text("标题(可空)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new-title"),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cwd,
                        onValueChange = { cwd = it },
                        label = { Text("工作目录") },
                        placeholder = { Text("留空 = 个人工作区") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("new-cwd"),
                    )
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag("new-pick")) { Text("选择…") }
                }
                Dropdown("模型", MODELS, model, { model = it }, tag = "new-model")
                Dropdown("Effort", EFFORTS, effort, { effort = it }, tag = "new-effort")
                Dropdown("权限", modes, mode, { mode = it }, tag = "new-mode")
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp).testTag("new-error"))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(CreateSession(title = title.trim(), cwd = cwd.trim(), model = model, effort = effort, permissionMode = mode)) },
                modifier = Modifier.testTag("new-create"),
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (picking) {
        DirectoryPickerDialog(
            initialPath = cwd.trim().takeIf { it.startsWith("/") }.orEmpty(),
            load = load,
            makeDir = makeDir,
            onSelect = {
                cwd = it
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}
