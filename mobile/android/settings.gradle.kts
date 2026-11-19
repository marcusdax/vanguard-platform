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
    }
}

rootProject.name = "VanguardField"

include(":app")
include(":core:model")
include(":core:crypto")
include(":core:design")
include(":core:data")
include(":feature:ground")
include(":feature:jobs")
include(":feature:capture")
include(":feature:vault")
include(":feature:payouts")
include(":feature:brief")
include(":feature:spaces")
include(":feature:vanguard")