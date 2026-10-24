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
import kotlinx.coroutines.withContext
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import kotlinx.serialization.json.JsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A downloaded file did not match its published checksum. */
class ChecksumException(message: String) : IOException(message)

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
    suspend fun updateSession(
        id: String,
        title: String? = null,
        permissionMode: String? = null,
        model: String? = null,
        effort: String? = null,
    ): SessionInfo
    suspend fun deleteSession(id: String)
    suspend fun sendMessage(id: String, text: String, attachments: List<String> = emptyList())
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

    /** Uploads one file to the session; the returned id goes into [sendMessage]'s attachments. */
    suspend fun upload(sessionId: String, file: UploadSource): Attachment

    /** Raw bytes of an uploaded attachment (used for image thumbnails). */
    suspend fun downloadAttachment(sessionId: String, attachmentId: String): ByteArray

    /** The newest published Android build, or null when the server has none. */
    suspend fun latestRelease(): Release?

    /**
     * Downloads the published APK to [dest], verifying its SHA-256 against [release]; a
     * corrupt or tampered download is deleted and reported as [ChecksumException].
     */
    suspend fun downloadRelease(release: Release, dest: java.io.File, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> })

    /** Slash commands (without "/") offered for the session. */
    suspend fun commands(sessionId: String): List<String>

    /** Conversations of the local Claude Code that are not managed by the server yet. */
    suspend fun listLocalSessions(): List<LocalSession>

    /** Takes over a local conversation; 409 means it is managed already or looks in use elsewhere (retry with [force]). */
    suspend fun importLocalSession(id: String, force: Boolean = false): SessionInfo

    /** Pulls in what was added to the session outside the server (terminal / VS Code). */
    suspend fun syncSession(id: String)

    /** Plan usage of the account the server is logged in with. */
    suspend fun usage(): Usage

    /** Lists sub-directories of [path]; a null path lists the allowed roots. */
    suspend fun listDirs(path: String?, hidden: Boolean = false): DirListing
    suspend fun makeDir(parent: String, name: String): String

    /** Event stream that survives disconnects by resuming from the last seen sequence. */
    fun events(sessionId: String, after: Long = 0): Flow<StreamEvent>
}

/** A file to upload, read lazily so large files are streamed rather than held in memory. */
class UploadSource(val name: String, val mime: String, val size: Long, val open: () -> java.io.InputStream)

class HarnessClient(
    baseUrl: String,
    http: OkHttpClient = OkHttpClient(),
    private val reconnectInitialMs: Long = 1_000,
    private val reconnectMaxMs: Long = 15_000,
) : HarnessApi {
    private val base: HttpUrl = baseUrl.trim().trimEnd('/').toHttpUrl()
    private val rest = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    // The server sends a heartbeat every 15s, so 45s of silence means a dead link.
    // Uploads can take a while on a slow link; no overall deadline, but the connection must make progress.
    private val upload = http.newBuilder().callTimeout(0, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private val download = http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

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

    override suspend fun updateSession(
        id: String,
        title: String?,
        permissionMode: String?,
        model: String?,
        effort: String?,
    ): SessionInfo {
        val body = buildJsonObject {
            title?.let { put("title", it) }
            permissionMode?.let { put("permissionMode", it) }
            model?.let { put("model", it) }
            effort?.let { put("effort", it) }
        }
        return HarnessJson.decodeFromString(SessionInfo.serializer(), send("PATCH", listOf("api", "sessions", id), body))
    }

    override suspend fun deleteSession(id: String) {
        send("DELETE", listOf("api", "sessions", id))
    }

    override suspend fun sendMessage(id: String, text: String, attachments: List<String>) {
        val body = buildJsonObject {
            put("text", text)
            if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments.map { JsonPrimitive(it) }))
        }
        send("POST", listOf("api", "sessions", id, "messages"), body)
    }

    override suspend fun upload(sessionId: String, file: UploadSource): Attachment {
        val body = object : RequestBody() {
            override fun contentType() = file.mime.ifEmpty { "application/octet-stream" }.toMediaTypeOrNull()
            override fun contentLength() = file.size
            override fun writeTo(sink: BufferedSink) {
                file.open().use { sink.writeAll(it.source()) }
            }
        }
        val req = Request.Builder()
            .url(url(listOf("api", "sessions", sessionId, "uploads"), listOf("name" to file.name)))
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .post(body)
            .build()
        return HarnessJson.decodeFromString(Attachment.serializer(), exec(req, upload))
    }

    override suspend fun downloadAttachment(sessionId: String, attachmentId: String): ByteArray {
        val req = getRequest(listOf("api", "sessions", sessionId, "uploads", attachmentId)).build()
        return execBytes(req, rest)
    }

    override suspend fun latestRelease(): Release? = try {
        HarnessJson.decodeFromString(Release.serializer(), send("GET", listOf("api", "client", "android", "latest")))
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    override suspend fun downloadRelease(release: Release, dest: java.io.File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            val part = java.io.File(dest.path + ".part")
            part.parentFile?.mkdirs()
            val call = download.newCall(getRequest(listOf("api", "client", "android", "download")).build())
            val reg = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw errorFor(resp, resp.body?.string().orEmpty())
                    val body = resp.body ?: throw IOException("empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: release.size
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    var done = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(release.sha256, ignoreCase = true)) {
                        throw ChecksumException("下载文件校验失败，请重试")
                    }
                }
                if (!part.renameTo(dest)) throw IOException("cannot move download into place")
            } catch (e: Throwable) {
                part.delete()
                throw e
            } finally {
                reg?.dispose()
            }
        }
    }

    override suspend fun commands(sessionId: String): List<String> =
        HarnessJson.decodeFromString(CommandList.serializer(), send("GET", listOf("api", "sessions", sessionId, "commands"))).commands

    override suspend fun listLocalSessions(): List<LocalSession> =
        HarnessJson.decodeFromString(LocalSessionList.serializer(), send("GET", listOf("api", "local-sessions"))).sessions

    override suspend fun importLocalSession(id: String, force: Boolean): SessionInfo = HarnessJson.decodeFromString(
        SessionInfo.serializer(),
        send("POST", listOf("api", "local-sessions", id, "import"), JsonObject(if (force) mapOf("force" to JsonPrimitive(true)) else emptyMap())),
    )

    override suspend fun syncSession(id: String) {
        send("POST", listOf("api", "sessions", id, "sync"), JsonObject(emptyMap()))
    }

    override suspend fun usage(): Usage =
        HarnessJson.decodeFromString(Usage.serializer(), send("GET", listOf("api", "usage")))

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

    private suspend fun exec(req: Request, client: OkHttpClient = rest): String =
        String(execBytes(req, client), Charsets.UTF_8)

    private suspend fun execBytes(req: Request, client: OkHttpClient): ByteArray = suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    try {
                        val bytes = r.body?.bytes() ?: ByteArray(0)
                        if (r.isSuccessful) cont.resume(bytes) else cont.resumeWithException(errorFor(r, String(bytes, Charsets.UTF_8)))
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
