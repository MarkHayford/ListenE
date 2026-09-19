package com.c0d3c.listene

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// 客户端更新检测：进入程序时查后端最新版本，若需要强制更新则由 UI 拦截（更新或退出）。
data class AppUpdateInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val downloadUrl: String,
    val forceUpdate: Boolean,
    val releaseNotes: String
) {
    // 强制更新：后端标记强制 且 线上版本号高于本机。
    val mustUpdate: Boolean get() = forceUpdate && latestVersionCode > BuildConfig.VERSION_CODE
}

object AppUpdateStore {
    // 短超时：离线/后端不可达时快速失败并放行（fail-open，避免把 App 卡死）。
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(ctx: Context): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        runCatching {
            val req = Request.Builder()
                .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/app-version")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("app-version failed: ${resp.code}")
                val o = parseJsonObjectOrNull(resp.body.string()) ?: error("parse failed")
                AppUpdateInfo(
                    latestVersionCode = o["latestVersionCode"]?.jsonPrimitive?.intOrNull ?: 0,
                    latestVersionName = o["latestVersionName"]?.jsonPrimitive?.contentOrNull ?: "",
                    downloadUrl = o["downloadUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                    forceUpdate = o["forceUpdate"]?.jsonPrimitive?.booleanOrNull ?: true,
                    releaseNotes = o["releaseNotes"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        }.getOrNull()
    }
}

// 强制更新拦截页：全屏、不可返回绕过，只有「立即更新」或「退出」。
@Composable
fun AppForceUpdateScreen(info: AppUpdateInfo, onUpdate: () -> Unit, onExit: () -> Unit) {
    BackHandler(enabled = true) { /* 强制更新期间屏蔽返回键，不允许绕过 */ }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.SystemUpdate,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "发现新版本",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (info.latestVersionName.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "v${info.latestVersionName}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (info.releaseNotes.isNotBlank()) info.releaseNotes
            else "为保证功能正常，请更新到最新版本后再继续使用。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp)
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).height(50.dp)) {
            Text("立即更新", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).height(50.dp)) {
            Text("退出")
        }
    }
}
