package ai.xmax.sdk.rendering.video

import ai.xmax.sdk.RealtimeVideoTrack
import java.util.concurrent.ConcurrentHashMap

/** 保存实时视频轨道与底层渲染能力之间的内部绑定关系。 */
internal object VideoRenderRegistry {
    private val bindings = ConcurrentHashMap<RealtimeVideoTrack, VideoRenderBinding>()

    fun register(track: RealtimeVideoTrack, binding: VideoRenderBinding) {
        bindings[track] = binding
    }

    fun unregister(track: RealtimeVideoTrack) {
        bindings.remove(track)
    }

    fun binding(track: RealtimeVideoTrack): VideoRenderBinding? = bindings[track]
}
