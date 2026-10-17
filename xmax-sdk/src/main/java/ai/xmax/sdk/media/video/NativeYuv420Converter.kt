//
//  NativeYuv420Converter.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.VideoRotation
import java.nio.ByteBuffer

/**
 * Android 平台内部的 YUV 热路径加速器；原生库不可用时由调用方回退到 Kotlin。
 */
internal object NativeYuv420Converter {
    /**
     * 本地转换器可用状态。
     */
    private val isAvailable = runCatching {
        System.loadLibrary("xmax_yuv")
        true
    }.getOrDefault(false)

    /**
     * 通过本地转换器裁剪、旋转并缩放 YUV420 帧；不可用时返回 false 供调用方回退。
     *
     * @param source 源图像的尺寸、裁剪区域及像素平面。
     * @param outputWidth 输出画面宽度，单位为像素。
     * @param outputHeight 输出画面高度，单位为像素。
     * @param rotation 应用到源画面的旋转方向。
     * @param y Y 像素平面数据。
     * @param u U 像素平面数据。
     * @param v V 像素平面数据。
     * @return 转换成功时为 true；不可用或失败时为 false。
     */
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

    /**
     * 将 I420 像素转换到调用方提供的 ARGB 数组。
     *
     * @param y Y 像素平面数据。
     * @param yStride Y 平面相邻行的字节距离。
     * @param u U 像素平面数据。
     * @param uStride U 平面相邻行的字节距离。
     * @param v V 像素平面数据。
     * @param vStride V 平面相邻行的字节距离。
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @param pixels 接收 ARGB 像素结果的数组。
     * @return 转换成功时为 true；不可用或失败时为 false。
     */
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

    /**
     * 按裁剪区域、旋转方向和输出尺寸转换 YUV420 数据。
     *
     * @param yBuffer Y 亮度平面的直接字节缓冲区。
     * @param yOffset Y 平面有效数据的起始字节偏移。
     * @param yRowStride Y 平面相邻行起点的字节距离。
     * @param yPixelStride Y 平面相邻像素的字节距离。
     * @param uBuffer U 色度平面的直接字节缓冲区。
     * @param uOffset U 平面有效数据的起始字节偏移。
     * @param uRowStride U 平面相邻行起点的字节距离。
     * @param uPixelStride U 平面相邻像素的字节距离。
     * @param vBuffer V 色度平面的直接字节缓冲区。
     * @param vOffset V 平面有效数据的起始字节偏移。
     * @param vRowStride V 平面相邻行起点的字节距离。
     * @param vPixelStride V 平面相邻像素的字节距离。
     * @param sourceWidth 源图像宽度，单位为像素。
     * @param sourceHeight 源图像高度，单位为像素。
     * @param cropLeft 裁剪区域左边界，单位为像素。
     * @param cropTop 裁剪区域上边界，单位为像素。
     * @param cropWidth 裁剪区域宽度，单位为像素。
     * @param cropHeight 裁剪区域高度，单位为像素。
     * @param outputWidth 输出画面宽度，单位为像素。
     * @param outputHeight 输出画面高度，单位为像素。
     * @param rotationDegrees 顺时针旋转角度，单位为度。
     * @param outputY 接收输出亮度数据的 Y 平面数组。
     * @param outputU 接收输出色度数据的 U 平面数组。
     * @param outputV 接收输出色度数据的 V 平面数组。
     * @return 转换成功时为 true，否则为 false。
     */
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

    /**
     * 调用本地转换器将 I420 像素写入 ARGB 数组。
     *
     * @param y Y 像素平面数据。
     * @param yStride Y 平面相邻行的字节距离。
     * @param u U 像素平面数据。
     * @param uStride U 平面相邻行的字节距离。
     * @param v V 像素平面数据。
     * @param vStride V 平面相邻行的字节距离。
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @param pixels 接收 ARGB 像素结果的数组。
     * @return 转换成功时为 true，否则为 false。
     */
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
