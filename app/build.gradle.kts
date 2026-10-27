import java.util.Properties

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release 签名材料不入库。
// keystore 路径、alias 与口令从 local.properties 读取（该文件已被 .gitignore 忽略），
// 口令也可用环境变量 DSH_KEYSTORE_PASSWORD 兜底。
// 仅当 keystore 文件存在且路径/alias/口令齐全时才创建 release signingConfig；
// 否则 release 仍可构建，只是不签名（assembleRelease 可能产出 unsigned APK）。
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

val envKeystorePassword = System.getenv("DSH_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeystorePath = localProperties.getProperty("dsh.keystore.path")
    ?.takeIf { it.isNotBlank() }
    ?: "signing/dsh-mobile.jks"
val releaseKeystore = rootProject.file(releaseKeystorePath)
val releaseStorePassword = localProperties.getProperty("dsh.keystore.storePassword")
    ?.takeIf { it.isNotBlank() }
    ?: envKeystorePassword
val releaseKeyPassword = localProperties.getProperty("dsh.keystore.keyPassword")
    ?.takeIf { it.isNotBlank() }
    ?: envKeystorePassword
val releaseKeyAlias = localProperties.getProperty("dsh.keystore.keyAlias")
    ?.takeIf { it.isNotBlank() }
val hasReleaseSigning = releaseKeystore.exists() &&
    releaseStorePassword != null &&
    releaseKeyPassword != null &&
    releaseKeyAlias != null

android {
    namespace = "ai.deepseek.dsh.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.deepseek.dsh.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.4.4"

        // v0.3.0 起不再内置任何证书/指纹：普通服务器 TLS 采用 fail-open；
        // v0.4.0 认证网关由扫码取得证书 pin，仅对该 host 走严格证书校验。
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")

    // v0.4.0 扫码配对：CameraX 取景/分析 + ML Kit bundled 二维码识别（不依赖 Google Play Services）。
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
