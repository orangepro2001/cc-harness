package com.cchharness.app

import com.cchharness.core.Attachment
import com.cchharness.core.CreateSession
import com.cchharness.core.Release
import com.cchharness.core.UploadSource
import com.cchharness.core.DirListing
import com.cchharness.core.HarnessApi
import com.cchharness.core.LoginResponse
import com.cchharness.core.LocalSession
import com.cchharness.core.Me
import com.cchharness.core.Usage
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
    var onUpload: suspend (UploadSource) -> Attachment = { Attachment("att-${it.name}", it.name, if (it.mime.startsWith("image/")) "image" else "file", it.mime, it.size) }
    var onRelease: () -> Release? = { null }
    var onDownload: suspend (Release, java.io.File, (Long, Long) -> Unit) -> Unit = { _, f, p -> f.writeText("apk"); p(3, 3) }
    var commandList: List<String> = listOf("compact", "context")
    var attachmentBytes: ByteArray = ByteArray(0)
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

    override suspend fun updateSession(id: String, title: String?, permissionMode: String?, model: String?, effort: String?): SessionInfo {
        calls += "update:$id:$title:$permissionMode:$model" + (effort?.let { ":effort=$it" } ?: "")
        return sessions.first { it.id == id }
    }

    override suspend fun deleteSession(id: String) {
        calls += "delete:$id"
        sessions.removeAll { it.id == id }
    }

    override suspend fun upload(sessionId: String, file: UploadSource): Attachment {
        calls += "upload:$sessionId:${file.name}"
        return onUpload(file)
    }

    override suspend fun downloadAttachment(sessionId: String, attachmentId: String): ByteArray {
        calls += "download-att:$attachmentId"
        return attachmentBytes
    }

    override suspend fun commands(sessionId: String): List<String> {
        calls += "commands:$sessionId"
        return commandList
    }

    override suspend fun latestRelease(): Release? {
        calls += "latest-release"
        return onRelease()
    }

    override suspend fun downloadRelease(release: Release, dest: java.io.File, onProgress: (Long, Long) -> Unit) {
        calls += "download-release:${release.versionCode}"
        onDownload(release, dest, onProgress)
    }

    override suspend fun sendMessage(id: String, text: String, attachments: List<String>) {
        calls += "send:$id:$text" + if (attachments.isEmpty()) "" else ":att=${attachments.joinToString(",")}"
        onSend(id, text)
    }

    var localSessions: List<LocalSession> = emptyList()
    var onImportLocal: (String, Boolean) -> SessionInfo = { id, _ ->
        val l = localSessions.first { it.id == id }
        SessionInfo(id = l.id, title = l.title, cwd = l.cwd, started = true)
    }
    var onUsage: () -> Usage = { Usage() }

    override suspend fun listLocalSessions(): List<LocalSession> {
        calls += "local-list"
        return localSessions
    }

    override suspend fun importLocalSession(id: String, force: Boolean): SessionInfo {
        calls += "import:$id:$force"
        return onImportLocal(id, force).also { sessions += it }
    }

    override suspend fun syncSession(id: String) {
        calls += "sync:$id"
    }

    override suspend fun usage(): Usage {
        calls += "usage"
        return onUsage()
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
