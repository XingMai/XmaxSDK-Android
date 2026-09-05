<h1 align="center">XmaxSDK for Android</h1>

<p align="center">
  <a href="https://developer.android.com/about/versions/oreo"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+"></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF" alt="Kotlin 2.3"></a>
  <a href="https://platform.xmaxai.com/"><img src="https://img.shields.io/badge/Realtime-AI-FF9500" alt="Realtime AI"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-4C9A2A" alt="MIT License"></a>
</p>

Native Android SDK, providing access to the real-time interactive video generation
models from Xmax AI. It supports low latency, high fidelity video transformations
driven by live video streams, reference images, and user interactions. With just a few
lines of code, developers can integrate features such as real-time character swap,
virtual try-on, mixed reality companions, and interactive image animation directly
into their apps.

<p align="center"><img src="./docs/images/xlab/generation-demo.gif" alt="X-Lab realtime generation demo" width="33%" /><img src="./docs/images/xlab/index-demo.gif" alt="X-Lab index demo" width="33%" /><img src="./docs/images/xlab/storage-demo.gif" alt="X-Lab storage demo" width="33%" /></p>

<br>

## Features

- Real-time video generation from live camera streams, still images, and local video
  files, guided by prompts, reference images, and user interactions
- In-application rendering of local media input and generated output
- Multi-touch trajectory input for controlling subject movement in generated video
  streams
- Image and video transfer through Xmax-managed object storage
- Asynchronous APIs based on Kotlin coroutines
- Jetpack Compose integration through `AndroidView`

## Requirements

- Android 8.0 (API 26) or later
- JDK 17
- Android SDK 37
- An Xmax API key

> [!WARNING]
> Do not commit an Xmax API key to version control. Supply credentials securely at
> runtime, or use a temporary key issued by the Xmax API. See
> [Authentication](https://platform.xmaxai.com/docs/authentication) for details.

## Installation

### Maven Central

Maven Central is the recommended integration method. Configure the repositories used
by the application in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://artifact.bytedance.com/repository/Volcengine/")
    }
}
```

Enable AndroidX and Jetifier in the application's `gradle.properties`:

```properties
android.useAndroidX=true
android.enableJetifier=true
```

Jetifier is currently required because VolcEngine RTC still references classes from
the legacy Android Support Library.

Declare XmaxSDK in the application module:

```kotlin
val xmaxSdkVersion = "1.0.0"

dependencies {
    implementation("ai.xmax:xmax-sdk:$xmaxSdkVersion")

    // Keeps VolcEngine RTC's legacy Support Library metadata on current AndroidX.
    implementation("androidx.appcompat:appcompat:1.7.1")
}
```

### Manual

Download `xmax-sdk-1.0.0.aar` and `SHA256SUMS` from the
[XmaxSDK 1.0.0 GitHub Release](https://github.com/XingMai/XmaxSDK-Android/releases/tag/v1.0.0),
verify the checksum, and copy the AAR to the application module:

```text
app/
└── libs/
    └── xmax-sdk-1.0.0.aar
```

Use the same repositories and AndroidX properties shown in the Maven Central
instructions, then declare the AAR and its exact third-party dependencies:

```kotlin
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    implementation(files("libs/xmax-sdk-1.0.0.aar"))
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.volcengine:VolcEngineRTC:3.60.106.400")
    implementation("com.qcloud.cos:cos-android-lite-nobeacon:5.9.52")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
```

The manual AAR does not contain third-party libraries. Keep these dependencies in the
host application and update them together with XmaxSDK when adopting a newer release.

## Permissions

For camera-based input, declare the following entries in the application manifest:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />

<uses-feature
    android:name="android.hardware.camera"
    android:required="false" />
```

The host application must obtain camera permission at runtime before creating a
camera stream. If permission is unavailable, XmaxSDK reports an `XmaxError`.

## Getting Started

### Create a client

