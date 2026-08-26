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
        maven("https://artifact.bytedance.com/repository/Volcengine/")
    }
}

rootProject.name = "XmaxSDK"

include(":xmax-sdk")
include(":examples:XLab")
