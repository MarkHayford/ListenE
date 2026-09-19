package com.c0d3c.listene

import android.content.Context

// 聊天草稿存储：按工作区（chatKey，未建工作区时为 "new"）分开保存输入框草稿，
// 切换会话互不串扰，进程被杀后也能恢复。
internal object AgentChatDraftStore {
    private const val PREFS = "agent_chat_drafts"

    fun get(ctx: Context, key: String): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "").orEmpty()

    fun set(ctx: Context, key: String, text: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (text.isBlank()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, text).apply()
    }
}
