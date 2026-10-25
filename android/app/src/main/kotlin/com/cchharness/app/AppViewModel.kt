package com.cchharness.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cchharness.core.ApiException
import com.cchharness.core.Attachment
import com.cchharness.core.ChecksumException
import com.cchharness.core.DEFAULT_MAX_UPLOAD_BYTES
import com.cchharness.core.MAX_ATTACHMENTS
import com.cchharness.core.AuthException
import com.cchharness.core.CreateSession
import com.cchharness.core.CreateTerminal
import com.cchharness.core.TerminalController
import com.cchharness.core.TerminalInfo
import com.cchharness.core.DirListing
import com.cchharness.core.HarnessApi
import com.cchharness.core.LocalSession
import com.cchharness.core.Me
import com.cchharness.core.PermissionItem
import com.cchharness.core.RateLimitedException
import com.cchharness.core.SessionController
import com.cchharness.core.SessionInfo
import com.cchharness.core.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID

sealed interface Screen {
    data object Starting : Screen
    data object Connect : Screen
    data object Sessions : Screen
    data class Chat(val id: String) : Screen
    data object Terminals : Screen
    data class Terminal(val id: String) : Screen
}

/** The chat the user is currently looking at. */
class OpenChat(val info: SessionInfo, val controller: SessionController)

/** The terminal the user is currently looking at. */
class OpenTerminal(val info: TerminalInfo, val controller: TerminalController)

/** Progress of the "take over a local session" dialog. */
data class LocalImport(
    val loading: Boolean = false,
    val sessions: List<LocalSession> = emptyList(),
    val error: String? = null,
    /** id being imported right now */
    val importing: String? = null,
    /** id that looks in use elsewhere and awaits the user's go-ahead */
    val confirmForce: String? = null,
)

