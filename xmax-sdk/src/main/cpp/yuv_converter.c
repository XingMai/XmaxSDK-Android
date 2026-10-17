//
//  yuv_converter.c
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

/**
 * 描述可安全访问的像素平面及其内存布局。
 */
typedef struct {
    /**
     * 像素数据的地址、可访问容量及有效起点。
     */
    const uint8_t *data;
    jlong capacity;
    jint offset;

    /**
     * 相邻行与相邻像素之间的字节距离。
     */
    jint row_stride;
    jint pixel_stride;
} Plane;

/**
 * 将颜色通道值限制在有效的八位范围内。
 *
 * @param value 待限制的颜色通道值。
 * @return 位于 0 到 255 范围内的通道值。
 */
static jint clamp_color(jint value) {
    if (value < 0) return 0;
    if (value > 255) return 255;
    return value;
}

/**
 * 检查像素平面是否能安全访问指定的最远坐标。
 *
 * @param plane 待校验的像素平面。
 * @param maximum_x 需要读取的最大横坐标，单位为像素。
 * @param maximum_y 需要读取的最大纵坐标，单位为像素。
 * @return 数据、步长和坐标均有效且未越界时为 JNI_TRUE，否则为 JNI_FALSE。
 */
static jboolean valid_plane(
    const Plane *plane,
    jint maximum_x,
    jint maximum_y
) {
    if (plane->data == NULL || plane->capacity <= 0 || plane->offset < 0 ||
        plane->row_stride <= 0 || plane->pixel_stride <= 0 ||
        maximum_x < 0 || maximum_y < 0) {
        return JNI_FALSE;
    }
    const jlong last = (jlong) plane->offset +
        (jlong) maximum_y * plane->row_stride +
        (jlong) maximum_x * plane->pixel_stride;
    return last < plane->capacity ? JNI_TRUE : JNI_FALSE;
}

/**
 * 将显示坐标转换为应用裁剪和旋转前的源图像坐标。
 *
 * @param display_x 显示画面横坐标。
 * @param display_y 显示画面纵坐标。
 * @param crop_left 裁剪区域左边界。
 * @param crop_top 裁剪区域上边界。
 * @param crop_width 裁剪区域宽度。
 * @param crop_height 裁剪区域高度。
 * @param rotation 顺时针旋转角度，支持 0、90、180、270 度。
 * @param source_x 接收源图像横坐标的输出指针。
 * @param source_y 接收源图像纵坐标的输出指针。
 */
static inline void source_coordinate(
    jint display_x,
    jint display_y,
    jint crop_left,
    jint crop_top,
    jint crop_width,
    jint crop_height,
    jint rotation,
    jint *source_x,
    jint *source_y
) {
    switch (rotation) {
        case 90:
            *source_x = crop_left + display_y;
            *source_y = crop_top + crop_height - 1 - display_x;
            break;
        case 180:
            *source_x = crop_left + crop_width - 1 - display_x;
            *source_y = crop_top + crop_height - 1 - display_y;
            break;
        case 270:
            *source_x = crop_left + crop_width - 1 - display_y;
            *source_y = crop_top + display_x;
            break;
        default:
            *source_x = crop_left + display_x;
            *source_y = crop_top + display_y;
            break;
    }
}

