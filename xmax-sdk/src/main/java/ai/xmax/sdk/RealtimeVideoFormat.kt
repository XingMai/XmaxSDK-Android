package ai.xmax.sdk

/** Neutral video dimensions and frame rate shared across platform adapters. */
public data class RealtimeVideoFormat(
    public val width: Int,
    public val height: Int,
    public val framesPerSecond: Int,
) {
    init {
        require(width > 0) { "Width must be greater than zero" }
        require(height > 0) { "Height must be greater than zero" }
        require(framesPerSecond > 0) { "Frame rate must be greater than zero" }
    }
}

