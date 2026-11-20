import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val signing = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String) = signing.getProperty(key) ?: System.getenv(env)
val storePasswordValue = signingValue("storePassword", "MOBILEGH_STORE_PASSWORD")
val keyPasswordValue = signingValue("keyPassword", "MOBILEGH_KEY_PASSWORD")

android {
    namespace = "com.mobilegh.translation"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mobilegh.translation"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    if (storePasswordValue != null && keyPasswordValue != null) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(signingValue("storeFile", "MOBILEGH_STORE_FILE") ?: "release.keystore")
            storePassword = storePasswordValue
            keyAlias = signingValue("keyAlias", "MOBILEGH_KEY_ALIAS") ?: "mobilegh"
            keyPassword = keyPasswordValue
        }
    }
    buildTypes.named("release") {
        signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
    }
    packaging.jniLibs.useLegacyPackaging = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig.ndk.abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
}

kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

dependencies {
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
}
