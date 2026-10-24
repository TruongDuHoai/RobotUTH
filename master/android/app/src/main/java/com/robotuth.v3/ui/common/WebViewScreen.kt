package com.robotuth.v3.ui.common

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon

private const val TAG = "WebViewScreen"

/** Soft resize for Pano2VR — never throws into the page. */
private const val PANO_RESIZE_JS = """
(function() {
    try {
        var w = window.innerWidth || document.documentElement.clientWidth || 0;
        var h = window.innerHeight || document.documentElement.clientHeight || 0;
        if (w <= 0 || h <= 0) return;

        var meta = document.querySelector('meta[name="viewport"]');
        if (!meta && document.head) {
            meta = document.createElement('meta');
            meta.name = 'viewport';
            document.head.appendChild(meta);
        }
        if (meta) {
            meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
        }

        document.documentElement.style.width = '100%';
        document.documentElement.style.height = '100%';
        document.documentElement.style.margin = '0';
        document.documentElement.style.padding = '0';
        document.documentElement.style.overflow = 'hidden';

        if (document.body) {
            document.body.style.width = '100%';
            document.body.style.height = '100%';
            document.body.style.margin = '0';
            document.body.style.padding = '0';
            document.body.style.overflow = 'hidden';
        }

        var c = document.getElementById('container');
        if (c) {
            c.style.position = 'fixed';
            c.style.top = '0';
            c.style.left = '0';
            c.style.width = '100%';
            c.style.height = '100%';
            c.style.overflow = 'hidden';
        }

        window.dispatchEvent(new Event('resize'));

        if (typeof pano !== 'undefined' && pano) {
            try {
                if (typeof pano.updateViewerSizeNow === 'function') pano.updateViewerSizeNow();
                else if (typeof pano.fd === 'function') pano.fd();
            } catch (e) {}
        }
    } catch (e) {}
})();
"""

