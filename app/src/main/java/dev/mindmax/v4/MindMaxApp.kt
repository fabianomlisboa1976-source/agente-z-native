package dev.mindmax.v4

import android.app.Application
import dev.mindmax.v4.core.di.ServiceLocator
import dev.mindmax.v4.service.ServiceStarter

class MindMaxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Reconcile foreground-service state on every launch: starts when the
        // user has previously enabled it, stops when they haven't. Safe no-op
        // when the user has never toggled Settings.serviceEnabled.
        ServiceStarter.ensureStartedIfEnabled(this)
    }
}
