package com.mobilegh.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.mobilegh.R
import com.mobilegh.ui.theme.Gh
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 扫码结果：otpauth:// 链接或纯密钥文本 */
class QrResult(val text: String) {
    val secret: String?
        get() = when {
            text.startsWith("otpauth://", true) -> runCatching {
                Uri.parse(text).getQueryParameters("secret").firstOrNull()
            }.getOrNull()
            else -> null
        }
}

private val reader = MultiFormatReader().apply {
    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
}
private val busy = AtomicBoolean(false)

/** 从 CameraX YUV 帧解码二维码，返回文本或 null */
private fun decodeFrame(image: ImageProxy): String? {
    if (!busy.compareAndSet(false, true)) return null
    try {
        val plane = image.planes[0]
        val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
        // 只取画面中央 80% 区域，提升识别率并避免边缘干扰
        val cw = (image.width * 0.8f).toInt()
        val ch = (image.height * 0.8f).toInt()
        val source = PlanarYUVLuminanceSource(
            bytes, plane.rowStride, image.height,
            (image.width - cw) / 2, (image.height - ch) / 2, cw, ch, false
        )
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        return null
    } finally {
        reader.reset()
        busy.set(false)
    }
}

/**
 * 二维码扫码页：用于导入 GitHub 两步验证的验证器密钥。
 * 画面仅本地解码，不做任何网络传输。
 */
@Composable
fun QrScannerScreen(onScanned: (QrResult) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = Gh.c
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Page("扫码导入", onBack = onBack) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = ArrangementCenterVertical(),
            ) {
                Oc(R.drawable.oc_x_circle, g.fgMuted, 40.dp)
                Spacer(Modifier.height(16.dp))
                Text("需要相机权限才能扫码", color = g.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("也可以返回后手动粘贴密钥或 otpauth 链接。", color = g.fgMuted, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                GhButton("授予相机权限", primary = true) { permission.launch(Manifest.permission.CAMERA) }
            }
        }
        return
    }
    var done by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    Page("扫码导入", onBack = onBack) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewCtx ->
                    val previewView = PreviewView(viewCtx)
                    val providerFuture = ProcessCameraProvider.getInstance(viewCtx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { frame ->
                            val text = decodeFrame(frame)
                            frame.close()
                            if (text != null && !done) {
                                done = true
                                previewView.post { onScanned(QrResult(text)) }
                            }
                        }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        } catch (_: Exception) { /* 相机被占用等；页面仍可返回手动输入 */ }
                    }, ContextCompat.getMainExecutor(viewCtx))
                    previewView
                },
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Oc(R.drawable.oc_scan, Color.White, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("对准 GitHub 显示的二维码即可自动导入", color = Color.White, fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
                GhButton("手动粘贴密钥") { onBack() }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun ArrangementCenterVertical() = androidx.compose.foundation.layout.Arrangement.Center
