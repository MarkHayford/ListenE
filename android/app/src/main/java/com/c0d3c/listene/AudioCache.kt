package com.c0d3c.listene

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// 远程音频本地磁盘缓存：按 URL 哈希落盘到 cacheDir/audio-cache，命中即复用本地文件，
// 避免每次播放都重新从服务器下载（听力整段/逐句、TTS 重听等都受益）。失败时回退原 URL（退化为在线流式播放）。
object AudioCache {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // 缓存总量上限，超出按最近最少使用（最旧 lastModified）淘汰。
    private const val MAX_BYTES = 200L * 1024 * 1024

    private fun dir(ctx: Context): File =
        File(ctx.applicationContext.cacheDir, "audio-cache").apply { if (!exists()) mkdirs() }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun extOf(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = if (clean.contains('.')) clean.substringAfterLast('.') else ""
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ".${ext.lowercase()}" else ".audio"
    }

    fun cachedFile(ctx: Context, url: String): File = File(dir(ctx), sha1(url) + extOf(url))

    // 已是本地路径直接返回；远程地址返回缓存文件路径（必要时下载一次）。下载失败回退原 URL。
    suspend fun localPath(ctx: Context, url: String?): String = withContext(Dispatchers.IO) {
        val u = url?.trim().orEmpty()
        if (u.isBlank()) return@withContext ""
        if (u.startsWith("/") || u.startsWith("file://")) return@withContext u
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return@withContext u
        val file = cachedFile(ctx, u)
        if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file.absolutePath
        }
        val tmp = File(file.absolutePath + ".tmp")
        val ok = runCatching {
            val req = Request.Builder().url(u).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                tmp.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
                if (tmp.length() > 0) tmp.renameTo(file) else false
            }
        }.getOrDefault(false)
        if (ok) {
            trim(ctx)
            file.absolutePath
        } else {
            runCatching { tmp.delete() }
            u
        }
    }

    private fun trim(ctx: Context) {
        runCatching {
            val files = dir(ctx).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_BYTES) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        }
    }
}
