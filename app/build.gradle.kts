import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    kotlin("plugin.serialization") version "2.0.0"
}

// Read local.properties — these values are never committed to version control.
val localProps = Properties().also { props ->
    val file = rootProject.file("local.properties")
    if (file.exists()) props.load(file.inputStream())
}

android {
    namespace = "com.enderthor.kSafe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.enderthor.kSafe"
        minSdk = 23
        targetSdk = 34
        versionCode = 202605022
        versionName = "1.1.0"

        // Calibration log delivery credentials — injected from local.properties at compile time.
        // Falls back to empty string if the key is not set (LogReporter skips sending in that case).
        buildConfigField("String", "CALIB_BOT_TOKEN",
            "\"${localProps.getProperty("calib.bot_token", "")}\"")
        buildConfigField("String", "CALIB_CHAT_ID",
            "\"${localProps.getProperty("calib.chat_id", "")}\"")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.hammerhead.karoo.ext)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.androidx.lifeycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.preview)
    implementation(libs.androidx.glance.appwidget.preview)
    implementation(libs.timber)
    implementation(libs.androidx.foundation.android)
    implementation(libs.androidx.foundation.layout.android)
}
