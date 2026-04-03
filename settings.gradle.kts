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
        google()
        mavenCentral()
        // Zebra EMDK (com.symbol:emdk) — same as mydevicesandroid
        maven(url = "https://zebratech.jfrog.io/artifactory/EMDK-Android/")
    }
}

rootProject.name = "MyDevice"
include(":app")
 