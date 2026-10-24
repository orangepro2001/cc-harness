package com.cchharness.app

import android.content.Context

/** Where the connection settings live. Only the login token is kept, never the password. */
interface SettingsStore {
    var baseUrl: String
    var user: String
    var token: String

    /** Terminal font size in sp. */
    var termFontSp: Int
        get() = 14
        set(_) {}
}

class SharedPrefsSettings(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("cc-harness", Context.MODE_PRIVATE)

    override var baseUrl: String
        get() = prefs.getString("baseUrl", "").orEmpty()
        set(v) = prefs.edit().putString("baseUrl", v).apply()

    override var user: String
        get() = prefs.getString("user", "").orEmpty()
        set(v) = prefs.edit().putString("user", v).apply()

    override var token: String
        get() = prefs.getString("token", "").orEmpty()
        set(v) = prefs.edit().putString("token", v).apply()

    override var termFontSp: Int
        get() = prefs.getInt("termFontSp", 14)
        set(v) = prefs.edit().putInt("termFontSp", v).apply()
}
