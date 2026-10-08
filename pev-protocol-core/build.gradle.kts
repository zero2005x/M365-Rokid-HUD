plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
}

group = "io.github.zero2005x.pev"
version = "0.1.0-SNAPSHOT"

// Pure Kotlin/JVM, MIT. No Android, Compose, JNI or GPL dependency may be added.
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test { useJUnit() }

