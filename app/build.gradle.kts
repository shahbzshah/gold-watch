import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing credentials live in keystore.properties (project
// root, never committed). If absent, the release build is unsigned.
// There is no key in this app: the gold bot's /api/shift endpoint
// is public.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasReleaseKey = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasReleaseKey) load(keystorePropsFile.inputStream())
}

android {
    namespace = "com.megah.goldwatch"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.megah.goldwatch"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v1.0 shipped v2-only; some installers want both schemes.
                isV1SigningEnabled = true
                isV2SigningEnabled = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Framework-only app (like Crash Watch): Views, org.json,
    // NotificationManager and foreground services are all part of
    // the Android framework — no AndroidX dependencies.
}
