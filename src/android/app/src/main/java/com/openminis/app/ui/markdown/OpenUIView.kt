package com.openminis.app.ui.markdown

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.ui.theme.ChatColors

enum class OpenUIMode {
    PREVIEW,
    CODE
}

/**
 * Interactive OpenUI Generative UI view.
 * Renders OpenUI Lang programs dynamically via an embedded WebView with support for
 * interactive cards, charts, forms, and tables, with seamless switching to raw code view.
 */
@Composable
fun OpenUIView(
    code: String,
    modifier: Modifier = Modifier,
    initialMode: OpenUIMode = OpenUIMode.PREVIEW,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isDark = ChatColors.isDark

    var mode by remember { mutableStateOf(initialMode) }
    var copied by remember { mutableStateOf(false) }
    var contentHeightPx by remember { mutableFloatStateOf(240f * density.density) }

    if (copied) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }

    val cardBg = if (isDark) Color(0xFF1E1E24) else Color(0xFFF8F9FA)
    val borderColor = if (isDark) Color(0xFF2E2E38) else Color(0xFFE5E7EB)
    val accentColor = Color(0xFF7C3AED) // OpenUI Brand Violet

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .background(cardBg),
    ) {
        // Header Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isDark) Color(0xFF18181C) else Color(0xFFF1F3F5))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Brand Chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentColor),
                )
                Text(
                    text = "OpenUI",
                    style = TextStyle(
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF334155),
                    ),
                )
            }

            // Controls: Mode switch + Copy
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Preview / Code toggle button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isDark) Color(0xFF27272A) else Color(0xFFE2E8F0))
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (mode == OpenUIMode.PREVIEW) accentColor else Color.Transparent)
                            .clickable { mode = OpenUIMode.PREVIEW }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "预览",
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (mode == OpenUIMode.PREVIEW) Color.White else if (isDark) Color(0xFFA1A1AA) else Color(0xFF64748B),
                            ),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (mode == OpenUIMode.CODE) accentColor else Color.Transparent)
                            .clickable { mode = OpenUIMode.CODE }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = "代码",
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (mode == OpenUIMode.CODE) Color.White else if (isDark) Color(0xFFA1A1AA) else Color(0xFF64748B),
                            ),
                        )
                    }
                }

                // Copy button
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("OpenUI Lang", code))
                        copied = true
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "复制 OpenUI 代码",
                        modifier = Modifier.size(15.dp),
                        tint = if (copied) Color(0xFF10B981) else if (isDark) Color(0xFFA1A1AA) else Color(0xFF64748B),
                    )
                }
            }
        }

        // Content: Preview or Code
        if (mode == OpenUIMode.PREVIEW) {
            val heightDp = with(density) { (contentHeightPx / density.density).dp.coerceIn(100.dp, 650.dp) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp)
                    .padding(4.dp),
            ) {
                OpenUIWebViewHost(
                    code = code,
                    isDark = isDark,
                    onHeightChange = { px ->
                        if (px > 0) contentHeightPx = px
                    },
                )
            }
        } else {
            val codeColor = if (isDark) Color(0xFF4EC9B0) else Color(0xFF0F766E)
            val vScroll = rememberScrollState()
            val hScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll)
                    .padding(12.dp),
            ) {
                Text(
                    text = code,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        color = codeColor,
                        lineHeight = 18.sp,
                    ),
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun OpenUIWebViewHost(
    code: String,
    isDark: Boolean,
    onHeightChange: (Float) -> Unit,
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }

    val escapedCode = remember(code) {
        code.replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")
    }

    LaunchedEffect(pageLoaded, escapedCode, isDark) {
        if (pageLoaded) {
            webViewRef?.evaluateJavascript(
                "renderOpenUI(`$escapedCode`, $isDark);",
                null,
            )
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(0x00000000)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    textZoom = 100
                }

                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onHeightChange(h: Float) {
                            post { onHeightChange(h) }
                        }
                    },
                    "AndroidBridge",
                )

                webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?,
                    ): Boolean {
                        return com.openminis.app.ui.webview.WebViewRenderProcess.handle("OpenUIView", detail)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        pageLoaded = true
                        view?.evaluateJavascript(
                            "renderOpenUI(`$escapedCode`, $isDark);",
                            null,
                        )
                    }
                }

                loadUrl("file:///android_asset/openui/openui-render.html")
                webViewRef = this
            }
        },
        update = { wv ->
            webViewRef = wv
        },
        onRelease = { wv ->
            runCatching {
                wv.stopLoading()
                wv.destroy()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
