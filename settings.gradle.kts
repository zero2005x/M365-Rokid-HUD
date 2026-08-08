pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Rokid Maven Repository - Official SDK source for CXR-M glasses connection
        // Rokid's repository is HTTPS, so isAllowInsecureProtocol was both
        // unnecessary (the flag only affects http:// URLs) and harmful: it
        // would silently permit a plaintext fetch of dependency artifacts and
        // their checksums if the URL were ever changed or redirected to HTTP.
        maven {
            url = uri("https://maven.rokid.com/repository/maven-public/")
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "M365BleApp"
include(":app")
include(":glass-hud")
