plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.flashcardreader.app.baselineprofile"
    compileSdk = 34

    defaultConfig {
        // Macrobenchmark / profile generation needs API 28+ on the device that runs it.
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The app whose startup/scroll paths we are profiling.
    targetProjectPath = ":app"
}

// Generate the profile on whatever device CI provides (the emulator-runner action) rather
// than a Gradle Managed Device, so the one `generateBaselineProfile` task works in CI.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.3")
}