/**
 * 将直接缓冲区中的 YUV420 图像裁剪、旋转并等比填充到目标 I420 尺寸。
 *
 * @param env 当前线程的 JNI 环境。
 * @param instance 发起调用的 Kotlin 单例，本方法不访问该对象。
 * @param y_buffer Y 平面的直接缓冲区。
 * @param y_offset Y 平面有效数据的字节偏移。
 * @param y_row_stride Y 平面相邻行的字节距离。
 * @param y_pixel_stride Y 平面相邻像素的字节距离。
 * @param u_buffer U 平面的直接缓冲区。
 * @param u_offset U 平面有效数据的字节偏移。
 * @param u_row_stride U 平面相邻行的字节距离。
 * @param u_pixel_stride U 平面相邻像素的字节距离。
 * @param v_buffer V 平面的直接缓冲区。
 * @param v_offset V 平面有效数据的字节偏移。
 * @param v_row_stride V 平面相邻行的字节距离。
 * @param v_pixel_stride V 平面相邻像素的字节距离。
 * @param source_width 源图像宽度，单位为像素。
 * @param source_height 源图像高度，单位为像素。
 * @param crop_left 裁剪区域左边界，单位为像素。
 * @param crop_top 裁剪区域上边界，单位为像素。
 * @param crop_width 裁剪区域宽度，单位为像素。
 * @param crop_height 裁剪区域高度，单位为像素。
 * @param output_width 输出宽度，必须为正偶数。
 * @param output_height 输出高度，必须为正偶数。
 * @param rotation 顺时针旋转角度，支持 0、90、180、270 度。
 * @param output_y 接收连续 Y 平面数据的数组。
 * @param output_u 接收连续 U 平面数据的数组。
 * @param output_v 接收连续 V 平面数据的数组。
 * @return 转换成功时为 JNI_TRUE；参数无效、内存不足或缓冲不可访问时为 JNI_FALSE。
 */
