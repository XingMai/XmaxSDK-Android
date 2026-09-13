# 实时状态与错误契约

状态：2026-09-13 对齐 iOS 已提交的实时状态模型。整体并发和清理结构见[并发与取消架构](CONCURRENCY_DESIGN.md)。

## 单一状态入口

`XmaxRealtimeManaging.setStateListener()` 是异步生命周期和致命故障的唯一公共通知入口。`RealtimeState` 包含 `connectionState`、`sessionId`、`taskId` 和可空的 `reason`。新操作开始时清空旧原因。

| 状态 | 含义 |
| --- | --- |
| `IDLE` | 没有可用的本地媒体流。 |
| `PREPARING` | 正在准备本地媒体；摄像头等待有效首帧及预览绑定。 |
| `READY` | 本地媒体可预览，可建立新连接。 |
| `CONNECTING` | 正在创建服务端 Session、加入 RTC 房间并发布本地流。 |
| `CONNECTED` | 实时连接已建立，当前没有生成任务。 |
| `GENERATING` | 生成任务正在运行。 |
| `DISCONNECTING` | 正在清理生成、房间和 Session。 |

`RealtimeReason.Normal` 表示主动断开或关闭；`RealtimeReason.OrientationChanged` 留给方向变化触发的断开；`RealtimeReason.Failure(error)` 表示故障终止。正常开始新操作时 `reason == null`。`disconnect()` 后本地媒体仍在则进入 `READY`，否则进入 `IDLE`；`close()` 清理本地媒体并进入 `IDLE`。最终状态会保留最近一次 `sessionId`，清除 `taskId`。

调用中的失败仍由对应的 `suspend` 方法抛出。可恢复错误不结束当前有效生命周期，因此不发布 `Failure` 状态；调用者在 `catch` 中处理。致命失败先清理，再发布带 `Failure` 原因的最终状态；主动调用还会抛出原始错误。接入方若同时展示 `catch` 和状态原因，应自行避免重复提示。协程取消保留 `CancellationException`，不转换为业务失败状态。

```kotlin
realtime.setStateListener { state ->
    val failure = state.reason as? RealtimeReason.Failure
    if (failure != null) {
        showFatalError(failure.error.message)
    }
}

try {
    remoteStream = realtime.startGeneration(localStream, context)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: XmaxError) {
    if (error.severity == XmaxErrorSeverity.RECOVERABLE) {
        showOperationError(error.message)
    }
}
```

## 故障范围与时序

- 媒体故障释放本地媒体及其依赖资源，最终进入 `IDLE`。
- 连接或生成故障释放 Session 与 RTC 连接，保留可用的本地预览，最终进入 `READY`。
- 条件更新、音量设置等可恢复失败保留原有生成与连接。
- Session 心跳、RTC 房间终止等后台故障通过当前运行时身份检查后进入统一清理；旧运行时的迟到错误不会影响新连接。
- 清理时合并同时到达的终止请求。最终状态回调排到主线程前，协调器已解除旧操作占用，接入方可立即重试。
- 清理过程中的异常记诊断日志，不取代原始故障；用户状态监听器自身抛错也只记日志。

页面销毁时应主动调用 `setStateListener(null)`；`disconnect()` 和 `close()` 不会替接入方移除公共监听器。XLab 使用状态原因处理致命故障，上传参考图等非实时错误仍在各自调用边界处理。
