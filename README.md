<h1 align="center">XmaxSDK for Android</h1>

<p align="center">
  <a href="https://developer.android.com/about/versions/oreo"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+"></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF" alt="Kotlin 2.3"></a>
  <a href="https://platform.xmaxai.com/"><img src="https://img.shields.io/badge/Realtime-AI-FF9500" alt="Realtime AI"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-4C9A2A" alt="MIT License"></a>
</p>

XmaxSDK is a native Android SDK that provides access to Xmax's real-time,
interactive video generation models. It enables low-latency, cost-efficient, and
high-fidelity video transformations conditioned on reference images, text prompts,
and user interactions. With concise Kotlin APIs, developers can integrate features
such as real-time character swapping, virtual try-on, mixed reality companions, and
interactive image animation into Android applications.

<p align="center"><img src="./docs/images/xlab/generation-demo.gif" alt="X-Lab realtime generation demo" width="33%" /><img src="./docs/images/xlab/index-demo.gif" alt="X-Lab index demo" width="33%" /><img src="./docs/images/xlab/storage-demo.gif" alt="X-Lab storage demo" width="33%" /></p>

<br>

## What XmaxSDK does

XmaxSDK provides an end-to-end pipeline covering media capture, low-latency video
communication, frame-by-frame generation, and in-app rendering. Whether processing
live camera feeds, pre-recorded video, or still images, the SDK streams input to our
cloud AI engine, processes the returned video on the device, and renders the result.
With the entire workflow abstracted into simple API calls, integrating real-time
video generation is seamless and intuitive.

<br>

## What you can build with XmaxSDK

<table>
  <tr>
    <th width="25%" align="left">Realtime Use Case</th>
    <th width="75%" align="left">Description</th>
  </tr>
  <tr>
    <td width="25%" valign="middle"><strong>Character Swapping</strong></td>
    <td width="75%" valign="middle">
      Replace anyone in your live feed with a designated avatar in real time.
      <br><br>
      <strong>Prompt:</strong> <code>视频中角色替换成参考图中角色</code>
      <br><br>
      <strong>Reference image:</strong> Select a clear image of the desired character with a clean background.
    </td>
  </tr>
  <tr>
    <td width="25%" valign="middle"><strong>Virtual Try-On</strong></td>
    <td width="75%" valign="middle">
      Seamlessly change outfits while preserving body shape, natural motion, and an authentic fit.
      <br><br>
      <strong>Prompt:</strong> <code>视频中人物衣服替换成参考图中衣服</code>
      <br><br>
      <strong>Reference image:</strong> Select a clear image of the target outfit with a clean background.
    </td>
  </tr>
  <tr>
    <td width="25%" valign="middle"><strong>Video Restyling</strong></td>
    <td width="75%" valign="middle">
      Reimagine your world in any style with an immersive visual experience.
      <br><br>
      <strong>Prompt:</strong> <code>视频风格变为参考图指定的风格</code>
      <br><br>
      <strong>Reference image:</strong> Select an image that captures the artistic style you want to apply.
    </td>
  </tr>
  <tr>
    <td width="25%" valign="middle"><strong>AI Companions</strong></td>
    <td width="75%" valign="middle">
      Summon virtual characters into your live camera feed and interact with them through gestures.
      <br><br>
      <strong>Prompt:</strong> <code>指定角色在场景中互动</code>
      <br><br>
      <strong>Reference image:</strong> Select a clear image of the virtual character you want to summon with a clean background.
    </td>
  </tr>
  <tr>
    <td width="25%" valign="middle"><strong>Live Photo</strong></td>
    <td width="75%" valign="middle">
      Animate and control characters in your images by drawing motion trajectories.
      <br><br>
      <strong>Prompt:</strong> <code>让画面自然动起来</code>
      <br><br>
      <strong>Reference image:</strong> Use the input image as the reference image.
    </td>
  </tr>
</table>

<br>

## Why XmaxSDK?

<table>
  <thead>
    <tr>
      <th width="33%" align="center">Low latency</th>
      <th width="33%" align="center">Cost efficiency</th>
      <th width="34%" align="center">High fidelity</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>End-to-end latency is measured in hundreds of milliseconds, so changes to generation conditions and interaction controls are reflected quickly.</td>
      <td>Models can run on a single RTX 5090, reducing inference costs by orders of magnitude compared with datacenter GPUs such as the H100.</td>
      <td>Our models support real-time generation at up to 1080p, delivering production-ready, high-quality video output.</td>
    </tr>
  </tbody>
