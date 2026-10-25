package com.cchharness.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import kotlinx.coroutines.cancel
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import com.cchharness.app.ui.ChatScreen
import com.cchharness.app.ui.ConnectScreen
import com.cchharness.app.ui.DirectoryPickerDialog
import com.cchharness.app.ui.HarnessTheme
import com.cchharness.app.ui.HostsDialog
import com.cchharness.app.ui.MarkdownText
import com.cchharness.app.ui.NewSessionDialog
import com.cchharness.app.ui.SessionListScreen
import com.cchharness.core.ChatReducer
import com.cchharness.core.ChatState
import com.cchharness.core.Connection
import com.cchharness.core.CreateSession
import com.cchharness.core.DirEntry
import com.cchharness.core.DirListing
import com.cchharness.core.Me
import com.cchharness.core.PermissionItem
import com.cchharness.core.SessionInfo
import com.cchharness.core.SseParser
import com.cchharness.core.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Headless Compose UI tests: Robolectric renders the real composables on the JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun ev(seq: Long, json: String) = StreamEvent(seq, 0, Json.parseToJsonElement(json).jsonObject)

    private fun stateOf(vararg events: StreamEvent, status: String = "idle", live: String = ""): ChatState {
        val r = ChatReducer()
        events.forEach(r::apply)
        return r.snapshot().copy(status = status, liveText = live)
    }

    private fun realState(): ChatState {
        val r = ChatReducer()
        javaClass.getResourceAsStream("/real-session.jsonl")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }.forEach { r.apply(SseParser.parseEvent(it)!!) }
        return r.snapshot()
    }

    private fun permissionRequest(rid: String = "rq1", suggestions: String = """[{"type":"setMode"}]""") = ev(
        1,
        """{"type":"control_request","request_id":"$rid","request":{"subtype":"can_use_tool","tool_name":"Write","input":{"file_path":"/work/a.txt","content":"x"},"permission_suggestions":$suggestions}}""",
    )

    private class ChatCalls {
        val sent = mutableListOf<String>()
        val allowed = mutableListOf<Pair<String, Boolean>>()
        val denied = mutableListOf<String>()
        val answers = mutableListOf<Map<String, String>>()
        val modes = mutableListOf<String>()
        var interrupts = 0
        var stops = 0
        var back = 0
    }

    private fun showChat(
        state: ChatState,
        calls: ChatCalls = ChatCalls(),
        connection: Connection = Connection.Live,
        notice: String? = null,
    ): ChatCalls {
        rule.setContent {
            HarnessTheme {
                ChatScreen(
                    title = "demo", cwd = "/work", state = state, connection = connection,
                    fallbackMode = "manual", allowBypass = false, notice = notice,
                    onBack = { calls.back++ }, onSend = { calls.sent += it },
                    onInterrupt = { calls.interrupts++ }, onStopProcess = { calls.stops++ },
                    onSetMode = { calls.modes += it },
                    onAllow = { id, remember -> calls.allowed += id to remember },
                    onDeny = { calls.denied += it },
                    onAnswer = { _, a -> calls.answers += a },
                    onDismissNotice = {},
                )
            }
        }
        return calls
    }

    // ---- connect ----

    @Test
    fun connectButtonNeedsAllFieldsAndPassesThemOn() {
        var got: Triple<String, String, String>? = null
        rule.setContent {
            HarnessTheme { ConnectScreen("", "tony", busy = false, error = null) { u, n, p, _ -> got = Triple(u, n, p) } }
        }
        rule.onNodeWithTag("connect-button").assertIsNotEnabled()
        rule.onNodeWithTag("connect-url").performTextInput("192.168.1.164:8765")
        rule.onNodeWithTag("connect-button").assertIsNotEnabled()
        rule.onNodeWithTag("connect-password").performTextInput("test-pass-1")
        rule.onNodeWithTag("connect-button").assertIsEnabled().performClick()
        assertEquals(Triple("192.168.1.164:8765", "tony", "test-pass-1"), got)
    }

    @Test
    fun connectShowsErrorAndDisablesWhileBusy() {
        rule.setContent { HarnessTheme { ConnectScreen("http://h", "tony", busy = true, error = "用户名或密码错误") { _, _, _, _ -> } } }
        rule.onNodeWithTag("connect-error").assertTextContains("用户名或密码错误")
        rule.onNodeWithTag("connect-button").assertIsNotEnabled()
    }

    // ---- session list ----

    private val sessions = listOf(
        SessionInfo(id = "a", title = "alpha", cwd = "/work/a", status = "busy"),
        SessionInfo(id = "b", title = "", cwd = "/work/b", status = "stopped"),
    )

    @Test
    fun sessionListShowsStatusOpensAndDeletesWithConfirmation() {
        var opened: String? = null
        var deleted: String? = null
        rule.setContent {
            HarnessTheme {
                SessionListScreen("tony", sessions, null, { opened = it }, {}, { deleted = it }, {}, {}, {})
            }
        }
        rule.onNodeWithText("alpha").assertExists()
        rule.onNodeWithText("运行中 · /work/a").assertExists()
        rule.onNodeWithText("(未命名)").assertExists()
        rule.onNodeWithTag("session-a").performClick()
        assertEquals("a", opened)

        rule.onNodeWithTag("delete-a").performClick()
        assertEquals(null, deleted, "must ask before deleting")
        rule.onNodeWithTag("confirm-delete").performClick()
        assertEquals("a", deleted)
    }

    @Test
    fun emptySessionListSaysSo() {
        rule.setContent { HarnessTheme { SessionListScreen("tony", emptyList(), "刷新失败: x", {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithText("还没有会话,点右下角新建").assertExists()
        rule.onNodeWithTag("notice").assertTextContains("刷新失败: x")
    }

    // ---- chat ----

    @Test
    fun activityBarShowsWhatABusySessionIsDoing() {
        val running = com.cchharness.core.ToolCall("k1", "tu1", "Bash", "go test", "{}")
        val calls = showChat(ChatState(items = listOf(running), status = "busy"))
        rule.onNodeWithTag("activity-label").assertTextContains("运行 Bash…")
        rule.onNodeWithTag("activity-time").assertExists()
        rule.onNodeWithTag("activity-stop").performClick()
        assertEquals(1, calls.interrupts)
    }

    @Test
    fun activityBarSaysThinkingStreamingAndWaitingForYou() {
        showChat(ChatState(status = "busy"))
        rule.onNodeWithTag("activity-label").assertTextContains("思考中…")
    }

    @Test
    fun activityBarShowsStreamingText() {
        showChat(ChatState(status = "busy", liveText = "正在写"))
        rule.onNodeWithTag("activity-label").assertTextContains("正在输出…")
    }

    @Test
    fun activityBarIsHiddenWhenIdleAndWarnsWhenTheLinkIsDown() {
        showChat(ChatState(status = "idle"))
        rule.onNodeWithTag("activity-bar").assertDoesNotExist()
    }

    @Test
    fun activityBarReportsALostConnection() {
        showChat(ChatState(status = "idle"), connection = Connection.Failed("timeout"))
        rule.onNodeWithTag("activity-label").assertTextContains("连接中断", substring = true)
    }

    @Test
    fun sleepingSessionHintsThatSendingWakesIt() {
        showChat(ChatState(status = "stopped"))
        rule.onNodeWithText("进程已休眠", substring = true).assertExists()
    }

    @Test
    fun sendingShowsTheBarBeforeTheServerReports() {
        showChat(ChatState(status = "idle"))
        rule.onNodeWithTag("activity-bar").assertDoesNotExist()
        rule.onNodeWithTag("chat-input").performTextInput("hi")
        rule.onNodeWithTag("chat-send").performClick()
        rule.onNodeWithTag("activity-label").assertTextContains("已发送", substring = true)
    }

    @Test
    fun rendersARealRecordedConversation() {
        showChat(realState().copy(status = "idle"))
        // opens scrolled to the newest message
        assertTrue(rule.onAllNodesWithText("hi-from-harness", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("chat-status").assertTextContains("空闲")
        // and the beginning is reachable by scrolling up
        rule.onNodeWithTag("chat-list").performScrollToIndex(0)
        rule.onNodeWithText("Use the Write tool", substring = true).assertExists()
    }

    @Test
    fun permissionCardOffersAllowAlwaysAndDeny() {
        val calls = showChat(stateOf(permissionRequest(), status = "awaiting_permission"))
        rule.onNodeWithText("请求使用工具: Write").assertExists()
        rule.onNodeWithTag("chat-status").assertTextContains("等待授权")
        rule.onNodeWithTag("perm-allow").performClick()
        rule.onNodeWithTag("perm-always").performClick()
        rule.onNodeWithTag("perm-deny").performClick()
        assertEquals(listOf("rq1" to false, "rq1" to true), calls.allowed)
        assertEquals(listOf("rq1"), calls.denied)
    }

    @Test
    fun alwaysAllowIsHiddenWhenTheCliOffersNoRule() {
        showChat(stateOf(permissionRequest(suggestions = "[]")))
        rule.onNodeWithTag("perm-allow").assertExists()
        rule.onNodeWithTag("perm-always").assertDoesNotExist()
    }

    @Test
    fun resolvedPermissionShowsOutcomeWithoutButtons() {
        showChat(
            stateOf(
                permissionRequest(),
                ev(2, """{"type":"harness","kind":"permission_resolved","requestId":"rq1","allow":true,"remember":true}"""),
            ),
        )
        rule.onNodeWithTag("perm-state").assertTextContains("已允许 (本会话)")
        rule.onNodeWithTag("perm-allow").assertDoesNotExist()
    }

    @Test
    fun askUserQuestionCollectsAnswers() {
        val calls = showChat(
            stateOf(
                ev(
                    1,
                    """{"type":"control_request","request_id":"q1","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[{"question":"Pick one","header":"H","multiSelect":false,"options":[{"label":"A"},{"label":"B","description":"second"}]}]}}}""",
                ),
            ),
        )
        rule.onNodeWithText("Claude 需要你的回答").assertExists()
        rule.onNodeWithTag("answer-submit").assertIsNotEnabled()
        rule.onNodeWithTag("option-B").performClick()
        rule.onNodeWithTag("answer-submit").assertIsEnabled().performClick()
        assertEquals(listOf(mapOf("Pick one" to "B")), calls.answers)
    }

    @Test
    fun sendingClearsTheInputAndNeedsText() {
        val calls = showChat(stateOf())
        rule.onNodeWithTag("chat-send").assertIsNotEnabled()
        rule.onNodeWithTag("chat-input").performTextInput("hello world")
        rule.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        assertEquals(listOf("hello world"), calls.sent)
        rule.onNodeWithText("hello world").assertDoesNotExist()
        rule.onNodeWithTag("chat-send").assertIsNotEnabled()
    }

    @Test
    fun menuInterruptsOnlyWhileActiveAndSwitchesMode() {
        val calls = showChat(stateOf(status = "busy"))
        rule.onNodeWithTag("chat-menu").performClick()
        rule.onNodeWithTag("menu-interrupt").assertIsEnabled().performClick()
        assertEquals(1, calls.interrupts)

        rule.onNodeWithTag("chat-menu").performClick()
        rule.onNodeWithTag("menu-mode-plan").performClick()
        assertEquals(listOf("plan"), calls.modes)

        rule.onNodeWithTag("chat-menu").performClick()
        rule.onNodeWithTag("menu-stop").performClick()
        assertEquals(1, calls.stops)
    }

    @Test
    fun interruptIsDisabledWhenIdleAndBypassModeIsHidden() {
        showChat(stateOf(status = "idle"))
        rule.onNodeWithTag("chat-menu").performClick()
        rule.onNodeWithTag("menu-interrupt").assertIsNotEnabled()
        rule.onNodeWithTag("menu-mode-bypassPermissions").assertDoesNotExist()
    }

    @Test
    fun streamingTextAndConnectionProblemsAreVisible() {
        showChat(stateOf(live = "partial **reply**"), connection = Connection.Failed("timeout"), notice = "发送失败: offline")
        rule.onNodeWithTag("live-bubble").assertExists()
        rule.onNodeWithTag("chat-connection").assertTextContains("timeout", substring = true)
        rule.onNodeWithTag("chat-notice").assertTextContains("offline", substring = true)
    }

    @Test
    fun toolCardExpandsToShowItsResult() {
        showChat(
            stateOf(
                ev(1, """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"echo hi"}}]}}"""),
                ev(2, """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"hi-output"}]}}"""),
            ),
        )
        rule.onNodeWithText("echo hi").assertExists()
        rule.onNodeWithText("hi-output").assertDoesNotExist()
        rule.onNodeWithTag("tool-t1").performClick()
        rule.onNodeWithTag("tool-result-t1", useUnmergedTree = true).assertTextContains("hi-output")
    }

    @Test
    fun backButtonCallsBack() {
        val calls = showChat(stateOf())
        rule.onNodeWithTag("chat-back").performClick()
        assertEquals(1, calls.back)
    }

    // ---- directory picker ----

    private class FakeTree {
        val calls = mutableListOf<String>()
        val made = mutableListOf<Pair<String, String>>()
        val tree = mutableMapOf<String?, DirListing>(
            null to DirListing("", "", listOf(DirEntry("个人工作区", "/w"), DirEntry("/shared", "/shared"))),
            "/w" to DirListing("/w", "", listOf(DirEntry("projA", "/w/projA"))),
            "/w/projA" to DirListing("/w/projA", "/w", listOf(DirEntry("src", "/w/projA/src"))),
            "/w/projA/src" to DirListing("/w/projA/src", "/w/projA", emptyList()),
            "/shared" to DirListing("/shared", "", emptyList()),
        )

        suspend fun load(path: String?, hidden: Boolean): DirListing {
            calls += "${path ?: "<roots>"}:$hidden"
            return tree[path] ?: if (path == "/boom") throw java.io.IOException("cannot read directory")
            else DirListing(path.orEmpty(), path.orEmpty().substringBeforeLast('/'), emptyList())
        }

        suspend fun mkdir(parent: String, name: String): String {
            made += parent to name
            return "$parent/$name"
        }
    }

    private fun showPicker(tree: FakeTree, initial: String = "", onSelect: (String) -> Unit = {}, onDismiss: () -> Unit = {}) {
        rule.setContent { HarnessTheme { DirectoryPickerDialog(initial, tree::load, tree::mkdir, onSelect, onDismiss) } }
    }

    @Test
    fun pickerWalksDownAndSelectsADirectory() {
        val tree = FakeTree()
        var chosen: String? = null
        showPicker(tree, onSelect = { chosen = it })
        rule.onNodeWithTag("picker-path").assertTextContains("(根目录)")
        rule.onNodeWithTag("picker-select").assertIsNotEnabled() // nothing chosen at the roots
        rule.onNodeWithTag("picker-mkdir").assertIsNotEnabled()

        rule.onNodeWithTag("picker-entry-个人工作区").performClick()
        rule.onNodeWithTag("picker-entry-projA").performClick()
        rule.onNodeWithTag("picker-entry-src").performClick()
        rule.onNodeWithTag("picker-path").assertTextContains("/w/projA/src")
        rule.onNodeWithText("(没有子目录)").assertExists()
        rule.onNodeWithTag("picker-select").assertIsEnabled().performClick()
        assertEquals("/w/projA/src", chosen)
    }

    @Test
    fun pickerGoesUpAndBackToTheRoots() {
        val tree = FakeTree()
        showPicker(tree, initial = "/w/projA")
        rule.onNodeWithTag("picker-path").assertTextContains("/w/projA")
        rule.onNodeWithTag("picker-up").performClick()
        rule.onNodeWithTag("picker-path").assertTextContains("/w")
        rule.onNodeWithTag("picker-up").performClick() // parent of a root is the root list
        rule.onNodeWithTag("picker-path").assertTextContains("(根目录)")
        rule.onNodeWithTag("picker-up").assertDoesNotExist()
        rule.onNodeWithTag("picker-entry-/shared").assertExists()
    }

    @Test
    fun pickerTogglesHiddenAndCreatesFolders() {
        val tree = FakeTree()
        showPicker(tree, initial = "/w")
        rule.onNodeWithTag("picker-hidden").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("picker-hidden").assertIsOn()
        assertTrue("/w:true" in tree.calls, tree.calls.toString())

        rule.onNodeWithTag("picker-mkdir").performClick()
        rule.onNodeWithTag("mkdir-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("mkdir-name").performTextInput("fresh")
        rule.onNodeWithTag("mkdir-confirm").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("/w" to "fresh"), tree.made)
        rule.onNodeWithTag("picker-path").assertTextContains("/w/fresh") // steps into the new folder
    }

    @Test
    fun pickerShowsLoadErrorsAndFallsBackToRoots() {
        val tree = FakeTree()
        showPicker(tree, initial = "/boom")
        rule.onNodeWithTag("picker-error").assertTextContains("cannot read directory", substring = true)
        rule.onNodeWithTag("picker-path").assertTextContains("(根目录)")
    }

    // ---- new session dialog ----

    @Test
    fun newSessionDialogBuildsTheRequestFromThePickedDirectory() {
        val tree = FakeTree()
        var created: CreateSession? = null
        rule.setContent {
            HarnessTheme {
                NewSessionDialog(Me("tony", "/w", listOf("/shared")), null, tree::load, tree::mkdir, { created = it }, {})
            }
        }
        rule.onNodeWithTag("new-title").performTextInput("my task")
        rule.onNodeWithTag("new-pick").performClick()
        rule.onNodeWithTag("picker-entry-/shared").performClick()
        rule.onNodeWithTag("picker-select").performClick()
        rule.onNodeWithTag("new-cwd").assertTextContains("/shared")

        rule.onNodeWithTag("new-model").performClick()
        rule.onNodeWithTag("new-model-haiku").performClick()
        rule.onNodeWithTag("new-mode").performClick()
        rule.onNodeWithTag("new-mode-plan").performClick()
        rule.onNodeWithTag("new-create").performClick()

        val c = assertNotNull(created)
        assertEquals(CreateSession(title = "my task", cwd = "/shared", model = "haiku", permissionMode = "plan"), c)
    }

    @Test
    fun newSessionDialogHidesBypassUnlessAllowedAndShowsServerError() {
        val tree = FakeTree()
        rule.setContent {
            HarnessTheme { NewSessionDialog(Me("tony"), "directory is outside the allowed roots", tree::load, tree::mkdir, {}, {}) }
        }
        rule.onNodeWithTag("new-error").assertTextContains("outside the allowed roots", substring = true)
        rule.onNodeWithTag("new-mode").performClick()
        rule.onNodeWithTag("new-mode-bypassPermissions").assertDoesNotExist()
        rule.onNodeWithTag("new-mode-plan").assertExists()
    }

    // ---- markdown ----

    @Test
    fun markdownRendersCodeAndLists() {
        rule.setContent { HarnessTheme { MarkdownText("# Title\n\n- one\n- two\n\n```\nval x = 1\n```\n\nplain **bold** text") } }
        rule.onNodeWithText("Title").assertExists()
        rule.onNodeWithText("one").assertExists()
        rule.onNodeWithText("val x = 1").assertExists()
        rule.onNode(hasText("plain bold text")).assertExists()
    }

    @Test
    fun markdownTableCellsAndTaskItemsRender() {
        rule.setContent { HarnessTheme { MarkdownText("| a | b |\n|---|:-:|\n| one | two |\n\n- [x] done") } }
        rule.onNodeWithTag("md-table").assertExists()
        rule.onNodeWithText("one").assertExists()
        rule.onNodeWithText("two").assertExists()
        rule.onNode(hasText("done", substring = true)).assertExists()
    }

    // ---- terminal ----

    @Test
    fun terminalKeysSendEscapeSequencesAndStickyCtrl() {
        val api = FakeApi()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val controller = com.cchharness.core.TerminalController(api, "cch00000001", scope)
        controller.start()
        rule.setContent { HarnessTheme { com.cchharness.app.ui.TerminalScreen("t", controller, 14, {}, {}, {}) } }
        val until = System.currentTimeMillis() + 10_000
        while (controller.status.value != com.cchharness.core.TermStatus.Live && System.currentTimeMillis() < until) Thread.sleep(20)
        rule.onNodeWithTag("term-status").assertTextContains("已连接")

        rule.onNodeWithTag("term-key-esc").performClick()
        rule.onNodeWithTag("term-key-up").performClick()
        rule.onNodeWithTag("term-key-ctrl").performClick()
        rule.onNodeWithTag("term-key-char-/").performScrollTo().performClick() // Ctrl+/ = 0x1F, and Ctrl is released afterwards
        rule.onNodeWithTag("term-key-char-/").performClick()
        val want = "\u001b" + "\u001b[A" + "\u001f" + "/"
        while (api.termInput.joinToString("") != want && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(want, api.termInput.joinToString(""))
        controller.stop()
        scope.cancel()
    }

    @Test
    fun terminalShowsAnEndedBanner() {
        val api = FakeApi()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val controller = com.cchharness.core.TerminalController(api, "cch00000001", scope)
        controller.start()
        rule.setContent { HarnessTheme { com.cchharness.app.ui.TerminalScreen("t", controller, 14, {}, {}, {}) } }
        api.termEvents.tryEmit(com.cchharness.core.TermEvent.Exited)
        val until = System.currentTimeMillis() + 10_000
        while (controller.status.value != com.cchharness.core.TermStatus.Ended && System.currentTimeMillis() < until) Thread.sleep(20)
        rule.waitForIdle()
        rule.onNodeWithTag("term-banner").assertTextContains("终端已结束", substring = true)
        scope.cancel()
    }

    // ---- hosts ----

    private val hostList = listOf(
        Host("h1", "家里", "http://192.168.1.164:8765", "tony", "t"),
        Host("h2", "云服务器", "http://10.0.0.5:8765", "tony", ""),
    )

    @Test
    fun hostsDialogSwitchesAddsAndMarksTheLoginState() {
        var switched: String? = null
        var added = false
        rule.setContent {
            HarnessTheme { HostsDialog(hostList, "h1", { switched = it }, { added = true }, { _, _, _ -> true }, {}, {}) }
        }
        rule.onNodeWithText("云服务器").assertExists()
        rule.onNodeWithText("http://10.0.0.5:8765 · 未登录").assertExists()
        rule.onNodeWithText("http://192.168.1.164:8765 · 当前").assertExists()
        rule.onNodeWithTag("host-h2").performClick()
        assertEquals("h2", switched)
        rule.onNodeWithTag("host-add").performClick()
        assertTrue(added)
    }

    @Test
    fun editingAHostPassesTheNewNameAndAddress() {
        var got: Triple<String, String, String>? = null
        rule.setContent {
            HarnessTheme { HostsDialog(hostList, "h1", {}, {}, { id, n, u -> got = Triple(id, n, u); true }, {}, {}) }
        }
        rule.onNodeWithTag("host-edit-h2").performClick()
        rule.onNodeWithTag("host-name").performTextInput("!")
        rule.onNodeWithTag("host-save").performClick()
        assertEquals(Triple("h2", "!云服务器", "http://10.0.0.5:8765"), got)
        rule.onNodeWithTag("host-save").assertDoesNotExist()
    }

    @Test
    fun rejectedAddressStaysInTheEditDialog() {
        rule.setContent {
            HarnessTheme { HostsDialog(hostList, "h1", {}, {}, { _, _, _ -> false }, {}, {}) }
        }
        rule.onNodeWithTag("host-edit-h1").performClick()
        rule.onNodeWithTag("host-save").performClick()
        rule.onNodeWithTag("host-url-error").assertExists()
    }

    @Test
    fun removingAHostNeedsASecondTap() {
        var removed: String? = null
        rule.setContent {
            HarnessTheme { HostsDialog(hostList, "h1", {}, {}, { _, _, _ -> true }, { removed = it }, {}) }
        }
        rule.onNodeWithTag("host-edit-h2").performClick()
        rule.onNodeWithTag("host-remove").performClick()
        assertEquals(null, removed)
        rule.onNodeWithTag("host-remove").performClick()
        assertEquals("h2", removed)
    }

    @Test
    fun sessionListTitleOpensTheHostSwitcher() {
        var opened = false
        rule.setContent {
            HarnessTheme {
                SessionListScreen("tony", sessions, null, {}, {}, {}, {}, {}, {}, hostName = "家里", onHosts = { opened = true })
            }
        }
        rule.onNodeWithTag("host-switcher").assertTextContains("家里 ▾", substring = true)
        rule.onNodeWithTag("host-switcher").performClick()
        assertTrue(opened)
    }

    @Test
    fun connectScreenOffersRetrySwitchAndCancelWhenGiven() {
        var retried = false
        var cancelled = false
        var named: String? = null
        rule.setContent {
            HarnessTheme {
                ConnectScreen("", "", busy = false, error = null, askName = true, onCancel = { cancelled = true }, onRetry = { retried = true }, onHosts = {}) { _, _, _, n -> named = n }
            }
        }
        rule.onNodeWithTag("connect-name").performTextInput("云")
        rule.onNodeWithTag("connect-url").performTextInput("h:1")
        rule.onNodeWithTag("connect-password").performTextInput("p")
        rule.onNodeWithTag("connect-button").performClick()
        assertEquals("云", named)
        rule.onNodeWithTag("connect-retry").performClick()
        rule.onNodeWithTag("connect-cancel").performClick()
        rule.onNodeWithTag("connect-hosts").assertExists()
        assertTrue(retried && cancelled)
    }
}
