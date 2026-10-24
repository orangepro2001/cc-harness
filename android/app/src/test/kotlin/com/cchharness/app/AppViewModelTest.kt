package com.cchharness.app

import androidx.lifecycle.viewModelScope
import com.cchharness.core.ApiException
import com.cchharness.core.Attachment
import com.cchharness.core.ChecksumException
import com.cchharness.core.Release
import com.cchharness.core.UploadSource
import com.cchharness.core.AuthException
import com.cchharness.core.CreateSession
import com.cchharness.core.LocalSession
import com.cchharness.core.Usage
import com.cchharness.core.UsageLimit
import com.cchharness.core.RateLimitedException
import com.cchharness.core.SessionInfo
import com.cchharness.core.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setMain() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    private fun TestScope.vm(settings: FakeSettings, api: FakeApi, poll: Long = 4_000): AppViewModel =
        AppViewModel(settings, { api }, poll).also { runCurrent() }

    private fun run(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private fun info(id: String, title: String = id) = SessionInfo(id = id, title = title, cwd = "/work", status = "idle")

    @Test
    fun startsAtConnectWithoutSavedLogin() = run {
        val v = vm(FakeSettings(), FakeApi())
        assertEquals(Screen.Connect, v.screen.value)
        v.viewModelScope.cancel()
    }

    @Test
    fun savedTokenRestoresTheSessionList() = run {
        val api = FakeApi().also { it.sessions += info("a") }
        val v = vm(FakeSettings("http://h:1", "tony", "saved"), api)
        assertEquals(Screen.Sessions, v.screen.value)
        assertEquals("saved", api.token)
        assertEquals(listOf("a"), v.sessions.value.map { it.id })
        assertEquals("tony", v.me.value?.user)
        v.viewModelScope.cancel()
    }

    @Test
    fun expiredTokenReturnsToConnectAndForgetsIt() = run {
        val settings = FakeSettings("http://h:1", "tony", "old")
        val api = FakeApi().also { it.onMe = { throw AuthException("expired") } }
        val v = vm(settings, api)
        assertEquals(Screen.Connect, v.screen.value)
        assertEquals("", settings.token)
        assertTrue(v.connectError.value!!.contains("过期"))
        v.viewModelScope.cancel()
    }

    @Test
    fun unreachableServerKeepsTheTokenForNextTime() = run {
        val settings = FakeSettings("http://h:1", "tony", "keep")
        val api = FakeApi().also { it.onMe = { throw IOException("timeout") } }
        val v = vm(settings, api)
        assertEquals(Screen.Connect, v.screen.value)
        assertEquals("keep", settings.token)
        assertTrue(v.connectError.value!!.contains("无法连接"))
        v.viewModelScope.cancel()
    }

    @Test
    fun connectStoresSettingsButNeverThePassword() = run {
        val settings = FakeSettings()
        val api = FakeApi()
        val v = vm(settings, api)
        v.connect("192.168.1.164:8765/", " Tony ", "test-pass-1")
        runCurrent()
        assertEquals(Screen.Sessions, v.screen.value)
        assertEquals("http://192.168.1.164:8765", settings.baseUrl)
        assertEquals("tony", settings.user)
        assertEquals("tok-tony", settings.token)
        assertEquals("login:tony:test-pass-1", api.calls.first { it.startsWith("login") })
        assertTrue(listOf(settings.baseUrl, settings.user, settings.token).none { "test-pass-1" in it })
        v.viewModelScope.cancel()
    }

    @Test
    fun connectFailuresAreExplained() = run {
        val api = FakeApi()
        val v = vm(FakeSettings(), api)

        api.onLogin = { _, _ -> throw AuthException("invalid") }
        v.connect("h:1", "u", "p"); runCurrent()
        assertEquals("用户名或密码错误", v.connectError.value)
        assertEquals(Screen.Connect, v.screen.value)

        api.onLogin = { _, _ -> throw RateLimitedException("locked", 299) }
        v.connect("h:1", "u", "p"); runCurrent()
        assertTrue(v.connectError.value!!.contains("299"))

        api.onLogin = { _, _ -> throw IOException("refused") }
        v.connect("h:1", "u", "p"); runCurrent()
        assertTrue(v.connectError.value!!.contains("refused"))

        api.onLogin = { _, _ -> throw ApiException(500, "boom") }
        v.connect("h:1", "u", "p"); runCurrent()
        assertTrue(v.connectError.value!!.contains("boom"))

        v.connect("   ", "u", "p"); runCurrent()
        assertEquals("服务器地址无效", v.connectError.value)
        assertEquals(false, v.busy.value)
        v.viewModelScope.cancel()
    }

    @Test
    fun urlNormalization() {
        assertEquals("http://a:1", AppViewModel.normalizeUrl("a:1"))
        assertEquals("https://a", AppViewModel.normalizeUrl(" https://a/ "))
        assertFailsWith<IllegalArgumentException> { AppViewModel.normalizeUrl("  ") }
    }

    @Test
    fun sessionListPollsAndSurvivesTransientErrors() = run {
        val api = FakeApi()
        val v = vm(FakeSettings("http://h:1", "tony", "t"), api)
        assertEquals(emptyList(), v.sessions.value)

        api.sessions += info("a")
        advanceTimeBy(4_001); runCurrent()
        assertEquals(listOf("a"), v.sessions.value.map { it.id })

        api.onList = { throw IOException("wifi down") }
        advanceTimeBy(4_001); runCurrent()
        assertTrue(v.notice.value!!.contains("wifi down"))
        assertEquals(listOf("a"), v.sessions.value.map { it.id }, "stale list is kept while offline")
        v.viewModelScope.cancel()
    }

    @Test
    fun authLossWhilePollingSendsYouBackToLogin() = run {
        val settings = FakeSettings("http://h:1", "tony", "t")
        val api = FakeApi()
        val v = vm(settings, api)
        api.onList = { throw AuthException("expired") }
        advanceTimeBy(4_001); runCurrent()
        assertEquals(Screen.Connect, v.screen.value)
        assertEquals("", settings.token)
        v.viewModelScope.cancel()
    }

    @Test
    fun creatingASessionOpensItAndStreamsEvents() = run {
        val api = FakeApi()
        val v = vm(FakeSettings("http://h:1", "tony", "t"), api)
        v.createSession(CreateSession(title = "demo", cwd = "/shared/p", model = "haiku", permissionMode = "plan"))
        runCurrent()
        assertEquals(Screen.Chat("new-id"), v.screen.value)
        assertEquals("create:demo:/shared/p:haiku:plan", api.calls.last { it.startsWith("create") })

        val chat = v.chat.value!!
        api.events.emit(StreamEvent(1, 0, kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"harness","kind":"user_message","text":"hi"}""").jsonObject))
        runCurrent(); advanceTimeBy(60); runCurrent()
        assertEquals(1, chat.controller.state.value.items.size)

        v.send("hello"); runCurrent()
        assertTrue("send:new-id:hello" in api.calls)

        v.closeChat(); runCurrent()
        assertEquals(Screen.Sessions, v.screen.value)
        assertNull(v.chat.value)
        v.viewModelScope.cancel()
    }

    @Test
    fun createFailureIsShownInTheDialogAndStaysOnTheList() = run {
        val api = FakeApi().also { it.onCreate = { throw ApiException(400, "directory is outside the allowed roots") } }
        val v = vm(FakeSettings("http://h:1", "tony", "t"), api)
        v.createSession(CreateSession(cwd = "/etc")); runCurrent()
        assertEquals("directory is outside the allowed roots", v.createError.value)
        assertEquals(Screen.Sessions, v.screen.value)
        v.clearCreateError()
        assertNull(v.createError.value)
        v.viewModelScope.cancel()
    }

    @Test
    fun chatActionsReachTheApiAndErrorsBecomeNotices() = run {
        val api = FakeApi().also { it.sessions += info("s1") }
        val v = vm(FakeSettings("http://h:1", "tony", "t"), api)
        v.open("s1"); runCurrent()
        v.interrupt(); v.stopProcess(); v.setMode("plan"); v.allow("r1", true); v.deny("r2"); runCurrent()
        assertTrue(
            api.calls.containsAll(
                listOf("interrupt:s1", "stop:s1", "update:s1:null:plan:null", "perm:s1:r1:true:true:", "perm:s1:r2:false:false:"),
            ),
            api.calls.toString(),
        )
        api.onSend = { _, _ -> throw IOException("offline") }
        v.send("x"); runCurrent()
        assertTrue(v.notice.value!!.contains("offline"))
        v.dismissNotice()
        assertNull(v.notice.value)
        v.viewModelScope.cancel()
    }

    @Test
    fun deleteAndLogout() = run {
        val settings = FakeSettings("http://h:1", "tony", "t")
        val api = FakeApi().also { it.sessions += info("a"); it.sessions += info("b") }
        val v = vm(settings, api)
        v.deleteSession("a"); runCurrent()
        assertEquals(listOf("b"), v.sessions.value.map { it.id })

        v.logout(); runCurrent()
        assertEquals(Screen.Connect, v.screen.value)
        assertEquals("", settings.token)
        assertTrue("logout" in api.calls)
        assertEquals(emptyList(), v.sessions.value)
        v.viewModelScope.cancel()
    }

    @Test
    fun directoryCallsGoThroughTheApi() = run {
        val api = FakeApi()
        val v = vm(FakeSettings("http://h:1", "tony", "t"), api)
        v.listDirs(null, false)
        v.listDirs("/shared", true)
        assertEquals("/shared/new", v.makeDir("/shared", "new"))
        assertEquals(listOf("dirs:<roots>:false", "dirs:/shared:true", "mkdir:/shared:new"), api.calls.filter { it.startsWith("dirs") || it.startsWith("mkdir") })
        v.viewModelScope.cancel()
    }

    // ---- attachments, slash commands, effort ----

    private fun src(name: String, mime: String = "text/plain", size: Long = 5) = UploadSource(name, mime, size) { ByteArray(size.toInt()).inputStream() }

    private fun TestScope.chatVm(api: FakeApi, files: Map<String, UploadSource?>): AppViewModel =
        AppViewModel(FakeSettings("http://h:1", "tony", "t"), { api }, 4_000, resolver = { files[it] }).also {
            api.sessions += info("s1")
            runCurrent()
            it.open("s1")
            runCurrent()
        }

    @Test
    fun uploadsBecomeDraftsAndAreSentWithTheMessage() = run {
        val api = FakeApi()
        val v = chatVm(api, mapOf("content://a" to src("a.txt"), "content://p" to src("p.png", "image/png", 9)))
        v.addAttachments(listOf("content://a", "content://p"))
        assertTrue(v.drafts.value.all { it.state is UploadState.Uploading })
        runCurrent()
        assertEquals(listOf("a.txt", "p.png"), v.drafts.value.map { it.name })
        assertEquals(listOf(false, true), v.drafts.value.map { it.image })
        assertTrue(v.drafts.value.all { it.state is UploadState.Done })

        v.send("see these"); runCurrent()
        assertTrue("send:s1:see these:att=att-a.txt,att-p.png" in api.calls, api.calls.toString())
        assertEquals(emptyList(), v.drafts.value)
        v.viewModelScope.cancel()
    }

    @Test
    fun anAttachmentAloneCanBeSent() = run {
        val api = FakeApi()
        val v = chatVm(api, mapOf("content://a" to src("a.txt")))
        v.addAttachments(listOf("content://a")); runCurrent()
        v.send(""); runCurrent()
        assertTrue("send:s1::att=att-a.txt" in api.calls, api.calls.toString())
        v.send(""); runCurrent() // nothing left to send
        assertEquals(1, api.calls.count { it.startsWith("send:") })
        v.viewModelScope.cancel()
    }

    @Test
    fun sendWaitsForUploadsAndRefusesFailedOnes() = run {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val api = FakeApi().also { it.onUpload = { f -> gate.await(); Attachment("id-${f.name}", f.name) } }
        val v = chatVm(api, mapOf("content://a" to src("a.txt"), "content://gone" to null, "content://big" to src("big.bin", size = 26L * 1024 * 1024)))
        v.addAttachments(listOf("content://a")); runCurrent()
        v.send("too early"); runCurrent()
        assertTrue(v.notice.value!!.contains("上传"))
        assertTrue(api.calls.none { it.startsWith("send:") })

        gate.complete(Unit); runCurrent()
        v.addAttachments(listOf("content://gone", "content://big")); runCurrent()
        val failed = v.drafts.value.filter { it.state is UploadState.Failed }
        assertEquals(2, failed.size)
        assertTrue((failed[0].state as UploadState.Failed).message.contains("无法读取"))
        assertTrue((failed[1].state as UploadState.Failed).message.contains("25"))
        assertTrue(api.calls.none { it.startsWith("upload:s1:big.bin") }, "oversized files are never uploaded")
        v.send("x"); runCurrent()
        assertTrue(v.notice.value!!.contains("失败"))

        failed.forEach { v.removeAttachment(it.localId) }
        v.send("now"); runCurrent()
        assertTrue("send:s1:now:att=id-a.txt" in api.calls, api.calls.toString())
        v.viewModelScope.cancel()
    }

    @Test
    fun serverUploadErrorMarksTheDraftFailed() = run {
        val api = FakeApi().also { it.onUpload = { throw ApiException(413, "file exceeds the 25 MB limit") } }
        val v = chatVm(api, mapOf("content://a" to src("a.txt")))
        v.addAttachments(listOf("content://a")); runCurrent()
        assertEquals(UploadState.Failed("file exceeds the 25 MB limit"), v.drafts.value.single().state)
        v.viewModelScope.cancel()
    }

    @Test
    fun failedSendRestoresTheDrafts() = run {
        val api = FakeApi()
        val v = chatVm(api, mapOf("content://a" to src("a.txt")))
        v.addAttachments(listOf("content://a")); runCurrent()
        api.onSend = { _, _ -> throw IOException("offline") }
        v.send("hi"); runCurrent()
        assertEquals(listOf("a.txt"), v.drafts.value.map { it.name })
        assertTrue(v.notice.value!!.contains("offline"))
        v.viewModelScope.cancel()
    }

    @Test
    fun attachmentCountIsCapped() = run {
        val api = FakeApi()
        val files = (1..12).associate { "content://$it" to src("f$it.txt") }
        val v = chatVm(api, files)
        v.addAttachments(files.keys.toList()); runCurrent()
        assertEquals(10, v.drafts.value.size)
        assertTrue(v.notice.value!!.contains("10"))
        v.viewModelScope.cancel()
    }

    @Test
    fun draftsAreDroppedWhenLeavingTheChat() = run {
        val api = FakeApi()
        val v = chatVm(api, mapOf("content://a" to src("a.txt")))
        v.addAttachments(listOf("content://a")); runCurrent()
        v.closeChat(); runCurrent()
        assertEquals(emptyList(), v.drafts.value)
        assertEquals(emptyList(), v.commands.value)
        v.viewModelScope.cancel()
    }

    @Test
    fun commandsAreLoadedWhenAChatOpens() = run {
        val api = FakeApi().also { it.commandList = listOf("compact", "review") }
        val v = chatVm(api, emptyMap())
        assertEquals(listOf("compact", "review"), v.commands.value)
        v.viewModelScope.cancel()
    }

    @Test
    fun thumbnailsAreFetchedOnceThenCached() = run {
        val api = FakeApi().also { it.attachmentBytes = byteArrayOf(1, 2) }
        val v = chatVm(api, emptyMap())
        val att = Attachment("a1", "p.png", "image")
        assertEquals(listOf<Byte>(1, 2), v.loadAttachment(att)!!.toList())
        v.loadAttachment(att)
        assertEquals(1, api.calls.count { it == "download-att:a1" })
        v.viewModelScope.cancel()
    }

    @Test
    fun modelAndEffortChangesReachTheApi() = run {
        val api = FakeApi()
        val v = chatVm(api, emptyMap())
        v.setEffort("max"); v.setModel("opus"); runCurrent()
        assertTrue("update:s1:null:null:null:effort=max" in api.calls, api.calls.toString())
        assertTrue("update:s1:null:null:opus" in api.calls, api.calls.toString())
        v.viewModelScope.cancel()
    }

    // ---- app updates ----

    private class FakeInstaller(var result: InstallResult = InstallResult.Started) : ApkInstaller {
        val installed = mutableListOf<java.io.File>()
        override fun install(apk: java.io.File): InstallResult {
            installed += apk
            return result
        }
    }

    private fun release(code: Int, name: String = "1.$code") = Release(code, name, "a".repeat(64), 3, "notes for $name")

    private fun TestScope.updVm(api: FakeApi, dir: java.io.File, installer: ApkInstaller, code: Int = 2): AppViewModel =
        AppViewModel(
            FakeSettings("http://h:1", "tony", "t"), { api }, 4_000,
            version = AppVersion(code, "1.1.0"), updateDir = dir, installer = installer,
        ).also { runCurrent() }

    private fun tmpDir() = java.nio.file.Files.createTempDirectory("upd").toFile()

    @Test
    fun newerReleaseIsOfferedAfterLogin() = run {
        val api = FakeApi().also { it.onRelease = { release(3) } }
        val v = updVm(api, tmpDir(), FakeInstaller())
        assertEquals(UpdateState.Available(release(3)), v.update.value)
        assertTrue(v.updateDialog.value)
        v.dismissUpdateDialog()
        assertEquals(false, v.updateDialog.value)
        assertIs<UpdateState.Available>(v.update.value, "still offered as a banner")
        v.showUpdateDialog()
        assertTrue(v.updateDialog.value)
        v.viewModelScope.cancel()
    }

    @Test
    fun noPromptWhenCurrentOrAheadOrNothingPublished() = run {
        for (rel in listOf(release(2), release(1), null)) {
            val api = FakeApi().also { it.onRelease = { rel } }
            val v = updVm(api, tmpDir(), FakeInstaller())
            assertEquals(UpdateState.Idle, v.update.value)
            assertEquals(false, v.updateDialog.value)
            assertNull(v.notice.value, "the automatic check is silent")
            v.checkForUpdate(manual = true); runCurrent()
            assertEquals("已是最新版本 (1.1.0)", v.notice.value)
            v.viewModelScope.cancel()
        }
    }

    @Test
    fun checkFailuresAreSilentUnlessAskedFor() = run {
        val api = FakeApi().also { it.onRelease = { throw IOException("offline") } }
        val v = updVm(api, tmpDir(), FakeInstaller())
        assertNull(v.notice.value)
        assertEquals(UpdateState.Idle, v.update.value)
        v.checkForUpdate(manual = true); runCurrent()
        assertTrue(v.notice.value!!.contains("offline"))
        v.viewModelScope.cancel()
    }

    @Test
    fun updateIsDisabledWithoutAnInstaller() = run {
        val api = FakeApi().also { it.onRelease = { release(3) } }
        val v = AppViewModel(FakeSettings("http://h:1", "tony", "t"), { api }, 4_000).also { runCurrent() }
        assertEquals(UpdateState.Idle, v.update.value)
        assertTrue("latest-release" !in api.calls)
        v.viewModelScope.cancel()
    }

    @Test
    fun downloadsVerifiesThenHandsOverToTheInstaller() = run {
        val api = FakeApi().also { it.onRelease = { release(3) } }
        val dir = tmpDir()
        val installer = FakeInstaller()
        val v = updVm(api, dir, installer)
        v.startUpdate(); runCurrent()
        val ready = assertIs<UpdateState.Ready>(v.update.value)
        assertEquals(listOf(ready.file), installer.installed)
        assertEquals("apk", ready.file.readText())
        assertTrue(ready.file.parentFile == dir)
        v.viewModelScope.cancel()
    }

    @Test
    fun progressIsReportedWhileDownloading() = run {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val api = FakeApi().also {
            it.onRelease = { release(3) }
            it.onDownload = { _, f, progress -> progress(1, 3); gate.await(); f.writeText("apk") }
        }
        val v = updVm(api, tmpDir(), FakeInstaller())
        v.startUpdate(); runCurrent()
        assertEquals(1L, (v.update.value as UpdateState.Downloading).done)
        v.dismissUpdateDialog() // download keeps running in the background
        gate.complete(Unit); runCurrent()
        assertIs<UpdateState.Ready>(v.update.value)
        v.viewModelScope.cancel()
    }

    @Test
    fun checksumFailureOffersARetryAndInstallsNothing() = run {
        var attempts = 0
        val api = FakeApi().also {
            it.onRelease = { release(3) }
            it.onDownload = { _, f, _ -> if (attempts++ == 0) throw ChecksumException("下载文件校验失败,请重试") else f.writeText("apk") }
        }
        val installer = FakeInstaller()
        val v = updVm(api, tmpDir(), installer)
        v.startUpdate(); runCurrent()
        val failed = assertIs<UpdateState.Failed>(v.update.value)
        assertTrue(failed.message.contains("校验"))
        assertTrue(installer.installed.isEmpty())

        v.startUpdate(); runCurrent() // retry from the failed state
        assertIs<UpdateState.Ready>(v.update.value)
        assertEquals(1, installer.installed.size)
        v.viewModelScope.cancel()
    }

    @Test
    fun installNeedsTheUnknownSourcesPermissionFirst() = run {
        val api = FakeApi().also { it.onRelease = { release(3) } }
        val installer = FakeInstaller(InstallResult.NeedsPermission)
        val v = updVm(api, tmpDir(), installer)
        v.startUpdate(); runCurrent()
        assertIs<UpdateState.NeedsPermission>(v.update.value)

        installer.result = InstallResult.Started // the user allowed it in settings
        v.startUpdate(); runCurrent() // "install" again reuses the downloaded file
        assertIs<UpdateState.Ready>(v.update.value)
        assertEquals(2, installer.installed.size)
        assertEquals(1, api.calls.count { it.startsWith("download-release") }, "no second download")
        v.viewModelScope.cancel()
    }

    @Test
    fun staleDownloadsAreClearedBeforeANewOne() = run {
        val api = FakeApi().also { it.onRelease = { release(3) } }
        val dir = tmpDir().also { java.io.File(it, "cc-harness-1.0-1.apk").writeText("old") }
        val v = updVm(api, dir, FakeInstaller())
        v.startUpdate(); runCurrent()
        assertEquals(1, dir.listFiles()!!.size)
        v.viewModelScope.cancel()
    }

    @Test
    fun takingOverALocalSessionImportsOpensAndSyncsIt() = run {
        val api = FakeApi().also { it.localSessions = listOf(LocalSession("loc-1", "整理 README", "/work/p"), LocalSession("loc-2", "other", "/work/q")) }
        val v = vm(FakeSettings("http://h:1", "tony", "t"), api)
        v.openLocalImport()
        assertEquals(true, v.localImport.value?.loading)
        runCurrent()
        assertEquals(listOf("loc-1", "loc-2"), v.localImport.value?.sessions?.map { it.id })

        v.importLocal("loc-1"); runCurrent()
        assertNull(v.localImport.value)
        assertEquals(Screen.Chat("loc-1"), v.screen.value)
        assertTrue("import:loc-1:false" in api.calls, api.calls.toString())
        assertTrue("sync:loc-1" in api.calls, "opening a chat folds in terminal changes first: ${api.calls}")
        v.viewModelScope.cancel()
    }

    @Test
    fun aSessionInUseElsewhereNeedsConfirmationBeforeForcing() = run {
        val api = FakeApi().also {
            it.localSessions = listOf(LocalSession("busy", "b", "/work", active = true))
            it.onImportLocal = { id, force -> if (!force) throw ApiException(409, "modified moments ago") else SessionInfo(id = id, started = true) }
        }
        val v = vm(FakeSettings("http://h:1", "tony", "t"), api)
        v.openLocalImport(); runCurrent()
        v.importLocal("busy"); runCurrent()
        assertEquals("busy", v.localImport.value?.confirmForce)
        assertEquals(Screen.Sessions, v.screen.value)

        v.cancelForce()
        assertNull(v.localImport.value?.confirmForce)

        v.importLocal("busy", force = true); runCurrent()
        assertEquals(Screen.Chat("busy"), v.screen.value)
        assertTrue("import:busy:true" in api.calls)
        v.viewModelScope.cancel()
    }

    @Test
    fun importFailureStaysInTheDialog() = run {
        val api = FakeApi().also {
            it.localSessions = listOf(LocalSession("x", "x", "/etc"))
            it.onImportLocal = { _, _ -> throw ApiException(400, "outside the allowed roots") }
        }
        val v = vm(FakeSettings("http://h:1", "tony", "t"), api)
        v.openLocalImport(); runCurrent()
        v.importLocal("x"); runCurrent()
        assertEquals("outside the allowed roots", v.localImport.value?.error)
        assertNull(v.localImport.value?.importing)
        v.closeLocalImport()
        assertNull(v.localImport.value)
        v.viewModelScope.cancel()
    }

    @Test
    fun usageIsLoadedOnEnteringTheListAndRefreshedAboutEveryMinute() = run {
        val api = FakeApi().also { it.onUsage = { Usage("max", listOf(UsageLimit("session", "当前会话", 12.0))) } }
        val v = vm(FakeSettings("http://h:1", "tony", "t"), api)
        assertEquals(12.0, v.usage.value?.limits?.single()?.percent)
        assertEquals(1, api.calls.count { it == "usage" })
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, api.calls.count { it == "usage" }, "not on every poll")
        advanceTimeBy(40_000); runCurrent()
        assertEquals(2, api.calls.count { it == "usage" })

        api.onUsage = { throw ApiException(503, "claude login has expired") }
        advanceTimeBy(61_000); runCurrent()
        assertEquals("claude login has expired", v.usageError.value)
        assertEquals(12.0, v.usage.value?.limits?.single()?.percent, "the last good figures stay visible")
        v.viewModelScope.cancel()
    }

    // ---- terminals ----

    @Test
    fun terminalsListCreateOpenAndLeave() = run {
        val api = FakeApi().also { it.onMe = { com.cchharness.core.Me("tony", terminal = true) } }
        api.terminals += com.cchharness.core.TerminalInfo("cch00000001", "old", "shell", "/work")
        val v = vm(FakeSettings("http://h:1", "tony", "tok"), api)
        assertTrue(v.me.value!!.terminal)

        v.openTerminals()
        runCurrent()
        assertEquals(Screen.Terminals, v.screen.value)
        assertEquals(listOf("cch00000001"), v.terminals.value.map { it.id })

        v.createTerminal(com.cchharness.core.CreateTerminal(title = "build", cwd = "/work"))
        runCurrent()
        assertEquals(Screen.Terminal("cch00000002"), v.screen.value)
        assertEquals("build", v.terminal.value!!.info.title)
        assertTrue(api.calls.any { it.startsWith("term-stream:cch00000002") })

        v.closeTerminalScreen() // leaving keeps the terminal running on the server
        runCurrent()
        assertEquals(Screen.Terminals, v.screen.value)
        assertNull(v.terminal.value)
        assertTrue(api.calls.none { it.startsWith("term-delete") })

        v.openTerminal("cch00000001")
        runCurrent()
        assertEquals(Screen.Terminal("cch00000001"), v.screen.value)
        v.killTerminal("cch00000001")
        runCurrent()
        assertEquals(Screen.Terminals, v.screen.value)
        assertEquals(listOf("cch00000002"), v.terminals.value.map { it.id })
        assertTrue("term-delete:cch00000001" in api.calls)
        v.viewModelScope.cancel()
    }

    @Test
    fun terminalErrorsAreShownNotThrown() = run {
        val api = FakeApi().also { it.onMe = { com.cchharness.core.Me("tony", terminal = true) } }
        api.onCreateTerminal = { throw ApiException(400, "at most 8 terminals per user: close one first") }
        val v = vm(FakeSettings("http://h:1", "tony", "tok"), api)
        v.openTerminals()
        v.createTerminal(com.cchharness.core.CreateTerminal())
        runCurrent()
        assertEquals(Screen.Terminals, v.screen.value)
        assertTrue(v.terminalError.value!!.contains("at most 8"))
        v.viewModelScope.cancel()
    }

    @Test
    fun takeOverResumesTheChatSessionInTheRealCli() = run {
        val api = FakeApi().also {
            it.onMe = { com.cchharness.core.Me("tony", terminal = true) }
            it.sessions += info("sess-1", "my chat")
        }
        val v = vm(FakeSettings("http://h:1", "tony", "tok"), api)
        v.open("sess-1")
        runCurrent()
        assertEquals(Screen.Chat("sess-1"), v.screen.value)

        v.takeOverInTerminal()
        runCurrent()
        assertTrue("term-create:claude:sess-1:" in api.calls)
        assertIs<Screen.Terminal>(v.screen.value)
        assertNull(v.chat.value)
        v.viewModelScope.cancel()
    }
}
