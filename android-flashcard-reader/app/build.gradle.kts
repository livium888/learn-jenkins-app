import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Optional release-signing config. Values come from a gitignored keystore.properties at the
// project root, or from RELEASE_* environment variables (e.g. CI secrets). Nothing secret is
// committed: when neither is present, a `release` build is simply left unsigned - the debug
// build used for sideloading is unaffected. This is the seam a Play Store release plugs into.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun releaseSecret(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)
val hasReleaseSigning = releaseSecret("storeFile", "RELEASE_STORE_FILE") != null

android {
    namespace = "com.flashcardreader.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.flashcardreader.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }

    // A single, committed signing key so every build - CI or local - signs with the
    // SAME key. Android only allows an in-place update when the new APK's signature
    // matches the installed one; without this, each CI runner generated a random
    // debug key, so every APK forced an uninstall (wiping saved flashcards). This is
    // a throwaway debug key for personal sideloading, not a Play Store release key,
    // so keeping it in the repo is intentional.
    signingConfigs {
        create("stable") {
            storeFile = file("flashcard-debug.keystore")
            storePassword = "flashcardreader"
            keyAlias = "flashcard"
            keyPassword = "flashcardreader"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSecret("storeFile", "RELEASE_STORE_FILE")!!)
                storePassword = releaseSecret("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = releaseSecret("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = releaseSecret("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            // Non-debuggable + R8 (shrink/optimize) - this is the build that actually benefits
            // from ART optimization and the Baseline Profile. Signed with the real key when one
            // is supplied, else with the stable debug key so the sideloaded release APK still
            // installs and updates in place over the existing app (same signature).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("stable")
            }
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Local persistence: global term/flashcard database + imported source library.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // EPUB parsing (zip of XHTML) and stripping MOBI's embedded HTML down to plain text.
    implementation("org.jsoup:jsoup:1.17.2")

    // PDF text extraction (fixed-layout PDF -> reflowable text).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Encrypts the AI-tutor API key at rest (AES-256, key wrapped by the Android Keystore).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Installs the bundled Baseline Profile so ART AOT-compiles hot paths (startup/scroll).
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    // Scheduled evening/morning review reminder notifications.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
