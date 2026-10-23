package com.cchharness.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

sealed interface ChatItem {
    /** Stable identity for list rendering. */
    val key: String
}

data class UserMessage(override val key: String, val text: String) : ChatItem

data class AssistantText(override val key: String, val text: String) : ChatItem

data class ToolCall(
    override val key: String,
    val toolUseId: String,
    val name: String,
    val summary: String,
    val inputJson: String,
    val result: String? = null,
    val isError: Boolean = false,
) : ChatItem

data class Note(override val key: String, val text: String, val isError: Boolean = false) : ChatItem

enum class PermState { Pending, Allowed, AllowedRemembered, Denied, Cancelled }

data class AskOption(val label: String, val description: String = "")

data class AskQuestion(
    val question: String,
    val header: String = "",
    val options: List<AskOption> = emptyList(),
    val multiSelect: Boolean = false,
)

data class PermissionItem(
    override val key: String,
    val requestId: String,
    val toolName: String,
    val input: JsonObject,
    /** The CLI offered a "don't ask again this session" rule. */
    val canRemember: Boolean,
    /** Non-empty for the AskUserQuestion tool, which wants answers instead of a yes/no. */
    val questions: List<AskQuestion> = emptyList(),
    val state: PermState = PermState.Pending,
) : ChatItem {
    val isQuestion: Boolean get() = questions.isNotEmpty()
}

data class ChatState(
    val items: List<ChatItem> = emptyList(),
    /** Assistant text that is still streaming and not yet part of [items]. */
    val liveText: String = "",
    /** stopped | idle | busy | awaiting_permission */
    val status: String = "stopped",
    val permissionMode: String? = null,
    val model: String? = null,
    val lastSeq: Long = 0,
) {
    val pendingPermissions: List<PermissionItem>
        get() = items.filterIsInstance<PermissionItem>().filter { it.state == PermState.Pending }
}

/**
 * Folds the server's event stream into a [ChatState]. Mirrors the web client
 * (web/app.js): it is thread-safe, idempotent for already seen sequence
 * numbers, and cheap to feed thousands of replayed events.
 */
class ChatReducer {
    private val items = ArrayList<ChatItem>()
    private val toolIndex = HashMap<String, Int>()
    private val permIndex = HashMap<String, Int>()
    private val live = StringBuilder()
    private var status = "stopped"
    private var permissionMode: String? = null
    private var model: String? = null
    private var lastSeq = 0L
    private var counter = 0

    private fun key() = "k${counter++}"

    @Synchronized
    fun apply(ev: StreamEvent) {
        if (ev.seq > 0) {
            if (ev.seq <= lastSeq) return
            lastSeq = ev.seq
        }
        val d = ev.data
        when (d.str("type")) {
            "harness" -> onHarness(d)
            "system" -> if (d.str("subtype") == "init") {
                model = d.str("model") ?: model
                items += Note(key(), "已连接 · ${d.str("model").orEmpty()} · ${d.str("permissionMode").orEmpty()}")
            }
            "assistant" -> onAssistant(d)
            "user" -> onToolResults(d)
            "control_request" -> onPermissionRequest(d)
            "result" -> onResult(d)
            "stream_event" -> onDelta(d["event"] as? JsonObject)
        }
    }

    @Synchronized
    fun snapshot(): ChatState = ChatState(
        items = items.toList(),
        liveText = live.toString(),
        status = status,
        permissionMode = permissionMode,
        model = model,
        lastSeq = lastSeq,
    )

    private fun onHarness(d: JsonObject) {
        when (d.str("kind")) {
            "user_message" -> items += UserMessage(key(), d.str("text").orEmpty())
            "status" -> status = d.str("status") ?: status
            "process_started" ->
                items += Note(key(), if (d["resume"]?.let { it is JsonPrimitive && it.booleanOrNull == true } == true) "后台进程已启动 (恢复上下文)" else "后台进程已启动")
            "process_exit" -> items += Note(key(), "后台进程已退出: ${d.str("reason").orEmpty()}")
            "error" -> items += Note(key(), d.str("message").orEmpty(), isError = true)
            "mode_changed" -> permissionMode = d.str("permissionMode") ?: permissionMode
            "model_changed" -> model = d.str("model") ?: model
            "permission_resolved" -> {
                val idx = permIndex[d.str("requestId")] ?: return
                val p = items[idx] as? PermissionItem ?: return
                val cancelled = (d["cancelled"] as? JsonPrimitive)?.booleanOrNull == true
                val allowed = (d["allow"] as? JsonPrimitive)?.booleanOrNull == true
                val remembered = (d["remember"] as? JsonPrimitive)?.booleanOrNull == true
                items[idx] = p.copy(
                    state = when {
                        cancelled -> PermState.Cancelled
                        allowed && remembered -> PermState.AllowedRemembered
                        allowed -> PermState.Allowed
                        else -> PermState.Denied
                    },
                )
            }
        }
    }

