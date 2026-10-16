# Android 外部摄像头采集接入

接入方可以自己打开摄像头、显示预览、处理画面，再把视频帧交给 XmaxSDK 进行实时生成，不需要调用 `createLocalCameraStream()`。

本文以**接入方自行预览、仅输入视频**为例。采集器可以使用自己的相机实现；SDK 不限定采集框架。

> 版本说明：本文针对当前源码，包含声网 RTC、`X2_1_1_PREVIEW_CN` / `X2_1_1_PREVIEW_US` 和 `RealtimeExternalVideoSource` 的默认实现，尚未发版。Maven Central 的 `1.0.8` 仍使用火山 RTC 和旧模型，且需要显式实现 `hasAudio = false`，以及三个预览／音量空方法。`createExternalVideoStream()` 本身已在 `1.0.7` 提供。

## 职责划分

| 接入方负责 | SDK 负责 |
| --- | --- |
| 摄像头权限、打开和关闭、前后摄像头切换 | 创建生成会话、RTC 连接和发送视频 |
| 本地预览 | 接收远端生成结果并提供渲染轨道 |
| 旋转、裁剪、缩放和 RGBA 转换 | 校验模型输入规格，向适配器提供最终供帧规格 |
| 持续提交视频帧和报告采集错误 | 解绑后丢弃迟到帧，清理 SDK 的会话和媒体资源 |

`RealtimeExternalVideoSource` 是连接两者的适配器，不是摄像头，也不是视频帧。

最少只需要实现三个成员：

- `videoFormat`：声明源视频的显示规格。
- `start(sink)`：由 SDK 调用，保存供帧入口。
- `stop()`：由 SDK 调用，清除供帧入口，停止向 SDK 送帧。

默认不提供音频、不挂载本地预览、不控制本地监听音量。

## 1. 实现外部摄像头源

下面的适配器可以复用。它不包含相机初始化或图像转换算法；转换由接入方通过 `rgbaForFormat` 提供。

```kotlin
import ai.xmax.sdk.RealtimeExternalFrameSink
import ai.xmax.sdk.RealtimeExternalVideoSource
import ai.xmax.sdk.RealtimeVideoFormat
import kotlinx.coroutines.CancellationException

class CameraSource(
    override val videoFormat: RealtimeVideoFormat,
) : RealtimeExternalVideoSource {

    @Volatile
    private var sink: RealtimeExternalFrameSink? = null

    override suspend fun start(sink: RealtimeExternalFrameSink) {
        this.sink = sink
    }

    override suspend fun stop() {
        // 幂等：重复调用仍然安全，不在这里关闭接入方摄像头。
        sink = null
    }

    // 接入方在后台采集回调中调用，不要把图像转换放到主线程。
    fun submitFrame(
        timestampUs: Long,
        rgbaForFormat: (RealtimeVideoFormat) -> ByteArray,
    ) {
        // 保存本次绑定，避免转换完成后把旧帧送给重新绑定的新任务源。
        val target = sink ?: return
        if (!target.isRequestingFrames) return

        try {
            val rgba = rgbaForFormat(target.videoFormat)

            // 转换期间可能已经解绑，或者停止了生成。
            if (sink !== target || !target.isRequestingFrames) return

            target.pushVideo(rgba, timestampUs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            target.reportError(error)
        }
    }

    fun reportCaptureError(error: Throwable) {
        sink?.reportError(error)
    }
}
```

`start/stop` 的含义是**绑定／解绑 SDK 供帧**，不是开始／停止生成，也不是接管摄像头设备。不要手动调用它们，由 SDK 管理。

## 2. 创建 Manager 和外部流

以下片段在 Activity 中执行；挂起方法放在接入方管理的协程中。变量在后续片段中复用。

```kotlin
import ai.xmax.sdk.RealtimeConfiguration
import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxClient
import ai.xmax.sdk.XmaxConfiguration

val client = XmaxClient(
    context = applicationContext,
    configuration = XmaxConfiguration(apiKey = "YOUR_XMAX_API_KEY"),
)

val realtime = client.createRealtimeManager(
    options = RealtimeConfiguration(model = RealtimeModel.X2_1_1_PREVIEW_CN),
)

val outputFormat = RealtimeVideoFormat(
    width = 1024,
    height = 1920,
    fps = 30,
)

// 示例假设摄像头经方向校正后的源画面为 1080 × 1920。
val source = CameraSource(
    videoFormat = RealtimeVideoFormat(1080, 1920, 30),
)

// 在协程中调用：SDK 会调用 source.start(sink)。
val localStream = realtime.createExternalVideoStream(
    source = source,
    videoFormat = outputFormat,
)
```

`source.videoFormat` 描述源画面，`createExternalVideoStream` 的 `videoFormat` 指定期望输出。最终始终以 `sink.videoFormat` 为准，SDK 不替接入方转换像素。

当前仅支持 `x2.1.1-preview-cn` 和 `x2.1.1-preview-us`，均通过声网传输，仅接受 `1024×1920` 或 `1920×1024`。相机输出不符合模型规格时，需要指定合法输出格式并自行转换，不能直接提交原始尺寸。

## 3. 持续提供相机帧

接入方获得权限后打开摄像头，并把帧回调接到 `source.submitFrame()`。以下是**伪代码**：`camera`、`frame` 和 `convertToRgba` 都是接入方自己的采集／转换实现，不是 XmaxSDK API。

