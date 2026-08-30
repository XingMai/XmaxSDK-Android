<h1 align="center">XmaxSDK for Android</h1>

<p align="center">
  <a href="https://developer.android.com/about/versions/oreo"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+"></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF" alt="Kotlin 2.3"></a>
  <a href="https://platform.xmaxai.com/"><img src="https://img.shields.io/badge/Realtime-AI-FF9500" alt="Realtime AI"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-4C9A2A" alt="MIT License"></a>
</p>

Native Android SDK, providing access to the real-time interactive video generation
models from Xmax AI. It enables low-latency, high-fidelity video transformations
using live video streams, reference images, and user interactions.
With just a few lines of code, developers can integrate features such as
real-time character swap, virtual try-on, mixed-reality companions,
and interactive image animation directly into their apps.

<p align="center"><img src="./docs/images/xlab/generation-demo.gif" alt="X-Lab realtime generation demo" width="33%" /><img src="./docs/images/xlab/index-demo.gif" alt="X-Lab index demo" width="33%" /><img src="./docs/images/xlab/storage-demo.gif" alt="X-Lab storage demo" width="33%" /></p>

<br>

## Features

- Generate AI video in real time from a live camera, a still image, or a local video
- Use custom prompts and reference images for character swap, virtual try-on, mixed-reality companions, and interactive image animation
- Preview your media input and generated output directly in your app
- Control subject movement and scene interactions with intuitive multi-touch gestures
- Upload on-device images to object storage for use in generation workflows
- Kotlin coroutine-based APIs

## Requirements

- Android 8.0 (API 26) or later
- JDK 17
- Android SDK 37
- Android Studio Quail or a compatible newer version
- An Xmax API key

## Installation

XmaxSDK for Android is currently integrated as a source module. Clone this repository
next to your application project, then register the SDK module in
`settings.gradle.kts`:

```kotlin
include(":xmax-sdk")
project(":xmax-sdk").projectDir = file("../XmaxSDK-Android/xmax-sdk")
```

Add the module dependency to the application:

```kotlin
dependencies {
    implementation(project(":xmax-sdk"))
}
```

The SDK module uses Android Gradle Plugin 9.3.1 and Kotlin 2.3.21. Declare compatible
plugins in the root `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
```

Add the repositories required by the SDK and its dependencies:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://artifact.bytedance.com/repository/Volcengine/")
    }
}
```

`mavenCentral()` is used to resolve third-party dependencies. XmaxSDK itself is not
currently distributed through Maven Central.

## Permissions

Camera input requires the following manifest entries:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />

<uses-feature
    android:name="android.hardware.camera"
    android:required="false" />
```

The host application must request camera permission at runtime before creating a
camera stream. XmaxSDK checks the permission and returns an `XmaxError` when it has
not been granted.

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

Realtime operations are suspending functions. Call them from a lifecycle-aware
coroutine scope owned by your application.

### Create an input stream

Use a granted camera permission to create a live camera stream:

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

You can also use a still image or local video as the input:

```kotlin
val imageStream = realtime.createLocalImageStream(imageUri)
val videoStream = realtime.createLocalVideoStream(videoUri)
```

Only one input stream can be active at a time. Stop the current stream before
switching to another input source.

### Preview the input

Bind the local stream to an `XmaxVideoView`:

```kotlin
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxVideoView

val localVideoView = XmaxVideoView(context).apply {
    videoContentMode = VideoContentMode.FILL
    track = localStream.videoTrack
}
```

In Jetpack Compose, host the view with `AndroidView`:

```kotlin
AndroidView(
    factory = { context -> XmaxVideoView(context) },
    update = { view ->
        view.videoContentMode = VideoContentMode.FILL
        view.track = localStream.videoTrack
    },
)
```

### Start generation

Provide a prompt and, when needed, the URL of a reference image:

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

The returned stream contains the generated video. Display it with another
`XmaxVideoView`:

```kotlin
val remoteVideoView = XmaxVideoView(context).apply {
    videoContentMode = VideoContentMode.FILL
    track = remoteStream.videoTrack
}
```

To apply a different effect while generation is active, submit another context with
a new prompt or reference image:

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
realtime.disconnect()
realtime.close()
```

`stopGeneration()` keeps the current connection and local preview available.
`disconnect()` closes the remote session but preserves the local preview. Call
`close()` when leaving the feature to release all local media and RTC resources.

## Touch Interaction

During an active generation task, users can drag one or more fingers across the
generated video to guide the subject's movement or trigger scene interaction.
`XmaxVideoView` handles the touch path and sends it to the active task automatically,
so the application does not need to implement gesture tracking or coordinate mapping.

Interaction is enabled by default and can be disabled when the surrounding UI needs
to handle touch input itself:

```kotlin
remoteVideoView.isInteractionEnabled = false
```

## Reference Image Upload

A local reference image must be uploaded before its URL can be passed to
`RealtimeContext`. Create a storage manager and use the returned URL:

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

Uploads use temporary credentials requested from Xmax. No Tencent Cloud secret is
embedded in the application.

## Logging

Logging is disabled by default. Enable it when diagnosing integration or runtime
issues:

```kotlin
val configuration = XmaxConfiguration(
    apiKey = "YOUR_API_KEY",
    loggerOptions = XmaxLoggerOption.all,
)
```

Enabled entries are written to Logcat with the `XmaxSDK` tag. API keys, authentication
headers, tokens, and response bodies are not logged.

## Example Project

A runnable Jetpack Compose example is available in
[`examples/XLab`](https://github.com/XingMai/XmaxSDK-Android/tree/main/examples/XLab).
It demonstrates camera, image, and local video input; prompt and reference-image
effects; touch interaction; and reference-image upload.

<p align="center"><img src="./docs/images/xlab/home.jpg" alt="X-Lab home" width="20%" /><img src="./docs/images/xlab/features.jpg" alt="X-Lab SDK features" width="20%" /><img src="./docs/images/xlab/storage.jpg" alt="X-Lab storage service" width="20%" /><img src="./docs/images/xlab/realtime-generation.jpg" alt="X-Lab realtime generation" width="20%" /><img src="./docs/images/xlab/trajectory-generation.jpg" alt="X-Lab trajectory generation" width="20%" /></p>

<br>

Build the example from the repository root:

```bash
./gradlew :examples:XLab:assembleDebug
```

The generated APK is written to
`examples/XLab/build/outputs/apk/debug/XLab-debug.apk`.

## Dependencies

- [VolcEngineRTC `3.60.106.400`](https://www.volcengine.com/product/veRTC) provides
  real-time audio and video communication.
- [Tencent Cloud COS Android SDK `5.9.52`](https://cloud.tencent.com/document/product/436)
  provides reference-image upload.

## Distribution

- Source-module integration is currently supported.
- Maven Central distribution is not currently supported.

## Security

Never commit or hard-code a production Xmax API key. The XLab example encrypts its
development key with Android Keystore for local testing, but production applications
remain responsible for secure key provisioning and storage.

## Feedback

Please use [GitHub Issues](https://github.com/XingMai/XmaxSDK-Android/issues) for bug
reports and feature requests.

## License

XmaxSDK is available under the terms of the [MIT License](LICENSE).
