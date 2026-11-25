pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // Build-time Tabler source archive only.
        ivy {
            url = uri("https://codeload.github.com/tabler/tabler-icons/zip/")
            patternLayout { artifact("[revision]") }
            metadataSources { artifact() }
            content { includeModule("com.tabler", "tabler-icons") }
        }
    }
}

rootProject.name = "Prism"
include(":app")
include(":baselineprofile")
