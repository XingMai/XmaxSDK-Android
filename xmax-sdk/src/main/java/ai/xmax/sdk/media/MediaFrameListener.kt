package ai.xmax.sdk.media

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.VideoFrame

internal typealias MediaVideoFrameListener = (VideoFrame) -> Unit
internal typealias MediaAudioFrameListener = (AudioFrame) -> Unit
