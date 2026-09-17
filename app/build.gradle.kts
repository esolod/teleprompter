import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Обчислюється один раз під час конфігурації Gradle -- тобто щоразу, коли CI
// реально перезбирає проект. Використовується як "відбиток" збірки, щоб на
// головному екрані додатку можна було візуально підтвердити, що встановлена
// версія -- це саме той білд, який щойно вийшов з Actions, а не залишок
// старого кешу/APK.
val buildTimestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm").apply {
    timeZone = TimeZone.getTimeZone("Europe/Madrid")
}.format(Date())

android {
    namespace = "com.solod.teleprompter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.solod.teleprompter"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "BUILD_TIME", "\"$buildTimestamp\"")
    }

    signingConfigs {
        getByName("debug") {
            // Фіксований debug-ключ, закомічений у репо (app/debug.keystore).
            // Без цього кожен CI-білд підписувався б новим випадковим ключем,
            // і Android відмовлявся б ставити APK поверх попередньої версії
            // ("App not installed" через конфлікт підпису).
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
