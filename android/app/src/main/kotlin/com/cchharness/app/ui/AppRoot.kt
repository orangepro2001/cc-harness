package com.cchharness.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.cchharness.app.ui.ImportDialog
import com.cchharness.app.ui.NewSessionDialog
import com.cchharness.app.ui.SessionListScreen
import com.cchharness.app.ui.NewTerminalDialog
import com.cchharness.app.ui.TerminalScreen
import com.cchharness.app.ui.TerminalsScreen
import com.cchharness.app.ui.UpdateDialog

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
            Screen.Terminals -> TerminalsRoute(vm)
            is Screen.Terminal -> TerminalRoute(vm, s.id)
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

    val update by vm.update.collectAsState()
    val updateDialog by vm.updateDialog.collectAsState()
    val usage by vm.usage.collectAsState()
    val usageError by vm.usageError.collectAsState()
    val localImport by vm.localImport.collectAsState()

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
        versionName = vm.versionName,
        update = update,
        onCheckUpdate = { vm.checkForUpdate(manual = true) },
        onShowUpdate = vm::showUpdateDialog,
        usage = usage,
        usageError = usageError,
        onImport = vm::openLocalImport,
        showTerminal = me?.terminal == true,
        onTerminals = vm::openTerminals,
    )

    localImport?.let {
        ImportDialog(it, vm::importLocal, { id -> vm.importLocal(id, force = true) }, vm::cancelForce, vm::closeLocalImport)
    }

    if (updateDialog) {
        UpdateDialog(update, vm.versionName, vm::startUpdate, vm::installUpdate, vm::dismissUpdateDialog)
    }

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
    val drafts by vm.drafts.collectAsState()
    val commands by vm.commands.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        vm.addAttachments(uris.map { it.toString() })
    }
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
        drafts = drafts,
        commands = commands,
        fallbackModel = open.info.model,
        fallbackEffort = open.info.effort,
        onPickFiles = { picker.launch("*/*") },
        onRemoveDraft = vm::removeAttachment,
        onSetModel = vm::setModel,
        onSetEffort = vm::setEffort,
        loadAttachment = vm::loadAttachment,
        onTakeOver = if (me?.terminal == true) vm::takeOverInTerminal else null,
    )
}

@Composable
private fun TerminalsRoute(vm: AppViewModel) {
    val terminals by vm.terminals.collectAsState()
    val error by vm.terminalError.collectAsState()
    var creating by remember { mutableStateOf(false) }
    BackHandler(onBack = vm::closeTerminals)
    TerminalsScreen(
        terminals = terminals,
        error = error,
        onOpen = vm::openTerminal,
        onNew = { creating = true },
        onKill = vm::killTerminal,
        onRefresh = vm::refreshTerminals,
        onBack = vm::closeTerminals,
    )
    if (creating) {
        NewTerminalDialog(
            error = error,
            load = vm::listDirs,
            makeDir = vm::makeDir,
            onCreate = {
                creating = false
                vm.createTerminal(it)
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun TerminalRoute(vm: AppViewModel, id: String) {
    val open = vm.terminal.collectAsState().value?.takeIf { it.info.id == id } ?: return
    val font by vm.termFont.collectAsState()
    BackHandler(onBack = vm::closeTerminalScreen)
    TerminalScreen(
        title = open.info.title,
        controller = open.controller,
        fontSizeSp = font,
        onFontSize = vm::setTermFont,
        onBack = vm::closeTerminalScreen,
        onClose = { vm.killTerminal(id) },
    )
}
