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
    }
}

rootProject.name = "XmaxSDK"

include(":xmax-sdk")
include(":examples:XLab")

// 可选播放器适配组件；SDK 核心不依赖 Media3。
include(":xmax-media3")
