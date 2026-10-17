//
//  TrajectoryRegistry.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.trajectory

import ai.xmax.sdk.RealtimeVideoTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * 保存远端视频轨道与轨迹交互能力之间的绑定。
 */
internal object TrajectoryRegistry {
    /**
     * 轨道与显示组件的绑定记录。
     */
    private val bindings = ConcurrentHashMap<RealtimeVideoTrack, TrajectoryBinding>()

    /**
     * 为视频轨道登记对应的显示或交互绑定。
     *
     * @param track 需要显示或绑定的视频轨道。
     * @param binding 视频轨道对应的显示或交互绑定。
     */
    fun register(track: RealtimeVideoTrack, binding: TrajectoryBinding) {
        bindings.put(track, binding)?.invalidate()
    }

    /**
     * 移除视频轨道的显示或交互绑定。
     *
     * @param track 需要显示或绑定的视频轨道。
     */
    fun unregister(track: RealtimeVideoTrack) {
        bindings.remove(track)?.invalidate()
    }

    /**
     * 查询视频轨道对应的显示或交互绑定。
     *
     * @param track 需要显示或绑定的视频轨道。
     * @return 轨道对应的轨迹绑定；未注册时为 null。
     */
    fun binding(track: RealtimeVideoTrack): TrajectoryBinding? = bindings[track]
}
