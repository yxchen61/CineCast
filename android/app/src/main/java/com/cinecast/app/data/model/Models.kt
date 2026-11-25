package com.cinecast.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceType(val displayName: String, val badge: String) {
    MACCMS_JSON("苹果CMS JSON接口 (Type 1)", "JSON"),
    MACCMS_XML("苹果CMS XML接口 (Type 0)", "XML"),
    T4_API("TVBox T4远程接口 (Type 4)", "T4"),
    WEB_SNIFFER("网页嗅探视频站", "网页嗅探"),
    TVBOX_SPIDER("TVBox 爬虫站点 (Type 3)", "Spider")
}

@Serializable
data class VideoSource(
    val id: String,
    val name: String,
    val type: SourceType = SourceType.MACCMS_JSON,
    val apiUrl: String,
    val searchable: Boolean = true,
    val enabled: Boolean = true,
    val group: String = "默认",
    val categories: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val ext: String = "",
    val subscriptionUrl: String = "",
    val latencyMs: Long? = null
)

@Serializable
data class SubscriptionConfig(
    val id: String,
    val name: String,
    val url: String,
    val lastSyncedAt: Long = 0L,
    val siteCount: Int = 0,
    val liveCount: Int = 0,
    val parseCount: Int = 0
)

@Serializable
data class ParseInterface(
    val name: String,
    val type: Int = 0,
    val url: String
)

@Serializable
data class LiveSource(
    val id: String,
    val name: String,
    val url: String,
    val epg: String = ""
)

@Serializable
data class LiveChannel(
    val group: String,
    val name: String,
    val urls: List<String>,
    val logo: String = ""
)

@Serializable
data class VodCategory(
    val id: String,
    val name: String,
    val pid: String = "0",
    val childIds: List<String> = emptyList()
)

@Serializable
data class VodItem(
    val id: String,
    val name: String,
    val pic: String = "",
    val remarks: String = "",
    val year: String = "",
    val area: String = "",
    val typeName: String = "",
    val sourceId: String = "",
    val sourceName: String = "",
    val score: String = ""
)

@Serializable
data class VodEpisode(
    val name: String,
    val url: String
)

@Serializable
data class VodPlaySource(
    val name: String,
    val episodes: List<VodEpisode>
)

@Serializable
data class VodDetail(
    val id: String,
    val name: String,
    val pic: String = "",
    val remarks: String = "",
    val year: String = "",
    val area: String = "",
    val actor: String = "",
    val director: String = "",
    val content: String = "",
    val typeName: String = "",
    val sourceId: String = "",
    val sourceName: String = "",
    val playSources: List<VodPlaySource> = emptyList()
)

@Serializable
data class WatchHistoryItem(
    val vodId: String,
    val sourceId: String,
    val sourceName: String,
    val name: String,
    val pic: String = "",
    val episodeIndex: Int = 0,
    val episodeName: String = "",
    val playSourceIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class FavoriteItem(
    val vodId: String,
    val sourceId: String,
    val sourceName: String,
    val name: String,
    val pic: String = "",
    val remarks: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

@Serializable
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式")
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val autoNextEpisode: Boolean = true,
    val preferM3u8Line: Boolean = true,
    val defaultSpeed: Float = 1.0f,
    val userAgent: String = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
    val adBlockHosts: List<String> = listOf(
        "mimg.0c1q0l.cn", "www.googletagmanager.com", "www.google-analytics.com",
        "mc.usihnbcq.cn", "mg.g1mm3d.cn", "mscs.svaeuzh.cn", "cnzz.hhttm.top",
        "tp.vinuxhome.com", "cnzz.mmstat.com", "www.baihuillq.com", "s23.cnzz.com",
        "z3.cnzz.com", "c.cnzz.com", "stj.v1vo.top", "z12.cnzz.com", "img.mosflower.cn",
        "tips.gamevvip.com", "ehwe.yhdtns.com", "xdn.cqqc3.com", "www.jixunkyy.cn",
        "sp.chemacid.cn", "hm.baidu.com", "s9.cnzz.com", "z6.cnzz.com", "um.cavuc.com"
    )
)

@Serializable
data class BackupBundle(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val sources: List<VideoSource> = emptyList(),
    val subscriptions: List<SubscriptionConfig> = emptyList(),
    val liveSources: List<LiveSource> = emptyList(),
    val parses: List<ParseInterface> = emptyList(),
    val settings: AppSettings = AppSettings()
)
