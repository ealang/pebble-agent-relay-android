plugins {
    // The floor is set by pebblekit2 1.3.x: it demands compileSdk 37, which
    // demands AGP 9, which ships built-in Kotlin — so there is no separate
    // kotlin.android plugin any more. This AGP/Kotlin/Gradle combination is
    // the one PebbleKitAndroid2's own sample builds with.
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