class AppViewModel(
    private val settings: SettingsStore,
    private val apiFactory: (baseUrl: String) -> HarnessApi,
    private val pollIntervalMs: Long = 4_000,
    private val resolver: AttachmentResolver = AttachmentResolver { null },
    private val version: AppVersion = AppVersion(0, ""),
    private val updateDir: File? = null,
    private val installer: ApkInstaller? = null,
    private val newHostId: () -> String = { UUID.randomUUID().toString().take(8) },
) : ViewModel() {
    private val _screen = MutableStateFlow<Screen>(Screen.Starting)
    private val _busy = MutableStateFlow(false)
    private val _connectError = MutableStateFlow<String?>(null)
    private val _me = MutableStateFlow<Me?>(null)
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    private val _notice = MutableStateFlow<String?>(null)
    private val _createError = MutableStateFlow<String?>(null)
    private val _chat = MutableStateFlow<OpenChat?>(null)
    private val _drafts = MutableStateFlow<List<DraftAttachment>>(emptyList())
    private val _commands = MutableStateFlow<List<String>>(emptyList())
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val _updateDialog = MutableStateFlow(false)
    private val _usage = MutableStateFlow<Usage?>(null)
    private val _usageError = MutableStateFlow<String?>(null)
    private val _local = MutableStateFlow<LocalImport?>(null)
    private val _terminals = MutableStateFlow<List<TerminalInfo>>(emptyList())
    private val _terminalError = MutableStateFlow<String?>(null)
    private val _terminal = MutableStateFlow<OpenTerminal?>(null)
    private val _termFont = MutableStateFlow(settings.termFontSp)
    private val _hosts = MutableStateFlow(settings.hosts)
    private val _active = MutableStateFlow(settings.hosts.firstOrNull { it.id == settings.activeHostId } ?: settings.hosts.firstOrNull())
    private val _connectTarget = MutableStateFlow<Host?>(null)
    private val _hostsDialog = MutableStateFlow(false)

    val screen: StateFlow<Screen> = _screen
    val busy: StateFlow<Boolean> = _busy
    val connectError: StateFlow<String?> = _connectError
    val me: StateFlow<Me?> = _me
    val sessions: StateFlow<List<SessionInfo>> = _sessions
    val notice: StateFlow<String?> = _notice
    val createError: StateFlow<String?> = _createError
    val chat: StateFlow<OpenChat?> = _chat

    /** Files picked for the next message in the open chat. */
    val drafts: StateFlow<List<DraftAttachment>> = _drafts

    /** Slash commands offered while typing "/" (the chat's own list arrives with its first turn). */
    val commands: StateFlow<List<String>> = _commands
    val update: StateFlow<UpdateState> = _update
    val updateDialog: StateFlow<Boolean> = _updateDialog
    val versionName: String get() = version.name

    /** Plan usage of the server's Claude account (null until the first answer). */
    val usage: StateFlow<Usage?> = _usage
    val usageError: StateFlow<String?> = _usageError

    /** State of the "take over a local session" dialog; null while it is closed. */
    val localImport: StateFlow<LocalImport?> = _local

    /** Terminals of the user (only when the server allows them over this connection, see [Me.terminal]). */
    val terminals: StateFlow<List<TerminalInfo>> = _terminals
    val terminalError: StateFlow<String?> = _terminalError
    val terminal: StateFlow<OpenTerminal?> = _terminal
    val termFont: StateFlow<Int> = _termFont

    /** All saved servers, and the one the app is showing. */
    val hosts: StateFlow<List<Host>> = _hosts
    val activeHost: StateFlow<Host?> = _active

    /** Server the connect screen logs in to; null while adding a new one. */
    val connectTarget: StateFlow<Host?> = _connectTarget

    /** Whether the server list is open. */
    val hostsDialog: StateFlow<Boolean> = _hostsDialog

    private var api: HarnessApi? = null
    private var poller: Job? = null
    private var connectJob: Job? = null
    private var updateJob: Job? = null
    private var checkedForUpdate = false
    private var nextDraftId = 1L
    private val thumbnails = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > THUMBNAIL_CACHE
    }

    init {
        connectJob = viewModelScope.launch { restore() }
    }

    // ---- connection ----

    /** Opens the active host with its saved token, or asks for a login. */
    private suspend fun restore() {
        val host = _active.value
        _connectTarget.value = host
        if (host == null || !host.loggedIn) {
            _screen.value = Screen.Connect
            return
        }
        try {
            val client = apiFactory(host.url).also { it.token = host.token }
            _me.value = client.me()
            api = client
            enterSessions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            forgetToken(host.id)
            _connectTarget.value = _active.value
            _connectError.value = "登录已过期,请重新登录"
            _screen.value = Screen.Connect
        } catch (e: Exception) {
            _connectError.value = "无法连接服务器: ${e.message ?: e}"
            _screen.value = Screen.Connect
        }
    }

    /** Tries the active host again with its saved token (after it was unreachable). */
    fun retry() {
        if (_busy.value) return
        _connectError.value = null
        _screen.value = Screen.Starting
        connectJob?.cancel()
        connectJob = viewModelScope.launch { restore() }
    }

    fun connect(rawUrl: String, user: String, password: String, name: String = "") {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _connectError.value = null
            try {
                val url = normalizeUrl(rawUrl)
                val client = apiFactory(url)
                val login = client.login(user.trim().lowercase(), password)
                val me = client.me()
                // logging in again to a saved server (same entry or same address) keeps its name
                val known = _connectTarget.value ?: _hosts.value.firstOrNull { it.url == url }
                val host = Host(
                    id = known?.id ?: newHostId(),
                    name = known?.name?.takeIf { it.isNotBlank() } ?: name.trim().ifBlank { defaultHostName(url) },
                    url = url,
                    user = login.user,
                    token = login.token,
                )
                saveHost(host)
                activate(host)
                _me.value = me
                api = client
                enterSessions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitedException) {
                _connectError.value = "失败次数过多,请 ${e.retryAfterSeconds} 秒后再试"
            } catch (e: AuthException) {
                _connectError.value = "密码错误"
            } catch (e: ApiException) {
                _connectError.value = "服务器返回错误: ${e.message}"
            } catch (e: IOException) {
                _connectError.value = "无法连接: ${e.message ?: e}"
            } catch (e: IllegalArgumentException) {
                _connectError.value = "服务器地址无效"
            } finally {
                _busy.value = false
            }
        }
    }

    /** Logs out of the active host; it stays in the list and only needs the password again. */
    fun logout() {
        val client = api
        val id = _active.value?.id
        viewModelScope.launch {
            runCatching { client?.logout() }
            if (id != null) forgetToken(id)
            dropConnection()
            _connectTarget.value = _active.value
            _screen.value = Screen.Connect
        }
    }

    private fun authLost() {
        _active.value?.id?.let(::forgetToken)
        dropConnection()
        _connectTarget.value = _active.value
        _connectError.value = "登录已过期,请重新登录"
        _screen.value = Screen.Connect
    }

    /** Stops everything tied to the current server and clears what it showed. */
    private fun dropConnection() {
        connectJob?.cancel()
        leaveChat()
        leaveTerminal()
        poller?.cancel()
        api = null
        _me.value = null
        _sessions.value = emptyList()
        _terminals.value = emptyList()
        _usage.value = null
        _usageError.value = null
        _notice.value = null
        _local.value = null
        _connectError.value = null
    }

    // ---- hosts ----

    private fun saveHost(host: Host) {
        val list = _hosts.value
        _hosts.value = if (list.any { it.id == host.id }) list.map { if (it.id == host.id) host else it } else list + host
        settings.hosts = _hosts.value
        if (_active.value?.id == host.id) _active.value = host
    }

    private fun forgetToken(id: String) {
        _hosts.value.firstOrNull { it.id == id }?.let { saveHost(it.copy(token = "")) }
    }

    /** Makes [host] the one the app shows (and opens on next start), leaving the previous server's state behind. */
    private fun activate(host: Host) {
        dropConnection()
        settings.activeHostId = host.id
        _active.value = host
        _connectTarget.value = host
    }

    fun openHosts() {
        _hostsDialog.value = true
    }

    fun closeHosts() {
        _hostsDialog.value = false
    }

    /** Switches to another saved server; its sessions are loaded fresh. */
    fun switchHost(id: String) {
        val host = _hosts.value.firstOrNull { it.id == id } ?: return
        _hostsDialog.value = false
        if (host.id == _active.value?.id && api != null) return
        activate(host)
        _screen.value = Screen.Starting
        connectJob = viewModelScope.launch { restore() }
    }

    /** Opens the connect screen to log in to a server that is not in the list yet. */
    fun addHost() {
        _hostsDialog.value = false
        _connectError.value = null
        _connectTarget.value = null
        _screen.value = Screen.Connect
    }

    /** Leaves the add-server screen: back to the sessions if one is open, else to the active server's login. */
    fun cancelAddHost() {
        _connectError.value = null
        if (api != null) {
            _connectTarget.value = _active.value
            _screen.value = Screen.Sessions
        } else {
            _connectTarget.value = _active.value
        }
    }

    /**
     * Renames a server and/or changes its address. A new address is a different server, so its
     * login is dropped. Returns false (nothing changed) when the address is unusable.
     */
    fun editHost(id: String, name: String, rawUrl: String): Boolean {
        val old = _hosts.value.firstOrNull { it.id == id } ?: return false
        val url = try {
            normalizeUrl(rawUrl)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val moved = url != old.url
        val edited = old.copy(name = name.trim().ifBlank { defaultHostName(url) }, url = url, token = if (moved) "" else old.token)
        saveHost(edited)
        if (moved && _active.value?.id == id) {
            dropConnection()
            _connectTarget.value = edited
            _screen.value = Screen.Connect
        }
        return true
    }

    /** Forgets a server and signs out of it. Removing the active one moves on to the next, or to the login screen. */
    fun removeHost(id: String) {
        val host = _hosts.value.firstOrNull { it.id == id } ?: return
        val wasActive = _active.value?.id == id
        val client = if (wasActive) api else if (host.loggedIn) apiFactory(host.url).also { it.token = host.token } else null
        if (client != null) viewModelScope.launch { runCatching { client.logout() } }
        _hosts.value = _hosts.value.filterNot { it.id == id }
        settings.hosts = _hosts.value
        if (!wasActive) return
        val next = _hosts.value.firstOrNull()
        if (next != null) {
            switchHost(next.id)
        } else {
            dropConnection()
            settings.activeHostId = ""
            _active.value = null
            _connectTarget.value = null
            _hostsDialog.value = false
            _screen.value = Screen.Connect
        }
    }

    // ---- session list ----

    private fun enterSessions() {
        _screen.value = Screen.Sessions
        startPolling()
        if (!checkedForUpdate) {
            checkedForUpdate = true
            checkForUpdate(manual = false)
        }
    }

    private fun startPolling() {
        poller?.cancel()
        poller = viewModelScope.launch {
            var polls = 0
            while (isActive) {
                refreshOnce()
                if (polls++ % USAGE_EVERY_POLLS == 0) refreshUsage()
                delay(pollIntervalMs)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce() {
        val client = api ?: return
        try {
            val list = client.listSessions()
            if (api === client) _sessions.value = list
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            authLost()
        } catch (e: Exception) {
            _notice.value = "刷新失败: ${e.message ?: e}"
        }
    }

    private suspend fun refreshUsage() {
        val client = api ?: return
        try {
            val u = client.usage()
            if (api === client) {
                _usage.value = u
                _usageError.value = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            authLost()
        } catch (e: Exception) {
            _usageError.value = e.message ?: e.toString()
        }
    }

    // ---- taking over sessions of the local Claude Code ----

    fun openLocalImport() {
        val client = api ?: return
        _local.value = LocalImport(loading = true)
        viewModelScope.launch {
            try {
                val list = client.listLocalSessions()
                _local.update { it?.copy(loading = false, sessions = list, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                _local.value = null
                authLost()
            } catch (e: Exception) {
                _local.update { it?.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun closeLocalImport() {
        _local.value = null
    }

    /** Imports [id] and opens it; a 409 on a session that looks busy elsewhere asks for confirmation first. */
    fun importLocal(id: String, force: Boolean = false) {
        val client = api ?: return
        _local.update { it?.copy(importing = id, error = null, confirmForce = null) }
        viewModelScope.launch {
            try {
                val s = client.importLocalSession(id, force)
                _local.value = null
                _sessions.value = listOf(s) + _sessions.value.filterNot { it.id == s.id }
                open(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                _local.value = null
                authLost()
            } catch (e: ApiException) {
                if (e.code == 409 && !force && _local.value?.sessions?.firstOrNull { it.id == id }?.active == true) {
                    _local.update { it?.copy(importing = null, confirmForce = id) }
                } else {
                    _local.update { it?.copy(importing = null, error = e.message ?: e.toString()) }
                }
            } catch (e: Exception) {
                _local.update { it?.copy(importing = null, error = e.message ?: e.toString()) }
            }
        }
    }

    fun cancelForce() {
        _local.update { it?.copy(confirmForce = null) }
    }

    fun createSession(req: CreateSession) {
        val client = api ?: return
        viewModelScope.launch {
            _createError.value = null
            try {
                val s = client.createSession(req)
                _sessions.value = listOf(s) + _sessions.value
                open(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                authLost()
            } catch (e: Exception) {
                _createError.value = e.message ?: e.toString()
            }
        }
    }

    fun clearCreateError() {
        _createError.value = null
    }

    fun deleteSession(id: String) {
        val client = api ?: return
        viewModelScope.launch {
            try {
                client.deleteSession(id)
                _sessions.value = _sessions.value.filterNot { it.id == id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                authLost()
            } catch (e: Exception) {
                _notice.value = "删除失败: ${e.message ?: e}"
            }
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    // ---- terminals ----

    fun openTerminals() {
        _terminalError.value = null
        _screen.value = Screen.Terminals
        refreshTerminals()
    }

    fun closeTerminals() {
        _screen.value = Screen.Sessions
    }

    fun refreshTerminals() {
        val client = api ?: return
        viewModelScope.launch {
            try {
                _terminals.value = client.listTerminals()
                _terminalError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                authLost()
            } catch (e: Exception) {
                _terminalError.value = e.message ?: e.toString()
            }
        }
    }

    fun createTerminal(req: CreateTerminal) {
        val client = api ?: return
        viewModelScope.launch {
            _terminalError.value = null
            try {
                val info = client.createTerminal(req)
                _terminals.value = listOf(info) + _terminals.value
                showTerminal(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                authLost()
            } catch (e: Exception) {
                _terminalError.value = e.message ?: e.toString()
            }
        }
    }

    /** Resumes the open chat's session in the real Claude Code CLI on the server (the harness process is stopped first). */
    fun takeOverInTerminal() {
        val open = _chat.value ?: return
        val client = api ?: return
        viewModelScope.launch {
            try {
                val info = client.createTerminal(CreateTerminal(kind = "claude", session = open.info.id))
                _terminals.value = listOf(info) + _terminals.value
                closeChat()
                showTerminal(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                authLost()
            } catch (e: Exception) {
                _notice.value = "终端接管失败: ${e.message ?: e}"
            }
        }
    }

    fun openTerminal(id: String) {
        val info = _terminals.value.firstOrNull { it.id == id } ?: return
        showTerminal(info)
    }

    private fun showTerminal(info: TerminalInfo) {
        val client = api ?: return
        leaveTerminal()
        val controller = TerminalController(client, info.id, viewModelScope)
        controller.start()
        _terminal.value = OpenTerminal(info, controller)
        _screen.value = Screen.Terminal(info.id)
    }

    /** Leaves the terminal screen; the shell keeps running on the server. */
    fun closeTerminalScreen() {
        leaveTerminal()
        _screen.value = Screen.Terminals
        refreshTerminals()
    }

    private fun leaveTerminal() {
        _terminal.value?.controller?.stop()
        _terminal.value = null
    }

    fun killTerminal(id: String) {
        val client = api ?: return
        viewModelScope.launch {
            try {
                client.deleteTerminal(id)
                _terminals.value = _terminals.value.filterNot { it.id == id }
                if (_terminal.value?.info?.id == id) closeTerminalScreen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                authLost()
            } catch (e: Exception) {
                _terminalError.value = "结束失败: ${e.message ?: e}"
            }
        }
    }

    fun setTermFont(sp: Int) {
        val v = sp.coerceIn(8, 28)
        _termFont.value = v
        settings.termFontSp = v
    }

    // ---- directory browsing (for choosing a working directory) ----

    suspend fun listDirs(path: String?, hidden: Boolean): DirListing = requireApi().listDirs(path, hidden)

    suspend fun makeDir(parent: String, name: String): String = requireApi().makeDir(parent, name)

    private fun requireApi(): HarnessApi = api ?: throw IOException("未连接")

    // ---- chat ----

    fun open(id: String) {
        val info = _sessions.value.firstOrNull { it.id == id } ?: return
        open(info)
    }

    private fun open(info: SessionInfo) {
        val client = api ?: return
        leaveChat()
        poller?.cancel()
        val controller = SessionController(client, info.id)
        controller.start(viewModelScope)
        _chat.value = OpenChat(info, controller)
        viewModelScope.launch {
            try {
                val list = controller.commands()
                if (_chat.value?.controller === controller) _commands.value = list
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // completion is a convenience; the chat works without it
            }
        }
        _screen.value = Screen.Chat(info.id)
    }

    fun closeChat() {
        leaveChat()
        _screen.value = Screen.Sessions
        startPolling()
    }

    private fun leaveChat() {
        _chat.value?.controller?.stop()
        _chat.value = null
        _drafts.value = emptyList()
        _commands.value = emptyList()
        thumbnails.clear()
    }

    private fun chatAction(what: String, onError: () -> Unit = {}, block: suspend SessionController.() -> Unit) {
        val controller = _chat.value?.controller ?: return
        viewModelScope.launch {
            try {
                controller.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                authLost()
            } catch (e: Exception) {
                onError()
                _notice.value = "$what: ${e.message ?: e}"
            }
        }
    }

    fun send(text: String) {
        val drafts = _drafts.value
        if (drafts.any { it.state is UploadState.Uploading }) {
            _notice.value = "附件还在上传,请稍候"
            return
        }
        if (drafts.any { it.state is UploadState.Failed }) {
            _notice.value = "有附件上传失败,请移除后再发送"
            return
        }
        val ids = drafts.mapNotNull { (it.state as? UploadState.Done)?.attachment?.id }
        if (text.isBlank() && ids.isEmpty()) return
        _drafts.value = emptyList()
        chatAction("发送失败", onError = { _drafts.value = drafts }) { send(text, ids) }
    }

    /** Starts uploading the picked files ([uris] are content:// URIs from the system picker). */
    fun addAttachments(uris: List<String>) {
        val controller = _chat.value?.controller ?: return
        for (uri in uris) {
            if (_drafts.value.size >= MAX_ATTACHMENTS) {
                _notice.value = "每条消息最多 $MAX_ATTACHMENTS 个附件"
                break
            }
            val id = nextDraftId++
            val src = try {
                resolver.resolve(uri)
            } catch (e: Exception) {
                null
            }
            if (src == null) {
                _drafts.value += DraftAttachment(id, "文件", 0, false, UploadState.Failed("无法读取该文件"))
                continue
            }
            val tooBig = src.size > DEFAULT_MAX_UPLOAD_BYTES
            _drafts.value += DraftAttachment(
                id, src.name, src.size, src.mime.startsWith("image/"),
                if (tooBig) UploadState.Failed("超过 ${DEFAULT_MAX_UPLOAD_BYTES shr 20} MB") else UploadState.Uploading,
            )
            if (tooBig) continue
            viewModelScope.launch {
                try {
                    setDraftState(id, UploadState.Done(controller.upload(src)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AuthException) {
                    authLost()
                } catch (e: Exception) {
                    setDraftState(id, UploadState.Failed(e.message ?: e.toString()))
                }
            }
        }
    }

    fun removeAttachment(localId: Long) {
        _drafts.value = _drafts.value.filterNot { it.localId == localId }
    }

    private fun setDraftState(id: Long, state: UploadState) {
        _drafts.value = _drafts.value.map { if (it.localId == id) it.copy(state = state) else it }
    }

    /** Image bytes for a message's thumbnail; null when unavailable. */
    suspend fun loadAttachment(att: Attachment): ByteArray? {
        val chat = _chat.value ?: return null
        thumbnails[att.id]?.let { return it }
        return try {
            requireApi().downloadAttachment(chat.info.id, att.id).also { thumbnails[att.id] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun setModel(model: String) = chatAction("切换模型失败") { setModel(model) }

    fun setEffort(level: String) = chatAction("切换 effort 失败") { setEffort(level) }

    fun interrupt() = chatAction("中断失败") { interrupt() }

    fun stopProcess() = chatAction("停止失败") { stopProcess() }

    fun setMode(mode: String) = chatAction("切换模式失败") { setPermissionMode(mode) }

    fun allow(requestId: String, remember: Boolean) = chatAction("授权失败") { allow(requestId, remember) }

    fun deny(requestId: String) = chatAction("操作失败") { deny(requestId) }

    fun answer(item: PermissionItem, answers: Map<String, String>) = chatAction("提交失败") { answer(item, answers) }

    // ---- app updates ----

    /**
     * Asks the server for a newer build. The automatic check after login stays silent unless
     * there is something to install; a [manual] check reports "up to date" and errors too.
     */
    fun checkForUpdate(manual: Boolean) {
        val client = api ?: return
        if (installer == null || version.code <= 0) return
        val busy = _update.value
        if (busy is UpdateState.Checking || busy is UpdateState.Downloading) return
        if (!manual && busy !is UpdateState.Idle) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val rel = client.latestRelease()
                if (rel != null && rel.isNewerThan(version.code)) {
                    _update.value = UpdateState.Available(rel)
                    _updateDialog.value = true
                } else {
                    _update.value = UpdateState.Idle
                    if (manual) _notice.value = "已是最新版本 (${version.name})"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                _update.value = UpdateState.Idle
                authLost()
            } catch (e: Exception) {
                _update.value = UpdateState.Idle
                if (manual) _notice.value = "检查更新失败: ${e.message ?: e}"
            }
        }
    }

    /** Downloads the offered release (verifying its checksum), then hands it to the installer. */
    fun startUpdate() {
        val client = api ?: return
        val dir = updateDir ?: return
        val rel = when (val s = _update.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release ?: return
            is UpdateState.Ready -> return installUpdate()
            is UpdateState.NeedsPermission -> return installUpdate()
            else -> return
        }
        _update.value = UpdateState.Downloading(rel, 0, rel.size)
        _updateDialog.value = true
        updateJob = viewModelScope.launch {
            val dest = File(dir, "cc-harness-${rel.versionName.ifBlank { rel.versionCode.toString() }}-${rel.versionCode}.apk")
            try {
                dir.mkdirs()
                dir.listFiles()?.forEach { if (it != dest) it.delete() }
                client.downloadRelease(rel, dest) { done, total ->
                    _update.value = UpdateState.Downloading(rel, done, total)
                }
                _update.value = UpdateState.Ready(rel, dest)
                installUpdate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                _update.value = UpdateState.Idle
                authLost()
            } catch (e: ChecksumException) {
                _update.value = UpdateState.Failed(e.message ?: "校验失败", rel)
            } catch (e: Exception) {
                _update.value = UpdateState.Failed("下载失败: ${e.message ?: e}", rel)
            }
        }
    }

    fun installUpdate() {
        val inst = installer ?: return
        val (rel, file) = when (val s = _update.value) {
            is UpdateState.Ready -> s.release to s.file
            is UpdateState.NeedsPermission -> s.release to s.file
            else -> return
        }
        _update.value = try {
            when (inst.install(file)) {
                InstallResult.Started -> UpdateState.Ready(rel, file)
                InstallResult.NeedsPermission -> UpdateState.NeedsPermission(rel, file)
            }
        } catch (e: Exception) {
            UpdateState.Failed("无法启动安装: ${e.message ?: e}", rel)
        }
    }

    fun showUpdateDialog() {
        _updateDialog.value = true
    }

    /** Hides the dialog; an available update stays offered as a banner and a download keeps running. */
    fun dismissUpdateDialog() {
        _updateDialog.value = false
        if (_update.value is UpdateState.Failed) _update.value = UpdateState.Idle
    }

    override fun onCleared() {
        leaveChat()
    }

    companion object {
        private const val THUMBNAIL_CACHE = 24

        /** Usage moves slowly: ask on entering the list, then about once a minute. */
        private const val USAGE_EVERY_POLLS = 15

        /** Accepts "host:port" and "http://host:port/" alike. */
        fun normalizeUrl(raw: String): String {
            val t = raw.trim().trimEnd('/')
            require(t.isNotEmpty()) { "empty url" }
            return if ("://" in t) t else "http://$t"
        }
    }
}
