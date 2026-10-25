package com.cchharness.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One cc-harness server the app can talk to. Only the login token is kept, never the password. */
data class Host(
    val id: String,
    val name: String,
    val url: String,
    val user: String = "",
    val token: String = "",
) {
    val loggedIn: Boolean get() = token.isNotBlank()
}

/** Where the saved hosts and preferences live. */
interface SettingsStore {
    var hosts: List<Host>

    /** Id of the host the app opens on start; blank when there is none. */
    var activeHostId: String

    /** Terminal font size in sp. */
    var termFontSp: Int
        get() = 14
        set(_) {}
}

class SharedPrefsSettings(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("cc-harness", Context.MODE_PRIVATE)

    init {
        migrateSingleHost()
    }

    override var hosts: List<Host>
        get() = decode(prefs.getString("hosts", "").orEmpty())
        set(v) = prefs.edit().putString("hosts", encode(v)).apply()

    override var activeHostId: String
        get() = prefs.getString("activeHostId", "").orEmpty()
        set(v) = prefs.edit().putString("activeHostId", v).apply()

    override var termFontSp: Int
        get() = prefs.getInt("termFontSp", 14)
        set(v) = prefs.edit().putInt("termFontSp", v).apply()

    /** Versions before multi-host kept one server in baseUrl/user/token; carry it over once. */
    private fun migrateSingleHost() {
        if (prefs.contains("hosts")) return
        val url = prefs.getString("baseUrl", "").orEmpty()
        val edit = prefs.edit().remove("baseUrl").remove("user").remove("token")
        if (url.isBlank()) {
            edit.putString("hosts", encode(emptyList())).apply()
            return
        }
        val host = Host(
            id = LEGACY_ID,
            name = defaultHostName(url),
            url = url,
            user = prefs.getString("user", "").orEmpty(),
            token = prefs.getString("token", "").orEmpty(),
        )
        edit.putString("hosts", encode(listOf(host))).putString("activeHostId", host.id).apply()
    }

    companion object {
        const val LEGACY_ID = "default"

        internal fun encode(hosts: List<Host>): String = JSONArray().apply {
            hosts.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("user", it.user).put("token", it.token))
            }
        }.toString()

        internal fun decode(raw: String): List<Host> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Host(o.getString("id"), o.optString("name"), o.getString("url"), o.optString("user"), o.optString("token"))
                }
            } catch (e: org.json.JSONException) {
                emptyList()
            }
        }
    }
}

/** "http://192.168.1.164:8765" -> "192.168.1.164:8765". */
fun defaultHostName(url: String): String = url.substringAfter("://").trimEnd('/').ifBlank { url }
