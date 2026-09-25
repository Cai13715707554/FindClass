import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * 高德 Key **不再写进 BuildConfig**。
 *
 * 原因：Key 是开发者凭据，打进 APK 就能被反编译出来；而且高德 Key 与
 * 「包名 + SHA1 签名」绑定，换机器就要换 Key。现在改为由用户在
 * 「我的 → 设置 → 高德地图 Key」里填写，存 SharedPreferences。
 *
 * 这里只保留从 local.properties 读取的能力，用于**本机开发调试时预填**
 * 到 UI 的输入框（可选），不参与任何构建期常量注入。
 */
val debugAmapKeyHint: String = run {
    val props = rootProject.file("local.properties")
    if (!props.exists()) {
        System.getenv("AMAP_KEY") ?: ""
    } else {
        val loaded = Properties()
        props.inputStream().use { stream -> loaded.load(stream) }
        loaded.getProperty("amap.key") ?: System.getenv("AMAP_KEY") ?: ""
    }
}

android {
    namespace = "com.school.nav"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.school.nav"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 开发调试时把 local.properties 里的 amap.key 预填到设置页输入框（可选）
        buildConfigField("String", "AMAP_KEY_HINT", "\"$debugAmapKeyHint\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    /**
     * 高德 SDK 自带 arm64-v8a 与 armeabi-v7a 两套 so（约 19 MB），
     * 单包同时带上会明显变大。release 按 ABI 拆分，国内商店通常只收 arm64。
     * debug 不拆，方便直接装到任意测试机。
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    /*
     * 高德地图 SDK（用于地图编辑器绘制楼栋轮廓）。
     *
     * 依赖说明：
     *  - 引入 3dmap 就够了，它**自带** arm64-v8a / armeabi-v7a 的 libAMapSDK_MAP_*.so，
     *    以及地图内部用到的定位与搜索能力，不需要另外加 location / search。
     *  - 这个 jar 有约 19 MB，会让 APK 明显变大，所以 release 构建要按 ABI 拆分
     *    （见下面的 splits 配置）。
     *  - 官方仓库 maven.amap.com 国内可达但本机连不通，而 Maven Central / 阿里云公共仓库
     *    也有全套 com.amap.api 产物，因此走 mavenCentral() 即可，不需要加高德私有仓库。
     */
    implementation("com.amap.api:3dmap:10.0.600")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.14.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