JNIEXPORT jboolean JNICALL
Java_ai_xmax_sdk_media_video_NativeYuv420Converter_convertNative(
    JNIEnv *env,
    jobject instance,
    jobject y_buffer,
    jint y_offset,
    jint y_row_stride,
    jint y_pixel_stride,
    jobject u_buffer,
    jint u_offset,
    jint u_row_stride,
    jint u_pixel_stride,
    jobject v_buffer,
    jint v_offset,
    jint v_row_stride,
    jint v_pixel_stride,
    jint source_width,
    jint source_height,
    jint crop_left,
    jint crop_top,
    jint crop_width,
    jint crop_height,
    jint output_width,
    jint output_height,
    jint rotation,
    jbyteArray output_y,
    jbyteArray output_u,
    jbyteArray output_v
) {
    (void) instance;
    if (source_width <= 0 || source_height <= 0 || crop_left < 0 || crop_top < 0 ||
        crop_width <= 0 || crop_height <= 0 ||
        crop_left + crop_width > source_width || crop_top + crop_height > source_height ||
        output_width <= 0 || output_height <= 0 ||
        (output_width & 1) != 0 || (output_height & 1) != 0 ||
        (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) ||
        output_y == NULL || output_u == NULL || output_v == NULL) {
        return JNI_FALSE;
    }

    const jint chroma_width = output_width / 2;
    const jint chroma_height = output_height / 2;
    if ((*env)->GetArrayLength(env, output_y) < output_width * output_height ||
        (*env)->GetArrayLength(env, output_u) < chroma_width * chroma_height ||
        (*env)->GetArrayLength(env, output_v) < chroma_width * chroma_height) {
        return JNI_FALSE;
    }

    Plane y_plane = {
        .data = (*env)->GetDirectBufferAddress(env, y_buffer),
        .capacity = (*env)->GetDirectBufferCapacity(env, y_buffer),
        .offset = y_offset,
        .row_stride = y_row_stride,
        .pixel_stride = y_pixel_stride,
    };
    Plane u_plane = {
        .data = (*env)->GetDirectBufferAddress(env, u_buffer),
        .capacity = (*env)->GetDirectBufferCapacity(env, u_buffer),
        .offset = u_offset,
        .row_stride = u_row_stride,
        .pixel_stride = u_pixel_stride,
    };
    Plane v_plane = {
        .data = (*env)->GetDirectBufferAddress(env, v_buffer),
        .capacity = (*env)->GetDirectBufferCapacity(env, v_buffer),
        .offset = v_offset,
        .row_stride = v_row_stride,
        .pixel_stride = v_pixel_stride,
    };
    const jint maximum_x = crop_left + crop_width - 1;
    const jint maximum_y = crop_top + crop_height - 1;
    if (!valid_plane(&y_plane, maximum_x, maximum_y) ||
        !valid_plane(&u_plane, maximum_x / 2, maximum_y / 2) ||
        !valid_plane(&v_plane, maximum_x / 2, maximum_y / 2)) {
        return JNI_FALSE;
    }

    jint *target_x = malloc((size_t) output_width * sizeof(jint));
    jint *target_y = malloc((size_t) output_height * sizeof(jint));
    if (target_x == NULL || target_y == NULL) {
        free(target_x);
        free(target_y);
        return JNI_FALSE;
    }

    const jboolean swaps_dimensions = rotation == 90 || rotation == 270;
    const jint display_width = swaps_dimensions ? crop_height : crop_width;
    const jint display_height = swaps_dimensions ? crop_width : crop_height;
    const double width_scale = (double) output_width / display_width;
    const double height_scale = (double) output_height / display_height;
    const double scale = width_scale > height_scale ? width_scale : height_scale;
    const double display_left = (display_width - output_width / scale) / 2.0;
    const double display_top = (display_height - output_height / scale) / 2.0;
    for (jint x = 0; x < output_width; ++x) {
        jint mapped = (jint) (display_left + (x + 0.5) / scale);
        if (mapped < 0) mapped = 0;
        if (mapped >= display_width) mapped = display_width - 1;
        target_x[x] = mapped;
    }
    for (jint y = 0; y < output_height; ++y) {
        jint mapped = (jint) (display_top + (y + 0.5) / scale);
        if (mapped < 0) mapped = 0;
        if (mapped >= display_height) mapped = display_height - 1;
        target_y[y] = mapped;
    }

    jbyte *y_result = (*env)->GetByteArrayElements(env, output_y, NULL);
    jbyte *u_result = (*env)->GetByteArrayElements(env, output_u, NULL);
    jbyte *v_result = (*env)->GetByteArrayElements(env, output_v, NULL);
    if (y_result == NULL || u_result == NULL || v_result == NULL) {
        if (v_result != NULL) (*env)->ReleaseByteArrayElements(env, output_v, v_result, 0);
        if (u_result != NULL) (*env)->ReleaseByteArrayElements(env, output_u, u_result, 0);
        if (y_result != NULL) (*env)->ReleaseByteArrayElements(env, output_y, y_result, 0);
        free(target_x);
        free(target_y);
        return JNI_FALSE;
    }

    jint output_index = 0;
    for (jint y = 0; y < output_height; ++y) {
        const jint display_y = target_y[y];
        for (jint x = 0; x < output_width; ++x) {
            jint source_x;
            jint source_y;
            source_coordinate(
                target_x[x], display_y, crop_left, crop_top, crop_width, crop_height,
                rotation, &source_x, &source_y
            );
            y_result[output_index++] = (jbyte) y_plane.data[
                y_plane.offset + source_y * y_plane.row_stride + source_x * y_plane.pixel_stride
            ];
        }
    }

    output_index = 0;
    for (jint y = 0; y < chroma_height; ++y) {
        const jint luma_y = y * 2 + 1 < output_height ? y * 2 + 1 : output_height - 1;
        const jint display_y = target_y[luma_y];
        for (jint x = 0; x < chroma_width; ++x) {
            const jint luma_x = x * 2 + 1 < output_width ? x * 2 + 1 : output_width - 1;
            jint source_x;
            jint source_y;
            source_coordinate(
                target_x[luma_x], display_y, crop_left, crop_top, crop_width, crop_height,
                rotation, &source_x, &source_y
            );
            source_x /= 2;
            source_y /= 2;
            u_result[output_index] = (jbyte) u_plane.data[
                u_plane.offset + source_y * u_plane.row_stride + source_x * u_plane.pixel_stride
            ];
            v_result[output_index] = (jbyte) v_plane.data[
                v_plane.offset + source_y * v_plane.row_stride + source_x * v_plane.pixel_stride
            ];
            ++output_index;
        }
    }

    (*env)->ReleaseByteArrayElements(env, output_v, v_result, 0);
    (*env)->ReleaseByteArrayElements(env, output_u, u_result, 0);
    (*env)->ReleaseByteArrayElements(env, output_y, y_result, 0);
    free(target_x);
    free(target_y);
    return JNI_TRUE;
}

