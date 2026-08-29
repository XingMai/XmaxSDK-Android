@file:Suppress("DEPRECATION")

package com.xmax.xlab.modules.xlrealtime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xmax.xlab.R
import kotlin.math.min

/** 与 iOS XLab 一致的实时预览 Loading 蒙层。 */
internal class RealtimeLoadingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private var isLoading = false
    private var transitionVersion = 0L
    private val loadingImageView = RealtimeLoadingGifView(context)
    private val fallbackIndicator = ProgressBar(context).apply {
        indeterminateTintList = ColorStateList.valueOf(Color.argb(219, 255, 255, 255))
        visibility = View.GONE
    }

    init {
        setBackgroundColor(Color.argb(184, 0, 0, 0))
        visibility = View.GONE
        alpha = 0f
        isClickable = false
        isFocusable = false

        addView(
            loadingImageView,
            LayoutParams(54.dp, 50.dp, Gravity.CENTER),
        )
        addView(
            fallbackIndicator,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    fun startLoading() {
        if (isLoading) return
        isLoading = true
        transitionVersion += 1
        animate().cancel()
        animate().setListener(null)
        visibility = View.VISIBLE

        if (loadingImageView.canAnimate) {
            fallbackIndicator.visibility = View.GONE
            loadingImageView.visibility = View.VISIBLE
            loadingImageView.startAnimating()
        } else {
            loadingImageView.visibility = View.GONE
            fallbackIndicator.visibility = View.VISIBLE
        }

        animate()
            .alpha(1f)
            .setDuration(TRANSITION_DURATION_MILLIS)
            .start()
    }

    fun hideLoading() {
        if (!isLoading && visibility == View.GONE) return
        isLoading = false
        transitionVersion += 1
        val version = transitionVersion
        animate().cancel()
        loadingImageView.stopAnimating()
        fallbackIndicator.visibility = View.GONE

        animate()
            .alpha(0f)
            .setDuration(TRANSITION_DURATION_MILLIS)
            .setListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (version == transitionVersion) visibility = View.GONE
                    }
                },
            )
            .start()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TRANSITION_DURATION_MILLIS = 300L
    }
}

@Composable
internal fun RealtimeLoadingView(
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> RealtimeLoadingOverlay(context) },
        update = { view ->
            if (isLoading) {
                view.startLoading()
            } else {
                view.hideLoading()
            }
        },
        modifier = modifier,
    )
}

private class RealtimeLoadingGifView(context: Context) : View(context) {
    private val movie = resources.openRawResource(R.raw.realtime_loading).use(Movie::decodeStream)
    private var isAnimating = false
    private var animationStartedAt = 0L

    val canAnimate: Boolean
        get() = movie != null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun startAnimating() {
        if (!canAnimate || isAnimating) return
        isAnimating = true
        animationStartedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun stopAnimating() {
        isAnimating = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val animatedMovie = movie ?: return
        val movieWidth = animatedMovie.width().takeIf { it > 0 } ?: return
        val movieHeight = animatedMovie.height().takeIf { it > 0 } ?: return
        if (isAnimating) {
            val duration = animatedMovie.duration().takeIf { it > 0 } ?: DEFAULT_FRAME_DURATION_MILLIS
            val elapsed = (SystemClock.uptimeMillis() - animationStartedAt) % duration
            animatedMovie.setTime(elapsed.toInt())
        }

        val scale = min(width.toFloat() / movieWidth, height.toFloat() / movieHeight)
        val renderedWidth = movieWidth * scale
        val renderedHeight = movieHeight * scale
        canvas.save()
        canvas.translate((width - renderedWidth) / 2f, (height - renderedHeight) / 2f)
        canvas.scale(scale, scale)
        animatedMovie.draw(canvas, 0f, 0f)
        canvas.restore()

        if (isAnimating) postInvalidateOnAnimation()
    }

    private companion object {
        const val DEFAULT_FRAME_DURATION_MILLIS = 100
    }
}
