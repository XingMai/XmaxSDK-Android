package ai.xmax.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 显示单个本地或远端视频轨道的 Compose 组件，内部使用 [XmaxVideoView]。
 * 参数随重组更新；离开组合时解绑轨道，不启动、停止或释放媒体流。
 *
 * @param track 要显示的视频轨道；null 清空画面。
 * @param modifier 控制组件的尺寸、布局及其他 Compose 修饰。
 * @param videoContentMode 视频等比缩放模式，默认填充并裁切。
 * @param isInteractionEnabled 是否允许远端轨道的触控轨迹交互。
 * @param trajectoryRenderer 自定义轨迹视觉效果；null 使用 SDK 内置效果。
 */
@Composable
public fun XmaxVideo(
    track: RealtimeVideoTrack?,
    modifier: Modifier = Modifier,
    videoContentMode: VideoContentMode = VideoContentMode.FILL,
    isInteractionEnabled: Boolean = true,
    trajectoryRenderer: TrajectoryEffectRendering? = null,
) {
    AndroidView(
        factory = { context -> XmaxVideoView(context) },
        modifier = modifier,
        update = { view ->
            view.videoContentMode = videoContentMode
            view.isInteractionEnabled = isInteractionEnabled
            if (view.trajectoryRenderer !== trajectoryRenderer) {
                view.trajectoryRenderer = trajectoryRenderer
            }
            view.track = track
        },
        onRelease = { view ->
            view.isInteractionEnabled = false
            view.track = null
            view.trajectoryRenderer = null
        },
    )
}
