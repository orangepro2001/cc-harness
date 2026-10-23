package com.cchharness.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cchharness.core.ApiException
import com.cchharness.core.AuthException
import com.cchharness.core.CreateSession
import com.cchharness.core.DirListing
import com.cchharness.core.HarnessApi
import com.cchharness.core.Me
import com.cchharness.core.PermissionItem
import com.cchharness.core.RateLimitedException
import com.cchharness.core.SessionController
import com.cchharness.core.SessionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface Screen {
    data object Starting : Screen
    data object Connect : Screen
    data object Sessions : Screen
    data class Chat(val id: String) : Screen
}

/** The chat the user is currently looking at. */
class OpenChat(val info: SessionInfo, val controller: SessionController)

class AppViewModel(
    private val settings: SettingsStore,
    private val apiFactory: (baseUrl: String) -> HarnessApi,
    private val pollIntervalMs: Long = 4_000,
) : ViewModel() {
    private val _screen = MutableStateFlow<Screen>(Screen.Starting)
    private val _busy = MutableStateFlow(false)
    private val _connectError = MutableStateFlow<String?>(null)
    private val _me = MutableStateFlow<Me?>(null)
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    private val _notice = MutableStateFlow<String?>(null)
    private val _createError = MutableStateFlow<String?>(null)
    private val _chat = MutableStateFlow<OpenChat?>(null)

    val screen: StateFlow<Screen> = _screen
    val busy: StateFlow<Boolean> = _busy
    val connectError: StateFlow<String?> = _connectError
    val me: StateFlow<Me?> = _me
    val sessions: StateFlow<List<SessionInfo>> = _sessions
    val notice: StateFlow<String?> = _notice
    val createError: StateFlow<String?> = _createError
    val chat: StateFlow<OpenChat?> = _chat

    val savedUrl: String get() = settings.baseUrl
    val savedUser: String get() = settings.user

    private var api: HarnessApi? = null
    private var poller: Job? = null

    init {
        viewModelScope.launch { restore() }
    }

    // ---- connection ----

    private suspend fun restore() {
        val url = settings.baseUrl
        val token = settings.token
        if (url.isBlank() || token.isBlank()) {
            _screen.value = Screen.Connect
            return
        }
        try {
            val client = apiFactory(url).also { it.token = token }
            _me.value = client.me()
            api = client
            enterSessions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            settings.token = ""
            _connectError.value = "登录已过期,请重新登录"
            _screen.value = Screen.Connect
        } catch (e: Exception) {
            _connectError.value = "无法连接服务器: ${e.message ?: e}"
            _screen.value = Screen.Connect
        }
    }

    fun connect(rawUrl: String, user: String, password: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _connectError.value = null
            try {
                val url = normalizeUrl(rawUrl)
                val client = apiFactory(url)
                val login = client.login(user.trim().lowercase(), password)
                settings.baseUrl = url
                settings.user = login.user
                settings.token = login.token
                _me.value = client.me()
                api = client
                enterSessions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitedException) {
                _connectError.value = "失败次数过多,请 ${e.retryAfterSeconds} 秒后再试"
            } catch (e: AuthException) {
                _connectError.value = "用户名或密码错误"
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

    fun logout() {
        val client = api
        viewModelScope.launch {
            runCatching { client?.logout() }
            settings.token = ""
            leaveChat()
            poller?.cancel()
            api = null
            _me.value = null
            _sessions.value = emptyList()
            _connectError.value = null
            _screen.value = Screen.Connect
        }
    }

    private fun authLost() {
        settings.token = ""
        leaveChat()
        poller?.cancel()
        api = null
        _me.value = null
        _connectError.value = "登录已过期,请重新登录"
        _screen.value = Screen.Connect
    }

    // ---- session list ----

    private fun enterSessions() {
        _screen.value = Screen.Sessions
        startPolling()
    }

    private fun startPolling() {
        poller?.cancel()
        poller = viewModelScope.launch {
            while (isActive) {
                refreshOnce()
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
            _sessions.value = client.listSessions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            authLost()
        } catch (e: Exception) {
            _notice.value = "刷新失败: ${e.message ?: e}"
        }
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
    }

    private fun chatAction(what: String, block: suspend SessionController.() -> Unit) {
        val controller = _chat.value?.controller ?: return
        viewModelScope.launch {
            try {
                controller.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                authLost()
            } catch (e: Exception) {
                _notice.value = "$what: ${e.message ?: e}"
            }
        }
    }

    fun send(text: String) = chatAction("发送失败") { send(text) }

    fun interrupt() = chatAction("中断失败") { interrupt() }

    fun stopProcess() = chatAction("停止失败") { stopProcess() }

    fun setMode(mode: String) = chatAction("切换模式失败") { setPermissionMode(mode) }

    fun allow(requestId: String, remember: Boolean) = chatAction("授权失败") { allow(requestId, remember) }

    fun deny(requestId: String) = chatAction("操作失败") { deny(requestId) }

    fun answer(item: PermissionItem, answers: Map<String, String>) = chatAction("提交失败") { answer(item, answers) }

    override fun onCleared() {
        leaveChat()
    }

    companion object {
        /** Accepts "host:port" and "http://host:port/" alike. */
        fun normalizeUrl(raw: String): String {
            val t = raw.trim().trimEnd('/')
            require(t.isNotEmpty()) { "empty url" }
            return if ("://" in t) t else "http://$t"
        }
    }
}
