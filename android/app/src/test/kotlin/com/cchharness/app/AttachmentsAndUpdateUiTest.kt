package com.cchharness.app

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.cchharness.app.ui.ChatScreen
import com.cchharness.app.ui.HarnessTheme
import com.cchharness.app.ui.SessionListScreen
import com.cchharness.app.ui.UpdateDialog
import com.cchharness.app.ui.slashSuggestions
import com.cchharness.core.Attachment
import com.cchharness.core.ChatReducer
import com.cchharness.core.ChatState
import com.cchharness.core.Connection
import com.cchharness.core.Release
import com.cchharness.core.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentsAndUpdateUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun ev(seq: Long, json: String) = StreamEvent(seq, 0, Json.parseToJsonElement(json).jsonObject)

    private fun stateOf(vararg events: StreamEvent): ChatState {
        val r = ChatReducer()
        events.forEach(r::apply)
        return r.snapshot().copy(status = "idle")
    }

    private class Calls {
        val sent = mutableListOf<String>()
        val removed = mutableListOf<Long>()
        val models = mutableListOf<String>()
        val efforts = mutableListOf<String>()
        var picks = 0
    }

    private fun showChat(
        state: ChatState = stateOf(),
        drafts: List<DraftAttachment> = emptyList(),
        commands: List<String> = emptyList(),
        calls: Calls = Calls(),
        loader: suspend (Attachment) -> ByteArray? = { null },
    ): Calls {
        rule.setContent {
            HarnessTheme {
                ChatScreen(
                    title = "demo", cwd = "/work", state = state, connection = Connection.Live,
                    fallbackMode = "manual", allowBypass = false, notice = null,
                    onBack = {}, onSend = { calls.sent += it }, onInterrupt = {}, onStopProcess = {}, onSetMode = {},
                    onAllow = { _, _ -> }, onDeny = {}, onAnswer = { _, _ -> }, onDismissNotice = {},
                    drafts = drafts, commands = commands, fallbackModel = "sonnet", fallbackEffort = "high",
                    onPickFiles = { calls.picks++ }, onRemoveDraft = { calls.removed += it },
                    onSetModel = { calls.models += it }, onSetEffort = { calls.efforts += it },
                    loadAttachment = loader,
                )
            }
        }
        return calls
    }

    private fun png(): ByteArray {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun draft(id: Long, name: String, state: UploadState, image: Boolean = false) =
        DraftAttachment(id, name, 2048, image, state)

    // ---- slash commands ----

    @Test
    fun suggestionLogic() {
        val cmds = listOf("compact", "context", "review", "code-review")
        assertEquals(listOf("compact", "context", "code-review"), slashSuggestions("/co", cmds))
        assertEquals(cmds, slashSuggestions("/", cmds))
        assertEquals(listOf("review", "code-review"), slashSuggestions("/REV", cmds))
        assertEquals(emptyList(), slashSuggestions("/context now", cmds), "arguments end completion")
        assertEquals(emptyList(), slashSuggestions("hello /co", cmds))
        assertEquals(emptyList(), slashSuggestions("/zzz", cmds))
    }

    @Test
    fun typingSlashListsCommandsAndPickingFillsTheInput() {
        showChat(commands = listOf("compact", "context", "review"))
        rule.onNodeWithTag("slash-list").assertDoesNotExist()
        rule.onNodeWithTag("chat-input").performTextInput("/co")
        rule.onNodeWithTag("slash-compact").assertExists()
        rule.onNodeWithTag("slash-context").assertExists()
        rule.onNodeWithTag("slash-review").assertDoesNotExist()
        rule.onNodeWithTag("slash-context").performClick()
        rule.onNodeWithTag("chat-input").assertTextContains("/context ", substring = true)
        rule.onNodeWithTag("slash-list").assertDoesNotExist()
    }

    @Test
    fun commandsReportedByTheChatWinOverTheFallbackList() {
        val state = stateOf(ev(1, """{"type":"system","subtype":"init","slash_commands":["review"]}"""))
        showChat(state, commands = listOf("compact"))
        rule.onNodeWithTag("chat-input").performTextInput("/")
        rule.onNodeWithTag("slash-review").assertExists()
        rule.onNodeWithTag("slash-compact").assertDoesNotExist()
        rule.onNodeWithTag("chat-input").performTextReplacement("/review do it")
        rule.onNodeWithTag("slash-list").assertDoesNotExist()
    }

    // ---- attachments ----

    @Test
    fun attachButtonOpensThePicker() {
        val calls = showChat()
        rule.onNodeWithTag("chat-attach").performClick()
        assertEquals(1, calls.picks)
    }

    @Test
    fun draftChipsShowProgressFailureAndCanBeRemoved() {
        val calls = showChat(
            drafts = listOf(
                draft(1, "notes.txt", UploadState.Done(Attachment("a", "notes.txt"))),
                draft(2, "pic.png", UploadState.Uploading, image = true),
                draft(3, "big.bin", UploadState.Failed("超过 25 MB")),
            ),
        )
        rule.onNodeWithTag("draft-1").assertTextContains("notes.txt", substring = true)
        rule.onNodeWithTag("draft-2").assertTextContains("上传中", substring = true)
        rule.onNodeWithTag("draft-3").assertTextContains("超过 25 MB", substring = true)
        rule.onNodeWithTag("draft-3").performClick()
        assertEquals(listOf(3L), calls.removed)
    }

    @Test
    fun sendIsBlockedWhileUploadingButAttachmentsAloneEnableIt() {
        showChat(drafts = listOf(draft(1, "a.txt", UploadState.Uploading)))
        rule.onNodeWithTag("chat-input").performTextInput("hi")
        rule.onNodeWithTag("chat-send").assertIsNotEnabled()
    }

    @Test
    fun attachmentWithoutTextCanBeSent() {
        val calls = showChat(drafts = listOf(draft(1, "a.txt", UploadState.Done(Attachment("a", "a.txt")))))
        rule.onNodeWithTag("chat-send").assertIsEnabled().performClick()
        assertEquals(listOf(""), calls.sent)
    }

    @Test
    fun userMessagesShowImagesAndFiles() {
        val state = stateOf(
            ev(
                1,
                """{"type":"harness","kind":"user_message","text":"look","attachments":[
                    {"id":"img1","name":"p.png","kind":"image","mime":"image/png","size":10},
                    {"id":"f1","name":"notes.txt","kind":"file","mime":"text/plain","size":2048}]}""",
            ),
        )
        val bytes = png()
        showChat(state, loader = { if (it.id == "img1") bytes else null })
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTagCount("att-image-img1") == 1
        }
        rule.onNodeWithTag("att-f1").assertTextContains("notes.txt (2.0 KB)", substring = true)
    }

    @Test
    fun unreadableImageFallsBackToItsName() {
        val state = stateOf(ev(1, """{"type":"harness","kind":"user_message","text":"","attachments":[{"id":"img1","name":"p.png","kind":"image","size":1}]}"""))
        showChat(state, loader = { null })
        rule.onNodeWithTag("att-img1").assertTextContains("p.png", substring = true)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    // ---- model / effort ----

    @Test
    fun settingsDialogChangesModelAndEffort() {
        val calls = showChat(stateOf(ev(1, """{"type":"harness","kind":"effort_changed","effort":"low"}""")))
        rule.onNodeWithTag("chat-menu").performClick()
        rule.onNodeWithTag("menu-settings").performClick()
        rule.onNodeWithTag("chat-effort").assertTextContains("低", substring = true)
        rule.onNodeWithTag("chat-model").assertTextContains("sonnet", substring = true)
        rule.onNodeWithTag("chat-effort").performClick()
        rule.onNodeWithTag("chat-effort-max").performClick()
        rule.onNodeWithTag("chat-model").performClick()
        rule.onNodeWithTag("chat-model-opus").performClick()
        assertEquals(listOf("max"), calls.efforts)
        assertEquals(listOf("opus"), calls.models)
        rule.onNodeWithTag("chat-settings-close").performClick()
        rule.onNodeWithTag("chat-effort").assertDoesNotExist()
    }

    // ---- updates ----

    private fun release() = Release(7, "1.4.0", "a".repeat(64), 5_242_880, "修复若干问题\n新增上传")

    @Test
    fun sessionListOffersAndChecksForUpdates() {
        var checks = 0
        var shown = 0
        var update by androidx.compose.runtime.mutableStateOf<UpdateState>(UpdateState.Idle)
        rule.setContent {
            HarnessTheme {
                SessionListScreen(
                    user = "tony", sessions = emptyList(), notice = null, onOpen = {}, onNew = {}, onDelete = {},
                    onRefresh = {}, onLogout = {}, onDismissNotice = {},
                    versionName = "1.1.0", update = update, onCheckUpdate = { checks++ }, onShowUpdate = { shown++ },
                )
            }
        }
        rule.onNodeWithTag("app-version").assertTextContains("1.1.0", substring = true)
        rule.onNodeWithTag("update-banner").assertDoesNotExist()
        rule.onNodeWithTag("check-update").performClick()
        assertEquals(1, checks)

        update = UpdateState.Checking
        rule.onNodeWithTag("check-update").assertIsNotEnabled()
        update = UpdateState.Available(release())
        rule.onNodeWithTag("update-banner").performClick()
        assertEquals(1, shown)
    }

    private fun showUpdate(state: UpdateState): IntArray {
        val hits = IntArray(3) // start, install, dismiss
        rule.setContent {
            HarnessTheme {
                UpdateDialog(state, "1.1.0", { hits[0]++ }, { hits[1]++ }, { hits[2]++ })
            }
        }
        return hits
    }

    @Test
    fun availableUpdateShowsNotesAndStartsTheDownload() {
        val hits = showUpdate(UpdateState.Available(release()))
        rule.onNodeWithTag("update-notes").assertTextContains("新增上传", substring = true)
        rule.onNodeWithTag("update-go").assertTextContains("下载并安装").performClick()
        assertEquals(listOf(1, 0, 0), hits.toList())
        rule.onNodeWithTag("update-later").performClick()
        assertEquals(1, hits[2])
    }

    @Test
    fun downloadingShowsProgressAndNoActionButton() {
        showUpdate(UpdateState.Downloading(release(), 2_621_440, 5_242_880))
        rule.onNodeWithTag("update-progress").assertExists()
        rule.onNodeWithTag("update-go").assertDoesNotExist()
        rule.onNodeWithTag("update-later").assertTextContains("后台下载")
    }

    @Test
    fun permissionAndFailureStatesOfferTheRightAction() {
        val hits = showUpdate(UpdateState.NeedsPermission(release(), File("x.apk")))
        rule.onNodeWithTag("update-permission").assertExists()
        rule.onNodeWithTag("update-go").assertTextContains("安装").performClick()
        assertEquals(listOf(0, 1, 0), hits.toList())
    }

    @Test
    fun failedUpdateCanBeRetried() {
        val hits = showUpdate(UpdateState.Failed("下载文件校验失败,请重试", release()))
        rule.onNodeWithTag("update-error").assertTextContains("校验失败", substring = true)
        rule.onNodeWithTag("update-go").assertTextContains("重试").performClick()
        assertEquals(1, hits[0])
    }

    // ---- installer ----

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun apk(): File = File(PackageApkInstaller.updateDir(app), "u.apk").also {
        it.parentFile!!.mkdirs()
        it.writeText("apk")
    }

    @Test
    fun installerSendsYouToSettingsUntilInstallsAreAllowed() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        assertEquals(InstallResult.NeedsPermission, PackageApkInstaller(app).install(apk()))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    @Test
    fun installerHandsTheApkToThePackageInstallerThroughAFileProvider() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        assertEquals(InstallResult.Started, PackageApkInstaller(app).install(apk()))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(PackageApkInstaller.APK_MIME, intent.type)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${app.packageName}.updates", intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun usagePanelShowsEveryLimitAndItsReset() {
        val usage = com.cchharness.core.Usage(
            "max",
            listOf(
                com.cchharness.core.UsageLimit("session", "当前会话(5 小时)", 12.0, resetsAt = java.time.Instant.now().plusSeconds(3 * 3600 + 1200).toString()),
                com.cchharness.core.UsageLimit("weekly_scoped", "Fable 额度", 0.0),
            ),
        )
        rule.setContent { HarnessTheme { com.cchharness.app.ui.UsagePanel(usage, null) } }
        rule.onNodeWithTag("usage-session-当前会话(5 小时)").assertTextContains("12%", substring = true)
        rule.onNodeWithTag("usage-session-当前会话(5 小时)").assertTextContains("小时", substring = true)
        rule.onNodeWithTag("usage-weekly_scoped-Fable 额度").assertTextContains("Fable 额度", substring = true)
    }

    @Test
    fun usagePanelShowsAnErrorOnlyWhenThereAreNoFigures() {
        rule.setContent { HarnessTheme { com.cchharness.app.ui.UsagePanel(null, "claude login has expired") } }
        rule.onNodeWithTag("usage-error").assertTextContains("expired", substring = true)
    }

    @Test
    fun resetTextIsHumanReadable() {
        val now = java.time.Instant.parse("2026-10-24T00:00:00Z")
        assertEquals("", com.cchharness.app.ui.resetText(null, now))
        assertEquals("", com.cchharness.app.ui.resetText("2026-10-23T00:00:00Z", now))
        assertEquals("25 分钟后重置", com.cchharness.app.ui.resetText("2026-10-24T00:25:00Z", now))
        assertEquals("3 小时 20 分后重置", com.cchharness.app.ui.resetText("2026-10-24T03:20:00Z", now))
        assertEquals("6 天后重置", com.cchharness.app.ui.resetText("2026-10-30T00:00:00Z", now))
    }

    @Test
    fun importDialogListsFiltersAndImports() {
        val picked = mutableListOf<String>()
        var forced: String? = null
        val sessions = listOf(
            com.cchharness.core.LocalSession("a", "整理 README", "/w/p"),
            com.cchharness.core.LocalSession("b", "修 bug", "/w/q", active = true),
        )
        rule.setContent {
            HarnessTheme {
                com.cchharness.app.ui.ImportDialog(LocalImport(sessions = sessions, confirmForce = "b"), { picked += it }, { forced = it }, {}, {})
            }
        }
        rule.onNodeWithTag("local-a").performClick()
        assertEquals(listOf("a"), picked)
        rule.onNodeWithTag("local-b").assertTextContains("可能正在使用", substring = true)
        rule.onNodeWithTag("import-force").performClick()
        assertEquals("b", forced)
    }
}
