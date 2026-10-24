package com.cchharness.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

val HarnessJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class LoginResponse(val user: String, val token: String)

@Serializable
data class Me(
    val user: String,
    val workspace: String = "",
    val roots: List<String> = emptyList(),
    val allowBypass: Boolean = false,
    /** True when this user may open terminals from the network the app is connected over. */
    val terminal: Boolean = false,
)

@Serializable
data class PendingPerm(
    val requestId: String,
    val toolName: String,
    val toolUseId: String = "",
    val input: JsonElement = JsonObject(emptyMap()),
    val suggestions: JsonElement? = null,
)

@Serializable
data class SessionInfo(
    val id: String,
    val user: String = "",
    val title: String = "",
    val cwd: String = "",
    val model: String = "",
    val effort: String = "",
    val permissionMode: String = "manual",
    val started: Boolean = false,
    val turns: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    /** stopped | idle | busy | awaiting_permission */
    val status: String = "stopped",
    val pending: List<PendingPerm> = emptyList(),
    val lastSeq: Long = 0,
)

@Serializable
data class SessionList(val sessions: List<SessionInfo> = emptyList())

@Serializable
data class CreateSession(
    val title: String = "",
    val cwd: String = "",
    val model: String = "",
    val effort: String = "",
    val permissionMode: String = "manual",
    val appendSystemPrompt: String = "",
)

/** A file uploaded to a session; [kind] is "image" (shown to the model inline) or "file". */
@Serializable
data class Attachment(
    val id: String,
    val name: String,
    val kind: String = "file",
    val mime: String = "",
    val size: Long = 0,
) {
    val isImage: Boolean get() = kind == "image"
}

/** The newest published build of this app, as announced by the server. */
@Serializable
data class Release(
    val versionCode: Int,
    val versionName: String = "",
    val sha256: String,
    val size: Long = 0,
    val notes: String = "",
    val publishedAt: String = "",
) {
    fun isNewerThan(installedVersionCode: Int): Boolean = versionCode > installedVersionCode
}

@Serializable
data class CommandList(val commands: List<String> = emptyList())

@Serializable
data class DirEntry(val name: String, val path: String)

/** Listing of one directory; [path] and [parent] are empty at the top level (the roots). */
@Serializable
data class DirListing(
    val path: String = "",
    val parent: String = "",
    val entries: List<DirEntry> = emptyList(),
)

@Serializable
data class MkdirResponse(val path: String)

/** One server-sent event: [seq] is 0 for live-only events that are not persisted. */
data class StreamEvent(val seq: Long, val ts: Long, val data: JsonObject)

val PERMISSION_MODES: List<Pair<String, String>> = listOf(
    "manual" to "逐项询问",
    "acceptEdits" to "自动接受编辑",
    "plan" to "计划模式",
    "auto" to "自动分类",
    "dontAsk" to "不询问(拒绝未授权)",
    "bypassPermissions" to "全部放行",
)

val MODELS: List<Pair<String, String>> = listOf(
    "" to "默认",
    "opus" to "opus",
    "sonnet" to "sonnet",
    "haiku" to "haiku",
    "fable" to "fable",
)

val EFFORTS: List<Pair<String, String>> = listOf(
    "" to "默认",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "xhigh" to "超高",
    "max" to "最高",
)

/** Largest file the server accepts by default (HARNESS_MAX_UPLOAD_MB); the server has the final say. */
const val DEFAULT_MAX_UPLOAD_BYTES: Long = 25L * 1024 * 1024
const val MAX_ATTACHMENTS: Int = 10

/** A conversation kept by the local Claude Code (terminal / VS Code) that can be taken over. */
@Serializable
data class LocalSession(
    val id: String,
    val title: String = "",
    val cwd: String = "",
    val updatedAt: String = "",
    val size: Long = 0,
    /** Modified moments ago: probably still open in another Claude Code. */
    val active: Boolean = false,
)

@Serializable
data class LocalSessionList(val sessions: List<LocalSession> = emptyList())

/** One bar of the plan-usage panel (5-hour session, weekly, per-model weekly). */
@Serializable
data class UsageLimit(
    val kind: String,
    val label: String,
    val percent: Double = 0.0,
    val severity: String = "",
    val resetsAt: String? = null,
)

@Serializable
data class Usage(val plan: String = "", val limits: List<UsageLimit> = emptyList())

@Serializable
data class TerminalInfo(
    val id: String,
    val title: String = "",
    /** shell | claude */
    val kind: String = "shell",
    val cwd: String = "",
    val createdAt: String = "",
    val attached: Int = 0,
)

@Serializable
data class CreateTerminal(
    val cwd: String = "",
    val kind: String = "shell",
    /** For kind = claude: the harness session to resume in the real CLI. */
    val session: String = "",
    val title: String = "",
    val cols: Int = 80,
    val rows: Int = 24,
)

/** What a terminal connection delivers: the attachment id first, then output chunks, then possibly the end. */
sealed interface TermEvent {
    data class Attached(val id: String) : TermEvent
    class Output(val bytes: ByteArray) : TermEvent
    data object Exited : TermEvent
}
