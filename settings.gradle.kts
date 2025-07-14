@file:Suppress("UnstableApiUsage") // Bu satır @Incubating uyarılarını gizler

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
        // PhotoView GİBİ GITHUB KÜTÜPHANELERİ İÇİN GEREKLİ REPO
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AcilNotUygulamasi"
include(":app")