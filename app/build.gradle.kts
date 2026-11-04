import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Fast "kit" builds (no native engine) for CI PR validation: -PskipNative=true
val skipNative = providers.gradleProperty("skipNative").getOrElse("false").toBoolean()

android {
    namespace = "com.pocketnotebook.app"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.pocketnotebook.app"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("versionName").getOrElse("0.1.0-alpha.7")
        vectorDrawables { useSupportLibrary = true }

        ndk {
            // Sideload target is real phones; add "x86_64" locally for emulator runs.
            abiFilters += "arm64-v8a"
        }
    }

    if (!skipNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    // Signing priority for the release build:
    //   1. env vars (set by CI when repo secrets RELEASE_KEYSTORE_* are configured)
    //   2. committed sideload keystore (keeps sideload updates signature-stable)
    //   3. debug key (last resort; breaks upgrade continuity, still installable)
    val envStore = providers.environmentVariable("PN_KEYSTORE_PATH").orNull
    val sideloadProps = rootProject.file("keystores/keystore.properties")
    val hasSideload = sideloadProps.exists()

    signingConfigs {
        if (envStore != null) {
            create("releaseCi") {
                storeFile = file(envStore)
                storePassword = providers.environmentVariable("PN_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("PN_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("PN_KEY_PASSWORD").orNull
            }
        }
        if (hasSideload) {
            val p = Properties()
            sideloadProps.inputStream().use { p.load(it) }
            create("sideload") {
                storeFile = rootProject.file("keystores/" + p.getProperty("storeFile"))
                storeType = p.getProperty("storeType")
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // enable + rules when ProGuard config lands (pre-1.0)
            signingConfig = when {
                envStore != null -> signingConfigs.getByName("releaseCi")
                hasSideload -> signingConfigs.getByName("sideload")
                else -> signingConfigs.getByName("debug")
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
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
    lint {
        abortOnError = false
    }
    androidResources {
        // One locale for now: English-only by design (see docs/PLAN.md non-goals).
        generateLocaleConfig = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.documentfile)

    // Bundled SQLite 3.50.1 (FTS5, 16 KB-page aligned) — Android's system SQLite
    // ships without FTS5 on every vendor incl. Android 15/16 ("no such module:
    // fts5" crash of v0.1.0-alpha.2), so the app bundles its own engine.
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.bundled)
    // Real-FTS5 engine for the JVM schema regression test only.
    testImplementation(libs.xerial.sqlite.jdbc)

    implementation(libs.com.tom.roush.pdfbox)
    implementation(libs.org.jsoup)
    implementation(libs.com.squareup.okhttp)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
