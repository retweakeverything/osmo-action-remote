plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from environment variables so the key never lives in
// the repo. CI sets them from GitHub secrets; without them, release builds are
// left unsigned and debug builds are unaffected.
val releaseKeystore: String? = System.getenv("OSMO_KEYSTORE_FILE")

// Version can be overridden from the command line, e.g. -PappVersionName=0.2.0
val appVersionName = (findProperty("appVersionName") as String?) ?: "0.1.0"
val appVersionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1

android {
    namespace = "com.retweakeverything.osmoremote"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.retweakeverything.osmoremote"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("OSMO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OSMO_KEY_ALIAS")
                keyPassword = System.getenv("OSMO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 drops unused code and icons: about 45 MB down to a few MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
