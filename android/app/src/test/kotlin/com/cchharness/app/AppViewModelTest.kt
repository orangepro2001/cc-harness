package com.cchharness.app

import androidx.lifecycle.viewModelScope
import com.cchharness.core.ApiException
import com.cchharness.core.AuthException
import com.cchharness.core.CreateSession
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
        v.connect("192.168.1.164:8765/", " Tony ", "512557")
        runCurrent()
        assertEquals(Screen.Sessions, v.screen.value)
        assertEquals("http://192.168.1.164:8765", settings.baseUrl)
        assertEquals("tony", settings.user)
        assertEquals("tok-tony", settings.token)
        assertEquals("login:tony:512557", api.calls.first { it.startsWith("login") })
        assertTrue(listOf(settings.baseUrl, settings.user, settings.token).none { "512557" in it })
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
}
