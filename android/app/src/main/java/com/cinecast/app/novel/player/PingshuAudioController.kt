package com.cinecast.app.novel.player

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

data class PingshuPlaybackState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val error: String? = null
)

/**
 * 阅读模块专属「听评书」音频播放器（独立 Media3 ExoPlayer 内核，针对评书人声与长音频优化：
 * 支持锁屏/后台唤醒锁、0.75x~2.0x 倍速、快进30s/快退15s、断点续听与自动连播下一回）
 */
class PingshuAudioController(context: Context) {

    private val appContext = context.applicationContext
    private var exoPlayer: ExoPlayer? = null
    private var onEpisodeEndedCallback: (() -> Unit)? = null
    private var onEpisodeErrorCallback: ((String) -> Unit)? = null
    private var hasSignaledEnded = false
    private var hasSignaledError = false
    private var currentSpeed = 1.0f

    private val _state = MutableStateFlow(PingshuPlaybackState())
    val state: StateFlow<PingshuPlaybackState> = _state.asStateFlow()

    @OptIn(UnstableApi::class)
    private fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        val dataSourceFactory = OkHttpDataSource.Factory(NetworkClient.client)
            .setUserAgent(NetworkClient.DEFAULT_UA)
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { player ->
                player.setPlaybackSpeed(currentSpeed)
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
                        if (playbackState == Player.STATE_ENDED && !hasSignaledEnded) {
                            hasSignaledEnded = true
                            onEpisodeEndedCallback?.invoke()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        _state.value = _state.value.copy(
                            isPlaying = false,
                            isBuffering = false,
                            error = "评书音频流播放失败：${error.errorCodeName}"
                        )
                        if (!hasSignaledError) {
                            hasSignaledError = true
                            onEpisodeErrorCallback?.invoke(error.errorCodeName)
                        }
                    }
                })
                exoPlayer = player
            }
    }

    fun setOnEpisodeEnded(callback: () -> Unit) {
        onEpisodeEndedCallback = callback
    }

    fun setOnEpisodeError(callback: (String) -> Unit) {
        onEpisodeErrorCallback = callback
    }

    fun playUrl(url: String, startPositionMs: Long = 0L) {
        val player = ensurePlayer()
        hasSignaledEnded = false
        hasSignaledError = false
        _state.value = PingshuPlaybackState(
            isBuffering = true,
            positionMs = startPositionMs.coerceAtLeast(0L),
            speed = currentSpeed
        )
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        if (startPositionMs > 1000L) {
            player.seekTo(startPositionMs)
        }
        player.setPlaybackSpeed(currentSpeed)
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
        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, dur)
        player.seekTo(target)
        _state.value = _state.value.copy(positionMs = target)
    }

    fun skipBy(deltaMs: Long) {
        val player = exoPlayer ?: return
        seekTo(player.currentPosition + deltaMs)
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0.75f, 2.0f)
        exoPlayer?.setPlaybackSpeed(currentSpeed)
        _state.value = _state.value.copy(speed = currentSpeed)
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
        if (player.playbackState == Player.STATE_ENDED && !hasSignaledEnded) {
            hasSignaledEnded = true
            onEpisodeEndedCallback?.invoke()
        }
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }
}
