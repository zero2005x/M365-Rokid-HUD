// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Gradle 9.6.1 + AGP 9.0.0 compatible (using built-in Kotlin)
plugins {
    id("com.android.application") version "9.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    // Version catalog for subprojects. Applied here as well so `koverXmlReport`
    // (total, all variants) is available from the root; debug-only CI uses the
    // module tasks `:app:koverXmlReportDebug` and `:glass-hud:koverXmlReportDebug`
    // because the root project is not an Android module and has no debug variant.
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
}

dependencies {
    kover(project(":app"))
    kover(project(":glass-hud"))
}

kover {
    reports {
        // AGP generates R, BuildConfig, databinding, etc. Those files are not
        // in sonar.sources and would only produce "file not found" noise.
        filters.excludes.androidGeneratedClasses()
    }
}

