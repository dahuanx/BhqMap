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
        versionCode = 39
        versionName = "0.8.0"

        // 注入到 BuildConfig.TIANDITU_TK
        buildConfigField("String", "TIANDITU_TK", "\"$tiandituTk\"")
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
