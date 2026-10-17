//
//  RealtimeState.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * 实时业务连接状态。
 *
 * @param value 需要规范化、校验或显示的文本。
 */
public enum class RealtimeConnectionState(public val value: String) {
    /**
     * 尚无可用本地媒体，或全部资源已关闭。
     */
    IDLE("Idle"),

    /**
     * 正在准备本地媒体输入。
     */
    PREPARING("Preparing"),

    /**
     * 本地媒体已就绪，尚未连接生成服务。
     */
    READY("Ready"),

    /**
     * 正在创建会话并加入 RTC 房间。
     */
    CONNECTING("Connecting"),

    /**
     * 连接已建立，可以启动生成。
     */
    CONNECTED("Connected"),

    /**
     * 当前生成结果已可显示。
     */
    GENERATING("Generating"),

    /**
     * 正在终止生成并释放连接资源。
     */
    DISCONNECTING("Disconnecting"),
}

/**
 * 进入当前实时状态的原因。
 */
public sealed interface RealtimeReason {
    /**
     * 主动停止或正常释放资源。
     */
    public data object Normal : RealtimeReason

    /**
     * 显示方向变化，需要重新配置生成。
     */
    public data object OrientationChanged : RealtimeReason

    /**
     * 操作或运行异常导致当前流程结束。
     *
     * @param error 本次操作失败的异常或 SDK 错误。
     */
    public data class Failure(public val error: XmaxError) : RealtimeReason
}

/**
 * 实时业务当前状态快照。
 *
 * @param connectionState 当前媒体和生成连接的状态。
 * @param sessionId 服务端会话标识。
 * @param taskId 用于关联本次生成请求和结果流的任务标识。
 * @param reason 引起状态变化或失败的原因。
 */
public data class RealtimeState(
    public val connectionState: RealtimeConnectionState,
    public val sessionId: String? = null,
    public val taskId: String? = null,

    /**
     * 进入当前状态的原因；开始新操作时清空。
     */
    public val reason: RealtimeReason? = null,
)

/**
 * 实时状态监听器。
 */
public fun interface RealtimeStateListener {
    /**
     * 通知管理器最新的实时状态快照。
     *
     * @param state 当前状态快照。
     */
    public fun onStateChanged(state: RealtimeState)
}
