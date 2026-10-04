package com.mobilegh.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mobilegh.R
import com.mobilegh.data.AppLog
import com.mobilegh.data.ImageFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 全局图片查看器状态：任何页面调用 [open] 即可全屏查看，宿主组件 [ImageViewerHost] 放在 AppRoot */
object ImageViewer {
    var url by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set

    fun open(src: String, alt: String = "") {
        if (src.isBlank() || src.startsWith("data:")) return
        url = src
        title = alt.ifBlank { fileName(src) }
    }

    fun close() { url = null }

    fun fileName(src: String): String =
        src.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
            .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .ifBlank { "image" }
}

@Composable
fun ImageViewerHost() {
    val url = ImageViewer.url ?: return
    Dialog(
        onDismissRequest = ImageViewer::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        key(url) { ImageViewerContent(url, ImageViewer.title) }
    }
}

private data class ViewerImage(
    val data: ImageFetch.Result,
    val mime: String?,
    val bitmap: ImageBitmap?,
    val html: String?,
)

private data class ImageLoadState(val image: ViewerImage? = null, val complete: Boolean = false)

private data class ImageSaveRequest(val bytes: ByteArray, val mime: String?, val name: String)

@Composable
private fun ImageViewerContent(url: String, title: String) {
    val ctx = rememberCtx()
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var showBars by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<ImageSaveRequest?>(null) }
    var renderFailed by remember(reload) { mutableStateOf(false) }
    // produceState 的 initialValue 不会在 key 改变时自动重置；重建 State，避免沿用旧图/失败结果。
    val result by key(reload) {
        produceState(ImageLoadState(), url) {
            val image = try {
                val data = withContext(Dispatchers.IO) { ImageFetch.fetch(url, force = reload > 0) }
                data?.let {
                    withContext(Dispatchers.Default) {
                        val mime = it.mime ?: ImageFetch.mimeOf(url, it.bytes)
                        val bitmap = it.takeIf { mime !in setOf("image/svg+xml", "image/gif", "image/avif") }
                            ?.let { Images.decode(it.bytes, 4096) }
                        ViewerImage(it, mime, bitmap, if (bitmap == null) webImageHtml(it.bytes, mime) else null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn("image", "加载图片失败", e)
                null
            } catch (e: OutOfMemoryError) {
                AppLog.warn("image", "图片过大，无法显示", e)
                null
            }
            value = ImageLoadState(image, complete = true)
        }
    }
    val image = result.image
    val data = image?.data
    val mime = image?.mime
    val failed = (image == null && result.complete) || renderFailed

    fun save(request: ImageSaveRequest) {
        busy = true
        scope.launch {
            try {
                val where = withContext(Dispatchers.IO) { saveImage(ctx, request.bytes, request.mime, request.name) }
                ctx.toast(where?.let { "已保存到 $it" } ?: "保存失败")
            } finally {
                busy = false
            }
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingSave
        pendingSave = null
        if (granted && request != null) save(request)
        else {
            busy = false
            if (request != null) ctx.toast("保存到相册需要存储权限，请在系统设置中允许后重试")
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            image == null && !failed -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color.White)
            failed -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("图片加载失败", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                GhButton("重试", icon = R.drawable.oc_sync) { reload++ }
            }
            image?.bitmap != null -> ZoomableBitmap(image.bitmap) { showBars = !showBars }
            image?.html != null -> WebImage(image.html, onFailure = { renderFailed = true; showBars = true }) { showBars = !showBars }
        }

        if (showBars) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OcButton(R.drawable.oc_x, ImageViewer::close, Color.White)
                Text(
                    title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                MoreMenu(
                    listOf(
                        MenuAction("在浏览器打开") { ctx.openBrowser(url) },
                        MenuAction("复制图片链接") { ctx.copy(url, "链接已复制") },
                    ),
                    tint = Color.White,
                )
            }
            if (data != null) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GhButton("保存到相册", Modifier.weight(1f), primary = true, icon = R.drawable.oc_download, enabled = !busy) {
                        val request = ImageSaveRequest(data.bytes, mime, saveName(url, title, mime))
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingSave = request
                            busy = true
                            try {
                                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } catch (e: Exception) {
                                pendingSave = null
                                busy = false
                                AppLog.warn("image", "无法申请存储权限", e)
                                ctx.toast("无法申请存储权限")
                            }
                        } else save(request)
                    }
                    GhButton("分享", Modifier.weight(1f), icon = R.drawable.oc_share_android, enabled = !busy) {
                        busy = true
                        scope.launch {
                            try {
                                shareImage(ctx, data.bytes, mime, saveName(url, title, mime))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AppLog.warn("image", "分享图片失败", e)
                                ctx.toast("分享失败")
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 双指缩放（1~8 倍）、拖动、双击放大 / 还原；单击切换顶部和底部工具栏 */
@Composable
private fun ZoomableBitmap(bitmap: ImageBitmap, onTap: () -> Unit) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val tap by rememberUpdatedState(onTap)

    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it; offset = clamp(offset, scale) }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { p ->
                        if (scale > 1.05f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            val s = 2.5f
                            val center = Offset(box.width / 2f, box.height / 2f)
                            scale = s
                            offset = clamp((center - p) * (s - 1f), s)
                        }
                    },
                )
            }
            .pointerInput(bitmap) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val ns = (scale * zoom).coerceIn(1f, 8f)
                    val center = Offset(box.width / 2f, box.height / 2f)
                    // 以双指中心为锚点缩放，再叠加拖动
                    val anchored = (offset - (centroid - center)) * (ns / scale) + (centroid - center)
                    offset = clamp(anchored + pan, ns)
                    scale = ns
                }
            },
    ) {
        Image(
            bitmap, null,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
            contentScale = ContentScale.Fit,
        )
    }
}

