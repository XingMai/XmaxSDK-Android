<p align="center">
  <img src="./docs/images/brand/xmax-sdk.png" alt="XmaxSDK — Realtime Interactive Video Generation" width="880">
</p>

<p align="center">
  <a href="https://developer.android.com/about/versions/oreo"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+"></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF" alt="Kotlin 2.3"></a>
  <a href="https://platform.xmaxai.com/"><img src="https://img.shields.io/badge/Realtime-AI-FF9500" alt="Realtime AI"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-4C9A2A" alt="MIT License"></a>
</p>

Native Android SDK, providing access to Xmax's real-time, interactive video
generation models. The models are optimized for low latency and cost efficiency,
enabling instantaneous video transformations across diverse characters, outfits,
and aesthetic styles. Also, they can dynamically respond to user gestures, allowing
interactive virtual subjects to blend into real-world footage for immersive
experiences. XmaxSDK implements an end-to-end pipeline to leverage these novel
capabilities through concise Kotlin APIs, making it easy for developers to build
next-generation interactive video experiences within the Android ecosystem.

<p align="center"><img src="./docs/images/xlab/generation-demo.gif" alt="X-Lab realtime generation demo" width="33%" /><img src="./docs/images/xlab/index-demo.gif" alt="X-Lab index demo" width="33%" /><img src="./docs/images/xlab/storage-demo.gif" alt="X-Lab storage demo" width="33%" /></p>

<br>

## What XmaxSDK does

XmaxSDK offers a complete workflow that covers media acquisition, low-latency video
communication, frame-by-frame generation, and in-app rendering. Whether processing
live camera feeds, pre-recorded video, or still images, it streams media to our cloud
inference service, applies on-device enhancement to the returned video, and renders
the result to screen. With the entire workflow abstracted into simple API calls,
integrating real-time video generation is seamless and intuitive.

<br>

## What you can build with XmaxSDK

<table>
  <tr>
    <th width="24%" align="left">Realtime Use Case</th>
    <th width="60%" align="left">Description</th>
    <th width="16%" align="center">Demo</th>
  </tr>
  <tr>
    <td rowspan="2" width="24%" valign="middle">
      <strong>Character Swapping</strong>
    </td>
    <td width="60%" valign="middle">
      Replace anyone in your live feed with a designated avatar in real-time.
    </td>
    <td rowspan="2" width="16%" align="center" valign="middle">
      <a href="./docs/videos/use-cases/character-swapping.mp4">
        <img src="./docs/images/use-cases/character-swapping-poster.png" alt="Play the Character Swapping demo" width="120">
        <br>
        <sub>▶ Play demo</sub>
      </a>
    </td>
  </tr>
  <tr>
    <td width="60%" valign="middle">
      <strong>Prompt:</strong> <code>视频中角色替换成参考图中角色</code>
      <br><br>
      <strong>Reference image:</strong> Select a clear image of the desired character with a clean background.
    </td>
  </tr>
  <tr>
    <td rowspan="2" width="24%" valign="middle">
      <strong>Virtual Try-On</strong>
    </td>
    <td width="60%" valign="middle">
      Seamlessly change outfits, preserving exact body shape, natural motion, and an
      authentic fit.
    </td>
    <td rowspan="2" width="16%" align="center" valign="middle">
      <a href="./docs/videos/use-cases/virtual-try-on.mp4">
        <img src="./docs/images/use-cases/virtual-try-on-poster.png" alt="Play the Virtual Try-On demo" width="120">
        <br>
        <sub>▶ Play demo</sub>
      </a>
    </td>
  </tr>
  <tr>
    <td width="60%" valign="middle">
      <strong>Prompt:</strong> <code>视频中人物衣服替换成参考图中衣服</code>
      <br><br>
      <strong>Reference image:</strong> Select a clear image of the target outfit with a clean background.
    </td>
  </tr>
  <tr>
    <td rowspan="2" width="24%" valign="middle">
      <strong>Video Restyling</strong>
    </td>
    <td width="60%" valign="middle">
      Reimagine your world in any style with an immersive visual experience.
    </td>
    <td rowspan="2" width="16%" align="center" valign="middle">
      <a href="./docs/videos/use-cases/video-restyling.mp4">
        <img src="./docs/images/use-cases/video-restyling-poster.png" alt="Play the Video Restyling demo" width="120">
        <br>
        <sub>▶ Play demo</sub>
      </a>
    </td>
  </tr>
  <tr>
    <td width="60%" valign="middle">
      <strong>Prompt:</strong> <code>视频风格变为参考图指定的风格</code>
      <br><br>
      <strong>Reference image:</strong> Select an image that captures the artistic style you want to apply.
    </td>
  </tr>
  <tr>
    <td rowspan="2" width="24%" valign="middle">
      <strong>AI Companions</strong>
    </td>
    <td width="60%" valign="middle">
      Summon virtual characters into your live camera feed and interact with them
      through gestures.
    </td>
    <td rowspan="2" width="16%" align="center" valign="middle">
      <a href="./docs/videos/use-cases/ai-companions.mp4">
        <img src="./docs/images/use-cases/ai-companions-poster.png" alt="Play the AI Companions demo" width="120">
        <br>
        <sub>▶ Play demo</sub>
      </a>
    </td>
  </tr>
  <tr>
    <td width="60%" valign="middle">
      <strong>Prompt:</strong> <code>指定角色在场景中互动</code>
      <br><br>
      <strong>Reference image:</strong> Select a clear image of the virtual character you want to summon with a clean background.
    </td>
  </tr>
  <tr>
    <td rowspan="2" width="24%" valign="middle">
      <strong>Live Photo</strong>
    </td>
    <td width="60%" valign="middle">
      Animate and control characters in your images simply by drawing motion
      trajectories.
    </td>
    <td rowspan="2" width="16%" align="center" valign="middle">
      <a href="./docs/videos/use-cases/live-photo.mp4">
        <img src="./docs/images/use-cases/live-photo-poster.png" alt="Play the Live Photo demo" width="120">
        <br>
        <sub>▶ Play demo</sub>
      </a>
    </td>
  </tr>
  <tr>
    <td width="60%" valign="middle">
      <strong>Prompt:</strong> <code>让画面自然动起来</code>
      <br><br>
      <strong>Reference image:</strong> Use the input image as the reference
    </td>
  </tr>
