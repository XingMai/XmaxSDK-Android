//
//  LocalAudioPreviewPlayer.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.audio

import ai.xmax.sdk.AudioFrame
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * 播放视频文件解码出的 48 kHz 单声道 PCM 本地预览。
 */
internal class LocalAudioPreviewPlayer {
    /**
     * 本地 PCM 播放资源。
     */
    private var audioTrack: AudioTrack? = null

    /**
     * 本地预览的静音与音量配置。
     */
    private var muted = false
    private var volume = DEFAULT_VOLUME

    /**
     * 启动用于本地预览的音频播放队列。
     */
    @Synchronized
    fun start() {
        if (audioTrack != null) return
        val minimumBufferSize = AudioTrack.getMinBufferSize(
            AudioFrame.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(AudioFrame.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(minimumBufferSize, AudioFrame.samplesPerFrame * Short.SIZE_BYTES * 4),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Failed to initialize local audio preview")
        }
        track.setVolume(if (muted) 0f else volume)
        track.play()
        audioTrack = track
    }

    /**
     * 将 PCM 音频帧加入本地预览播放队列。
     *
     * @param frame 待播放或提交的 PCM 音频帧。
     */
    @Synchronized
    fun enqueue(frame: AudioFrame) {
        val data = frame.dataBytes()
        audioTrack?.write(data, 0, data.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    /**
     * 设置本地音频预览是否静音。
     *
     * @param muted 是否静音。
     */
    @Synchronized
    fun setMuted(muted: Boolean) {
        this.muted = muted
        audioTrack?.setVolume(if (muted) 0f else volume)
    }

    /**
     * 设置本地音频预览音量。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    @Synchronized
    fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        audioTrack?.setVolume(if (muted) 0f else this.volume)
    }

    /**
     * 停止本地音频预览并释放音频输出资源。
     */
    @Synchronized
    fun stop() {
        val track = audioTrack ?: return
        audioTrack = null
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        track.release()
    }

    /**
     * 提供 LocalAudioPreviewPlayer 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val DEFAULT_VOLUME = 0.45f
    }
}
