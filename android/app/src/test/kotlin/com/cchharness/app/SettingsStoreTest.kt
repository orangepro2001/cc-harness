package com.cchharness.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = ctx.getSharedPreferences("cc-harness", Context.MODE_PRIVATE)

    @Test
    fun legacySingleServerBecomesTheActiveHost() {
        prefs.edit().putString("baseUrl", "http://192.168.1.164:8765").putString("user", "tony").putString("token", "tok").commit()
        val s = SharedPrefsSettings(ctx)
        val h = s.hosts.single()
        assertEquals("http://192.168.1.164:8765", h.url)
        assertEquals("192.168.1.164:8765", h.name)
        assertEquals("tok", h.token)
        assertEquals(h.id, s.activeHostId)
        assertTrue("baseUrl" !in prefs.all && "token" !in prefs.all)
    }

    @Test
    fun migrationRunsOnlyOnce() {
        prefs.edit().putString("baseUrl", "http://a:1").putString("token", "t").commit()
        SharedPrefsSettings(ctx).hosts = emptyList()
        prefs.edit().putString("baseUrl", "http://stale:1").commit()
        assertTrue(SharedPrefsSettings(ctx).hosts.isEmpty())
    }

    @Test
    fun freshInstallHasNoHosts() {
        assertTrue(SharedPrefsSettings(ctx).hosts.isEmpty())
    }

    @Test
    fun hostsRoundTrip() {
        val s = SharedPrefsSettings(ctx)
        val list = listOf(Host("1", "A \"quoted\"", "http://a:1", "tony", "t1"), Host("2", "B", "https://b", "", ""))
        s.hosts = list
        s.activeHostId = "2"
        val again = SharedPrefsSettings(ctx)
        assertEquals(list, again.hosts)
        assertEquals("2", again.activeHostId)
    }
}
