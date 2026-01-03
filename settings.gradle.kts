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
        // Rokid Maven Repository - 官方 SDK 來源
        // 如需使用 CXR-M SDK 進行官方眼鏡連線，請取消下方註解
        // maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "M365BleApp"
include(":app")
include(":glass-hud")
