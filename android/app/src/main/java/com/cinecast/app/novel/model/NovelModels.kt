package com.cinecast.app.novel.model

import kotlinx.serialization.Serializable

/**
 * 独立小说模块数据模型 —— 与影视源 / 直播 / 观影历史完全解耦
 */
@Serializable
enum class NovelSourceType(val displayName: String, val badge: String) {
    WIKISOURCE_API("维基文库名著典藏", "典藏"),
    GUTENDEX_API("古登堡世界名著库", "名著"),
    LEGADO_JSON("Legado JSON API 书源", "API"),
    LEGADO_HTML("Legado HTML 规则书源", "规则")
}

@Serializable
data class NovelRuleSearch(
    val bookList: String = "",
    val name: String = "",
    val author: String = "",
    val bookUrl: String = "",
    val coverUrl: String = "",
    val intro: String = "",
    val kind: String = "",
    val lastChapter: String = "",
    val wordCount: String = ""
)

@Serializable
data class NovelRuleBookInfo(
    val init: String = "",
    val name: String = "",
    val author: String = "",
    val coverUrl: String = "",
    val intro: String = "",
    val kind: String = "",
    val lastChapter: String = "",
    val tocUrl: String = ""
)

@Serializable
data class NovelRuleToc(
    val chapterList: String = "",
    val chapterName: String = "",
    val chapterUrl: String = "",
    val nextTocUrl: String = ""
)

@Serializable
data class NovelRuleContent(
    val content: String = "",
    val nextContentUrl: String = "",
    val replaceRegex: String = ""
)

@Serializable
data class NovelSource(
    val id: String,
    val name: String,
    val baseUrl: String,
    val group: String = "开源书源",
    val sourceType: NovelSourceType = NovelSourceType.LEGADO_HTML,
    val enabled: Boolean = true,
    val searchable: Boolean = true,
    val searchUrl: String = "",
    val exploreUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
    val charset: String = "UTF-8",
    val comment: String = "",
    val subscriptionUrl: String = "",
    val latencyMs: Long? = null,
    val ruleSearch: NovelRuleSearch = NovelRuleSearch(),
    val ruleBookInfo: NovelRuleBookInfo = NovelRuleBookInfo(),
    val ruleToc: NovelRuleToc = NovelRuleToc(),
    val ruleContent: NovelRuleContent = NovelRuleContent()
)

@Serializable
data class NovelSubscription(
    val id: String,
    val name: String,
    val url: String,
    val repoHint: String = "",
    val lastSyncedAt: Long = 0L,
    val importedCount: Int = 0
)

@Serializable
data class NovelExploreCategory(
    val title: String,
    val url: String
)

@Serializable
data class NovelBook(
    val id: String,
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    val intro: String = "",
    val kind: String = "",
    val latestChapter: String = "",
    val wordCount: String = "",
    val bookUrl: String,
    val tocUrl: String = ""
)

@Serializable
data class NovelChapter(
    val index: Int,
    val title: String,
    val url: String,
    val inlineContent: String? = null
)

@Serializable
data class BookshelfItem(
    val bookKey: String, // "${sourceId}::${bookUrl}"
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    val intro: String = "",
    val kind: String = "",
    val latestChapter: String = "",
    val bookUrl: String,
    val tocUrl: String = "",
    val currentChapterIndex: Int = 0,
    val currentChapterTitle: String = "",
    val totalChapters: Int = 0,
    val scrollOffset: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = System.currentTimeMillis()
)

@Serializable
enum class ReaderTheme(
    val label: String,
    val backgroundHex: Long,
    val textHex: Long,
    val secondaryTextHex: Long,
    val surfaceHex: Long,
    val isDark: Boolean
) {
    PAPER("纯净纸白", 0xFFFCFCFA, 0xFF1F1F1F, 0xFF5F6368, 0xFFF1F3F4, false),
    SEPIA("暖黄羊皮", 0xFFF6EFE0, 0xFF3E2F1C, 0xFF7A6855, 0xFFECE2CE, false),
    SAGE("护眼豆沙", 0xFFE5F0E3, 0xFF1F3322, 0xFF526A56, 0xFFD5E6D3, false),
    NIGHT("深邃暗夜", 0xFF121316, 0xFFDADCE0, 0xFF9AA0A6, 0xFF1F2125, true)
}

@Serializable
data class NovelReadingSettings(
    val readerTheme: ReaderTheme = ReaderTheme.PAPER,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.75f,
    val paragraphSpacingDp: Float = 12f,
    val horizontalMarginDp: Float = 20f,
    val useSerifFont: Boolean = false,
    val keepScreenOn: Boolean = true
)

@Serializable
data class NovelBackupBundle(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val novelSources: List<NovelSource> = emptyList(),
    val novelSubscriptions: List<NovelSubscription> = emptyList(),
    val bookshelf: List<BookshelfItem> = emptyList(),
    val readingSettings: NovelReadingSettings = NovelReadingSettings()
)
