import java.util.Properties
import java.io.FileInputStream

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

android {
    namespace = "com.m365hud.glass"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.m365hud.glass"
        minSdk = 29  // Android 10+ (Rokid runs Android 12)
        targetSdk = 36
        // Aligned with the phone app so a joint release ships two APKs that
        // report the same versionName. versionCode must strictly increase for
        // sideloaded updates to install over an existing build.
        versionCode = 3
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // Signing details must come from local.properties. Falling back to
            // an empty password and a well-known keystore name risks silently
            // shipping a release signed with an unintended key.
            //
            // Missing values are a hard error, but only when a release build is
            // actually requested, so debug builds keep working without a
            // keystore.
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
                // local.properties is read from.
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
    // NOTE: no explicit Kotlin jvmTarget is set here. This project relies on
    // AGP's built-in Kotlin support (no org.jetbrains.kotlin.android plugin is
    // applied anywhere), so the Kotlin JVM target follows AGP's default. If a
    // "Inconsistent JVM-target compatibility" error ever appears, pin the
    // Kotlin jvmTarget to 17 using the DSL that matches the AGP version in use.
    buildFeatures {
        compose = true
    }
    // Kotlin 2.0+ uses compose compiler plugin, no composeOptions needed
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // Rokid CXR-M SDK for official glasses real-time communication
    // Provides ARTC protocol for low-latency audio/video/data streaming
    implementation("com.rokid.cxr:client-m:1.0.1-20250812.080117-2")
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
