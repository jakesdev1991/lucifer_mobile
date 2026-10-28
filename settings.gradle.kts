pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application") {
                useModule("com.android.tools.build:gradle:8.7.3")
            }
            if (requested.id.id == "org.jetbrains.kotlin.android") {
                            useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
                        }
                        if (requested.id.id == "org.jetbrains.kotlin.multiplatform") {
                            useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
                        }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LuciferMobile"

include(":composeApp")
include(":native")