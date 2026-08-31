package ai.xmax.sdk.media.video

import ai.xmax.sdk.VideoRotation
import java.nio.ByteBuffer

/** Android 平台内部的 YUV 热路径加速器；原生库不可用时由调用方回退到 Kotlin。 */
internal object NativeYuv420Converter {
    private val isAvailable = runCatching {
        System.loadLibrary("xmax_yuv")
        true
    }.getOrDefault(false)

    fun convert(
        source: Yuv420VideoFrameConverter.Source,
        outputWidth: Int,
        outputHeight: Int,
        rotation: VideoRotation,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
    ): Boolean {
        if (!isAvailable || source.planes.any { !it.buffer.isDirect }) return false
        val yPlane = source.planes[0]
        val uPlane = source.planes[1]
        val vPlane = source.planes[2]
        return runCatching {
            convertNative(
                yBuffer = yPlane.buffer,
                yOffset = yPlane.baseOffset,
                yRowStride = yPlane.rowStride,
                yPixelStride = yPlane.pixelStride,
                uBuffer = uPlane.buffer,
                uOffset = uPlane.baseOffset,
                uRowStride = uPlane.rowStride,
                uPixelStride = uPlane.pixelStride,
                vBuffer = vPlane.buffer,
                vOffset = vPlane.baseOffset,
                vRowStride = vPlane.rowStride,
                vPixelStride = vPlane.pixelStride,
                sourceWidth = source.width,
                sourceHeight = source.height,
                cropLeft = source.cropLeft,
                cropTop = source.cropTop,
                cropWidth = source.cropWidth,
                cropHeight = source.cropHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                rotationDegrees = rotation.degrees,
                outputY = y,
                outputU = u,
                outputV = v,
            )
        }.getOrDefault(false)
    }

    fun convertI420ToArgb(
        y: ByteArray,
        yStride: Int,
        u: ByteArray,
        uStride: Int,
        v: ByteArray,
        vStride: Int,
        width: Int,
        height: Int,
        pixels: IntArray,
    ): Boolean = isAvailable && runCatching {
        convertI420ToArgbNative(
            y = y,
            yStride = yStride,
            u = u,
            uStride = uStride,
            v = v,
            vStride = vStride,
            width = width,
            height = height,
            pixels = pixels,
        )
    }.getOrDefault(false)

    private external fun convertNative(
        yBuffer: ByteBuffer,
        yOffset: Int,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: ByteBuffer,
        uOffset: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: ByteBuffer,
        vOffset: Int,
        vRowStride: Int,
        vPixelStride: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        rotationDegrees: Int,
        outputY: ByteArray,
        outputU: ByteArray,
        outputV: ByteArray,
    ): Boolean

    private external fun convertI420ToArgbNative(
        y: ByteArray,
        yStride: Int,
        u: ByteArray,
        uStride: Int,
        v: ByteArray,
        vStride: Int,
        width: Int,
        height: Int,
        pixels: IntArray,
    ): Boolean

}
