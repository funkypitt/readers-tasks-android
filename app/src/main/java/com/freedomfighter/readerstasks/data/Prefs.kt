package com.freedomfighter.readerstasks.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The inks, the phone's choice between them, then the Plume palettes (see [Palettes]). */
enum class ThemeMode { DARK, LIGHT, SYSTEM, LAVANDE, SAUGE, NORDIQUE, TERRACOTTA, COQUETTE, MILITANTE, TORRIDE, NATURE }
enum class FontChoice { SERIF, SANS, MONO }
enum class TextSize { SMALL, MEDIUM, LARGE }
enum class Align { LEFT, CENTER }

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val font: FontChoice = FontChoice.SANS,
    val textSize: TextSize = TextSize.MEDIUM,
    val align: Align = Align.LEFT,
    val haptics: Boolean = true,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = ""
) {
    val hasAccount: Boolean get() = serverUrl.isNotBlank() && username.isNotBlank()
}

/** Look and account. The account password stays in app-private storage, like the desktop client. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = read() }

    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    private fun read() = Settings(
        theme = enumOr(sp.getString("theme", null), ThemeMode.DARK),
        font = enumOr(sp.getString("font", null), FontChoice.SANS),
        textSize = enumOr(sp.getString("text_size", null), TextSize.MEDIUM),
        align = enumOr(sp.getString("align", null), Align.LEFT),
        haptics = sp.getBoolean("haptics", true),
        serverUrl = sp.getString("server_url", "") ?: "",
        username = sp.getString("username", "") ?: "",
        password = sp.getString("password", "") ?: ""
    )

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    fun setTheme(m: ThemeMode) = sp.edit().putString("theme", m.name).apply()
    fun setFont(f: FontChoice) = sp.edit().putString("font", f.name).apply()
    fun setTextSize(t: TextSize) = sp.edit().putString("text_size", t.name).apply()
    fun setAlign(a: Align) = sp.edit().putString("align", a.name).apply()
    fun setHaptics(v: Boolean) = sp.edit().putBoolean("haptics", v).apply()
    fun setAccount(url: String, user: String, password: String) =
        sp.edit().putString("server_url", url.trim()).putString("username", user.trim()).putString("password", password).apply()

    /** One tap: the next theme, round and round (the inks first, then the palettes; "follow system" leads back to the first ink). */
    fun nextTheme() = setTheme(Palettes.next(_settings.value.theme))
}
