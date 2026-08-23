plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.test") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
    id("androidx.baselineprofile") version "1.3.3" apply false
    // Renders Compose on the JVM so screens can be seen without a device or emulator.
    //
    // Pinned to 1.3.4 deliberately: it is the last release built against Kotlin 1.9.24, the version
    // this project uses. 1.3.5 moved to Kotlin 2.0.21, and because a plugin declared here puts its
    // Kotlin on the buildscript classpath, that silently upgraded the whole build - breaking KSP
    // and demanding the Compose Compiler plugin that Kotlin 2.0 requires. "apply false" does not
    // prevent that, and neither did gating the apply behind a property: the version declaration
    // alone is what does it. Moving to Kotlin 2.0 is a fine thing to do on purpose, but not as an
    // accident of adding a screenshot tool.
    id("app.cash.paparazzi") version "1.3.4" apply false
}
