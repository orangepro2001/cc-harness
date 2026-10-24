package com.cchharness.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.cchharness.app.ui.ChatScreen
import com.cchharness.app.ui.ConnectScreen
import com.cchharness.app.ui.DirectoryPickerDialog
import com.cchharness.app.ui.HarnessTheme
import com.cchharness.app.ui.NewSessionDialog
import com.cchharness.app.ui.SessionListScreen
import com.cchharness.app.ui.MarkdownText
import com.cchharness.app.ui.TerminalScreen
import com.cchharness.app.ui.TerminalsScreen
import com.cchharness.core.TermEvent
import com.cchharness.core.TerminalController
import com.cchharness.core.TerminalInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cchharness.core.ChatReducer
import com.cchharness.core.Connection
import com.cchharness.core.DirEntry
import com.cchharness.core.DirListing
import com.cchharness.core.Me
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
import java.io.File
import kotlin.test.assertTrue

/**
 * Renders the real screens with native graphics and writes PNGs to app/build/screenshots,
 * so the layout can be inspected without an emulator. Each test also checks the image is
 * not blank.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir = File("build/screenshots").also { it.mkdirs() }

    /** Root views of every window (activity first, then dialogs/popups), via WindowManagerGlobal. */
    private fun windows(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val field = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(global) as List<View>).toList()
    }

    private fun draw(view: View): Bitmap {
        val w = view.width.takeIf { it > 0 } ?: view.measuredWidth
        val h = view.height.takeIf { it > 0 } ?: view.measuredHeight
        val bmp = Bitmap.createBitmap(maxOf(w, 1), maxOf(h, 1), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        view.draw(Canvas(bmp))
        return bmp
    }

    /** Screenshot of the topmost window (the dialog when one is showing, else the activity). */
    private fun save(@Suppress("UNUSED_PARAMETER") ignored: SemanticsNodeInteraction?, name: String) {
        rule.waitForIdle()
        val bmp = draw(windows().last())
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val first = bmp.getPixel(0, 0)
        var varied = false
        for (x in 0 until bmp.width step 17) for (y in 0 until bmp.height step 17) if (bmp.getPixel(x, y) != first) varied = true
        assertTrue(varied, "$name is blank")
    }

    private fun ev(seq: Long, json: String) = StreamEvent(seq, 0, Json.parseToJsonElement(json).jsonObject)

    private fun conversation() = ChatReducer().also { r ->
        javaClass.getResourceAsStream("/real-session.jsonl")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }.forEach { r.apply(SseParser.parseEvent(it)!!) }
        r.apply(ev(100, """{"type":"harness","kind":"user_message","text":"请把 README 里的安装步骤整理成列表"}"""))
        r.apply(ev(101, """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"tu9","name":"Read","input":{"file_path":"/work/README.md"}}]}}"""))
        r.apply(ev(102, """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tu9","content":"# demo\n安装: make install"}]}}"""))
        r.apply(ev(103, """{"type":"control_request","request_id":"p9","request":{"subtype":"can_use_tool","tool_name":"Edit","input":{"file_path":"/work/README.md","old_string":"make install","new_string":"1. make install"},"permission_suggestions":[{"type":"setMode"}]}}"""))
        r.apply(ev(0, """{"type":"harness","kind":"status","status":"awaiting_permission"}"""))
        r.apply(ev(0, """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"正在整理 **安装步骤**，稍后给出 `make install` 的说明。"}}}"""))
    }.snapshot()

    private val sessions = listOf(
        SessionInfo(id = "a", title = "整理 README", cwd = "/home/tony/dev/cc-harness", status = "awaiting_permission"),
        SessionInfo(id = "b", title = "重构登录模块", cwd = "/home/tony/dev/app", status = "busy"),
        SessionInfo(id = "c", title = "", cwd = "/home/tony/dev/scratch", status = "stopped"),
    )

    private val tree = mapOf<String?, DirListing>(
        null to DirListing("", "", listOf(DirEntry("个人工作区", "/w"), DirEntry("/home/tony/dev", "/home/tony/dev"))),
        "/home/tony/dev" to DirListing(
            "/home/tony/dev", "",
            listOf("AFVI_UI", "cc-harness", "daily_work_web", "dsh-proxy").map { DirEntry(it, "/home/tony/dev/$it") },
        ),
    )

    @Test
    fun connect() {
        rule.setContent { HarnessTheme { ConnectScreen("http://192.168.1.164:8765", "tony", false, "用户名或密码错误") { _, _, _ -> } } }
        rule.onNodeWithTag("connect-password").performTextInput("secret")
        save(null, "01-connect")
    }

    @Test
    fun sessionList() {
        rule.setContent { HarnessTheme { SessionListScreen("tony", sessions, null, {}, {}, {}, {}, {}, {}) } }
        save(null, "02-sessions")
    }

    @Test
    fun chat() {
        val state = conversation()
        rule.setContent {
            HarnessTheme {
                ChatScreen(
                    "整理 README", "/home/tony/dev/cc-harness", state, Connection.Live, "manual", false, null,
                    {}, {}, {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, {},
                )
            }
        }
        save(null, "03-chat")
    }

    @Test
    fun newSessionAndPicker() {
        rule.setContent {
            HarnessTheme {
                NewSessionDialog(Me("tony", "/w", listOf("/home/tony/dev")), null, { p, _ -> tree[p] ?: DirListing(p.orEmpty()) }, { p, n -> "$p/$n" }, {}, {})
            }
        }
        rule.onNodeWithTag("new-title").performTextInput("整理 README")
        save(null, "04-new-session")
        rule.onNodeWithTag("new-pick").performClick()
        rule.onNodeWithTag("picker-entry-/home/tony/dev").performClick()
        save(null, "05-picker")
    }

    @Test
    fun pickerAtRoots() {
        rule.setContent { HarnessTheme { DirectoryPickerDialog("", { p, _ -> tree[p] ?: DirListing(p.orEmpty()) }, { p, n -> "$p/$n" }, {}, {}) } }
        save(null, "06-picker-roots")
    }

    @Test
    fun chatWithAttachmentsAndSlashCommands() {
        val r = ChatReducer()
        r.apply(ev(1, """{"type":"system","subtype":"init","model":"claude-sonnet","slash_commands":["compact","context","review","code-review","security-review","simplify"]}"""))
        r.apply(
            ev(
                2,
                """{"type":"harness","kind":"user_message","text":"帮我看看这张截图和日志","attachments":[
                    {"id":"i1","name":"crash.png","kind":"image","mime":"image/png","size":1},
                    {"id":"f1","name":"server.log","kind":"file","mime":"text/plain","size":183500}]}""",
            ),
        )
        val state = r.snapshot().copy(status = "idle")
        val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF4A78C2.toInt()) }
        val png = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        rule.setContent {
            HarnessTheme {
                ChatScreen(
                    "日志排查", "/home/tony/dev/cc-harness", state, Connection.Live, "manual", false, null,
                    {}, {}, {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, {},
                    drafts = listOf(
                        DraftAttachment(1, "trace.txt", 20480, false, UploadState.Done(com.cchharness.core.Attachment("t", "trace.txt"))),
                        DraftAttachment(2, "screen.jpg", 2_400_000, true, UploadState.Uploading),
                    ),
                    loadAttachment = { png },
                )
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("att-image-i1")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("chat-input").performTextInput("/re")
        save(null, "07-chat-attachments-slash")
    }

    @Test
    fun chatSettingsDialog() {
        rule.setContent {
            HarnessTheme {
                ChatScreen(
                    "整理 README", "/work", conversation(), Connection.Live, "manual", false, null,
                    {}, {}, {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, {},
                    fallbackModel = "sonnet", fallbackEffort = "high",
                )
            }
        }
        rule.onNodeWithTag("chat-menu").performClick()
        rule.onNodeWithTag("menu-settings").performClick()
        save(null, "08-chat-settings")
    }

    @Test
    fun updateOffer() {
        val rel = com.cchharness.core.Release(7, "1.4.0", "a".repeat(64), 10_485_760, "修复若干问题\n新增图片和文件上传\n支持斜杠命令")
        rule.setContent {
            HarnessTheme {
                SessionListScreen(
                    "tony", sessions, null, {}, {}, {}, {}, {}, {},
                    versionName = "1.1.0", update = UpdateState.Available(rel),
                )
                com.cchharness.app.ui.UpdateDialog(UpdateState.Downloading(rel, 4_194_304, 10_485_760), "1.1.0", {}, {}, {})
            }
        }
        save(null, "09-update-dialog")
    }

    @Test
    fun markdownWithTablesAndQuotes() {
        val md = """
            ### 三、极致跨平台

            这里有几个**关键点**：~~旧结论~~ 见 https://example.com/a。`inline | code`

            | 技术栈 | Win / Linux / mac | 一台机器全出 | 旧 Windows |
            | --- | :-: | --: | --- |
            | Avalonia(本项目) | 三个都有 | 是(非 AOT) | .NET 10 要求 Win10 以上 |
            | Wails | 三个都有 | Windows 可以,Linux/mac 需要对应机器 | WebView2 要 Win10 以上 |
            | Gio / ImGui | 三个都有 | Gio 可以(无 cgo) | 较好 |

            > 结论:一份代码覆盖面最广的是 Avalonia 和 Qt。

            - 顶层
              - 嵌套 **粗体 `code`**
            - [x] 已完成
            - [ ] 待办

            ---

            ```go
            func main() {}
            ```
        """.trimIndent()
        rule.setContent {
            HarnessTheme {
                androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                    MarkdownText(md, Modifier.padding(16.dp))
                }
            }
        }
        rule.onNodeWithTag("md-table").assertExists()
        rule.onNodeWithTag("md-quote").assertExists()
        save(null, "10-markdown")
    }

    @Test
    fun terminalsList() {
        val list = listOf(
            TerminalInfo("cch00000001", "cc-harness", "shell", "/home/tony/dev/cc-harness", attached = 1),
            TerminalInfo("cch00000002", "claude · 整理 README", "claude", "/home/tony/dev/app"),
        )
        rule.setContent { HarnessTheme { TerminalsScreen(list, null, {}, {}, {}, {}, {}) } }
        save(null, "11-terminals")
    }

    @Test
    fun terminalScreen() {
        val api = FakeApi()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = TerminalController(api, "cch00000001", scope, cols = 52, rows = 30)
        val e = "\u001b"
        val screen = buildString {
            append("$e[1;32mtony@ai-pc$e[0m:$e[1;34m~/dev/cc-harness$e[0m$ git status -sb\r\n")
            append("## main\r\n$e[31m M$e[0m android/app/src/main/kotlin/ui/Theme.kt\r\n$e[31m M$e[0m web/style.css\r\n$e[31m??$e[0m terminal.go\r\n")
            append("$e[1;32mtony@ai-pc$e[0m:$e[1;34m~/dev/cc-harness$e[0m$ go test ./...\r\n")
            append("ok  \tcc-harness\t2.708s\r\n")
            append("$e[38;5;208m● $e[0mClaude Code 终端 · 中文宽字符 OK\r\n")
            append("$e[1;32mtony@ai-pc$e[0m:$e[1;34m~/dev/cc-harness$e[0m$ ")
        }
        api.termEvents.tryEmit(TermEvent.Output(screen.toByteArray()))
        controller.start()
        val until = System.currentTimeMillis() + 10_000
        while (!controller.emulator.screenText().contains("中文宽字符") && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(controller.emulator.screenText().contains("go test"), controller.emulator.screenText())
        rule.setContent { HarnessTheme { TerminalScreen("cc-harness", controller, 13, {}, {}, {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("term-keys").assertExists()
        save(null, "12-terminal")
        controller.stop()
        scope.cancel()
    }
}
