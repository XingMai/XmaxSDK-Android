package ai.xmax.sdk.media.audio

import ai.xmax.sdk.AudioFrame
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** 播放视频文件解码出的 48 kHz 单声道 PCM 本地预览。 */
internal class LocalAudioPreviewPlayer {
    private var audioTrack: AudioTrack? = null
    private var muted = false
    private var volume = 1f

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

    @Synchronized
    fun enqueue(frame: AudioFrame) {
        val data = frame.dataBytes()
        audioTrack?.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
    }

    @Synchronized
    fun setMuted(muted: Boolean) {
        this.muted = muted
        audioTrack?.setVolume(if (muted) 0f else volume)
    }

    @Synchronized
    fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        audioTrack?.setVolume(if (muted) 0f else this.volume)
    }

    @Synchronized
    fun stop() {
        val track = audioTrack ?: return
        audioTrack = null
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        track.release()
    }
}
