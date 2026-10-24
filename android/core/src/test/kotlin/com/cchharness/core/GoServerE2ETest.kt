package com.cchharness.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real cc-harness server (built from this checkout) with the Go test
 * binary standing in for the `claude` CLI, so the whole Kotlin stack is exercised
 * against the actual wire protocol without spending any model quota.
 */
class GoServerE2ETest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cancelScope() = scope.cancel()

    private suspend fun <T> StateFlow<T>.await(what: String, pred: (T) -> Boolean): T =
        try {
            withTimeout(15_000) { first(pred) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what; last state: $value")
        }

    private suspend fun newClient(): HarnessClient =
        HarnessClient(baseUrl).also { it.login("tony", PASSWORD) }

    private suspend fun newSession(c: HarnessClient, title: String = "t", cwd: String = ""): SessionController {
        val s = c.createSession(CreateSession(title = title, cwd = cwd))
        return SessionController(c, s.id, publishIntervalMs = 10).also { it.start(scope) }
    }

    @Test
    fun loginRules() = runBlocking {
        val bad = HarnessClient(baseUrl)
        assertFailsWith<AuthException> { bad.login("tony", "wrong") }
        assertFailsWith<AuthException> { bad.me() } // no token
        val good = newClient()
        val me = good.me()
        assertEquals("tony", me.user)
        assertTrue(me.workspace.isNotEmpty())
        assertEquals(listOf(shared.canonicalPath), me.roots.map { File(it).canonicalPath })
    }

    @Test
    fun chatTurnStreamsIntoState() = runBlocking {
        val c = newClient()
        val ctl = newSession(c, "chat")
        ctl.send("hello")
        val s = ctl.state.await("assistant echo") { st -> st.items.any { it is AssistantText && it.text == "echo: hello" } && st.status == "idle" }
        assertEquals("hello", s.items.filterIsInstance<UserMessage>().single().text)
        assertEquals(Connection.Live, ctl.connection.value)
        assertTrue(s.items.any { it is Note && it.text.contains("后台进程已启动") })
        assertTrue(s.lastSeq > 0)
        // the session list reflects the title derived from the conversation
        assertEquals("chat", c.listSessions().single { it.id == ctl.sessionId }.title)
    }

    @Test
    fun permissionRoundTripWithRemember() = runBlocking {
        val c = newClient()
        val ctl = newSession(c)
        ctl.send("TOOL please")
        val waiting = ctl.state.await("pending permission") { it.pendingPermissions.isNotEmpty() && it.status == "awaiting_permission" }
        val p = waiting.pendingPermissions.single()
        assertEquals("Write", p.toolName)
        assertTrue(p.canRemember)

        ctl.allow(p.requestId, remember = true)
        val done = ctl.state.await("decision") { st -> st.items.any { it is AssistantText && it.text.contains("decision") } && st.status == "idle" }
        val decision = done.items.filterIsInstance<AssistantText>().first { it.text.contains("decision") }.text
        assertTrue(decision.contains("allow") && decision.contains("updatedPermissions"), decision)
        assertEquals(PermState.AllowedRemembered, done.items.filterIsInstance<PermissionItem>().single().state)
    }

    @Test
    fun denyReachesTheAgent() = runBlocking {
        val c = newClient()
        val ctl = newSession(c)
        ctl.send("TOOL deny me")
        val p = ctl.state.await("pending") { it.pendingPermissions.isNotEmpty() }.pendingPermissions.single()
        ctl.deny(p.requestId, "not today")
        val s = ctl.state.await("denied decision") { st -> st.items.any { it is AssistantText && it.text.contains("deny") } }
        assertEquals(PermState.Denied, s.items.filterIsInstance<PermissionItem>().single().state)
        assertTrue(s.items.filterIsInstance<AssistantText>().any { it.text.contains("not today") })
    }

    @Test
    fun anotherControllerReplaysTheWholeHistory() = runBlocking {
        val c = newClient()
        val first = newSession(c)
        first.send("one")
        first.state.await("first turn") { st -> st.items.any { it is AssistantText && it.text == "echo: one" } && st.status == "idle" }
        first.send("two")
        val original = first.state.await("second turn") { st -> st.items.any { it is AssistantText && it.text == "echo: two" } && st.status == "idle" }
        first.stop()

        // a fresh client (as after an app restart) rebuilds the identical conversation from the log
        val replay = SessionController(newClient(), first.sessionId, publishIntervalMs = 10).also { it.start(scope) }
        val rebuilt = replay.state.await("replay") { it.lastSeq >= original.lastSeq }
        assertEquals(original.items.map { it::class to (it as? AssistantText)?.text }, rebuilt.items.map { it::class to (it as? AssistantText)?.text })
    }

    @Test
    fun interruptReturnsToIdleAndStopKillsTheProcess() = runBlocking {
        val c = newClient()
        val ctl = newSession(c)
        ctl.send("SLEEP forever")
        ctl.state.await("busy") { it.status == "busy" }
        ctl.interrupt()
        val s = ctl.state.await("idle after interrupt") { it.status == "idle" }
        assertTrue(s.items.any { it is Note && it.isError && it.text.contains("error_during_execution") })

        ctl.stopProcess()
        ctl.state.await("stopped") { it.status == "stopped" }
        assertEquals("stopped", c.getSession(ctl.sessionId).status)
    }

    @Test
    fun modeCanBeChangedAndSessionDeleted() = runBlocking {
        val c = newClient()
        val ctl = newSession(c)
        ctl.setPermissionMode("plan")
        assertEquals("plan", c.getSession(ctl.sessionId).permissionMode)
        ctl.state.await("mode event") { it.permissionMode == "plan" }
        c.updateSession(ctl.sessionId, title = "renamed")
        assertEquals("renamed", c.getSession(ctl.sessionId).title)
        c.deleteSession(ctl.sessionId)
        assertEquals(404, assertFailsWith<ApiException> { c.getSession(ctl.sessionId) }.code)
    }

    @Test
    fun directoryBrowsingAndWorkingDirectorySelection() = runBlocking {
        val c = newClient()
        File(shared, "projA/src").mkdirs()
        File(shared, ".secret").mkdirs()

        val roots = c.listDirs(null)
        assertEquals("", roots.path)
        assertEquals(2, roots.entries.size) // personal workspace + HARNESS_ROOTS
        val sharedRoot = roots.entries.single { File(it.path).canonicalPath == shared.canonicalPath }

        val top = c.listDirs(sharedRoot.path)
        assertEquals("", top.parent) // top of a root goes back to the root list
        assertTrue(top.entries.any { it.name == "projA" })
        assertTrue(top.entries.none { it.name == ".secret" })
        assertTrue(c.listDirs(sharedRoot.path, hidden = true).entries.any { it.name == ".secret" })

        val made = c.makeDir(sharedRoot.path, "created-from-android")
        assertTrue(File(made).isDirectory)

        val src = File(shared, "projA/src").canonicalPath
        val session = c.createSession(CreateSession(title = "in-dir", cwd = src))
        assertEquals(src, session.cwd)

        assertEquals(403, assertFailsWith<ApiException> { c.listDirs("/etc") }.code)
        assertEquals(400, assertFailsWith<ApiException> { c.createSession(CreateSession(cwd = "/etc")) }.code)
        assertEquals(400, assertFailsWith<ApiException> { c.makeDir(sharedRoot.path, "../escape") }.code)
    }

    @Test
    fun uploadsAreSentToTheAgentAndShownInHistory() = runBlocking {
        val c = newClient()
        val ctl = newSession(c, "files")
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )
        val img = ctl.upload(UploadSource("shot.png", "image/png", png.size.toLong()) { png.inputStream() })
        assertEquals("image", img.kind)
        val note = "hello notes\n".toByteArray()
        val txt = ctl.upload(UploadSource("notes.txt", "text/plain", note.size.toLong()) { note.inputStream() })
        assertEquals("file", txt.kind)
        assertTrue(c.downloadAttachment(ctl.sessionId, img.id).contentEquals(png))

        ctl.send("look", listOf(img.id, txt.id))
        val s = ctl.state.await("reply with attachments") { st -> st.items.any { it is AssistantText && it.text.contains("IMAGES=1") } && st.status == "idle" }
        val user = s.items.filterIsInstance<UserMessage>().single()
        assertEquals(listOf("shot.png", "notes.txt"), user.attachments.map { it.name })
        assertTrue(user.attachments[0].isImage)

        assertEquals(400, assertFailsWith<ApiException> { ctl.send("x", listOf("0000000000000000")) }.code)

        // a different user never sees the file
        val other = HarnessClient(baseUrl).also { it.login("mallory", PASSWORD) }
        assertEquals(404, assertFailsWith<ApiException> { other.downloadAttachment(ctl.sessionId, img.id) }.code)
        // and the history of a fresh controller still carries the attachments
        val replay = SessionController(newClient(), ctl.sessionId, publishIntervalMs = 10).also { it.start(scope) }
        val rebuilt = replay.state.await("replay") { st -> st.items.any { it is UserMessage && it.attachments.size == 2 } }
        assertEquals(2, rebuilt.items.filterIsInstance<UserMessage>().single().attachments.size)
    }

    @Test
    fun effortAndSlashCommands() = runBlocking {
        val c = newClient()
        assertEquals(400, assertFailsWith<ApiException> { c.createSession(CreateSession(effort = "turbo")) }.code)
        val s = c.createSession(CreateSession(title = "eff", effort = "high"))
        assertEquals("high", s.effort)
        val ctl = SessionController(c, s.id, publishIntervalMs = 10).also { it.start(scope) }
        ctl.send("hi")
        val st = ctl.state.await("init commands") { it.commands.isNotEmpty() && it.status == "idle" }
        assertEquals(listOf("compact", "context", "review"), st.commands)
        assertEquals(listOf("compact", "context", "review"), ctl.commands())

        ctl.setEffort("max")
        ctl.state.await("effort event") { it.effort == "max" }
        assertEquals("max", c.getSession(s.id).effort)
    }

    @Test
    fun releaseCheckAndVerifiedDownload() = runBlocking {
        val c = newClient()
        val dir = File(tmp, "data/releases")
        File(dir, "android.json").delete()
        assertNull(c.latestRelease())

        dir.mkdirs()
        val apk = ByteArray(300_000) { (it % 251).toByte() }
        File(dir, "app-9.apk").writeBytes(apk)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        File(dir, "android.json").writeText("""{"versionCode":9,"versionName":"2.0","sha256":"$sha","file":"app-9.apk","notes":"new stuff"}""")

        val rel = c.latestRelease()!!
        assertEquals(9, rel.versionCode)
        assertEquals("new stuff", rel.notes)
        assertEquals(apk.size.toLong(), rel.size)
        assertTrue(rel.isNewerThan(2) && !rel.isNewerThan(9))

        val dest = File(tmp, "dl/update.apk")
        var last = 0L
        c.downloadRelease(rel, dest) { done, _ -> last = done }
        assertTrue(dest.readBytes().contentEquals(apk))
        assertEquals(apk.size.toLong(), last)

        // a manifest whose checksum does not match is rejected and leaves nothing behind
        val bad = dest.resolveSibling("bad.apk")
        assertFailsWith<ChecksumException> { c.downloadRelease(rel.copy(sha256 = "0".repeat(64)), bad) }
        assertTrue(!bad.exists() && !File(bad.path + ".part").exists())
    }

    private suspend fun TerminalController.screenHas(what: String) {
        try {
            withTimeout(15_000) {
                while (!emulator.screenText().contains(what)) kotlinx.coroutines.delay(50)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for \"$what\" on the terminal; screen:\n${emulator.screenText()}\nstatus=${status.value}")
        }
    }

    @Test
    fun terminalRunsAShellAndSurvivesReconnects() = runBlocking {
        assumeTrue("tmux not installed", runCatching { ProcessBuilder("tmux", "-V").start().waitFor() == 0 }.getOrDefault(false))
        val c = newClient()
        assertTrue(c.me().terminal)
        val info = c.createTerminal(CreateTerminal(title = "e2e", cols = 90, rows = 24))
        assertEquals("shell", info.kind)
        assertEquals(listOf(info.id), c.listTerminals().map { it.id })

        val first = TerminalController(c, info.id, scope, cols = 90, rows = 24).also { it.start() }
        first.status.await("live") { it == TermStatus.Live }
        first.send("echo e2e-\$((6*7))\r")
        first.screenHas("e2e-42")
        first.resize(70, 20)
        first.send("stty size\r")
        first.screenHas("20 70")
        first.stop()

        // a new connection (as after the app was backgrounded) repaints the very same shell
        val second = TerminalController(c, info.id, scope, cols = 70, rows = 20).also { it.start() }
        second.status.await("live again") { it == TermStatus.Live }
        second.screenHas("e2e-42")
        second.send("exit\r")
        second.status.await("ended") { it == TermStatus.Ended }
        second.stop()
        // the shell ended, so the terminal is gone
        withTimeout(10_000) { while (c.listTerminals().isNotEmpty()) kotlinx.coroutines.delay(100) }
    }

    @Test
    fun terminalIsPrivateToItsOwner() = runBlocking {
        assumeTrue("tmux not installed", runCatching { ProcessBuilder("tmux", "-V").start().waitFor() == 0 }.getOrDefault(false))
        val c = newClient()
        val info = c.createTerminal(CreateTerminal())
        val other = HarnessClient(baseUrl).also { it.login("someone", PASSWORD) }
        assertTrue(!other.me().terminal)
        val e = assertFailsWith<ApiException> { other.listTerminals() }
        assertEquals(403, e.code)
        c.deleteTerminal(info.id)
        assertFailsWith<ApiException> { c.deleteTerminal(info.id) }
        Unit
    }

    companion object {
        private const val PASSWORD = "test-pass-1"
        private lateinit var tmp: File
        private lateinit var shared: File
        private lateinit var baseUrl: String
        private var server: Process? = null
        private val tmuxSocket = "cche2e${ProcessHandle.current().pid()}"

        private fun run(dir: File, vararg cmd: String) {
            val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor(5, TimeUnit.MINUTES) && p.exitValue() == 0) { "${cmd.joinToString(" ")} failed:\n$out" }
        }

        @BeforeClass
        @JvmStatic
        fun startServer() {
            val repo = File(System.getProperty("harness.repo"))
            val goOk = runCatching { ProcessBuilder("go", "version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
            assumeTrue("go toolchain not available, skipping end-to-end tests", goOk && File(repo, "main.go").exists())

            tmp = Files.createTempDirectory("cch-e2e").toFile()
            shared = File(tmp, "shared").also { it.mkdirs() }
            run(repo, "go", "build", "-o", File(tmp, "cc-harness").path, ".")
            run(repo, "go", "test", "-c", "-o", File(tmp, "mock.test").path, ".")

            val port = ServerSocket(0).use { it.localPort }
            baseUrl = "http://127.0.0.1:$port"
            server = ProcessBuilder(File(tmp, "cc-harness").path).apply {
                environment().apply {
                    put("HARNESS_PASSWORD", PASSWORD)
                    put("HARNESS_BIND", "127.0.0.1")
                    put("HARNESS_PORT", port.toString())
                    put("HARNESS_DATA", File(tmp, "data").path)
                    put("HARNESS_ROOTS", shared.path)
                    put("HARNESS_CLAUDE_BIN", File(tmp, "mock.test").path)
                    put("MOCK_CLAUDE", "1")
                    put("HARNESS_TERMINAL", "tony")
                    put("HARNESS_TMUX_SOCKET", tmuxSocket)
                }
                redirectErrorStream(true)
                redirectOutput(File(tmp, "server.log"))
            }.start()

            repeat(100) {
                val up = runCatching {
                    (URL("$baseUrl/api/health").openConnection() as HttpURLConnection).responseCode == 200
                }.getOrDefault(false)
                if (up) return
                Thread.sleep(100)
            }
            error("server did not start:\n${File(tmp, "server.log").readText()}")
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            server?.destroy()
            server?.waitFor(5, TimeUnit.SECONDS)
            runCatching { ProcessBuilder("tmux", "-L", tmuxSocket, "kill-server").start().waitFor(5, TimeUnit.SECONDS) }
            if (::tmp.isInitialized) tmp.deleteRecursively()
        }
    }
}
