package com.cchharness.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cchharness.app.UpdateState
import com.cchharness.core.Release

/** Offers, downloads and installs a newer build; shows nothing for states without a release. */
@Composable
fun UpdateDialog(
    state: UpdateState,
    currentVersion: String,
    onStart: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val release: Release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        is UpdateState.NeedsPermission -> state.release
        is UpdateState.Failed -> state.release ?: return
        else -> return
    }
    val downloading = state is UpdateState.Downloading

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = HarnessDialogProperties,
        modifier = HarnessDialogModifier,
        title = { Text("发现新版本 ${release.versionName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("当前版本 $currentVersion · 大小 ${formatSize(release.size)}", style = MaterialTheme.typography.bodySmall)
                if (release.notes.isNotBlank()) Text(release.notes, modifier = Modifier.testTag("update-notes"))
                when (state) {
                    is UpdateState.Downloading -> {
                        val p = if (state.total > 0) state.done.toFloat() / state.total else 0f
                        LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().testTag("update-progress"))
                        Text("下载中 ${(p * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    }
                    is UpdateState.NeedsPermission -> Text(
                        "请在系统设置里允许本应用安装未知应用,返回后点「安装」。",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp).testTag("update-permission"),
                    )
                    is UpdateState.Ready -> Text("已下载并校验,系统安装界面确认后即完成更新。", modifier = Modifier.testTag("update-ready"))
                    is UpdateState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("update-error"))
                    else -> {}
                }
            }
        },
        confirmButton = {
            if (!downloading) {
                TextButton(
                    onClick = if (state is UpdateState.Ready || state is UpdateState.NeedsPermission) onInstall else onStart,
                    modifier = Modifier.testTag("update-go"),
                ) {
                    Text(
                        when (state) {
                            is UpdateState.Ready, is UpdateState.NeedsPermission -> "安装"
                            is UpdateState.Failed -> "重试"
                            else -> "下载并安装"
                        },
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("update-later")) { Text(if (downloading) "后台下载" else "稍后") } },
    )
}
