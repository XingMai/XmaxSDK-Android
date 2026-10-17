//
//  MediaFrameListener.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.VideoFrame

internal typealias MediaVideoFrameListener = (VideoFrame) -> Unit
internal typealias MediaAudioFrameListener = (AudioFrame) -> Unit
