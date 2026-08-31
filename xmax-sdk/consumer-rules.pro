# VolcEngine RTC probes Honor's optional hardware-earback extension at runtime.
# The extension is absent on other devices and must not make consumer R8 builds fail.
-dontwarn com.hihonor.android.magicx.media.audio.interfaces.**

# JNI entry points use the class and method names exported by libxmax_yuv.so.
-keep class ai.xmax.sdk.media.video.NativeYuv420Converter {
    native <methods>;
}
