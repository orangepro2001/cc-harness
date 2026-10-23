package com.cchharness.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

open class ApiException(val code: Int, message: String) : IOException(message)

/** The server rejected our credentials or the stored token expired. */
class AuthException(message: String) : ApiException(401, message)

/** Too many failed logins from this client. */
class RateLimitedException(message: String, val retryAfterSeconds: Int) : ApiException(429, message)

/** Everything the UI needs from the server. Implemented by [HarnessClient]; faked in UI tests. */
interface HarnessApi {
    /** Bearer token for authenticated calls; set by [login] or restored from storage. */
    var token: String?

    suspend fun login(user: String, password: String): LoginResponse
    suspend fun logout()
    suspend fun me(): Me
    suspend fun listSessions(): List<SessionInfo>
    suspend fun createSession(req: CreateSession): SessionInfo
    suspend fun getSession(id: String): SessionInfo
    suspend fun updateSession(id: String, title: String? = null, permissionMode: String? = null, model: String? = null): SessionInfo
    suspend fun deleteSession(id: String)
    suspend fun sendMessage(id: String, text: String)
    suspend fun interrupt(id: String)
    suspend fun stop(id: String)
    suspend fun resolvePermission(
        id: String,
        requestId: String,
        allow: Boolean,
        remember: Boolean = false,
        message: String = "",
        updatedInput: JsonElement? = null,
    )

    /** Lists sub-directories of [path]; a null path lists the allowed roots. */
    suspend fun listDirs(path: String?, hidden: Boolean = false): DirListing
    suspend fun makeDir(parent: String, name: String): String

    /** Event stream that survives disconnects by resuming from the last seen sequence. */
    fun events(sessionId: String, after: Long = 0): Flow<StreamEvent>
}