private const val WEBGL_CHECK_JS = """
(function() {
    try {
        var canvas = document.createElement('canvas');
        return !!(window.WebGLRenderingContext &&
            (canvas.getContext('webgl') || canvas.getContext('experimental-webgl')));
    } catch (e) { return false; }
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    url: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var loadError by remember(url) { mutableStateOf<String?>(null) }
    var isLoading by remember(url) { mutableStateOf(true) }
    var reloadToken by remember { mutableStateOf(0) }
    val webViewHolder = remember { WebViewHolder() }
    val fullscreenState = remember { FullscreenState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    BackHandler {
        if (fullscreenState.view != null) {
            fullscreenState.callback?.onCustomViewHidden()
            fullscreenState.clear()
        } else {
            val wv = webViewHolder.webView
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                onBack()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        AndroidView(
            factory = { ctx ->
                val container = FrameLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                CookieManager.getInstance().setAcceptCookie(true)

                val wv = WebView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(AndroidColor.BLACK)
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        textZoom = 100
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        allowFileAccess = true
                        allowContentAccess = true
                        loadsImagesAutomatically = true
                        blockNetworkImage = false
                        javaScriptCanOpenWindowsAutomatically = true
                        // Chrome-like UA helps some CDN / WebGL paths
                        userAgentString = userAgentString
                            .replace("; wv", "")
                            .replace("Version/4.0 ", "")
                    }

                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    var pageLoaded = false
                    var urlLoaded = false

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val target = request?.url?.toString().orEmpty()
                            // Keep same-host navigation inside WebView
                            if (target.startsWith("http://") || target.startsWith("https://")) {
                                return false
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: android.graphics.Bitmap?) {
                            isLoading = true
                            loadError = null
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            pageLoaded = true
                            isLoading = false
                            schedulePanoResize(webViewHolder)
                            view?.evaluateJavascript(WEBGL_CHECK_JS.trimIndent()) { result ->
                                if (result == "false") {
                                    loadError =
                                        "Thiết bị không hỗ trợ WebGL. Cập nhật Android System WebView / bật GPU."
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                isLoading = false
                                val desc = error?.description?.toString() ?: "lỗi mạng"
                                loadError = "Không thể tải bản đồ: $desc\nURL: $url"
                                Log.e(TAG, "Main frame error: $desc code=${error?.errorCode}")
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                            message?.let {
                                Log.d(TAG, "${it.message()} -- ${it.sourceId()}:${it.lineNumber()}")
                                if (it.message().contains("WebGL not available", ignoreCase = true)) {
                                    loadError =
                                        "Thiết bị không hỗ trợ WebGL. Cập nhật Android System WebView / bật GPU."
                                }
                            }
                            return true
                        }

                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            if (newProgress >= 100) isLoading = false
                        }

                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            if (fullscreenState.view != null) {
                                callback?.onCustomViewHidden()
                                return
                            }
                            fullscreenState.view = view
                            fullscreenState.callback = callback
                            view?.let { fullscreenView ->
                                container.removeAllViews()
                                container.addView(
                                    fullscreenView,
                                    FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        }

                        override fun onHideCustomView() {
                            fullscreenState.callback?.onCustomViewHidden()
                            fullscreenState.clear()
                            container.removeAllViews()
                            container.addView(this@apply)
                            post { schedulePanoResize(webViewHolder) }
                        }
                    }

                    fun tryLoadUrl() {
                        if (urlLoaded || width <= 0 || height <= 0) return
                        urlLoaded = true
                        Log.i(TAG, "Loading $url size=${width}x$height")
                        loadUrl(url)
                    }

                    viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                        private var lastWidth = 0
                        private var lastHeight = 0

                        override fun onGlobalLayout() {
                            val w = width
                            val h = height
                            if (w <= 0 || h <= 0) return

                            if (!urlLoaded) {
                                tryLoadUrl()
                            }

                            if (pageLoaded && (w != lastWidth || h != lastHeight)) {
                                lastWidth = w
                                lastHeight = h
                                post { schedulePanoResize(webViewHolder) }
                            }
                        }
                    })

                    post { tryLoadUrl() }
                }

                webViewHolder.bind(wv)
                container.addView(wv)
                container.tag = wv
                container
            },
            modifier = Modifier.fillMaxSize(),
            update = { frame ->
                // Reload when user requests retry
                val wv = frame.tag as? WebView ?: return@AndroidView
                if (reloadToken > 0 && loadError != null) {
                    // handled via reload button recreating by token in error UI
                }
                frame.post {
                    if (wv.width > 0 && wv.height > 0) {
                        schedulePanoResize(webViewHolder)
                    }
                }
            }
        )

        if (isLoading && loadError == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color(0xFF03B8BB)
            )
        }

        loadError?.let { error ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E293B).copy(alpha = 0.95f),
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Không thể hiển thị bản đồ 3D",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = error,
                        fontSize = 13.sp,
                        color = Color(0xFFCBD5E1),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            loadError = null
                            isLoading = true
                            reloadToken++
                            webViewHolder.webView?.let { wv ->
                                wv.clearCache(false)
                                wv.loadUrl(url)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF018486))
                    ) {
                        Text("Thử lại")
                    }
                    TextButton(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Open browser failed", e)
                            }
                        }
                    ) {
                        Text("Mở bằng trình duyệt", color = Color(0xFF94A3B8))
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.White.copy(alpha = 0.92f),
            shadowElevation = 4.dp
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF0F172A)
                )
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webViewHolder.webView?.onResume()
                Lifecycle.Event.ON_PAUSE -> webViewHolder.webView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        webViewHolder.webView?.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webViewHolder.destroy()
        }
    }
}

private class WebViewHolder {
    var webView: WebView? = null
        private set
    private var generation = 0

    fun bind(webView: WebView) {
        this.webView = webView
        generation++
    }

    fun destroy() {
        generation++
        webView?.apply {
            stopLoading()
            onPause()
            destroy()
        }
        webView = null
    }

    fun currentGeneration(): Int = generation
}

private class FullscreenState {
    var view: View? = null
    var callback: WebChromeClient.CustomViewCallback? = null

    fun clear() {
        view = null
        callback = null
    }
}

private fun schedulePanoResize(holder: WebViewHolder) {
    val webView = holder.webView ?: return
    if (webView.width <= 0 || webView.height <= 0) return
    val generation = holder.currentGeneration()
    val delays = longArrayOf(0, 200, 600, 1500, 3000)
    delays.forEach { delayMs ->
        webView.postDelayed({
            if (holder.currentGeneration() != generation) return@postDelayed
            val active = holder.webView ?: return@postDelayed
            if (active.width > 0 && active.height > 0) {
                active.evaluateJavascript(PANO_RESIZE_JS.trimIndent(), null)
            }
        }, delayMs)
    }
}
