import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// tk 与签名配置从 local.properties 读取，不进源码、不进 Git
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val tiandituTk: String = localProps.getProperty("TIANDITU_TK").orEmpty()
val ksPass: String = localProps.getProperty("KS_PASS").orEmpty()
val ksAlias: String = localProps.getProperty("KEY_ALIAS").orEmpty()
val ksKeyPass: String = localProps.getProperty("KEY_PASS").orEmpty()

android {
    namespace = "com.bianhequ.bhqmap"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bianhequ.bhqmap"
        minSdk = 26
        targetSdk = 34
        versionCode = 43
        versionName = "0.9.2"

        // 注入到 BuildConfig.TIANDITU_TK
        buildConfigField("String", "TIANDITU_TK", "\"$tiandituTk\"")

        // 更新清单地址（v0.9.0）。两条通道都指向同一个 Gitee 仓库：
        //   主：仓库 raw 静态文件，不消耗 API 配额（实测连打 30 次无限流）
        //   备：Gitee contents API，正文 base64，匿名可读
        // 刻意不用 GitHub 做备用通道 —— raw.githubusercontent.com 在国内直连 DNS 解析失败，
        // 一个在目标网络里连不上的备用通道等于没有备用通道。
        // 将来若迁到对象存储/CDN，只改这两行，App 其余代码不动。
        buildConfigField(
            "String", "UPDATE_MANIFEST_URL",
            "\"https://gitee.com/dahuanxx/BhqMap/raw/master/update.json\""
        )
        buildConfigField(
            "String", "UPDATE_MANIFEST_API_URL",
            "\"https://gitee.com/api/v5/repos/dahuanxx/BhqMap/contents/update.json\""
        )
    }

    signingConfigs {
        create("release") {
            // 与巡查相机共用同一签名（两个 App 成体系，便于日后数据互通）
            // 密码等敏感项放在 local.properties（已排除出 Git）
            storeFile = rootProject.file("patrol.keystore")
            storePassword = ksPass
            keyAlias = ksAlias
            keyPassword = ksKeyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes.add("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 地图渲染层：纯 Java 瓦片引擎，只加载天地图 WMTS，不碰任何境外瓦片源
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