class HarnessClient(
    baseUrl: String,
    http: OkHttpClient = OkHttpClient(),
    private val reconnectInitialMs: Long = 1_000,
    private val reconnectMaxMs: Long = 15_000,
) : HarnessApi {
    private val base: HttpUrl = baseUrl.trim().trimEnd('/').toHttpUrl()
    private val rest = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    // The server sends a heartbeat every 15s, so 45s of silence means a dead link.
    private val sse = http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()

    @Volatile
    override var token: String? = null

    // ---- auth ----

    override suspend fun login(user: String, password: String): LoginResponse {
        val body = buildJsonObject {
            put("user", user)
            put("password", password)
        }
        val resp = HarnessJson.decodeFromString(LoginResponse.serializer(), send("POST", listOf("api", "login"), body))
        token = resp.token
        return resp
    }

    override suspend fun logout() {
        try {
            send("POST", listOf("api", "logout"), JsonObject(emptyMap()))
        } finally {
            token = null
        }
    }

    // ---- REST ----

    override suspend fun me(): Me =
        HarnessJson.decodeFromString(Me.serializer(), send("GET", listOf("api", "me")))

    override suspend fun listSessions(): List<SessionInfo> =
        HarnessJson.decodeFromString(SessionList.serializer(), send("GET", listOf("api", "sessions"))).sessions

    override suspend fun createSession(req: CreateSession): SessionInfo = HarnessJson.decodeFromString(
        SessionInfo.serializer(),
        send("POST", listOf("api", "sessions"), HarnessJson.encodeToJsonElement(CreateSession.serializer(), req)),
    )

    override suspend fun getSession(id: String): SessionInfo =
        HarnessJson.decodeFromString(SessionInfo.serializer(), send("GET", listOf("api", "sessions", id)))

    override suspend fun updateSession(id: String, title: String?, permissionMode: String?, model: String?): SessionInfo {
        val body = buildJsonObject {
            title?.let { put("title", it) }
            permissionMode?.let { put("permissionMode", it) }
            model?.let { put("model", it) }
        }
        return HarnessJson.decodeFromString(SessionInfo.serializer(), send("PATCH", listOf("api", "sessions", id), body))
    }

    override suspend fun deleteSession(id: String) {
        send("DELETE", listOf("api", "sessions", id))
    }

    override suspend fun sendMessage(id: String, text: String) {
        send("POST", listOf("api", "sessions", id, "messages"), buildJsonObject { put("text", text) })
    }

    override suspend fun interrupt(id: String) {
        send("POST", listOf("api", "sessions", id, "interrupt"), JsonObject(emptyMap()))
    }

    override suspend fun stop(id: String) {
        send("POST", listOf("api", "sessions", id, "stop"), JsonObject(emptyMap()))
    }

    override suspend fun resolvePermission(
        id: String,
        requestId: String,
        allow: Boolean,
        remember: Boolean,
        message: String,
        updatedInput: JsonElement?,
    ) {
        val body = buildJsonObject {
            put("allow", allow)
            put("remember", remember)
            if (message.isNotEmpty()) put("message", message)
            if (updatedInput != null) put("updatedInput", updatedInput)
        }
        send("POST", listOf("api", "sessions", id, "permissions", requestId), body)
    }

    override suspend fun listDirs(path: String?, hidden: Boolean): DirListing {
        val query = buildList {
            if (!path.isNullOrEmpty()) add("path" to path)
            if (hidden) add("hidden" to "1")
        }
        return HarnessJson.decodeFromString(DirListing.serializer(), send("GET", listOf("api", "fs"), query = query))
    }

    override suspend fun makeDir(parent: String, name: String): String {
        val body = buildJsonObject {
            put("parent", parent)
            put("name", name)
        }
        return HarnessJson.decodeFromString(MkdirResponse.serializer(), send("POST", listOf("api", "fs", "mkdir"), body)).path
    }

    // ---- event stream ----

    override fun events(sessionId: String, after: Long): Flow<StreamEvent> = flow {
        var last = after
        var backoff = reconnectInitialMs
        while (true) {
            try {
                streamOnce(sessionId, last) { ev ->
                    if (ev.seq > last) last = ev.seq
                    backoff = reconnectInitialMs
                    emit(ev)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                throw e
            } catch (e: ApiException) {
                if (e.code == 404) throw e // the session is gone, nothing to retry
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive() // a cancelled call surfaces as IOException
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(reconnectMaxMs)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun streamOnce(sessionId: String, after: Long, onEvent: suspend (StreamEvent) -> Unit) {
        val req = getRequest(listOf("api", "sessions", sessionId, "events"))
            .header("Accept", "text/event-stream")
            .apply { if (after > 0) header("Last-Event-ID", after.toString()) }
            .build()
        val call = sse.newCall(req)
        // Cancelling the collector must unblock the blocking read below.
        val registration = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw errorFor(resp, resp.body?.string().orEmpty())
                val source = resp.body?.source() ?: return
                val parser = SseParser()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    parser.feed(line)?.let { onEvent(it) }
                }
            }
        } finally {
            registration?.dispose()
        }
    }

    // ---- plumbing ----

    private fun url(segments: List<String>, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    /** A GET request builder for [segments] carrying the bearer token, if any. */
    private fun getRequest(segments: List<String>): Request.Builder =
        Request.Builder().url(url(segments)).apply {
            token?.let { header("Authorization", "Bearer $it") }
        }

    private suspend fun send(
        method: String,
        segments: List<String>,
        body: JsonElement? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): String {
        val builder = Request.Builder().url(url(segments, query))
        token?.let { builder.header("Authorization", "Bearer $it") }
        val requestBody = body?.let { HarnessJson.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) }
        builder.method(method, requestBody ?: if (method == "GET" || method == "DELETE") null else EMPTY_BODY)
        return exec(builder.build())
    }

    private suspend fun exec(req: Request): String = suspendCancellableCoroutine { cont ->
        val call = rest.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    try {
                        val text = r.body?.string().orEmpty()
                        if (r.isSuccessful) cont.resume(text) else cont.resumeWithException(errorFor(r, text))
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        })
    }

    private fun errorFor(r: Response, body: String): ApiException {
        val msg = try {
            HarnessJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        } ?: r.message.ifEmpty { "HTTP ${r.code}" }
        return when (r.code) {
            401 -> AuthException(msg)
            429 -> RateLimitedException(msg, r.header("Retry-After")?.toIntOrNull() ?: 0)
            else -> ApiException(r.code, msg)
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