```kotlin
import ai.xmax.sdk.RealtimeConfiguration
import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.XmaxClient
import ai.xmax.sdk.XmaxConfiguration

val client = XmaxClient(
    context = applicationContext,
    configuration = XmaxConfiguration(apiKey = "YOUR_API_KEY"),
)

val realtime = client.createRealtimeManager(
    options = RealtimeConfiguration(model = RealtimeModel.X2_0),
)
```

Realtime operations are exposed as suspending functions and should be invoked from
an application-owned, lifecycle-aware coroutine scope.

Connection-state and error listeners may be registered on the realtime manager:

```kotlin
import android.util.Log

realtime.setStateListener { state ->
    Log.i(
        "YourApp",
        "Xmax realtime state: ${state.connectionState}, " +
            "session: ${state.sessionId}, task: ${state.taskId}",
    )
}

realtime.setErrorListener { error ->
    Log.e("YourApp", "Xmax realtime error: ${error.code} ${error.message}")
}
```

### Create an input stream

After camera permission has been granted, create a live camera stream:

```kotlin
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeVideoFormat

val localStream = realtime.createLocalCameraStream(
    videoFormat = RealtimeVideoFormat(
        width = 704,
        height = 1280,
        fps = 24,
    ),
    position = CameraPosition.FRONT,
)
```

Still images and local video files can also be used as input sources:

```kotlin
val imageStream = realtime.createLocalImageStream(imageUri)
val videoStream = realtime.createLocalVideoStream(videoUri)
```

Only one input stream may be active at a time. Stop the current stream before
selecting a different input source.

### Preview the input

Use one `XmaxRealtimeVideoView` for both the input preview and generated video:

```kotlin
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxRealtimeVideoView

val realtimeVideoView = XmaxRealtimeVideoView(context).apply {
    videoContentMode = VideoContentMode.FILL
    localTrack = localStream.videoTrack
}
```

In Jetpack Compose, embed the view with `AndroidView`:

```kotlin
AndroidView(
    factory = { context -> XmaxRealtimeVideoView(context) },
    update = { view ->
        view.videoContentMode = VideoContentMode.FILL
        view.localTrack = localStream?.videoTrack
        view.remoteTrack = remoteStream?.videoTrack
    },
    onRelease = { view ->
        view.remoteTrack = null
        view.localTrack = null
    },
)
```

Set view properties on the main thread. The container keeps the local preview
mounted, waits for a rendered remote frame, and fades in the generated video.
Assigning a different remote track returns to the local preview until that track
renders; assigning `null` returns immediately. `XmaxVideoView` remains available
for applications that need to display a single track.

Stopping or disconnecting generation also restores the local preview inside the
SDK before the remote canvas is cleared, even if Compose has not updated
`remoteTrack` yet. A retained remote track waits for a fresh rendered frame on
the next generation; late callbacks from the stopped surface cannot reveal it.

### Start generation

Construct a `RealtimeContext` with a prompt and, when applicable, a remote reference
image URL:

```kotlin
import ai.xmax.sdk.RealtimeContext

val remoteStream = realtime.startGeneration(
    localStream = localStream,
    context = RealtimeContext(
        prompt = "视频中角色替换成参考图中角色",
        referencePath = referenceImageUrl,
    ),
)
```

Assign the generated track to the same container:

```kotlin
realtimeVideoView.remoteTrack = remoteStream.videoTrack
```

For each new generation, `startGeneration()` waits for the matching task SEI and
a fresh processed remote video frame, enables remote audio, and transitions to
`GENERATING` before returning. Reusing a connection still requires a fresh frame.
This state does not depend on a mounted view or completion of the view's fade-in.

To update an active generation task, submit a new context containing the revised
prompt or reference image:

```kotlin
realtime.startGeneration(
    RealtimeContext(
        prompt = "将人物服装替换成参考图中的服装",
        referencePath = anotherReferenceImageUrl,
    ),
)
```

### Stop and release resources

```kotlin
realtime.stopGeneration()
realtimeVideoView.remoteTrack = null
realtime.disconnect()
realtime.close()
realtimeVideoView.localTrack = null
```