/** GIF / SVG / AVIF：交给 WebView 显示（保留动画与矢量），开启内置缩放 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebImage(html: String, onFailure: () -> Unit, onTap: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val tap by rememberUpdatedState(onTap)
    val failure by rememberUpdatedState(onFailure)
    val webRef = remember { arrayOfNulls<ViewerWebView>(1) }
    DisposableEffect(Unit) {
        onDispose {
            webRef[0]?.release()
            webRef[0] = null
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            webRef[0]?.takeUnless { it.released }?.let { view ->
                try {
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> view.onResume()
                        Lifecycle.Event.ON_PAUSE -> view.onPause()
                        else -> Unit
                    }
                } catch (e: Exception) {
                    view.release()
                    if (webRef[0] === view) webRef[0] = null
                    AppLog.warn("image", "图片 WebView 生命周期调用失败", e)
                    failure()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    AndroidView(
        factory = { c ->
            FrameLayout(c).apply {
                try {
                    val view = ViewerWebView(c)
                    webRef[0] = view
                    view.apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        addJavascriptInterface(object {
                            @android.webkit.JavascriptInterface
                            fun t() { post { if (!released) tap() } }

                            @android.webkit.JavascriptInterface
                            fun failed() { post { if (!released) failure() } }
                        }, "Tap")
                        webViewClient = object : WebViewClient() {
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                // 已退出的渲染器不能复用；只清理此回调给出的实例。
                                (view as? ViewerWebView)?.release()
                                if (webRef[0] === view) webRef[0] = null
                                AppLog.warn("image", "图片渲染进程退出：didCrash=" + detail.didCrash())
                                failure()
                                return true
                            }
                        }
                    }
                    addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
                    else view.onPause()
                    view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                } catch (e: Exception) {
                    webRef[0]?.release()
                    webRef[0] = null
                    AppLog.warn("image", "无法创建图片 WebView", e)
                    // factory 正在构建 AndroidView，下一次主线程消息再切换到错误界面。
                    post { failure() }
                }
            }
        },
        onRelease = { container ->
            (container.getChildAt(0) as? ViewerWebView)?.let { view ->
                view.release()
                if (webRef[0] === view) webRef[0] = null
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private class ViewerWebView(context: Context) : WebView(context) {
    var released = false
        private set

    fun release() {
        if (released) return
        released = true
        runCatching { (parent as? ViewGroup)?.removeView(this) }
        // 渲染器崩溃后个别调用也可能失败，不能因此跳过后续销毁。
        runCatching { stopLoading() }
        runCatching { removeJavascriptInterface("Tap") }
        runCatching { removeAllViews() }
        runCatching { destroy() }
    }
}

/** 在后台线程准备 data URL，避免大 GIF/SVG 的 Base64 编码阻塞主线程。 */
private fun webImageHtml(bytes: ByteArray, mime: String?): String {
    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val type = mime?.takeIf { Regex("image/[a-zA-Z0-9.+-]+").matches(it) } ?: "image/png"
    return """<html><head><meta name="viewport" content="width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=8,user-scalable=yes">
        <style>html,body{margin:0;height:100%;background:#000;display:flex;align-items:center;justify-content:center}
        img{max-width:100%;max-height:100%;object-fit:contain;background:${if (type == "image/svg+xml") "#fff" else "transparent"}}</style></head>
        <body onclick="Tap.t()"><img onerror="Tap.failed()" src="data:$type;base64,$b64"></body></html>"""
}

