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
    private var hasSignaledEndedForCurrentItem: Boolean = false
    private var hasSignaledErrorForCurrentItem: Boolean = false

    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()

    @OptIn(UnstableApi::class)
    private fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        val dataSourceFactory = OkHttpDataSource.Factory(NetworkClient.client)
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
                            onTrackReadyCallback?.invoke()
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

    fun playUrl(url: String) {
        val player = ensurePlayer()
        hasSignaledEndedForCurrentItem = false
        hasSignaledErrorForCurrentItem = false
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
}
