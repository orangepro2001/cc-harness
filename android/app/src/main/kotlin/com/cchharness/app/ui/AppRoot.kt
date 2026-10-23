package com.cchharness.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.cchharness.app.ui.ChatScreen
import com.cchharness.app.ui.ConnectScreen
import com.cchharness.app.ui.NewSessionDialog
import com.cchharness.app.ui.SessionListScreen

/** Chooses the screen for the current [AppViewModel] state. */
@Composable
fun AppRoot(vm: AppViewModel) {
    val screen by vm.screen.collectAsState()
    Surface(Modifier.fillMaxSize()) {
        when (val s = screen) {
            Screen.Starting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.testTag("starting"))
            }
            Screen.Connect -> {
                val busy by vm.busy.collectAsState()
                val error by vm.connectError.collectAsState()
                ConnectScreen(vm.savedUrl, vm.savedUser, busy, error, vm::connect)
            }
            Screen.Sessions -> SessionsRoute(vm)
            is Screen.Chat -> ChatRoute(vm, s.id)
        }
    }
}

@Composable
private fun SessionsRoute(vm: AppViewModel) {
    val sessions by vm.sessions.collectAsState()
    val me by vm.me.collectAsState()
    val notice by vm.notice.collectAsState()
    val createError by vm.createError.collectAsState()
    var creating by remember { mutableStateOf(false) }

    SessionListScreen(
        user = me?.user.orEmpty(),
        sessions = sessions,
        notice = notice,
        onOpen = vm::open,
        onNew = {
            vm.clearCreateError()
            creating = true
        },
        onDelete = vm::deleteSession,
        onRefresh = vm::refresh,
        onLogout = vm::logout,
        onDismissNotice = vm::dismissNotice,
    )

    val who = me
    if (creating && who != null) {
        NewSessionDialog(
            me = who,
            error = createError,
            load = vm::listDirs,
            makeDir = vm::makeDir,
            onCreate = vm::createSession,
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun ChatRoute(vm: AppViewModel, id: String) {
    val chat by vm.chat.collectAsState()
    val notice by vm.notice.collectAsState()
    val me by vm.me.collectAsState()
    BackHandler(onBack = vm::closeChat)

    val open = chat?.takeIf { it.info.id == id } ?: return
    val state by open.controller.state.collectAsState()
    val connection by open.controller.connection.collectAsState()
    ChatScreen(
        title = open.info.title,
        cwd = open.info.cwd,
        state = state,
        connection = connection,
        fallbackMode = open.info.permissionMode,
        allowBypass = me?.allowBypass == true,
        notice = notice,
        onBack = vm::closeChat,
        onSend = vm::send,
        onInterrupt = vm::interrupt,
        onStopProcess = vm::stopProcess,
        onSetMode = vm::setMode,
        onAllow = vm::allow,
        onDeny = vm::deny,
        onAnswer = vm::answer,
        onDismissNotice = vm::dismissNotice,
    )
}
