package com.mobilegh.ui.components

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.mobilegh.R
import com.mobilegh.data.AppLock
import com.mobilegh.ui.theme.Gh

/** 系统身份验证（生物识别或锁屏密码）。 */
object Biometrics {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    private val silent = setOf(
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
    )

    /** 设备已设置锁屏密码或已录入生物识别，可以发起验证 */
    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS || deviceSecure(ctx)

    fun deviceSecure(ctx: Context): Boolean = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun authenticate(activity: FragmentActivity, title: String, subtitle: String?, onSuccess: () -> Unit, onError: (String) -> Unit) {
        AppLock.authenticating = true
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                AppLock.authenticating = false
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                AppLock.authenticating = false
                if (errorCode !in silent) onError(errString.toString().ifBlank { "验证失败（$errorCode）" })
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        runCatching { prompt.authenticate(info) }.onFailure {
            AppLock.authenticating = false
            onError("无法发起验证：${it.message ?: it.javaClass.simpleName}")
        }
    }
}

fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

/** 应用锁定时覆盖全部内容的界面；进入时自动弹出一次验证，失败或取消后可点「解锁」重试。 */
@Composable
fun AppLockScreen() {
    val g = Gh.c
    val ctx = rememberCtx()
    val activity = remember(ctx) { ctx.findFragmentActivity() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var prompted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun prompt() {
        val a = activity ?: return
        if (!Biometrics.available(a)) {
            // 设备已移除锁屏密码：应用锁失去意义，自动关闭以免无法进入
            AppLock.setLockEnabled(false)
            ctx.toast("设备未设置锁屏密码，应用锁已关闭")
            return
        }
        error = null
        Biometrics.authenticate(a, "解锁 MobileGH", "验证身份后继续使用", onSuccess = { AppLock.unlock() }, onError = { error = it })
    }

    LaunchedEffect(lifecycle) {
        when {
            // 真正离开过应用（而不是系统验证界面盖住）时，回来再自动弹一次
            lifecycle == Lifecycle.State.CREATED && !AppLock.authenticating -> prompted = false
            lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !prompted -> {
                prompted = true
                prompt()
            }
        }
    }
    // 锁定期间禁止截屏，最近任务中也不显示内容
    DisposableEffect(activity) {
        val w = activity?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    BackHandler { activity?.moveTaskToBack(true) }

    Column(
        Modifier.fillMaxSize().background(g.canvas).systemBarsPadding().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Oc(R.drawable.oc_mark_github, g.fg, 56.dp)
        Spacer(Modifier.height(20.dp))
        Text("MobileGH 已锁定", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
        Spacer(Modifier.height(6.dp))
        Text("使用指纹、面容或锁屏密码解锁", fontSize = 14.sp, color = g.fgMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        GhButton("解锁", Modifier.fillMaxWidth(0.6f), primary = true, icon = R.drawable.oc_unlock) { prompt() }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 13.sp, color = g.danger, textAlign = TextAlign.Center)
        }
    }
}
