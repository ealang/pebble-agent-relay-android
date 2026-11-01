import org.gradle.kotlin.dsl.support.serviceOf
import java.util.Properties

plugins {
    id("com.android.application") // AGP 9: Kotlin support is built in
    id("org.jetbrains.kotlin.plugin.compose")
}

// The debug keystore lives outside the repo (path comes from local.properties),
// so a rebuilt environment signs with the same key and the APK still installs
// over the copy already on the phone.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * Build-time settings, from local.properties (gitignored) or the environment,
 * so a release machine with no checked-out properties file can supply them too.
 */
fun buildSetting(name: String): String? =
    (localProps[name]?.toString() ?: System.getenv(name))?.trim()?.ifEmpty { null }

// The Telegram application credentials, baked in rather than typed by the user.
// They identify the *application*, not the account — every install of a
// Telegram client ships the same pair, and the official clients' values are
// published — so asking each user to register at my.telegram.org was work with
// nothing to show for it. Not secret either: anything in an APK decompiles in
// seconds. Gitignored only because they are a personal registration that
// Telegram can revoke if abused.
//
// Absent, the build still succeeds and the app says it has no credentials:
// a clone without a pair must stay buildable.
val telegramApiId = buildSetting("TELEGRAM_API_ID")?.toIntOrNull() ?: 0
val telegramApiHash = buildSetting("TELEGRAM_API_HASH").orEmpty()

android {
    namespace = "io.github.ealang.operator"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.ealang.operator"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("int", "TELEGRAM_API_ID", "$telegramApiId")
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
    }

    localProps["DEBUG_KEYSTORE"]?.toString()?.let { path ->
        signingConfigs {
            getByName("debug") {
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            // Dev APKs carry only the ABIs named in DEV_ABIS (comma-separated,
            // from local.properties) — a development phone has one ABI, and
            // every extra TDLib copy is ~30 MB of install for nothing.
            // Unset, debug packs everything.
            buildSetting("DEV_ABIS")?.let { abis ->
                ndk.abiFilters += abis.split(",").map { it.trim() }
            }
        }
        getByName("release") {
            // Deliberately unsigned: the build produces artifacts, signing is
            // a separate step with a key that lives nowhere near this repo
            // (see docs/RELEASING.md). No shrinking either — the APK is
            // almost entirely TDLib's native code, which R8 cannot touch, and
            // keep rules for the generated TDLib JNI classes would buy
            // nothing but risk.
            isMinifyEnabled = false
            signingConfig = null
        }
    }

    // Release APKs are split per ABI (plus one universal), so a sideloader
    // downloads ~30 MB instead of ~100 MB. The bundle ignores this and
    // carries everything; Play does its own splitting. Enabled only for
    // release tasks: AGP refuses an ABI that is both split and filtered, and
    // debug uses DEV_ABIS above. (Split APKs share one versionCode; fine for
    // sideloading, and Play gets the bundle, never the split APKs.)
    val releaseRequested = gradle.startParameter.taskNames.any { it.contains("Release") }
    splits {
        abi {
            isEnable = releaseRequested
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Off by default since AGP 8; the Telegram credentials above need it.
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

// The pebblekit2 jars are Java 21 bytecode. The APK does not care (d8 reads
// them fine), but unit tests load them on a plain JVM, so that JVM must be 21.
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        project.serviceOf<org.gradle.jvm.toolchain.JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // PebbleKit Android 2: CoreApp binds to our exported listener service and
    // delivers watchapp messages over AIDL. "client" is the companion-app side.
    implementation("io.rebble.pebblekit2:client:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // TdApi.java (generated by scripts/build-tdlib.sh) uses @Nullable/@IntDef.
    implementation("androidx.annotation:annotation:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.17.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
