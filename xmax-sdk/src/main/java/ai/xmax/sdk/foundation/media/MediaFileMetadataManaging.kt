//
//  MediaFileMetadataManaging.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media

import android.net.Uri

/**
 * 定义读取本地媒体文件元数据的能力。
 */
internal interface MediaFileMetadataManaging {
    /**
     * 读取媒体的显示尺寸、旋转方向、时长和音轨信息。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @return 媒体的尺寸、旋转、时长及音轨信息。
     */
    suspend fun readMetadata(uri: Uri): MediaFileMetadata
}
