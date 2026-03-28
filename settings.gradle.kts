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
        maven { 
            url = uri("https://maven.rokid.com/repository/maven-public/") 
            // Allow insecure protocol if needed
            isAllowInsecureProtocol = true
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "M365BleApp"
include(":app")
include(":glass-hud")
