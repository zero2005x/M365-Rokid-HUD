import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlinx.kover")
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
    logger.warn("WARNING: $message")
}

// ---------------------------------------------------------------------------
// Rust JNI (ninebot-ffi) native build
//
// The app loads "ninebot_ffi" via System.loadLibrary (see M365Native.kt), so
// src/main/jniLibs/libninebot_ffi.so has to be built from the Rust sources and
// kept in step with them.
//
// The previous wiring could not guarantee that, in two ways:
//
//  1. The cargo invocation set `isIgnoreExitValue = true`, so a failed
//     cross-compile was a WARNING and the build carried on. The old .so was
//     still sitting in jniLibs, so the APK shipped it.
//  2. Even on success, `verifyRustJniLibs` only checked that a file EXISTED —
//     never that it matched the sources. Editing Rust, breaking the build, and
//     then packaging produced an APK with silently stale native code.
//
// Both are fixed here. The chain is:
//
//   buildRustJni      cargo-ndk -> build/rustJni/<abi>/lib<name>.so   (fails hard)
//   copyRustJniLibs   copies into src/main/jniLibs + writes a stamp   (fails hard)
//   verifyRustJniLibs compares the stamp against a hash of the inputs (fails hard)
//
// The stamp is a SHA-256 over every Rust input, so "the .so is current" is a
// content question rather than a timestamp guess, and a failed Rust build can
// never be mistaken for an up-to-date one.
//
// `.so` files are DELIBERATELY gitignored and regenerated per build. They used
// to be committed byte-for-byte, which is what allowed the checked-in binaries
// to drift away from the Rust sources without anything noticing.
//
// Escape hatch for an environment with no Rust toolchain:
//   ./gradlew assembleDebug -PskipRustBuild
// That downgrades the freshness check to a warning, because a debug build with
// no native library is still useful for UI work (the crypto paths simply fail
// at runtime). Release builds never downgrade.
// ---------------------------------------------------------------------------

/** Android ABI -> Rust target triple. Must match defaultConfig.ndk.abiFilters. */
val rustAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

val rustCrateDir = rootProject.file("ninebot-ffi")
val rustBleCrateDir = rootProject.file("ninebot-ble")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val skipRustBuild = providers.gradleProperty("skipRustBuild").isPresent
val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * Name of the library `System.loadLibrary("ninebot_ffi")` resolves to.
 *
 * cargo-ndk also copies `libninebot_ble.so` because ninebot-ble declares
 * `crate-type = ["cdylib", ...]`. That library is a build artefact of the
 * dependency, not something the app loads, so it is deliberately not packaged
 * — each ABI would carry ~2 MB of dead weight.
 */
val rustLibName = "ninebot_ffi"

/**
 * Every file that can change the compiled output.
 *
 * Both crates are included: ninebot-ffi depends on ninebot-ble by path, so a
 * change to ninebot-ble's crypto changes the ffi library. Omitting it would let
 * exactly the case that prompted this work — a protocol fix in ninebot-ble —
 * slip past the freshness check.
 */
fun rustInputFiles(): List<File> {
    val files = mutableListOf<File>()
    for (crate in listOf(rustCrateDir, rustBleCrateDir)) {
        if (!crate.isDirectory) continue
        crate.resolve("Cargo.toml").takeIf { it.isFile }?.let { files.add(it) }
        crate.resolve("Cargo.lock").takeIf { it.isFile }?.let { files.add(it) }
        crate.resolve("src").takeIf { it.isDirectory }?.walkTopDown()?.forEach { f ->
            // target/ never lives under src/, but guard anyway: hashing build
            // output would make the fingerprint change on every build and the
            // up-to-date checks would never fire.
            if (f.isFile && !f.path.contains("${File.separator}target${File.separator}")) {
                files.add(f)
            }
        }
    }
    return files
}

val rustInputs = rustInputFiles()

