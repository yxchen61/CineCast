package com.cinecast.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cinecast.app.ui.viewmodel.MainViewModel
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSnifferScreen(
    viewModel: MainViewModel,
    initialUrl: String,
    initialTitle: String,
    onBack: () -> Unit,
    onPlaySniffedUrl: (title: String, url: String) -> Unit
) {
    val parses by viewModel.parses.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var currentUrl by remember { mutableStateOf(initialUrl.ifBlank { "https://m.bilibili.com/" }) }
    var urlInput by remember { mutableStateOf(currentUrl) }
    var pageTitle by remember { mutableStateOf(initialTitle.ifBlank { "网页嗅探" }) }
    val sniffedUrls = remember { mutableStateListOf<String>() }
    var showSniffedSheet by remember { mutableStateOf(false) }
    var showParseMenu by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val adHosts = remember(settings.adBlockHosts) { settings.adBlockHosts.toSet() }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        placeholder = { Text("输入任意视频网站网址") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            val fixed = if (urlInput.startsWith("http")) urlInput else "https://$urlInput"
                            currentUrl = fixed
                            webViewRef?.loadUrl(fixed)
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Box {
                        IconButton(onClick = { showParseMenu = true }) {
                            Icon(Icons.Rounded.AutoFixHigh, contentDescription = "解析接口")
                        }
                        DropdownMenu(expanded = showParseMenu, onDismissRequest = { showParseMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("使用解析接口打开当前网页", fontWeight = FontWeight.SemiBold) },
                                onClick = {}
                            )
                            HorizontalDivider()
                            parses.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name) },
                                    onClick = {
                                        showParseMenu = false
                                        val parseTarget = "${p.url}${Uri.encode(currentUrl)}"
                                        webViewRef?.loadUrl(parseTarget)
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                }
            }
        },
        floatingActionButton = {
            if (sniffedUrls.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showSniffedSheet = true },
                    icon = { Icon(Icons.Rounded.PlayCircle, contentDescription = null) },
                    text = { Text("已嗅探 ${sniffedUrls.size} 条高清流") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { innerPadding ->
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    this.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = viewModel.settings.value.userAgent
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) pageTitle = title
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!url.isNullOrBlank()) {
                                currentUrl = url
                                urlInput = url
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString().orEmpty()
                            val host = request?.url?.host.orEmpty()
                            if (host in adHosts) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }
                            if (isVideoStreamUrl(reqUrl)) {
                                view?.post {
                                    if (reqUrl !in sniffedUrls) {
                                        sniffedUrls.add(0, reqUrl)
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    loadUrl(currentUrl)
                    webViewRef = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }

    if (showSniffedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSniffedSheet = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("嗅探到的视频流 (${sniffedUrls.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { sniffedUrls.clear(); showSniffedSheet = false }) {
                        Text("清空")
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sniffedUrls) { stream ->
                        Surface(
                            onClick = {
                                showSniffedSheet = false
                                onPlaySniffedUrl(pageTitle, stream)
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Hd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = when {
                                            stream.contains(".m3u8", true) -> "HLS 高清流 (.m3u8)"
                                            stream.contains(".mp4", true) -> "MP4 视频流"
                                            stream.contains(".mpd", true) -> "DASH 高清流"
                                            else -> "媒体流"
                                        },
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = stream,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "播放")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isVideoStreamUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".ts")) {
        return false
    }
    return lower.contains(".m3u8") ||
        lower.contains(".mp4") ||
        lower.contains(".flv") ||
        lower.contains(".mpd") ||
        lower.contains("/hls/") ||
        lower.contains("mime=video")
}
