package com.gohj99.telewatch.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import com.gohj99.telewatch.utils.proxy.CloudflareProxy

internal object WatchActivityPolicy : Application.ActivityLifecycleCallbacks {
    private const val SCREEN_IDLE_MS = 45_000L
    private const val BACKGROUND_GRACE_MS = 750L
    private val handler = Handler(Looper.getMainLooper())
    private val windows = mutableMapOf<Activity, IdleWindow>()
    private val resumed = mutableSetOf<Activity>()
    private var wasForeground = false
    @Volatile var isForeground = false
        private set

    private val background = Runnable { if (resumed.isEmpty()) isForeground = false }

    override fun onActivityResumed(activity: Activity) {
        handler.removeCallbacks(background)
        val returning = wasForeground && !isForeground
        isForeground = true
        wasForeground = true
        resumed.add(activity)
        val window = activity.window
        val callback = window.callback
        if (callback != null && window.attributes.flags and FLAG_KEEP_SCREEN_ON == 0) {
            windows[activity] = IdleWindow(window, callback).also {
                window.callback = it
                it.interact()
            }
        }
        if (returning) CloudflareProxy.onForeground()
    }

    override fun onActivityPaused(activity: Activity) {
        windows.remove(activity)?.release()
        resumed.remove(activity)
        if (resumed.isEmpty()) handler.postDelayed(background, BACKGROUND_GRACE_MS)
    }

    private class IdleWindow(private val window: Window, private val delegate: Window.Callback) : Window.Callback by delegate {
        private var lastInteraction = 0L
        private var holding = false
        private val expire = object : Runnable {
            override fun run() {
                val remaining = lastInteraction + SCREEN_IDLE_MS - SystemClock.uptimeMillis()
                if (remaining > 0) handler.postDelayed(this, remaining) else {
                    holding = false
                    window.clearFlags(FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        fun interact() {
            lastInteraction = SystemClock.uptimeMillis()
            if (!holding) {
                holding = true
                window.addFlags(FLAG_KEEP_SCREEN_ON)
                handler.postDelayed(expire, SCREEN_IDLE_MS)
            }
        }

        fun release() {
            handler.removeCallbacks(expire)
            if (holding) window.clearFlags(FLAG_KEEP_SCREEN_ON)
            if (window.callback === this) window.callback = delegate
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            interact()
            return delegate.dispatchTouchEvent(event)
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) interact()
            return delegate.dispatchGenericMotionEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            interact()
            return delegate.dispatchKeyEvent(event)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) { windows.remove(activity)?.release() }
}