private fun saveName(url: String, title: String, mime: String?): String {
    val ext = ImageFetch.extOf(mime)
    val base = ImageViewer.fileName(url).substringBeforeLast('.').ifBlank { title }
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).ifBlank { "image" }
    return "${base}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext"
}

/** 保存到系统相册（Pictures/MobileGH）；返回给用户看的保存位置，失败返回 null */
private fun saveImage(ctx: Context, bytes: ByteArray, mime: String?, name: String): String? = try {
    val type = mime ?: "image/png"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val svg = type == "image/svg+xml"
        val collection = if (svg) MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val dir = (if (svg) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_PICTURES) + "/MobileGH"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: error("无法创建文件")
        var published = false
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入文件")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) > 0) { "无法发布图片" }
            published = true
            dir
        } finally {
            if (!published) runCatching { resolver.delete(uri, null, null) }
                .onFailure { AppLog.warn("image", "无法清理保存失败的图片", it) }
        }
    } else {
        check(ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            "没有写入相册的权限"
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(
            if (type == "image/svg+xml") Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_PICTURES,
        ), "MobileGH")
        check((dir.exists() || dir.mkdirs()) && dir.isDirectory) { "保存目录不可用" }
        val f = File(dir, name)
        check(f.createNewFile()) { "文件已存在" }
        var saved = false
        try {
            f.outputStream().use { it.write(bytes) }
            MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(type), null)
            saved = true
            dir.absolutePath
        } finally {
            if (!saved && !f.delete() && f.exists()) AppLog.warn("image", "无法清理保存失败的文件")
        }
    }
} catch (e: Exception) {
    AppLog.warn("image", "保存图片失败：" + name, e)
    null
}

private suspend fun shareImage(ctx: Context, bytes: ByteArray, mime: String?, name: String) = withContext(Dispatchers.IO) {
    val dir = File(ctx.cacheDir, "shared")
    check((dir.exists() || dir.mkdirs()) && dir.isDirectory) { "分享目录不可用" }
    // 独立文件供接收应用异步读取，后一次分享不能删除前一次的文件。
    val f = File(dir, name)
    check(f.createNewFile()) { "分享文件已存在" }
    var launched = false
    try {
        f.outputStream().use { it.write(bytes) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        withContext(Dispatchers.Main.immediate) {
            val send = Intent(Intent.ACTION_SEND)
                .setType(mime ?: "image/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("image", uri) }
            ctx.startActivity(Intent.createChooser(send, "分享图片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            launched = true
        }
    } catch (e: Exception) {
        // Intent 已发出后，即使查看器协程被取消，也要保留接收应用正在读取的文件。
        if (!launched && !f.delete() && f.exists()) AppLog.warn("image", "无法清理分享失败的文件")
        throw e
    }
}
