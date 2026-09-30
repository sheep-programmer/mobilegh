import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 公开的 OAuth App Client ID（设备码流程不需要 secret），可用环境变量或 gradle 属性覆盖
val clientId: String = (System.getenv("MOBILEGH_CLIENT_ID")
    ?: providers.gradleProperty("mobilegh.clientId").orNull)?.takeIf { it.isNotBlank() } ?: "Ov23litcWlPUB3KqPlQ2"

// 发布签名：优先读取 keystore.properties（已被 .gitignore 忽略），
// 也支持用环境变量在 CI 中注入。两者都没有时回退到 debug 签名。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val hasReleaseKey = prop("storePassword", "MOBILEGH_STORE_PASSWORD") != null &&
    prop("keyPassword", "MOBILEGH_KEY_PASSWORD") != null

android {
    namespace = "com.mobilegh"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mobilegh"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.6"
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$clientId\"")
    }

    // 按 ABI 拆分：默认主产物为 arm64-v8a（现代手机），同时产出 32 位与 x86 版本
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // 必须在 buildTypes 之前创建，否则 buildTypes 里取不到
    if (hasReleaseKey) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(prop("storeFile", "MOBILEGH_STORE_FILE") ?: "release.keystore")
                storePassword = prop("storePassword", "MOBILEGH_STORE_PASSWORD")
                keyAlias = prop("keyAlias", "MOBILEGH_KEY_ALIAS") ?: "mobilegh"
                keyPassword = prop("keyPassword", "MOBILEGH_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 有发布密钥就用它签名，否则回退到 debug 签名（方便直接安装体验）
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
