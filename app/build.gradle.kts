plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pocketnotebook.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketnotebook.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // Release signing:
    //  - If PN_KEYSTORE_PATH et al. are set (CI release workflow decodes them
    //    from repo secrets), sign with the release keystore.
    //  - Otherwise fall back to the debug key so sideload builds stay installable.
    signingConfigs {
        val keystorePath = providers.environmentVariable("PN_KEYSTORE_PATH").orNull
        if (keystorePath != null) {
            create("releaseCi") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("PN_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("PN_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("PN_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // enable + rules when ProGuard config lands (Phase 6)
            signingConfig = if (providers.environmentVariable("PN_KEYSTORE_PATH").isPresent) {
                signingConfigs.getByName("releaseCi")
            } else {
                signingConfigs.getByName("debug")
            }
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
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
