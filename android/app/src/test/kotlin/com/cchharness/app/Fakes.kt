package com.cchharness.app

import com.cchharness.core.CreateSession
import com.cchharness.core.DirListing
import com.cchharness.core.HarnessApi
import com.cchharness.core.LoginResponse
import com.cchharness.core.Me
import com.cchharness.core.SessionInfo
import com.cchharness.core.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonElement

class FakeSettings(
    override var baseUrl: String = "",
    override var user: String = "",
    override var token: String = "",
) : SettingsStore

/** Scriptable stand-in for the server. Every call is recorded in [calls]. */
class FakeApi : HarnessApi {
    override var token: String? = null

    var onLogin: (String, String) -> LoginResponse = { u, _ -> LoginResponse(u, "tok-$u") }
    var onMe: () -> Me = { Me("tony", "/work", listOf("/shared")) }
    var onList: () -> List<SessionInfo> = { sessions.toList() }
    var onCreate: (CreateSession) -> SessionInfo = { SessionInfo(id = "new-id", title = it.title, cwd = it.cwd.ifEmpty { "/work" }) }
    var onSend: (String, String) -> Unit = { _, _ -> }
    var onDirs: (String?, Boolean) -> DirListing = { _, _ -> DirListing() }

    val sessions = mutableListOf<SessionInfo>()
    val calls = mutableListOf<String>()
    val events = MutableSharedFlow<StreamEvent>(extraBufferCapacity = 256)

    override suspend fun login(user: String, password: String): LoginResponse {
        calls += "login:$user:$password"
        return onLogin(user, password).also { token = it.token }
    }

    override suspend fun logout() {
        calls += "logout"
        token = null
    }

    override suspend fun me(): Me = onMe().also { calls += "me" }

    override suspend fun listSessions(): List<SessionInfo> = onList().also { calls += "list" }

    override suspend fun createSession(req: CreateSession): SessionInfo {
        calls += "create:${req.title}:${req.cwd}:${req.model}:${req.permissionMode}"
        return onCreate(req).also { sessions += it }
    }

    override suspend fun getSession(id: String): SessionInfo = sessions.first { it.id == id }

    override suspend fun updateSession(id: String, title: String?, permissionMode: String?, model: String?): SessionInfo {
        calls += "update:$id:$title:$permissionMode:$model"
        return sessions.first { it.id == id }
    }

    override suspend fun deleteSession(id: String) {
        calls += "delete:$id"
        sessions.removeAll { it.id == id }
    }

    override suspend fun sendMessage(id: String, text: String) {
        calls += "send:$id:$text"
        onSend(id, text)
    }

    override suspend fun interrupt(id: String) {
        calls += "interrupt:$id"
    }

    override suspend fun stop(id: String) {
        calls += "stop:$id"
    }

    override suspend fun resolvePermission(
        id: String,
        requestId: String,
        allow: Boolean,
        remember: Boolean,
        message: String,
        updatedInput: JsonElement?,
    ) {
        calls += "perm:$id:$requestId:$allow:$remember:${updatedInput ?: ""}"
    }

    override suspend fun listDirs(path: String?, hidden: Boolean): DirListing {
        calls += "dirs:${path ?: "<roots>"}:$hidden"
        return onDirs(path, hidden)
    }

    override suspend fun makeDir(parent: String, name: String): String {
        calls += "mkdir:$parent:$name"
        return "$parent/$name"
    }

    override fun events(sessionId: String, after: Long): Flow<StreamEvent> = events
}
