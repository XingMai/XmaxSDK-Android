package ai.xmax.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 实时生成画面的 Compose 组件，内部使用 [XmaxRealtimeVideoView]。
 * 保留本地预览，远端首帧显示后自动淡入；清空远端轨道后回到本地预览。
 * 参数随重组更新；离开组合时解绑轨道，不启动、停止或释放媒体流。
 *
 * @param localTrack 本地输入轨道；null 清空本地预览。
 * @param remoteTrack 远端生成轨道；null 显示本地预览。
 * @param modifier 控制组件的尺寸、布局及其他 Compose 修饰。
 * @param videoContentMode 同时应用于本地和远端的等比缩放模式，默认填充并裁切。
 * @param isInteractionEnabled 是否允许远端画面显示后的触控轨迹交互。
 * @param trajectoryRenderer 远端轨迹视觉效果；null 使用 SDK 内置效果。
 */
@Composable
public fun XmaxRealtimeVideo(
    localTrack: RealtimeVideoTrack?,
    remoteTrack: RealtimeVideoTrack?,
    modifier: Modifier = Modifier,
    videoContentMode: VideoContentMode = VideoContentMode.FILL,
    isInteractionEnabled: Boolean = true,
    trajectoryRenderer: TrajectoryEffectRendering? = null,
) {
    AndroidView(
        factory = { context -> XmaxRealtimeVideoView(context) },
        modifier = modifier,
        update = { view ->
            view.videoContentMode = videoContentMode
            view.isInteractionEnabled = isInteractionEnabled
            if (view.trajectoryRenderer !== trajectoryRenderer) {
                view.trajectoryRenderer = trajectoryRenderer
            }
            view.localTrack = localTrack
            view.remoteTrack = remoteTrack
        },
        onRelease = { view ->
            view.isInteractionEnabled = false
            view.remoteTrack = null
            view.localTrack = null
            view.trajectoryRenderer = null
        },
    )
}
