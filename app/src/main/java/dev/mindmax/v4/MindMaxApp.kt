package dev.mindmax.v4

import android.app.Application
import android.util.Log
import dev.mindmax.v4.core.di.ServiceLocator
import dev.mindmax.v4.service.ServiceStarter

class MindMaxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        try {
            ServiceLocator.init(this)
        } catch (t: Throwable) {
            // Don't let init failures take the whole app down — log and let the
            // UI surface the error rather than a black screen of death.
            Log.e(TAG, "ServiceLocator.init failed", t)
        }
        // Reconcile foreground-service state on every launch: starts when the
        // user has previously enabled it, stops when they haven't. Runs off the
        // main thread (the previous runBlocking implementation could ANR the
        // Application bootstrap and would have killed the BootReceiver broadcast
        // if it ever ran there).
        ServiceStarter.ensureStartedIfEnabledAsync(this)
    }

    /**
     * Captures uncaught exceptions in a single log line so a crash report can
     * surface the root cause from `adb logcat`. Android still kills the
     * process afterwards; this only adds observability.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private companion object {
        const val TAG = "MindMaxApp"
    }
}
