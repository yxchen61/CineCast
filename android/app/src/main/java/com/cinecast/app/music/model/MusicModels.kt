package com.cinecast.app.music.model

import kotlinx.serialization.Serializable

/**
 * 独立音乐模块数据模型 —— 与影视源 / 直播 / 小说阅读模块 100% 物理隔离
 */
@Serializable
enum class MusicSourceType(val displayName: String, val badge: String) {
    METING_API("Meting 开源音乐接口", "Meting"),
    GDSTUDIO_API("GDStudio 聚合音乐 API", "GD API"),
    AUDIUS_API("Audius 开源独立音乐", "Audius"),
    RADIO_BROWSER("全球开源在线音乐电台", "电台"),
    M3U_AUDIO("开源 M3U 音频歌单/电台", "M3U")
}

@Serializable
data class MusicChartCategory(
    val title: String,
    val idOrQuery: String
)

@Serializable
data class MusicSource(
    val id: String,
    val name: String,
    val baseUrl: String,
    val sourceType: MusicSourceType = MusicSourceType.METING_API,
    val platform: String = "netease", // netease / tencent / joox / kuwo / cn / global
    val group: String = "开源音乐源",
    val enabled: Boolean = true,
    val searchable: Boolean = true,
    val charts: List<MusicChartCategory> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val comment: String = "",
    val subscriptionUrl: String = "",
    val latencyMs: Long? = null
)

@Serializable
data class MusicSubscription(
    val id: String,
    val name: String,
    val url: String,
    val repoHint: String = "",
    val lastSyncedAt: Long = 0L,
    val importedCount: Int = 0
)

@Serializable
data class MusicTrack(
    val id: String,
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val artist: String = "未知歌手",
    val album: String = "",
    val coverUrl: String = "",
    val streamUrl: String = "", // Direct URL or lazily resolved via MusicSourceEngine
    val lyricUrl: String = "",
    val rawLyric: String = "",
    val durationMs: Long = 0L,
    val platform: String = "",
    val picId: String = "",
    val urlId: String = "",
    val lyricId: String = "",
    val isLiveRadio: Boolean = false
) {
    val uniqueKey: String get() = "${sourceId}::${id}"
}

@Serializable
data class LrcLine(
    val timeMs: Long,
    val text: String
)

@Serializable
data class MusicPlaylist(
    val id: String,
    val name: String,
    val tracks: List<MusicTrack> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
enum class RepeatMode(val label: String) {
    LOOP_ALL("列表循环"),
    LOOP_ONE("单曲循环"),
    SHUFFLE("随机播放")
}

@Serializable
data class MusicSettings(
    val preferHighBitrate: Boolean = true, // 320kbps vs 192kbps
    val repeatMode: RepeatMode = RepeatMode.LOOP_ALL
)

@Serializable
data class MusicBackupBundle(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val musicSources: List<MusicSource> = emptyList(),
    val musicSubscriptions: List<MusicSubscription> = emptyList(),
    val favorites: List<MusicTrack> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val settings: MusicSettings = MusicSettings()
)