</table>

<br>

## Prerequisites

- Android 8.0 (API level 26) or later
- Kotlin 2.3 and JDK 17
- Android SDK 37 for building from source
- An Xmax API key

> [!WARNING]
> Never commit your Xmax API key to version control. Pass it securely at runtime or
> use short-lived temporary keys issued by the Xmax API. For step-by-step
> instructions, see [Authentication](https://platform.xmaxai.com/docs/authentication).

<br>

## Installation

XmaxSDK supports [**Maven Central**](#maven-central) and
[**manual AAR integration**](#manual) on Android. Maven Central is recommended.

### Maven Central

Configure the repositories used by your application in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://artifact.bytedance.com/repository/Volcengine/")
    }
}
```

Enable AndroidX and Jetifier in your application's `gradle.properties`:

```properties
android.useAndroidX=true
android.enableJetifier=true
```

Jetifier is currently required because VolcEngine RTC contains references to the
legacy Android Support Library.

Add XmaxSDK to your application module:

```kotlin
dependencies {
    implementation("ai.xmax:xmax-sdk:1.0.2")

    // Required for compatibility with VolcEngine RTC's legacy support references.
    implementation("androidx.appcompat:appcompat:1.7.1")
}
```

### Manual

Download
[`xmax-sdk-1.0.2.aar`](https://github.com/XingMai/XmaxSDK-Android/releases/download/1.0.2/xmax-sdk-1.0.2.aar)
from the GitHub Release and copy it into your application module:

```text
app/
└── libs/
    └── xmax-sdk-1.0.2.aar
```

Use the same repositories and AndroidX properties shown above, then add the AAR and
its third-party dependencies:

```kotlin
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    implementation(files("libs/xmax-sdk-1.0.2.aar"))
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

The AAR does not bundle third-party libraries. Keep these dependencies in the host
application and update them together with XmaxSDK when adopting a newer release.

<br>

## Quick Start

### Configure permissions

Declare internet and camera access in your application's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />

<uses-feature
    android:name="android.hardware.camera"
    android:required="false" />
```

Your application must request camera permission at runtime before creating a camera
stream. XmaxSDK throws an `XmaxError` if permission is denied or unavailable.

<br>

### Generate and display video

The following snippet creates a camera stream, starts real-time generation, and
binds the output to a video view. Call suspending SDK APIs from an application-owned,
lifecycle-aware coroutine scope.

```kotlin
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeConfiguration
import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxClient
import ai.xmax.sdk.XmaxConfiguration
import ai.xmax.sdk.XmaxRealtimeVideoView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

val client = XmaxClient(
    context = applicationContext,
    configuration = XmaxConfiguration(apiKey = "YOUR_XMAX_API_KEY"),
)

val realtime = client.createRealtimeManager(
    options = RealtimeConfiguration(model = RealtimeModel.X2_0),
)

val videoView = XmaxRealtimeVideoView(this).apply {
    videoContentMode = VideoContentMode.FILL
}
setContentView(videoView)

lifecycleScope.launch {
    val localStream = realtime.createLocalCameraStream(
        videoFormat = RealtimeVideoFormat(width = 704, height = 1280, fps = 24),
        position = CameraPosition.FRONT,
    )
    videoView.localTrack = localStream.videoTrack

    val remoteStream = realtime.startGeneration(
        localStream = localStream,
        context = RealtimeContext(
            prompt = "视频中角色替换成参考图中角色",
            referencePath = "https://platform.xmaxai.com/images/source/charx/chatx_image1.jpg",
        ),
    )
    videoView.remoteTrack = remoteStream.videoTrack
}
```

`XmaxRealtimeVideoView` displays the local camera preview until the first generated
frame arrives, then transitions to the remote video. Touch interaction is enabled by
default after the generated video becomes visible.

Still images and local videos can also be used as input:

```kotlin
val imageStream = realtime.createLocalImageStream(imageUri)
val videoStream = realtime.createLocalVideoStream(videoUri)
```

Only one local input stream can be active at a time.

<br>

### Using Jetpack Compose

Embed `XmaxRealtimeVideoView` with `AndroidView` and update its tracks as your state
changes:

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

Keep stream objects in lifecycle-aware Compose state and set view properties on the
main thread. The same view handles the local preview, remote first-frame transition,
and generated-video touch trajectories.

<br>

### Listen for events

After creating `realtime`, register the listeners you need before creating the input
stream or starting generation.

| Listener | Purpose |
| --- | --- |
| `setStateListener` | Observe pipeline states during real-time generation. |
| `setErrorListener` | Handle fatal errors that prevent the realtime workflow from continuing. |
| `setCameraPreviewReadyListener` | Receive notification when the first local camera frame is ready for preview. |
| `setRemoteVideoFrameListener` | Receive generated I420 frames for recording or custom processing. |
| `setNetworkQualityListener` | Monitor uplink and downlink network quality. |
| `setPerformanceAlarmListener` | Detect device performance limitations or recovery, with a suggested video format when available. |

For example, monitor state changes and errors:

```kotlin
realtime.setStateListener { state ->
    println("State: ${state.connectionState.value}")
}

realtime.setErrorListener { error ->
    println("Error: ${error.code} ${error.message}")
}
```

Listeners are delivered on the main thread, except remote video frame callbacks,
which run serially on an SDK background dispatcher.

<br>

### Upload a reference image

`RealtimeContext.referencePath` accepts a remote image URL. Upload an on-device image
through the storage manager before starting generation:

```kotlin
val storage = client.createStorageManager()

val uploaded = storage.uploadImageFile(
    file = imageFile,
    contentType = "image/jpeg",
) { progress ->
    println(progress.fractionCompleted)
}

val context = RealtimeContext(
    prompt = "视频中角色替换成参考图中角色",
    referencePath = uploaded.url,
)
```

The storage manager obtains temporary credentials from Xmax. Tencent Cloud
credentials are not embedded in the host application.

<br>

### Resource cleanup

- **`disconnect()` — Stop remote generation**

  Stops remote generation and cancels billing while keeping the local media stream
  and preview active. Use it when ending the online session but staying on the same
  screen. You can start a new session later with the same local stream:

  ```kotlin
  realtime.disconnect()
  ```

- **`close()` — Full teardown and release**

  Ends the remote session, stops local media capture, and releases all RTC resources.
  Use it when leaving the generation screen:

  ```kotlin
  realtime.close()
  videoView.remoteTrack = null
  videoView.localTrack = null
  ```

> **Note:** These methods are alternatives, not sequential steps. When exiting a
> screen, call `close()` directly—there is no need to call `disconnect()` first.

<br>

> [!TIP]
> For complete examples covering camera, image, and video inputs, reference image
> upload, custom prompts, touch interaction, recording, and lifecycle handling, see
> the [example project](#example-project).

<br>

## Example Project

A complete Jetpack Compose example application is available in
[`examples/XLab`](https://github.com/XingMai/XmaxSDK-Android/tree/main/examples/XLab).
It demonstrates real-time generation using live camera feeds, static images, and
local video files, along with storage operations, reference image selection, custom
prompts, trajectory interaction, and generated-video recording.

<p align="center"><img src="./docs/images/xlab/home.jpg" alt="X-Lab home" width="20%" /><img src="./docs/images/xlab/features.jpg" alt="X-Lab SDK features" width="20%" /><img src="./docs/images/xlab/storage.jpg" alt="X-Lab storage service" width="20%" /><img src="./docs/images/xlab/realtime-generation.jpg" alt="X-Lab realtime generation" width="20%" /><img src="./docs/images/xlab/trajectory-generation.jpg" alt="X-Lab trajectory generation" width="20%" /></p>

<br>

## Dependencies

- <ins><strong>VolcEngine RTC SDK for Android</strong></ins> enables low-latency, real-time audio and video communication.
- <ins><strong>Tencent Cloud COS SDK for Android</strong></ins> handles media upload and download through object storage.

<br>

## Contact us

For bug reports and feature requests, please open a
[GitHub Issue](https://github.com/XingMai/XmaxSDK-Android/issues). For integration
assistance and technical support, contact us at [sdk@xmax.ai](mailto:sdk@xmax.ai).

<br>

## License

XmaxSDK is available under the terms of the [MIT License](LICENSE).
