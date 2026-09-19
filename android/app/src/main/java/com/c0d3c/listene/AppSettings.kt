package com.c0d3c.listene

import android.content.Context

/** 用户偏好设置（本地 SharedPreferences） */
object AppSettings {
    private const val PREFS = "listene_settings"
    private const val KEY_NOTIFY_ON_COMPLETE = "notify_on_complete"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_NOTIF_PROMPT_SHOWN = "notif_prompt_shown"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    /** 后台生成（如离开 App 时的 AI 回复）完成/失败后是否发通知提醒。默认开。 */
    fun isNotifyOnCompleteEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFY_ON_COMPLETE, true)
    }

    fun setNotifyOnCompleteEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFY_ON_COMPLETE, enabled)
            .apply()
    }

    /** 通知权限说明弹窗是否已出现过（只提示一次，之后由用户去系统设置管理）。 */
    fun isNotifPromptShown(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIF_PROMPT_SHOWN, false)
    }

    fun setNotifPromptShown(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIF_PROMPT_SHOWN, true)
            .apply()
    }

    /** 外观：跟随系统 / 浅色 / 深色。默认跟随系统。 */
    fun getThemeMode(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    fun setThemeMode(context: Context, mode: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode)
            .apply()
    }
}
