plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing is read from environment variables so no secret is stored in the repo.
// The GitHub Actions workflow sets these from GitHub Secrets (see README).
val ksPath: String? = System.getenv("ORBIT_KEYSTORE_PATH")
val ksPassword: String? = System.getenv("ORBIT_KEYSTORE_PASSWORD")
val keyAliasEnv: String? = System.getenv("ORBIT_KEY_ALIAS")
val keyPasswordEnv: String? = System.getenv("ORBIT_KEY_PASSWORD")
val hasSigning = !ksPath.isNullOrBlank() && !ksPassword.isNullOrBlank() &&
    !keyAliasEnv.isNullOrBlank() && !keyPasswordEnv.isNullOrBlank()

android {
    namespace = "com.orbit.ai"
    // Android 16 is API 36, but API 35 is the newest level AGP 8.7 supports.
    // The app still runs on Android 16 devices.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.orbit.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The offline model engine ships native code for 64-bit ARM phones only.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = ksPassword
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false // keep false until ProGuard rules are tested with the native engine
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    lint { abortOnError = false }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Local database
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Settings + encrypted storage for the proxy token
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking (OkHttp talks to the backend proxy and downloads model files)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Background work (reminders, routines)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Files
    implementation("androidx.documentfile:documentfile:1.0.1")

    // PDF text extraction (offline)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // On-device LLM runtime (Google LiteRT-LM). Version follows Google's own docs.
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