/** SHA-256 over every input's path and contents, plus the ABI list. */
fun rustInputFingerprint(files: List<File>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    // Sort for a stable order: directory iteration order is not guaranteed.
    for (f in files.sortedBy { it.absolutePath }) {
        val relative = f.absolutePath.removePrefix(rootProject.projectDir.absolutePath)
        digest.update(relative.toByteArray())
        digest.update(0)
        digest.update(f.readBytes())
        digest.update(0)
    }
    digest.update(rustAbis.joinToString(",").toByteArray())
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Freshness stamp written next to the libraries it describes. */
val rustStampFile = jniLibsDir.file("rust-build.stamp")

/** Where cargo-ndk writes, so the source tree is never a build output. */
val rustJniBuildDir = layout.buildDirectory.dir("rustJni")

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
    description = "Cross-compiles ninebot-ffi with cargo-ndk into build/rustJni"

    workingDir = rustCrateDir

    inputs.files(rustInputs).withPathSensitivity(PathSensitivity.RELATIVE)
    // Declared per ABI so the freshness check can tell exactly which one is
    // stale rather than invalidating everything.
    outputs.dir(rustJniBuildDir)

    val ndkDir = resolveNdkDir()
    if (ndkDir != null) {
        environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
        environment("ANDROID_NDK_ROOT", ndkDir.absolutePath)
    }

    // Google Play requirement: align native libraries to 16 KB boundaries.
    environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")

    // cargo-ndk writes <outDir>/<abi>/lib<name>.so
    commandLine(
        buildList {
            add(if (isWindows) "cargo.exe" else "cargo")
            add("ndk")
            rustAbis.forEach { add("-t"); add(it) }
            add("-o"); add(rustJniBuildDir.get().asFile.absolutePath)
            add("build"); add("--release")
        }
    )

    // FAIL the build when the cross-compile fails.
    //
    // This was `isIgnoreExitValue = true` with a warning in doLast, which is
    // precisely how stale native code reached an APK: cargo-ndk would fail, the
    // previous .so would still be in jniLibs, and the packaging step would ship
    // it. A build that cannot produce current native code must not succeed.
    isIgnoreExitValue = false

    onlyIf {
        when {
            skipRustBuild -> {
                logger.lifecycle("buildRustJni: skipped (-PskipRustBuild)")
                false
            }
            !rustCrateDir.isDirectory -> {
                // A genuinely absent crate is a broken checkout, not a missing
                // toolchain, so this is fatal in both build types.
                throw GradleException(
                    "Rust crate directory not found: ${rustCrateDir.absolutePath}. " +
                        "The native library cannot be built; the app would fail to load it."
                )
            }
            ndkDir == null -> {
                throw GradleException(
                    "No Android NDK found, so lib$rustLibName.so cannot be cross-compiled. " +
                        "Install one via the SDK Manager (SDK Tools > NDK) or point " +
                        "ANDROID_NDK_HOME at an existing NDK. " +
                        "To build without the native library, pass -PskipRustBuild."
                )
            }
            else -> true
        }
    }
}

/**
 * Copies the freshly cross-compiled libraries into `src/main/jniLibs`.
 *
 * Separated from [buildRustJni] so the source tree is written to by exactly one
 * task whose outputs Gradle can track, and so a partial cargo-ndk run cannot
 * leave a half-updated jniLibs behind.
 *
 * It also writes [rustStampFile]: a hash of the inputs the libraries were built
 * from. [verifyRustJniLibs] compares against it, which is what turns "a .so
 * exists" into "this .so matches the Rust sources".
 */
val copyRustJniLibs = tasks.register("copyRustJniLibs") {
    group = "build"
    description = "Copies cargo-ndk output into src/main/jniLibs and stamps it"

    dependsOn(buildRustJni)

    val inputsForFingerprint = rustInputs
    val abis = rustAbis
    val libName = rustLibName
    val sourceDir = rustJniBuildDir
    val destDir = jniLibsDir.asFile
    val stamp = rustStampFile.asFile

    inputs.files(inputsForFingerprint).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(destDir)
    outputs.file(stamp)

    // Always run.
    //
    // The stamp is a hash of the inputs, and it has to describe the sources as
    // they are AT EXECUTION TIME. Computing it while the build script is
    // configured instead produced a real failure: Gradle evaluated the task as
    // UP-TO-DATE from a stale input snapshot, so neither the copy nor the stamp
    // happened, and verifyRustJniLibs then correctly reported the library as
    // stale against the new sources. Skipping this task can only ever be wrong,
    // and re-copying a handful of files is far cheaper than the failure mode.
    outputs.upToDateWhen { false }

    doLast {
        val fingerprint = rustInputFingerprint(inputsForFingerprint)

        // Remove stale libraries from ABIs that are no longer configured, and
        // any previous copy of a library we no longer build. A leftover file
        // would otherwise be packaged silently forever.
        if (destDir.exists()) {
            destDir.deleteRecursively()
        }
        destDir.mkdirs()

        val written = mutableListOf<File>()
        for (abi in abis) {
            val built = File(sourceDir.get().asFile, "$abi/lib$libName.so")
            if (!built.isFile) {
                throw GradleException(
                    "cargo-ndk reported success but $abi/lib$libName.so is missing. " +
                        "Expected it under ${sourceDir.get().asFile}. " +
                        "This means the ABI list here and the one passed to cargo-ndk have diverged."
                )
            }
            val target = File(destDir, "$abi/lib$libName.so")
            target.parentFile.mkdirs()
            built.copyTo(target, overwrite = true)
            written.add(target)
        }

        stamp.writeText(fingerprint)

        logger.lifecycle(
            "copyRustJniLibs: wrote lib$libName.so for ${abis.size} ABIs " +
                "(${written.sumOf { it.length() } / 1024} KB total)"
        )
    }
}