/**
 * 将 I420 像素转换为可供 Android Bitmap 使用的 ARGB 像素。
 *
 * @param env 当前线程的 JNI 环境。
 * @param instance 发起调用的 Kotlin 单例，本方法不访问该对象。
 * @param y_data Y 亮度平面数据。
 * @param y_stride Y 平面相邻行的字节距离。
 * @param u_data U 色度平面数据。
 * @param u_stride U 平面相邻行的字节距离。
 * @param v_data V 色度平面数据。
 * @param v_stride V 平面相邻行的字节距离。
 * @param width 图像宽度，单位为像素。
 * @param height 图像高度，单位为像素。
 * @param output_pixels 接收 ARGB 像素的数组，长度至少为 width × height。
 * @return 转换成功时为 JNI_TRUE，参数无效或数组不可访问时为 JNI_FALSE。
 */
JNIEXPORT jboolean JNICALL
Java_ai_xmax_sdk_media_video_NativeYuv420Converter_convertI420ToArgbNative(
    JNIEnv *env,
    jobject instance,
    jbyteArray y_data,
    jint y_stride,
    jbyteArray u_data,
    jint u_stride,
    jbyteArray v_data,
    jint v_stride,
    jint width,
    jint height,
    jintArray output_pixels
) {
    (void) instance;
    if (y_data == NULL || u_data == NULL || v_data == NULL || output_pixels == NULL ||
        width <= 0 || height <= 0 || y_stride < width ||
        u_stride < (width + 1) / 2 || v_stride < (width + 1) / 2 ||
        (*env)->GetArrayLength(env, y_data) < y_stride * height ||
        (*env)->GetArrayLength(env, u_data) < u_stride * ((height + 1) / 2) ||
        (*env)->GetArrayLength(env, v_data) < v_stride * ((height + 1) / 2) ||
        (*env)->GetArrayLength(env, output_pixels) < width * height) {
        return JNI_FALSE;
    }

    jbyte *y = (*env)->GetByteArrayElements(env, y_data, NULL);
    jbyte *u = (*env)->GetByteArrayElements(env, u_data, NULL);
    jbyte *v = (*env)->GetByteArrayElements(env, v_data, NULL);
    jint *pixels = (*env)->GetIntArrayElements(env, output_pixels, NULL);
    if (y == NULL || u == NULL || v == NULL || pixels == NULL) {
        if (pixels != NULL) (*env)->ReleaseIntArrayElements(env, output_pixels, pixels, 0);
        if (v != NULL) (*env)->ReleaseByteArrayElements(env, v_data, v, JNI_ABORT);
        if (u != NULL) (*env)->ReleaseByteArrayElements(env, u_data, u, JNI_ABORT);
        if (y != NULL) (*env)->ReleaseByteArrayElements(env, y_data, y, JNI_ABORT);
        return JNI_FALSE;
    }

    jint output_index = 0;
    for (jint row = 0; row < height; ++row) {
        const jint y_row = row * y_stride;
        const jint chroma_row = (row / 2) * u_stride;
        const jint chroma_v_row = (row / 2) * v_stride;
        for (jint column = 0; column < width; ++column) {
            const jint luma = ((uint8_t) y[y_row + column]) - 16;
            const jint chroma_u = ((uint8_t) u[chroma_row + column / 2]) - 128;
            const jint chroma_v = ((uint8_t) v[chroma_v_row + column / 2]) - 128;
            const jint normalized_luma = luma > 0 ? luma : 0;
            const jint red = clamp_color((298 * normalized_luma + 409 * chroma_v + 128) >> 8);
            const jint green = clamp_color(
                (298 * normalized_luma - 100 * chroma_u - 208 * chroma_v + 128) >> 8
            );
            const jint blue = clamp_color((298 * normalized_luma + 516 * chroma_u + 128) >> 8);
            pixels[output_index++] = (jint) (
                0xFF000000u | ((uint32_t) red << 16) | ((uint32_t) green << 8) | (uint32_t) blue
            );
        }
    }

    (*env)->ReleaseIntArrayElements(env, output_pixels, pixels, 0);
    (*env)->ReleaseByteArrayElements(env, v_data, v, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, u_data, u, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, y_data, y, JNI_ABORT);
    return JNI_TRUE;
}
