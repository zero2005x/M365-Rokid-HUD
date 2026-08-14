import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}


// Load signing config from local.properties (not committed to git)
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    // use{} so the stream is closed; a bare FileInputStream leaks a file
    // descriptor on every configuration run.
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

/** True when the invocation includes a release task, so signing must be configured. */
val isBuildingRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

// ---------------------------------------------------------------------------
// Rokid CXR-M credentials
//
// The Client ID / Secret / AccessKey identify this app to Rokid's auth service
// and must never reach version control, so they are read from local.properties
// (or the environment, for CI) and injected as BuildConfig fields.
//
// The .lc licence file is a binary blob that ships in assets/. It is bound to
// the applicationId, so replacing it means re-issuing it from the Rokid
// developer console.
// ---------------------------------------------------------------------------
fun rokidProperty(key: String): String =
    localProperties.getProperty(key)
        ?: System.getenv(key)
        ?: ""

val rokidClientId = rokidProperty("ROKID_CLIENT_ID")
val rokidClientSecret = rokidProperty("ROKID_CLIENT_SECRET")
val rokidAccessKey = rokidProperty("ROKID_ACCESS_KEY")

/** Filename of the .lc licence inside src/main/assets. */
val rokidLicenseAsset = localProperties.getProperty("ROKID_LICENSE_ASSET")
    ?: "3c51a56e8deb4dce955122e8d1faaa04.lc"

// Fail the build only for releases: a debug build without credentials is still
// useful (BLE and WiFi transports work), it just cannot reach the glasses over
// CXR-M.
if (rokidClientId.isEmpty() || rokidAccessKey.isEmpty()) {
    val message = "Rokid CXR-M credentials are missing. Set ROKID_CLIENT_ID, " +
        "ROKID_CLIENT_SECRET and ROKID_ACCESS_KEY in local.properties."
    if (isBuildingRelease) throw GradleException(message) else logger.warn("WARNING: $message")
}

// ---------------------------------------------------------------------------
// Rust JNI (ninebot-ffi) native build
//
// The app loads "ninebot_ffi" via System.loadLibrary (see M365Native.kt), but
// nothing used to build or copy it: src/main/jniLibs was empty while the
// cross-compiled .so sat unused in ninebot-ffi/target/. The APK therefore
// shipped without the library and every native crypto call failed at runtime
// with UnsatisfiedLinkError.
//
// This wires the cross-compile into the normal build. It is intentionally
// non-fatal: if the Rust toolchain or the NDK is unavailable the build still
// completes, but prints a loud warning, so a missing library can never again go
// unnoticed.
//
// Skip explicitly with:  ./gradlew assembleDebug -PskipRustBuild
// ---------------------------------------------------------------------------

/** Android ABI -> Rust target triple. Must match defaultConfig.ndk.abiFilters. */
val rustAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

val rustCrateDir = rootProject.file("ninebot-ffi")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val skipRustBuild = providers.gradleProperty("skipRustBuild").isPresent
val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * Locates an NDK for cargo-ndk.
 *
 * cargo-ndk resolves the NDK from ANDROID_NDK_HOME / ANDROID_NDK_ROOT. If the
 * environment points at a path that does not exist, it fails with
 * "Error detecting NDK version for path ...", so prefer an actually-present
 * install discovered under the SDK and fall back to the environment.
 */
fun resolveNdkDir(): File? {
    val sdkDir = localProperties.getProperty("sdk.dir")?.let { File(it) }
    val installed = sdkDir?.resolve("ndk")
        ?.listFiles { f: File -> f.isDirectory && f.resolve("source.properties").exists() }
        ?.sortedBy { it.name }
        ?.lastOrNull()
    if (installed != null) return installed

    return sequenceOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT")
        .mapNotNull { System.getenv(it) }
        .map { File(it) }
        .firstOrNull { it.isDirectory }
}

