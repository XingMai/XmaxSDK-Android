# 实时致命错误回调契约

状态：已实施，2026-09-05。设计参考本机 `/Users/xmax.ai/dev/Xmax/iOS/XmaxSDK`，并补充 Android 的取消与资源归属保护。整体架构见 [并发与取消架构](CONCURRENCY_DESIGN.md)。

## 接入入口

仅保留原有的 `setErrorListener` 作为统一错误监听入口。只通知当前实时流程无法继续的 FATAL 错误，不把普通调用失败、取消或清理诊断都推给接入方。

`XmaxError` 保留原有构造签名，新增只读的 `severity` 属性及显式指定 severity 的构造重载：

- `code` 描述故障类别。
- `severity` 为 RECOVERABLE / FATAL，描述当前流程受到的影响。
- 协调器内部的操作身份和终止范围决定取消谁、清理什么；不以日志等级替代错误等级。

致命不表示应用崩溃或永远不能重试，而是本次媒体、连接或生成意图已经失败，需要重新准备或启动。

## 场景处理

| 场景 | 调用结果 / 日志 | 统一错误回调 | 保留资源 |
| --- | --- | --- | --- |
| 调用者取消、stop/close 撤销工作 | 原样抛 CancellationException | 无 | 按终止范围保留 |
| 参数、权限、API Key 配置错误、重复调用、流归属错误 | RECOVERABLE XmaxError | 无 | 已有有效资源 |
| prompt/参考条件更新失败 | RECOVERABLE XmaxError | 无 | 旧条件与当前生成 |
| 音量等辅助设置失败 | RECOVERABLE XmaxError | 无 | 当前实时流程 |
| 单次轨迹发送、可降级预览帧转换失败 | 内部日志 | 无 | 当前实时流程 |
| 上传参考图失败 | 存储方法返回错误 | 无 | 当前实时流程 |
| 必需媒体源建立失败、运行中的解码/输入管道异常退出 | FATAL | 一次 | 终止该媒体及其依赖资源 |
| 必要 Session/RTC 连接无法建立 | FATAL；调用同时抛错 | 一次 | 可用本地媒体 |
| start 信令、SEI 或远端流首帧等待失败 | FATAL；调用同时抛错 | 一次 | 已提交连接与本地媒体 |
| Session 心跳决定终止、RTC 明确被踢出/解散/凭证失效 | FATAL | 一次 | 可用本地媒体 |
| stopGeneration/disconnect/close 的清理失败 | 日志；继续释放其他资源 | 无 | 只有可安全复用的资源 |

MEDIA/RTC 错误的默认等级是 FATAL；参数、权限、未配置密钥、取消与不支持能力默认 RECOVERABLE。条件更新和设置在各自业务边界明确降级。媒体工作循环抛出的管道异常会终止循环；普通供应商重连告警不能直接升级为致命错误。

RTC 房间状态采用明确终止原因表，自动重试进房/重连告警保持内部处理。该区分参考供应商[房间状态回调](https://www.volcengine.com/docs/6348/1390576?lang=zh)及[错误与警告码](https://www.volcengine.com/docs/6427/70097)，枚举与工程锁定的 RTC AAR 核对。未知状态在初次入房由失败/超时路径兜底；已连接时不把未知告警贸然当成会话终止。

## 唯一出口与时序

1. 组件返回错误或向其所属 runtime 提交故障。
2. manager 根据业务阶段调整等级；取消先于分类处理。
3. FATAL 进入协调器，合并终止范围，撤销受影响操作。
4. 独立清理任务等待工作任务及回滚退出，释放对应资源。
5. 提交最终状态，再由 `RealtimeCallbacks` 在 Main 上通知注册的错误监听。

清理中的关联故障合并在同一个终止记录中；清理完成后的同一失败状态不会再次报告。新的明确启动可以开启下一次流程。心跳检查 sessionId，RTC 检查本地实例身份，旧 runtime 的错误出口不能影响新 runtime。

当前实现选择在有界远端关闭尝试结束后发出错误通知，使接入方收到回调时清理屏障已完成。它与初版提案中“先通知、后台继续远端关闭”的时序不同：没有遗留远端删除任务，也便于立即重试。Session.close 的协程预算是 5 秒；底层阻塞 I/O 退出仍受实际传输实现及超时约束。

生成级 FATAL 保留已提交连接，状态为 ERROR 并保留 sessionId；后续 startGeneration 可以重用连接。连接级 FATAL 清理会话；媒体级 FATAL 清理整个 runtime。

监听替换或 close 会使尚未派发的旧注册失效。已经开始执行的用户回调无法撤回。监听器自身抛异常仅记日志，不再次触发 fatal，不影响清理。

## suspend 与回调的接入方式

致命的主动调用失败同时抛错与回调。抛错完成该次调用，回调提供统一的致命故障处理入口。接入层按 severity 去重：

```kotlin
realtime.setErrorListener { error ->
    // Main 线程；统一处理致命错误提示。
    showFatalError(error.message)
}

try {
    remoteStream = realtime.startGeneration(localStream, generationContext)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: XmaxError) {
    if (error.severity == XmaxErrorSeverity.RECOVERABLE) {
        showOperationError(error.message)
    }
} finally {
    generationLoading = false
}
```

上面的错误分支只用于 realtime 调用。上传/输入准备应在自己的调用边界提示错误，不能因为存储错误的 severity 而等待实时回调。

XLab 已实现这个接入方式；源切换或后台恢复调用 close 后，会重新注册统一错误监听。参考图上传复用实时页面的 XmaxClient，避免第二个客户端改变全局日志选项。

## iOS 对照

| 本机 iOS 文件 | 已核对行为 |
| --- | --- |
| Foundation/Errors/XmaxError.swift | recoverable/fatal、severity、withSeverity |
| Core/Realtime/RealtimeErrorHandler.swift | 统一记录日志，仅 fatal 交给监听器 |
| Service/Realtime/RealtimeError.swift | MainActor 监听契约 |
| Stream/Room/RoomController.swift | start 失败致命；条件更新、停止和轨迹失败可恢复 |
| Service/Realtime/RealtimeSessionService.swift | 会话关闭失败可恢复；终止性心跳失败致命 |

iOS Handler 本身没有完整的操作身份/资源去重机制；Android 在协调器和资源所有者中实现这些保证。本次阅读了 iOS 源码与测试，没有改动 iOS 工程或执行其测试。

## 验证

`RealtimeCoordinatorTest` 和 `XmaxRealtimeManagerTest` 覆盖等级过滤、fatal 同时抛错和通知、重复故障合并、取消不通知、监听替换/异常、生成失败保留连接并重试，以及心跳清理不在故障任务中自取消。`RtcManagerTest` 验证已连接房间的终止故障与重连告警区分。
