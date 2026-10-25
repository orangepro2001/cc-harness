package com.cchharness.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ConnectScreen(
    initialUrl: String,
    initialUser: String,
    busy: Boolean,
    error: String?,
    askName: Boolean = false,
    hostName: String = "",
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onHosts: (() -> Unit)? = null,
    onConnect: (url: String, user: String, password: String, name: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val ready = url.isNotBlank() && password.isNotEmpty() && !busy

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("✻", color = Harness.colors.brand, style = MaterialTheme.typography.headlineMedium)
        Text("CC Harness", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (hostName.isNotBlank()) "登录到 $hostName" else "连接到常驻的 Claude Code 服务",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("connect-subtitle"),
        )
        Spacer(Modifier.height(24.dp))
        if (askName) {
            OutlinedTextField(
                shape = RoundedCornerShape(12.dp),
                value = name,
                onValueChange = { name = it },
                label = { Text("名称(可选)") },
                placeholder = { Text("例如 家里 / 云服务器") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("connect-name"),
            )
            Spacer(Modifier.height(12.dp))
        }
        OutlinedTextField(
            shape = RoundedCornerShape(12.dp),
            value = url,
            onValueChange = { url = it },
            label = { Text("服务器地址") },
            placeholder = { Text("192.168.1.164:8765") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("connect-url"),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            shape = RoundedCornerShape(12.dp),
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("connect-password"),
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("connect-error"))
        }
        Spacer(Modifier.height(16.dp))
        Button(
            // The server runs as one user and ignores the name; it is only sent for servers that still tell users apart.
            onClick = { onConnect(url, initialUser.ifBlank { DEFAULT_USER }, password, name) },
            enabled = ready,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("connect-button"),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("连接")
        }
        if (onRetry != null || onHosts != null || onCancel != null) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (onRetry != null) {
                    TextButton(onClick = onRetry, modifier = Modifier.testTag("connect-retry")) { Text("用已保存的登录重试") }
                }
                if (onHosts != null) {
                    TextButton(onClick = onHosts, modifier = Modifier.testTag("connect-hosts")) { Text("切换主机") }
                }
                if (onCancel != null) {
                    TextButton(onClick = onCancel, modifier = Modifier.testTag("connect-cancel")) { Text("取消") }
                }
            }
        }
    }
}

private const val DEFAULT_USER = "tony"
