import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Keep the release key and its password out of source control. Local release builds
// are signed when the ignored password file exists; otherwise they remain unsigned.
val releaseKeyPasswordFile = rootProject.file("signing/release-signing-password.txt")

/**
 * 用「运行构建的 JDK 17+ 编译、产出 Java 17 字节码」的方式固定目标版本，
 * 而不是 `jvmToolchain(17)`。
 *
 * 原因：jvmToolchain 会强制要求机器上存在**确切** 17 版本的 JDK，找不到就构建失败
 * （除非另外配 foojay 工具链下载仓库，多一份网络依赖）。而实际上从 JDK 17 到 21 都能
 * 稳定产出 17 字节码，把约束放宽到"任意 17+"能让工程在任何一台装了 Android Studio
 * 的机器上直接构建 —— 不必再单独装一个 JDK 17。
 *
 * target 17 是硬要求：AGP 8.x 的 desugar 与 D8 都按 17 处理，且 API 24/25 的
 * 脱糖依赖这一设置（见 android.compileOptions.isCoreLibraryDesugaringEnabled）。
 */
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.pocketserver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketserver"
        // minSdk 24：覆盖 Android 7.0 起的手机与电视盒子
        minSdk = 24
        targetSdk = 35
        versionCode = 120
        versionName = "V1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // 端口约定（需规 §4.4 / 记忆项 3）
        buildConfigField("int", "DEFAULT_HTTP_PORT", "8080")
        buildConfigField("int", "DEFAULT_FTP_PORT", "2121")
        buildConfigField("int", "PASSIVE_PORT_START", "50000")
        buildConfigField("int", "PASSIVE_PORT_END", "50100")
        // mDNS 主机名（需规 §4.4 的"零输入"核心）
        buildConfigField("String", "MDNS_HOST", "\"pocketserver.local\"")
        buildConfigField("String", "MDNS_SERVICE_TYPE", "\"_pocketserver._tcp\"")
    }

    signingConfigs {
        if (releaseKeyPasswordFile.isFile) {
            create("release") {
                val releasePassword = releaseKeyPasswordFile.readText().trim()
                storeFile = rootProject.file("signing/pocketserver-release.jks")
                storePassword = releasePassword
                keyAlias = "pocketserver-release"
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseKeyPasswordFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 部分 java.nio / java.net API 在 API 24/25 上需要脱糖
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    // Compose BOM 2024.09 still selects graphics-path 1.0.1, whose bundled native library
    // is not aligned for 16 KB page-size devices. Keep this native artifact current explicitly.
    implementation(libs.androidx.graphics.path)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Lifecycle / Navigation
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    // 设置持久化
    implementation(libs.androidx.datastore.preferences)

    // 记录类数据
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // 二维码生成与识别（不使用 Google 服务，满足 FR-10 国行盒子约束）
    implementation(libs.zxing.core)

    // 扫码
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
