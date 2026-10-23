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
    val permissionMode: String = "manual",
    val appendSystemPrompt: String = "",
)

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
