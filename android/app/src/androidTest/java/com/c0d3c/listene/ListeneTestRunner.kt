package com.c0d3c.listene

import android.os.Build
import androidx.test.runner.AndroidJUnitRunner

/**
 * 自定义仪器测试 Runner：在测试开始前预授运行时权限。
 *
 * MainActivity 在 Android 13+ 启动时会申请 POST_NOTIFICATIONS，弹出的系统权限框会盖在
 * Compose UI 之上，导致基于 createAndroidComposeRule&lt;MainActivity&gt;() 的 E2E 测试在查询节点时
 * 报 NoComposeHierarchiesFound。提前授权可避免权限框出现，让 Activity 的 Compose 树正常附着。
 */
class ListeneTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        grantRuntimePermissions()
        // 旧渲染器退役 #5：本地固定题型引擎已拆除，E2E 一律按生产默认跑（微元生成+渲染开关保持开），
        // 不再钉死为关——否则测试走的是线上不存在的路径。
        super.onStart()
    }

    private fun grantRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val pkg = targetContext.packageName
        val permissions = buildList {
            add("android.permission.RECORD_AUDIO")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("android.permission.POST_NOTIFICATIONS")
            }
        }
        permissions.forEach { permission ->
            runCatching { uiAutomation.grantRuntimePermission(pkg, permission) }
        }
    }
}
