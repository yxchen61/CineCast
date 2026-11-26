package com.cinecast.app.music.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.cinecast.app.data.network.NetworkClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioPlaybackState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null
)

/**
 * 独立音乐 ExoPlayer 音频控制器（与视频播放器完全分离，支持全局迷你条平滑切换与后台续播）
 */
class MusicAudioController(context: Context) {

    private val appContext = context.applicationContext
    private var exoPlayer: ExoPlayer? = null
    private var onTrackEndedCallback: (() -> Unit)? = null
    private var onTrackErrorCallback: ((String) -> Unit)? = null
    private var onTrackReadyCallback: (() -> Unit)? = null
    private var onShortPreviewDetectedCallback: ((Long) -> Unit)? = null
    private var hasSignaledEndedForCurrentItem: Boolean = false
    private var hasSignaledErrorForCurrentItem: Boolean = false
    private var hasSignaledShortPreviewForCurrentItem: Boolean = false
    private var currentIsLiveRadio: Boolean = false

    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()

    @OptIn(UnstableApi::class)
    private fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        val musicHttpClient = NetworkClient.client.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host.lowercase()
                val builder = req.newBuilder()
                if (host.contains("bilivideo") || host.contains("hdslb") ||
                    host.contains("akamaized") || host.contains("bilibili")
                ) {
                    builder.header("Referer", "https://www.bilibili.com/")
                    builder.header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                    )
                }
                chain.proceed(builder.build())
            }
            .build()
        val dataSourceFactory = OkHttpDataSource.Factory(musicHttpClient)
            .setUserAgent(NetworkClient.DEFAULT_UA)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { player ->
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.value = _state.value.copy(isPlaying = isPlaying)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val buffering = playbackState == Player.STATE_BUFFERING
                        val dur = player.duration.takeIf { it > 0 } ?: _state.value.durationMs
                        _state.value = _state.value.copy(
                            isBuffering = buffering,
                            durationMs = dur,
                            error = if (playbackState == Player.STATE_READY) null else _state.value.error
                        )
                        if (playbackState == Player.STATE_READY) {
                            if (!currentIsLiveRadio && !hasSignaledShortPreviewForCurrentItem &&
                                dur in 1_000L..SHORT_PREVIEW_THRESHOLD_MS
                            ) {
                                hasSignaledShortPreviewForCurrentItem = true
                                player.pause()
                                onShortPreviewDetectedCallback?.invoke(dur)
                            } else {
                                onTrackReadyCallback?.invoke()
                            }
                        }
                        if (playbackState == Player.STATE_ENDED && !hasSignaledEndedForCurrentItem) {
                            hasSignaledEndedForCurrentItem = true
                            onTrackEndedCallback?.invoke()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        _state.value = _state.value.copy(
                            isPlaying = false,
                            isBuffering = false,
                            error = "音频流播放失败：${error.errorCodeName}"
                        )
                        if (!hasSignaledErrorForCurrentItem) {
                            hasSignaledErrorForCurrentItem = true
                            onTrackErrorCallback?.invoke(error.errorCodeName)
                        }
                    }
                })
                exoPlayer = player
            }
    }

    fun setOnTrackEnded(callback: () -> Unit) {
        onTrackEndedCallback = callback
    }

    fun setOnTrackError(callback: (String) -> Unit) {
        onTrackErrorCallback = callback
    }

    fun setOnTrackReady(callback: () -> Unit) {
        onTrackReadyCallback = callback
    }

    fun setOnShortPreviewDetected(callback: (Long) -> Unit) {
        onShortPreviewDetectedCallback = callback
    }

    fun playUrl(url: String, isLiveRadio: Boolean = false) {
        val player = ensurePlayer()
        hasSignaledEndedForCurrentItem = false
        hasSignaledErrorForCurrentItem = false
        hasSignaledShortPreviewForCurrentItem = false
        currentIsLiveRadio = isLiveRadio
        _state.value = AudioPlaybackState(isBuffering = true)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun seekTo(positionMs: Long) {
        val player = exoPlayer ?: return
        player.seekTo(positionMs.coerceAtLeast(0L))
        _state.value = _state.value.copy(positionMs = positionMs.coerceAtLeast(0L))
    }

    fun pollProgress() {
        val player = exoPlayer ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration.takeIf { it > 0 } ?: _state.value.durationMs
        if (pos != _state.value.positionMs || dur != _state.value.durationMs || player.isPlaying != _state.value.isPlaying) {
            _state.value = _state.value.copy(
                isPlaying = player.isPlaying,
                positionMs = pos,
                durationMs = dur
            )
        }
        // Detect short VIP trial previews even when duration arrives slightly after STATE_READY
        if (!currentIsLiveRadio && !hasSignaledShortPreviewForCurrentItem &&
            player.playbackState == Player.STATE_READY &&
            dur in 1_000L..SHORT_PREVIEW_THRESHOLD_MS
        ) {
            hasSignaledShortPreviewForCurrentItem = true
            player.pause()
            onShortPreviewDetectedCallback?.invoke(dur)
            return
        }
        // Fallback safety net: ensure track completion is never missed even if a state callback was dropped
        if (player.playbackState == Player.STATE_ENDED && !hasSignaledEndedForCurrentItem) {
            hasSignaledEndedForCurrentItem = true
            onTrackEndedCallback?.invoke()
        }
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }

    companion object {
        /** 凡时长 <= 65 秒的非电台音频均视为 VIP 试听片段，触发自动切换完整版音源 */
        const val SHORT_PREVIEW_THRESHOLD_MS = 65_000L
    }
}
