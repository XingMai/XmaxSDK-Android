# XmaxSDK for Android

XmaxSDK is the Android client SDK for Xmax real-time interactive video generation.
The Android implementation follows the public behavior and lifecycle defined by the
HarmonyOS SDK while using Kotlin and Jetpack Compose idioms.

> The Android port is in progress. Storage upload and download are available; realtime,
> rendering, and media generation APIs are still being ported.

## Project structure

```text
XmaxSDK/
├── xmax-sdk/          Android library published as ai.xmax:xmax-sdk
├── examples/
│   └── XLab/          Compose example app (com.xmax.xlab)
└── docs/
    └── PUBLISHING.md  Maven Central release notes
```

## Requirements

- Android 8.0 (API 26) or later
- JDK 17
- Android SDK 37
- Android Studio Quail or a compatible newer version

## Build

```bash
./gradlew build
```

Build the XLab example application:

```bash
./gradlew :examples:XLab:assembleDebug
```

## Storage

Create a client with an Android `Context`, then create a storage manager. Uploads use
short-lived credentials requested from Xmax; no Tencent Cloud secret is embedded in the
application.

```kotlin
val client = XmaxClient(
    context = applicationContext,
    configuration = XmaxConfiguration(apiKey = "YOUR_XMAX_API_KEY"),
)
val storage = client.createStorageManager()

val uploaded = storage.uploadImageFile(
    file = imageFile,
    contentType = "image/jpeg",
) { progress ->
    println(progress.fractionCompleted)
}

val referencePath = uploaded.url
```

`XmaxStorageManager` also supports image safety checks, video uploads, byte-array
uploads, streamed progress, and downloading images or videos to a local file.

## Planned installation

Once the first public version has been released to Maven Central:

```kotlin
dependencies {
    implementation("ai.xmax:xmax-sdk:<version>")
}
```

See [Publishing](docs/PUBLISHING.md) for the release setup.

## Security

Never commit an Xmax API key. XLab keeps the key only in memory during the current
process. Production applications should obtain short-lived credentials from their own
backend where possible.

## License

MIT License. See [LICENSE](LICENSE).