```kotlin
camera.onFrame { frame ->
    val timestampUs = SystemClock.elapsedRealtimeNanos() / 1_000L

    try {
        source.submitFrame(timestampUs) { targetFormat ->
            convertToRgba(
                frame = frame,
                outputWidth = targetFormat.width,
                outputHeight = targetFormat.height,
            )
        }
    } finally {
        // 即使 SDK 当前不需要帧，也必须归还采集缓冲区。
        frame.close()
    }
}
```

转换和供帧必须满足：

- 视频数据为逐行、从上到下排列的 RGBA，每像素 4 字节。
- 数组长度恰好为 `width × height × 4`，每行没有额外 padding。
- SDK 当前没有直接接收 YUV、相机图像对象或 GPU 纹理的这个公共入口；这些输入需要先转换。
- 旋转、镜像、缩放和裁剪由接入方处理，不会从原始帧自动读取方向信息。应保持输入结果与预览的坐标关系一致。
- `timestampUs` 是微秒，使用同一单调时钟，持续递增。不要使用会被校时修改的系统日期时间。
- 同一视频源建议串行转换和提交，避免并行处理导致帧乱序；时间戳重复或回退的帧会被丢弃。
- 按目标帧率安排供帧；`isRequestingFrames` 不是限帧器。处理跟不上时丢弃过期帧，不积压无界队列。
- `pushVideo` 返回后可以复用数组，但调用期间不能从其他线程修改其内容。

**只有预览、尚未生成时，`sink.isRequestingFrames` 通常为 `false`，这是正常行为。** 此时仍可保持摄像头和自有预览，跳过为 SDK 做的转换工作。

## 4. 开始生成并显示结果

```kotlin
import ai.xmax.sdk.RealtimeContext

// 在协程中执行；此前摄像头已启动，帧回调持续运行。
val remoteStream = realtime.startGeneration(
    localStream = localStream,
    context = RealtimeContext(
        prompt = "把画面转换成动漫风格",
        // referencePath = "https://...", // 如需参考图，传入已上传的地址。
    ),
)

// remoteVideoView 是 UI 中的 XmaxVideoView；在主线程绑定。
remoteVideoView.track = remoteStream.videoTrack
```

**不能等 `startGeneration()` 返回后才开始采集或供帧。** 该调用会等待结果流确认和首帧就绪；等待期间没有输入，可能最终出现生成启动超时。

本例采用两个独立视图：接入方自己的摄像头预览，以及显示生成结果的 `XmaxVideoView`。返回本地预览时清空远端轨道并隐藏远端视图，避免黑色背景遮挡摄像头。

不实现 `attachPreview/detachPreview` 时，把本地轨道设置到 SDK 视频容器中不会自动出现相机画面。如果希望使用 `XmaxRealtimeVideoView` 的本地／远端切换，需要额外实现这两个方法，把自己的预览挂载进传入的容器。

## 5. 取消生成、解绑与退出

| 操作 | SDK 行为 | 接入方需要做什么 |
| --- | --- | --- |
| `disconnect()` | 结束生成和连接，保留外部源绑定 | 可继续自己的摄像头预览；隐藏远端结果 |
| `disconnect()` 后再 `startGeneration(localStream, context)` | 复用仍有效的外部流重新生成 | 保持供帧回调可用 |
| `disconnect()` 后再 `stopExternalVideoStream()` | 解绑供帧，调用 `source.stop()` | 旧 `localStream` 不再可用，需要时重新创建；摄像头是否继续由接入方决定 |
| `close()` | 取消在途操作，关闭连接并解绑外部源 | 自行停止摄像头、释放采集与预览资源 |

退出页面时，应在仍可完成清理的协程中等待 `close()`，随后释放自己的摄像头。即使 SDK 清理报错，也要通过 `finally` 释放应用持有的采集资源；不要在一个已经取消的页面作用域中仅启动清理任务就直接离开。

切换前后摄像头时，不调用 SDK 的 `switchCamera()`。由接入方切换设备，并继续输出符合当前 `sink.videoFormat` 的画面。如果需要替换整个外部源或修改绑定的输出规格，可以使用 `replaceExternalVideoStream(videoFormat) { newSource }`。

## 可选能力

### 外部音频

需要音频时覆盖 `hasAudio = true`，接入方自行采集并通过 `sink.pushAudio()` 提交：

- 48kHz，单声道，PCM16LE。
- 每包 480 个采样，即 10ms、960 字节。
- 音视频时间戳使用同一单调时钟基准，并保持各自递增。

设置 `hasAudio = true` 不会让 SDK 自动打开麦克风。若有本地监听，可覆盖 `setPreviewAudio()`；它只改变本地听到的声音，不能改变送给 SDK 的 PCM 音量。

### 采集异常

设备异常退出、不可恢复的采集或转换错误，通过 `source.reportCaptureError(error)` 转交给 `sink.reportError()`。SDK 会按媒体错误处理并清理相关资源；接入方自己的摄像头仍需自行释放。

计划中的摄像头切换、页面退出不应当作采集故障上报。重新绑定时使用新的 `sink`，旧绑定的迟到帧和错误会被忽略。

## 排查顺序

1. 确认已经获得摄像头权限，采集回调正在持续触发。
2. 确认 `source.start(sink)` 已被 SDK 调用；不要自行创建 sink。
3. 生成启动期间检查 `sink.isRequestingFrames`，而不是只在本地预览时检查。
4. 检查最终 RGBA 数组长度、实际宽高、颜色通道和方向是否正确。
5. 检查时间戳单位、递增性和转换耗时，确认没有帧队列积压。
6. 若报 `Realtime generation start timed out`，除本地供帧外，还需要检查 RTC 上行、服务端生成和目标机器人视频发布／订阅；声网不依赖 SEI 确认。该错误不等同于“相机没打开”。