    private fun onDelta(e: JsonObject?) {
        if (e == null) return
        when (e.str("type")) {
            "message_start" -> live.setLength(0)
            "content_block_delta" -> {
                val delta = e["delta"] as? JsonObject ?: return
                if (delta.str("type") == "text_delta") live.append(delta.str("text").orEmpty())
            }
        }
    }

    private fun onAssistant(d: JsonObject) {
        live.setLength(0)
        val blocks = (d["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        for (b in blocks) {
            val o = b as? JsonObject ?: continue
            when (o.str("type")) {
                "text" -> o.str("text")?.takeIf { it.isNotBlank() }?.let { items += AssistantText(key(), it) }
                "tool_use" -> {
                    val id = o.str("id").orEmpty()
                    val input = o["input"] as? JsonObject ?: JsonObject(emptyMap())
                    toolIndex[id] = items.size
                    items += ToolCall(key(), id, o.str("name").orEmpty(), summarizeInput(input), HarnessJson.encodeToString(JsonObject.serializer(), input))
                }
            }
        }
    }

    private fun onToolResults(d: JsonObject) {
        val content = (d["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        for (b in content) {
            val o = b as? JsonObject ?: continue
            if (o.str("type") != "tool_result") continue
            val idx = toolIndex[o.str("tool_use_id")] ?: continue
            val call = items[idx] as? ToolCall ?: continue
            var text = toolResultText(o["content"])
            if (text.length > MAX_RESULT_CHARS) {
                text = text.substring(0, MAX_RESULT_CHARS) + "\n… (已截断 ${text.length - MAX_RESULT_CHARS} 字符)"
            }
            val isError = (o["is_error"] as? JsonPrimitive)?.booleanOrNull == true
            items[idx] = call.copy(result = text.ifEmpty { "(无输出)" }, isError = isError)
        }
    }

    private fun onPermissionRequest(d: JsonObject) {
        val req = d["request"] as? JsonObject ?: return
        if (req.str("subtype") != "can_use_tool") return
        val rid = d.str("request_id") ?: return
        val input = req["input"] as? JsonObject ?: JsonObject(emptyMap())
        val name = req.str("tool_name").orEmpty()
        val suggestions = req["permission_suggestions"] as? JsonArray
        permIndex[rid] = items.size
        items += PermissionItem(
            key = key(),
            requestId = rid,
            toolName = name,
            input = input,
            canRemember = suggestions != null && suggestions.isNotEmpty(),
            questions = if (name == "AskUserQuestion") parseQuestions(input) else emptyList(),
        )
    }

    private fun onResult(d: JsonObject) {
        live.setLength(0)
        val subtype = d.str("subtype")
        val isError = (d["is_error"] as? JsonPrimitive)?.booleanOrNull == true
        if (isError || (subtype != null && subtype != "success")) {
            val detail = d.str("result")?.take(300)?.let { " · $it" }.orEmpty()
            items += Note(key(), "回合结束: ${subtype ?: "error"}$detail", isError = true)
        } else {
            val parts = ArrayList<String>()
            (d["duration_ms"] as? JsonPrimitive)?.doubleOrNull?.let { parts += "%.1fs".format(it / 1000) }
            (d["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull?.let { parts += "$%.4f".format(it) }
            items += Note(key(), parts.joinToString(" · "))
        }
    }

    private companion object {
        const val MAX_RESULT_CHARS = 8000
    }
}

fun summarizeInput(input: JsonObject): String {
    for (k in listOf("command", "file_path", "path", "pattern", "url", "query", "description", "prompt")) {
        input.str(k)?.let { return it.replace(Regex("\\s+"), " ").take(160) }
    }
    return HarnessJson.encodeToString(JsonObject.serializer(), input).take(160)
}

private fun toolResultText(content: JsonElement?): String = when (content) {
    null -> ""
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.joinToString("\n") { part ->
        val o = part as? JsonObject
        if (o?.str("type") == "text") o.str("text").orEmpty() else "[${o?.str("type") ?: "?"}]"
    }
    else -> content.toString()
}

fun parseQuestions(input: JsonObject): List<AskQuestion> {
    val qs = input["questions"] as? JsonArray ?: return emptyList()
    return qs.mapNotNull { q ->
        val o = q as? JsonObject ?: return@mapNotNull null
        val text = o.str("question") ?: return@mapNotNull null
        AskQuestion(
            question = text,
            header = o.str("header").orEmpty(),
            options = (o["options"] as? JsonArray).orEmpty().mapNotNull { opt ->
                val oo = opt as? JsonObject ?: return@mapNotNull null
                AskOption(oo.str("label") ?: return@mapNotNull null, oo.str("description").orEmpty())
            },
            multiSelect = (o["multiSelect"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }
}

/** Builds the `updatedInput` that answers an AskUserQuestion request. */
fun buildAnswerInput(original: JsonObject, answers: Map<String, String>): JsonObject = buildJsonObject {
    original.forEach { (k, v) -> put(k, v) }
    put("answers", buildJsonObject { answers.forEach { (q, a) -> put(q, a) } })
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
