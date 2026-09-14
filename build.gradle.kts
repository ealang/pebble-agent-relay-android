plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // From Kotlin 2.0 the Compose compiler ships with Kotlin itself, applied
    // through this plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
