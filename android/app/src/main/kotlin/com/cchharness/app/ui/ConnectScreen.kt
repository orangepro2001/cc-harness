package com.cchharness.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
    onConnect: (url: String, user: String, password: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var user by rememberSaveable { mutableStateOf(initialUser) }
    var password by rememberSaveable { mutableStateOf("") }
    val ready = url.isNotBlank() && user.isNotBlank() && password.isNotEmpty() && !busy

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("CC Harness", style = MaterialTheme.typography.headlineMedium)
        Text("连接到常驻的 Claude Code 服务", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
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
            value = user,
            onValueChange = { user = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("connect-user"),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
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
            onClick = { onConnect(url, user, password) },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().testTag("connect-button"),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("连接")
        }
    }
}