</table>

<br>

## Why XmaxSDK?

<table>
  <thead>
    <tr>
      <th height="104" align="center" valign="middle">
        <img src="./docs/images/why/low-latency.svg" alt="Low latency" width="36" height="36"><br>Low latency
      </th>
      <th height="104" align="center" valign="middle">
        <img src="./docs/images/why/low-cost.svg" alt="Cost efficiency" width="36" height="36"><br>Cost efficiency
      </th>
      <th height="104" align="center" valign="middle">
        <img src="./docs/images/why/high-fidelity.svg" alt="High fidelity" width="36" height="36"><br>High fidelity
      </th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>End-to-end latency is measured in <img src="./docs/images/why/latency-highlight.svg" alt="hundreds of milliseconds" width="192" height="20" align="absmiddle">, ensuring that updates to generation conditions and interaction controls are reflected instantly.</td>
      <td>Run on a <img src="./docs/images/why/gpu-highlight.svg" alt="single RTX 5090" width="126" height="20" align="absmiddle">, reducing inference costs by orders of magnitude versus datacenter GPUs like H100.</td>
      <td>Our models support real-time generation at up to <img src="./docs/images/why/resolution-highlight.svg" alt="1080p" width="48" height="20" align="absmiddle">, delivering production-ready, high-quality video output.</td>
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

XmaxSDK is distributed through [**Maven Central**](#maven-central).

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

The client uses the China service environment by default. To connect to the global
environment, select it when creating the client:

```kotlin
import ai.xmax.sdk.XmaxEnvironment

val globalClient = XmaxClient(
    context = applicationContext,
    configuration = XmaxConfiguration(
        apiKey = "YOUR_XMAX_API_KEY",
        environment = XmaxEnvironment.GLOBAL,
    ),
)
```

`XmaxEnvironment.CHINA` uses `https://cloud.xmax.22duck.cn/open/api/v1`, and
`XmaxEnvironment.GLOBAL` uses `https://api.xmax.cloud/open/api/v1`. Realtime and
storage API requests use the environment selected for their client.

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

A complete example application featuring a Jetpack Compose implementation
is available in
[`examples/XLab`](https://github.com/XingMai/XmaxSDK-Android/tree/main/examples/XLab).
It demonstrates real-time generation using live camera feeds, static images, and
local video files.

<p align="center"><img src="./docs/images/xlab/home.jpg" alt="X-Lab home" width="20%" /><img src="./docs/images/xlab/features.jpg" alt="X-Lab SDK features" width="20%" /><img src="./docs/images/xlab/storage.jpg" alt="X-Lab storage service" width="20%" /><img src="./docs/images/xlab/realtime-generation.jpg" alt="X-Lab realtime generation" width="20%" /><img src="./docs/images/xlab/trajectory-generation.jpg" alt="X-Lab trajectory generation" width="20%" /></p>

<br>

## Dependencies

- <ins><strong>VolcEngine RTC SDK</strong></ins> enables low-latency, real-time audio and video communication.
- <ins><strong>Tencent Cloud COS SDK</strong></ins> handles media upload and download through object storage.

<br>

## Contact us

For bug reports and feature requests, please open a
[GitHub Issue](https://github.com/XingMai/XmaxSDK-Android/issues). For integration
assistance and technical support, contact us at [sdk@xmax.ai](mailto:sdk@xmax.ai).

<br>

## License

XmaxSDK is available under the terms of the [MIT License](LICENSE).
