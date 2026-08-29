package ai.xmax.sdk.rendering.trajectory

import ai.xmax.sdk.RealtimeVideoTrack
import java.util.concurrent.ConcurrentHashMap

/** 保存远端视频轨道与轨迹交互能力之间的绑定。 */
internal object TrajectoryRegistry {
    private val bindings = ConcurrentHashMap<RealtimeVideoTrack, TrajectoryBinding>()

    fun register(track: RealtimeVideoTrack, binding: TrajectoryBinding) {
        bindings.put(track, binding)?.invalidate()
    }

    fun unregister(track: RealtimeVideoTrack) {
        bindings.remove(track)?.invalidate()
    }

    fun binding(track: RealtimeVideoTrack): TrajectoryBinding? = bindings[track]
}
