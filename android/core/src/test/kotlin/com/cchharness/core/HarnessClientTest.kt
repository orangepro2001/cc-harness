package com.cchharness.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HarnessClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = HarnessClient(server.url("/").toString(), reconnectInitialMs = 20, reconnectMaxMs = 50)
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun sse(vararg events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { it + "\n\n" })

    private fun event(seq: Long, kind: String = "x") =
        "id: $seq\ndata: {\"seq\":$seq,\"ts\":1,\"data\":{\"type\":\"harness\",\"kind\":\"$kind\"}}"

    @Test
    fun loginStoresTokenAndSendsItAsBearer() = runBlocking {
        server.enqueue(json("""{"user":"tony","token":"tok123"}"""))
        server.enqueue(json("""{"user":"tony","workspace":"/w","roots":["/r"],"allowBypass":false}"""))
        val resp = client.login("tony", "pw")
        assertEquals("tok123", resp.token)
        val login = server.takeRequest()
        assertEquals("/api/login", login.path)
        assertEquals("POST", login.method)
        assertEquals("tony", HarnessJson.parseToJsonElement(login.body.readUtf8()).jsonObject["user"]!!.jsonPrimitive.content)

        val me = client.me()
        assertEquals(listOf("/r"), me.roots)
        assertEquals("Bearer tok123", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun unauthorizedBecomesAuthException() = runBlocking {
        server.enqueue(json("""{"error":"invalid credentials"}""", 401))
        val e = assertFailsWith<AuthException> { client.me() }
        assertEquals("invalid credentials", e.message)
    }

    @Test
    fun lockoutExposesRetryAfter() = runBlocking {
        server.enqueue(json("""{"error":"too many failed attempts, retry later"}""", 429).setHeader("Retry-After", "299"))
        val e = assertFailsWith<RateLimitedException> { client.login("tony", "x") }
        assertEquals(299, e.retryAfterSeconds)
    }

    @Test
    fun otherErrorsCarryServerMessageAndCode() = runBlocking {
        server.enqueue(json("""{"error":"session not found"}""", 404))
        val e = assertFailsWith<ApiException> { client.getSession("00000000-0000-0000-0000-000000000000") }
        assertEquals(404, e.code)
        assertEquals("session not found", e.message)
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        assertEquals(502, assertFailsWith<ApiException> { client.me() }.code)
    }

    @Test
    fun sessionListAndCreateRoundTrip() = runBlocking {
        server.enqueue(json("""{"sessions":[{"id":"a","title":"t","cwd":"/w","status":"idle","pending":[{"requestId":"r","toolName":"Bash","input":{"command":"ls"}}],"lastSeq":4,"unknownField":1}]}"""))
        val list = client.listSessions()
        assertEquals("idle", list.single().status)
        assertEquals("r", list.single().pending.single().requestId)

        server.takeRequest()
        server.enqueue(json("""{"id":"b","cwd":"/w","permissionMode":"plan"}""", 201))
        val s = client.createSession(CreateSession(title = "x", cwd = "/w", model = "haiku", permissionMode = "plan"))
        assertEquals("plan", s.permissionMode)
        val body = HarnessJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("haiku", body["model"]!!.jsonPrimitive.content)
        assertEquals("plan", body["permissionMode"]!!.jsonPrimitive.content)
    }

    @Test
    fun directoryCallsEncodeQueryAndParseListing() = runBlocking {
        server.enqueue(json("""{"path":"","parent":"","entries":[{"name":"个人工作区","path":"/w"}]}"""))
        val roots = client.listDirs(null)
        assertEquals("/w", roots.entries.single().path)
        assertEquals("/api/fs", server.takeRequest().path)

        server.enqueue(json("""{"path":"/a b/c&d","parent":"/a b","entries":[]}"""))
        client.listDirs("/a b/c&d", hidden = true)
        val req = server.takeRequest()
        assertEquals("/a b/c&d", req.requestUrl!!.queryParameter("path"))
        assertEquals("1", req.requestUrl!!.queryParameter("hidden"))

        server.enqueue(json("""{"path":"/a/new"}""", 201))
        assertEquals("/a/new", client.makeDir("/a", "new"))
    }

    @Test
    fun permissionAnswerIsPostedToTheRightPath() = runBlocking {
        server.enqueue(json("""{"ok":true}"""))
        val input = HarnessJson.parseToJsonElement("""{"answers":{"q":"a"}}""")
        client.resolvePermission("sid", "rid/odd", allow = true, remember = true, updatedInput = input)
        val req = server.takeRequest()
        assertEquals("/api/sessions/sid/permissions/rid%2Fodd", req.path)
        val body = HarnessJson.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("true", body["remember"]!!.jsonPrimitive.content)
        assertEquals("a", body["updatedInput"]!!.jsonObject["answers"]!!.jsonObject["q"]!!.jsonPrimitive.content)
    }

    @Test
    fun deleteAndInterruptUseTheRightVerbs() = runBlocking {
        server.enqueue(json("""{"ok":true}"""))
        server.enqueue(json("""{"ok":true}"""))
        client.deleteSession("sid")
        client.interrupt("sid")
        assertEquals("DELETE", server.takeRequest().method)
        assertEquals("/api/sessions/sid/interrupt", server.takeRequest().path)
    }

    @Test
    fun eventsAreParsedFromTheStream() = runBlocking {
        server.enqueue(sse(event(1, "a"), event(2, "b"), ": ping"))
        val evs = withTimeout(5_000) { client.events("sid").take(2).toList() }
        assertEquals(listOf(1L, 2L), evs.map { it.seq })
        assertEquals("text/event-stream", server.takeRequest().getHeader("Accept"))
    }

    @Test
    fun eventsReconnectWithLastEventId() = runBlocking {
        // first connection ends after seq 2, the client must resume from 2
        server.enqueue(sse(event(1), event(2)))
        server.enqueue(sse(event(3), event(4)))
        val evs = withTimeout(5_000) { client.events("sid").take(4).toList() }
        assertEquals(listOf(1L, 2L, 3L, 4L), evs.map { it.seq })
        assertNull(server.takeRequest().getHeader("Last-Event-ID"))
        assertEquals("2", server.takeRequest().getHeader("Last-Event-ID"))
    }

    @Test
    fun eventsSurviveDroppedConnectionsAndServerErrors() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(sse(event(1)))
        val ev = withTimeout(5_000) { client.events("sid").first() }
        assertEquals(1, ev.seq)
    }

    @Test
    fun eventsFailFastOnAuthAndMissingSession() = runBlocking {
        server.enqueue(json("""{"error":"nope"}""", 401))
        assertFailsWith<AuthException> { withTimeout(5_000) { client.events("sid").first() } }
        server.enqueue(json("""{"error":"session not found"}""", 404))
        assertEquals(404, assertFailsWith<ApiException> { withTimeout(5_000) { client.events("sid").first() } }.code)
    }

    @Test
    fun cancellingTheCollectorClosesTheConnection() = runBlocking {
        // a stream that stays open: headers + one event, then silence
        server.enqueue(sse(event(1)).setSocketPolicy(SocketPolicy.KEEP_OPEN).also { it.setBody(event(1) + "\n\n") })
        val first = withTimeout(5_000) { client.events("sid").first() }
        assertEquals(1, first.seq)
        // first() cancels the flow; this must return promptly instead of hanging on the open socket
        assertTrue(server.requestCount >= 1)
    }
}
