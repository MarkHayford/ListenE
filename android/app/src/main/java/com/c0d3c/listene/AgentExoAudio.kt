package com.c0d3c.listene

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * MediaPlayer→ExoPlayer(media3) 的薄封装：集中 media3 细节，对外保留各播放点原有的
 * prepared / completion / error 回调式用法，使迁移近乎机械替换、且只有这一处需要核对 media3 行为。
 *
 * 约束：必须在主线程创建与调用（ExoPlayer 要求宿主线程带 Looper）。语义对齐 MediaPlayer：
 * - prepare() 对应 prepareAsync()，就绪后回调 onPrepared（每个媒体项只回一次）；
 * - start()/pause()/seekTo()/currentPosition/duration/release() 与原用法一致；
 * - 播放自然结束回调 onCompletion；出错回调 onError。
 */
class AgentExoAudio(context: Context) {
    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    var onPrepared: (() -> Unit)? = null
    var onCompletion: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    private var preparedFired = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> if (!preparedFired) {
                        preparedFired = true
                        onPrepared?.invoke()
                    }
                    Player.STATE_ENDED -> onCompletion?.invoke()
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onError?.invoke()
            }
        })
    }

    fun setDataSource(uri: Uri) {
        preparedFired = false
        player.setMediaItem(MediaItem.fromUri(uri))
    }

    /** 本地绝对路径转 file:// URI；http(s)/content 等按原样解析。 */
    fun setDataSource(path: String) {
        val uri = if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
        setDataSource(uri)
    }

    fun prepare() {
        player.prepare()
    }

    fun start() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(ms: Int) {
        player.seekTo(ms.toLong().coerceAtLeast(0L))
    }

    val currentPosition: Int
        get() = player.currentPosition.coerceAtLeast(0L).toInt()

    val duration: Int
        get() = player.duration.let { if (it == C.TIME_UNSET || it < 0L) 0 else it.toInt() }

    fun release() {
        player.release()
    }
}
