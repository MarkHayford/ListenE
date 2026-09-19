package com.c0d3c.listene

/**
 * 记录 App 是否处于前台，供后台生成完成时决定是否走通知栏提醒。
 * 由 MainActivity 的 onStart/onStop 驱动。
 */
object AppForegroundTracker {
    @Volatile
    var isForeground: Boolean = false
        private set

    fun onEnterForeground() {
        isForeground = true
    }

    fun onEnterBackground() {
        isForeground = false
    }
}
