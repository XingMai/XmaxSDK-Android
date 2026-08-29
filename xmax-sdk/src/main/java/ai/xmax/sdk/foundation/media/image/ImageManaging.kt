package ai.xmax.sdk.foundation.media.image

import android.graphics.Bitmap

/** 定义 SDK 内部使用的平台图片解码能力。 */
internal interface ImageManaging {
    fun decode(data: ByteArray): DecodedImage

    fun decode(bitmap: Bitmap): DecodedImage
}
