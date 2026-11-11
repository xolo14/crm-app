package com.syncpedia.crm.utils

import android.app.Activity
import android.app.Application
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.syncpedia.crm.R

/**
 * Android 15+ forces edge-to-edge for targetSdk 35+, and the deprecated statusBarColor /
 * navigationBarColor no longer apply. Every screen gets its content inset away from the
 * system bars, with the bar areas painted in brand colors behind them.
 */
object EdgeToEdge {

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is ComponentActivity) apply(activity)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun apply(activity: ComponentActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val topColor = ContextCompat.getColor(activity, R.color.bg_toolbar)
        // Match the bottom bar to the screen's own background (light pages vs. dark call/login screens).
        val bottomColor = (content.getChildAt(0)?.background as? ColorDrawable)?.color
            ?: ContextCompat.getColor(activity, R.color.nav_bg)
        val lightBottom = ColorUtils.calculateLuminance(bottomColor) > 0.5

        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = if (lightBottom) {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            } else {
                SystemBarStyle.dark(Color.TRANSPARENT)
            }
        )

        val barsBackground = SystemBarBackground(topColor, bottomColor)
        activity.window.decorView.background = barsBackground

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // Include the keyboard so adjustResize screens (login, search) still lift their fields.
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            view.setPadding(bars.left, bars.top, bars.right, bottom)
            barsBackground.update(bars.top, bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
    }

    /** Paints the status-bar strip in the toolbar color and everything else in the page color. */
    private class SystemBarBackground(
        private val topColor: Int,
        private val bottomColor: Int
    ) : Drawable() {
        private val paint = Paint()
        private var topInset = 0

        fun update(top: Int, @Suppress("UNUSED_PARAMETER") bottom: Int) {
            topInset = top
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            canvas.drawColor(bottomColor)
            paint.color = topColor
            val b = bounds
            canvas.drawRect(
                b.left.toFloat(), b.top.toFloat(),
                b.right.toFloat(), (b.top + topInset).toFloat(), paint
            )
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