val buildRustJni = tasks.register<Exec>("buildRustJni") {
    group = "build"
    description = "Cross-compiles ninebot-ffi with cargo-ndk into src/main/jniLibs"

    workingDir = rustCrateDir

    // Re-run only when the crate actually changes.
    inputs.dir(rustCrateDir.resolve("src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rustCrateDir.resolve("Cargo.toml"))
    outputs.dir(jniLibsDir)

    val ndkDir = resolveNdkDir()
    if (ndkDir != null) {
        environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
        environment("ANDROID_NDK_ROOT", ndkDir.absolutePath)
    }

    // cargo-ndk writes <outDir>/<abi>/libninebot_ffi.so
    commandLine(
        buildList {
            add(if (isWindows) "cargo.exe" else "cargo")
            add("ndk")
            rustAbis.forEach { add("-t"); add(it) }
            add("-o"); add(jniLibsDir.asFile.absolutePath)
            add("build"); add("--release")
        }
    )

    // Never break a build that was otherwise fine; report instead.
    isIgnoreExitValue = true

    onlyIf {
        when {
            skipRustBuild -> {
                logger.lifecycle("buildRustJni: skipped (-PskipRustBuild)")
                false
            }
            !rustCrateDir.isDirectory -> {
                logger.warn("buildRustJni: ${rustCrateDir} not found, skipping")
                false
            }
            ndkDir == null -> {
                logger.warn(
                    "buildRustJni: no Android NDK found. Install one via the SDK Manager " +
                        "(Tools > SDK Manager > SDK Tools > NDK) or set ANDROID_NDK_HOME to an " +
                        "existing directory. Skipping the native build."
                )
                false
            }
            else -> true
        }
    }

    doLast {
        val result = executionResult.get().exitValue
        if (result != 0) {
            logger.warn(
                "buildRustJni: cargo-ndk exited with $result. Is Rust installed and are the " +
                    "Android targets added?  rustup target add aarch64-linux-android " +
                    "armv7-linux-androideabi i686-linux-android x86_64-linux-android  " +
                    "and  cargo install cargo-ndk"
            )
        }
    }
}

/**
 * Fails loudly at packaging time if the native library is missing, so the app is
 * never shipped with a crypto layer that cannot load.
 */
val verifyRustJniLibs = tasks.register("verifyRustJniLibs") {
    group = "verification"
    description = "Checks that libninebot_ffi.so exists for every configured ABI"
    dependsOn(buildRustJni)

    val libsRoot = jniLibsDir.asFile
    val abis = rustAbis
    val failOnMissing = isBuildingRelease

    doLast {
        val missing = abis.filterNot { libsRoot.resolve("$it/libninebot_ffi.so").isFile }
        if (missing.isEmpty()) {
            logger.lifecycle("verifyRustJniLibs: libninebot_ffi.so present for all ${abis.size} ABIs")
            return@doLast
        }

        val message = "libninebot_ffi.so is MISSING for: ${missing.joinToString()}. " +
            "The app will throw UnsatisfiedLinkError and no BLE handshake or telemetry " +
            "decryption will work. Build it with:  ./gradlew :app:buildRustJni"

        // A release that cannot talk to the scooter is not shippable.
        if (failOnMissing) throw GradleException(message) else logger.warn("WARNING: $message")
    }
}

android {
    namespace = "com.m365bleapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.m365bleapp"
        // Rokid CXR-M SDK requires API 28 as its floor. Kept in sync with
        // glass-hud so both APKs share one support matrix.
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "1.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            // Specify supported architectures including 64-bit (arm64-v8a)
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        // Rokid CXR-M authentication material. Values come from
        // local.properties; see the block above.
        buildConfigField("String", "ROKID_CLIENT_ID", "\"$rokidClientId\"")
        buildConfigField("String", "ROKID_CLIENT_SECRET", "\"$rokidClientSecret\"")
        buildConfigField("String", "ROKID_ACCESS_KEY", "\"$rokidAccessKey\"")
        buildConfigField("String", "ROKID_LICENSE_ASSET", "\"$rokidLicenseAsset\"")
        // Support 16 KB memory page size (Google Play requirement)
        packaging {
            jniLibs {
                useLegacyPackaging = false
            }
        }
    }
    signingConfigs {
        create("release") {
            // Signing details must come from local.properties. Falling back to
            // an empty password and a well-known keystore name meant a fresh
            // checkout either failed much later with an opaque "keystore
            // tampered with" error, or — worse — silently signed with whatever
            // key happened to sit at that path.
            //
            // Missing values are a hard error, but only when a release build is
            // actually requested: throwing unconditionally would break debug
            // builds for anyone without a keystore.
            val releaseStoreFile = localProperties.getProperty("RELEASE_STORE_FILE")
            val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")?.takeIf { it.isNotEmpty() }
            val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }

            if (releaseStoreFile == null || releaseStorePassword == null || releaseKeyPassword == null) {
                if (isBuildingRelease) {
                    throw GradleException(
                        "Release signing is not configured. Set RELEASE_STORE_FILE, " +
                            "RELEASE_STORE_PASSWORD, RELEASE_KEY_PASSWORD (and optionally " +
                            "RELEASE_KEY_ALIAS) in local.properties."
                    )
                }
                logger.warn(
                    "Release signing is not configured in local.properties; " +
                        "release builds will fail until it is."
                )
            } else {
                // Resolve relative to the root project, which is where
                // local.properties itself is read from. `file(...)` would
                // resolve against the app module directory instead.
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS", "m365key")
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isDebuggable = false
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // Required from AGP 8 onward for the buildConfigField entries above.
        buildConfig = true
    }
    // Kotlin 2.0+ uses compose compiler plugin, no composeOptions needed
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }
}

// Make the native library part of the normal build, before anything packages
// jniLibs. verifyRustJniLibs depends on buildRustJni, so this pulls in both.
tasks.named("preBuild") {
    dependsOn(verifyRustJniLibs)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.security:security-crypto-ktx:1.1.0")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Rokid CXR-M SDK — the phone-side half of the Rokid Glasses protocol.
    // It belongs here rather than in :glass-hud: CXR-M runs on the companion
    // phone and drives the glasses; the glasses-side counterpart is CXR-S.
    // Transitive deps (Retrofit, OkHttp, ...) are declared by the SDK.
    implementation("com.rokid.cxr:client-m:1.0.4")
}
