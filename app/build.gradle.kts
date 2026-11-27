import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sportcamhud.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sportcamhud.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"
    }

    // The signing keystore is restored only on GitHub Actions runners.
    // Debug builds remain possible without secrets, but release builds must be signed.
    signingConfigs {
        create("developerRelease") {
            providers.environmentVariable("ANDROID_SIGNING_STORE_FILE").orNull?.let { storeFile = file(it) }
            storePassword = providers.environmentVariable("ANDROID_SIGNING_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("ANDROID_SIGNING_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("ANDROID_SIGNING_KEY_PASSWORD").orNull
            providers.environmentVariable("ANDROID_SIGNING_STORE_TYPE").orNull?.let { storeType = it }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("developerRelease")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
}
