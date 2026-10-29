import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Подпись: в CI ключ приходит из секретов (SIGNING_KEYSTORE_B64 + SIGNING_PASSWORD) → app/release.jks.
// Без секретов используется запасной ключ ci-fallback.jks из репозитория (только для проб: он публичный).
val releaseJks = file("release.jks")
val signPassword: String = System.getenv("SIGNING_PASSWORD") ?: ""
val useRelease = releaseJks.exists() && signPassword.isNotEmpty()
val ver = Properties().apply { file("../version.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "ru.skywige.swds"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.skywige.swdspocket"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = (ver.getProperty("version") ?: "1.0") + "." + buildNumber
    }

    signingConfigs {
        create("swds") {
            if (useRelease) {
                storeFile = releaseJks
                storePassword = signPassword
                keyAlias = "swds"
                keyPassword = signPassword
            } else {
                storeFile = file("ci-fallback.jks")
                storePassword = "swds-fallback"
                keyAlias = "swds"
                keyPassword = "swds-fallback"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("swds")
        }
        debug {
            signingConfig = signingConfigs.getByName("swds")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