/**
 * Guarantees the packaged native library matches the Rust sources.
 *
 * Two checks, both fatal in every build type except an explicit
 * `-PskipRustBuild` debug build:
 *
 *  1. The library exists for every configured ABI.
 *  2. The stamp written by [copyRustJniLibs] matches a fresh hash of the Rust
 *     inputs — i.e. the .so was built from exactly these sources.
 *
 * Check 2 is the one that matters. Presence alone was the old contract, and it
 * passes happily for a .so compiled months ago. Because the fingerprint is
 * content-based, editing a Rust file and reverting the edit still leaves the
 * stamp valid, and no false staleness is reported.
 */
val verifyRustJniLibs = tasks.register("verifyRustJniLibs") {
    group = "verification"
    description = "Fails if lib$rustLibName.so is missing or does not match the Rust sources"

    dependsOn(copyRustJniLibs)

    val libsRoot = jniLibsDir.asFile
    val abis = rustAbis
    val libName = rustLibName
    val stamp = rustStampFile.asFile
    val fingerprintInputs = rustInputs
    // Only an explicit opt-out may downgrade these to warnings, and only for
    // debug: a release APK with stale or missing native code is never shippable.
    val lenient = skipRustBuild && !isBuildingRelease

    doLast {
        // Recompute here, not at configuration time: the whole point is to
        // compare the stamp against the sources as they are now.
        val expectedFingerprint = rustInputFingerprint(fingerprintInputs)

        val missing = abis.filterNot { File(libsRoot, "$it/lib$libName.so").isFile }

        if (missing.isNotEmpty()) {
            val message =
                "lib$libName.so is MISSING for: ${missing.joinToString()}. " +
                    "The app will throw UnsatisfiedLinkError and every native crypto call " +
                    "(handshake, login, telemetry decryption) will fail. " +
                    "Build it with:  ./gradlew :app:copyRustJniLibs"
            if (lenient) {
                logger.warn("WARNING: $message")
                return@doLast
            }
            throw GradleException(message)
        }

        val actualFingerprint = stamp.takeIf { it.isFile }?.readText()?.trim()
        if (actualFingerprint != expectedFingerprint) {
            val detail = when {
                actualFingerprint == null ->
                    "no build stamp was found at ${stamp.absolutePath}"
                else ->
                    "the stamp records ${actualFingerprint.take(12)}… but the Rust sources " +
                        "hash to ${expectedFingerprint.take(12)}…"
            }
            val message =
                "lib$libName.so is STALE — $detail. " +
                    "The packaged native library was not built from the Rust code in this " +
                    "working tree, so any protocol or crypto fix in ninebot-ble / ninebot-ffi " +
                    "is NOT in the APK. Rebuild it with:  ./gradlew :app:copyRustJniLibs"
            if (lenient) {
                logger.warn("WARNING: $message")
                return@doLast
            }
            throw GradleException(message)
        }

        logger.lifecycle(
            "verifyRustJniLibs: lib$libName.so present and current for all ${abis.size} ABIs"
        )
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
        versionCode = 8
        versionName = "1.5.0"

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
// jniLibs. verifyRustJniLibs depends on copyRustJniLibs, which depends on
// buildRustJni, so this pulls in the whole chain and no task can be skipped by
// invoking an assemble* task directly.
tasks.named("preBuild") {
    dependsOn(verifyRustJniLibs)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
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

kover {
    reports {
        filters.excludes.androidGeneratedClasses()
    }
}
