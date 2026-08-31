plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish")
}

val sdkGroup = providers.gradleProperty("GROUP").get()
val sdkVersion = providers.gradleProperty("VERSION_NAME").get()

group = sdkGroup
version = sdkVersion

android {
    namespace = "ai.xmax.sdk"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "XMAX_SDK_VERSION", "\"$sdkVersion\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    api(composeBom)
    api("androidx.compose.ui:ui")

    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.volcengine:VolcEngineRTC:3.60.106.400")
    implementation("com.qcloud.cos:cos-android-lite-nobeacon:5.9.52")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

mavenPublishing {
    coordinates(sdkGroup, "xmax-sdk", sdkVersion)
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("XmaxSDK")
        description.set("Xmax real-time interactive video generation SDK for Android")
        url.set("https://github.com/XingMai/XmaxSDK-Android")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("xmax")
                name.set("XMAX.AI PTE. LTD.")
                url.set("https://xmax.ai")
            }
        }

        scm {
            url.set("https://github.com/XingMai/XmaxSDK-Android")
            connection.set("scm:git:https://github.com/XingMai/XmaxSDK-Android.git")
            developerConnection.set("scm:git:ssh://git@github.com/XingMai/XmaxSDK-Android.git")
        }
    }
}
