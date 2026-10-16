package com.vuforia.engine.native_sample.notifications

import java.util.concurrent.atomic.AtomicInteger

object GlimpAppForeground {
    private val resumedActivities = AtomicInteger(0)

    val isForeground: Boolean
        get() = resumedActivities.get() > 0

    fun onActivityStarted() {
        resumedActivities.incrementAndGet()
    }

    fun onActivityStopped() {
        resumedActivities.updateAndGet { current -> if (current > 0) current - 1 else 0 }
    }
}