`stopGeneration()` terminates the active generation task while retaining the remote
connection and local preview. `disconnect()` closes the remote session while
preserving the local preview. `close()` releases all local media and RTC resources
and should be called when the realtime workflow is no longer required. The view
only manages rendering: clear its remote track when generation stops, the session
disconnects, or a fatal error ends the task, and clear both tracks when leaving the
workflow. In Compose, update the corresponding stream state to `null`.

## Touch Interaction

During an active generation task, one or more pointer trajectories may be supplied
through the generated-video view to guide subject motion or initiate scene
interaction. `XmaxRealtimeVideoView` captures trajectories on the displayed remote
video and submits them to the
active task; the host application does not need to implement gesture tracking or
coordinate conversion.

Trajectory interaction is enabled by default. Disable it when touch input must be
handled by the surrounding user interface:

```kotlin
realtimeVideoView.isInteractionEnabled = false
```

## Reference Image Upload

`RealtimeContext.referencePath` requires a remote image URL. To use an on-device
image, upload it through the storage manager and supply the resulting URL:

```kotlin
val storage = client.createStorageManager()

val uploaded = storage.uploadImageFile(
    file = imageFile,
    contentType = "image/jpeg",
) { progress ->
    println(progress.fractionCompleted)
}

val referenceImageUrl = uploaded.url
```

The storage manager uses temporary credentials obtained from Xmax. Tencent Cloud
credentials are not embedded in the host application.

## Runtime Information

The SDK automatically includes the same runtime information in Xmax API requests
and RTC room signals. Applications do not need to supply it.

| Information | API request header | Field in the signal's top-level `runtime` object |
| --- | --- | --- |
| Platform (`android`) | `X-Platform` | `platform` |
| Android version (`Build.VERSION.RELEASE`) | `X-OS-Version` | `os_version` |
| SDK version (`XmaxSdk.VERSION`) | `X-SDK-Version` | `sdk_version` |
| Device model (`Build.MODEL`) | `X-Device-Model` | `device_model` |

All room events include this object: `start`, `change_condition`, `stop`, `tracks`,
and `heartbeat`. Unavailable OS version or device model values are sent as `unknown`.

## Logging

SDK logging is disabled by default and may be enabled for integration diagnostics
and runtime analysis:

```kotlin
val configuration = XmaxConfiguration(
    apiKey = "YOUR_API_KEY",
    loggerOptions = XmaxLoggerOption.all,
)
```

Enabled log entries are written to Logcat with the `XmaxSDK` tag. API keys,
authentication headers, tokens, and response bodies are excluded from log output.

## Example Project

A runnable Jetpack Compose reference application is available in
[`examples/XLab`](https://github.com/XingMai/XmaxSDK-Android/tree/main/examples/XLab).
The application demonstrates realtime generation with camera, image, and local
video inputs, together with custom prompts, reference image selection, and
trajectory rendering.

<p align="center"><img src="./docs/images/xlab/home.jpg" alt="X-Lab home" width="20%" /><img src="./docs/images/xlab/features.jpg" alt="X-Lab SDK features" width="20%" /><img src="./docs/images/xlab/storage.jpg" alt="X-Lab storage service" width="20%" /><img src="./docs/images/xlab/realtime-generation.jpg" alt="X-Lab realtime generation" width="20%" /><img src="./docs/images/xlab/trajectory-generation.jpg" alt="X-Lab trajectory generation" width="20%" /></p>

## Dependencies

- VolcEngine RTC SDK for Android provides real-time audio and video communication.
- Tencent Cloud COS SDK provides image and video transfer through object storage.

## Feedback

For bug reports and feature requests, use
[GitHub Issues](https://github.com/XingMai/XmaxSDK-Android/issues). For integration
questions and technical support, contact [sdk@xmax.ai](mailto:sdk@xmax.ai).

## License

XmaxSDK is available under the terms of the [MIT License](LICENSE).
